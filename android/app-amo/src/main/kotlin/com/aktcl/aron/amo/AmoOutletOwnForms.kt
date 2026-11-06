package com.aktcl.aron.amo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.repo.RouteDay
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.feature.amo.AmoBanner
import com.aktcl.aron.feature.amo.AmoFormError
import com.aktcl.aron.feature.amo.AmoFormItem
import com.aktcl.aron.feature.amo.AmoOption
import com.aktcl.aron.feature.amo.AmoOutletFormBody
import com.aktcl.aron.feature.amo.AmoOutletLists
import com.aktcl.aron.feature.amo.AmoPrimary
import com.aktcl.aron.feature.amo.AmoSuccessDialog
import com.aktcl.aron.feature.amo.OutletVerify
import com.aktcl.aron.feature.amo.Pick
import com.aktcl.aron.feature.amo.ShopText
import com.aktcl.aron.feature.amo.UpdateBaseMapContent
import com.aktcl.aron.feature.amo.UpdateBasePoint
import com.aktcl.aron.feature.amo.UpdateBasePointContent
import com.aktcl.aron.feature.amo.changeInfoItems
import com.aktcl.aron.feature.amo.closeItems
import com.aktcl.aron.feature.amo.newShopItems
import com.aktcl.aron.feature.amo.retailerItem
import com.aktcl.aron.feature.amo.routeItem
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletRequestForm
import com.aktcl.aron.feature.outlet.OutletRequestKind
import com.aktcl.aron.feature.outlet.RequestError
import com.aktcl.aron.feature.outlet.RequestValidation
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.feature.outlet.R as OutR
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The routes of the AMO's zone from the local bundle (offline too). */
@Composable
private fun rememberRouteDays(day: AmoDay): List<RouteDay> {
    var days by remember { mutableStateOf<List<RouteDay>>(emptyList()) }
    LaunchedEffect(day) { days = runCatching { day.routes() }.getOrDefault(emptyList()) }
    return days
}

/** Resolved at the first save of a form: a key whose request is already stored means the earlier act finished, so this act takes a new uuid (never swallowed as a replay). */
private class SubmitUuid(private val day: AmoDay, private val key: String) {
    private var uuid: String? = null
    suspend fun get(): String = uuid ?: run {
        var u = day.drafts.uuid(key)
        if (day.requestStored(u)) { day.drafts.clear(key); u = day.drafts.uuid(key) }
        u.also { uuid = it }
    }
}

