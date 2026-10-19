package ua.vatsuo.chytalka.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
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

/** Every [periodS] seconds writes battery/thermal/memory to the samples CSV. */
class Sampler(
    private val battery: Battery, private val thermal: Thermal, private val out: Writer,
    private val t0Nanos: Long, private val periodS: Long = 5,
) {
    private val ex = Executors.newSingleThreadScheduledExecutor()
    val charges = CopyOnWriteArrayList<Long>()
    @Volatile var maxTemp = Float.NEGATIVE_INFINITY; private set
    @Volatile var peakHwmMb = 0.0; private set
    @Volatile var peakPssMb = 0.0; private set

    init { out.write(Csv.row("t_s", "charge_uah", "current_ua", "pct", "temp_c", "thermal", "vmhwm_mb", "native_pss_mb") + "\n") }

    fun start() { ex.scheduleAtFixedRate({ runCatching { sample() } }, 0, periodS, TimeUnit.SECONDS) }

    @Synchronized fun sample() {
        val t = (System.nanoTime() - t0Nanos) / 1e9
        val c = battery.chargeUah(); val temp = battery.tempC()
        val hwm = Mem.vmHwmMb(); val pss = Mem.nativePssMb()
        charges.add(c)
        if (temp > maxTemp) maxTemp = temp
        if (hwm > peakHwmMb) peakHwmMb = hwm
        if (pss > peakPssMb) peakPssMb = pss
        out.write(Csv.row(t, c, battery.currentUa(), battery.pct(), temp, Thermal.name(thermal.status()), hwm, pss) + "\n")
        out.flush()
    }

    fun stop() { ex.shutdownNow(); ex.awaitTermination(2, TimeUnit.SECONDS); runCatching { sample() } }

    /** smallest non-zero change of the charge counter = its effective granularity (µAh) */
    fun granularityUah(): Long = charges.zipWithNext { a, b -> Math.abs(a - b) }.filter { it > 0 }.minOrNull() ?: 0L
}
