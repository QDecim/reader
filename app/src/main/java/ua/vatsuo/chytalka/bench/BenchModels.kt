package ua.vatsuo.chytalka.bench

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BenchMode(val label: String) {
    LATENCY("A Латентність"), BATCH("B Пакет розділів"), IDLE("C Простій"), PLAYBACK("D Відтворення"), PIPER("E Piper")
}

data class BenchConfig(
    val mode: BenchMode,
    val threads: List<Int>,      // A: усі зі списку; B/E: перший
    val voice: Int,              // індекс у BenchFiles.voices()
    val provider: String,        // cpu | xnnpack | nnapi
    val repeats: Int,            // A
    val chapters: Int,           // B/E
    val perChapter: Int,         // B/E: речень у «розділі»
    val play: Boolean,           // B/E: програвати згенероване
    val minutes: Int,            // C/D
    val capacityMah: Int,        // для порогу 10% на годину аудіо (0 = невідомо)
)

data class BenchUi(
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val summary: String = "",
    val files: List<String> = emptyList(),
)

object BenchState {
    val ui = MutableStateFlow(BenchUi())
    @Volatile var config: BenchConfig? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(s: String) {
        Log.i("ChytalkaBench", s)
        val line = "${fmt.format(Date())} $s"
        ui.update { it.copy(log = (it.log + line).takeLast(400)) }
    }
    fun clear() = ui.update { it.copy(log = emptyList(), summary = "", files = emptyList()) }
}

object BenchFiles {
    fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "st2").also { it.mkdirs() }
    fun model(dir: File) = File(dir, "model.onnx")
    fun voices(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".bin", true) || f.name.endsWith(".pt", true)) }
            ?.sortedBy { it.name } ?: emptyList()
    fun vocabSource(dir: File): String? = when {
        File(dir, "vocab.json").exists() -> "vocab.json"
        File(dir, "config.yml").exists() -> "config.yml"
        else -> null
    }
}
