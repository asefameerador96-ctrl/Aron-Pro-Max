package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

@Serializable
data class DigestBucket(val count: Long, val hash: String)

@Serializable
data class DigestItem(val business_date: String, val type: String, val buckets: List<DigestBucket>)

@Serializable
data class SyncDigestRequest(val device_uuid: String, val items: List<DigestItem>)

@Serializable
data class DigestResend(val business_date: String, val type: String, val buckets: List<Int>)

@Serializable
data class SyncDigestResponse(val resend: List<DigestResend>)

/**
 * POST /v1/sync/digest (F-SYS-080 server half, docs/24 s4.8). Per (business date, type) the phone sends 16 buckets of
 * the client_uuids the server acked as stored; the server computes the same buckets and names those that differ, and
 * the phone re-sends their rows (trigger `digest_resend`; idempotent by client_uuid).
 *
 * The rule both sides compute (answer to android-core-backend-sync-digest.md):
 * - **Per device**: the rows the calling phone uploaded (user and device from the token), so a replaced or shared
 *   phone is never told to re-send rows it never had.
 * - Rows counted: registry status `accepted` or `voided` (stored at least once; an ack of `accepted` or `duplicate`).
 *   Rejected, quarantined and parked rows are not counted (a re-send changes nothing for them).
 * - Bucket = the first hex digit of the client_uuid (0..15). `count` = rows in the bucket. `hash` = the sum, modulo
 *   2^64, of the uuid's first 8 bytes read as a big-endian unsigned integer (its first 16 hex digits), written as 16
 *   lowercase hex digits (an empty bucket is `0000000000000000`).
 * - Dates from today back [WINDOW_DAYS] days (Dhaka); an item outside that window, or of a type the server does not
 *   ingest, is answered as matching (no re-send).
 */
class SyncDigestService(private val db: Database, private val clock: AronClock = AronClock.SYSTEM) {
    fun compare(p: AronPrincipal, req: SyncDigestRequest): SyncDigestResponse {
        val deviceId = p.deviceId ?: throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "a phone token is required")
        if (req.device_uuid.lowercase() != p.deviceUuid) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device_uuid differs from the token's device")
        if (req.items.size > MAX_ITEMS) throw invalid("/items", "at most $MAX_ITEMS items")
        val today = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()
        val items = req.items.mapIndexed { i, it ->
            val date = it.business_date.takeIf { d -> DATE.matches(d) }?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() }
                ?: throw invalid("/items/$i/business_date", "YYYY-MM-DD")
            if (it.buckets.size != 16) throw invalid("/items/$i/buckets", "16 buckets")
            it.buckets.forEachIndexed { b, bucket ->
                if (bucket.count < 0 || !HASH.matches(bucket.hash)) throw invalid("/items/$i/buckets/$b", "count >= 0, hash 16 lowercase hex digits")
            }
            if (it.type !in TypeRules.BY_TYPE.keys) throw invalid("/items/$i/type", "unknown record type")
            Triple(date, it.type, it.buckets)
        }
        val inWindow = items.filter { (d) -> !d.isAfter(today) && !d.isBefore(today.minusDays(WINDOW_DAYS)) }
        if (inWindow.isEmpty()) return SyncDigestResponse(emptyList())
        val server = buckets(p.userId, deviceId, inWindow.map { it.first }.distinct(), inWindow.map { it.second }.distinct())
        val resend = inWindow.mapNotNull { (date, type, phone) ->
            val mine = server[date to type] ?: EMPTY
            val differ = (0 until 16).filter { b -> phone[b].count != mine[b].count || phone[b].hash != mine[b].hash }
            if (differ.isEmpty()) null else DigestResend(date.toString(), type, differ)
        }
        return SyncDigestResponse(resend)
    }

    private fun buckets(userId: Long, deviceId: Long, dates: List<LocalDate>, types: List<String>): Map<Pair<LocalDate, String>, List<DigestBucket>> {
        val acc = HashMap<Pair<LocalDate, String>, Pair<LongArray, LongArray>>()
        db.jdbi.useHandle<Exception> { h ->
            h.createQuery(
                """
                SELECT business_date, record_type, client_uuid FROM app.ingest_registry
                WHERE user_id = :u AND device_id = :dev AND business_date = ANY(:d) AND record_type = ANY(:t) AND status IN ('accepted', 'voided')
                """.trimIndent(),
            ).bind("u", userId).bind("dev", deviceId).bindArray("d", LocalDate::class.java, dates).bindArray("t", String::class.java, types)
                .map { rs, _ -> Triple(rs.getObject(1, LocalDate::class.java), rs.getString(2), rs.getObject(3, UUID::class.java)) }
                .forEach { (date, type, uuid) ->
                    val (counts, sums) = acc.getOrPut(date to type) { LongArray(16) to LongArray(16) }
                    val hi = uuid.mostSignificantBits // the first 8 bytes, big-endian
                    val b = (hi ushr 60).toInt()      // the first hex digit
                    counts[b]++
                    sums[b] += hi                     // two's-complement addition is addition modulo 2^64
                }
        }
        return acc.mapValues { (_, v) -> (0 until 16).map { DigestBucket(v.first[it], hex(v.second[it])) } }
    }

    companion object {
        const val MAX_ITEMS = 200
        const val WINDOW_DAYS = 31L
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val HASH = Regex("^[0-9a-f]{16}$")
        private val EMPTY = List(16) { DigestBucket(0, "0000000000000000") }

        /** [v] as an unsigned 64-bit number in 16 lowercase hex digits. */
        fun hex(v: Long): String = java.lang.Long.toUnsignedString(v, 16).padStart(16, '0')

        private fun invalid(pointer: String, msg: String) =
            ApiProblem(ProblemCode.ERR_VALIDATION, msg, errors = listOf(FieldError(pointer, "invalid_value")))
    }
}
