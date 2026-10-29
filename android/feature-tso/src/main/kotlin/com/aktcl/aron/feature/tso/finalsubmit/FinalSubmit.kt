package com.aktcl.aron.feature.tso.finalsubmit

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** `FinalSubmitPreview.routes[].upload_state` (contract 1.10.0, D-TSO-MAN-27). A saved Web Entry counts as uploaded. */
enum class FsUploadState(val wire: String) {
    UPLOADED("uploaded"), WEB_ENTRY("web_entry"), NOT_UPLOADED("not_uploaded"), NOT_DUE("not_due");

    companion object { fun of(wire: String?): FsUploadState? = entries.firstOrNull { it.wire == wire } }
}

/** The kind label after the route name on a card (D-TSO-MAN-27): Daily, 3F, 2F or AMO. */
enum class FsKindLabel { DAILY, F3, F2, AMO }

/**
 * One route card of the preview (contract `FinalSubmitPreview.routes[]`): the FF's full name or null = "SR Not Set".
 * The 1.10.0 members ([routeClass], [visitKind], [ffRole], [uploadState]) are optional until the server sends them.
 */
data class FsRoute(
    val routeId: Long, val code: String, val name: String, val kind: String, val ffName: String?, val state: String, val hadData: Boolean,
    val memoCount: Int, val netMtk: Long, val pendingRows: Int?, val planned: Boolean? = null,
    val routeClass: String? = null, val visitKind: String? = null, val ffRole: String? = null, val uploadState: FsUploadState? = null,
) {
    /**
     * Greyed, never a failed submit: the server's `not_due`; before 1.10.0, contract 1.7 `planned == false` with no data
     * (D-BC-PREVIEW-01).
     */
    val notDue: Boolean get() = uploadState?.let { it == FsUploadState.NOT_DUE } ?: (planned == false && !hadData)

    /** A due route that has not uploaded: the amber "Not uploaded" chip and a line of the warning dialog. */
    val notUploaded: Boolean get() = uploadState == FsUploadState.NOT_UPLOADED

    /** AMO for an AMO route; otherwise the stored visit kind (daily, 3f, 2f); null when the route has none. */
    val kindLabel: FsKindLabel? get() = if (kind == "amo") FsKindLabel.AMO else when (visitKind?.lowercase()) {
        "daily" -> FsKindLabel.DAILY
        "3f" -> FsKindLabel.F3
        "2f" -> FsKindLabel.F2
        else -> null
    }
}

data class FsPreview(
    val zoneId: Long, val date: String, val alreadySubmitted: Boolean, val submittedAt: String?, val submittedBy: String?, val routes: List<FsRoute>, val warnings: List<String>,
    /** Reopened Final Submits of this zone-day: part of the client uuid (D-TSO-MAN-28). 0 until the server sends it. */
    val submitSeq: Int = 0,
    /** The due routes not uploaded, named by the warning dialog; null when the server did not send the member (older server). */
    val warnRouteIds: List<Long>? = null,
) {
    /** The routes the warning dialog names: the server's list, else (older server) the routes marked not uploaded. Empty = no dialog. */
    val warnRoutes: List<FsRoute> get() = warnRouteIds?.let { ids -> routes.filter { it.routeId in ids } } ?: routes.filter { it.notUploaded }
}

data class FsResult(val zoneId: Long, val date: String, val submittedAt: String, val routeCount: Int, val memoCount: Int, val netMtk: Long)

object FsJson {
    private fun str(o: JSONObject, k: String): String { require(!o.isNull(k)) { "$k null" }; return o.getString(k) }
    private fun optStr(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).ifBlank { null }

