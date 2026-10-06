package com.aktcl.aron.rules

/** A normalised value and whether normalisation changed the input (ingest flags a changed value, F-SYS-070). */
data class Normalised(val value: String, val changed: Boolean)

/** Text normalisation and Bangla collation (F-SYS-070); identical on phone and server. */
object TextRules {
    /** Converts Bengali (U+09E6..09EF) and Eastern Arabic-Indic (U+06F0..06F9, U+0660..0669) digits to ASCII; other characters untouched. */
    fun toWesternDigits(s: String): Normalised {
        val out = s.map {
            when (it) {
                in '০'..'৯' -> '0' + (it - '০')
                in '۰'..'۹' -> '0' + (it - '۰')
                in '٠'..'٩' -> '0' + (it - '٠')
                else -> it
            }
        }.joinToString("")
        return Normalised(out, out != s)
    }

    /**
     * Normalises a Bangladesh mobile number to 11 digits `01XXXXXXXXX`: digits only (Bengali converted), `+880`/`880`/`00880`
     * prefix and a missing leading 0 are repaired. Returns null when the result is not 11 digits starting `01`.
     */
    fun normalisePhone(raw: String): Normalised? {
        var d = toWesternDigits(raw).value.filter { it in '0'..'9' }
        if (d.startsWith("00880")) d = d.substring(4) else if (d.startsWith("880") && d.length == 13) d = d.substring(2)
        if (d.length == 10 && d.startsWith("1")) d = "0$d"
        if (d.length != 11 || !d.startsWith("01")) return null
        return Normalised(d, d != raw)
    }

    /**
     * Sort key for outlet names: Bengali digits to ASCII, Latin lower-cased, zero-width joiners removed, whitespace collapsed and trimmed.
     * Order keys by code point (UTF-8 byte order: SQLite BINARY, PostgreSQL `COLLATE "C"`): Latin sorts before Bangla, Bangla in
     * Unicode (alphabet) order. The key is computed here and stored, so no database collation can differ.
     */
    fun nameSortKey(name: String): String {
        val sb = StringBuilder()
        var space = false
        for (c in toWesternDigits(name).value.lowercase()) {
            if (c == '‌' || c == '‍') continue
            if (c.isWhitespace()) { space = true; continue }
            if (space && sb.isNotEmpty()) sb.append(' ')
            space = false
            sb.append(c)
        }
        return sb.toString()
    }
}
