package com.aktcl.aron.backend.masterdata

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Independent T1 checker for F-API-080 / F-ADM-081: edges the lane's own tests do not cover. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminPricesRefuteTest {
    private lateinit var t: AdminHarness

    @BeforeAll fun setUp() { t = AdminHarness() }
    @AfterAll fun tearDown() = t.close()

    private val fwd get() = t.today.plusDays(3).toString()
    private fun uuid() = UUID.randomUUID().toString()
    private fun row(sku: Long, type: String, amount: Long, per: Int = 1) = """{"sku_id":$sku,"price_type":"$type","amount_mtk":$amount,"per_base_qty":$per}"""
    private fun req(batch: String, from: String, vararg rows: String, reason: String = "Quarterly price revision approved by finance") =
        """{"batch_uuid":"$batch","valid_from":"$from","prices":[${rows.joinToString(",")}],"change_reason":"$reason"}"""

    /** A contract-valid amount (int64 >= 0) far above the old price: the stored max_change_pct numeric(12,2) overflows and the preview answers 500. */
    @Test
    fun previewOfAHugeButValidPriceIsNot500() = t.app {
        val sku = t.newSku("RF-HUGE1", outlet = 10000)
        val body = req(uuid(), fwd, row(sku, "outlet", 10_000_000_000_000L)) // 10^10 Tk, percent 10^11
        val r = send(HttpMethod.Post, "/v1/admin/prices/preview", t.admin, body)
        assertNotEquals(HttpStatusCode.InternalServerError, r.status, "preview of a huge move must be a 200 (approval_required) or a 400, not 500")
    }

    @Test
    fun publishOfAHugeButValidPriceIsNot500() = t.app(requirePreview = false) {
        val sku = t.newSku("RF-HUGE2", outlet = 10000)
        val r = send(HttpMethod.Post, "/v1/admin/prices", t.admin, req(uuid(), fwd, row(sku, "outlet", Long.MAX_VALUE)))
        assertNotEquals(HttpStatusCode.InternalServerError, r.status)
    }

    /** ChangeReason minLength 10 is checked before the trim; the stored and audited reason is then 1 character. */
    @Test
    fun paddedChangeReasonIsRefused() = t.app {
        val sku = t.newSku("RF-PAD1", outlet = 10000)
        val r = send(HttpMethod.Post, "/v1/admin/prices/preview", t.admin, req(uuid(), fwd, row(sku, "outlet", 10100), reason = "x            "))
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    /**
     * ConfigService.commit serialises on pg_advisory_xact_lock(7242001) and takes max(config_version)+1; the price publish takes
     * 7210001 and the same max+1, so a publish racing a config commit collides on cfg_version's primary key.
     */
    @Test
    fun listVersionBumpWaitsForAConcurrentConfigCommit() = t.app {
        val sku = t.newSku("RF-RACE1", outlet = 10000)
        val body = req(uuid(), fwd, row(sku, "outlet", 10100))
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/v1/admin/prices/preview", t.admin, body).status)
        val inserted = CountDownLatch(1)
        val by = t.id("superadmin1001")
        val other = Thread {
            t.env.fresh.dataSource.connection.use { c ->
                c.autoCommit = false
                c.createStatement().use { st ->
                    st.execute("SELECT pg_advisory_xact_lock(7242001)")
                    st.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary, max_risk_class) SELECT COALESCE(max(config_version),0)+1, 'change', $by, 'concurrent config commit', 0 FROM app.cfg_version")
                }
                inserted.countDown()
                Thread.sleep(1500)
                c.commit()
            }
        }
        other.start()
        assertTrue(inserted.await(10, TimeUnit.SECONDS))
        val r = send(HttpMethod.Post, "/v1/admin/prices", t.admin, body)
        other.join()
        assertEquals(HttpStatusCode.Created, r.status, "publish must wait on the config commit lock, got ${r.status}: ${r.obj()["code"]}")
        assertEquals("published", r.headers[BATCH_STATUS_HEADER])
    }

    /** Two identical publishes at once (preview switched off): the second must be a replay, not an error. */
    @Test
    fun concurrentIdenticalPublishesWithoutPreviewRecordReplay() = t.app(requirePreview = false) {
        val sku = t.newSku("RF-CONC1", outlet = 10000)
        val body = req(uuid(), fwd, row(sku, "outlet", 10100))
        val (a, b) = coroutineScope {
            val x = async { send(HttpMethod.Post, "/v1/admin/prices", t.admin, body) }
            val y = async { send(HttpMethod.Post, "/v1/admin/prices", t.admin, body) }
            x.await() to y.await()
        }
        assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Created), listOf(a.status, b.status), "codes: ${listOf(a, b).map { it.obj()["code"] }}")
        assertEquals(1, t.count("SELECT count(*) FROM app.sku_price WHERE sku_id = $sku AND price_type = 'outlet' AND valid_from > DATE '2026-01-01'"))
    }

    /** Concurrent identical publishes after a preview (the default flow) serialise on the batch row and the second replays. */
    @Test
    fun concurrentIdenticalPublishesAfterPreviewReplay() = t.app {
        val sku = t.newSku("RF-CONC2", outlet = 10000)
        val body = req(uuid(), fwd, row(sku, "outlet", 10100))
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/v1/admin/prices/preview", t.admin, body).status)
        val v0 = t.count("SELECT count(*) FROM app.cfg_version")
        val (a, b) = coroutineScope {
            val x = async { send(HttpMethod.Post, "/v1/admin/prices", t.admin, body) }
            val y = async { send(HttpMethod.Post, "/v1/admin/prices", t.admin2, body) }
            x.await() to y.await()
        }
        assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Created), listOf(a.status, b.status))
        assertEquals(v0 + 1, t.count("SELECT count(*) FROM app.cfg_version"))
        assertEquals(1, t.count("SELECT count(*) FROM app.sku_price WHERE sku_id = $sku AND price_type = 'outlet' AND valid_from > DATE '2026-01-01'"))
    }

    /** Two different batches for the same SKU/type/date at once: exactly one wins, the other is a clean 409, never a 500. */
    @Test
    fun concurrentDifferentBatchesSameSkuDate() = t.app {
        val sku = t.newSku("RF-CONC3", outlet = 10000)
        val b1 = req(uuid(), fwd, row(sku, "outlet", 10100)); val b2 = req(uuid(), fwd, row(sku, "outlet", 10200))
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/v1/admin/prices/preview", t.admin, b1).status)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/v1/admin/prices/preview", t.admin, b2).status)
        val (a, b) = coroutineScope {
            val x = async { send(HttpMethod.Post, "/v1/admin/prices", t.admin, b1) }
            val y = async { send(HttpMethod.Post, "/v1/admin/prices", t.admin2, b2) }
            x.await() to y.await()
        }
        assertEquals(setOf(HttpStatusCode.Created, HttpStatusCode.Conflict), setOf(a.status, b.status))
        assertEquals(1, t.count("SELECT count(*) FROM app.sku_price WHERE sku_id = $sku AND price_type = 'outlet' AND valid_from > DATE '2026-01-01'"))
    }

    /** Preview rail from a zero price; a negative threshold config; the preview response keeps max_change_pct a JSON number. */
    @Test
    fun displayAndNumberShape() = t.app {
        val sku = t.newSku("RF-SHAPE", outlet = 10000)
        val p = send(HttpMethod.Post, "/v1/admin/prices/preview", t.admin, req(uuid(), fwd, row(sku, "outlet", 10333))).obj()
        assertTrue(!p["max_change_pct"]!!.jsonPrimitive.isString)
        assertEquals("3.33", p["max_change_pct"]!!.jsonPrimitive.content)
    }
}