    fun preview(body: String): FsPreview? = runCatching {
        val o = JSONObject(body); val a = o.getJSONArray("routes")
        FsPreview(
            o.getLong("zone_id"), str(o, "business_date"), o.getBoolean("already_submitted"), optStr(o, "submitted_at"), optStr(o, "submitted_by"),
            (0 until a.length()).map { i -> a.getJSONObject(i).let { r ->
                FsRoute(r.getLong("route_id"), str(r, "route_code"), str(r, "route_name"), str(r, "kind"), optStr(r, "ff_user_name"),
                    str(r, "state"), r.getBoolean("had_data"), r.getInt("memo_count"), r.getLong("net_mtk"), if (r.isNull("pending_rows_reported")) null else r.optInt("pending_rows_reported"),
                    if (r.isNull("planned")) null else r.optBoolean("planned"),
                    routeClass = optStr(r, "route_class"), visitKind = optStr(r, "visit_kind"), ffRole = optStr(r, "ff_role"), uploadState = FsUploadState.of(optStr(r, "upload_state")))
            } },
            o.optJSONArray("warnings")?.let { w -> (0 until w.length()).map { w.getString(it) } }.orEmpty(),
            submitSeq = o.optInt("submit_seq", 0).coerceAtLeast(0),
            warnRouteIds = o.optJSONArray("warn_route_ids")?.let { w -> (0 until w.length()).map { w.getLong(it) } },
        )
    }.getOrNull()

    fun result(body: String): FsResult? = runCatching {
        val o = JSONObject(body)
        FsResult(o.getLong("zone_id"), str(o, "business_date"), str(o, "submitted_at"), o.getInt("route_count"), o.getInt("memo_count"), o.getLong("net_mtk"))
    }.getOrNull()
}

/** Why a call produced no answer; the screen shows words for each, never a code. */
enum class FsFailure { OFFLINE, FORBIDDEN, REFUSED, SERVER }

sealed interface FsPreviewOutcome {
    data class Ok(val preview: FsPreview) : FsPreviewOutcome
    data class Failed(val why: FsFailure) : FsPreviewOutcome
}

sealed interface FsSubmitOutcome {
    data class Ok(val result: FsResult) : FsSubmitOutcome
    /** 409 `ERR_DAY_ALREADY_FINAL_SUBMITTED` for a different client uuid. */
    data object AlreadySubmitted : FsSubmitOutcome
    /** 409 `ERR_DAY_FINAL_SUBMIT_REOPENED`: this uuid's submit was reopened since; read the preview again (D-TSO-MAN-28). */
    data object Reopened : FsSubmitOutcome
    data class Failed(val why: FsFailure) : FsSubmitOutcome
}

/** One node of a cascade level; [nameBn] is null when the node has no Bangla name. */
data class FsGeoNode(val id: Long, val name: String, val nameBn: String?) {
    fun label(bangla: Boolean): String = if (bangla && !nameBn.isNullOrBlank()) nameBn else name
}

/** One FilterNode of `GET /v1/dashboards/national/filter-options` with the parent ids its level carries. */
data class FsFilterNode(val id: Long, val name: String, val parentId: Long?, val territoryId: Long? = null)

/** The wing-to-zone part of the filter options (reach from the token, server-side scope; the client sends no ids). */
data class FsFilterOptions(
    val wings: List<FsFilterNode>, val divisions: List<FsFilterNode>, val territories: List<FsFilterNode>, val houses: List<FsFilterNode>, val zones: List<FsFilterNode>,
)

object FsGeo {
    /** The five levels of the manual's cascade, top first (screenshot 23). */
    val LEVELS = listOf("wing", "division", "territory", "house", "zone")

    /** The House option for the zones of a territory that belong to no house (house_id null); never a server id (ids are >= 1). */
    const val NO_HOUSE = 0L

    /**
     * The wing-to-zone lists of a `NationalFilterOptions` body; bad nodes are dropped; null when the body is not one.
     * Parents: division.wing_id, territory.division_id, house.territory_id, zone.house_id (and zone.territory_id).
     */
    fun parseFilterOptions(body: String?): FsFilterOptions? = runCatching {
        val o = JSONObject(body ?: return null)
        fun nodes(key: String, parent: String?): List<FsFilterNode> {
            val a: JSONArray = o.getJSONArray(key)
            return (0 until a.length()).mapNotNull { i ->
                val n = a.optJSONObject(i) ?: return@mapNotNull null
                val id = n.optLong("id", 0L).takeIf { it > 0 } ?: return@mapNotNull null
                val name = (if (n.isNull("name")) null else n.optString("name").ifBlank { null }) ?: n.optString("code").ifBlank { null } ?: return@mapNotNull null
                fun pid(k: String) = if (n.isNull(k)) null else n.optLong(k, 0L).takeIf { it > 0 }
                FsFilterNode(id, name, parent?.let(::pid), pid("territory_id"))
            }
        }
        FsFilterOptions(nodes("wings", null), nodes("divisions", "wing_id"), nodes("territories", "division_id"), nodes("houses", "territory_id"), nodes("zones", "house_id"))
    }.getOrNull()

