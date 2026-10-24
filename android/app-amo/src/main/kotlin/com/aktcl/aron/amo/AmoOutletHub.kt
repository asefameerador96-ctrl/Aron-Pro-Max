package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoBanner
import com.aktcl.aron.feature.amo.AmoListRow
import com.aktcl.aron.feature.amo.AmoPrimary
import com.aktcl.aron.feature.amo.AmoFrame
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.system.permission.GatedFeature
import com.aktcl.aron.core.system.permission.PermissionGate
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.feature.amo.AmoRoutePick
import com.aktcl.aron.feature.amo.ControlCall
import com.aktcl.aron.feature.amo.HubListContent
import com.aktcl.aron.feature.amo.HubRow
import com.aktcl.aron.feature.amo.HubSection
import com.aktcl.aron.feature.outlet.ClusterOption
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.outlet.OutletRequestContent
import com.aktcl.aron.feature.outlet.OutletRequestForm
import com.aktcl.aron.feature.outlet.OutletRequestKind
import com.aktcl.aron.feature.outlet.OwnRequestRow
import com.aktcl.aron.feature.outlet.OwnRequestsContent
import com.aktcl.aron.feature.outlet.RequestError
import com.aktcl.aron.feature.outlet.RequestValidation
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.feature.outlet.R as OutR
import kotlinx.coroutines.launch

private enum class OutletLeaf { LIST, OWN_LIST, PICK_ROUTE, PICK_OUTLET, FORM, VERIFY_LIST, VERIFY_FORM }

/**
 * The AMO Outlet hub (docs/07): the AMO's own New shop (F-AMO-025), Permanent close (026) and Change information (027), which
 * are the SR's outlet requests on the AMO's own database and go to the web approver (no AMO verification of an AMO's own
 * request), and the list of own requests. Verification of the SRs' requests and Update Base are added to this hub by their rows.
 */
