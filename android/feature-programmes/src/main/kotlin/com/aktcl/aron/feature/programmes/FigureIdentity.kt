package com.aktcl.aron.feature.programmes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Phone rule EXACT BY IDENTITY (docs/requests/sr-programmes-phone-rule-b.md, contract `postSyncFigures` and `RouteSnapshot`): the phone does not infer what the server figure holds from
 * acknowledgements, resolutions, quarantine states or ordering; it asks. Everything the arithmetic needs from the server is behind ONE small interface, [ServerHolds]: for an own memo, IN the figure,
 * VOIDED (gone for good), REFUSED (never stored: a final reject or a reviewer's discard; dropped and never asked again), NOT IN, or UNKNOWN (only a source that cannot tell: the old acknowledged-set inference for a server without the endpoint). Nothing here reads a timestamp or a clock.
 */
enum class Holds { IN, VOIDED, REFUSED, NOT_IN, UNKNOWN }

/** What the figures figure holds, for an own memo uuid. */
fun interface ServerHolds { fun of(uuid: String): Holds }

/** One stored line of a memo the figures counted (`FigureStoredLine`): what to subtract when the phone removed the memo. */
data class StoredLine(val skuId: Long, val qtyBase: Long, val lineKind: String)

/** An IN answer: the stored non-voided line count, and the stored lines when the probe said `removed_locally`. */
data class FigureIn(val lines: Int, val storedLines: List<StoredLine>?)

/** The `/v1/sync/figures` answer: three states per probed uuid, the route sections as sent (parsed by their own readers) and the optional Astha section. */
class FiguresAnswer(
    val businessDate: String?, val throughDate: String?, val inFigure: Map<String, FigureIn>, val voided: Set<String>, val twinAmbiguous: Set<String>,
    val refused: Set<String>, val routes: JsonArray, val astha: JsonObject?, val raw: String,
) : ServerHolds {
    /**
     * EXACTLY four states: IN, VOIDED, REFUSED (also a removal uuid whose removal was refused) or NOT IN (listed nowhere: never uploaded, in flight and not committed in the snapshot, held and open, dated after
     * through_date, an active memo whose lines are not stored yet, or not the caller's own). The NOT IN answers of different requests are never combined: a total uses ONE answer.
     */
    override fun of(uuid: String): Holds = when { uuid in refused -> Holds.REFUSED; uuid in voided -> Holds.VOIDED; uuid in inFigure -> Holds.IN; else -> Holds.NOT_IN }
}

object FiguresAnswerParser {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The answer, or null for a body that is not a figures answer (a missing or unreadable `figure_probe`): the caller then keeps using the bundle's figures. */
    fun parse(body: String): FiguresAnswer? {
        val o = runCatching { lenient.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val probe = o["figure_probe"] as? JsonObject ?: return null
        fun s(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull
        val inFigure = LinkedHashMap<String, FigureIn>()
        (probe["in_figure"] as? JsonArray).orEmpty().forEach { e ->
            val m = e as? JsonObject ?: return@forEach
            val uuid = s(m["memo_client_uuid"]) ?: return@forEach
            val stored = (m["stored_lines"] as? JsonArray)?.mapNotNull { l ->
                val lo = l as? JsonObject ?: return@mapNotNull null
                StoredLine((lo["sku_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null, (lo["qty_base"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null, s(lo["line_kind"]) ?: "sale")
            }
            inFigure[uuid] = FigureIn(((m["lines"] as? JsonPrimitive)?.longOrNull ?: 1L).toInt(), stored)
        }
        val voided = (probe["voided"] as? JsonArray).orEmpty().mapNotNull { s(it) }.toSet()
        val twins = (probe["twin_ambiguous"] as? JsonArray).orEmpty().mapNotNull { s(it) }.toSet()
        val refused = (probe["refused"] as? JsonArray).orEmpty().mapNotNull { s(it) }.toSet()
        return FiguresAnswer(s(o["business_date"]), s(o["through_date"]), inFigure, voided, twins, refused, (o["routes"] as? JsonArray) ?: JsonArray(emptyList()), o["astha"] as? JsonObject, body)
    }
}

/** The OLD inference behind the same interface, for a phone whose server has no `/figures` answer: a memo in the acknowledged set is IN, one the server holds or may hold is UNKNOWN, a never-sent one is NOT IN. */
class AckedSetHolds(private val acked: Set<String>?, private val memos: List<LocalMemo>) : ServerHolds {
    private val byUuid = memos.associateBy { it.uuid }
    override fun of(uuid: String): Holds {
        if (acked != null && uuid in acked) return Holds.IN
        return when (byUuid[uuid]?.state) {
            "pending" -> Holds.NOT_IN
            else -> Holds.UNKNOWN // acknowledged after the request, sent, held, resolved: the server may or may not hold it
        }
    }
}

object FigureIdentity {
    /** A memo the phone removed and that the figure still holds: SUBTRACT these stored lines (what the figures counted), with the memo's own value per SKU when the phone's line matches. */
    data class Subtract(val memo: LocalMemo, val lines: List<LocalMemoLine>)
    data class Delta(val add: List<LocalMemo>, val subtract: List<Subtract>, val unknown: List<LocalMemo>)

    /** Memos the phone no longer counts as live: superseded or voided locally by a record the server did not REFUSE (a refused void or edit removes nothing: the old memo stays live, the refused edit is dropped). */
    private fun removed(memos: List<LocalMemo>, holds: ServerHolds): Set<String> {
        val live = memos.filter { it.state != "rejected" && holds.of(it.uuid) != Holds.REFUSED }
        return live.flatMap { listOfNotNull(it.supersedes, it.voidOf) }.toSet()
    }

    /**
     * The rule, for each own memo of the bundle [month], by what [holds] says (the answer of one snapshot): (1) dated after [throughDate]: ADD when live on the phone and not VOIDED; (2) dated up to it and
     * live: IN adds nothing, VOIDED drops it, NOT IN ADDS it (UNKNOWN, only from the old inference, adds nothing); (3) dated up to it and removed on the phone: IN SUBTRACTS the stored lines of the
     * answer, anything else nothing; (4) a memo stored with FEWER lines than sent is IN and adds nothing (server-authoritative). [storedLinesOf] gives the answer's stored lines of a removed memo.
     */
    fun delta(memos: List<LocalMemo>, holds: ServerHolds, month: String?, throughDate: String?, storedLinesOf: (String) -> List<StoredLine>? = { null }, confirmedIn: Set<String> = emptySet()): Delta? {
        if (month == null) return null
        val gone = removed(memos, holds)
        val add = ArrayList<LocalMemo>(); val sub = ArrayList<Subtract>(); val unknown = ArrayList<LocalMemo>()
        for (m in memos) {
            if (m.voidOf != null || m.businessDate.take(7) != month) continue
            val after = throughDate != null && m.businessDate > throughDate
            if (m.uuid in gone) {
                if (after) continue // never in the figure (cut at through_date)
                if (holds.of(m.uuid) == Holds.IN) {
                    val stored = storedLinesOf(m.uuid)
                    if (stored != null) sub += Subtract(m, stored.map { sl -> m.lines.firstOrNull { it.skuId == sl.skuId && it.lineKind == sl.lineKind }?.let { LocalMemoLine(sl.skuId, sl.lineKind, sl.qtyBase, it.packFactor, if (it.qtyBase > 0) it.grossMtk * sl.qtyBase / it.qtyBase else 0L) } ?: LocalMemoLine(sl.skuId, sl.lineKind, sl.qtyBase, 1, 0L) })
                }
                continue
            }
            if (m.state == "rejected") continue
            // confirmed IN earlier and not touched since: not asked again; if the office voided it the figure lost it by itself and the phone adds nothing (rule 4)
            if (!after && m.uuid in confirmedIn) continue
            when (holds.of(m.uuid)) {
                Holds.VOIDED, Holds.REFUSED -> Unit // gone for good / never stored: dropped
                Holds.IN -> if (after) add += m // dated after through_date the figure never holds it (an IN answer for it is not possible; defensive: it is the phone's to add)
                Holds.NOT_IN -> add += m
                Holds.UNKNOWN -> if (after) add += m else unknown += m
            }
        }
        return Delta(add, sub, unknown)
    }

    /** The figure to show, by [delta]: quantities from SALE lines, a memo counted once when it has a SALE line of the figure (brand and campaign counts; the per-SKU `achievement_mtd.memo_count` rule is the section reader's). */
    fun show(
        server: FigureRuleB.ServerFigure?, memos: List<LocalMemo>, holds: ServerHolds, month: String?, throughDate: String?, storedLinesOf: (String) -> List<StoredLine>? = { null },
        memoIn: (LocalMemo) -> Boolean = { true }, lineIn: (LocalMemoLine) -> Boolean = { true }, confirmedIn: Set<String> = emptySet(),
    ): FigureRuleB.Shown? {
        server ?: return null
        val d = delta(memos.filter(memoIn), holds, month, throughDate, storedLinesOf, confirmedIn) ?: return FigureRuleB.Shown(server.qtyBase, server.memoCount, server.grossMtk, server.byPack, false, server.asOf)
        var qty = server.qtyBase; var count = server.memoCount; var mtk = server.grossMtk
        val packs = server.byPack.toMutableMap()
        fun put(lines: List<LocalMemoLine>, memoCounts: Boolean, sign: Int) {
            val ls = lines.filter { it.lineKind == "sale" && lineIn(it) }
            if (ls.isEmpty()) return
            qty += sign * ls.sumOf { it.qtyBase }; mtk += sign * ls.sumOf { it.grossMtk }; if (memoCounts) count += sign
            ls.groupBy { it.packFactor }.forEach { (f, l) -> packs[f] = ((packs[f] ?: 0L) + sign * l.sumOf { it.qtyBase }).coerceAtLeast(0L) }
        }
        d.add.filter { it.memoKind == "sale" }.forEach { put(it.lines, true, +1) }
        d.subtract.filter { it.memo.memoKind == "sale" }.forEach { put(it.lines, true, -1) }
        return FigureRuleB.Shown(qty.coerceAtLeast(0L), count.coerceAtLeast(0L), mtk.coerceAtLeast(0L), packs.filterValues { it != 0L }, true, server.asOf)
    }
}

/** The probe of the phone's own memos whose status in the figure it does not know, and the request body for `POST /v1/sync/figures`. */
object FigureProbe {
    const val CAP = 300

    data class Entry(val memoUuid: String, val memoNo: String?, val businessDate: String, val removedLocally: Boolean, val removalUuid: String? = null)

    /** [entries] and [tooMany]: more than [CAP] unconfirmed memos in the window cannot get an exact total in one call, so the page says it is still syncing until uploads and confirmations bring it under. */
    data class Probe(val entries: List<Entry>, val tooMany: Boolean)

    /**
     * Own memos of [month] to ask about: a live memo not yet CONFIRMED IN ([confirmedIn]: held by the phone until it voids or edits it) and every live memo dated after [throughDate] (asked on every request, so an
     * office void of today is seen); a memo removed locally (voided or superseded) that has not been settled ([settledRemoved]: answered VOIDED, or IN once and subtracted by the stored lines is not settled: it is asked
     * with `removed_locally` until the server says VOIDED). A refused memo is not asked about (it is not in any figure).
     */
    fun build(memos: List<LocalMemo>, month: String, throughDate: String?, confirmedIn: Set<String>, settledRemoved: Set<String> = emptySet(), refused: Set<String> = emptySet()): Probe {
        val removers = memos.filter { it.state != "rejected" && it.uuid !in refused }.flatMap { k -> listOfNotNull(k.supersedes, k.voidOf).map { it to k.uuid } }.toMap()
        val gone = removers.keys
        val out = ArrayList<Entry>()
        for (m in memos) {
            if (m.voidOf != null || m.state == "rejected" || m.uuid in refused || m.businessDate.take(7) != month) continue
            val after = throughDate != null && m.businessDate > throughDate
            val removed = m.uuid in gone
            if (removed) { if (!after && m.uuid !in settledRemoved) out += Entry(m.uuid, m.memoNo, m.businessDate, true, removers[m.uuid]) }
            else if (after || m.uuid !in confirmedIn) out += Entry(m.uuid, m.memoNo, m.businessDate, false)
        }
        return Probe(out.take(CAP), out.size > CAP)
    }

    /** The request body (`FiguresRequest`): the probe, the bundle date as `for` when given, and `include_astha` when asked. */
    fun requestJson(entries: List<Entry>, forDate: String? = null, includeAstha: Boolean = false): String = buildJsonObject {
        forDate?.let { put("for", it) }
        put("probe", buildJsonArray {
            entries.forEach { e -> add(buildJsonObject { put("memo_client_uuid", e.memoUuid); e.memoNo?.let { put("memo_no", it) }; put("business_date", e.businessDate); if (e.removedLocally) { put("removed_locally", true); e.removalUuid?.let { put("removal_client_uuid", it) } } }) }
        })
        if (includeAstha) put("include_astha", true)
    }.toString()
}

/**
 * The newest `/figures` answer kept on the phone (an app-private file per user: the answer can be large) and what the phone has CONFIRMED IN. Kept apart from the bundle; absent = the bundle's figures are used
 * (the old-phone fallback). [supported] remembers that the server has no endpoint (404/405) so a screen does not ask again in the same session.
 */
class FiguresStore(private val dir: File) {
    private val answerFile get() = File(dir, "answer.json")
    private val confirmedFile get() = File(dir, "confirmed_in.txt")

    fun saveAnswer(raw: String) { dir.mkdirs(); val tmp = File(dir, "answer.json.tmp"); tmp.writeText(raw); java.nio.file.Files.move(tmp.toPath(), answerFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }

    fun loadAnswer(): FiguresAnswer? = runCatching { FiguresAnswerParser.parse(answerFile.readText()) }.getOrNull()

    fun confirmedIn(): Set<String> = runCatching { confirmedFile.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    fun saveConfirmedIn(uuids: Set<String>) { dir.mkdirs(); val tmp = File(dir, "confirmed_in.txt.tmp"); tmp.writeText(uuids.sorted().joinToString("\n")); java.nio.file.Files.move(tmp.toPath(), confirmedFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }

    private val refusedFile get() = File(dir, "refused.txt")

    /** Uuids the server answered REFUSED for: never asked about again, and always REFUSED in the arithmetic. */
    fun refused(): Set<String> = runCatching { refusedFile.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    /** The holds of the newest answer plus the remembered refusals. */
    fun holds(answer: FiguresAnswer): ServerHolds { val r = refused(); return ServerHolds { if (it in r) Holds.REFUSED else answer.of(it) } }

    fun clear() { dir.deleteRecursively() }

    /** Records an answer: keeps it and adds every probed memo it holds IN (and that the phone has not removed) to the confirmed set; a memo answered VOIDED leaves the set. */
    fun record(answer: FiguresAnswer, probed: List<FigureProbe.Entry>) {
        saveAnswer(answer.raw)
        val keep = confirmedIn().toMutableSet()
        probed.forEach { e -> when (answer.of(e.memoUuid)) { Holds.IN -> if (!e.removedLocally) keep += e.memoUuid; Holds.VOIDED -> keep -= e.memoUuid; else -> Unit } }
        saveConfirmedIn(keep)
        val refusedNow = answer.refused
        if (refusedNow.isNotEmpty()) { dir.mkdirs(); refusedFile.writeText((refused() + refusedNow).sorted().joinToString("\n")) }
    }
}

/** The result of one call of `POST /v1/sync/figures` as the refresher sees it (the host maps the network result to it): 404 and 405 are an old server (no endpoint), never an error to show. */
sealed interface FiguresCall {
    data class Ok(val body: String) : FiguresCall
    data object NoEndpoint : FiguresCall
    data object Offline : FiguresCall
    data object Failed : FiguresCall
}

/**
 * Asks the server what its figures hold, behind a capability check: a server without the endpoint (404/405) is remembered for this session and the phone keeps the bundle's figures with the old-phone fallback
 * (today's behaviour). Called after a sync cycle and when a figures screen opens while online, never on a timer; read-only and idempotent, so a retry is always safe.
 */
class FiguresRefresher(private val store: FiguresStore, private val call: suspend (bodyJson: String) -> FiguresCall) {
    /** null = not asked yet; false = the server has no endpoint (do not ask again this session). */
    @Volatile var supported: Boolean? = null
        private set

    sealed interface Outcome {
        data class Updated(val answer: FiguresAnswer) : Outcome
        /** More than 300 unconfirmed memos: no exact total in one call; the page says it is still syncing. */
        data object TooMany : Outcome
        data object NoEndpoint : Outcome
        data object Offline : Outcome
        data object Failed : Outcome
    }

    suspend fun refresh(memos: List<LocalMemo>, month: String, throughDate: String?, forDate: String? = null, includeAstha: Boolean = false): Outcome {
        if (supported == false) return Outcome.NoEndpoint
        val probe = FigureProbe.build(memos, month, throughDate, store.confirmedIn(), refused = store.refused())
        if (probe.tooMany) return Outcome.TooMany
        return when (val r = call(FigureProbe.requestJson(probe.entries, forDate, includeAstha))) {
            is FiguresCall.Ok -> {
                val answer = FiguresAnswerParser.parse(r.body) ?: return Outcome.Failed // not a figures answer: keep what is there
                supported = true
                store.record(answer, probe.entries)
                Outcome.Updated(answer)
            }
            FiguresCall.NoEndpoint -> { supported = false; Outcome.NoEndpoint }
            FiguresCall.Offline -> Outcome.Offline
            FiguresCall.Failed -> Outcome.Failed
        }
    }
}