    /**
     * The options of [level] under the picks above it (level 0: every wing). House: the territory's houses, plus
     * [NO_HOUSE] when some zone of the territory has no house. Zone: the zones of the picked house (for [NO_HOUSE], the
     * territory's zones without a house).
     */
    fun children(o: FsFilterOptions, level: Int, picks: Map<Int, Long>): List<FsGeoNode> {
        fun List<FsFilterNode>.geo() = map { FsGeoNode(it.id, it.name, null) }
        return when (level) {
            0 -> o.wings.geo()
            1 -> o.divisions.filter { it.parentId == picks[0] }.geo()
            2 -> o.territories.filter { it.parentId == picks[1] }.geo()
            3 -> {
                val t = picks[2] ?: return emptyList()
                val houses = o.houses.filter { it.parentId == t }.geo()
                if (o.zones.any { it.parentId == null && it.territoryId == t }) houses + FsGeoNode(NO_HOUSE, "", null) else houses
            }
            4 -> {
                val h = picks[3] ?: return emptyList()
                if (h == NO_HOUSE) o.zones.filter { it.parentId == null && it.territoryId == picks[2] }.geo() else o.zones.filter { it.parentId == h }.geo()
            }
            else -> emptyList()
        }
    }
}

/** The server side of Final Submit. Online only by design (docs/24): there is no queue. Scope is the server's. */
interface FinalSubmitApi {
    /** Zone ids the TSO reaches: the flat fallback list when the filter options cannot be read. */
    suspend fun zones(): List<Long>?
    /** Wing to zone inside the caller's reach (`GET /v1/dashboards/national/filter-options`, D-TSO-MAN-32); null = unreadable. */
    suspend fun filterOptions(): FsFilterOptions? = null
    suspend fun preview(zoneId: Long, date: String): FsPreviewOutcome
    suspend fun submit(clientUuid: String, zoneId: Long, date: String): FsSubmitOutcome
}

/**
 * The client uuid of a Final Submit (D-TSO-MAN-28, contract FinalSubmitRequest): the name-based v4 uuid of
 * `aron.tso.final-submit:<user>:<zone>:<date>:<submit_seq>`. The same submit retried after a lost answer replays the
 * first success; after a reopen the preview's `submit_seq` has moved, so the next submit is a new one (CR-3).
 */
object FsIds {
    fun clientUuid(userId: Long, zoneId: Long, date: String, submitSeq: Int): String =
        com.aktcl.aron.feature.tso.StableIds.v4("aron.tso.final-submit:$userId:$zoneId:$date:$submitSeq")
}

enum class FsError { NO_ZONE, DATE_FORMAT, DATE_FUTURE }

fun validateFs(zoneId: Long?, date: String, today: String): List<FsError> {
    val e = mutableListOf<FsError>()
    if (zoneId == null) e += FsError.NO_ZONE
    val d = runCatching { LocalDate.parse(date) }.getOrNull()
    if (d == null) e += FsError.DATE_FORMAT else if (d.isAfter(LocalDate.parse(today))) e += FsError.DATE_FUTURE
    return e
}

/** A line over the routes: the zone was reopened since the last submit, so the preview was read again (D-TSO-MAN-28). */
enum class FsNotice { REOPENED }

sealed interface FsPhase {
    data object Pick : FsPhase
    data object Loading : FsPhase
    /** "আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!" is shown over this phase. */
    data class AlreadySubmitted(val preview: FsPreview) : FsPhase
    /** The routes; [warning] = the "not uploaded" dialog is open (Submit anyway / Cancel). */
    data class Review(val preview: FsPreview, val warning: Boolean = false, val notice: FsNotice? = null) : FsPhase
    data class Submitting(val preview: FsPreview) : FsPhase
    /** The success dialog over the greyed routes (screenshot 26); its OK goes on to the next zone. */
    data class Done(val result: FsResult, val preview: FsPreview) : FsPhase
    data class Failed(val why: FsFailure) : FsPhase
}

