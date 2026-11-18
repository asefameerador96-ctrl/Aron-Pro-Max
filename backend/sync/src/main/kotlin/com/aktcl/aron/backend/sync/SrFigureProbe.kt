package com.aktcl.aron.backend.sync

import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.util.UUID

/**
 * EXACT BY IDENTITY (the lead's ruling after the review of c6c799b69, D-TKPI-14; spike). The phone no longer infers from acknowledgements, resolutions, quarantine
 * states or clocks what the server figure contains: with its request it sends the client uuids of its own memos whose status in the figure it does not yet know
 * (never confirmed, pending, sent-unanswered, held, recently voided or edited), at most [MAX], and the server answers, in the SAME repeatable-read snapshot as the
 * figures, for each one:
 * - IN the figure (`inFigure`): the memo is stored, active, `line_count > 0`, not voided, has at least one non-voided line, and its business date lies from the 1st
 *   of the bundle month up to `through_date`; with the number of its stored lines (the phone sees a difference when a line was refused for good, e.g. a zero price);
 * - VOIDED (`voided`): the server holds it and it is gone for good (status voided or superseded, an admin data void, every line voided): the phone never adds it;
 * - NOT IN (everything else, not listed): not stored (never uploaded, in flight and not committed at the snapshot, held for review, refused), or stored but dated after
 *   `through_date`: the phone adds it if it is still a live sale locally.
 * A memo that is not the caller's own answers NOT IN (nothing is revealed). PAPER TWIN: a probe may carry `memoNo`; when its uuid is unknown to the server and an
 * own `manual` (paper backfill) memo with the same memo number and business date exists (the number is unique per date), the answer is that twin's, so the phone's
 * copy of a keyed paper memo is IN when the paper memo is, and the phone does not add it on top (LIMIT: the memo number must identify the memo; it is unique per business date over all memos,
 * so a mistyped number is simply another memo until the reviewer matches them; two candidates would answer NOT IN with a flag). For a memo the phone has voided or superseded locally
 * (`removedLocally`) that is IN, the answer also lists the stored lines (sku, base quantity, kind) so the phone subtracts exactly what the server holds.
 */
object SrFigureProbe {
    const val MAX = 300

    class Probe(val uuid: UUID, val memoNo: String?, val date: LocalDate, val removedLocally: Boolean = false, val removalUuid: UUID? = null)
    class Line(val skuId: Long, val qtyBase: Long, val kind: String)
    class Answer(val through: LocalDate, val inFigure: Map<UUID, Int>, val voided: Set<UUID>, val removedLines: Map<UUID, List<Line>>, val twinAmbiguous: Set<UUID> = emptySet(), val refused: Set<UUID> = emptySet()) {
        companion object { fun empty(through: LocalDate) = Answer(through, emptyMap(), emptySet(), emptyMap()) }
    }

    private class Row(val uuid: UUID, val date: LocalDate, val status: String, val voided: Boolean, val lineCount: Int, val memoNo: String)

