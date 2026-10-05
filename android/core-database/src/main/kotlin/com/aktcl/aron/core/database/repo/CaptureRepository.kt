package com.aktcl.aron.core.database.repo

import androidx.room.withTransaction
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.QcLineEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.record.RecordMapping
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A complete sale as the SR saves it: the memo with its lines, discount lines and QC lines (docs/24 s4.2, s7.4). */
data class SaleCapture(
    val memo: MemoEntity,
    val lines: List<MemoLineEntity>,
    val discounts: List<MemoDiscountEntity> = emptyList(),
    val qcLines: List<QcLineEntity> = emptyList(),
    val editFix: GeoFixEntity? = null,
)

/**
 * Commits captures (docs/24 s4.1 item 1, s5.2): the domain rows and their outbox rows in ONE Room transaction, so a kill
 * at any point leaves either the whole capture with its outbox rows or nothing. The network is never touched here.
 * A duplicate client UUID aborts the whole transaction.
 */
class CaptureRepository(
    private val db: AronDatabase,
    private val nowIso: () -> String = { ISO_MILLIS.format(Instant.now()) },
) {
    private val capture = db.captureDao()
    private val outbox = db.outboxDao()

    suspend fun recordAttendance(event: AttendanceEventEntity, fix: GeoFixEntity) = db.withTransaction {
        requireUuids(event.clientUuid, fix.clientUuid)
        requireFixOf(fix, event.clientUuid, event.fixClientUuid)
        capture.insertFix(fix)
        capture.insertAttendance(event)
        outbox.insert(listOf(RecordMapping.attendance(event, fix, nowIso())))
    }

    /** One Save of the Stock screen: one event per SKU, all or nothing. */
    suspend fun recordStock(movements: List<StockMovementEntity>) {
        require(movements.isNotEmpty()) { "nothing to save" }
        requireUuids(*movements.map { it.clientUuid }.toTypedArray())
        db.withTransaction {
            capture.insertStock(movements)
            val at = nowIso()
            outbox.insert(movements.map { RecordMapping.stock(it, at) })
        }
    }

    suspend fun recordVisitOpen(visit: VisitEntity, fix: GeoFixEntity) = db.withTransaction {
        requireUuids(visit.clientUuid, fix.clientUuid)
        requireRoute(visit.meta)
        requireFixOf(fix, visit.clientUuid, visit.fixClientUuid)
        capture.insertFix(fix)
        capture.insertVisit(visit)
        outbox.insert(listOf(RecordMapping.visit(visit, fix, nowIso())))
    }

    /**
     * Saves a memo with everything that belongs to it. Outbox order inside the transaction follows the family ranks
     * (memo, then lines and discounts, then QC lines), so the batch carries parents before children (s4.2).
     */
    suspend fun recordSale(sale: SaleCapture) {
        val m = sale.memo
        require(sale.lines.all { it.memoClientUuid == m.clientUuid }) { "every line must belong to the memo" }
        require(sale.discounts.all { it.memoClientUuid == m.clientUuid }) { "every discount line must belong to the memo" }
        require(sale.qcLines.all { it.visitClientUuid == m.visitClientUuid }) { "QC lines must belong to the memo's visit" }
        // QC lines saved with the memo are deducted on it (s7.4); a line for another memo would break qc_deduction.
        require(sale.qcLines.all { it.memoClientUuid == m.clientUuid && it.appliedToMemo }) { "QC lines saved with a memo must be applied to that memo" }
        require((m.editFixClientUuid == null) == (sale.editFix == null)) { "an edited memo carries its edit fix, and only then" }
        // An edit is a new memo that supersedes the old one, with a reason and a fresh fix for the server's geofence re-check.
        require((m.supersedesClientUuid != null) == (sale.editFix != null && m.editReasonCode != null)) {
            "an edited memo carries supersedes_client_uuid, edit_reason_code and its edit fix together"
        }
        requireUuids(
            m.clientUuid, m.visitClientUuid, *sale.lines.map { it.clientUuid }.toTypedArray(),
            *sale.discounts.map { it.clientUuid }.toTypedArray(), *sale.qcLines.map { it.clientUuid }.toTypedArray(),
        )
        m.supersedesClientUuid?.let { requireUuids(it) }
        sale.editFix?.let { requireUuids(it.clientUuid) }
        (listOf(m.meta) + sale.lines.map { it.meta } + sale.discounts.map { it.meta } + sale.qcLines.map { it.meta }).forEach(::requireRoute)
        require(m.lineCount == sale.lines.size && m.discountLineCount == sale.discounts.size && m.qcLineCount == sale.qcLines.size) {
            "memo counts must match the lines committed with it"
        }
        db.withTransaction {
            val visit = checkNotNull(capture.visit(m.visitClientUuid)) { "memo ${m.memoNo} has no visit on this phone" }
            require(visit.outletId == m.outletId) { "memo ${m.memoNo} is for another outlet than its visit" }
            sale.editFix?.let {
                requireFixOf(it, m.clientUuid, m.editFixClientUuid)
                capture.insertFix(it)
            }
            capture.insertMemo(m)
            capture.insertMemoLines(sale.lines)
            capture.insertMemoDiscounts(sale.discounts)
            capture.insertQcLines(sale.qcLines)
            val at = nowIso()
            val family = m.visitClientUuid
            outbox.insert(
                listOf(RecordMapping.memo(m, sale.editFix, at)) +
                    sale.lines.sortedBy { it.lineNo }.map { RecordMapping.memoLine(it, family, at) } +
                    sale.discounts.map { RecordMapping.memoDiscount(it, family, at) } +
                    sale.qcLines.map { RecordMapping.qcLine(it, at) },
            )
        }
    }

    suspend fun recordVisitClose(close: VisitCloseEntity) = db.withTransaction {
        requireUuids(close.clientUuid, close.visitClientUuid)
        requireRoute(close.meta)
        checkNotNull(capture.visit(close.visitClientUuid)) { "visit_close without its visit" }
        capture.insertVisitClose(close)
        outbox.insert(listOf(RecordMapping.visitClose(close, nowIso())))
    }

    /** Device ids are lower-case UUID v4 (contract `Uuid`); anything else would be rejected on upload as schema_invalid. */
    private fun requireUuids(vararg ids: String) {
        ids.forEach { require(ClientIds.isUuidV4(it)) { "not a lower-case UUID v4: $it" } }
    }

    /** `route_id` is required on visit families and route-day records (docs/24 s4.3). */
    private fun requireRoute(meta: CaptureMeta) {
        require(meta.routeId != null) { "visit-family records carry the route worked (route_id)" }
    }

    private fun requireFixOf(fix: GeoFixEntity, ownerUuid: String, referencedFix: String?) {
        require(fix.ownerClientUuid == ownerUuid) { "the fix must belong to the record that took it" }
        require(referencedFix == fix.clientUuid) { "the record must reference its fix" }
    }

    private companion object {
        /** RFC 3339 UTC with exactly three fraction digits, as on the wire (docs/24 s3.1 item 5). */
        val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
