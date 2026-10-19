package ua.vatsuo.chytalka.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.os.PowerManager
import java.io.File
import java.io.Writer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Battery(private val ctx: Context) {
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    /** µAh left in the battery (drops while discharging). */
    fun chargeUah(): Long = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
    /** µA, the sign convention differs between devices. */
    fun currentUa(): Long = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    fun pct(): Int = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    private fun sticky(): Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    fun tempC(): Float = (sticky()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    fun plugged(): Boolean = (sticky()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}

object Mem {
    fun vmHwmMb(): Double = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }
            .filter { it.isDigit() }.toDouble() / 1024.0
    }.getOrDefault(Double.NaN)

    fun nativePssMb(): Double {
        val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi); return mi.nativePss / 1024.0
    }
}

class Thermal(ctx: Context, private val onChange: (String) -> Unit) {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    @Volatile var maxStatus = 0; private set
    private val listener = PowerManager.OnThermalStatusChangedListener { s ->
        if (s > maxStatus) maxStatus = s
        onChange("Термо-статус: ${name(s)}")
    }
    private val exec = Executors.newSingleThreadExecutor()

    fun start() { if (Build.VERSION.SDK_INT >= 29) pm.addThermalStatusListener(exec, listener) }
    fun stop() { if (Build.VERSION.SDK_INT >= 29) pm.removeThermalStatusListener(listener); exec.shutdownNow() }
    fun status(): Int = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1

    companion object {
        fun name(s: Int) = when (s) {
            0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "?"
        }
    }
}

/**
 * Reads battery current every second (integrated into mAh: the charge counter of some phones only moves in 1% steps),
 * and writes a CSV row every [rowEvery] seconds: battery/thermal/memory.
 */
class Sampler(
    private val battery: Battery, private val thermal: Thermal, private val out: Writer,
    private val t0Nanos: Long, private val rowEvery: Int = 5,
) {
    private val ex = Executors.newSingleThreadScheduledExecutor()
    val charges = CopyOnWriteArrayList<Long>()
    private val absRaw = ArrayList<Double>()          // |CURRENT_NOW| every second, raw units (guarded by this)
    private var prevRaw = 0.0; private var prevNs = 0L
    private var integRaw = 0.0                        // raw units * seconds
    private var winSum = 0.0; private var winN = 0
    private var ticks = 0
    @Volatile var maxTemp = Float.NEGATIVE_INFINITY; private set
    @Volatile var peakHwmMb = 0.0; private set
    @Volatile var peakPssMb = 0.0; private set

    init { out.write(Csv.row("t_s", "charge_uah", "current_ua", "current_avg_raw", "pct", "temp_c", "thermal", "vmhwm_mb", "native_pss_mb") + "\n") }

    fun start() { ex.scheduleAtFixedRate({ runCatching { tick(false) } }, 0, 1, TimeUnit.SECONDS) }

    @Synchronized private fun tick(last: Boolean) {
        val now = SystemClock.elapsedRealtimeNanos()
        var cur = battery.currentUa()
        if (cur == Long.MIN_VALUE || cur == Int.MIN_VALUE.toLong()) cur = 0   // "not supported"
        val raw = Math.abs(cur.toDouble())
        if (prevNs != 0L) integRaw += (prevRaw + raw) / 2 * ((now - prevNs) / 1e9)
        prevRaw = raw; prevNs = now
        absRaw.add(raw); winSum += raw; winN++
        val c = battery.chargeUah(); charges.add(c)
        if (last || ticks % rowEvery == 0) {
            val temp = battery.tempC(); val hwm = Mem.vmHwmMb(); val pss = Mem.nativePssMb()
            if (temp > maxTemp) maxTemp = temp
            if (hwm > peakHwmMb) peakHwmMb = hwm
            if (pss > peakPssMb) peakPssMb = pss
            val avg = if (winN > 0) winSum / winN else 0.0
            winSum = 0.0; winN = 0
            out.write(Csv.row((now - t0Nanos) / 1e9, c, cur, avg, battery.pct(), temp, Thermal.name(thermal.status()), hwm, pss) + "\n")
            out.flush()
        }
        ticks++
    }

    fun stop() { ex.shutdownNow(); ex.awaitTermination(2, TimeUnit.SECONDS); runCatching { tick(true) } }

    /** true if CURRENT_NOW looks like mA (see CurrentMath) */
    @Synchronized fun milli(): Boolean = CurrentMath.isMilli(absRaw)
    @Synchronized fun hasCurrent(): Boolean = absRaw.any { it > 0 }
    /** charge drawn since start, from the integrated current, mAh */
    @Synchronized fun mAh(): Double = CurrentMath.mAh(integRaw, CurrentMath.isMilli(absRaw))
    /** average current over [wallS] seconds, mA */
    fun avgMa(wallS: Double): Double = if (wallS <= 0) Double.NaN else mAh() * 3600.0 / wallS

    /** smallest non-zero change of the charge counter = its effective granularity (µAh) */
    fun granularityUah(): Long = charges.zipWithNext { a, b -> Math.abs(a - b) }.filter { it > 0 }.minOrNull() ?: 0L
}
