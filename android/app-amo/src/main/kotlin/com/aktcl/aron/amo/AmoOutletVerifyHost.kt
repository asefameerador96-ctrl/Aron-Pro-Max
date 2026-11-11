package com.aktcl.aron.amo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.amo.AmoFormError
import com.aktcl.aron.feature.amo.AmoFormItem
import com.aktcl.aron.feature.amo.AmoOption
import com.aktcl.aron.feature.amo.AmoOutletLists
import com.aktcl.aron.feature.amo.AmoSuccessDialog
import com.aktcl.aron.feature.amo.OutletVerify
import com.aktcl.aron.feature.amo.PendingRequest
import com.aktcl.aron.feature.amo.Pick
import com.aktcl.aron.feature.amo.SubChannel
import com.aktcl.aron.feature.amo.VerifyDetailState
import com.aktcl.aron.feature.amo.VerifyError
import com.aktcl.aron.feature.amo.VerifyGroup
import com.aktcl.aron.feature.amo.VerifyListRow
import com.aktcl.aron.feature.amo.captureItem
import com.aktcl.aron.feature.amo.classificationItems
import com.aktcl.aron.feature.amo.R as AmoR
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The list of one verification group (47, 50, 52): route, cluster, name, owner, contact per request; the details come from the server, kept copy offline. */
@Composable
internal fun VerifyListHost(day: AmoDay, group: VerifyGroup, onOpen: (String) -> Unit) {
    var rows by remember { mutableStateOf<List<VerifyListRow>?>(null) }
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(day, group) {
        val pending = runCatching { day.pendingVerifications() }.getOrDefault(emptyList()).filter { group.matches(it.type) }
        val days = runCatching { day.routes() }.getOrDefault(emptyList())
        val routeNames = days.associate { it.route.routeId to (it.route.displayLabel ?: it.route.name) }
        val clusterNames = day.zoneOutlets.mapNotNull { o -> o.clusterId?.let { it to (o.clusterName ?: it.toString()) } }.toMap()
        // the plain list first, then the details fill in; one failed fetch leaves its row with dashes
        rows = pending.map { VerifyListRow(it, it.routeId?.let(routeNames::get), it.clusterId?.let(clusterNames::get), it.outletName, null, null) }
        val detailed = coroutineScope {
            pending.map { r ->
                async {
                    val d = when (val f = runCatching { day.api.outletRequest(r.uuid) }.getOrNull()) {
                        is AmoFetch.Fresh -> OutletVerify.detail(f.json)
                        is AmoFetch.Cached -> OutletVerify.detail(f.json)
                        else -> null
                    }
                    val o = r.outletId?.let { id -> day.zoneOutlets.firstOrNull { it.outletId == id } }
                    val cluster = r.clusterId ?: d?.proposed?.get("cluster_id")?.toLongOrNull() ?: o?.clusterId
                    rowOf(
                        r, r.routeId?.let(routeNames::get) ?: o?.routeId?.let(routeNames::get), cluster?.let(clusterNames::get),
                        d?.proposed?.get("name") ?: o?.name ?: r.outletName, d?.proposed?.get("owner_name") ?: o?.ownerName,
                        AmoOutletLists.phone(d?.proposed?.get("contact_number") ?: o?.contactNumber), d != null,
                    )
                }
            }.awaitAll()
        }
        offline = detailed.any { !it.second }
        rows = detailed.map { it.first }
    }
    rows?.let { com.aktcl.aron.feature.amo.VerifyListContent(it, { r -> onOpen(r.uuid) }, detailsOffline = offline) } ?: com.aktcl.aron.core.ui.aron.AronLoadingScreen()
}

private fun rowOf(r: PendingRequest, route: String?, cluster: String?, shop: String?, owner: String?, contact: String?, loaded: Boolean): Pair<VerifyListRow, Boolean> =
    VerifyListRow(r, route, cluster, shop, owner, contact) to loaded

/**
 * F-AMO-022/023/024 host: loads the pending request from the bundle, its details from the server (kept copy offline), queues the decision.
 * Save = approve, Cancel = reject after "আপনি কি নিশ্চিত?" হ্যাঁ/না (D-AMO-MAN-12); a new outlet re-captures GEO and photo (48) with the fix's mock flag kept.
 */
