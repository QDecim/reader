package ua.vatsuo.chytalka

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VISIBLE_LINES = 3 // скільки рядків тексту видно одночасно

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("chytalka", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
        if (Player.state.value.items.isEmpty()) {
            prefs.getString("last_uri", null)?.let { runCatching { openBook(Uri.parse(it)) } }
        }
    }

    private fun openBook(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString("last_uri", uri.toString()).apply()
        Player.update { it.copy(busy = true, error = null) }
        lifecycleScope.launch {
            try {
                val (raw, items) = withContext(Dispatchers.Default) {
                    val r = BookLoader.load(this@MainActivity, uri)
                    r to Preprocessor.prepare(this@MainActivity, r)
                }
                val pos = prefs.getInt("pos_${raw.id}", 0).coerceIn(0, maxOf(0, items.lastIndex))
                if (Player.state.value.playing) PlayerService.send(this@MainActivity, PlayerService.PAUSE)
                Player.update { it.copy(bookId = raw.id, title = raw.title, items = items, index = pos, busy = false) }
            } catch (e: Throwable) {
                Player.update { it.copy(busy = false, error = "Не вдалось відкрити: ${e.message}") }
            }
        }
    }

    @Composable
    private fun Screen() {
        val s by Player.state.collectAsState()
        val list = rememberLazyListState()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) openBook(u) }
        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) { if (android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
        LaunchedEffect(s.index, s.items.size) {
            if (s.items.isNotEmpty()) list.animateScrollToItem(s.index.coerceAtMost(s.items.lastIndex))
        }
        var speed by remember(s.speed) { mutableFloatStateOf(s.speed) }
        var pos by remember(s.index) { mutableFloatStateOf(s.index.toFloat()) }
        val boxH = with(LocalDensity.current) { (30.sp * VISIBLE_LINES).toDp() }

        Column(
            Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = {
                picker.launch(arrayOf("application/x-fictionbook+xml", "application/epub+zip", "text/plain", "application/octet-stream", "*/*"))
            }) { Text(if (s.title.isEmpty()) "Відкрити книгу (FB2 / EPUB / TXT)" else s.title) }

            OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, ua.vatsuo.chytalka.bench.BenchActivity::class.java)) }) { Text("Тест StyleTTS2") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sel("Системний", s.engine == EngineKind.SYSTEM) { PlayerService.send(this@MainActivity, PlayerService.ENGINE, 0) }
                Sel("Piper (офлайн)", s.engine == EngineKind.PIPER) { PlayerService.send(this@MainActivity, PlayerService.ENGINE, 1) }
            }

            if (s.engine == EngineKind.PIPER) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Голос:", color = Color.Gray)
                (0..2).forEach { v -> Sel("${v + 1}", s.sid == v) { PlayerService.send(this@MainActivity, PlayerService.SID, v) } }
            }

            if (s.busy) Text("Підготовка тексту…", color = Color.Gray)
            s.error?.let { Text(it, color = Color(0xFFFF6666)) }

            LazyColumn(Modifier.fillMaxWidth().height(boxH), state = list) {
                itemsIndexed(s.items) { i, it ->
                    Text(
                        it.raw, fontSize = 22.sp, lineHeight = 30.sp,
                        color = if (i == s.index) Color.White else Color(0xFF777777),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            if (s.items.isNotEmpty()) {
                Slider(
                    value = pos, onValueChange = { pos = it },
                    onValueChangeFinished = { PlayerService.send(this@MainActivity, PlayerService.SEEK, pos.toInt()) },
                    valueRange = 0f..s.items.lastIndex.coerceAtLeast(1).toFloat()
                )
                Text("${s.index + 1} / ${s.items.size}", color = Color.Gray)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { PlayerService.send(this@MainActivity, PlayerService.PREV) }) { Text("⏮") }
                Button(onClick = { PlayerService.send(this@MainActivity, PlayerService.TOGGLE) }) { Text(if (s.playing) "⏸" else "▶") }
                Button(onClick = { PlayerService.send(this@MainActivity, PlayerService.NEXT) }) { Text("⏭") }
            }

            Text("Швидкість: %.2f×".format(speed), color = Color.Gray)
            Slider(
                value = speed, onValueChange = { speed = it },
                onValueChangeFinished = { PlayerService.send(this@MainActivity, PlayerService.SPEED, speed) },
                valueRange = 0.6f..2.0f
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Сон:", color = Color.Gray)
                listOf(0, 15, 30, 60).forEach { m ->
                    Sel(if (m == 0) "викл" else "$m хв", s.sleepMin == m) { PlayerService.send(this@MainActivity, PlayerService.SLEEP, m) }
                }
            }
        }
    }

    @Composable
    private fun Sel(label: String, selected: Boolean, onClick: () -> Unit) {
        if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
    }
}
