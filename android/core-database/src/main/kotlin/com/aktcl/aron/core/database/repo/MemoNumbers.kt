package com.aktcl.aron.core.database.repo

import androidx.room.withTransaction
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.MemoCounterEntity
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.LocalDate

/** Who numbers memos on this phone (from the login: username, `bind_ordinal`, `memo_seq_block_size`). */
data class MemoNumbering(val username: String, val bindOrdinal: Int, val blockSize: Int = 500) {
    init {
        require(Regex("^[a-z][a-z0-9]{3,31}$").matches(username)) { "memo usernames are lower-case, 4 to 32 characters, no hyphen" }
        require(bindOrdinal in 0..3) { "bind_ordinal is 0..3" }
        require(blockSize in 1..999) { "block size must keep blocks below the overflow range" }
    }
}

class MemoSeqExhausted(businessDate: String) : IllegalStateException("memo.seq_exhausted on $businessDate")

/**
 * Memo numbers of docs/24 s7.5 (F-SYS-027): `<username>-<yyMMdd>-<seq3>`, `seq = bind_ordinal × block + n` from this
 * phone's own counter per business date, so two phones of one user never collide offline; past the block the overflow
 * range `5000 + bind_ordinal × 1000 + (n − block)` (999 more), then `memo.seq_exhausted`.
 *
 * [reserve] commits the counter in its own transaction before the memo is written: a save that then fails burns the number
 * (the gap is reported by the server's memo-number-gaps report) and a number is never handed out twice.
 */
class MemoNumbers(private val db: AronDatabase) {
    private val dao = db.referenceDao()

    suspend fun reserve(businessDate: String, numbering: MemoNumbering): String = db.withTransaction {
        val n = (dao.memoCounter(businessDate) ?: 0) + 1
        val seq = seqOf(n, numbering) ?: throw MemoSeqExhausted(businessDate)
        dao.putMemoCounter(MemoCounterEntity(businessDate, n))
        compose(numbering.username, businessDate, seq)
    }

    companion object {
        fun seqOf(n: Int, numbering: MemoNumbering): Int? {
            require(n >= 1)
            val block = numbering.blockSize
            return when {
                n <= block -> numbering.bindOrdinal * block + n
                n - block <= 999 -> 5000 + numbering.bindOrdinal * 1000 + (n - block)
                else -> null
            }
        }

        fun compose(username: String, businessDate: String, seq: Int): String =
            "$username-${BusinessDate.yyMMdd(LocalDate.parse(businessDate))}-${seq.toString().padStart(3, '0')}"
    }
}
