package com.aktcl.aron.core.database.repo

import com.aktcl.aron.contract.ServerTotals
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.MemoEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** How one reconciliation row stands (docs/24 s4.12). A mismatch blocks nothing; it is shown with its reason. */
enum class ReconState { MATCH, MISMATCH, NO_SERVER_YET }

/**
 * Why device and server differ, most specific first. The Sales Submit screen (F-SR-034) shows a text per reason:
 * - [NOT_SENT]: rows of these types are still on the phone (pending or in a batch not yet answered);
 * - [AWAITING_SERVER]: everything was answered after the server's figures were taken; the next sync refreshes them;
 * - [SERVER_HAS_FEWER]: everything was answered and the server still counts fewer (a support case, never silent);
 * - [SERVER_HAS_MORE]: the server counts rows this phone no longer holds (reinstall, another phone, purged history).
 */
enum class ReconReason { NOT_SENT, AWAITING_SERVER, SERVER_HAS_FEWER, SERVER_HAS_MORE }

/**
 * One row of the reconciliation table: [device] committed rows of [types] for the date, [server] = accepted + rejected +
 * quarantined from the last `server_totals` (null before the first answer for the date: the Server column is blank and
 * [serverAsOf] is null; the screen shows [Reconciliation.deviceAsOf] instead), [awaited] = device - server when positive.
 */
data class ReconRow(
    val key: String,
    val types: List<String>,
    val device: Int,
    val server: Int?,
    val unsent: Int,
    val state: ReconState,
    val reason: ReconReason?,
) {
    val awaited: Int get() = if (server == null) device else (device - server).coerceAtLeast(0)
}

/**
 * Money and quantities of the date, device against server (exact: milli-taka and base units). [reason] says why they
 * differ (null when they match or before the first answer): [ReconReason.NOT_SENT] while money rows are still on the
 * phone, [ReconReason.AWAITING_SERVER] when rows were answered after the figures, otherwise [ReconReason.SERVER_HAS_FEWER]
 * (the server's figures differ: a support case, never silent).
 */
data class MoneyCheck(
    val device: JsonObject,
    val server: JsonObject?,
    val matches: Boolean?,
    val differingMembers: List<String>,
    val reason: ReconReason? = null,
)

data class Reconciliation(
    val businessDate: String,
    val rows: List<ReconRow>,
    val money: MoneyCheck,
    /** When the server's figures were taken (`ServerTotals.as_of`), or null before the first answer for the date. */
    val serverAsOf: String?,
    /** When the device figures were read (trusted time). */
    val deviceAsOf: String,
)

/**
 * Device-versus-server reconciliation (F-SYS-009, docs/24 s4.12). The device side is computed from Room only:
 * - [deviceCounts]: committed outbox rows per record type and business date (the batch's `device_counts`);
 * - [deviceMoney]: contract `MoneyTotals` from the stored memos, lines, due collections and stock movements, with the
 *   server's definitions (`IngestService.money`): sums over every active memo (not superseded), the count over those with
 *   line_count > 0; net by category and sold quantity from the lines of active memos; issued = every stock movement's
 *   qty_base. Rows the server refused or quarantined are left out (it stores them apart, never in the day's tables), and a
 *   refused edit does not retire the memo it meant to supersede. A memo or collection the server voided later is not
 *   known to the phone until the next bundle.
 * The server side is the last `server_totals` the sync engine stored per date ([KEY_SERVER_TOTALS]).
 */
class ReconciliationRepository(private val db: AronDatabase, private val nowIso: () -> String) {
    private val outbox = db.outboxDao()
    private val capture = db.captureDao()

    suspend fun deviceCounts(businessDate: String): Map<String, Int> = outbox.committedCounts(businessDate).associate { it.recordType to it.count }

    /** `device_counts` value of a date as JSON (batch and `day_submit`). */
    suspend fun deviceCountsJson(businessDate: String): JsonObject = JsonObject(deviceCounts(businessDate).mapValues { JsonPrimitive(it.value) })