private suspend fun safeSubmit(day: AmoDay, f: OutletRequestForm): RequestValidation? =
    try { day.outletRequests.submit(f) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

@Composable
private fun failedNotice(failed: Boolean): List<AmoFormItem> = if (failed) listOf(AmoFormItem.Notice(stringResource(AmoR.string.amo_task_failed), BannerKind.Error)) else emptyList()

private fun mapErrors(e: Set<RequestError>): Set<AmoFormError> = e.mapNotNull { AmoOutletLists.fieldError(it) }.toSet()

/** Error texts the field mapping does not cover (dues block, too many photos, ...), shown as banners above the form. */
@Composable
private fun otherErrors(e: Set<RequestError>): List<AmoFormItem> = e.filter { AmoOutletLists.fieldError(it) == null && it != RequestError.CONFIRMATION_REQUIRED }.map {
    AmoFormItem.Notice(
        stringResource(when (it) { RequestError.DUES_BLOCK_CLOSE -> OutR.string.out_req_err_dues; RequestError.POINT_TOO_FAR -> OutR.string.out_req_err_point_far; RequestError.MOVE_TOO_FAR -> OutR.string.out_req_err_move_far; else -> OutR.string.out_req_err_reason }),
        BannerKind.Error,
    )
}

// ---------------------------------------------------------------- New shop (54, 55, 56)

/** New shop: route → cluster → form → GEO and photo → save → "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" → OK → the cleared form (56). */
@Composable
fun AmoNewShopHost(day: AmoDay) {
    val days = rememberRouteDays(day)
    var generation by rememberSaveable { mutableStateOf(0) }
    key(generation) { NewShopForm(day, days) { generation++ } }
}

@Composable
private fun NewShopForm(day: AmoDay, days: List<RouteDay>, onCleared: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bangla = LocalConfiguration.current.locales[0].language == "bn"
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var clusterId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf("") }
    var subId by rememberSaveable { mutableStateOf<Long?>(null) }
    var geo by rememberSaveable { mutableStateOf<String?>(null) }
    var errors by remember { mutableStateOf<Set<RequestError>>(emptySet()) }
    var failed by remember { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var subs by remember { mutableStateOf<List<com.aktcl.aron.feature.amo.SubChannel>>(emptyList()) }
    LaunchedEffect(day) { subs = runCatching { day.subChannels() }.getOrDefault(emptyList()) }
    val capture = remember { day.newCapture("outlet_capture") }
    val cap by capture.state.collectAsState()
    val draftKey = "own:new"
    val ids = remember(draftKey) { SubmitUuid(day, draftKey) }
    val routeOutlets = routeId?.let { ControlCallOutlets.of(days, it) }.orEmpty()
    val clusters = AmoOutletLists.clusters(routeOutlets.ifEmpty { day.zoneOutlets })
    if (saved) { AmoSuccessDialog(stringResource(AmoR.string.amo_out_saved), stringResource(AmoR.string.amo_ok)) { saved = false; onCleared() }; return }
    val formErrors = mapErrors(errors)
    AmoOutletFormBody(
        items = failedNotice(failed) + otherErrors(errors) + newShopItems(
            route = Pick(AmoOutletLists.routes(days), routeId, { routeId = it; clusterId = null }),
            cluster = Pick(clusters, clusterId, { clusterId = it }, enabled = routeId != null),
            text = ShopText(name, owner, mobile, { name = it }, { owner = it }, { mobile = it }),
            sub = Pick(subs.map { AmoOption(it.id, if (bangla) it.labelBn ?: it.labelEn else it.labelEn) }, subId, { subId = it }),
            geoClass = geo, onGeoClass = { geo = it }, capture = cap, onShutter = { scope.launch { capture.shutter() } }, errors = formErrors,
        ),
        onSave = {
            if (!busy) {
                busy = true
                scope.launch {
                    if (cap.fix?.isMock == true) { errors = setOf(RequestError.MOCK_FIX); busy = false; return@launch } // anti-spoofing: a fake fix is never queued
                    val f = OutletRequestForm(
                        kind = OutletRequestKind.NEW, requestUuid = ids.get(), name = name, ownerName = owner, mobile = mobile, clusterId = clusterId,
                        subChannelId = subId, geoClass = geo, fix = cap.fix, photoUuids = listOfNotNull(cap.photo?.photoUuid),
                    )
                    when (val r = safeSubmit(day, f)) {
                null -> failed = true
                        is RequestValidation.Invalid -> errors = r.errors
                        is RequestValidation.Ok -> { failed = false; errors = emptySet(); day.drafts.clear(draftKey); saved = true }
                    }
                    busy = false
                }
            }
        },
        saveEnabled = clusterId != null, busy = busy, modifier = Modifier.fillMaxSize(),
    )
}

/** Outlets of one route of the downloaded days (the first occurrence wins). */
private object ControlCallOutlets { fun of(days: List<RouteDay>, routeId: Long): List<OutletEntity> = com.aktcl.aron.feature.amo.ControlCall.outlets(days, routeId) }

// ---------------------------------------------------------------- Permanent close (57, 58)

/** Permanent close: cluster → retailer → read-only details → সংরক্ষণ → "আপনি কি নিশ্চিত?" → হ্যাঁ → dialog → OK → the cleared form. */
@Composable
fun AmoCloseHost(day: AmoDay) {
    var generation by rememberSaveable { mutableStateOf(0) }
    key(generation) { CloseForm(day) { generation++ } }
}

@Composable
private fun CloseForm(day: AmoDay, onCleared: () -> Unit) {
    val scope = rememberCoroutineScope()
    var clusterId by rememberSaveable { mutableStateOf<Long?>(null) }
    var outletId by rememberSaveable { mutableStateOf<Long?>(null) }
    var errors by remember { mutableStateOf<Set<RequestError>>(emptySet()) }
    var failed by remember { mutableStateOf(false) }
    var pickedMissing by remember { mutableStateOf(false) }
    var ask by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val outlet = outletId?.let { id -> day.zoneOutlets.firstOrNull { it.outletId == id } }
    val draftKey = "own:close:${outletId ?: 0}"
    val ids = remember(draftKey) { SubmitUuid(day, draftKey) }
    if (saved) { AmoSuccessDialog(stringResource(AmoR.string.amo_out_saved), stringResource(AmoR.string.amo_ok)) { saved = false; onCleared() }; return }
    fun commit() {
        val o = outlet ?: return
        busy = true
        scope.launch {
            val f = OutletRequestForm(kind = OutletRequestKind.CLOSE, outletId = o.outletId, requestUuid = ids.get(), confirmed = true, openDueMtk = o.openDueMtk)
            when (val r = safeSubmit(day, f)) {
                null -> failed = true
                is RequestValidation.Invalid -> errors = r.errors
                is RequestValidation.Ok -> { failed = false; errors = emptySet(); day.drafts.clear(draftKey); saved = true }
            }
            busy = false
        }
    }
    if (ask) {
        AronConfirmDialog(
            stringResource(OutR.string.out_req_confirm_title), stringResource(OutR.string.out_req_confirm_close), stringResource(OutR.string.out_req_yes), stringResource(OutR.string.out_req_no),
            onConfirm = { ask = false; commit() }, onDismiss = { ask = false },
        )
    }
    val inCluster = day.zoneOutlets.filter { it.clusterId == clusterId }
    val warn = buildList<AmoFormItem> {
        if ((outlet?.openDueMtk ?: 0) > 0) add(AmoFormItem.Notice(stringResource(OutR.string.out_req_dues_warning), BannerKind.Warning))
    }
    AmoOutletFormBody(
        items = failedNotice(failed) + otherErrors(errors) + warn + closeItems(
            cluster = Pick(AmoOutletLists.clusters(day.zoneOutlets), clusterId, { clusterId = it; outletId = null }),
            retailer = Pick(AmoOutletLists.retailers(inCluster, clusterId, OutletPicker.ALL_CHIP), outletId, { outletId = it }, enabled = clusterId != null),
            name = outlet?.name, owner = outlet?.ownerName, mobile = outlet?.contactNumber,
            errors = if (pickedMissing && outletId == null) setOf(AmoFormError.RETAILER) else emptySet(),
        ),
        onSave = { if (outlet == null) pickedMissing = true else if (!busy) ask = true },
        busy = busy, modifier = Modifier.fillMaxSize(),
    )
}

// ---------------------------------------------------------------- Change information (59, 60, 61)

/** Change information: route → retailer (alphabet bar) → cluster, name, owner, mobile → GEO and photo → সংরক্ষণ → "আপনি কি নিশ্চিত?" → dialog. */
@Composable
fun AmoChangeInfoHost(day: AmoDay) {
    val days = rememberRouteDays(day)
    var generation by rememberSaveable { mutableStateOf(0) }
    key(generation) { ChangeInfoForm(day, days) { generation++ } }
}

@Composable
private fun ChangeInfoForm(day: AmoDay, days: List<RouteDay>, onCleared: () -> Unit) {
    val scope = rememberCoroutineScope()
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var outletId by rememberSaveable { mutableStateOf<Long?>(null) }
    var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
    var name by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf<Set<RequestError>>(emptySet()) }
    var failed by remember { mutableStateOf(false) }
    var ask by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var photoDone by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val capture = remember { day.newCapture("outlet_capture") }
    val cap by capture.state.collectAsState()
    val outlets = routeId?.let { ControlCallOutlets.of(days, it) }.orEmpty()
    val outlet = outlets.firstOrNull { it.outletId == outletId }
    val draftKey = "own:info:${outletId ?: 0}"
    val ids = remember(draftKey) { SubmitUuid(day, draftKey) }
    // choosing a retailer fills the three fields from the bundle; the AMO edits them
    LaunchedEffect(outletId) {
        outlet?.let { name = it.name; owner = it.ownerName.orEmpty(); mobile = AmoOutletLists.phone(it.contactNumber).orEmpty() }
    }
    // "ছবি ধারণ করা সম্পন্ন হয়েছে" (60): once per captured photo
    val photoUuid = cap.photo?.photoUuid
    var announced by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(photoUuid) { if (photoUuid != null && photoUuid != announced) { announced = photoUuid; photoDone = true } }
    if (saved) { AmoSuccessDialog(stringResource(AmoR.string.amo_out_saved), stringResource(AmoR.string.amo_ok)) { saved = false; onCleared() }; return }
    if (photoDone) { AmoSuccessDialog(stringResource(AmoR.string.amo_out_photo_done), stringResource(AmoR.string.amo_ok)) { photoDone = false } }
    fun commit() {
        val o = outlet ?: return
        busy = true
        scope.launch {
            if (cap.fix?.isMock == true) { errors = setOf(RequestError.MOCK_FIX); busy = false; return@launch }
            val f = OutletRequestForm(
                kind = OutletRequestKind.INFO, outletId = o.outletId, requestUuid = ids.get(), name = name, ownerName = owner, mobile = mobile,
                fix = cap.fix, photoUuids = listOfNotNull(cap.photo?.photoUuid), confirmed = true,
            )
            when (val r = safeSubmit(day, f)) {
                null -> failed = true
                is RequestValidation.Invalid -> errors = r.errors
                is RequestValidation.Ok -> { failed = false; errors = emptySet(); day.drafts.clear(draftKey); saved = true }
            }
            busy = false
        }
    }
    if (ask) {
        AronConfirmDialog(
            stringResource(OutR.string.out_req_confirm_title), stringResource(OutR.string.out_req_confirm_message), stringResource(OutR.string.out_req_yes), stringResource(OutR.string.out_req_no),
            onConfirm = { ask = false; commit() }, onDismiss = { ask = false },
        )
    }
    val formErrors = mapErrors(errors) + if (RequestError.OUTLET_REQUIRED in errors) setOf(AmoFormError.RETAILER) else emptySet()
    val letters = AmoOutletLists.letters(outlets, null)
    AmoOutletFormBody(
        items = failedNotice(failed) + otherErrors(errors) + changeInfoItems(
            route = Pick(AmoOutletLists.routes(days), routeId, { routeId = it; outletId = null; chip = OutletPicker.ALL_CHIP }),
            retailer = Pick(AmoOutletLists.retailers(outlets, null, chip), outletId, { outletId = it }, enabled = routeId != null),
            letters = if (routeId != null && letters.isNotEmpty()) AmoFormItem.Letters(letters, chip) { chip = it } else null,
            cluster = Pick(listOfNotNull(outlet?.clusterId?.let { AmoOption(it, outlet.clusterName ?: it.toString()) }), outlet?.clusterId, {}, enabled = false),
            text = ShopText(name, owner, mobile, { name = it }, { owner = it }, { mobile = it }),
            capture = cap, onShutter = { scope.launch { capture.shutter() } }, errors = formErrors,
        ),
        onSave = { if (outlet == null) errors = setOf(RequestError.OUTLET_REQUIRED) else if (!busy) ask = true },
        busy = busy, modifier = Modifier.fillMaxSize(),
    )
}

