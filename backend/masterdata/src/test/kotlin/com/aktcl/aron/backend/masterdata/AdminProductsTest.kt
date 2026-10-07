package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-035c: product tree and SKU CRUD under /v1/admin: audited writes, version rules, replays change nothing, roles and validation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminProductsTest {
    private lateinit var t: AdminHarness

    @BeforeAll fun setUp() { t = AdminHarness() }
    @AfterAll fun tearDown() = t.close()

    private fun audits(entity: String, id: String? = null) = t.count("SELECT count(*) FROM app.audit_log WHERE entity = '$entity'" + (id?.let { " AND entity_id = '$it'" } ?: ""))
    private fun node(level: String, name: String, parent: Long?, sort: Int = 1, code: String? = null) =
        """{"name":"$name","sort":$sort${if (parent != null) ""","parent_id":$parent""" else ""}${if (code != null) ""","code":"$code"""" else ""}}"""

    @Test
    fun treeNodeCreatePatchVersionAuditAndReplays() = t.app {
        val cat = t.scalar("SELECT id FROM app.product_node WHERE level = 'category' AND name = 'Cigarette'").toLong()
        val before = audits("product_node")
        val r = send(HttpMethod.Post, "/v1/admin/product-nodes/segment", t.admin, node("segment", "Premium Test", cat, 7, "SEG-T1"))
        assertEquals(HttpStatusCode.Created, r.status)
        val seg = r.obj()
        val id = seg["id"]!!.jsonPrimitive.content.toLong()
        assertEquals("segment", seg["level"]!!.jsonPrimitive.content); assertEquals("1", seg["version"]!!.jsonPrimitive.content)
        assertEquals("\"1\"", r.headers["ETag"])
        assertEquals(before + 1, audits("product_node"))

        // The same create again is a duplicate: nothing is written and no audit row is added.
        val dup = send(HttpMethod.Post, "/v1/admin/product-nodes/segment", t.admin, node("segment", "Premium Test", cat, 7, "SEG-T1"))
        assertEquals(HttpStatusCode.Conflict, dup.status); assertEquals("ERR_MASTER_DUPLICATE_CODE", dup.obj()["code"]!!.jsonPrimitive.content)
        assertEquals(before + 1, audits("product_node"))

        val up = send(HttpMethod.Patch, "/v1/admin/product-nodes/segment/$id", t.sup1, """{"name":"Premium Test 2","sort":9,"name_bn":"প্রিমিয়াম"}""", ifMatch = 1)
        assertEquals(HttpStatusCode.OK, up.status)
        val o = up.obj(); assertEquals("2", o["version"]!!.jsonPrimitive.content); assertEquals("Premium Test 2", o["name"]!!.jsonPrimitive.content); assertEquals("প্রিমিয়াম", o["name_bn"]!!.jsonPrimitive.content)
        assertEquals(2, audits("product_node", id.toString()))
        // The audit row carries before and after.
        assertEquals("Premium Test", t.scalar("SELECT before->>'name' FROM app.audit_log WHERE entity = 'product_node' AND entity_id = '$id' AND action = 'update'"))

        // A stale If-Match is 412 and changes nothing; the identical patch with the fresh version is a no-op (no new version, no audit row).
        val stale = send(HttpMethod.Patch, "/v1/admin/product-nodes/segment/$id", t.admin, """{"sort":11}""", ifMatch = 1)
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status); assertEquals("ERR_PRECONDITION_FAILED", stale.obj()["code"]!!.jsonPrimitive.content)
        val replay = send(HttpMethod.Patch, "/v1/admin/product-nodes/segment/$id", t.sup1, """{"name":"Premium Test 2","sort":9}""", ifMatch = 2)
        assertEquals(HttpStatusCode.OK, replay.status); assertEquals("2", replay.obj()["version"]!!.jsonPrimitive.content)
        assertEquals(2, audits("product_node", id.toString()))

        // Listing: by level, parent, status, updated_since and paging.
        val page = send(HttpMethod.Get, "/v1/admin/product-nodes/segment?parent_id=$cat&limit=1", t.admin).obj()
        assertEquals(1, page["items"]!!.jsonArray.size); assertTrue(page["next_cursor"]!!.jsonPrimitive.content.isNotEmpty())
        val inactive = send(HttpMethod.Get, "/v1/admin/product-nodes/segment?status=inactive", t.admin).obj()
        assertEquals(0, inactive["items"]!!.jsonArray.size); assertNull(inactive["next_cursor"]!!.jsonPrimitive.contentOrNull)
        val since = send(HttpMethod.Get, "/v1/admin/product-nodes/segment?updated_since=2999-01-01T00:00:00.000Z", t.admin).obj()
        assertEquals(0, since["items"]!!.jsonArray.size)
    }

    @Test
    fun nodeValidationNotFoundAndInUse() = t.app {
        val cat = t.scalar("SELECT id FROM app.product_node WHERE level = 'category' AND name = 'Cigarette'").toLong()
        val brand = t.scalar("SELECT id FROM app.product_node WHERE level = 'brand' ORDER BY id LIMIT 1").toLong()
        fun bad(r: io.ktor.client.statement.HttpResponse) = assertEquals(HttpStatusCode.BadRequest, r.status)
        bad(send(HttpMethod.Post, "/v1/admin/product-nodes/galaxy", t.admin, node("x", "X", null)))
        bad(send(HttpMethod.Post, "/v1/admin/product-nodes/segment", t.admin, """{"parent_id":$cat}"""))
        bad(send(HttpMethod.Post, "/v1/admin/product-nodes/segment", t.admin, """{"name":"X","sort":1,"parent_id":$cat,"bogus":1}"""))
        bad(send(HttpMethod.Post, "/v1/admin/product-nodes/segment", t.admin, """{"name":"X","sort":1.5,"parent_id":$cat}"""))
        bad(send(HttpMethod.Post, "/v1/admin/product-nodes/segment", t.admin, node("segment", "Orphan", null)))
        bad(send(HttpMethod.Post, "/v1/admin/product-nodes/variant", t.admin, node("variant", "Wrong parent level", cat)))
        bad(send(HttpMethod.Post, "/v1/admin/product-nodes/category", t.admin, node("category", "Has parent", cat)))
        bad(send(HttpMethod.Post, "/v1/admin/product-nodes/segment", t.admin, "not json"))
        bad(send(HttpMethod.Patch, "/v1/admin/product-nodes/brand/$brand", t.admin, """{"sort":3}"""))   // If-Match missing
        bad(send(HttpMethod.Patch, "/v1/admin/product-nodes/brand/$brand", t.admin, "{}", ifMatch = 1))
        bad(send(HttpMethod.Patch, "/v1/admin/product-nodes/brand/$brand", t.admin, """{"status":"gone"}""", ifMatch = 1))
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Patch, "/v1/admin/product-nodes/brand/999999", t.admin, """{"sort":3}""", ifMatch = 1).status)
        // A node of another level is not found under this level's path.
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Patch, "/v1/admin/product-nodes/segment/$brand", t.admin, """{"sort":3}""", ifMatch = 1).status)
        // Deactivating a node that still has active children is refused.
        val cur = t.scalar("SELECT version FROM app.product_node WHERE id = $cat").toInt()
        val inUse = send(HttpMethod.Patch, "/v1/admin/product-nodes/category/$cat", t.admin, """{"status":"inactive"}""", ifMatch = cur)
        assertEquals(HttpStatusCode.Conflict, inUse.status); assertEquals("ERR_MASTER_IN_USE", inUse.obj()["code"]!!.jsonPrimitive.content)
        assertEquals("active", t.scalar("SELECT status FROM app.product_node WHERE id = $cat"))
    }

    @Test
    fun rolesReadAndWrite() = t.app {
        val body = node("category", "Role Probe", null)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/v1/admin/skus", t.tok("sr1001", Role.SR)).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/v1/admin/product-nodes/category", t.tok("sr1001", Role.SR)).status)
        for ((u, r) in listOf("dmo1001" to Role.DMO, "tso1001" to Role.TSO, "support1001" to Role.SUPPORT)) {
            assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/v1/admin/skus?limit=1", t.tok(u, r)).status, u)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/v1/admin/product-nodes/category", t.tok(u, r), body).status, u)
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/v1/admin/skus", t.tok(u, r), "{}").status, u)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/skus").status)
        assertEquals(0, t.count("SELECT count(*) FROM app.product_node WHERE name = 'Role Probe'"))
    }

    private fun skuBody(code: String, variant: Long, category: String = "cigarette", unit: String = "stick", perPack: Int = 10, factor: String = "1", extra: String = "") =
        """{"code":"$code","variant_id":$variant,"category_code":"$category","name":"$code product","short_name":"$code","base_unit":"$unit","base_per_pack":$perPack,"entry_unit_default":"pack","report_unit":"pack","report_factor":"$factor","sort":3$extra}"""

    @Test
    fun skuCreateListPatchAuditAndRules() = t.app {
        val variant = t.scalar("SELECT id FROM app.product_node WHERE level = 'variant' ORDER BY id LIMIT 1").toLong()
        val before = audits("sku")
        val r = send(HttpMethod.Post, "/v1/admin/skus", t.admin, skuBody("TST-A1", variant, factor = "0.5"))
        assertEquals(HttpStatusCode.Created, r.status)
        val s = r.obj(); val id = s["id"]!!.jsonPrimitive.content.toLong()
        assertEquals("0.500", s["report_factor"]!!.jsonPrimitive.content); assertEquals("stick", s["base_unit"]!!.jsonPrimitive.content); assertEquals("active", s["status"]!!.jsonPrimitive.content)
        assertEquals(before + 1, audits("sku"))
        val dup = send(HttpMethod.Post, "/v1/admin/skus", t.admin, skuBody("TST-A1", variant, factor = "0.5"))
        assertEquals("ERR_MASTER_DUPLICATE_CODE", dup.obj()["code"]!!.jsonPrimitive.content); assertEquals(before + 1, audits("sku"))

        // Units follow the category; lighter and match have no pack of sticks; the parent must be a variant.
        suspend fun bad(b: String) = send(HttpMethod.Post, "/v1/admin/skus", t.admin, b).also { assertEquals(HttpStatusCode.BadRequest, it.status, b) }
        bad(skuBody("TST-B1", variant, category = "lighter", unit = "stick", perPack = 1))
        bad(skuBody("TST-B2", variant, category = "lighter", unit = "piece", perPack = 5))
        bad(skuBody("TST-B3", variant, category = "match", unit = "piece", perPack = 1))
        bad(skuBody("TST-B4", t.scalar("SELECT id FROM app.product_node WHERE level = 'brand' ORDER BY id LIMIT 1").toLong()))
        bad(skuBody("bad code!", variant))
        bad(skuBody("TST-B5", variant, factor = "1.2345"))
        bad(skuBody("TST-B6", variant, factor = "0"))
        bad(skuBody("TST-B7", variant, perPack = 0))
        bad(skuBody("TST-B8", variant, extra = ""","bogus":true"""))
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/v1/admin/skus", t.admin, skuBody("TST-M1", variant, category = "match", unit = "dozen", perPack = 1)).status)

        val up = send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"name":"Renamed","status":"inactive","report_factor":"2.250","change_reason":"Retired from the range by the brand team"}""", ifMatch = 1)
        assertEquals(HttpStatusCode.OK, up.status)
        val o = up.obj(); assertEquals("2", o["version"]!!.jsonPrimitive.content); assertEquals("inactive", o["status"]!!.jsonPrimitive.content); assertEquals("2.250", o["report_factor"]!!.jsonPrimitive.content)
        assertEquals("Retired from the range by the brand team", t.scalar("SELECT reason FROM app.audit_log WHERE entity = 'sku' AND entity_id = '$id' AND action = 'update'"))
        assertEquals(HttpStatusCode.PreconditionFailed, send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"sort":4}""", ifMatch = 1).status)
        // Replaying the same patch at the new version changes nothing.
        val replay = send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"name":"Renamed","status":"inactive"}""", ifMatch = 2)
        assertEquals("2", replay.obj()["version"]!!.jsonPrimitive.content)
        assertEquals(2, audits("sku", id.toString()))
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"code":"NEW"}""", ifMatch = 2).status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"change_reason":"short"}""", ifMatch = 2).status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Patch, "/v1/admin/skus/99999999", t.admin, """{"sort":4}""", ifMatch = 1).status)

        val found = send(HttpMethod.Get, "/v1/admin/skus?q=TST-A1&status=inactive", t.admin).obj()["items"]!!.jsonArray
        assertEquals(listOf("TST-A1"), found.map { it.asObj()["code"]!!.jsonPrimitive.content })
        val p1 = send(HttpMethod.Get, "/v1/admin/skus?limit=2", t.admin).obj()
        assertEquals(2, p1["items"]!!.jsonArray.size)
        val p2 = send(HttpMethod.Get, "/v1/admin/skus?limit=2&cursor=${p1["next_cursor"]!!.jsonPrimitive.content}", t.admin).obj()
        assertTrue(p2["items"]!!.jsonArray.first().asObj()["id"]!!.jsonPrimitive.content.toLong() > p1["items"]!!.jsonArray.last().asObj()["id"]!!.jsonPrimitive.content.toLong())
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Get, "/v1/admin/skus?limit=501", t.admin).status)
    }
}

private fun kotlinx.serialization.json.JsonElement.asObj() = this as kotlinx.serialization.json.JsonObject