    /** Contract `MoneyTotals` of the date (batch `device_money` and `day_submit.device_money`). */
    suspend fun deviceMoney(businessDate: String): JsonObject {
        val refused = outbox.refusedUuids().toSet()
        val stored = capture.memosOn(businessDate).filter { it.clientUuid !in refused }
        val superseded = capture.memosBetween(businessDate, MAX_DATE)
            .filter { it.clientUuid !in refused }.mapNotNull { it.supersedesClientUuid }.toSet()
        val active = stored.filter { it.clientUuid !in superseded }
        val categoryOf = db.referenceDao().skuCategories().associate { it.skuId to it.categoryCode }
        val byCategory = sortedMapOf<String, Long>()
        val sold = sortedMapOf<Long, Long>()
        val activeIds = active.mapTo(HashSet()) { it.clientUuid }
        // One query for the date's lines (a busy day has ~150 memos; one query per memo would run on every batch).
        for (l in capture.linesOn(businessDate)) {
            if (l.memoClientUuid !in activeIds || l.clientUuid in refused) continue
            categoryOf[l.skuId]?.takeIf { CATEGORY.matches(it) }?.let { byCategory[it] = (byCategory[it] ?: 0L) + l.grossMtk }
            sold[l.skuId] = (sold[l.skuId] ?: 0L) + l.qtyBase
        }
        val issued = sortedMapOf<Long, Long>()
        for (s in capture.stockOn(businessDate).filter { it.clientUuid !in refused }) issued[s.skuId] = (issued[s.skuId] ?: 0L) + s.qtyBase
        fun sum(f: (MemoEntity) -> Long) = active.sumOf(f)
        return buildJsonObject {
            put("active_memo_count", JsonPrimitive(active.count { it.lineCount > 0 }))
            put("gross_mtk", JsonPrimitive(sum { it.grossMtk }))
            put("offer_discount_mtk", JsonPrimitive(sum { it.offerDiscountMtk }))
            put("drp_discount_mtk", JsonPrimitive(sum { it.drpDiscountMtk }))
            put("qc_deduction_mtk", JsonPrimitive(sum { it.qcDeductionMtk }))
            put("net_mtk", JsonPrimitive(sum { it.netMtk }))
            put("paid_mtk", JsonPrimitive(sum { it.paidMtk }))
            put("due_mtk", JsonPrimitive(sum { it.dueMtk }))
            put("due_collected_mtk", JsonPrimitive(capture.dueCollectionsOn(businessDate).filter { it.clientUuid !in refused }.sumOf { it.amountMtk }))
            put("net_by_category_mtk", JsonObject(byCategory.mapValues { JsonPrimitive(it.value) }))
            put("issued_qty_base_by_sku", JsonObject(issued.entries.filter { it.key > 0 }.associate { it.key.toString() to JsonPrimitive(it.value) }))
            put("sold_qty_base_by_sku", JsonObject(sold.entries.filter { it.key > 0 }.associate { it.key.toString() to JsonPrimitive(it.value) }))
        }
    }

    /** The last `server_totals` the server sent for the date, or null before the first answer that covered it. */
    suspend fun serverTotals(businessDate: String): ServerTotals? = runCatching {
        db.referenceDao().meta(KEY_SERVER_TOTALS + businessDate)?.let { JSON.decodeFromString(ServerTotals.serializer(), it) }
    }.getOrNull()

    /**
     * The rows of [role] for the date. Rows come from `cfg.sync.reconcile_types` (R17 flat `ROLE.row` keys; the nested
     * `{ROLE: {row: [...]}}` shape is read too), in the config's order; without config, the SR default (outlet, sale, stock,
     * QC, promotion).
     */
    suspend fun reconcile(businessDate: String, role: String): Reconciliation {
        val now = nowIso()
        val rowsConfig = rowTypes(ReferenceRepository(db).config(CONFIG_KEY, now), role)
        val device = deviceCounts(businessDate)
        val unsent = outbox.unsentCounts(businessDate).associate { it.recordType to it.count }
        val totals = serverTotals(businessDate)
        val totalsAt = db.referenceDao().meta(KEY_SERVER_TOTALS_AT + businessDate)
        val server = totals?.byType?.mapValues { (_, v) -> outcomeSum(v) }
        val rows = rowsConfig.map { (key, types) ->
            val d = types.sumOf { device[it] ?: 0 }
            val s = server?.let { m -> types.sumOf { m[it] ?: 0 } }
            val u = types.sumOf { unsent[it] ?: 0 }
            val state = when {
                s == null -> ReconState.NO_SERVER_YET
                s == d -> ReconState.MATCH
                else -> ReconState.MISMATCH
            }
            val reason = when {
                state == ReconState.MATCH -> null
                u > 0 -> ReconReason.NOT_SENT
                state == ReconState.NO_SERVER_YET -> null
                s!! > d -> ReconReason.SERVER_HAS_MORE
                answeredAfter(businessDate, types, totalsAt) -> ReconReason.AWAITING_SERVER
                else -> ReconReason.SERVER_HAS_FEWER
            }
            ReconRow(key, types, d, s, u, state, reason)
        }
        val money = deviceMoney(businessDate)
        val serverMoney = totals?.money as? JsonObject
        val differing = serverMoney?.let { sm -> MONEY_MEMBERS.filter { normal(money[it]) != normal(sm[it]) } }.orEmpty()
        val moneyMatches = serverMoney?.let { differing.isEmpty() }
        val moneyReason = when {
            moneyMatches != false -> if (moneyMatches == null && MONEY_TYPES.any { (unsent[it] ?: 0) > 0 }) ReconReason.NOT_SENT else null
            MONEY_TYPES.any { (unsent[it] ?: 0) > 0 } -> ReconReason.NOT_SENT
            answeredAfter(businessDate, MONEY_TYPES, totalsAt) -> ReconReason.AWAITING_SERVER
            else -> ReconReason.SERVER_HAS_FEWER
        }
        return Reconciliation(businessDate, rows, MoneyCheck(money, serverMoney, moneyMatches, differing, moneyReason), totals?.asOf, now)
    }

