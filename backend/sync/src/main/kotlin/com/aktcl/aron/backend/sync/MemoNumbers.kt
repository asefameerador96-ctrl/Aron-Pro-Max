package com.aktcl.aron.backend.sync

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The memo number rule of docs/24 s7.5 (D24-24, D24-48): `<username>-<yyMMdd>-<seq>`, where `username` is the
 * capturing user's lower-case login, `yyMMdd` the record's business date and `seq` lies in one of the user's ordinal
 * blocks (`bind_ordinal x block + 1 .. bind_ordinal x block + block`, ordinals 0 to 3) or overflow ranges
 * (`5000 + bind_ordinal x 1000 + 1 .. + 999`). Any ordinal of the four is accepted, never only the uploading phone's:
 * a phone rebound to another slot still uploads the numbers it printed under the old one, and a paper backfill comes
 * from no phone at all.
 */
object MemoNumbers {
    /** The uploader's login and the current `cfg.memo.seq_block_size` (bounds 100 to 999, as the bundle sends it). */
    class Rule(val username: String, val blockSize: Int)

    private val FORMAT = Regex("^([a-z][a-z0-9]{3,31})-(\\d{6})-(\\d{3,})$")
    private val YYMMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")
    private const val ORDINALS = 4

    /** Null when [memoNo] is the user's own number for [businessDate]; otherwise why not (the reject detail). */
    fun problem(rule: Rule, memoNo: String?, businessDate: LocalDate): String? {
        val m = FORMAT.matchEntire(memoNo ?: return "memo_no is missing") ?: return "memo_no is not <username>-<yyMMdd>-<seq>"
        val (user, date, seqText) = m.destructured
        if (user != rule.username.lowercase()) return "memo_no names another user"
        if (date != businessDate.format(YYMMDD)) return "memo_no date $date is not the business date $businessDate"
        val seq = seqText.toIntOrNull() ?: return "memo_no sequence $seqText is outside every block"
        if (!inRanges(seq, rule.blockSize)) return "memo_no sequence $seq is outside every block (block size ${rule.blockSize})"
        return null
    }

    fun inRanges(seq: Int, blockSize: Int): Boolean =
        seq in 1..ORDINALS * blockSize || (0 until ORDINALS).any { o -> seq in (5000 + o * 1000 + 1)..(5000 + o * 1000 + 999) }
}