/**
 * Final Submit (F-TSO-010/021, D-TSO-MAN-27/28/32, screenshots 23 to 26): Wing, Division, Territory, House and Zone from the
 * filter options (a level with one option is picked for the TSO), Get Sales Data (the preview read, where the
 * already-submitted alert fires), the routes with "<name> <kind>" and the FF line, then Submit: a warning that names the
 * due routes not uploaded ("Submit anyway" / "Cancel"; it never blocks) or, when nothing is due, the submit at once.
 * Success OK clears the zone for the next zone (CR-5). The sales date is today's Dhaka business date.
 */
class FinalSubmitState(private val api: FinalSubmitApi, private val userId: Long, private val today: () -> String) {
    private val _s = MutableStateFlow(Ui(date = today()))
    val ui: StateFlow<Ui> = _s.asStateFlow()
    private var busy = false
    private var filter: FsFilterOptions? = null

    data class Ui(val date: String, val zones: List<Long> = emptyList(), val zonesLoaded: Boolean = false, val zoneId: Long? = null, val phase: FsPhase = FsPhase.Pick, val errors: List<FsError> = emptyList(),
        /** Options per cascade level (index into [FsGeo.LEVELS]) and the picked id per level; [cascade] false = the flat zone list is used. */
        val options: Map<Int, List<FsGeoNode>> = emptyMap(), val picks: Map<Int, Long> = emptyMap(), val cascade: Boolean = false,
        /** True once the cascade read has answered (or failed); the screen draws the five levels from then on. */
        val cascadeTried: Boolean = false)

    private val locked get() = _s.value.phase.let { it is FsPhase.Loading || it is FsPhase.Submitting || it is FsPhase.Done || it is FsPhase.Review }

    suspend fun loadZones() {
        val z = runCatching { api.zones() }.getOrNull()
        _s.value = _s.value.copy(zones = z.orEmpty().sorted(), zonesLoaded = z != null)
    }

    /** One read of the filter options; levels with a single option are picked down the tree. A failure leaves the flat zone list. */
    suspend fun loadCascade() {
        val o = runCatching { api.filterOptions() }.getOrNull()
        filter = o
        if (o == null || o.wings.isEmpty()) { _s.value = _s.value.copy(cascade = false, cascadeTried = true); return }
        _s.value = _s.value.copy(cascade = true, cascadeTried = true, options = mapOf(0 to FsGeo.children(o, 0, emptyMap())), picks = emptyMap(), zoneId = null)
        autoPick(0)
    }

    /** Picks the single option of [level] (and so on down); stops at the first level with more than one option. */
    private fun autoPick(level: Int) {
        val only = _s.value.options[level]?.singleOrNull() ?: return
        pick(level, only.id)
    }

    /** The TSO's choice at [level]; everything below is cleared and the next level's options computed. */
    fun pickLevel(level: Int, id: Long) {
        val s = _s.value
        if (!s.cascade || level !in FsGeo.LEVELS.indices || s.phase !is FsPhase.Pick && s.phase !is FsPhase.Failed) return
        if (s.options[level]?.any { it.id == id } != true) return
        pick(level, id)
    }

    private fun pick(level: Int, id: Long) {
        val o = filter ?: return
        val s = _s.value
        val picks = s.picks.filterKeys { it < level } + (level to id)
        var options = s.options.filterKeys { it <= level }
        if (level < FsGeo.LEVELS.lastIndex) options = options + (level + 1 to FsGeo.children(o, level + 1, picks))
        _s.value = s.copy(picks = picks, options = options, zoneId = if (level == FsGeo.LEVELS.lastIndex) id else null, phase = FsPhase.Pick, errors = emptyList())
        if (level < FsGeo.LEVELS.lastIndex) autoPick(level + 1)
    }

    /** The flat zone list (filter options unreadable). */
    fun pickZone(id: Long) { if (locked) return; _s.value = _s.value.copy(zoneId = id, phase = FsPhase.Pick, errors = emptyList()) }

