package com.aktcl.aron.core.database

import com.aktcl.aron.core.database.record.AttendanceEventPayload
import com.aktcl.aron.core.database.record.DeviceGeoVerdictPayload
import com.aktcl.aron.core.database.record.FixDeviceStatePayload
import com.aktcl.aron.core.database.record.GeoFixPayload
import com.aktcl.aron.core.database.record.MemoDiscountPayload
import com.aktcl.aron.core.database.record.MemoLinePayload
import com.aktcl.aron.core.database.record.MemoPayload
import com.aktcl.aron.core.database.record.QcLinePayload
import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.core.database.record.StockMovementPayload
import com.aktcl.aron.core.database.record.VisitClosePayload
import com.aktcl.aron.core.database.record.VisitPayload
import com.aktcl.aron.core.database.reference.BundleOutlet
import com.aktcl.aron.core.database.reference.Route
import com.aktcl.aron.core.database.reference.RouteSnapshot
import com.aktcl.aron.core.database.reference.Sku
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

    @Test fun geoFix() = check(GeoFixPayload.serializer(), "GeoFix")
    @Test fun fixDevice() = check(FixDeviceStatePayload.serializer(), "FixDeviceState")
    @Test fun geoVerdict() = check(DeviceGeoVerdictPayload.serializer(), "DeviceGeoVerdict")
    @Test fun attendance() = check(AttendanceEventPayload.serializer(), "AttendanceEventPayload")
    @Test fun stock() = check(StockMovementPayload.serializer(), "StockMovementPayload")
    @Test fun visit() = check(VisitPayload.serializer(), "VisitPayload")
    @Test fun visitClose() = check(VisitClosePayload.serializer(), "VisitClosePayload")
    @Test fun memo() = check(MemoPayload.serializer(), "MemoPayload")
    @Test fun memoLine() = check(MemoLinePayload.serializer(), "MemoLinePayload")
    @Test fun memoDiscount() = check(MemoDiscountPayload.serializer(), "MemoDiscountPayload")
    @Test fun qcLine() = check(QcLinePayload.serializer(), "QcLinePayload")
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
}
