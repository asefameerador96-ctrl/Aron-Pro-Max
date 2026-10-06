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
     * prefix and a missing leading 0 are repaired (also `+880 0171...`). Returns null on letters or other junk, or when the result is not 11 digits `01[3-9]XXXXXXXX`.
     */
    fun normalisePhone(raw: String): Normalised? {
        val w = toWesternDigits(raw).value
        if (w.any { it !in '0'..'9' && it != '+' && it != ' ' && it != '-' && it != '(' && it != ')' && it != '.' }) return null
        var d = w.filter { it in '0'..'9' }
        if (d.startsWith("00880")) d = d.substring(2)
        if (d.startsWith("880") && d.length == 13) d = d.substring(2)
        else if (d.startsWith("8800") && d.length == 14) d = d.substring(3)
        if (d.length == 10 && d.startsWith("1")) d = "0$d"
        if (d.length != 11 || !(d.startsWith("01") && d[2] in '3'..'9')) return null
        return Normalised(d, d != raw)
    }

    /**
     * Sort key for outlet names: Bengali digits to ASCII, Latin lower-cased, invisible format characters removed (ZWSP, ZWNJ, ZWJ, LRM, RLM, BOM, ...), Bangla in NFC, whitespace collapsed and trimmed.
     * Order keys with [compareKeys] or by UTF-8 bytes (SQLite BINARY, PostgreSQL `COLLATE "C"`): Latin sorts before Bangla, Bangla in
     * Unicode (alphabet) order. The key is computed here and stored, so no database collation can differ.
     */
    fun nameSortKey(name: String): String {
        val sb = StringBuilder()
        var space = false
        for (c in nfcBangla(toWesternDigits(name).value).lowercase()) {
            if (isFormatChar(c)) continue
            if (c.isWhitespace()) { space = true; continue }
            if (space && sb.isNotEmpty()) sb.append(' ')
            space = false
            sb.append(c)
        }
        return sb.toString()
    }

    /** Orders two keys by Unicode code point (= UTF-8 byte order), not UTF-16 unit order; surrogate pairs sort above U+E000..U+FFFF. */
    fun compareKeys(a: String, b: String): Int {
        fun fix(c: Char): Int = if (c.isSurrogate()) c.code + 0x2000 else if (c.code >= 0xE000) c.code - 0x800 else c.code
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            if (a[i] != b[i]) return fix(a[i]) - fix(b[i])
        }
        return a.length - b.length
    }

    /** Invisible format characters stripped from names: ZWSP/ZWNJ/ZWJ/LRM/RLM, bidi controls, word joiners, soft hyphen and BOM. */
    private fun isFormatChar(c: Char): Boolean =
        c in '\u200B'..'\u200F' || c in '\u202A'..'\u202E' || c in '\u2060'..'\u2064' || c in '\u2066'..'\u2069' || c == '\uFEFF' || c == '\u00AD'

    /**
     * Canonical (NFC) form for the Bangla block, the only script this field uses besides Latin: the composition-excluded nukta
     * letters U+09DC, U+09DD, U+09DF decompose to base + U+09BC; U+09C7 + U+09BE and U+09C7 + U+09D7 compose to U+09CB and U+09CC.
     */
    internal fun nfcBangla(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\u09DC' -> sb.append('\u09A1').append('\u09BC')
                '\u09DD' -> sb.append('\u09A2').append('\u09BC')
                '\u09DF' -> sb.append('\u09AF').append('\u09BC')
                '\u09BE' -> if (sb.isNotEmpty() && sb.last() == '\u09C7') { sb.setLength(sb.length - 1); sb.append('\u09CB') } else sb.append(c)
                '\u09D7' -> if (sb.isNotEmpty() && sb.last() == '\u09C7') { sb.setLength(sb.length - 1); sb.append('\u09CC') } else sb.append(c)
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
