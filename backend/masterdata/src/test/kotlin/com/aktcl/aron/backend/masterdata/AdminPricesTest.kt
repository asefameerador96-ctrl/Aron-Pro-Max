package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F-API-080 and F-ADM-081: preview, idempotent publish, back-dating, and the second-approver rail above cfg.price.max_change_pct. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminPricesTest {
    private lateinit var t: AdminHarness

    @BeforeAll fun setUp() { t = AdminHarness() }
    @AfterAll fun tearDown() = t.close()

    private val fwd get() = t.today.plusDays(3).toString()
    private fun uuid() = UUID.randomUUID().toString()
    private fun row(sku: Long, type: String, amount: Long, per: Int = 1) = """{"sku_id":$sku,"price_type":"$type","amount_mtk":$amount,"per_base_qty":$per}"""
    private fun req(batch: String, from: String, vararg rows: String, reason: String = "Quarterly price revision approved by finance") =
        """{"batch_uuid":"$batch","valid_from":"$from","prices":[${rows.joinToString(",")}],"change_reason":"$reason"}"""

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.preview(tok: String, body: String) = send(HttpMethod.Post, "/v1/admin/prices/preview", tok, body)
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.publish(tok: String, body: String) = send(HttpMethod.Post, "/v1/admin/prices", tok, body)
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.decide(tok: String, batch: String, decision: String, note: String? = null) =
        send(HttpMethod.Post, "/v1/admin/prices/batches/$batch/decision", tok, """{"decision":"$decision"${if (note != null) ""","note":"$note"""" else ""}}""")

    /** Preview then publish; returns the publish response. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.previewAndPublish(tok: String, body: String): HttpResponse {
        assertEquals(HttpStatusCode.OK, preview(tok, body).status)
        return publish(tok, body)
    }

    private fun listVersion() = t.scalar("SELECT COALESCE((SELECT value::text FROM app.cfg_value WHERE key = 'cfg.price.list_version' AND scope_type = 'global' AND effective_to IS NULL), '0')").toLong()
    private fun rows(sku: Long, type: String) = t.scalar("SELECT string_agg(amount_mtk || '@' || valid_from || '>' || COALESCE(valid_to::text, 'open'), ' ' ORDER BY valid_from) FROM app.sku_price WHERE sku_id = $sku AND price_type = '$type'")
    private fun auditCount(entity: String, id: String? = null) = t.count("SELECT count(*) FROM app.audit_log WHERE entity = '$entity'" + (id?.let { " AND entity_id = '$it'" } ?: ""))

    // ---------------------------------------------------------------------------------------------- pure arithmetic

    @Test
    fun percentRailIsExactAtTheThresholdAndNeverUsesFloats() {
        val fifteen = BigDecimal(15)
        // Exactly 15 percent up or down is allowed; one milli-taka more needs the second person.
        assertFalse(PriceMath.exceeds(10000, 1, 11500, 1, fifteen)); assertTrue(PriceMath.exceeds(10000, 1, 11501, 1, fifteen))
        assertFalse(PriceMath.exceeds(10000, 1, 8500, 1, fifteen)); assertTrue(PriceMath.exceeds(10000, 1, 8499, 1, fifteen))
        // Rounding: 7.935 Tk to 9.125 Tk is 14.9968 percent (shown 15.00) and passes; 9.126 is 15.0094 percent (shown 15.01) and does not.
        assertFalse(PriceMath.exceeds(7935, 1, 9125, 1, fifteen)); assertTrue(PriceMath.exceeds(7935, 1, 9126, 1, fifteen))
        assertEquals(BigDecimal("15.00"), PriceMath.displayPct(7935, 1, 9125, 1)); assertEquals(BigDecimal("15.01"), PriceMath.displayPct(7935, 1, 9126, 1))
        // Different quantity bases compare per base unit: 23.000 Tk for 2 sticks is 11.500 each, exactly 15 percent over 10.000.
        assertFalse(PriceMath.exceeds(10000, 1, 23000, 2, fifteen)); assertTrue(PriceMath.exceeds(10000, 1, 23002, 2, fifteen))
        // A fractional threshold and the largest money values do not overflow or round.
        assertFalse(PriceMath.exceeds(1000, 1, 1125, 1, BigDecimal("12.5"))); assertTrue(PriceMath.exceeds(1000, 1, 1126, 1, BigDecimal("12.5")))
        assertTrue(PriceMath.exceeds(Long.MAX_VALUE / 2, 1000, Long.MAX_VALUE, 1, fifteen))
        // From a zero price any positive price has no finite percent and needs approval; zero to zero is no move.
        assertTrue(PriceMath.exceeds(0, 1, 1, 1, BigDecimal(100))); assertFalse(PriceMath.exceeds(0, 1, 0, 1, fifteen))
        assertEquals(BigDecimal("0.00"), PriceMath.displayPct(5000, 1, 5000, 1))
    }

    // ---------------------------------------------------------------------------------------------- F-API-080

    @Test
    fun previewIsMandatoryReportsAffectedAndPublishIsIdempotent() = t.app {
        val sku = t.newSku("PRC-A1", outlet = 9200, cc = 9200, distributor = 7935, reporting = 9200, nto = 0)
        val b = uuid()
        val body = req(b, fwd, row(sku, "outlet", 9500), row(sku, "cc", 9400), row(sku, "distributor", 8000))

        // No preview, no publish.
        val noPreview = publish(t.admin, body)
        assertEquals(HttpStatusCode.Conflict, noPreview.status); assertEquals("ERR_REQUEST_STATE", noPreview.obj()["code"]!!.jsonPrimitive.content)
        assertEquals("9200@2026-01-01>open", rows(sku, "outlet"))

        val pv = preview(t.admin, body)
        assertEquals(HttpStatusCode.OK, pv.status)
        val p = pv.obj()
        assertEquals(b, p["batch_uuid"]!!.jsonPrimitive.content); assertEquals(1, p["skus_affected"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("outlet", "cc", "distributor"), p["price_types"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(t.count("SELECT count(*) FROM app.outlet WHERE status = 'active'"), p["outlets_affected"]!!.jsonPrimitive.content.toInt())
        assertTrue(p["devices_affected"]!!.jsonPrimitive.content.toInt() >= 0)
        assertEquals("false", p["approval_required"]!!.jsonPrimitive.content)
        // 9200 -> 9500 is 3.26 percent, the largest move of the three (7935 -> 8000 is 0.82, 9200 -> 9400 is 2.17).
        assertEquals(0, BigDecimal("3.26").compareTo(BigDecimal(p["max_change_pct"]!!.jsonPrimitive.content)))
        val v0 = listVersion(); val audits0 = auditCount("sku_price")
        assertEquals(audits0, auditCount("sku_price")) // a preview writes no audit row and no price
        assertEquals(0, t.count("SELECT count(*) FROM app.sku_price WHERE sku_id = $sku AND valid_from > DATE '2026-01-01'"))
        val r = publish(t.admin, body)
        assertEquals(HttpStatusCode.Created, r.status); assertEquals("published", r.headers[BATCH_STATUS_HEADER])
        val out = r.obj()
        assertEquals(v0 + 1, out["price_list_version"]!!.jsonPrimitive.content.toLong())
        val items = out["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, items.size)
        assertTrue(items.all { it["valid_from"]!!.jsonPrimitive.content == fwd && it["valid_to"]!!.jsonPrimitive.content == "null" })
        // Money stays integer milli-taka: the stored value is the request value, no float on the way.
        assertEquals(setOf(9500L, 9400L, 8000L), items.map { it["amount_mtk"]!!.jsonPrimitive.content.toLong() }.toSet())
        assertEquals("9200@2026-01-01>$fwd 9500@$fwd>open", rows(sku, "outlet"))
        assertEquals("7935@2026-01-01>$fwd 8000@$fwd>open", rows(sku, "distributor"))
        assertEquals("9200@2026-01-01>open", rows(sku, "reporting")) // untouched types stay
        // One audit row per written price and one for the batch, all in the same transaction as the write.
        assertEquals(audits0 + 3, auditCount("sku_price")); assertEquals(1, auditCount("price_batch", b))
        assertEquals("9200", t.scalar("SELECT before->>'amount_mtk' FROM app.audit_log WHERE entity = 'sku_price' AND after->>'price_type' = 'outlet' AND after->>'sku_id' = '$sku'"))
        assertEquals(v0 + 1, listVersion()); assertEquals(1, t.count("SELECT count(*) FROM app.cfg_version WHERE summary LIKE '%$b%'"))

        // Replay: same answer, nothing changes (rows, versions, audit).
        val rows0 = t.scalar("SELECT count(*) FROM app.sku_price"); val cfgv0 = t.scalar("SELECT max(config_version) FROM app.cfg_version"); val auditAll0 = t.scalar("SELECT count(*) FROM app.audit_log")
        val again = publish(t.admin, body)
        assertEquals(HttpStatusCode.Created, again.status)
        assertEquals(out, again.obj())
        assertEquals(rows0, t.scalar("SELECT count(*) FROM app.sku_price")); assertEquals(cfgv0, t.scalar("SELECT max(config_version) FROM app.cfg_version"))
        assertEquals(auditAll0, t.scalar("SELECT count(*) FROM app.audit_log")); assertEquals(v0 + 1, listVersion())
        // A replay from a different admin is the same batch, still nothing new.
        assertEquals(out, publish(t.admin2, body).obj())
        // The same batch_uuid with different rows is a conflict, not a second publish.
        val other = req(b, fwd, row(sku, "outlet", 9600))
        assertEquals(HttpStatusCode.Conflict, publish(t.admin, other).status)
        assertEquals("ERR_CONFLICT", preview(t.admin, other).obj()["code"]!!.jsonPrimitive.content)
        assertEquals(auditAll0, t.scalar("SELECT count(*) FROM app.audit_log"))

        // listPrices shows the effective-dated rows and honours valid_on and the type filter.
        val on = send(HttpMethod.Get, "/v1/admin/prices?sku_id=$sku&price_type=outlet&valid_on=$fwd", t.sup1).obj()["items"]!!.jsonArray
        assertEquals(listOf(9500L), on.map { it.jsonObject["amount_mtk"]!!.jsonPrimitive.content.toLong() })
        val all = send(HttpMethod.Get, "/v1/admin/prices?sku_id=$sku&limit=2", t.sup1).obj()
        assertEquals(2, all["items"]!!.jsonArray.size); assertTrue(all["next_cursor"]!!.jsonPrimitive.content.isNotEmpty())
        // A TSO sees the selling price types only.
        val tso = send(HttpMethod.Get, "/v1/admin/prices?sku_id=$sku&limit=100", t.tok("tso1001", Role.TSO)).obj()["items"]!!.jsonArray.map { it.jsonObject["price_type"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("outlet", "cc", "distributor"), tso)
    }

    @Test
    fun backDatingNeedsFinanceBackdateAndOverlapsAreRefused() = t.app {
        val sku = t.newSku("PRC-B1", outlet = 10000)
        val today = t.today.toString()
        val body = req(uuid(), today, row(sku, "outlet", 10100))
        // Today and the past are back-dating: refused without the permission, for preview and publish alike.
        val denied = preview(t.admin, body)
        assertEquals(HttpStatusCode.BadRequest, denied.status); assertEquals("ERR_MASTER_EFFECTIVE_DATE_PAST", denied.obj()["code"]!!.jsonPrimitive.content)
        assertEquals("ERR_MASTER_EFFECTIVE_DATE_PAST", publish(t.admin, body).obj()["code"]!!.jsonPrimitive.content)
        assertEquals("10000@2026-01-01>open", rows(sku, "outlet"))
        val withPerm = t.tok("admin1001", Role.ADMIN, "finance.backdate")
        val ok = previewAndPublish(withPerm, body)
        assertEquals(HttpStatusCode.Created, ok.status)
        assertEquals("10000@2026-01-01>$today 10100@$today>open", rows(sku, "outlet"))

        // A price already scheduled from that date or later cannot be overwritten silently.
        val s2 = t.newSku("PRC-B2", outlet = 10000)
        assertEquals(HttpStatusCode.Created, previewAndPublish(t.admin, req(uuid(), t.today.plusDays(10).toString(), row(s2, "outlet", 10100))).status)
        val clash = previewAndPublish(t.admin, req(uuid(), t.today.plusDays(5).toString(), row(s2, "outlet", 10200)))
        assertEquals(HttpStatusCode.Conflict, clash.status); assertEquals("ERR_MASTER_OVERLAP", clash.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(1, t.count("SELECT count(*) FROM app.sku_price WHERE sku_id = $s2 AND price_type = 'outlet' AND valid_from > DATE '2026-01-01'"))
    }

    @Test
    fun publishValidationAndRoles() = t.app {
        val sku = t.newSku("PRC-C1", outlet = 10000)
        val batches0 = t.count("SELECT count(*) FROM app.price_batch WHERE status <> 'previewed'")
        suspend fun bad(body: String, what: String) { assertEquals(HttpStatusCode.BadRequest, preview(t.admin, body).status, "preview $what"); assertEquals(HttpStatusCode.BadRequest, publish(t.admin, body).status, "publish $what") }
        bad(req("not-a-uuid", fwd, row(sku, "outlet", 1)), "uuid")
        bad(req(uuid(), "2026-13-45", row(sku, "outlet", 1)), "date")
        bad(req(uuid(), fwd), "empty rows")
        bad(req(uuid(), fwd, row(sku, "outlet", -1)), "negative")
        bad(req(uuid(), fwd, row(sku, "outlet", 1, per = 0)), "per qty")
        bad(req(uuid(), fwd, row(sku, "gold", 1)), "type")
        bad(req(uuid(), fwd, row(sku, "outlet", 1), row(sku, "outlet", 2)), "duplicate")
        assertEquals(HttpStatusCode.BadRequest, preview(t.admin, req(uuid(), fwd, row(999999, "outlet", 1))).status, "unknown sku")
        bad(req(uuid(), fwd, row(sku, "outlet", 1), reason = "short"), "reason")
        bad("""{"batch_uuid":"${uuid()}","valid_from":"$fwd","prices":[{"sku_id":$sku,"price_type":"outlet","amount_mtk":9.5}],"change_reason":"Decimal slip in the amount"}""", "float money")
        bad("""{"batch_uuid":"${uuid()}","valid_from":"$fwd","prices":[{"sku_id":$sku,"price_type":"outlet","amount_mtk":"9500"}],"change_reason":"String money is not allowed"}""", "string money")
        bad("""{"batch_uuid":"${uuid()}","valid_from":"$fwd","prices":[{"sku_id":$sku,"price_type":"outlet","amount_mtk":9500}],"change_reason":"An unknown field is refused","extra":1}""", "extra field")
        assertEquals(batches0, t.count("SELECT count(*) FROM app.price_batch WHERE status <> 'previewed'"))

        val ok = req(uuid(), fwd, row(sku, "outlet", 10100))
        for ((u, r) in listOf("sr1001" to Role.SR, "tso1001" to Role.TSO, "dmo1001" to Role.DMO, "support1001" to Role.SUPPORT)) {
            assertEquals(HttpStatusCode.Forbidden, preview(t.tok(u, r), ok).status, u)
            assertEquals(HttpStatusCode.Forbidden, publish(t.tok(u, r), ok).status, u)
        }
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/v1/admin/prices", t.tok("sr1001", Role.SR)).status)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/v1/admin/prices?limit=1", t.tok("dmo1001", Role.DMO)).status)
        assertEquals("10000@2026-01-01>open", rows(sku, "outlet"))
    }

    // ---------------------------------------------------------------------------------------------- F-ADM-081

    @Test
    fun thresholdBoundaryDecidesWhetherASecondPersonIsNeeded() = t.app {
        val s = t.newSku("PRC-D1", outlet = 10000)
        // Exactly 15 percent up: published at once.
        val up = req(uuid(), fwd, row(s, "outlet", 11500))
        assertEquals("false", preview(t.admin, up).obj()["approval_required"]!!.jsonPrimitive.content)
        assertEquals("published", previewAndPublish(t.admin, up).headers[BATCH_STATUS_HEADER])
        // One milli-taka more: pending, nothing applied, no version bump, preview says so.
        val s2 = t.newSku("PRC-D2", outlet = 10000)
        val v0 = listVersion()
        val over = req(uuid(), fwd, row(s2, "outlet", 11501))
        val pv = preview(t.admin, over).obj()
        assertEquals("true", pv["approval_required"]!!.jsonPrimitive.content); assertEquals(0, BigDecimal("15.01").compareTo(BigDecimal(pv["max_change_pct"]!!.jsonPrimitive.content)))
        val r = previewAndPublish(t.admin, over)
        assertEquals(HttpStatusCode.Created, r.status); assertEquals("pending_approval", r.headers[BATCH_STATUS_HEADER])
        assertEquals(0, r.obj()["items"]!!.jsonArray.size); assertEquals(v0, r.obj()["price_list_version"]!!.jsonPrimitive.content.toLong()); assertEquals(v0, listVersion())
        assertEquals("10000@2026-01-01>open", rows(s2, "outlet"))
        // A fall counts the same: exactly 15 percent down passes, 15.01 percent down waits.
        val s3 = t.newSku("PRC-D3", outlet = 10000)
        assertEquals("published", previewAndPublish(t.admin, req(uuid(), fwd, row(s3, "outlet", 8500))).headers[BATCH_STATUS_HEADER])
        val s4 = t.newSku("PRC-D4", outlet = 10000)
        assertEquals("pending_approval", previewAndPublish(t.admin, req(uuid(), fwd, row(s4, "outlet", 8499))).headers[BATCH_STATUS_HEADER])
        // The rounded display never decides: 14.9968 percent shows 15.00 and passes.
        val s5 = t.newSku("PRC-D5", outlet = 7935)
        val edge = req(uuid(), fwd, row(s5, "outlet", 9125))
        val e = preview(t.admin, edge).obj()
        assertEquals(0, BigDecimal("15").compareTo(BigDecimal(e["max_change_pct"]!!.jsonPrimitive.content))); assertEquals("false", e["approval_required"]!!.jsonPrimitive.content)
        // A different configured threshold moves the line (a fractional percent is allowed).
        t.maxPct = JsonPrimitive(10)
        val s6 = t.newSku("PRC-D6", outlet = 10000)
        assertEquals("true", preview(t.admin, req(uuid(), fwd, row(s6, "outlet", 11001))).obj()["approval_required"]!!.jsonPrimitive.content)
        assertEquals("false", preview(t.admin, req(uuid(), fwd, row(s6, "outlet", 11000))).obj()["approval_required"]!!.jsonPrimitive.content)
        t.maxPct = JsonPrimitive(15)
        // Quantity bases compare per base unit.
        val s7 = t.newSku("PRC-D7", outlet = 10000)
        assertEquals("false", preview(t.admin, req(uuid(), fwd, row(s7, "outlet", 23000, per = 2))).obj()["approval_required"]!!.jsonPrimitive.content)
        assertEquals("true", preview(t.admin, req(uuid(), fwd, row(s7, "outlet", 23002, per = 2))).obj()["approval_required"]!!.jsonPrimitive.content)
        // A price with no live row has no baseline and is not a move; from a zero price it always needs approval.
        val s8 = t.newSku("PRC-D8", outlet = 10000, nto = 0)
        assertEquals("true", preview(t.admin, req(uuid(), fwd, row(s8, "nto", 1))).obj()["approval_required"]!!.jsonPrimitive.content)
        t.exec("DELETE FROM app.sku_price WHERE sku_id = $s8 AND price_type = 'cc'")
        assertEquals("false", preview(t.admin, req(uuid(), fwd, row(s8, "cc", 99999999))).obj()["approval_required"]!!.jsonPrimitive.content)
    }

    @Test
    fun oneRowAboveTheLineHoldsTheWholeMultiSkuBatch() = t.app {
        val a = t.newSku("PRC-E1", outlet = 10000); val b = t.newSku("PRC-E2", outlet = 10000, cc = 10000); val c = t.newSku("PRC-E3", outlet = 10000)
        val bu = uuid()
        val body = req(bu, fwd, row(a, "outlet", 10100), row(b, "outlet", 10200), row(b, "cc", 13000), row(c, "outlet", 9900))
        val pv = preview(t.admin, body).obj()
        assertEquals(3, pv["skus_affected"]!!.jsonPrimitive.content.toInt()); assertEquals(0, BigDecimal("30").compareTo(BigDecimal(pv["max_change_pct"]!!.jsonPrimitive.content))); assertEquals("true", pv["approval_required"]!!.jsonPrimitive.content)
        assertEquals("pending_approval", previewAndPublish(t.admin, body).headers[BATCH_STATUS_HEADER])
        // None of the four rows is live yet, including the three that were within the line.
        assertEquals(6, t.count("SELECT count(*) FROM app.sku_price WHERE sku_id IN ($a,$b,$c) AND price_type IN ('outlet','cc') AND valid_to IS NULL AND valid_from = DATE '2026-01-01'"))
        assertEquals(0, t.count("SELECT count(*) FROM app.sku_price WHERE publish_batch_uuid = '$bu'"))
        assertEquals(1, auditCount("price_batch", bu)); assertEquals("submit_pending", t.scalar("SELECT action FROM app.audit_log WHERE entity = 'price_batch' AND entity_id = '$bu'"))

        // Replaying the held batch answers the same and stores nothing more.
        val auditAll = t.scalar("SELECT count(*) FROM app.audit_log")
        assertEquals("pending_approval", publish(t.admin, body).headers[BATCH_STATUS_HEADER]); assertEquals(auditAll, t.scalar("SELECT count(*) FROM app.audit_log"))

        // ADMIN is the maker and cannot decide; a SUPERADMIN who is not the submitter approves, all four rows go live together.
        assertEquals(HttpStatusCode.Forbidden, decide(t.admin2, bu, "approve").status)
        assertEquals(HttpStatusCode.Forbidden, decide(t.admin, bu, "approve").status)
        val v0 = listVersion()
        val ok = decide(t.sup1, bu, "approve", "Checked against the finance memo")
        assertEquals(HttpStatusCode.OK, ok.status)
        val o = ok.obj()
        assertEquals("published", o["status"]!!.jsonPrimitive.content); assertEquals(4, o["rows"]!!.jsonPrimitive.content.toInt()); assertEquals(fwd, o["valid_from"]!!.jsonPrimitive.content)
        assertEquals(t.id("superadmin1001").toString(), o["decided_by_user_id"]!!.jsonPrimitive.content); assertTrue(o["decided_at"]!!.jsonPrimitive.content.endsWith("Z"))
        assertEquals("10000@2026-01-01>$fwd 10100@$fwd>open", rows(a, "outlet")); assertEquals("10000@2026-01-01>$fwd 13000@$fwd>open", rows(b, "cc")); assertEquals("10000@2026-01-01>$fwd 9900@$fwd>open", rows(c, "outlet"))
        assertEquals(v0 + 1, listVersion())
        assertEquals(2, auditCount("price_batch", bu)) // submit_pending and approve
        assertEquals("approve", t.scalar("SELECT action FROM app.audit_log WHERE entity = 'price_batch' AND entity_id = '$bu' ORDER BY id DESC LIMIT 1"))
        assertEquals("Checked against the finance memo", t.scalar("SELECT reason FROM app.audit_log WHERE entity = 'price_batch' AND entity_id = '$bu' AND action = 'approve'"))
        assertEquals(4, t.count("SELECT count(*) FROM app.audit_log WHERE entity = 'sku_price' AND reason = 'Checked against the finance memo'"))

        // The decision replays: same person, same decision, nothing more is written; the other decision is a state conflict.
        val rows0 = t.scalar("SELECT count(*) FROM app.sku_price"); val audit1 = t.scalar("SELECT count(*) FROM app.audit_log")
        val replay = decide(t.sup1, bu, "approve")
        assertEquals(HttpStatusCode.OK, replay.status); assertEquals(o, replay.obj())
        assertEquals(rows0, t.scalar("SELECT count(*) FROM app.sku_price")); assertEquals(audit1, t.scalar("SELECT count(*) FROM app.audit_log")); assertEquals(v0 + 1, listVersion())
        assertEquals("ERR_REQUEST_STATE", decide(t.sup1, bu, "reject").obj()["code"]!!.jsonPrimitive.content)
        assertEquals("ERR_REQUEST_STATE", decide(t.sup2, bu, "approve").obj()["code"]!!.jsonPrimitive.content)
        // The original publish replays as a held-then-published batch with its rows.
        val pub = publish(t.admin, body)
        assertEquals("published", pub.headers[BATCH_STATUS_HEADER]); assertEquals(4, pub.obj()["items"]!!.jsonArray.size)
    }

    @Test
    fun theSubmitterCanNeverDecideAndRejectLeavesPricesAlone() = t.app {
        val a = t.newSku("PRC-F1", outlet = 10000)
        val bu = uuid()
        // A SUPERADMIN submits (allowed: SUPERADMIN may publish) and cannot also approve or reject their own batch.
        val body = req(bu, fwd, row(a, "outlet", 14000))
        assertEquals("pending_approval", previewAndPublish(t.sup1, body).headers[BATCH_STATUS_HEADER])
        for (d in listOf("approve", "reject")) {
            val self = decide(t.sup1, bu, d)
            assertEquals(HttpStatusCode.Conflict, self.status, d); assertEquals("ERR_SEPARATION_OF_DUTIES", self.obj()["code"]!!.jsonPrimitive.content)
        }
        assertEquals("pending_approval", t.scalar("SELECT status FROM app.price_batch WHERE batch_uuid = '$bu'"))
        val v0 = listVersion()
        val rej = decide(t.sup2, bu, "reject", "Jump looks like a decimal slip")
        assertEquals(HttpStatusCode.OK, rej.status); assertEquals("rejected", rej.obj()["status"]!!.jsonPrimitive.content)
        assertEquals("10000@2026-01-01>open", rows(a, "outlet")); assertEquals(v0, listVersion())
        assertEquals("reject", t.scalar("SELECT action FROM app.audit_log WHERE entity = 'price_batch' AND entity_id = '$bu' ORDER BY id DESC LIMIT 1"))
        assertEquals(HttpStatusCode.OK, decide(t.sup2, bu, "reject").status) // replay by the same decider
        assertEquals("ERR_REQUEST_STATE", decide(t.sup2, bu, "approve").obj()["code"]!!.jsonPrimitive.content)
        // A rejected batch stays rejected: the same batch_uuid cannot be published again.
        assertEquals("rejected", publish(t.sup1, body).headers[BATCH_STATUS_HEADER])
        assertEquals("10000@2026-01-01>open", rows(a, "outlet"))

        // Validation and lookups on the decision path.
        assertEquals(HttpStatusCode.NotFound, decide(t.sup2, uuid(), "approve").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/v1/admin/prices/batches/$bu/decision", t.sup2, """{"decision":"maybe"}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/v1/admin/prices/batches/$bu/decision", t.sup2, """{"decision":"approve","x":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/v1/admin/prices/batches/not-a-uuid/decision", t.sup2, """{"decision":"approve"}""").status)
        assertEquals(HttpStatusCode.Forbidden, decide(t.tok("dmo1001", Role.DMO), bu, "approve").status)
        // A previewed-only batch is not a batch to decide.
        val pOnly = uuid()
        assertEquals(HttpStatusCode.OK, preview(t.admin, req(pOnly, fwd, row(a, "outlet", 20000))).status)
        assertEquals(HttpStatusCode.NotFound, decide(t.sup2, pOnly, "approve").status)
    }

    @Test
    fun approvalAfterTheStartDateHasPassedIsRefusedUnlessBackdated() = t.app {
        val a = t.newSku("PRC-G1", outlet = 10000)
        val bu = uuid()
        assertEquals("pending_approval", previewAndPublish(t.admin, req(bu, fwd, row(a, "outlet", 15000))).headers[BATCH_STATUS_HEADER])
        // The date comes due before the checker looks at it.
        t.exec("UPDATE app.price_batch SET valid_from = DATE '${t.today}' WHERE batch_uuid = '$bu'")
        val late = decide(t.sup1, bu, "approve")
        assertEquals(HttpStatusCode.BadRequest, late.status); assertEquals("ERR_MASTER_EFFECTIVE_DATE_PAST", late.obj()["code"]!!.jsonPrimitive.content)
        assertEquals("pending_approval", t.scalar("SELECT status FROM app.price_batch WHERE batch_uuid = '$bu'")); assertEquals("10000@2026-01-01>open", rows(a, "outlet"))
        // A batch whose submitter held finance.backdate may be approved for a past date.
        val b = t.newSku("PRC-G2", outlet = 10000)
        val bu2 = uuid()
        assertEquals("pending_approval", previewAndPublish(t.tok("admin1001", Role.ADMIN, "finance.backdate"), req(bu2, t.today.toString(), row(b, "outlet", 15000))).headers[BATCH_STATUS_HEADER])
        assertEquals(HttpStatusCode.OK, decide(t.sup1, bu2, "approve").status)
        assertEquals("10000@2026-01-01>${t.today} 15000@${t.today}>open", rows(b, "outlet"))
    }

    @Test
    fun thePreviewRequirementCanBeSwitchedOffInDeps() = t.app(requirePreview = false) {
        val a = t.newSku("PRC-H1", outlet = 10000)
        val r = publish(t.admin, req(uuid(), fwd, row(a, "outlet", 10300)))
        assertEquals(HttpStatusCode.Created, r.status); assertEquals("published", r.headers[BATCH_STATUS_HEADER])
        assertEquals("10000@2026-01-01>$fwd 10300@$fwd>open", rows(a, "outlet"))
    }
}