    fun answer(h: Handle, userId: Long, probes: List<Probe>, first: LocalDate, through: LocalDate): Answer {
        val list = probes.distinctBy { it.uuid }.take(MAX)
        if (list.isEmpty()) return Answer.empty(through)
        fun rows(sql: String, bind: (org.jdbi.v3.core.statement.Query) -> org.jdbi.v3.core.statement.Query) = bind(h.createQuery(sql)).map { rs, _ ->
            Row(rs.getObject(1, UUID::class.java), rs.getObject(2, LocalDate::class.java), rs.getString(3), rs.getTimestamp(4) != null, rs.getInt(5), rs.getString(6))
        }.list()
        val cols = "client_uuid, business_date, status, voided_at, line_count, memo_no"
        val own = "AND (user_id = :u OR acting_for_user_id = :u)"
        val byUuid = rows("SELECT $cols FROM app.memo WHERE client_uuid = ANY(:c) AND business_date = ANY(:d) $own") { q ->
            q.bindArray("c", UUID::class.java, list.map { it.uuid }).bindArray("d", LocalDate::class.java, list.map { it.date }.distinct()).bind("u", userId)
        }.associateBy { it.uuid }
        // the paper twin of a probe the server does not know: same own memo number and date, entered by the office (entry_source manual)
        val unknown = list.filter { it.uuid !in byUuid && it.memoNo != null }
        val twins = if (unknown.isEmpty()) emptyMap() else rows("SELECT $cols FROM app.memo WHERE memo_no = ANY(:n) AND business_date = ANY(:d) AND entry_source = 'manual' $own") { q ->
            q.bindArray("n", String::class.java, unknown.map { it.memoNo!! }.distinct()).bindArray("d", LocalDate::class.java, unknown.map { it.date }.distinct()).bind("u", userId)
        }.groupBy { it.memoNo to it.date }
        // (memo_no, business_date) is unique over ALL memos (index memo_no_unique), so at most one candidate exists; if two ever did, the answer is NOT IN and a flag, never a guess
        val ambiguous = list.filter { it.uuid !in byUuid && (twins[it.memoNo to it.date]?.size ?: 0) > 1 }.map { it.uuid }.toSet()
        val matched = list.mapNotNull { p -> (byUuid[p.uuid] ?: twins[p.memoNo to p.date]?.singleOrNull())?.let { p to it } }
        if (matched.isEmpty()) return Answer(through, emptyMap(), emptySet(), emptyMap(), ambiguous, refusedOf(h, userId, (list.filter { it.uuid !in byUuid }.map { it.uuid } + list.mapNotNull { it.removalUuid }).distinct()))
        // every stored line per memo, voided ones marked: a memo whose lines are not stored yet has none (NOT IN), one whose stored lines are all voided is gone for good
        class StoredLine(val line: Line, val voided: Boolean)
        val lineRows = h.createQuery(
            "SELECT memo_client_uuid, sku_id, qty_base, line_kind, voided_at IS NOT NULL FROM app.memo_line WHERE memo_client_uuid = ANY(:c) AND business_date = ANY(:d)",
        ).bindArray("c", UUID::class.java, matched.map { it.second.uuid }.distinct()).bindArray("d", LocalDate::class.java, matched.map { it.second.date }.distinct())
            .map { rs, _ -> rs.getObject(1, UUID::class.java) to StoredLine(Line(rs.getLong(2), rs.getLong(3), rs.getString(4)), rs.getBoolean(5)) }.list().groupBy({ it.first }, { it.second })
        val inFigure = HashMap<UUID, Int>(); val voided = HashSet<UUID>(); val removed = HashMap<UUID, List<Line>>()
        for ((p, m) in matched) {
            val stored = lineRows[m.uuid].orEmpty(); val live = stored.filter { !it.voided }.map { it.line }
            when {
                m.voided || m.status != "active" -> voided += p.uuid                                   // voided, superseded, admin-voided: gone for good
                live.isNotEmpty() && m.lineCount > 0 && !m.date.isBefore(first) && !m.date.isAfter(through) -> {
                    inFigure[p.uuid] = live.size
                    if (p.removedLocally) removed[p.uuid] = live
                }
                stored.isNotEmpty() && live.isEmpty() -> voided += p.uuid                              // every stored line is voided: gone for good
                // else: active with no stored line yet (lines still held), or dated after through_date: NOT IN
            }
        }
        // REFUSED: the caller's OWN records the server will never store, and removing records the phone names that are refused
        val askedRefused = (list.filter { it.uuid !in inFigure && it.uuid !in voided && !(byUuid.containsKey(it.uuid)) }.map { it.uuid } + list.mapNotNull { it.removalUuid }).distinct()
        val refused = refusedOf(h, userId, askedRefused)
        return Answer(through, inFigure, voided, removed, ambiguous, refused)
    }

    /** The asked uuids that are REFUSED for the caller: final registry rejects and reviewer discards (not return-to-device). */
    private fun refusedOf(h: Handle, userId: Long, asked: List<UUID>): Set<UUID> {
        if (asked.isEmpty()) return emptySet()
        val finals = com.aktcl.aron.contract.RecordOutcomeCode.entries.filter { it.status == com.aktcl.aron.contract.AckStatus.REJECTED && it.retryable == false }.map { it.wire }
        return h.createQuery(
            """
            SELECT client_uuid FROM app.ingest_registry WHERE client_uuid = ANY(:u) AND user_id = :user AND status = 'rejected' AND outcome_code = ANY(:f)
            UNION
            SELECT client_uuid FROM app.sync_quarantine WHERE client_uuid = ANY(:u) AND user_id = :user AND status = 'discarded' AND coalesce(resolution_note, '') NOT LIKE 'return_to_device%'
            """.trimIndent(),
        ).bindArray("u", UUID::class.java, asked).bindArray("f", String::class.java, finals).bind("user", userId).map { rs, _ -> rs.getObject(1, UUID::class.java) }.list().toSet()
    }
}
