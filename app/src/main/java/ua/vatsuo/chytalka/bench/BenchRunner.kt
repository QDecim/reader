package ua.vatsuo.chytalka.bench

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.update
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import ua.vatsuo.chytalka.Item
import ua.vatsuo.chytalka.Lang
import ua.vatsuo.chytalka.Player
import java.io.BufferedWriter
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** One benchmark run. Called on a background thread by BenchService (which holds the wake lock). */
class BenchRunner(private val ctx: Context, private val cfg: BenchConfig, private val stop: AtomicBoolean) {
    private class Gen(val pcm: FloatArray, val rate: Int, val tokens: Int, val tokS: Double, val infS: Double)
    private class Row(val tS: Double, val rtfInf: Double)

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val outDir = ctx.getExternalFilesDir(null)!!
    private val stDir = BenchFiles.dir(ctx)
    private val audioDir = File(ctx.filesDir, "bench_audio").also { it.mkdirs() }
    private val sentFile = File(outDir, "bench_$stamp.csv")
    private val sampFile = File(outDir, "bench_${stamp}_samples.csv")
    private val sumFile = File(outDir, "bench_${stamp}_summary.txt")
    private val errFile = File(outDir, "bench_${stamp}_error.txt")
    private val prefs = ctx.getSharedPreferences("chytalka_bench", Context.MODE_PRIVATE)
    private val battery = Battery(ctx)
    private val thermal = Thermal(ctx) { log(it) }

    private lateinit var sent: BufferedWriter
    private lateinit var samp: BufferedWriter
    private var sampler: Sampler? = null
    private var t0 = 0L
    private var upt0 = 0L   // uptimeMillis at start: does not count deep sleep, elapsedRealtime does
    private var chargeStart = 0L; private var pctStart = 0; private var tempStart = 0f
    private val rows = ArrayList<Row>()
    private var totalAudioS = 0.0
    private var modelLoadS = Double.NaN
    private val extra = ArrayList<String>()   // mode-specific summary lines
    private var error: String? = null
    private var prevHandler: Thread.UncaughtExceptionHandler? = null

    private fun log(s: String) = BenchState.log(s)
    private fun now() = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9   // real time, also while the CPU sleeps
    private fun sentences(n: Int): List<Item> {
        val s = Player.state.value
        val from = s.index.coerceIn(0, maxOf(0, s.items.lastIndex))
        return s.items.drop(from).filter { it.lang == Lang.UK }.take(n)
    }

    fun run() {
        try {
            begin()
            when (cfg.mode) {
                BenchMode.LATENCY -> modeLatency()
                BenchMode.BATCH -> modeBatch()
                BenchMode.IDLE -> modeIdle()
                BenchMode.PLAYBACK -> modePlayback()
                BenchMode.PIPER -> modePiper()
            }
        } catch (e: Throwable) {
            Log.e("ChytalkaBench", "fail", e)
            log("ПОМИЛКА: ${e.javaClass.simpleName}: ${e.message}")
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            error = "${e.javaClass.name}: ${e.message}\n$sw"
        } finally {
            finish()
        }
    }

