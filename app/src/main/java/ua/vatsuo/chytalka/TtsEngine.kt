package ua.vatsuo.chytalka

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

enum class EngineKind { SYSTEM, PIPER }

interface TtsEngine {
    fun speak(item: Item, speed: Float, sid: Int, onDone: () -> Unit, onError: (String) -> Unit)
    fun prefetch(items: List<Item>, speed: Float, sid: Int) {}
    fun stop()
    fun release()
}

/** Android system TTS (Google etc.). Only offline voices are selected. */
class SystemTtsEngine(ctx: Context) : TtsEngine {
    @Volatile private var ready = false
    private var pending: (() -> Unit)? = null
    private val callbacks = ConcurrentHashMap<String, Pair<() -> Unit, (String) -> Unit>>()
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { st ->
        ready = st == TextToSpeech.SUCCESS
        pending?.invoke(); pending = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { callbacks.remove(id)?.first?.invoke() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { callbacks.remove(id)?.second?.invoke("Помилка системного TTS") }
        })
    }

    override fun speak(item: Item, speed: Float, sid: Int, onDone: () -> Unit, onError: (String) -> Unit) {
        val run = {
            if (!ready) onError("Системний TTS недоступний") else {
                val voice = tts.voices?.firstOrNull { it.locale.language == item.lang.tag && !it.isNetworkConnectionRequired }
                if (voice != null) tts.setVoice(voice) else tts.setLanguage(Locale(item.lang.tag))
                tts.setSpeechRate(speed)
                val id = System.nanoTime().toString()
                callbacks[id] = onDone to onError
                if (tts.speak(item.spoken, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                    callbacks.remove(id); onError("TTS відхилив текст")
                }
            }
        }
        if (ready) run() else pending = run
    }

    override fun stop() { callbacks.clear(); tts.stop() }
    override fun release() { callbacks.clear(); tts.shutdown() }
}

/** Piper voices via sherpa-onnx, fully offline. Models live in assets/models/{uk,en,ru}. */
class SherpaTtsEngine(private val ctx: Context) : TtsEngine {
    companion object {
        // Lower noise = more stable (fewer artifacts) but a bit flatter voice. Tune 0.3..0.667 / 0.4..0.8.
        const val NOISE = 0.5f
        const val NOISE_W = 0.6f
        private const val SEC_PER_CHAR = 0.07   // typical Ukrainian speech rate, used by the quality gate
        private const val TRIES = 3
        private const val FADE_MS = 8
        private const val PAUSE_S = 0.15
    }

    private class Clip(val samples: FloatArray, val rate: Int)

    private val models = HashMap<Lang, OfflineTts>()
    private val synth = Executors.newSingleThreadExecutor()
    private val player = Executors.newSingleThreadExecutor()
    private val clips = ConcurrentHashMap<String, Future<Clip>>()
    @Volatile private var gen = 0
    private var track: AudioTrack? = null
    private var trackRate = 0

    /** espeak-ng reads its data with plain file I/O, so it must live on disk, not inside the APK assets. */
    private fun dataDir(l: Lang): String {
        val root = File(ctx.filesDir, "espeak-${l.tag}")
        val dst = File(root, "espeak-ng-data")
        val ok = File(root, ".ok")
        if (!ok.exists()) {
            root.deleteRecursively()
            copyAssets("models/${l.tag}/espeak-ng-data", dst)
            ok.writeText("1")
        }
        return dst.absolutePath
    }

    private fun copyAssets(path: String, dst: File) {
        val names = ctx.assets.list(path) ?: emptyArray()
        if (names.isEmpty()) {
            dst.parentFile?.mkdirs()
            runCatching { ctx.assets.open(path).use { i -> dst.outputStream().use { o -> i.copyTo(o) } } }
        } else {
            dst.mkdirs()
            names.forEach { copyAssets("$path/$it", File(dst, it)) }
        }
    }

