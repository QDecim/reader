package ua.vatsuo.chytalka.bench

/** Pure Kotlin (no Android imports): current/energy math and summary recovery from CSV files. Testable on a plain JVM. */

object CurrentMath {
    /**
     * BATTERY_PROPERTY_CURRENT_NOW is µA by the documentation, but some phones (seen: OnePlus Nord 2) report mA.
     * Under load a phone draws 100..3000 mA = 100 000..3 000 000 µA; the median of |values| tells the unit apart.
     */
    fun isMilli(absRaw: List<Double>): Boolean {
        val nz = absRaw.filter { it > 0 }
        if (nz.isEmpty()) return false
        return Stats.median(nz) < 3000.0
    }
    fun toMa(raw: Double, milli: Boolean) = if (milli) raw else raw / 1000.0
    /** integral of |I| in raw-units * seconds -> mAh */
    fun mAh(rawSeconds: Double, milli: Boolean) = if (milli) rawSeconds / 3600.0 else rawSeconds / 3.6e6
}

object Energy {
    const val LIMIT_PCT_PER_HOUR = 10.0

    /** Lines about "mAh per hour of audio"; net = total minus background (mode C average mA × wall time). */
    fun lines(mAh: Double, wallS: Double, audioS: Double, bgMa: Double, capacityMah: Int): List<String> {
        val out = ArrayList<String>()
        if (audioS <= 0 || wallS <= 0) return out
        val bg = if (bgMa.isNaN()) 0.0 else bgMa * wallS / 3600.0
        out.add(if (bgMa.isNaN()) "Фон (режим C): немає, віднімання пропущено (число завищене на фон системи/екрана)"
                else "Фон (режим C): %.0f мА × %.2f год = %.1f мА·год".format(bgMa, wallS / 3600.0, bg))
        val net = mAh - bg
        val perHour = net / (audioS / 3600.0)
        out.add("ГОЛОВНЕ ЧИСЛО (за струмом): %.0f мА·год на годину аудіо".format(perHour))
        if (capacityMah > 0) {
            val pct = perHour / capacityMah * 100
            out.add("= %.1f%% ємності (%d мА·год) на годину аудіо; поріг ≤%.0f%%: %s".format(pct, capacityMah, LIMIT_PCT_PER_HOUR, if (pct <= LIMIT_PCT_PER_HOUR) "ПРОЙДЕНО" else "НЕ пройдено"))
        } else out.add("Вкажіть ємність батареї, щоб порівняти з порогом 10%")
        if (net <= 0) out.add("!! чисте споживання ≤ 0: фон завищений, повторіть режим C")
        return out
    }
}

object Recovery {
    class Result(val summary: String, val wallS: Double, val avgMa: Double, val mode: String?)

    private val thermalNames = listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")

    private class Table(text: String) {
        val head: List<String>
        val rows: List<List<String>>
        init {
            val lines = text.lines().filter { it.isNotBlank() }
            head = if (lines.isEmpty()) emptyList() else lines[0].split(',')
            rows = lines.drop(1).map { it.split(',') }.filter { it.size == head.size }
        }
        fun has(c: String) = c in head
        fun d(c: String): List<Double> { val i = head.indexOf(c); return if (i < 0) emptyList() else rows.mapNotNull { it[i].toDoubleOrNull() } }
        fun s(c: String): List<String> { val i = head.indexOf(c); return if (i < 0) emptyList() else rows.map { it[i] } }
    }

    fun build(stamp: String, modeLabel: String?, samplesCsv: String, sentCsv: String, capacityMah: Int, bgMa: Double, exitInfo: String?): Result {
        val sm = Table(samplesCsv); val st = Table(sentCsv)
        val sb = StringBuilder()
        val t = sm.d("t_s")
        if (t.isEmpty()) {
            sb.appendLine("=== ВІДНОВЛЕНО $stamp: samples порожній, даних немає ===")
            exitInfo?.let { sb.appendLine(it) }
            return Result(sb.toString().trimEnd(), 0.0, Double.NaN, modeLabel)
        }
        val wall = t.last()
        val mode = modeLabel ?: st.s("mode").firstOrNull()
        sb.appendLine("=== ПІДСУМОК (ВІДНОВЛЕНО з CSV) $stamp, режим: ${mode ?: "невідомий (рядків речень нема; ймовірно C/D)"} ===")
        sb.appendLine("!! Прогін НЕ завершився нормально: процес, схоже, було вбито системою на ~%.1f хв від старту.".format(wall / 60))
        exitInfo?.let { sb.appendLine("Причини смерті процесу (Android):"); sb.appendLine(it) }
        sb.appendLine("Wall (до останнього запису): %.1f с (%.1f хв)".format(wall, wall / 60))

        val pct = sm.d("pct"); val ch = sm.d("charge_uah")
        if (pct.isNotEmpty()) sb.appendLine("Батарея: %.0f%% -> %.0f%%, лічильник %.0f -> %.0f мкА·год".format(pct.first(), pct.last(), ch.firstOrNull() ?: 0.0, ch.lastOrNull() ?: 0.0))

        val curCol = if (sm.has("current_avg_raw")) "current_avg_raw" else "current_ua"
        val raw = sm.d(curCol).map { Math.abs(it) }
        var avgMa = Double.NaN
        if (raw.isNotEmpty() && raw.any { it > 0 }) {
            val milli = CurrentMath.isMilli(raw)
            avgMa = raw.map { CurrentMath.toMa(it, milli) }.average()
            val mAh = avgMa * wall / 3600.0
            sb.appendLine("Струм: одиниці %s (авто), середній %.0f мА, заряд за струмом %.1f мА·год".format(if (milli) "мА" else "мкА", avgMa, mAh))
        } else sb.appendLine("Струм: даних нема")

        val temp = sm.d("temp_c")
        if (temp.isNotEmpty()) sb.appendLine("Темп.: %.1f -> макс %.1f °C; макс. термо-статус: %s".format(temp.first(), temp.max(), sm.s("thermal").maxByOrNull { thermalNames.indexOf(it) } ?: "?"))
        val hwm = sm.d("vmhwm_mb"); val pss = sm.d("native_pss_mb")
        if (hwm.isNotEmpty()) sb.appendLine("Пам'ять: піковий VmHWM %.0f МБ, native PSS (пік) %.0f МБ".format(hwm.max(), pss.maxOrNull() ?: Double.NaN))

        val audio = st.d("audio_s").sum()
        val rtf = st.d("rtf_inf")
        if (rtf.isNotEmpty()) {
            sb.appendLine("Речень: %d, RTF (інференс) медіана %.3f, p95 %.3f, аудіо %.1f с".format(rtf.size, Stats.median(rtf), Stats.percentile(rtf, 95.0), audio))
            if (!avgMa.isNaN()) Energy.lines(avgMa * wall / 3600.0, wall, audio, bgMa, capacityMah).forEach { sb.appendLine(it) }
        }
        return Result(sb.toString().trimEnd(), wall, avgMa, mode)
    }
}
