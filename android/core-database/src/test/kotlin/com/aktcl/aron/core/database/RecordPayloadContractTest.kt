package com.aktcl.aron.core.database

import com.aktcl.aron.contract.AttendanceEventPayload
import com.aktcl.aron.contract.DeviceGeoVerdict
import com.aktcl.aron.contract.FixDeviceState
import com.aktcl.aron.contract.GeoFix
import com.aktcl.aron.contract.MemoDiscountPayload
import com.aktcl.aron.contract.MemoLinePayload
import com.aktcl.aron.contract.MemoPayload
import com.aktcl.aron.contract.QcLinePayload
import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.contract.StockMovementPayload
import com.aktcl.aron.contract.VisitClosePayload
import com.aktcl.aron.contract.VisitPayload
import com.aktcl.aron.contract.BundleOutlet
import com.aktcl.aron.contract.Route
import com.aktcl.aron.contract.RouteSnapshot
import com.aktcl.aron.contract.Sku
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record payloads and reference rows use exactly the contract's member names (docs/26 s1: never invent a field). */
class RecordPayloadContractTest {
    private fun check(serializer: KSerializer<*>, schema: String, coverAll: Boolean = true) {
        val dto = serializer.descriptor.elementNames.toSet()
        val yaml = ContractYaml.propertyNames(schema)
        assertTrue("$schema: not in the contract: ${dto - yaml}", yaml.containsAll(dto))
        assertTrue("$schema: required missing: ${ContractYaml.requiredNames(schema) - dto}", dto.containsAll(ContractYaml.requiredNames(schema)))
        if (coverAll) assertEquals("$schema must cover every member", yaml, dto)
    }

    @Test fun geoFix() = check(GeoFix.serializer(), "GeoFix")
    @Test fun fixDevice() = check(FixDeviceState.serializer(), "FixDeviceState")
    @Test fun geoVerdict() = check(DeviceGeoVerdict.serializer(), "DeviceGeoVerdict")
    @Test fun attendance() = check(AttendanceEventPayload.serializer(), "AttendanceEventPayload")
    @Test fun stock() = check(StockMovementPayload.serializer(), "StockMovementPayload")
    @Test fun visit() = check(VisitPayload.serializer(), "VisitPayload")
    @Test fun visitClose() = check(VisitClosePayload.serializer(), "VisitClosePayload")
    @Test fun memo() = check(MemoPayload.serializer(), "MemoPayload")
    @Test fun memoLine() = check(MemoLinePayload.serializer(), "MemoLinePayload")
    @Test fun memoDiscount() = check(MemoDiscountPayload.serializer(), "MemoDiscountPayload")
    @Test fun qcLine() = check(QcLinePayload.serializer(), "QcLinePayload")
    @Test fun dueCollection() = check(com.aktcl.aron.core.database.record.DueCollectionPayload.serializer(), "DueCollectionPayload")
    @Test fun visitSkip() = check(com.aktcl.aron.core.database.record.VisitSkipPayload.serializer(), "VisitSkipPayload")
    @Test fun daySubmit() = check(com.aktcl.aron.core.database.record.DaySubmitPayload.serializer(), "DaySubmitPayload")
    @Test fun outletRequest() = check(com.aktcl.aron.core.database.record.OutletChangeRequestPayload.serializer(), "OutletChangeRequestPayload")
    @Test fun mediaMeta() = check(com.aktcl.aron.core.database.record.MediaMetaPayload.serializer(), "MediaMetaPayload")
    @Test fun taskEvent() = check(com.aktcl.aron.core.database.record.TaskEventPayload.serializer(), "TaskEventPayload")
    @Test fun bundleOutlet() = check(BundleOutlet.serializer(), "BundleOutlet")
    @Test fun sku() = check(Sku.serializer(), "Sku", coverAll = false)
    @Test fun route() = check(Route.serializer(), "Route", coverAll = false)
    @Test fun routeSnapshot() = check(RouteSnapshot.serializer(), "RouteSnapshot", coverAll = false)

    @Test
    fun theEnvelopeHasEveryRequiredMemberAndNothingElse() {
        val (visit, fix) = TestRows.visit()
        val record = Json.parseToJsonElement(RecordMapping.visit(visit, fix, "2026-10-05T04:36:00.000Z").payloadJson).jsonObject
        val allowed = ContractYaml.propertyNames("RecordEnvelope") + "payload"
        assertTrue("not in the envelope: ${record.keys - allowed}", allowed.containsAll(record.keys))
        val required = ContractYaml.requiredNames("RecordEnvelope")
        assertTrue("required missing: ${required - record.keys}", record.keys.containsAll(required))
    }