    // ---------------------------------------------------------------- common
    private fun begin() {
        sent = sentFile.bufferedWriter(); samp = sampFile.bufferedWriter()
        sent.write(Csv.row("mode", "t_s", "run", "chapter", "idx", "chars", "tokens", "audio_s", "tok_s", "inf_s", "enc_s",
            "rtf_inf", "rtf_total", "threads", "provider", "voice") + "\n")
        prefs.edit().putString("unfinished_stamp", stamp).putString("unfinished_mode", cfg.mode.label).putInt("unfinished_cap", cfg.capacityMah).apply()
        prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { errFile.writeText("Необроблений виняток у потоці ${t.name}:\n" + StringWriter().also { w -> e.printStackTrace(PrintWriter(w)) }) }
            prevHandler?.uncaughtException(t, e)
        }
        if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName))
            log("УВАГА: для застосунку ввімкнена оптимізація батареї: система може вбити тест при вимкненому екрані (кнопка на екрані тесту)")
        if (battery.plugged()) log("УВАГА: підключено зарядку: вимір батареї недійсний")
        if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 0) log("УВАГА: режим польоту вимкнений")
        log("Старт: ${cfg.mode.label}; заряд ${battery.pct()}%, ${"%.1f".format(battery.tempC())}°C")
        thermal.start()
        t0 = SystemClock.elapsedRealtimeNanos(); upt0 = SystemClock.uptimeMillis()
        chargeStart = battery.chargeUah(); pctStart = battery.pct(); tempStart = battery.tempC()
        sampler = Sampler(battery, thermal, samp, t0).also { it.start() }
    }

    private fun finish() {
        val s = sampler; s?.stop(); thermal.stop()
        val wall = now()
        val chargeEnd = battery.chargeUah(); val pctEnd = battery.pct()
        val text = buildSummary(wall, chargeEnd, pctEnd, s)
        runCatching { sent.close() }; runCatching { samp.close() }
        error?.let { runCatching { errFile.writeText(it) } }
        sumFile.writeText(text)
        prefs.edit().remove("unfinished_stamp").apply()
        Thread.setDefaultUncaughtExceptionHandler(prevHandler)
        text.lines().forEach { log(it) }
        BenchState.ui.update { it.copy(summary = text, files = listOf(sentFile, sampFile, sumFile, errFile).filter { f -> f.exists() }.map { f -> f.absolutePath }) }
        log("Файли: ${sentFile.name}, ${sampFile.name}, ${sumFile.name}" + if (errFile.exists()) ", ${errFile.name}" else "")
    }

    private fun buildSummary(wall: Double, chargeEnd: Long, pctEnd: Int, s: Sampler?): String {
        val sb = StringBuilder()
        sb.appendLine("=== ПІДСУМОК ${cfg.mode.label} (${cfg.provider}, потоки ${cfg.threads}) ===")
        error?.let { sb.appendLine("!! ПОМИЛКА: " + it.lines().first()); sb.appendLine("   (стек у bench_${stamp}_error.txt)") }
        sb.appendLine("Wall: %.1f с (%.1f хв)".format(wall, wall / 60))
        val awake = (SystemClock.uptimeMillis() - upt0) / 1000.0
        if (wall - awake > maxOf(5.0, wall * 0.03)) sb.appendLine("!! Процесор спав %.0f с із %.0f с (wake lock не втримав): вимір струму зайвий".format(wall - awake, wall))
        else sb.appendLine("Процесор не засинав (прокинутий %.0f з %.0f с)".format(awake, wall))
        if (!modelLoadS.isNaN()) sb.appendLine("Завантаження моделі: %.2f с".format(modelLoadS))
        if (rows.isNotEmpty()) {
            val r = rows.map { it.rtfInf }
            sb.appendLine("RTF (інференс) медіана %.3f, p95 %.3f, речень %d".format(Stats.median(r), Stats.percentile(r, 95.0), r.size))
            sb.appendLine("Сумарне аудіо: %.1f с (%.2f год)".format(totalAudioS, totalAudioS / 3600))
            val byMin = rows.groupBy { (it.tS / 60).toInt() }.toSortedMap()
            sb.appendLine("RTF по хвилинах: " + byMin.entries.joinToString("  ") { (m, v) -> "${m + 1}хв:%.2f".format(Stats.median(v.map { it.rtfInf })) })
            if (byMin.size >= 3) {
                val first = Stats.median(byMin.values.first().map { it.rtfInf }); val last = Stats.median(byMin.values.last().map { it.rtfInf })
                if (last > first * 1.2) sb.appendLine("!! RTF виріс на %.0f%% до кінця: схоже на тротлінг".format((last / first - 1) * 100))
            }
        }
        extra.forEach { sb.appendLine(it) }
        val used = chargeStart - chargeEnd
        val gran = s?.granularityUah() ?: 0L
        sb.appendLine("Батарея: %d%% -> %d%%, лічильник %d -> %d мкА·год (Δ %d), крок лічильника ~%d".format(pctStart, pctEnd, chargeStart, chargeEnd, used, gran))
        val haveCur = s?.hasCurrent() == true
        val mAh = if (haveCur) s!!.mAh() else Double.NaN
        if (haveCur) sb.appendLine("Струм (інтеграл, 1 с): одиниці %s (авто), середній %.0f мА, разом %.1f мА·год".format(if (s!!.milli()) "мА" else "мкА", s.avgMa(wall), mAh))
        else sb.appendLine("Струм: CURRENT_NOW недоступний на цьому телефоні")
        sb.appendLine("Темп.: %.1f -> макс %.1f °C; макс. термо-статус: %s".format(tempStart, s?.maxTemp ?: Float.NaN, Thermal.name(thermal.maxStatus)))
        sb.appendLine("Пам'ять: піковий VmHWM %.0f МБ, native PSS (пік) %.0f МБ".format(s?.peakHwmMb ?: Double.NaN, s?.peakPssMb ?: Double.NaN))
        val bgMa = prefs.getFloat("bg_ma", Float.NaN).toDouble()
        if ((cfg.mode == BenchMode.BATCH || cfg.mode == BenchMode.PIPER) && totalAudioS > 0) {
            if (haveCur) Energy.lines(mAh, wall, totalAudioS, bgMa, cfg.capacityMah).forEach { sb.appendLine(it) }
            else sb.appendLine("!! Без струму головне число недоступне")
            // cross-check with the charge counter (only if it moves in fine steps)
            val counterOk = gran > 0 && used >= 3 * gran
            if (counterOk) sb.appendLine("Звірка за лічильником: %.0f мА·год на годину аудіо (без віднімання фону)".format(used / 1000.0 / (totalAudioS / 3600)))
            else sb.appendLine("Лічильник заряду: крок завеликий/Δ замала, за ним вимір ненадійний (на цьому телефоні він змінюється кроками по 1%)")
            if (haveCur && wall < 600) sb.appendLine("!! Прогін коротший за 10 хв: результат орієнтовний, подовжте (більше розділів)")
        }
        return sb.toString().trimEnd()
    }

    private fun csvSentence(run: String, ch: Int, idx: Int, chars: Int, g: Gen, encS: Double, threads: Int) {
        val audio = g.pcm.size.toDouble() / g.rate
        val rtfInf = g.infS / audio
        val rtfTot = (g.tokS + g.infS + encS) / audio
        rows.add(Row(now(), rtfInf)); totalAudioS += audio
        sent.write(Csv.row(cfg.mode.name, now(), run, ch, idx, chars, g.tokens, audio, g.tokS, g.infS, encS, rtfInf, rtfTot, threads, cfg.provider, cfg.voice) + "\n")
        sent.flush()
    }

    private fun loadCommon(): Triple<StFrontend, FloatArray, String> {
        val model = BenchFiles.model(stDir)
        if (!model.exists()) throw IllegalStateException("Нема ${model.path}")
        val voices = BenchFiles.voices(stDir)
        if (voices.isEmpty()) throw IllegalStateException("Нема файлів голосів (.bin/.pt) у ${stDir.path}")
        val v = voices[cfg.voice.coerceIn(0, voices.lastIndex)]
        log("Голос: ${v.name}")
        val fe = StFrontend(Vocab.load(stDir))
        return Triple(fe, VoiceFile.load(v), model.path)
    }

    private fun synth(r: OrtRunner, fe: StFrontend, style: FloatArray, text: String): Gen {
        val t1 = System.nanoTime(); val tok = fe.tokens(text); val t2 = System.nanoTime()
        if (tok.isEmpty()) throw IllegalStateException("Порожні токени для: $text")
        val pcm = r.run(tok, 1f, style); val t3 = System.nanoTime()
        return Gen(pcm, OrtRunner.RATE, tok.size, (t2 - t1) / 1e9, (t3 - t2) / 1e9)
    }

    // ---------------------------------------------------------------- A: latency
    private fun modeLatency() {
        val (fe, style, model) = loadCommon()
        val items = sentences(300)
        if (items.isEmpty()) throw IllegalStateException("Відкрийте книгу (укр. речення) на головному екрані")
        val item = items.firstOrNull { it.spoken.length in 60..140 } ?: items.first()
        log("Речення (${item.spoken.length} симв.): ${item.spoken.take(80)}")
        for (th in cfg.threads) {
            if (stop.get()) break
            OrtRunner(model, th, cfg.provider).use { r ->
                modelLoadS = r.loadSeconds
                log("[$th потоків] модель завантажено за %.2f с".format(r.loadSeconds))
                repeat(2) { w -> val g = synth(r, fe, style, item.spoken); log("  прогрів $w: інф %.2f с, аудіо %.2f с".format(g.infS, g.pcm.size.toDouble() / g.rate)) }
                val local = ArrayList<Double>()
                repeat(cfg.repeats) { i ->
                    if (stop.get()) return@repeat
                    val g = synth(r, fe, style, item.spoken)
                    csvSentence("A$th#$i", 0, 0, item.spoken.length, g, 0.0, th)
                    local.add(rows.last().rtfInf)
                }
                if (local.isNotEmpty()) {
                    val line = "[$th потоків] RTF медіана %.3f, p95 %.3f (n=%d)".format(Stats.median(local), Stats.percentile(local, 95.0), local.size)
                    log(line); extra.add(line)
                }
            }
        }
    }

    // ---------------------------------------------------------------- B / E: batch of "chapters"
    private fun modeBatch() {
        val (fe, style, model) = loadCommon()
        val th = cfg.threads.first()
        OrtRunner(model, th, cfg.provider).use { r ->
            modelLoadS = r.loadSeconds
            log("Модель завантажено за %.2f с (потоки $th, ${cfg.provider})".format(r.loadSeconds))
            runBatch(th) { text -> synth(r, fe, style, text) }
        }
    }

    private fun modePiper() {
        val th = cfg.threads.first()
        val t0l = System.nanoTime()
        val tts = OfflineTts(
            ctx.assets,
            OfflineTtsConfig(model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = "models/uk/model.onnx", tokens = "models/uk/tokens.txt", dataDir = "", noiseScale = 0.5f, noiseScaleW = 0.6f),
                numThreads = th, provider = "cpu"))
        )
        modelLoadS = (System.nanoTime() - t0l) / 1e9
        log("Piper завантажено за %.2f с (потоки $th)".format(modelLoadS))
        val sid = cfg.voice.coerceIn(0, maxOf(0, tts.numSpeakers() - 1))
        try {
            runBatch(th) { text ->
                val t1 = System.nanoTime()
                val a = tts.generate(text.lowercase(), sid, 1f)
                Gen(a.samples, a.sampleRate, text.length, 0.0, (System.nanoTime() - t1) / 1e9)
            }
        } finally { tts.release() }
    }

    private fun runBatch(threads: Int, gen: (String) -> Gen) {
        val total = cfg.chapters * cfg.perChapter
        val items = sentences(total)
        if (items.isEmpty()) throw IllegalStateException("Відкрийте книгу (укр. речення) на головному екрані")
        if (items.size < total) log("Лише ${items.size} з $total речень доступно від поточної позиції")
        audioDir.listFiles()?.forEach { it.delete() }
        var player: PcmPlayer? = null
        var chapterReady2 = Double.NaN
        var k = 0
        val genStart = now()
        try {
            for (c in 1..cfg.chapters) {
                var enc: AacEncoder? = null; var chAudio = 0.0
                val file = File(audioDir, "${cfg.mode.name}_${stamp}_ch$c.m4a")
                try {
                    for (i in 0 until cfg.perChapter) {
                        if (stop.get() || k >= items.size) break
                        val item = items[k++]
                        val g = gen(item.spoken)
                        val pad = FloatArray((g.rate * 0.1).toInt())
                        val pcm = g.pcm + pad
                        if (enc == null) enc = AacEncoder(file, g.rate)
                        if (cfg.play && player == null) player = PcmPlayer(g.rate).also { it.start() }
                        val e1 = System.nanoTime(); enc.write(pcm); val encS = (System.nanoTime() - e1) / 1e9
                        player?.offer(pcm)
                        chAudio += pcm.size.toDouble() / g.rate
                        csvSentence("c$c", c, k, item.spoken.length, g, encS, threads)
                        if (k % 10 == 0) log("розділ $c: $k/${items.size} речень, RTF %.2f".format(rows.last().rtfInf))
                    }
                } finally {
                    val size = enc?.let { runCatching { it.finish() }.getOrDefault(0L) } ?: 0L
                    if (enc != null) log("Розділ $c готовий на %.1f с: аудіо %.1f с, файл %d КБ (AAC)".format(now() - genStart, chAudio, size / 1024))
                }
                if (c == 2) chapterReady2 = now() - genStart
                if (stop.get()) break
            }
            log("Генерація завершена за %.1f с".format(now() - genStart))
            extra.add("Генерацію завершено за %.1f с; час до готовності 2 розділів: %s".format(now() - genStart, if (chapterReady2.isNaN()) "н/д" else "%.1f с".format(chapterReady2)))
            if (player != null) { log("Дограємо буфер…"); if (stop.get()) player.abort() else player.finishAndWait() }
        } catch (e: Throwable) { player?.abort(); throw e }
    }

    // ---------------------------------------------------------------- C: idle baseline
    private fun modeIdle() {
        log("Простій ${cfg.minutes} хв: вимкніть екран і нічого не чіпайте (не закривайте застосунок свайпом)")
        waitMinutes(cfg.minutes)
        val min = now() / 60
        val avg = sampler?.avgMa(now()) ?: Double.NaN
        if (min >= 3 && !avg.isNaN() && avg > 0) {
            prefs.edit().putFloat("bg_ma", avg.toFloat()).putFloat("bg_minutes", min.toFloat()).apply()
            extra.add("ФОН: середній струм %.0f мА за %.1f хв, збережено для віднімання в B/E".format(avg, min))
        } else extra.add("ФОН не збережено (потрібно ≥3 хв і доступний струм)")
    }

    // ---------------------------------------------------------------- D: playback baseline
    private fun modePlayback() {
        val f = audioDir.listFiles { x -> x.name.endsWith(".m4a") }?.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("Нема готового аудіо: спершу виконайте режим B (або E)")
        log("Відтворення ${f.name} по колу, ${cfg.minutes} хв")
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.path); mp.isLooping = true; mp.prepare(); mp.start()
            waitMinutes(cfg.minutes)
        } finally { runCatching { mp.stop() }; mp.release() }
        val min = now() / 60
        val avg = sampler?.avgMa(now()) ?: Double.NaN
        val bg = prefs.getFloat("bg_ma", Float.NaN).toDouble()
        if (min > 1 && !avg.isNaN()) extra.add("Відтворення: середній струм %.0f мА; чисте (мінус фон C): %s".format(avg, if (bg.isNaN()) "н/д (нема режиму C)" else "%.0f мА".format(avg - bg)))
    }

    private fun waitMinutes(m: Int) {
        val end = SystemClock.elapsedRealtimeNanos() + m * 60_000_000_000L
        var nextLog = 1
        while (SystemClock.elapsedRealtimeNanos() < end && !stop.get()) {
            Thread.sleep(500)
            if (now() / 60 >= nextLog) { log("минуло $nextLog хв, заряд ${battery.pct()}%, лічильник ${battery.chargeUah()}"); nextLog++ }
        }
    }
}
