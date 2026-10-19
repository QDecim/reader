package ua.vatsuo.chytalka.bench

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Pure-Kotlin helpers (no Android imports) so they can be unit-tested on a plain JVM. */

object Stats {
    fun median(v: List<Double>): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
    /** nearest-rank percentile, p in 0..100 */
    fun percentile(v: List<Double>, p: Double): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sorted()
        val rank = Math.ceil(p / 100.0 * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }
}

object Csv {
    fun cell(x: Any?): String {
        val s = when (x) { null -> ""; is Double -> if (x.isNaN()) "" else "%.4f".format(java.util.Locale.ROOT, x); else -> x.toString() }
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
    fun row(vararg x: Any?) = x.joinToString(",") { cell(it) }
}

/** Symbol table of the StyleTTS2 model: index in the list = token id. */
object Vocab {
    /** vocab.json (array of strings, or one string) or config.yml (model_params.vocab). */
    fun load(dir: File): List<String> {
        val j = File(dir, "vocab.json"); val y = File(dir, "config.yml")
        return when {
            j.exists() -> parseJson(j.readText(Charsets.UTF_8))
            y.exists() -> parseYaml(y.readText(Charsets.UTF_8))
            else -> throw IllegalStateException("Нема vocab.json чи config.yml у ${dir.path}")
        }
    }

    private fun cps(s: String): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < s.length) { val c = s.codePointAt(i); out.add(String(Character.toChars(c))); i += Character.charCount(c) }
        return out
    }

    fun parseJson(text: String): List<String> {
        val t = text.trim()
        if (t.startsWith("\"")) return cps(quoted(t, 0).first)
        if (!t.startsWith("[")) throw IllegalStateException("vocab.json: очікувався масив або рядок")
        val items = ArrayList<String>(); var i = 1
        while (i < t.length) {
            val c = t[i]
            when {
                c == '"' -> { val (s, n) = quoted(t, i); items.add(s); i = n }
                c == ']' -> return items
                else -> i++   // spaces, commas, newlines
            }
        }
        throw IllegalStateException("vocab.json: масив не закрито")
    }

    /** Tolerant parser for `vocab:` in config.yml: flow list, block list, or a single scalar string. */
    fun parseYaml(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val key = Regex("""(^|[\s{,])vocab\s*:""")
        val k = lines.indexOfFirst { key.containsMatchIn(it) }
        if (k < 0) throw IllegalStateException("config.yml: нема ключа vocab")
        val afterKey = lines[k].substring(key.find(lines[k])!!.range.last + 1).trimStart()
        val rest = afterKey
        val items = ArrayList<String>()
        if (rest.startsWith("[")) {
            val sb = StringBuilder(afterKey.substring(1))
            var j = k
            while (!closesFlow(sb) && j + 1 < lines.size) { j++; sb.append('\n').append(lines[j]) }
            val body = sb.toString()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == ' ' || c == '\n' || c == '\t' || c == ',' -> i++
                    c == ']' -> break
                    c == '\'' || c == '"' -> { val (s, n) = quoted(body, i); items.add(s); i = n }
                    else -> { val e = body.indexOfAny(charArrayOf(',', ']'), i).let { if (it < 0) body.length else it }; items.add(body.substring(i, e).trim()); i = e }
                }
            }
            return items
        }
        if (rest.isEmpty() || rest.startsWith("#")) {
            var j = k + 1
            while (j < lines.size) {
                val l = lines[j].trim()
                if (l.isEmpty() || l.startsWith("#")) { j++; continue }
                if (l == "-" ) { items.add(""); j++; continue }
                if (!l.startsWith("- ")) break
                val v = l.substring(2).trimStart()
                items.add(if (v.startsWith("'") || v.startsWith("\"")) quoted(v, 0).first else v.substringBefore(" #").trim())
                j++
            }
            return items
        }
        val scalar = if (rest.startsWith("'") || rest.startsWith("\"")) quoted(rest, 0).first else rest.substringBefore(" #").trim()
        return cps(scalar)
    }

    private fun closesFlow(sb: StringBuilder): Boolean {
        var i = 0
        while (i < sb.length) {
            val c = sb[i]
            if (c == '\'' || c == '"') { i = quoted(sb.toString(), i).second; continue }
            if (c == ']') return true
            i++
        }
        return false
    }

    /** Decode YAML single/double-quoted scalar starting at s[i]; returns value and index after the closing quote. */
    private fun quoted(s: String, i0: Int): Pair<String, Int> {
        val q = s[i0]; var i = i0 + 1; val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (q == '\'') {
                if (c == '\'') { if (i + 1 < s.length && s[i + 1] == '\'') { sb.append('\''); i += 2; continue } else return sb.toString() to i + 1 }
                sb.append(c); i++
            } else {
                if (c == '"') return sb.toString() to i + 1
                if (c == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]; i += 2
                    when (n) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); '0' -> sb.append('\u0000')
                        '\\' -> sb.append('\\'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); '"' -> sb.append('"'); '/' -> sb.append('/'); ' ' -> sb.append(' '); 'e' -> sb.append('\u001b')
                        'x' -> { sb.append(s.substring(i, i + 2).toInt(16).toChar()); i += 2 }
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        'U' -> { sb.appendCodePoint(s.substring(i, i + 8).toInt(16)); i += 8 }
                        else -> sb.append(n)
                    }
                } else { sb.append(c); i++ }
            }
        }
        throw IllegalStateException("config.yml: незакрита лапка")
    }
}

/** Text -> StyleTTS2 token ids, using the ported ipa-uk (no stress marks) and the model vocab. */
class StFrontend(vocab: List<String>) {
    private val idx = HashMap<Int, Int>()
    init { vocab.forEachIndexed { i, s -> if (s.codePointCount(0, s.length) == 1) idx[s.codePointAt(0)] = i } }
    var droppedChars = 0L; private set

    fun tokens(spoken: String): LongArray {
        val ps = IpaUk.ipa(spoken)
        val out = ArrayList<Long>(ps.length); var i = 0
        while (i < ps.length) {
            val c = ps.codePointAt(i); i += Character.charCount(c)
            val t = idx[c]
            if (t != null) out.add(t.toLong()) else droppedChars++
        }
        return out.toLongArray()
    }
}

/** 256 float32 little-endian: raw .bin, or a torch .pt zip (entry .../data/0, read without torch). */
object VoiceFile {
    fun load(f: File): FloatArray {
        val bytes = if (f.name.endsWith(".pt", true)) readPt(f) else f.readBytes()
        if (bytes.size != 256 * 4) throw IllegalStateException("${f.name}: ${bytes.size} байт, очікувалось 1024 (256 float32)")
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(256).also { fb.get(it) }
    }
    private fun readPt(f: File): ByteArray = ZipFile(f).use { z ->
        val e = z.entries().asSequence().firstOrNull { it.name == "data/0" || it.name.endsWith("/data/0") }
            ?: throw IllegalStateException("${f.name}: нема data/0 у .pt")
        z.getInputStream(e).use { it.readBytes() }
    }
}
