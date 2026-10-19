package ua.vatsuo.chytalka.bench

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On opening the bench screen: (1) finds runs that never wrote a summary (process was killed, e.g. by the vendor
 * battery manager) and rebuilds the summary from the CSV files, with Android's own record of how the process died;
 * (2) offers the newest run's files for sharing, so nobody has to browse folders.
 */
object BenchRecovery {
    private val stampRe = Regex("""^bench_(\d{8}_\d{6})(_samples|_summary|_error)?\.(csv|txt)$""")

    fun check(ctx: Context) {
        if (BenchState.ui.value.running) return
        val dir = ctx.getExternalFilesDir(null) ?: return
        val prefs = ctx.getSharedPreferences("chytalka_bench", Context.MODE_PRIVATE)
        val markerStamp = prefs.getString("unfinished_stamp", null)
        val stamps = (dir.listFiles() ?: emptyArray()).mapNotNull { stampRe.find(it.name)?.groupValues?.get(1) }.toSortedSet()

        for (st in stamps) {
            val samples = File(dir, "bench_${st}_samples.csv"); val sum = File(dir, "bench_${st}_summary.txt")
            if (!samples.exists() || sum.exists()) continue
            val sent = File(dir, "bench_$st.csv")
            val ours = st == markerStamp
            val modeLabel = if (ours) prefs.getString("unfinished_mode", null) else null
            val cap = if (ours) prefs.getInt("unfinished_cap", 0) else prefs.getInt("capacity", 0)
            val bg = prefs.getFloat("bg_ma", Float.NaN).toDouble()
            val r = runCatching {
                Recovery.build(st, modeLabel, samples.readText(), if (sent.exists()) sent.readText() else "", cap, bg, exitInfo(ctx))
            }.getOrNull() ?: continue
            sum.writeText(r.summary)
            // an idle run (mode C) that ran long enough still gives a usable background current
            if (modeLabel == BenchMode.IDLE.label && r.wallS >= 180 && !r.avgMa.isNaN() && r.avgMa > 0)
                prefs.edit().putFloat("bg_ma", r.avgMa.toFloat()).putFloat("bg_minutes", (r.wallS / 60).toFloat()).apply()
        }
        prefs.edit().remove("unfinished_stamp").apply()

        // newest run -> share list + summary text
        val latest = stamps.lastOrNull() ?: return
        val files = listOf("bench_$latest.csv", "bench_${latest}_samples.csv", "bench_${latest}_summary.txt", "bench_${latest}_error.txt")
            .map { File(dir, it) }.filter { it.exists() }
        val summary = File(dir, "bench_${latest}_summary.txt").takeIf { it.exists() }?.readText() ?: ""
        BenchState.ui.update { if (it.running || it.files.isNotEmpty()) it else it.copy(summary = summary, files = files.map { f -> f.absolutePath }) }
    }

    private fun exitInfo(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 4).joinToString("\n") {
                "  ${fmt.format(Date(it.timestamp))} причина=${reasonName(it.reason)} статус=${it.status} важливість=${it.importance} опис=${it.description}"
            }.ifEmpty { null }
        }.getOrNull()
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (свайп/Force stop)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER (часто: менеджер батареї виробника)"
        else -> "код $r"
    }
}