    /** The alert's OK: back to the pickers. */
    fun dismissAlready() { _s.value = _s.value.copy(phase = FsPhase.Pick) }

    /** Get Sales Data: enabled once all five levels (or the flat zone) are chosen. */
    suspend fun getSalesData() {
        val s = _s.value
        if (busy || s.phase !is FsPhase.Pick && s.phase !is FsPhase.Failed) return
        // the sales date is today's business date when the data is asked for, not when the screen state was made (a state
        // kept over midnight asks for the new day)
        val date = today()
        val errors = validateFs(s.zoneId, date, date)
        if (errors.isNotEmpty()) { _s.value = s.copy(date = date, errors = errors); return }
        busy = true
        _s.value = s.copy(date = date, phase = FsPhase.Loading, errors = emptyList())
        try { readPreview(checkNotNull(s.zoneId), date, null) } finally { busy = false }
    }

    private suspend fun readPreview(zone: Long, date: String, notice: FsNotice?) {
        val o = runCatching { api.preview(zone, date) }.getOrElse { FsPreviewOutcome.Failed(FsFailure.SERVER) }
        _s.value = _s.value.copy(phase = when (o) {
            is FsPreviewOutcome.Ok -> if (o.preview.alreadySubmitted) FsPhase.AlreadySubmitted(o.preview) else FsPhase.Review(o.preview, notice = notice)
            is FsPreviewOutcome.Failed -> FsPhase.Failed(o.why)
        })
    }

    /** The Submit button: the warning dialog when a due route has not uploaded, else the submit at once (no confirm). */
    suspend fun submit() {
        val p = _s.value.phase
        if (busy || p !is FsPhase.Review || p.warning) return
        if (p.preview.warnRoutes.isNotEmpty()) _s.value = _s.value.copy(phase = p.copy(warning = true)) else send(p.preview)
    }

    /** "Submit anyway" in the warning dialog. */
    suspend fun confirmSubmit() {
        val p = _s.value.phase
        if (busy || p !is FsPhase.Review || !p.warning) return
        send(p.preview)
    }

    /** "Cancel" in the warning dialog: back to the routes, nothing sent. */
    fun cancelConfirm() { val p = _s.value.phase; if (p is FsPhase.Review) _s.value = _s.value.copy(phase = p.copy(warning = false)) }

    private suspend fun send(preview: FsPreview) {
        busy = true
        _s.value = _s.value.copy(phase = FsPhase.Submitting(preview))
        try {
            val zone = preview.zoneId
            val o = runCatching { api.submit(FsIds.clientUuid(userId, zone, preview.date, preview.submitSeq), zone, preview.date) }.getOrElse { FsSubmitOutcome.Failed(FsFailure.SERVER) }
            when (o) {
                is FsSubmitOutcome.Ok -> _s.value = _s.value.copy(phase = FsPhase.Done(o.result, preview))
                FsSubmitOutcome.AlreadySubmitted -> _s.value = _s.value.copy(phase = FsPhase.AlreadySubmitted(preview.copy(alreadySubmitted = true)))
                // the zone-day was reopened since this uuid's submit: read the preview again; its submit_seq gives the next uuid
                FsSubmitOutcome.Reopened -> { _s.value = _s.value.copy(phase = FsPhase.Loading); readPreview(zone, preview.date, FsNotice.REOPENED) }
                is FsSubmitOutcome.Failed -> _s.value = _s.value.copy(phase = FsPhase.Failed(o.why))
            }
        } finally { busy = false }
    }

    /**
     * The success dialog's OK (CR-5): back to the selectors with the zone cleared, the levels above it kept, ready for the
     * next zone. The zone just submitted is not picked again even when it is the only one.
     */
    fun nextZone() {
        val s = _s.value
        if (s.phase !is FsPhase.Done) return
        val last = FsGeo.LEVELS.lastIndex
        _s.value = s.copy(phase = FsPhase.Pick, zoneId = null, picks = s.picks - last, errors = emptyList())
    }

    /** Failure screens return to the pickers (the zone stays chosen). */
    fun retry() { _s.value = _s.value.copy(phase = FsPhase.Pick) }
}