@Composable
fun AmoOutletHub(day: AmoDay, onBack: () -> Unit, extra: @Composable (OutletHubExtras) -> Unit = {}) {
    var leaf by rememberSaveable { mutableStateOf(OutletLeaf.LIST) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var verifyUuid by rememberSaveable { mutableStateOf<String?>(null) }
    var verifyGroup by rememberSaveable { mutableStateOf("new") }
    var savedKind by rememberSaveable { mutableStateOf(OutletRequestKind.NEW.name) }
    // After process death AmoDay is rebuilt: the chosen kind is restored, and a Close/Info step whose outlet is gone starts over.
    day.requestKind = OutletRequestKind.valueOf(savedKind)
    if ((leaf == OutletLeaf.FORM && day.requestKind != OutletRequestKind.NEW && day.requestOutlet == null) ||
        (leaf == OutletLeaf.PICK_OUTLET && routeId == null)
    ) leaf = OutletLeaf.LIST
    val back: () -> Unit = {
        when (leaf) {
            OutletLeaf.LIST -> onBack()
            OutletLeaf.PICK_OUTLET -> leaf = OutletLeaf.PICK_ROUTE
            OutletLeaf.VERIFY_FORM -> leaf = OutletLeaf.VERIFY_LIST
            OutletLeaf.FORM -> leaf = if (day.requestKind == OutletRequestKind.NEW) OutletLeaf.LIST else OutletLeaf.PICK_OUTLET
            else -> leaf = OutletLeaf.LIST
        }
    }
    BackHandler(onBack = back)
    AmoFrame(stringResource(HomeR.string.hm_tile_outlet), back, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet) {
        when (leaf) {
            OutletLeaf.LIST -> {
                // docs/07 + manual: first the verification of the SRs' requests (three rows with their counts), then the AMO's own shop operations.
                var pending by remember { mutableStateOf<List<com.aktcl.aron.feature.amo.PendingRequest>>(emptyList()) }
                LaunchedEffect(day) { pending = runCatching { day.pendingVerifications() }.getOrDefault(emptyList()) }
                val verify = listOf(
                    HubRow(stringResource(AmoR.string.amo_verify_new), countText(pending.count { it.type == "new" }), { verifyGroup = "new"; leaf = OutletLeaf.VERIFY_LIST }, com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_new, pending.count { it.type == "new" }),
                    HubRow(stringResource(AmoR.string.amo_verify_closure), countText(pending.count { it.type == "close" }), { verifyGroup = "close"; leaf = OutletLeaf.VERIFY_LIST }, com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_close, pending.count { it.type == "close" }),
                    HubRow(stringResource(AmoR.string.amo_verify_change), countText(pending.count { it.type != "new" && it.type != "close" }), { verifyGroup = "info"; leaf = OutletLeaf.VERIFY_LIST }, com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_edit, pending.count { it.type != "new" && it.type != "close" }),
                )
                fun request(kind: OutletRequestKind): () -> Unit = {
                    day.requestKind = kind; savedKind = kind.name; day.requestOutlet = null
                    leaf = if (kind == OutletRequestKind.NEW) OutletLeaf.FORM else OutletLeaf.PICK_ROUTE
                }
                val ops = listOf(
                    HubRow(stringResource(OutR.string.out_req_new), onClick = request(OutletRequestKind.NEW), icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_new),
                    HubRow(stringResource(OutR.string.out_req_close), onClick = request(OutletRequestKind.CLOSE), icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_close),
                    HubRow(stringResource(OutR.string.out_req_info), onClick = request(OutletRequestKind.INFO), icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_edit),
                    HubRow(stringResource(AmoR.string.amo_leaf_update_base), onClick = request(OutletRequestKind.LOCATION), icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_update_base),
                    HubRow(stringResource(OutR.string.out_own_open), onClick = { leaf = OutletLeaf.OWN_LIST }, icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_verify),
                )
                HubListContent(listOf(HubSection(stringResource(AmoR.string.amo_verify_section), verify), HubSection(stringResource(AmoR.string.amo_ops_section), ops)), redLabels = true)
            }
            OutletLeaf.OWN_LIST -> {
                var list by remember { mutableStateOf<List<OwnRequestRow>?>(null) }
                var failed by remember { mutableStateOf(false) }
                var reload by remember { mutableStateOf(0) }
                LaunchedEffect(reload) {
                    list = null; failed = false
                    runCatching { day.ownRequests() }.onSuccess { list = it }.onFailure { failed = true }
                }
                when {
                    failed -> Column(Modifier.fillMaxSize().padding(AronTokens.Space.Screen)) {
                        AmoListRow(stringResource(AmoR.string.amo_load_failed_retry), onClick = { reload++ }, position = RowPosition.Only, divider = false, flat = true)
                    }
                    list == null -> com.aktcl.aron.core.ui.aron.AronLoadingScreen()
                    else -> OwnRequestsContent(list.orEmpty())
                }
            }
            OutletLeaf.PICK_ROUTE -> {
                var days by remember { mutableStateOf<List<com.aktcl.aron.core.database.repo.RouteDay>?>(null) }
                LaunchedEffect(day) { days = runCatching { day.routes() }.getOrDefault(emptyList()) }
                AmoRoutePick(ControlCall.routes(days.orEmpty()), { routeId = it.routeId; leaf = OutletLeaf.PICK_OUTLET })
            }
            OutletLeaf.PICK_OUTLET -> {
                var days by remember { mutableStateOf<List<com.aktcl.aron.core.database.repo.RouteDay>?>(null) }
                LaunchedEffect(day) { days = runCatching { day.routes() }.getOrDefault(emptyList()) }
                var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
                val outlets = ControlCall.outlets(days.orEmpty(), routeId ?: -1L)
                val all = OutletPicker.rows(outlets)
                OutletPickerContent(OutletPicker.rows(outlets, chip), OutletPicker.chips(all), chip, { chip = it }, { r -> day.requestOutlet = r.outlet; leaf = OutletLeaf.FORM })
            }
            OutletLeaf.VERIFY_LIST -> {
                var rows by remember { mutableStateOf<List<com.aktcl.aron.feature.amo.PendingRequest>?>(null) }
                var names by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
                LaunchedEffect(day) {
                    rows = runCatching { day.pendingVerifications() }.getOrDefault(emptyList())
                    names = com.aktcl.aron.feature.amo.OutletVerify.teamNames(runCatching { day.reference.section("supervisor") }.getOrNull())
                }
                rows?.let { all -> com.aktcl.aron.feature.amo.VerifyListContent(all.filter { when (verifyGroup) { "new" -> it.type == "new"; "close" -> it.type == "close"; else -> it.type != "new" && it.type != "close" } }, names, { r -> verifyUuid = r.uuid; leaf = OutletLeaf.VERIFY_FORM }) }
                    ?: com.aktcl.aron.core.ui.aron.AronLoadingScreen()
            }
            OutletLeaf.VERIFY_FORM -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = back) { AmoVerifyHost(day, verifyUuid, onDone = { leaf = OutletLeaf.VERIFY_LIST }, onGone = { leaf = OutletLeaf.VERIFY_LIST }) }
            OutletLeaf.FORM -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = back) {
                if (day.requestKind == OutletRequestKind.LOCATION) AmoUpdateBaseHost(day, onDone = { leaf = OutletLeaf.LIST }) else AmoRequestHost(day, onDone = { leaf = OutletLeaf.LIST })
            }
        }
    }
}

