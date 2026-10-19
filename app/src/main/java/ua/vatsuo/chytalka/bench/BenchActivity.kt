package ua.vatsuo.chytalka.bench

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ua.vatsuo.chytalka.Lang
import ua.vatsuo.chytalka.Player
import java.io.File

class BenchActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("chytalka_bench", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
    }

    private fun share(paths: List<String>) {
        val uris = ArrayList(paths.map { FileProvider.getUriForFile(this, "$packageName.bench", File(it)) })
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Поділитись результатами"))
    }

    @Composable
    private fun Screen() {
        val ui by BenchState.ui.collectAsState()
        val book by Player.state.collectAsState()
        val dir = remember { BenchFiles.dir(this) }
        var refresh by remember { mutableIntStateOf(0) }
        val voices = remember(refresh) { BenchFiles.voices(dir) }
        val hasModel = remember(refresh) { BenchFiles.model(dir).exists() }
        val vocabSrc = remember(refresh) { BenchFiles.vocabSource(dir) }

        var mode by remember { mutableStateOf(BenchMode.LATENCY) }
        var threads by remember { mutableStateOf(setOf(4)) }
        var voice by remember { mutableIntStateOf(0) }
        var provider by remember { mutableStateOf("cpu") }
        var repeats by remember { mutableStateOf("8") }
        var chapters by remember { mutableStateOf("2") }
        var perChapter by remember { mutableStateOf("40") }
        var play by remember { mutableStateOf(false) }
        var minutes by remember { mutableStateOf("12") }
        var capacity by remember { mutableStateOf(prefs.getInt("capacity", 0).let { if (it > 0) it.toString() else "" }) }
        val logScroll = rememberScrollState()
        LaunchedEffect(ui.log.size) { logScroll.animateScrollTo(logScroll.maxValue) }

        val ukCount = book.items.count { it.lang == Lang.UK }
        val ready = when (mode) {
            BenchMode.LATENCY, BenchMode.BATCH -> hasModel && vocabSrc != null && voices.isNotEmpty() && ukCount > 0
            BenchMode.PIPER -> ukCount > 0
            else -> true
        }

        Column(
            Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Тест StyleTTS2", fontSize = 22.sp, color = Color.White)
            Text(
                "Файли в ${dir.path}:\n" +
                    "model.onnx: ${if (hasModel) "є (${BenchFiles.model(dir).length() / 1048576} МБ)" else "НЕМАЄ"}\n" +
                    "vocab: ${vocabSrc ?: "НЕМАЄ (vocab.json або config.yml)"}\n" +
                    "голоси: ${if (voices.isEmpty()) "НЕМАЄ (.bin/.pt)" else voices.joinToString { it.nameWithoutExtension }}\n" +
                    "книга: ${if (book.items.isEmpty()) "не відкрита (відкрийте на головному екрані)" else "${book.title}, укр. речень $ukCount, поточне №${book.index + 1}"}",
                color = Color.Gray, fontSize = 12.sp
            )
            OutlinedButton(onClick = { refresh++ }) { Text("Оновити перевірку файлів") }

            Text("Режим", color = Color.Gray)
            FlowRowSel(BenchMode.values().toList(), { it == mode }, { it.label }) { mode = it }

            if (mode == BenchMode.LATENCY || mode == BenchMode.BATCH || mode == BenchMode.PIPER) {
                Text(if (mode == BenchMode.LATENCY) "Потоки (можна кілька)" else "Потоки", color = Color.Gray)
                FlowRowSel(listOf(1, 2, 3, 4, 6, 8), { it in threads }, { "$it" }) { t ->
                    threads = if (mode == BenchMode.LATENCY) (if (t in threads && threads.size > 1) threads - t else threads + t) else setOf(t)
                }
                Text("Голос", color = Color.Gray)
                FlowRowSel((if (voices.isEmpty()) listOf(0) else voices.indices.toList()), { it == voice }, { i -> voices.getOrNull(i)?.nameWithoutExtension?.take(14) ?: "${i + 1}" }) { voice = it }
            }
            if (mode == BenchMode.LATENCY || mode == BenchMode.BATCH) {
                Text("Провайдер (xnnpack/nnapi: експериментально)", color = Color.Gray)
                FlowRowSel(listOf("cpu", "xnnpack", "nnapi"), { it == provider }, { it }) { provider = it }
            }
            if (mode == BenchMode.LATENCY) Num("Повторів", repeats) { repeats = it }
            if (mode == BenchMode.BATCH || mode == BenchMode.PIPER) {
                Num("Розділів", chapters) { chapters = it }
                Num("Речень у розділі", perChapter) { perChapter = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = play, onCheckedChange = { play = it }); Spacer(Modifier.width(8.dp)); Text("Програвати згенероване", color = Color.White)
                }
            }
            if (mode == BenchMode.IDLE || mode == BenchMode.PLAYBACK) Num("Хвилин", minutes) { minutes = it }
            Num("Ємність батареї, мА·год (для порогу 10%/год)", capacity) { capacity = it; prefs.edit().putInt("capacity", it.toIntOrNull() ?: 0).apply() }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !ui.running && ready, onClick = {
                    BenchState.config = BenchConfig(
                        mode, threads.sorted(), voice, if (mode == BenchMode.LATENCY || mode == BenchMode.BATCH) provider else "cpu",
                        repeats.toIntOrNull()?.coerceIn(1, 100) ?: 8, chapters.toIntOrNull()?.coerceIn(1, 10) ?: 2,
                        perChapter.toIntOrNull()?.coerceIn(1, 500) ?: 40, play && (mode == BenchMode.BATCH || mode == BenchMode.PIPER),
                        minutes.toIntOrNull()?.coerceIn(1, 240) ?: 12, capacity.toIntOrNull() ?: 0
                    )
                    BenchService.start(this@BenchActivity)
                }) { Text("Старт") }
                OutlinedButton(enabled = ui.running, onClick = { BenchService.stop(this@BenchActivity) }) { Text("Стоп") }
                OutlinedButton(enabled = ui.files.isNotEmpty() && !ui.running, onClick = { share(ui.files) }) { Text("Поділитись") }
            }
            if (!ready) Text("Не готово: перевірте файли/книгу вище", color = Color(0xFFFF8866), fontSize = 12.sp)

            Text("Лог", color = Color.Gray)
            Box(Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF111111)).verticalScroll(logScroll).padding(6.dp)) {
                Text(ui.log.joinToString("\n"), color = Color(0xFFBBBBBB), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (ui.summary.isNotEmpty()) SelectionContainer {
                Text(ui.summary, color = Color(0xFF99DD99), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            Text(
                "Протокол: режим польоту; НЕ на зарядці (USB-зарядка псує вимір; файли забирайте після тесту через adb по Wi-Fi або «Поділитись»); " +
                    "заряд 40–90%; 10 хв охолодження між прогонами; кожен прогін ≥10–15 хв; мінімум 2 повтори. " +
                    "Порядок: C (простій, екран вимкнений) → B (пакет) → D (відтворення). " +
                    "Під час C/D/B вимкніть екран кнопкою живлення. Результати: Android/data/ua.vatsuo.chytalka/files/bench_*.csv",
                color = Color.Gray, fontSize = 11.sp
            )
        }
    }

    @Composable
    private fun Num(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(value = value, onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(6)) }, label = { Text(label) }, singleLine = true)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun <T> FlowRowSel(items: List<T>, selected: (T) -> Boolean, label: (T) -> String, onClick: (T) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { t ->
                if (selected(t)) Button(onClick = { onClick(t) }) { Text(label(t)) } else OutlinedButton(onClick = { onClick(t) }) { Text(label(t)) }
            }
        }
    }
}
