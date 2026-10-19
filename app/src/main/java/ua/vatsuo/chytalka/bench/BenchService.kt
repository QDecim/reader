package ua.vatsuo.chytalka.bench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for the long benchmark: PARTIAL_WAKE_LOCK keeps the CPU running with the screen off.
 * Type: mediaPlayback only when sound is really played (mode D, or "play generated"), otherwise specialUse.
 */
class BenchService : Service() {
    companion object {
        private const val STOP = "bench_stop"
        private const val CH = "chytalka_bench"
        private const val NOTIF = 2

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, BenchService::class.java))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, BenchService::class.java).setAction(STOP)) }
    }

    private val stopFlag = AtomicBoolean(false)
    @Volatile private var running = false
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == STOP) { stopFlag.set(true); BenchState.log("Зупинка…"); return START_NOT_STICKY }
        val cfg = BenchState.config
        if (running || cfg == null) {
            if (cfg == null) { goForeground(null); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        running = true; stopFlag.set(false)
        goForeground(cfg)
        wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Chytalka:bench")
            .apply { acquire(6 * 3600_000L) } // safety timeout
        BenchState.clear()
        BenchState.ui.update { it.copy(running = true) }
        Thread({
            try { BenchRunner(applicationContext, cfg, stopFlag).run() }
            finally {
                runCatching { wl?.release() }
                BenchState.ui.update { it.copy(running = false) }
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "bench-runner").start()
        return START_NOT_STICKY
    }

    private fun goForeground(cfg: BenchConfig?) {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Тест StyleTTS2", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH)
            .setContentTitle("Тест StyleTTS2")
            .setContentText(cfg?.mode?.label ?: "")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) {
            val audible = cfg != null && (cfg.play || cfg.mode == BenchMode.PLAYBACK)
            startForeground(NOTIF, n, if (audible) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIF, n)   // <34: types are not enforced, manifest declaration is enough
    }

    override fun onDestroy() { runCatching { wl?.release() }; super.onDestroy() }
}