@Composable
private fun countText(n: Int): String? = n.takeIf { it > 0 }?.let { com.aktcl.aron.core.ui.localizedNumber(it.toLong()) }

/** What an extension of the hub (Verify, Update Base) can use; kept empty until those rows land. */
class OutletHubExtras

/** The SR's request form on the AMO's own request store; the request uuid is fixed when the form opens, so a double tap posts once. */
@Composable
private fun AmoRequestHost(day: AmoDay, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val kind = day.requestKind
    val outlet: OutletEntity? = day.requestOutlet
    var form by remember(kind, outlet?.outletId) {
        mutableStateOf(
            OutletRequestForm(
                kind = kind, outletId = outlet?.outletId, requestUuid = day.outletRequests.newRequestUuid(), currentClusterId = outlet?.clusterId,
                name = if (kind == OutletRequestKind.INFO) outlet?.name else null, ownerName = if (kind == OutletRequestKind.INFO) outlet?.ownerName else null,
                mobile = if (kind == OutletRequestKind.INFO) outlet?.contactNumber else null, openDueMtk = outlet?.openDueMtk ?: 0,
            ),
        )
    }
    val capture = remember(kind, outlet?.outletId) { day.newCapture("outlet_capture") }
    val cap by capture.state.collectAsState()
    var errors by remember { mutableStateOf<Set<RequestError>>(emptySet()) }
    var saved by remember { mutableStateOf(false) }
    val clusters = remember(day.zoneOutlets) { day.zoneOutlets.map { ClusterOption(it.clusterId, it.clusterName) }.distinctBy { it.clusterId } }
    OutletRequestContent(
        form = form, errors = errors, clusters = clusters, capture = cap, saved = saved, warnOpenDues = form.openDueMtk > 0,
        onChange = { form = it }, onShutter = { scope.launch { capture.shutter() } },
        onSave = {
            scope.launch {
                val toSend = form.copy(fix = cap.fix, photoUuids = listOfNotNull(cap.photo?.photoUuid))
                when (val r = day.outletRequests.submit(toSend)) {
                    is RequestValidation.Invalid -> { errors = r.errors; saved = false }
                    is RequestValidation.Ok -> { errors = emptySet(); saved = true; onDone() }
                }
            }
        },
    )
}

