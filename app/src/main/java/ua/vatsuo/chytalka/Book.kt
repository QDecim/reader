package ua.vatsuo.chytalka

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

enum class Lang(val tag: String) { UK("uk"), RU("ru"), EN("en") }

/** raw = what is shown, spoken = what is sent to TTS (after dictionary/cleanup). */
data class Item(val raw: String, val lang: Lang, val spoken: String)

class RawBook(val id: String, val title: String, val sentences: List<String>)

object BookLoader {
    fun load(ctx: Context, uri: Uri): RawBook {
        val name = displayName(ctx, uri)
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val lower = name.lowercase()
        val text = when {
            lower.endsWith(".fb2") -> parseFb2(bytes)
            lower.endsWith(".epub") -> parseEpub(bytes)
            else -> decode(bytes)
        }
        val id = MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16)
        return RawBook(id, name.substringBeforeLast('.'), Splitter.split(text))
    }

    private fun displayName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "book.txt"

    private fun decode(b: ByteArray): String {
        val s = String(b, Charsets.UTF_8)
        return if (s.contains('\uFFFD')) String(b, charset("windows-1251")) else s
    }

    private fun parseFb2(b: ByteArray): String {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(b), null) // encoding from XML declaration
        val sb = StringBuilder()
        var inBody = false
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> if (p.name == "body") inBody = true
                XmlPullParser.END_TAG -> when (p.name) {
                    "body" -> inBody = false
                    "p", "v", "subtitle", "title", "empty-line" -> if (inBody) sb.append('\n')
                }
                XmlPullParser.TEXT -> if (inBody) sb.append(p.text)
            }
            e = p.next()
        }
        return sb.toString()
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

    private fun parseEpub(b: ByteArray): String {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            var en = z.nextEntry
            while (en != null) {
                if (!en.isDirectory) files[en.name] = z.readBytes()
                en = z.nextEntry
            }
        }
        val container = String(files["META-INF/container.xml"] ?: return "")
        val opfPath = attr(Regex("<rootfile\\b[^>]*>").find(container)?.value ?: return "", "full-path") ?: return ""
        val opf = String(files[opfPath] ?: return "")
        val dir = opfPath.substringBeforeLast('/', "")
        val manifest = Regex("<item\\b[^>]*>").findAll(opf).mapNotNull {
            val id = attr(it.value, "id"); val href = attr(it.value, "href")
            if (id != null && href != null) id to href else null
        }.toMap()
        val sb = StringBuilder()
        Regex("<itemref\\b[^>]*>").findAll(opf).forEach { ref ->
            val href = manifest[attr(ref.value, "idref")] ?: return@forEach
            val path = (if (dir.isEmpty()) "" else "$dir/") + Uri.decode(href).substringBefore('#')
            val html = files[path] ?: return@forEach
            sb.append(Html.fromHtml(String(html), Html.FROM_HTML_MODE_LEGACY).toString()).append('\n')
        }
        return sb.toString()
    }
}

object Splitter {
    private const val MAX = 200   // short chunks = fewer Piper glitches
    private const val MIN = 25    // very short sentences are merged with the next one
    private val boundary = Regex("(?<=[.!?…»”\"])\\s+(?=[А-ЯІЇЄҐA-Z«\"“—-])")

    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            val l = line.replace(Regex("\\s+"), " ").trim()
            if (l.isEmpty()) continue
            val pieces = ArrayList<String>()
            for (s in l.split(boundary)) {
                var rest = s.trim()
                while (rest.length > MAX) {
                    var cut = rest.lastIndexOf(", ", MAX).takeIf { it > 60 } ?: rest.lastIndexOf(' ', MAX)
                    if (cut <= 0) cut = MAX
                    pieces.add(rest.substring(0, cut + 1).trim())
                    rest = rest.substring(cut + 1).trim()
                }
                if (rest.isNotEmpty()) pieces.add(rest)
            }
            var k = 0
            while (k < pieces.size) {
                var cur = pieces[k++]
                while (cur.length < MIN && k < pieces.size && cur.length + 1 + pieces[k].length <= MAX) cur += " " + pieces[k++]
                out.add(cur)
            }
        }
        return out
    }
}