@Composable
internal fun AmoVerifyHost(day: AmoDay, uuid: String?, onDone: () -> Unit, onGone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bangla = LocalConfiguration.current.locales[0].language == "bn"
    var request by remember(uuid) { mutableStateOf<PendingRequest?>(null) }
    var detail by remember(uuid) { mutableStateOf<VerifyDetailState>(VerifyDetailState.Loading) }
    var subs by remember { mutableStateOf<List<SubChannel>>(emptyList()) }
    var gone by remember(uuid) { mutableStateOf(false) }
    var routes by remember { mutableStateOf<List<AmoOption>>(emptyList()) }
    LaunchedEffect(uuid) {
        val r = runCatching { day.pendingVerifications() }.getOrDefault(emptyList()).firstOrNull { it.uuid == uuid }
        if (r == null) { gone = true; return@LaunchedEffect }
        request = r
        subs = runCatching { day.subChannels() }.getOrDefault(emptyList())
        routes = AmoOutletLists.routes(runCatching { day.routes() }.getOrDefault(emptyList()))
        detail = when (val f = day.api.outletRequest(r.uuid)) {
            is AmoFetch.Fresh -> OutletVerify.detail(f.json)?.let { VerifyDetailState.Loaded(it, true) }
            is AmoFetch.Cached -> OutletVerify.detail(f.json)?.let { VerifyDetailState.Loaded(it, false) }
            AmoFetch.Gone -> VerifyDetailState.Gone
            AmoFetch.Unavailable -> null
        } ?: VerifyDetailState.Unavailable
    }
    if (gone) { LaunchedEffect(Unit) { onGone() }; return }
    val req = request ?: run { com.aktcl.aron.core.ui.aron.AronLoadingScreen(); return }
    // Fixed when the form opens and kept across a swipe-kill until the save succeeds: a double tap, a retry and a relaunch queue one decision.
    val draftKey = "verify:${req.uuid}"
    var recordUuid by remember(draftKey) { mutableStateOf(day.drafts.uuid(draftKey)) }
    var refusedCode by remember(uuid) { mutableStateOf<String?>(null) }
    var held by remember(uuid) { mutableStateOf(false) }
    var routeDays by remember { mutableStateOf<List<com.aktcl.aron.core.database.repo.RouteDay>>(emptyList()) }
    LaunchedEffect(uuid) { routeDays = runCatching { day.routes() }.getOrDefault(emptyList()) }
    // a sync can answer while the form is open: the notice follows the newest decision (a local read every few seconds)
    LaunchedEffect(uuid) { while (true) { val d = runCatching { day.refusedDecision(req.uuid) }.getOrNull(); refusedCode = d?.second; held = d?.first == com.aktcl.aron.core.database.entity.OutboxState.QUARANTINED; kotlinx.coroutines.delay(4000) } }
    var routeSel by rememberSaveable(uuid) { mutableStateOf<Long?>(null) }
    var clusterSel by rememberSaveable(uuid) { mutableStateOf<Long?>(null) }
    var subId by rememberSaveable(uuid) { mutableStateOf<Long?>(null) }
    var geo by rememberSaveable(uuid) { mutableStateOf<String?>(null) }
    var errors by remember { mutableStateOf<Set<VerifyError>>(emptySet()) }
    var noFix by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val capture = remember(uuid) { day.newCapture("outlet_capture") }
    val cap by capture.state.collectAsState()
    // The success dialog of 49/51/53: the decision is saved on this phone and goes out with the next sync; OK returns to the list.
    var savedDialog by rememberSaveable(uuid) { mutableStateOf(false) }
    var rejected by rememberSaveable(uuid) { mutableStateOf(false) }
    if (savedDialog) {
        AmoSuccessDialog(stringResource(if (req.type == "new" && !rejected) AmoR.string.amo_out_saved else AmoR.string.amo_out_updated), stringResource(AmoR.string.amo_ok)) { savedDialog = false; onDone() }
        return
    }
    val outlet = remember(req.outletId, day.zoneOutlets.size) { day.zoneOutlets.firstOrNull { it.outletId == req.outletId } }
    fun subLabel(id: String?) = id?.toLongOrNull()?.let { i -> subs.firstOrNull { it.id == i } }?.let { if (bangla) it.labelBn ?: it.labelEn else it.labelEn } ?: id
    val loaded = detail as? VerifyDetailState.Loaded
    val proposed = loaded?.detail?.proposed.orEmpty()
    val current: Map<String, String?> = outlet?.let {
        mapOf("name" to it.name, "name_bn" to it.nameBn, "owner_name" to it.ownerName, "contact_number" to it.contactNumber, "sub_channel_id" to subLabel(it.subChannelId?.toString()))
    }.orEmpty()
    val changes = loaded?.let { OutletVerify.changes(proposed.mapValues { (k, v) -> if (k == "sub_channel_id") subLabel(v).orEmpty() else v }, current) }.orEmpty()
    // The SR's proposed sub-channel and geo class (or the outlet's own) are the starting point.
    LaunchedEffect(loaded?.detail, subs) {
        val d = loaded?.detail ?: return@LaunchedEffect
        if (subId == null) subId = (d.proposed["sub_channel_id"] ?: outlet?.subChannelId?.toString())?.toLongOrNull()?.takeIf { id -> subs.any { it.id == id } }
        if (geo == null) geo = d.proposed["geo_class"]?.takeIf { it in OutletVerify.GEO_CLASSES }
    }
    val dueLabel = stringResource(AmoR.string.amo_verify_f_due)
    val money = com.aktcl.aron.rules.Formats.money(outlet?.openDueMtk ?: 0L, if (bangla) com.aktcl.aron.rules.UiLocale.BN else com.aktcl.aron.rules.UiLocale.EN)
    val facts = buildList {
        if (req.type == "close" && outlet != null) add(dueLabel to money + (outlet.openDueAsOf?.let { " (" + stringResource(AmoR.string.amo_verify_f_due_as_of, it) + ")" } ?: ""))
    }
    val decidable = detail !is VerifyDetailState.Gone && (loaded?.takeIf { it.fresh }?.let { OutletVerify.decidable(it.detail, day.userId) } ?: true)
    // The form's own pickers: device ids of the downloaded masterdata, the SR's/outlet's route and cluster as the start; the payload carries them (never the envelope route)
    val effRoute = routeSel ?: req.routeId ?: outlet?.routeId
    val effCluster = clusterSel ?: if (routeSel != null) null else (req.clusterId ?: proposed["cluster_id"]?.toLongOrNull() ?: outlet?.clusterId)
    fun decide(verify: Boolean) {
        val e = OutletVerify.validate(req.type, verify, subId, geo, captured = cap.photo != null && cap.fix?.isOk == true, clusterMissing = routeSel != null && clusterSel == null)
        errors = e
        if (e.isNotEmpty() || busy) return
        busy = true; noFix = false
        scope.launch {
            val ask = req.type == "new"
            var threw = false
            var outcome: VerifyOutcome? = null
            try {
                outcome = day.decideFromForm(
                    draftKey, req.uuid, recordUuid,
                    VerifyChoice(verify, subId, geo, cap.photo?.photoUuid, cap.fix, routeSel, clusterSel, ask),
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { threw = true }
            val ok = outcome?.ok == true
            outcome?.let { recordUuid = it.recordUuid; if (it.held) held = true }
            busy = false
            saveFailed = threw
            if (ok) { day.drafts.clear(draftKey); rejected = !verify; savedDialog = true } else noFix = !threw && outcome?.held != true
        }
    }
    val formErrors = errors.mapNotNull {
        when (it) { VerifyError.SUB_CHANNEL_REQUIRED -> AmoFormError.SUB_CHANNEL; VerifyError.GEO_CLASS_REQUIRED -> AmoFormError.GEO_CLASS; VerifyError.GEO_PHOTO_REQUIRED -> AmoFormError.GEO_PHOTO; VerifyError.CLUSTER_REQUIRED -> AmoFormError.CLUSTER }
    }.toSet()
    val routeOutlets = effRoute?.let { r -> routeDays.firstOrNull { it.route.routeId == r }?.outlets }.orEmpty()
    val clusterOptions = AmoOutletLists.clusters(routeOutlets.ifEmpty { day.zoneOutlets })
    val route = Pick(routes, effRoute, { routeSel = it; clusterSel = null })
    val cluster = Pick(clusterOptions, effCluster, { clusterSel = it })
    val sub = Pick(subs.map { AmoOption(it.id, if (bangla) it.labelBn ?: it.labelEn else it.labelEn) }, subId, { subId = it })
    val head = listOfNotNull(
        refusedCode?.let { c -> AmoFormItem.Notice(stringResource(when (OutletVerify.decisionProblem(c)) { "unknown_route" -> AmoR.string.amo_out_why_unknown_route; "cluster" -> AmoR.string.amo_out_why_cluster; "scope" -> AmoR.string.amo_out_why_scope; else -> AmoR.string.amo_out_why_other }), com.aktcl.aron.core.ui.BannerKind.Warning) },
    ) + listOf(
        com.aktcl.aron.feature.amo.routeItem(route, emptySet()), com.aktcl.aron.feature.amo.clusterItem(cluster, formErrors),
    )
    val shop = com.aktcl.aron.feature.amo.shopReadOnlyItems(proposed["name"] ?: outlet?.name ?: req.outletName, proposed["owner_name"] ?: outlet?.ownerName, proposed["contact_number"] ?: outlet?.contactNumber)
    val items = head + shop + when (req.type) {
        "close" -> emptyList()
        "new" -> classificationItems(sub, geo, { geo = it }, formErrors) + captureItem(cap, { scope.launch { capture.shutter() } }, formErrors)
        else -> classificationItems(sub, geo, { geo = it }, formErrors)
    }
    com.aktcl.aron.feature.amo.VerifyFormContent(
        request = req, detail = detail, changes = if (req.type == "info") changes else emptyList(), facts = facts, items = items, decidable = decidable && !held,
        noFix = noFix, saveFailed = saveFailed, busy = busy, onSave = { decide(true) }, onReject = { decide(false) },
    )
}
