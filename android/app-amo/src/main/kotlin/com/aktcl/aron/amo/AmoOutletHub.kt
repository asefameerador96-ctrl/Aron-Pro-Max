package com.aktcl.aron.amo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.aktcl.aron.core.database.repo.RouteDay
import com.aktcl.aron.core.system.permission.GatedFeature
import com.aktcl.aron.core.system.permission.PermissionGate
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.feature.amo.AmoBanner
import com.aktcl.aron.feature.amo.AmoFormError
import com.aktcl.aron.feature.amo.AmoFormItem
import com.aktcl.aron.feature.amo.AmoFrame
import com.aktcl.aron.feature.amo.AmoListRow
import com.aktcl.aron.feature.amo.AmoOutletFormBody
import com.aktcl.aron.feature.amo.AmoOutletLists
import com.aktcl.aron.feature.amo.AmoOutletMenuContent
import com.aktcl.aron.feature.amo.AmoOutletOp
import com.aktcl.aron.feature.amo.AmoOption
import com.aktcl.aron.feature.amo.AmoRoutePick
import com.aktcl.aron.feature.amo.AmoSuccessDialog
import com.aktcl.aron.feature.amo.ControlCall
import com.aktcl.aron.feature.amo.Pick
import com.aktcl.aron.feature.amo.ShopText
import com.aktcl.aron.feature.amo.VerifyGroup
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.outlet.OutletRequestContent
import com.aktcl.aron.feature.outlet.OutletRequestForm
import com.aktcl.aron.feature.outlet.OutletRequestKind
import com.aktcl.aron.feature.outlet.OwnRequestRow
import com.aktcl.aron.feature.outlet.OwnRequestsContent
import com.aktcl.aron.feature.outlet.RequestValidation
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.feature.outlet.R as OutR
import kotlinx.coroutines.launch

private enum class OutletLeaf { MENU, OWN_LIST, VERIFY_LIST, VERIFY_FORM, NEW_SHOP, CLOSE, INFO, UPDATE_BASE, PICK_ROUTE, PICK_OUTLET, CLUSTER_FORM }

/**
 * The AMO Outlet hub (screenshot 46, docs/07): the menu with its two groups, the verification of the SRs' requests (47 to 53), and the AMO's own
 * New shop (54 to 56), Permanent close (57, 58), Change information (59 to 61), Update Base (62 to 64) and Cluster (as the SR). An AMO's own
 * request goes to the web approver; every form keeps its uuid across a swipe-kill (`FormDrafts`) and returns to the cleared form after OK.
 */
