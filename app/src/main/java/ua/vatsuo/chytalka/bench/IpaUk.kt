package ua.vatsuo.chytalka.bench

/**
 * Kotlin port of github.com/patriotyk/ipa-uk (ipa(text, check_accent=False)).
 * Patterns are kept textually identical to the Python original; py() converts \1 -> $1 in replacements.
 * Stress marks (U+0301 / U+0300) in the input are honoured, but the bench feeds text WITHOUT stress.
 */
object IpaUk {
    const val ACUTE = '\u0301'
    const val GRAVE = '\u0300'

    private const val VOWEL_NO_I = "aɛɪuɔɐoʊe"
    private const val VOWEL = VOWEL_NO_I + "i"
    private const val VOWEL_C = "[$VOWEL]"
    private const val CONSONANT_NO_W = "bdzʒɡɦmnlrpftskxʃj"
    private const val CONSONANT_NO_W_C = "[$CONSONANT_NO_W]"
    private const val CONSONANT = CONSONANT_NO_W + "ʋβ̞wʍ"
    private const val CONSONANT_C = "[$CONSONANT]"
    private const val PALATALIZABLE = "tdsznlrbpʋfɡmkɦxʃʒ"
    private const val PALATALIZABLE_C = "[$PALATALIZABLE]"
    private const val VOICED_OBSTRUENT = "[bdzʒɡɦ]"

    private fun py(r: String) = r.replace(Regex("""\\(\d)"""), "\\$$1")
    private class Sub(val re: Regex, val rep: String) {
        fun apply(s: String) = re.replace(s, rep)
    }
    private fun sub(p: String, r: String) = Sub(Regex(p), py(r))

    private val voicing = listOf(
        "p" to "b", "f" to "v", "t" to "d", "tʲ" to "dʲ", "s" to "z", "sʲ" to "zʲ", "ʃ" to "ʒ", "k" to "ɡ", "x" to "ɦ",
        "t͡s" to "d͡z", "t͡sʲ" to "d͡zʲ", "t͡ʃ" to "d͡ʒ", "ʃt͡ʃ" to "ʒd͡ʒ"
    ).map { (a, b) -> Sub(Regex(a + "(" + VOICED_OBSTRUENT + "+)"), py(b + """\1""")) }

    private val permOnset = setOf(
        "spr", "str", "skr", "spl", "skl", "sp", "st", "sk", "sf", "sx", "pr", "br", "tr", "dr", "kr", "gr", "ɦr", "fr", "xr",
        "pl", "bl", "kl", "gl", "ɦl", "fl", "xl"
    )

    private val graveDecomp = mapOf('ѐ' to "е$GRAVE", 'Ѐ' to "Е$GRAVE", 'ѝ' to "и$GRAVE", 'Ѝ' to "И$GRAVE")

    private val charMaps: List<List<Pair<Regex, String>>> = listOf(
        listOf("дзь" to "d͡zʲ", "тьс" to "t͡sʲː"),
        listOf(
            "дж" to "d͡ʒ", "дз" to "d͡z", "дс" to "d͡zs", "дш" to "d͡ʒʃ", "дч" to "d͡ʒt͡ʃ", "дц" to "d͡zt͡s",
            "тс" to "t͡s", "тш" to "t͡ʃʃ", "тч" to "t͡ʃː", "тц" to "t͡sː"
        ),
        listOf(
            "а" to "a", "б" to "b", "в" to "ʋ", "г" to "ɦ", "ґ" to "ɡ", "д" to "d", "е" to "ɛ", "є" to "jɛ", "ж" to "ʒ",
            "з" to "z", "и" to "ɪ", "і" to "i", "ї" to "ji", "й" to "j", "к" to "k", "л" to "l", "м" to "m", "н" to "n",
            "о" to "ɔ", "п" to "p", "р" to "r", "с" to "s", "т" to "t", "у" to "u", "ф" to "f", "х" to "x", "ц" to "t͡s",
            "ч" to "t͡ʃ", "ш" to "ʃ", "щ" to "ʃt͡ʃ", "ь" to "ʲ", "ю" to "ju", "я" to "ja", "’" to "j",
            "$ACUTE" to "ˈ", "$GRAVE" to "ˌ"
        )
    ).map { lvl -> lvl.map { (k, v) -> Regex(Regex.escape(k)) to Regex.escapeReplacement(v) } }

    private val orthographic = listOf(
        "нтськ" to "ньськ", "стськ" to "ськ", "нтст" to "нст", "стч" to "шч", "стд" to "зд", "стс" to "сː",
        "#зш" to "#шː", "зш" to "жш", "#зч" to "#шч", "зч" to "жч",
        """([бвгґд])\1""" to """\1ː""",
        """([^д]+)жж""" to """\1жː""",
        """([^д]+)зз""" to """\1зː""",
        """([йклмнпрстфхцчшщ])\1""" to """\1ː""",
        "дждж" to "джː", "дздз" to "дзː"
    ).map { (a, b) -> Sub(Regex(a), py(b)) }

    private val wsRe = Regex("(?U)\\s+")
    private val splitRe = Regex("(?U)[\\s\\-]+")