    private fun model(l: Lang): OfflineTts = synchronized(models) {
        models.getOrPut(l) {
            val d = "models/${l.tag}"
            OfflineTts(
                ctx.assets,
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "$d/model.onnx", tokens = "$d/tokens.txt",
                            // uk model is a "text" model: patched in setup.ps1 to use the character frontend, no espeak
                            dataDir = if (l == Lang.UK) "" else dataDir(l),
                            noiseScale = NOISE, noiseScaleW = NOISE_W
                        ),
                        numThreads = 2, provider = "cpu"
                    )
                )
            )
        }
    }

    /** Generates up to TRIES times and keeps the take whose duration/clipping look sane. */
    private fun synthesize(item: Item, speed: Float, sid: Int): Clip {
        val tts = model(item.lang)
        val voice = sid.coerceIn(0, maxOf(0, tts.numSpeakers() - 1))
        // uk tokens are lowercase Cyrillic letters only (stress mark U+0301 is also accepted)
        val text = if (item.lang == Lang.UK) item.spoken.lowercase() else item.spoken
        val expected = text.length * SEC_PER_CHAR / speed
        var best: GeneratedAudio? = null
        var bestScore = Double.MAX_VALUE
        repeat(TRIES) {
            val a = tts.generate(text, voice, speed)
            if (a.samples.isEmpty()) return@repeat
            if (text.length < 15) return finish(a)          // too short to judge
            val ratio = (a.samples.size.toDouble() / a.sampleRate) / expected
            val clipped = a.samples.count { abs(it) >= 0.999f } > a.samples.size / 200
            val score = abs(ln(ratio)) + if (clipped) 1.0 else 0.0
            if (score < bestScore) { best = a; bestScore = score }
            if (ratio in 0.55..1.8 && !clipped) return finish(a)
        }
        return finish(best ?: throw IllegalStateException("Piper повернув тишу"))
    }

    /** Short fade in/out removes clicks at clip boundaries; trailing pause gives natural rhythm. */
    private fun finish(a: GeneratedAudio, speed: Float = 1f): Clip {
        val fade = a.sampleRate * FADE_MS / 1000
        val pad = (a.sampleRate * PAUSE_S).toInt()
        val out = FloatArray(a.samples.size + pad)
        System.arraycopy(a.samples, 0, out, 0, a.samples.size)
        val f = minOf(fade, a.samples.size / 2)
        for (i in 0 until f) {
            val k = i.toFloat() / f
            out[i] *= k
            out[a.samples.size - 1 - i] *= k
        }
        return Clip(out, a.sampleRate)
    }

    private fun clip(item: Item, speed: Float, sid: Int): Future<Clip> {
        if (clips.size > 6) clips.clear()
        return clips.computeIfAbsent("${item.lang}|$speed|$sid|${item.spoken}") {
            synth.submit(Callable<Clip> { synthesize(item, speed, sid) })
        }
    }

    @Synchronized private fun trackFor(rate: Int): AudioTrack {
        track?.let { if (trackRate == rate) return it else { it.stop(); it.release() } }
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(min, rate * 4)) // ~1 s buffer = fewer wakeups
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t; trackRate = rate
        return t
    }

    override fun speak(item: Item, speed: Float, sid: Int, onDone: () -> Unit, onError: (String) -> Unit) {
        val g = ++gen
        player.execute {
            try {
                val c = clip(item, speed, sid).get()
                if (g != gen) return@execute
                val t = trackFor(c.rate)
                t.play()
                var off = 0
                while (off < c.samples.size && g == gen) {
                    val n = minOf(4096, c.samples.size - off)
                    val w = t.write(c.samples, off, n, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { if (g == gen) throw IllegalStateException("AudioTrack error $w"); return@execute }
                    off += w
                }
                if (g == gen) onDone()
            } catch (e: Throwable) {
                if (g == gen) onError(e.message ?: "Помилка Piper (чи є моделі в assets?)")
            }
        }
    }

    override fun prefetch(items: List<Item>, speed: Float, sid: Int) { items.forEach { clip(it, speed, sid) } }

    @Synchronized override fun stop() {
        gen++
        track?.let { it.pause(); it.flush(); it.release() } // release unblocks a pending write
        track = null
    }

    override fun release() {
        stop(); synth.shutdownNow(); player.shutdownNow()
        synchronized(models) { models.values.forEach { it.release() }; models.clear() }
    }
}