    /**
     * True when a row of [types] was answered after the stored figures were received (a replayed answer applies acks but
     * never stores figures). Both times are the phone's own clock: the figures' receipt time is the `now` of the answer
     * that brought them, the same value its acks carry, so a shortfall in that very batch is not "awaiting".
     */
    private suspend fun answeredAfter(businessDate: String, types: List<String>, totalsAt: String?): Boolean {
        val at = totalsAt ?: return false
        return types.mapNotNull { outbox.lastAckedAt(businessDate, it) }.any { it > at }
    }

    companion object {
        /** sync_meta key prefix of the stored `ServerTotals` per business date (written by core-sync's SyncEngine). */
        const val KEY_SERVER_TOTALS = "sync.server_totals."

        /** sync_meta key prefix of the phone time at which those totals were received (the answer's `now`). */
        const val KEY_SERVER_TOTALS_AT = "sync.server_totals_at."

        /** Record types whose rows make up MoneyTotals. */
        private val MONEY_TYPES = listOf("memo", "memo_line", "memo_discount", "qc_line", "due_collection", "stock_movement")
        const val CONFIG_KEY = "cfg.sync.reconcile_types"
        private const val MAX_DATE = "9999-12-31"
        private val CATEGORY = Regex("^[a-z][a-z0-9_]{1,30}$")
        private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val MONEY_MEMBERS = listOf(
            "active_memo_count", "gross_mtk", "offer_discount_mtk", "drp_discount_mtk", "qc_deduction_mtk", "net_mtk", "paid_mtk",
            "due_mtk", "due_collected_mtk", "net_by_category_mtk", "issued_qty_base_by_sku", "sold_qty_base_by_sku",
        )

        /** SR rows of the db seed (V0006); used when the bundle carries no value for the role. */
        val SR_DEFAULT: List<Pair<String, List<String>>> = listOf(
            "outlet" to listOf("visit"), "sale" to listOf("memo"), "stock" to listOf("stock_movement"),
            "qc" to listOf("qc_line"), "promotion" to listOf("memo_discount"),
        )

        fun rowTypes(configJson: String?, role: String): List<Pair<String, List<String>>> {
            val o = configJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            fun types(e: JsonElement?) = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val flat = o?.entries?.filter { it.key.startsWith("$role.") }?.map { it.key.removePrefix("$role.") to types(it.value) }.orEmpty()
            if (flat.isNotEmpty()) return flat
            val nested = (o?.get(role) as? JsonObject)?.entries?.map { it.key to types(it.value) }.orEmpty()
            if (nested.isNotEmpty()) return nested
            return if (role == "SR") SR_DEFAULT else emptyList()
        }

        private fun outcomeSum(e: JsonElement): Int = runCatching {
            val o = e.jsonObject
            listOf("accepted", "rejected", "quarantined").sumOf { o[it]?.jsonPrimitive?.intOrNull ?: 0 }
        }.getOrDefault(0)

        /** Numbers compared as integers; objects member by member with absent = 0 (the server leaves out empty keys). */
        private fun normal(e: JsonElement?): Any? = when (e) {
            null -> null
            is JsonPrimitive -> e.longOrNull ?: e.contentOrNull
            is JsonObject -> e.entries.associate { it.key to (it.value.jsonPrimitive.longOrNull ?: 0L) }.filterValues { it != 0L }
            else -> e.toString()
        }
    }
}