    private val s01 = sub("""([aɛiɪuɔ])([ˈˌ])""", """\2\1""")
    private val sPal1 = sub("(" + PALATALIZABLE_C + ")([ː]?)([ˈˌ⁀]?)i", """\1ʲ\2\3i""")
    private val sPal2 = sub("(" + PALATALIZABLE_C + ")([ː]?)j", """\1ʲ\2""")
    private val sA = listOf(
        sub("ʲːj", "ʲː"),
        sub("st͡sʲ([ː]?)", "sʲt͡sʲ")
    )
    private val sB = listOf(
        sub("([tdszn])(.)ʲ", """\1ʲ\2ʲ"""),
        sub("([tdsznl])t͡sʲ", """\1ʲt͡sʲ"""),
        sub("([tdsznl])d͡zʲ", """\1ʲd͡zʲ"""),
        sub("t͡s(.)ʲ", """t͡sʲ\1ʲ"""),
        sub("d͡z(.)ʲ", """d͡zʲ\1ʲ"""),
        sub("d͡zt͡sʲ", "d͡zʲt͡sʲ"),
        sub("t͡sd͡zʲ", "t͡sʲd͡zʲ"),
        sub("ʒt͡sʲ", "zʲt͡sʲ"),
        sub("t͡ʃt͡sʲ", "t͡sʲː"),
        sub("ʃt͡sʲ", "sʲt͡sʲ"),
        sub("ʃsʲ", "sʲː"),
        sub("zʒ", "ʒː"),
        sub("sʃ", "ʃː"),
        sub("zt͡ʃ", "ʒt͡ʃ"),
        sub("zd͡ʒ", "ʒd͡ʒ"),
        sub("t͡ʒ", "d͡ʒ"),
        sub("t͡z", "d͡z"),
        sub("([^aɛiɪuɔ]+)ʲ([^aɛiɪuɔ]+)ʲ([^aɛiɪuɔ]+)ʲ", """\1\2ʲ\3ʲ"""),
        sub("([^ˈˌ⁀])a", """\1ɐ"""),
        sub("([^ˈˌ⁀])u", """\1ʊ"""),
        sub("ɔ([bdzʒɡɦmnlrpftskxʲʃ͡]+)([ˈˌ⁀][uiʊ])", """o\1\2"""),
        sub("([^ˈˌ⁀])[ɛ]", """\1e"""),
        sub("⁀", ""),
        sub("(" + VOWEL_C + ")ʋ([" + CONSONANT_NO_W + "#])", """\1u̯\2"""),
        sub("ʋ([ˈˌ]?[ɔuoʊbdzʒɡɦmnlr])", """w\1"""),
        sub("ʋ([pftskxʃ])", """ʍ\1"""),
        sub("(" + VOWEL_C + ")j([" + CONSONANT_NO_W + "#])", """\1i̯\2"""),
        sub("#j(" + CONSONANT_NO_W_C + ")", """#i̯\1"""),
        sub("%", ""),
        sub("([^#" + VOWEL + "]?[ʲː]*)([ˈˌ])", """\2\1"""),
        sub("([^#" + VOWEL + "]͡)([ˈˌ])", """\2\1""")
    )
    private val onsetPattern = Regex("(.)(ʲ?)($CONSONANT_C)(ʲ?)([ˈˌ])($CONSONANT_C)")
    private val sC = listOf(
        sub("([^#" + VOWEL + "]͡)([ˈˌ])(.ʲ?j)", """\2\1\3"""),
        sub("([^#" + VOWEL + "]͡)([ˈˌ])(.ʲ?)", """\1\3\2"""),
        sub("#([^#" + VOWEL + "]+)([ˈˌ])", """#\2\1"""),
        sub("#([ui]̯)([ˈˌ])", """#\2\1"""),
        sub("ʲ?ːʲ", "ʲː"),
        sub("l([^ʲ])", """ɫ\1""")
    )
    private val vowelCount = Regex("[aɛiɪuɔ]")

    fun ipa(textIn: String): String {
        var text = textIn
        for ((k, v) in graveDecomp) text = text.replace(k.toString(), v)
        text = text.lowercase()
        text = wsRe.replace(text, " ")
        val out = ArrayList<String>()
        for (word in splitRe.split(text)) {
            var p = "#$word#"
            for (s in orthographic) p = s.apply(p)
            p = p.replace("'", "%")
            for (lvl in charMaps) for ((re, rep) in lvl) p = re.replace(p, rep)
            p = s01.apply(p)
            p = sPal1.apply(p)
            p = sPal2.apply(p)
            for (s in sA) p = s.apply(p)
            for (s in voicing) p = s.apply(p)
            p = applySB(p)
            out.add(p)
        }
        return out.joinToString(" ").replace("#", "")
    }

    private fun applySB(start: String): String {
        var p = start
        // sB entries up to the "t͡z" replacement, then the palatalization cleanup etc. (order as in the original)
        for (s in sB) p = s.apply(p)
        // onset-moving step comes after the two stress-mark steps (last two entries of sB)
        p = onsetPattern.replace(p) { m ->
            val (a, aj, b, bj, stress, c) = m.destructured
            val key = a + b + c
            when {
                key in permOnset -> stress + a + aj + b + bj + c
                (b + c) in permOnset || c == "j" -> a + aj + stress + b + bj + c
                else -> a + aj + b + bj + stress + c
            }
        }
        for (s in sC) p = s.apply(p)
        return p
    }
}
