package ua.vatsuo.chytalka

/**
 * Text cleanup before TTS. Quotes, dashes, brackets, ellipses and digits are the most common
 * triggers of Piper glitches, so they are removed/rewritten once, at book preparation time.
 */
object Normalizer {
    private val quotes = Regex("[«»“”„\"]")
    private val lead = Regex("^[—–-]+\\s*")
    private val midDash = Regex("\\s[—–-]+\\s")
    private val junk = Regex("[()\\[\\]{}*#_/\\\\|<>~^•]")
    private val multi = Regex("([!?])[!?.]+")
    private val dots = Regex("\\.{2,}")
    private val num = Regex("(?<![\\d.,])(\\d{1,6})(?!\\d|[.,]\\d)")
    private val abbr = mapOf("т. д." to "так далі", "т.д." to "так далі", "т. п." to "таке інше", "т.п." to "таке інше")

    fun normalize(s: String, lang: Lang): String {
        var t = s.replace("\u00AD", "").replace(Regex("\\[\\d+]"), "")
        for ((a, b) in abbr) t = t.replace(a, b)
        t = t.replace(quotes, "").replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
        t = t.replace(lead, "").replace(midDash, ", ")
        t = t.replace("…", ".").replace(dots, ".")
        t = t.replace(junk, ", ").replace(multi, "$1")
        if (lang == Lang.UK) t = num.replace(t) { UkNumbers.words(it.value.toInt()) }
        t = t.replace(Regex("\\s+,"), ",").replace(Regex(",(\\s*,)+"), ",").replace(Regex(",\\s*([.!?])"), "$1")
        t = t.replace(Regex("\\s+"), " ").trim().trim(',', ' ', '-')
        if (t.isNotEmpty() && t.last() !in ".!?") t += "."
        return t
    }
}

/** Nominative cardinals up to 999 999 (case agreement is NOT handled: "у 1999 році" reads in nominative). */
object UkNumbers {
    private val ones = arrayOf("нуль", "один", "два", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять")
    private val onesF = arrayOf("нуль", "одна", "дві", "три", "чотири", "п'ять", "шість", "сім", "вісім", "дев'ять")
    private val teens = arrayOf("десять", "одинадцять", "дванадцять", "тринадцять", "чотирнадцять", "п'ятнадцять", "шістнадцять", "сімнадцять", "вісімнадцять", "дев'ятнадцять")
    private val tens = arrayOf("", "", "двадцять", "тридцять", "сорок", "п'ятдесят", "шістдесят", "сімдесят", "вісімдесят", "дев'яносто")
    private val hundreds = arrayOf("", "сто", "двісті", "триста", "чотириста", "п'ятсот", "шістсот", "сімсот", "вісімсот", "дев'ятсот")

    private fun below1000(n: Int, fem: Boolean): String {
        val p = ArrayList<String>()
        if (n / 100 > 0) p.add(hundreds[n / 100])
        val r = n % 100
        if (r in 10..19) p.add(teens[r - 10])
        else {
            if (r / 10 > 0) p.add(tens[r / 10])
            if (r % 10 > 0) p.add((if (fem) onesF else ones)[r % 10])
        }
        return p.joinToString(" ")
    }

    fun words(n: Int): String {
        if (n == 0) return "нуль"
        val th = n / 1000; val r = n % 1000
        val p = ArrayList<String>()
        if (th > 0) {
            if (th == 1) p.add("тисяча")
            else {
                val l2 = th % 100; val l = th % 10
                val form = if (l2 in 11..14) "тисяч" else when (l) { 1 -> "тисяча"; 2, 3, 4 -> "тисячі"; else -> "тисяч" }
                p.add(below1000(th, true)); p.add(form)
            }
        }
        if (r > 0) p.add(below1000(r, false))
        return p.joinToString(" ")
    }
}