@Composable
fun AmoOutletHub(day: AmoDay, onBack: () -> Unit) {
    var leaf by rememberSaveable { mutableStateOf(OutletLeaf.MENU) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var verifyUuid by rememberSaveable { mutableStateOf<String?>(null) }
    var verifyGroup by rememberSaveable { mutableStateOf(VerifyGroup.NEW.name) }
    var clusterOutletId by rememberSaveable { mutableStateOf<Long?>(null) }
    // After process death AmoDay is rebuilt: a step whose outlet is gone starts over.
    if (leaf == OutletLeaf.CLUSTER_FORM && day.requestOutlet == null) leaf = OutletLeaf.MENU
    if (leaf == OutletLeaf.PICK_OUTLET && routeId == null) leaf = OutletLeaf.MENU
    val back: () -> Unit = {
        when (leaf) {
            OutletLeaf.MENU -> onBack()
            OutletLeaf.PICK_OUTLET -> leaf = OutletLeaf.PICK_ROUTE
            OutletLeaf.VERIFY_FORM -> leaf = OutletLeaf.VERIFY_LIST
            OutletLeaf.CLUSTER_FORM -> leaf = OutletLeaf.PICK_OUTLET
            else -> leaf = OutletLeaf.MENU
        }
    }
    BackHandler(onBack = back)
    val title = when (leaf) {
        OutletLeaf.NEW_SHOP -> stringResource(OutR.string.out_req_new)
        OutletLeaf.CLOSE -> stringResource(OutR.string.out_req_close)
        OutletLeaf.INFO -> stringResource(OutR.string.out_req_info)
        OutletLeaf.UPDATE_BASE -> stringResource(AmoR.string.amo_out_base_title)
        OutletLeaf.PICK_ROUTE, OutletLeaf.PICK_OUTLET, OutletLeaf.CLUSTER_FORM -> stringResource(OutR.string.out_req_cluster)
        else -> stringResource(HomeR.string.hm_tile_outlet)
    }
    AmoFrame(title, back, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet) {
        when (leaf) {
            OutletLeaf.MENU -> {
                var pending by remember { mutableStateOf<List<com.aktcl.aron.feature.amo.PendingRequest>>(emptyList()) }
                LaunchedEffect(day) { pending = runCatching { day.pendingVerifications() }.getOrDefault(emptyList()) }
                AmoOutletMenuContent(
                    counts = VerifyGroup.entries.associateWith { g -> pending.count { g.matches(it.type) } },
                    onVerify = { verifyGroup = it.name; leaf = OutletLeaf.VERIFY_LIST },
                    onOp = { op ->
                        when (op) {
                            AmoOutletOp.NEW -> leaf = OutletLeaf.NEW_SHOP
                            AmoOutletOp.CLOSE -> leaf = OutletLeaf.CLOSE
                            AmoOutletOp.INFO -> leaf = OutletLeaf.INFO
                            AmoOutletOp.UPDATE_BASE -> leaf = OutletLeaf.UPDATE_BASE
                            AmoOutletOp.CLUSTER -> { day.requestKind = OutletRequestKind.CLUSTER; day.requestOutlet = null; leaf = OutletLeaf.PICK_ROUTE }
                        }
                    },
                    onOwn = { leaf = OutletLeaf.OWN_LIST },
                )
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
                var days by remember { mutableStateOf<List<RouteDay>?>(null) }
                LaunchedEffect(day) { days = runCatching { day.routes() }.getOrDefault(emptyList()) }
                AmoRoutePick(ControlCall.routes(days.orEmpty()), { routeId = it.routeId; leaf = OutletLeaf.PICK_OUTLET })
            }
            OutletLeaf.PICK_OUTLET -> {
                var days by remember { mutableStateOf<List<RouteDay>?>(null) }
                LaunchedEffect(day) { days = runCatching { day.routes() }.getOrDefault(emptyList()) }
                var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
                val outlets = ControlCall.outlets(days.orEmpty(), routeId ?: -1L)
                val all = OutletPicker.rows(outlets)
                OutletPickerContent(OutletPicker.rows(outlets, chip), OutletPicker.chips(all), chip, { chip = it }, { r -> day.requestOutlet = r.outlet; clusterOutletId = r.outlet.outletId; leaf = OutletLeaf.CLUSTER_FORM })
            }
            OutletLeaf.CLUSTER_FORM -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = back) { AmoClusterHost(day, onDone = { leaf = OutletLeaf.MENU }) }
            OutletLeaf.VERIFY_LIST -> VerifyListHost(day, VerifyGroup.valueOf(verifyGroup), onOpen = { verifyUuid = it; leaf = OutletLeaf.VERIFY_FORM })
            OutletLeaf.VERIFY_FORM -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = back) { AmoVerifyHost(day, verifyUuid, onDone = { leaf = OutletLeaf.VERIFY_LIST }, onGone = { leaf = OutletLeaf.VERIFY_LIST }) }
            OutletLeaf.NEW_SHOP -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = back) { AmoNewShopHost(day) }
            OutletLeaf.CLOSE -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = back) { AmoCloseHost(day) }
            OutletLeaf.INFO -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = back) { AmoChangeInfoHost(day) }
            OutletLeaf.UPDATE_BASE -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = back) { AmoUpdateBaseHost(day) }
        }
    }
}

/** The SR cluster-change form on the AMO's own request store (the SR app's form as it is: 46 only names the tile). */
@Composable
private fun AmoClusterHost(day: AmoDay, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val outlet: OutletEntity = day.requestOutlet ?: run { LaunchedEffect(Unit) { onDone() }; return }
    val draftKey = "own:cluster:${outlet.outletId}"
    var form by remember(outlet.outletId) {
        mutableStateOf(OutletRequestForm(kind = OutletRequestKind.CLUSTER, outletId = outlet.outletId, requestUuid = "", currentClusterId = outlet.clusterId))
    }
    val capture = remember(outlet.outletId) { day.newCapture("outlet_capture") }
    val cap by capture.state.collectAsState()
    var errors by remember { mutableStateOf<Set<com.aktcl.aron.feature.outlet.RequestError>>(emptySet()) }
    var done by rememberSaveable(outlet.outletId) { mutableStateOf(false) }
    val clusters = remember(day.zoneOutlets) { day.zoneOutlets.map { com.aktcl.aron.feature.outlet.ClusterOption(it.clusterId ?: -1, it.clusterName) }.filter { it.clusterId > 0 }.distinctBy { it.clusterId } }
    if (done) { AmoSuccessDialog(stringResource(AmoR.string.amo_out_saved), stringResource(AmoR.string.amo_ok)) { done = false; onDone() }; return }
    OutletRequestContent(
        form = form, errors = errors, clusters = clusters, capture = cap, saved = false, warnOpenDues = false,
        onChange = { form = it }, onShutter = { scope.launch { capture.shutter() } },
        onSave = {
            scope.launch {
                if (cap.fix?.isMock == true) { errors = setOf(com.aktcl.aron.feature.outlet.RequestError.MOCK_FIX); return@launch }
                var u = day.drafts.uuid(draftKey)
                if (day.requestStored(u)) { day.drafts.clear(draftKey); u = day.drafts.uuid(draftKey) } // an earlier act finished before a kill: this one is new
                val r = try { day.outletRequests.submit(form.copy(requestUuid = u, fix = cap.fix, photoUuids = listOfNotNull(cap.photo?.photoUuid))) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                when (r) {
                    null -> errors = setOf(com.aktcl.aron.feature.outlet.RequestError.REASON_REQUIRED)
                    is RequestValidation.Invalid -> errors = r.errors
                    is RequestValidation.Ok -> { errors = emptySet(); day.drafts.clear(draftKey); done = true }
                }
            }
        },
    )
}