    @Test
    fun theFixtureUsesContractShapes() {
        val bundle = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.reader().readText()).jsonObject
        val outletProps = ContractYaml.propertyNames("BundleOutlet")
        val routeProps = ContractYaml.propertyNames("Route")
        val skuProps = ContractYaml.propertyNames("Sku")
        bundle["routes"]!!.jsonArray.forEach { r ->
            val snap = r.jsonObject
            assertTrue(routeProps.containsAll(snap["route"]!!.jsonObject.keys))
            assertTrue(snap.keys.containsAll(ContractYaml.requiredNames("RouteSnapshot")))
            assertTrue(ContractYaml.propertyNames("RouteSnapshot").containsAll(snap.keys))
            assertTrue(snap["day_state"]!!.jsonObject.keys.containsAll(ContractYaml.requiredNames("RouteDayState")))
            snap["outlets"]!!.jsonArray.forEach { o ->
                assertTrue("outlet keys", outletProps.containsAll(o.jsonObject.keys))
                assertTrue(o.jsonObject.keys.containsAll(ContractYaml.requiredNames("BundleOutlet")))
            }
        }
        bundle["products"]!!.jsonObject["skus"]!!.jsonArray.forEach { s ->
            assertTrue(skuProps.containsAll(s.jsonObject.keys))
            assertTrue(s.jsonObject.keys.containsAll(ContractYaml.requiredNames("Sku")))
        }
    }

    /** Encoded payloads (not just DTO names): every required member present (null when empty), nothing extra, nested too. */
    @Test
    fun encodedPayloadsCarryEveryRequiredMemberEvenWhenNull() {
        val at = "2026-10-05T04:36:00.000Z"
        val (visit, fix) = TestRows.visit()
        val noFix = fix.copy(fixStatus = "timeout", lat = null, lng = null, accuracyM = null, gnssJson = null, refreshCount = null, requestPriority = null)
        val (att, attFix) = TestRows.attendance()
        val sale = TestRows.sale(visit.clientUuid)
        val rows = listOf(
            RecordMapping.visit(visit.copy(geoVerdict = "no_fix", geoDistanceM = null, geoOutletLat = null, geoOutletLng = null), noFix, at),
            RecordMapping.attendance(att, attFix.copy(lat = null, lng = null, accuracyM = null, fixStatus = "location_off"), at),
            RecordMapping.stock(TestRows.stock(), at),
            RecordMapping.memo(sale.memo, null, at),
            RecordMapping.memoLine(sale.lines[0], visit.clientUuid, at),
            RecordMapping.memoDiscount(sale.discounts[0].copy(skuId = null, qtyBase = null, offerId = null, offerVersionId = null, lineNo = null), visit.clientUuid, at),
            RecordMapping.qcLine(sale.qcLines[0], at),
            RecordMapping.visitClose(TestRows.close(visit.clientUuid), at),
        )
        val schemaOf = mapOf(
            "visit" to "VisitPayload", "attendance_event" to "AttendanceEventPayload", "stock_movement" to "StockMovementPayload",
            "memo" to "MemoPayload", "memo_line" to "MemoLinePayload", "memo_discount" to "MemoDiscountPayload",
            "qc_line" to "QcLinePayload", "visit_close" to "VisitClosePayload",
        )
        fun verify(obj: kotlinx.serialization.json.JsonObject, schema: String) {
            val missing = ContractYaml.requiredNames(schema) - obj.keys
            assertTrue("$schema missing required members: $missing", missing.isEmpty())
            val extra = obj.keys - ContractYaml.propertyNames(schema)
            assertTrue("$schema extra members: $extra", extra.isEmpty())
            obj["fix"]?.takeIf { it is kotlinx.serialization.json.JsonObject }?.let { f ->
                verify(f.jsonObject, "GeoFix")
                verify(f.jsonObject["device"]!!.jsonObject, "FixDeviceState")
                assertTrue("refresh_count may not be null", f.jsonObject["refresh_count"] !is kotlinx.serialization.json.JsonNull)
            }
            obj["geo"]?.let { verify(it.jsonObject, "DeviceGeoVerdict") }
        }
        rows.forEach { row ->
            val record = Json.parseToJsonElement(row.payloadJson).jsonObject
            verify(record["payload"]!!.jsonObject, schemaOf.getValue(row.recordType))
        }
    }

    /** The switch to the shared:contract DTOs keeps the wire as it was: GNSS summary kept, empty offer list left out. */
    @Test
    fun contractDtosKeepTheStoredGnssAndLeaveOutAnEmptyOfferList() {
        val at = "2026-10-05T04:36:00.000Z"
        val (visit, fix) = TestRows.visit()
        val payload = { row: com.aktcl.aron.core.database.entity.OutboxEntity -> Json.parseToJsonElement(row.payloadJson).jsonObject["payload"]!!.jsonObject }
        val gnss = payload(RecordMapping.visit(visit, fix, at))["fix"]!!.jsonObject["gnss"]!!.jsonObject
        assertEquals(Json.parseToJsonElement(fix.gnssJson!!), gnss)
        val sale = TestRows.sale(visit.clientUuid)
        assertTrue("offer_version_ids" !in payload(RecordMapping.memo(sale.memo.copy(offerVersionIdsJson = "[]"), null, at)))
        assertEquals("[12]", payload(RecordMapping.memo(sale.memo, null, at))["offer_version_ids"].toString())
    }

    /** A stored GNSS summary that is not a contract GnssSummary never fails the capture; it is left out. */
    @Test
    fun anUnreadableGnssSummaryIsLeftOutInsteadOfFailingTheSale() {
        val (visit, fix) = TestRows.visit()
        val row = RecordMapping.visit(visit, fix.copy(gnssJson = """{"satellites":"many"}"""), "2026-10-05T04:36:00.000Z")
        val f = Json.parseToJsonElement(row.payloadJson).jsonObject["payload"]!!.jsonObject["fix"]!!.jsonObject
        assertTrue("gnss" !in f)
    }

    /** Quantities are int32 in the contract: a stored value outside it fails before anything is written, never wraps. */
    @Test(expected = ArithmeticException::class)
    fun aQuantityOutsideInt32IsRefusedNotWrapped() {
        RecordMapping.stock(TestRows.stock().copy(qtyEntered = Int.MAX_VALUE + 1L, qtyBase = Int.MAX_VALUE + 1L), "2026-10-05T04:36:00.000Z")
    }
}