// ---------------------------------------------------------------- Update Base (62, 63, 64)

private enum class BaseStep { PICK, MAP }

/**
 * Update Base (D-AMO-MAN-13): route → retailer (alphabet bar) → the green আপডেট বেস button (off until a retailer is chosen) → "আপনি কি নিশ্চিত, খুচরা
 * বিক্রেতার অবস্থান আপডেট করতে?" আপডেট/বাতিল → camera (GEO with the photo, mock flag kept) → the map "অবস্থান নিশ্চিত করুন" with the draggable pin within
 * `cfg.geo.update_base_max_distance_m` → নিশ্চিত করুন. The record stays a `location` outlet-change request; the server guards stay at approval.
 */
@Composable
fun AmoUpdateBaseHost(day: AmoDay) {
    val days = rememberRouteDays(day)
    var generation by rememberSaveable { mutableStateOf(0) }
    key(generation) { UpdateBaseFlow(day, days) { generation++ } }
}

@Composable
private fun UpdateBaseFlow(day: AmoDay, days: List<RouteDay>, onCleared: () -> Unit) {
    val scope = rememberCoroutineScope()
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var outletId by rememberSaveable { mutableStateOf<Long?>(null) }
    var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
    var ask by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf(BaseStep.PICK) }
    var errors by remember { mutableStateOf<Set<RequestError>>(emptySet()) }
    var failed by remember { mutableStateOf(false) }
    var noFix by remember { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pinLat by rememberSaveable { mutableStateOf<Double?>(null) }
    var pinLng by rememberSaveable { mutableStateOf<Double?>(null) }
    val capture = remember { day.newCapture("outlet_capture") }
    val cap by capture.state.collectAsState()
    val outlets = routeId?.let { ControlCallOutlets.of(days, it) }.orEmpty()
    val outlet = outlets.firstOrNull { it.outletId == outletId }
    val draftKey = "own:location:${outletId ?: 0}"
    val ids = remember(draftKey) { SubmitUuid(day, draftKey) }
    val fix = cap.fix?.takeIf { it.isOk }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (saved) { AmoSuccessDialog(stringResource(AmoR.string.amo_out_saved), stringResource(AmoR.string.amo_ok)) { saved = false; onCleared() }; return }
    if (ask && outlet != null) {
        AronConfirmDialog(
            stringResource(AmoR.string.amo_base_confirm_title), "", stringResource(AmoR.string.amo_base_confirm_yes), stringResource(AmoR.string.amo_base_confirm_no),
            onConfirm = {
                ask = false; noFix = false
                scope.launch {
                    val after = capture.shutter()
                    val f = after.fix?.takeIf { it.isOk && !it.isMock }
                    if (after.photo != null && f != null) { pinLat = f.lat; pinLng = f.lng; step = BaseStep.MAP } else noFix = true
                }
            },
            onDismiss = { ask = false },
        )
    }
    if (step == BaseStep.MAP && fix != null && outlet != null) {
        val live = remember(day.online()) { com.aktcl.aron.core.map.outletMapLive(day.online(), com.aktcl.aron.core.map.MapSettings(), com.aktcl.aron.core.map.MapsKey.present(context)) }
        val pin = (pinLat ?: fix.lat!!) to (pinLng ?: fix.lng!!)
        val maxM = day.updateBaseMaxM
        Column(Modifier.fillMaxSize()) {
            if (failed) AmoBanner(stringResource(AmoR.string.amo_task_failed), Modifier.padding(horizontal = AronTokens.Space.Screen), BannerKind.Error)
            errors.forEach {
                AmoBanner(
                    stringResource(when (it) { RequestError.POINT_TOO_FAR -> OutR.string.out_req_err_point_far; RequestError.MOCK_FIX -> OutR.string.out_req_err_mock; RequestError.MOVE_TOO_FAR -> OutR.string.out_req_err_move_far; RequestError.PHOTO_REQUIRED -> OutR.string.out_req_err_photo; else -> OutR.string.out_req_err_geo }),
                    Modifier.padding(horizontal = AronTokens.Space.Screen), BannerKind.Error,
                )
            }
            UpdateBaseMapContent(
                fixLat = fix.lat!!, fixLng = fix.lng!!, pin = pin, maxM = maxM, live = live, onPin = { pinLat = it.first; pinLng = it.second },
                confirmEnabled = !busy,
                onConfirm = {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            val f = OutletRequestForm(
                                kind = OutletRequestKind.LOCATION, outletId = outlet.outletId, requestUuid = ids.get(), fix = cap.fix,
                                photoUuids = listOfNotNull(cap.photo?.photoUuid), pointLat = pin.first, pointLng = pin.second, oldLat = outlet.lat, oldLng = outlet.lng,
                            )
                            when (val r = safeSubmit(day, f)) {
                null -> failed = true
                                is RequestValidation.Invalid -> errors = r.errors
                                is RequestValidation.Ok -> { failed = false; errors = emptySet(); day.drafts.clear(draftKey); saved = true }
                            }
                            busy = false
                        }
                    }
                },
                fallback = {
                    // the steps move the pin from the AMO's fix in 10 m; the offset shown is the pin's own (north, east) from that fix
                    val fx = fix.lat!!; val fy = fix.lng!!
                    val n = ((pin.first - fx) * 111_320.0).roundToInt(); val e = ((pin.second - fy) * 111_320.0 * kotlin.math.cos(Math.toRadians(fx))).roundToInt()
                    UpdateBasePointContent(
                        northM = n, eastM = e, maxM = maxM, hasFix = true,
                        onMove = { dn, de -> UpdateBasePoint.move(fx, fy, n + dn, e + de).let { pinLat = it.first; pinLng = it.second } },
                        onReset = { pinLat = fx; pinLng = fy },
                    )
                },
            )
        }
        return
    }
    val letters = AmoOutletLists.letters(outlets, null)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            AmoOutletFormBody(
                items = failedNotice(failed) + (if (noFix) listOf(AmoFormItem.Notice(stringResource(OutR.string.out_req_err_geo), BannerKind.Error)) else emptyList()) + listOfNotNull(
                    routeItem(Pick(AmoOutletLists.routes(days), routeId, { routeId = it; outletId = null; chip = OutletPicker.ALL_CHIP }), emptySet()),
                    if (routeId != null && letters.isNotEmpty()) AmoFormItem.Letters(letters, chip) { chip = it } else null,
                    retailerItem(Pick(AmoOutletLists.retailers(outlets, null, chip), outletId, { outletId = it }, enabled = routeId != null), emptySet()),
                ),
                onSave = {}, modifier = Modifier.fillMaxSize(), bottom = {
                    AmoPrimary(stringResource(AmoR.string.amo_out_base_button), { ask = true }, Modifier.fillMaxWidth().padding(AronTokens.Space.Screen), enabled = outlet != null)
                },
            )
        }
    }
}
