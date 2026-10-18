package ua.vatsuo.chytalka

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val bookId: String = "",
    val title: String = "",
    val items: List<Item> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val engine: EngineKind = EngineKind.SYSTEM,
    val sid: Int = 0,
    val sleepMin: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
)

object Player {
    val state = MutableStateFlow(UiState())
    fun update(f: (UiState) -> UiState) = state.update(f)
}

class PlayerService : Service() {
    companion object {
        const val PLAY = "play"; const val PAUSE = "pause"; const val TOGGLE = "toggle"
        const val NEXT = "next"; const val PREV = "prev"; const val SEEK = "seek"
        const val SPEED = "speed"; const val ENGINE = "engine"; const val SLEEP = "sleep"; const val STOP = "stop"; const val SID = "sid"
        private const val CH = "chytalka"; private const val NOTIF = 1

        fun send(ctx: Context, action: String, extra: Number? = null) {
            val i = Intent(ctx, PlayerService::class.java).setAction(action)
            when (extra) { is Int -> i.putExtra("v", extra); is Float -> i.putExtra("f", extra) }
            ctx.startService(i) // UI is visible, so this is allowed; service goes foreground only in play()
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var engine: TtsEngine? = null
    private var engineKind: EngineKind? = null
    private var token = 0
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private val sleepRunnable = Runnable { Player.update { it.copy(sleepMin = 0) }; pause() }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { pause() }
    }
    private var noisyOn = false
    private var fg = false
    private var shown = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Читалка", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "Chytalka").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { step(1) }
                override fun onSkipToPrevious() { step(-1) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            PLAY -> play()
            PAUSE -> pause()
            TOGGLE -> if (Player.state.value.playing) pause() else play()
            NEXT -> step(1)
            PREV -> step(-1)
            SEEK -> seek(i.getIntExtra("v", 0))
            SPEED -> { Player.update { it.copy(speed = i.getFloatExtra("f", 1f)) }; restartIfPlaying() }
            SID -> { Player.update { it.copy(sid = i.getIntExtra("v", 0)) }; restartIfPlaying() }
            ENGINE -> {
                Player.update { it.copy(engine = EngineKind.values()[i.getIntExtra("v", 0)]) }
                restartIfPlaying()
            }
            SLEEP -> setSleep(i.getIntExtra("v", 0))
            STOP -> {
                pause()
                getSystemService(NotificationManager::class.java).cancel(NOTIF)
                fg = false; shown = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF, n)
        fg = true; shown = true
    }

    private fun ensureEngine() {
        val want = Player.state.value.engine
        if (engine == null || engineKind != want) {
            engine?.release()
            engine = if (want == EngineKind.PIPER) SherpaTtsEngine(this) else SystemTtsEngine(this)
            engineKind = want
        }
    }

    private fun play() {
        val s = Player.state.value
        if (s.items.isEmpty() || !requestFocus()) return
        if (!noisyOn) {
            ContextCompat.registerReceiver(
                this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyOn = true
        }
        Player.update { it.copy(playing = true, error = null) }
        goForeground()
        ensureEngine()
        speakCurrent()
    }

    private fun pause() {
        token++
        engine?.stop()
        Player.update { it.copy(playing = false) }
        if (noisyOn) { unregisterReceiver(noisy); noisyOn = false }
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        save()
        if (fg) { stopForeground(STOP_FOREGROUND_DETACH); fg = false; notify_() } // keep notification, drop foreground
    }

    private fun restartIfPlaying() {
        if (!Player.state.value.playing) return
        token++; engine?.stop(); ensureEngine(); speakCurrent()
    }

    private fun speakCurrent() {
        val s = Player.state.value
        if (!s.playing) return
        if (s.index >= s.items.size) { pause(); return }
        val my = ++token
        engine!!.speak(
            s.items[s.index], s.speed, s.sid,
            { h.post { if (my == token) next() } },
            { msg -> h.post { if (my == token) { Player.update { it.copy(error = msg) }; pause() } } }
        )
        engine!!.prefetch(s.items.drop(s.index + 1).take(2), s.speed, s.sid)
    }

    private fun next() {
        Player.update { it.copy(index = it.index + 1) }
        save(); speakCurrent()
    }

    private fun step(d: Int) = seek(Player.state.value.index + d)

    private fun seek(i: Int) {
        val s = Player.state.value
        if (s.items.isEmpty()) return
        token++; engine?.stop()
        Player.update { it.copy(index = i.coerceIn(0, it.items.lastIndex)) }
        save(); speakCurrent(); if (fg) notify_()
    }

    private fun setSleep(min: Int) {
        h.removeCallbacks(sleepRunnable)
        if (min > 0) h.postDelayed(sleepRunnable, min * 60_000L)
        Player.update { it.copy(sleepMin = min) }
    }

    private fun save() {
        val s = Player.state.value
        if (s.bookId.isNotEmpty())
            getSharedPreferences("chytalka", MODE_PRIVATE).edit().putInt("pos_${s.bookId}", s.index).apply()
    }

    private fun requestFocus(): Boolean {
        if (focus != null) return true
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener { c ->
                if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) h.post { pause() }
            }.build()
        val ok = audio.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (ok) focus = r
        return ok
    }

    private fun pi(a: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, PlayerService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(icon: Int, label: String, a: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi(a, code)).build()

    private fun notification(): Notification {
        val s = Player.state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(s.title.ifEmpty { "Chytalka" })
            .setContentText(s.items.getOrNull(s.index)?.raw?.take(80) ?: "")
            .setContentIntent(open)
            .setOngoing(s.playing)
            .addAction(action(android.R.drawable.ic_media_previous, "Назад", PREV, 1))
            .addAction(
                if (s.playing) action(android.R.drawable.ic_media_pause, "Пауза", PAUSE, 2)
                else action(android.R.drawable.ic_media_play, "Грати", PLAY, 3)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Далі", NEXT, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun notify_() {
        if (!shown) return
        getSystemService(NotificationManager::class.java).notify(NOTIF, notification())
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        engine?.release(); session.release()
        if (noisyOn) unregisterReceiver(noisy)
    }
}
