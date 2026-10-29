package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals

/** D-HOUSE: houses de-duplicated by territory and normalised name, merged with a reason, zones move, the merged house goes inactive. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HouseDedupTest {
    private lateinit var env: AdminEnv

    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private val admin get() = env.token("admin1001", Role.ADMIN)
    private fun id(table: String, code: String) = env.sql("SELECT id FROM app.$table WHERE code = '$code'")!!.toLong()

    private fun seedHouse(code: String, name: String, territory: String): Long {
        env.sql("INSERT INTO app.house (code, name, territory_id) VALUES ('$code', '$name', ${id("territory", territory)}) RETURNING id")
        return id("house", code)
    }

    @Test
    fun duplicatesAreFoundMergedAndTheZonesMove() = env.app {
        val terr = "T-DHK-N"
        val a = seedHouse("H-D1", "Gulshan  House", terr); val b = seedHouse("H-D2", "gulshan house", terr); val c = seedHouse("H-D3", "Gulshan-House.", terr)
        val other = seedHouse("H-D4", "Gulshan House", "T-OTHER")   // same name, other territory: not a duplicate
        val unrelated = seedHouse("H-D5", "Banani House", terr)
        env.sql("UPDATE app.zone SET house_id = $b WHERE code = 'Z-MIR' RETURNING id")
        val dup = sendA1(HttpMethod.Get, "/admin/geo/house/duplicates", admin)
        assertEquals(HttpStatusCode.OK, dup.status)
        val g = dup.objA1().getValue("groups").jsonArray.map { it.jsonObject }.single { it.getValue("houses").jsonArray.any { h -> h.jsonObject.strA1("code") == "H-D1" } }
        assertEquals(listOf("H-D1", "H-D2", "H-D3"), g.getValue("houses").jsonArray.map { it.jsonObject.strA1("code") })
        assertEquals(1, g.getValue("houses").jsonArray[1].jsonObject.getValue("zone_count").jsonPrimitive.content.toInt())
        // Merge needs the kept house's version, a reason, and true duplicates.
        val v = env.sql("SELECT version FROM app.house WHERE id = $a")!!.toInt()
        val reason = "De-duplicate Gulshan house entries"
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/geo/house/merge", admin, """{"keep_id":$a,"merge_ids":[$unrelated],"change_reason":"$reason"}""", ifMatch = v).status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/geo/house/merge", admin, """{"keep_id":$a,"merge_ids":[$other],"change_reason":"$reason"}""", ifMatch = v).status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/geo/house/merge", admin, """{"keep_id":$a,"merge_ids":[$a],"change_reason":"$reason"}""", ifMatch = v).status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/geo/house/merge", admin, """{"keep_id":$a,"merge_ids":[$b],"change_reason":"short"}""", ifMatch = v).status)
        assertEquals(HttpStatusCode.PreconditionFailed, sendA1(HttpMethod.Post, "/admin/geo/house/merge", admin, """{"keep_id":$a,"merge_ids":[$b],"change_reason":"$reason"}""", ifMatch = v + 5).status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/geo/house/merge", env.token("tso1001", Role.TSO), """{"keep_id":$a,"merge_ids":[$b],"change_reason":"$reason"}""", ifMatch = v).status)
        assertEquals("active", env.sql("SELECT status FROM app.house WHERE id = $b"), "refusals changed nothing")
        val zMir = id("zone", "Z-MIR")
        assertEquals(b, env.geo.geo().zoneHouse[zMir], "the snapshot is warm and holds the old house")
        val seenAtInvalidate = java.util.concurrent.CopyOnWriteArrayList<String?>()   // every call counts: one before the commit would show the old house
        // Read on ANOTHER THREAD: Jdbi reuses the calling thread's open handle, so a read on the request thread would join the merge transaction and see its uncommitted rows.
        env.geo.onInvalidate = { seenAtInvalidate += java.util.concurrent.CompletableFuture.supplyAsync { env.sql("SELECT house_id FROM app.zone WHERE code = 'Z-MIR'") }.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        val r = sendA1(HttpMethod.Post, "/admin/geo/house/merge", admin, """{"keep_id":$a,"merge_ids":[$b,$c],"change_reason":"$reason"}""", ifMatch = v)
        assertEquals(HttpStatusCode.OK, r.status, r.toString())
        assertEquals("inactive", env.sql("SELECT status FROM app.house WHERE id = $b")); assertEquals("inactive", env.sql("SELECT status FROM app.house WHERE id = $c"))
        assertEquals("active", env.sql("SELECT status FROM app.house WHERE id = $a"))
        assertEquals(a.toString(), env.sql("SELECT house_id FROM app.zone WHERE code = 'Z-MIR'"))
        assertEquals(listOf<String?>(a.toString()), seenAtInvalidate.toList(), "the snapshot is invalidated AFTER the merge committed (an invalidation before the commit lets a reload read the old rows and cache them)")
        assertEquals(a, env.geo.geo().zoneHouse[zMir], "the merge invalidated the snapshot: a house-scope reach follows at once, not after the TTL")
        assertEquals(1, env.audit("geo_house", b, "merge")); assertEquals(1, env.audit("geo_house", a, "merge_keep"))
        // A merged (inactive) house is not a duplicate candidate any more only when the group shrinks to one active house; the groups list stays name-based.
        val after = sendA1(HttpMethod.Get, "/admin/geo/house/duplicates", admin).objA1().getValue("groups").jsonArray.map { it.jsonObject }
        assertEquals(1, after.count { it.getValue("houses").jsonArray.any { h -> h.jsonObject.strA1("code") == "H-D1" } })
        // is_active on the house node.
        val node = sendA1(HttpMethod.Get, "/admin/geo/house/$b", admin).objA1()
        assertEquals("false", node.getValue("is_active").jsonPrimitive.content)
        assertEquals("true", sendA1(HttpMethod.Get, "/admin/geo/house/$a", admin).objA1().getValue("is_active").jsonPrimitive.content)
    }
}
