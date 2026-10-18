package ua.vatsuo.chytalka

import android.content.Context
import java.io.File

/**
 * One-time pass over the whole book (cached on disk): language detection per sentence +
 * user pronunciation dictionary. Playback then does no text work, which saves battery.
 *
 * Dictionary: <external files dir>/pronunciation.txt, one "word=how it should sound" per line,
 * e.g.  Гаррі=Гері   or   Hermione=Хермайні
 */
object Preprocessor {
    private val wordRe = Regex("[\\p{L}\\p{M}'’-]+")
    private val ukLetters = "іїєґІЇЄҐ"
    private val ruLetters = "ыъэёЫЪЭЁ"

    fun dictFile(ctx: Context) = File(ctx.getExternalFilesDir(null), "pronunciation.txt")

    fun prepare(ctx: Context, book: RawBook): List<Item> {
        val dict = dictFile(ctx)
        val stamp = if (dict.exists()) dict.lastModified() else 0L
        val cache = File(ctx.filesDir, "prep_${book.id}_v2_$stamp.tsv")
        if (cache.exists()) {
            val items = cache.readLines().mapNotNull {
                val p = it.split('\t', limit = 3)
                if (p.size == 3) Item(p[2], Lang.valueOf(p[0]), p[1]) else null
            }
            if (items.isNotEmpty()) return items
        }
        val words = loadDict(dict)
        var prev = Lang.UK
        val items = book.sentences.map { raw ->
            val lang = detect(raw, prev); prev = lang
            Item(raw, lang, applyDict(Normalizer.normalize(raw, lang), words))
        }
        ctx.filesDir.listFiles { f -> f.name.startsWith("prep_${book.id}_") }?.forEach { it.delete() }
        cache.writeText(items.joinToString("\n") {
            "${it.lang.name}\t${it.spoken.replace('\t', ' ').replace('\n', ' ')}\t${it.raw.replace('\t', ' ').replace('\n', ' ')}"
        })
        return items
    }

    private fun loadDict(f: File): Map<String, String> =
        if (!f.exists()) emptyMap() else f.readLines().mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim() else null
        }.toMap()

    private fun applyDict(s: String, dict: Map<String, String>): String {
        if (dict.isEmpty()) return s
        return wordRe.replace(s) { m ->
            val rep = dict[m.value.lowercase()] ?: return@replace m.value
            if (m.value[0].isUpperCase()) rep.replaceFirstChar { it.uppercase() } else rep
        }
    }

    fun detect(s: String, fallback: Lang): Lang {
        var cyr = 0; var lat = 0
        for (c in s) when {
            c in 'а'..'я' || c in 'А'..'Я' || c in ukLetters || c in ruLetters || c == 'ё' -> cyr++
            c in 'a'..'z' || c in 'A'..'Z' -> lat++
        }
        if (cyr == 0 && lat == 0) return fallback
        if (lat > cyr) return Lang.EN
        if (s.any { it in ukLetters }) return Lang.UK
        if (s.any { it in ruLetters }) return Lang.RU
        return if (fallback == Lang.EN) Lang.UK else fallback
    }
}
