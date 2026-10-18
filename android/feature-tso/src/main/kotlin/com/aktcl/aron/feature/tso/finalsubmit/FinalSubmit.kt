package com.aktcl.aron.feature.tso.finalsubmit

import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One route card of the preview: the FF (SR) name or null = "SR Not Set" (contract `FinalSubmitPreview.routes[]`). */
data class FsRoute(val routeId: Long, val code: String, val name: String, val kind: String, val ffName: String?, val state: String, val hadData: Boolean, val memoCount: Int, val netMtk: Long, val pendingRows: Int?)

data class FsPreview(val zoneId: Long, val date: String, val alreadySubmitted: Boolean, val submittedAt: String?, val submittedBy: String?, val routes: List<FsRoute>, val warnings: List<String>)

data class FsResult(val zoneId: Long, val date: String, val submittedAt: String, val routeCount: Int, val memoCount: Int, val netMtk: Long)

object FsJson {
    private fun str(o: JSONObject, k: String): String { require(!o.isNull(k)) { "$k null" }; return o.getString(k) }

    fun preview(body: String): FsPreview? = runCatching {
        val o = JSONObject(body); val a = o.getJSONArray("routes")
        FsPreview(
            o.getLong("zone_id"), str(o, "business_date"), o.getBoolean("already_submitted"), if (o.isNull("submitted_at")) null else o.optString("submitted_at"),
            if (o.isNull("submitted_by")) null else o.optString("submitted_by"),
            (0 until a.length()).map { i -> a.getJSONObject(i).let { r ->
                FsRoute(r.getLong("route_id"), str(r, "route_code"), str(r, "route_name"), str(r, "kind"), if (r.isNull("ff_user_name")) null else r.optString("ff_user_name").ifBlank { null },
                    str(r, "state"), r.getBoolean("had_data"), r.getInt("memo_count"), r.getLong("net_mtk"), if (r.isNull("pending_rows_reported")) null else r.optInt("pending_rows_reported"))
            } },
            o.optJSONArray("warnings")?.let { w -> (0 until w.length()).map { w.getString(it) } }.orEmpty(),
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
    data class Failed(val why: FsFailure) : FsSubmitOutcome
}

/** One node of the scope tree (`GeoNode` of `GET /v1/admin/geo/{level}`); [nameBn] is null when the node has no Bangla name. */
data class FsGeoNode(val id: Long, val name: String, val nameBn: String?) {
    fun label(bangla: Boolean): String = if (bangla && !nameBn.isNullOrBlank()) nameBn else name
}

object FsGeo {
    /** The five levels of the manual's cascade, top first. */
    val LEVELS = listOf("wing", "division", "territory", "house", "zone")

    /** Bad rows are dropped; null when the body is not a node page at all. */
    fun parse(body: String?): List<FsGeoNode>? = runCatching {
        val items = JSONObject(body ?: return null).getJSONArray("items")
        (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            val name = o.optString("name", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FsGeoNode(id, name, if (o.isNull("name_bn")) null else o.optString("name_bn").ifBlank { null })
        }
    }.getOrNull()
}

/** The server side of Final Submit. Online only by design (docs/24): there is no queue. Scope is the server's. */
interface FinalSubmitApi {
    /** Zone ids the TSO reaches (server scope; the five-level cascade names are a request, see docs/requests). */
    suspend fun zones(): List<Long>?
    /** The children of [parentId] at [level] (one of [FsGeo.LEVELS]; null parent = the top) inside the caller's reach; null = unreadable, the screen then falls back to the flat zone list. */
    suspend fun geo(level: String, parentId: Long?): List<FsGeoNode>? = null
    suspend fun preview(zoneId: Long, date: String): FsPreviewOutcome
    suspend fun submit(clientUuid: String, zoneId: Long, date: String): FsSubmitOutcome
}

/**
 * The same planner, zone and date always give the same client uuid, so a retry after a lost answer replays the first success
 * (contract: "the same client_uuid replays the first success") instead of looking like a second submit.
 */
object FsIds {
    fun clientUuid(userId: Long, zoneId: Long, date: String): String = com.aktcl.aron.feature.tso.StableIds.v4("aron.tso.final-submit:$userId:$zoneId:$date")
}

enum class FsError { NO_ZONE, DATE_FORMAT, DATE_FUTURE }

fun validateFs(zoneId: Long?, date: String, today: String): List<FsError> {
    val e = mutableListOf<FsError>()
    if (zoneId == null) e += FsError.NO_ZONE
    val d = runCatching { LocalDate.parse(date) }.getOrNull()
    if (d == null) e += FsError.DATE_FORMAT else if (d.isAfter(LocalDate.parse(today))) e += FsError.DATE_FUTURE
    return e
}

sealed interface FsPhase {
    data object Pick : FsPhase
    data object Loading : FsPhase
    /** "আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!" is shown over this phase. */
    data class AlreadySubmitted(val preview: FsPreview) : FsPhase
    data class Review(val preview: FsPreview, val confirming: Boolean = false) : FsPhase
    data object Submitting : FsPhase
    data class Done(val result: FsResult) : FsPhase
    data class Failed(val why: FsFailure) : FsPhase
}

/**
 * F-TSO-010 / F-TSO-021: pick the zone and the sales date, "Get Sales Data" (the preview read, where the already-submitted alert
 * fires), the routes with the FF name or "SR Not Set", an explicit confirm, then one submit per zone and day.
 */
class FinalSubmitState(private val api: FinalSubmitApi, private val userId: Long, private val today: () -> String) {
    private val _s = MutableStateFlow(Ui(date = today()))
    val ui: StateFlow<Ui> = _s.asStateFlow()
    private var busy = false

    data class Ui(val date: String, val zones: List<Long> = emptyList(), val zonesLoaded: Boolean = false, val zoneId: Long? = null, val phase: FsPhase = FsPhase.Pick, val errors: List<FsError> = emptyList(),
        /** Options per cascade level (index into [FsGeo.LEVELS]) and the picked id per level; [cascade] false = the flat zone list is used. */
        val options: Map<Int, List<FsGeoNode>> = emptyMap(), val picks: Map<Int, Long> = emptyMap(), val cascade: Boolean = false)

    suspend fun loadZones() {
        val z = runCatching { api.zones() }.getOrNull()
        _s.value = _s.value.copy(zones = z.orEmpty().sorted(), zonesLoaded = z != null)
    }

    /** Wing, Division, Territory, House, Zone: the top level is read once; a level with a single node is picked for the TSO. Any failure leaves the flat zone list. */
    suspend fun loadCascade() {
        val top = runCatching { api.geo(FsGeo.LEVELS[0], null) }.getOrNull()
        if (top.isNullOrEmpty()) { _s.value = _s.value.copy(cascade = false); return }
        _s.value = _s.value.copy(cascade = true, options = mapOf(0 to top), picks = emptyMap(), zoneId = null)
        if (top.size == 1) pickLevel(0, top[0].id)
    }

    suspend fun pickLevel(level: Int, id: Long) {
        val s = _s.value
        if (!s.cascade || level !in FsGeo.LEVELS.indices || s.phase is FsPhase.Done || s.phase is FsPhase.Loading || s.phase is FsPhase.Submitting) return
        if (s.options[level]?.any { it.id == id } != true) return
        _s.value = s.copy(picks = s.picks.filterKeys { it < level } + (level to id), options = s.options.filterKeys { it <= level }, zoneId = if (level == FsGeo.LEVELS.lastIndex) id else null, phase = FsPhase.Pick, errors = emptyList())
        if (level == FsGeo.LEVELS.lastIndex) return
        val kids = runCatching { api.geo(FsGeo.LEVELS[level + 1], id) }.getOrNull()
        // a stale answer (the TSO already picked something else) is dropped
        if (_s.value.picks[level] != id) return
        if (kids == null) { _s.value = _s.value.copy(cascade = false, options = emptyMap(), picks = emptyMap(), zoneId = null); return }
        _s.value = _s.value.copy(options = _s.value.options + (level + 1 to kids))
        if (kids.size == 1) pickLevel(level + 1, kids[0].id)
    }

    fun pickZone(id: Long) { if (_s.value.phase is FsPhase.Done || _s.value.phase is FsPhase.Loading || _s.value.phase is FsPhase.Submitting) return; _s.value = _s.value.copy(zoneId = id, phase = FsPhase.Pick, errors = emptyList()) }

    /** The alert's OK: back to the pickers. */
    fun dismissAlready() { _s.value = _s.value.copy(phase = FsPhase.Pick) }

    suspend fun getSalesData() {
        val s = _s.value
        if (busy || s.phase is FsPhase.Loading || s.phase is FsPhase.Submitting || s.phase is FsPhase.Done) return
        val errors = validateFs(s.zoneId, s.date, today())
        if (errors.isNotEmpty()) { _s.value = s.copy(errors = errors); return }
        busy = true
        _s.value = s.copy(phase = FsPhase.Loading, errors = emptyList())
        try {
            val o = runCatching { api.preview(checkNotNull(s.zoneId), s.date) }.getOrElse { FsPreviewOutcome.Failed(FsFailure.SERVER) }
            _s.value = _s.value.copy(phase = when (o) {
                is FsPreviewOutcome.Ok -> if (o.preview.alreadySubmitted) FsPhase.AlreadySubmitted(o.preview) else FsPhase.Review(o.preview)
                is FsPreviewOutcome.Failed -> FsPhase.Failed(o.why)
            })
        } finally { busy = false }
    }

    fun askConfirm() { val p = _s.value.phase; if (p is FsPhase.Review) _s.value = _s.value.copy(phase = p.copy(confirming = true)) }
    fun cancelConfirm() { val p = _s.value.phase; if (p is FsPhase.Review) _s.value = _s.value.copy(phase = p.copy(confirming = false)) }

    /** Only after the explicit confirm; once per zone and day (a second tap while sending is ignored). */
    suspend fun confirmSubmit() {
        val s = _s.value; val p = s.phase
        if (busy || p !is FsPhase.Review || !p.confirming) return
        busy = true
        _s.value = s.copy(phase = FsPhase.Submitting)
        try {
            val zone = checkNotNull(s.zoneId)
            val o = runCatching { api.submit(FsIds.clientUuid(userId, zone, s.date), zone, s.date) }.getOrElse { FsSubmitOutcome.Failed(FsFailure.SERVER) }
            _s.value = _s.value.copy(phase = when (o) {
                is FsSubmitOutcome.Ok -> FsPhase.Done(o.result)
                FsSubmitOutcome.AlreadySubmitted -> FsPhase.AlreadySubmitted(p.preview.copy(alreadySubmitted = true))
                is FsSubmitOutcome.Failed -> FsPhase.Failed(o.why)
            })
        } finally { busy = false }
    }

    /** Failure screens return to the Review (the preview is still valid) or the pickers. */
    fun retry() { _s.value = _s.value.copy(phase = FsPhase.Pick) }
}