/** F-AMO-022/023/024 host: loads the pending request from the bundle, its details from the server (kept copy offline), queues the decision. */
@Composable
private fun AmoVerifyHost(day: AmoDay, uuid: String?, onDone: () -> Unit, onGone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var request by remember(uuid) { mutableStateOf<com.aktcl.aron.feature.amo.PendingRequest?>(null) }
    var detail by remember(uuid) { mutableStateOf<com.aktcl.aron.feature.amo.VerifyDetailState>(com.aktcl.aron.feature.amo.VerifyDetailState.Loading) }
    var subs by remember { mutableStateOf<List<com.aktcl.aron.feature.amo.SubChannel>>(emptyList()) }
    var gone by remember(uuid) { mutableStateOf(false) }
    LaunchedEffect(uuid) {
        val r = runCatching { day.pendingVerifications() }.getOrDefault(emptyList()).firstOrNull { it.uuid == uuid }
        if (r == null) { gone = true; return@LaunchedEffect }
        request = r
        subs = runCatching { day.subChannels() }.getOrDefault(emptyList())
        detail = when (val f = day.api.outletRequest(r.uuid)) {
            is AmoFetch.Fresh -> com.aktcl.aron.feature.amo.OutletVerify.detail(f.json)?.let { com.aktcl.aron.feature.amo.VerifyDetailState.Loaded(it, true) }
            is AmoFetch.Cached -> com.aktcl.aron.feature.amo.OutletVerify.detail(f.json)?.let { com.aktcl.aron.feature.amo.VerifyDetailState.Loaded(it, false) }
            AmoFetch.Gone -> com.aktcl.aron.feature.amo.VerifyDetailState.Gone
            AmoFetch.Unavailable -> null
        } ?: com.aktcl.aron.feature.amo.VerifyDetailState.Unavailable
    }
    if (gone) { LaunchedEffect(Unit) { onGone() }; return }
    val req = request ?: run { com.aktcl.aron.core.ui.aron.AronLoadingScreen(); return }
    // Fixed when the form opens: a double tap or a replay queues one decision.
    val draftKey = "verify:${req.uuid}"
    val recordUuid = remember(draftKey) { day.drafts.uuid(draftKey) }
    var subId by rememberSaveable(uuid) { mutableStateOf<Long?>(null) }
    var geo by rememberSaveable(uuid) { mutableStateOf<String?>(null) }
    var note by rememberSaveable(uuid) { mutableStateOf("") }
    var errors by remember { mutableStateOf<Set<com.aktcl.aron.feature.amo.VerifyError>>(emptySet()) }
    var noFix by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // The manual's success dialog: the decision is saved on this phone and goes out with the next sync; OK returns to the list.
    var savedDialog by rememberSaveable(uuid) { mutableStateOf(false) }
    if (savedDialog) {
        com.aktcl.aron.core.ui.AronInfoDialog(
            stringResource(AmoR.string.amo_success_title), stringResource(AmoR.string.amo_verify_saved), stringResource(AmoR.string.amo_ok),
            onDismiss = { savedDialog = false; onDone() },
        )
        return
    }
    val bangla = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "bn"
    val outlet = remember(req.outletId, day.zoneOutlets.size) { day.zoneOutlets.firstOrNull { it.outletId == req.outletId } }
    fun subLabel(id: String?) = id?.toLongOrNull()?.let { i -> subs.firstOrNull { it.id == i } }?.let { if (bangla) it.labelBn ?: it.labelEn else it.labelEn } ?: id
    val loaded = detail as? com.aktcl.aron.feature.amo.VerifyDetailState.Loaded
    val current: Map<String, String?> = outlet?.let {
        mapOf("name" to it.name, "name_bn" to it.nameBn, "owner_name" to it.ownerName, "contact_number" to it.contactNumber, "sub_channel_id" to subLabel(it.subChannelId?.toString()))
    }.orEmpty()
    val changes = loaded?.let { l -> com.aktcl.aron.feature.amo.OutletVerify.changes(l.detail.proposed.mapValues { (k, v) -> if (k == "sub_channel_id") subLabel(v).orEmpty() else v }, current) }.orEmpty()
    // The SR's proposed sub-channel and geo class are the starting point for a new outlet.
    LaunchedEffect(loaded?.detail) {
        loaded?.detail?.takeIf { it.type == "new" }?.let { d ->
            if (subId == null) subId = d.proposed["sub_channel_id"]?.toLongOrNull()?.takeIf { id -> subs.any { it.id == id } }
            if (geo == null) geo = d.proposed["geo_class"]?.takeIf { it in com.aktcl.aron.feature.amo.OutletVerify.GEO_CLASSES }
        }
    }
    val dueLabel = stringResource(AmoR.string.amo_verify_f_due)
    val money = com.aktcl.aron.rules.Formats.money(outlet?.openDueMtk ?: 0L, if (bangla) com.aktcl.aron.rules.UiLocale.BN else com.aktcl.aron.rules.UiLocale.EN)
    val facts = if (req.type == "close" && outlet != null) listOf(dueLabel to money + (outlet.openDueAsOf?.let { " (" + stringResource(AmoR.string.amo_verify_f_due_as_of, it) + ")" } ?: "")) else emptyList()
    // A request the server refuses or no longer has (403/404) cannot be decided; a stale kept copy or no detail offline can (the server re-checks).
    val decidable = detail !is com.aktcl.aron.feature.amo.VerifyDetailState.Gone && (loaded?.takeIf { it.fresh }?.let { com.aktcl.aron.feature.amo.OutletVerify.decidable(it.detail, day.userId) } ?: true)
    fun decide(verify: Boolean) {
        val e = com.aktcl.aron.feature.amo.OutletVerify.validate(req.type, verify, subId, geo)
        errors = e
        if (e.isNotEmpty() || busy) return
        busy = true; noFix = false
        scope.launch {
            val ask = req.type == "new"
            var threw = false
            val ok = try {
                day.decideRequest(recordUuid, req.uuid, verify, if (ask) subId else null, if (ask) geo else null, note.trim().ifEmpty { null }, null)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { threw = true; false }
            busy = false
            saveFailed = threw
            if (ok) { day.drafts.clear(draftKey); savedDialog = true } else noFix = !threw
        }
    }
    com.aktcl.aron.feature.amo.VerifyFormContent(
        request = req, detail = detail, changes = changes, facts = facts, askClassification = req.type == "new", decidable = decidable, subChannels = subs, bangla = bangla, subChannelId = subId, geoClass = geo, note = note,
        errors = errors, noFix = noFix, saveFailed = saveFailed, busy = busy, onSubChannel = { subId = it }, onGeoClass = { geo = it }, onNote = { note = it },
        onSave = { decide(true) }, onCancel = onGone, onReject = { decide(false) },
    )
}

/**
 * F-AMO-028 Update Base: confirm, photo with the AMO's fix, the exact point (moved in steps from where the AMO stands, within the limit),
 * then a `location` outlet request that goes for approval. Works offline: the fix and the photo are local and the request queues.
 * The server applies the move limit, the monthly cap and the mock guard again.
 */
@Composable
private fun AmoUpdateBaseHost(day: AmoDay, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val outlet: OutletEntity? = day.requestOutlet
    var confirmed by rememberSaveable(outlet?.outletId) { mutableStateOf(false) }
    if (outlet == null) { LaunchedEffect(Unit) { onDone() }; return }
    if (!confirmed) {
        com.aktcl.aron.core.ui.AronConfirmDialog(
            stringResource(AmoR.string.amo_base_confirm_title), stringResource(AmoR.string.amo_base_confirm_message),
            stringResource(AmoR.string.amo_base_confirm_yes), stringResource(AmoR.string.amo_base_confirm_no),
            onConfirm = { confirmed = true }, onDismiss = onDone,
        )
        return
    }
    val uuid = rememberSaveable(outlet.outletId) { day.outletRequests.newRequestUuid() }
    val capture = remember(outlet.outletId) { day.newCapture("outlet_capture") }
    val cap by capture.state.collectAsState()
    var north by rememberSaveable(outlet.outletId) { mutableStateOf(0) }
    var east by rememberSaveable(outlet.outletId) { mutableStateOf(0) }
    var errors by remember { mutableStateOf<Set<RequestError>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    val fix = cap.fix?.takeIf { it.isOk }
    val point = fix?.let { com.aktcl.aron.feature.amo.UpdateBasePoint.move(it.lat!!, it.lng!!, north, east) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val savedText = stringResource(AmoR.string.amo_base_saved)
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            AmoListRow(outlet.name, subtitle = stringResource(AmoR.string.amo_base_title), flat = true)
            com.aktcl.aron.feature.outlet.GeoPhotoBlock(cap, onShutter = { scope.launch { val before = cap.fix; val after = capture.shutter(); if (after.fix !== before) { north = 0; east = 0 } } }, note = stringResource(OutR.string.out_req_photo_note))
            com.aktcl.aron.feature.amo.UpdateBasePointContent(north, east, day.updateBaseMaxM, fix != null, { dn, de -> north += dn; east += de }, { north = 0; east = 0 })
            val oldLat = outlet.lat; val oldLng = outlet.lng
            if (point != null && oldLat != null && oldLng != null) {
                val moved = com.aktcl.aron.rules.Geo.haversineM(oldLat, oldLng, point.first, point.second).toLong()
                Text(stringResource(AmoR.string.amo_base_moved, com.aktcl.aron.core.ui.localizedNumber(moved)), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
            errors.forEach { e ->
                AmoBanner(
                    stringResource(when (e) {
                        RequestError.POINT_TOO_FAR -> OutR.string.out_req_err_point_far
                        RequestError.MOCK_FIX -> OutR.string.out_req_err_mock
                        RequestError.PHOTO_REQUIRED -> OutR.string.out_req_err_photo
                        RequestError.MOVE_TOO_FAR -> OutR.string.out_req_err_move_far
                        else -> OutR.string.out_req_err_geo
                    }),
                    kind = com.aktcl.aron.core.ui.BannerKind.Error,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen)) {
            AmoPrimary(
                stringResource(AmoR.string.amo_base_save),
                {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            val form = OutletRequestForm(
                                kind = OutletRequestKind.LOCATION, outletId = outlet.outletId, requestUuid = uuid, fix = cap.fix,
                                photoUuids = listOfNotNull(cap.photo?.photoUuid), pointLat = point?.first, pointLng = point?.second, oldLat = outlet.lat, oldLng = outlet.lng,
                            )
                            when (val r = day.outletRequests.submit(form)) {
                                is RequestValidation.Invalid -> errors = r.errors
                                is RequestValidation.Ok -> { errors = emptySet(); android.widget.Toast.makeText(context, savedText, android.widget.Toast.LENGTH_SHORT).show(); onDone() }
                            }
                            busy = false
                        }
                    }
                },
                Modifier.fillMaxWidth(), enabled = !busy, busy = busy,
            )
        }
    }
}
