package com.aktcl.aron.amo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.feature.amo.AmoRoutePick
import com.aktcl.aron.feature.amo.ControlCall
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

private enum class OutletLeaf { LIST, OWN_LIST, PICK_ROUTE, PICK_OUTLET, FORM }

/**
 * The AMO Outlet hub (docs/07): the AMO's own New shop (F-AMO-025), Permanent close (026) and Change information (027), which
 * are the SR's outlet requests on the AMO's own database and go to the web approver (no AMO verification of an AMO's own
 * request), and the list of own requests. Verification of the SRs' requests and Update Base are added to this hub by their rows.
 */
@Composable
fun AmoOutletHub(day: AmoDay, onBack: () -> Unit, extra: @Composable (OutletHubExtras) -> Unit = {}) {
    var leaf by rememberSaveable { mutableStateOf(OutletLeaf.LIST) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    val back: () -> Unit = {
        when (leaf) {
            OutletLeaf.LIST -> onBack()
            OutletLeaf.PICK_OUTLET -> leaf = OutletLeaf.PICK_ROUTE
            OutletLeaf.FORM -> leaf = if (day.requestKind == OutletRequestKind.NEW) OutletLeaf.LIST else OutletLeaf.PICK_OUTLET
            else -> leaf = OutletLeaf.LIST
        }
    }
    BackHandler(onBack = back)
    AronScreen(topBar = { AronTopBar(stringResource(HomeR.string.hm_tile_outlet), onBack = back) }) {
        when (leaf) {
            OutletLeaf.LIST -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
                val rows = listOf(
                    Triple(OutR.string.out_req_new, OutletRequestKind.NEW, true), Triple(OutR.string.out_req_close, OutletRequestKind.CLOSE, true),
                    Triple(OutR.string.out_req_info, OutletRequestKind.INFO, true),
                )
                rows.forEachIndexed { i, (label, kind, _) ->
                    AronListRow(
                        stringResource(label),
                        onClick = {
                            day.requestKind = kind; day.requestOutlet = null
                            leaf = if (kind == OutletRequestKind.NEW) OutletLeaf.FORM else OutletLeaf.PICK_ROUTE
                        },
                        position = if (i == 0) RowPosition.First else RowPosition.Middle, divider = true, flat = true,
                    )
                }
                AronListRow(stringResource(OutR.string.out_own_open), onClick = { leaf = OutletLeaf.OWN_LIST }, position = RowPosition.Last, divider = false, flat = true)
            }
            OutletLeaf.OWN_LIST -> {
                var list by remember { mutableStateOf<List<OwnRequestRow>?>(null) }
                LaunchedEffect(Unit) { list = runCatching { day.ownRequests() }.getOrDefault(emptyList()) }
                list?.let { OwnRequestsContent(it) }
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
            OutletLeaf.FORM -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = back) { AmoRequestHost(day, onDone = { leaf = OutletLeaf.LIST }) }
        }
    }
}

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
