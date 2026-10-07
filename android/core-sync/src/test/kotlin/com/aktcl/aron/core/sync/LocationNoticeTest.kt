package com.aktcl.aron.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.session.TrustedClockSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-075: the notice is stored and uploaded once per user and version, and gates the sale when the setting requires it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LocationNoticeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val elapsed = 10_000_000L
    private val wall = 1_791_165_600_000L // 2026-10-05T02:00Z
    private val syncs = mutableListOf<Long>()
    private var offline = true
    private val scheduler = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) { syncs += userId } }

    @org.junit.After fun tearDown() = db.close()

    private fun notice(): LocationNotice {
        val clock = TrustedClockSource(tmp.newFile(), { 41 }, { elapsed }, { wall })
        return LocationNotice({ db }, clock, scheduler) { offline }
    }

    private fun bundle(configValues: String, consents: String? = null) = runBlocking {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        var raw = JsonObject(base + ("config" to Json.parseToJsonElement("""{"config_version":318,"values":[$configValues],"scheduled":[]}""")))
        if (consents != null) raw = JsonObject(raw + ("user" to Json.parseToJsonElement("""{"user_id":7,"consents":$consents}""")))
        ReferenceRepository(db).apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw)
    }

    private fun consentRows() = runBlocking { db.outboxDao().nextPending(1000) }.filter { it.recordType == "consent_accept" }

    @Test
    fun withoutTheKeyTheNoticeIsRequiredAndNoSaleStartsBeforeAcceptance() = runBlocking {
        bundle("")
        val n = notice()
        assertEquals(NoticeState(needed = true, required = true), n.state(7))
        assertFalse(n.allowsSale(7))
    }

    /** BC-55: after a wipe or reinstall the bundle carries the server's acceptance: no notice, no second record. */
    @Test
    fun anAcceptanceTheServerHoldsIsNotAskedAgainButANewVersionIs() = runBlocking {
        bundle("", """[{"policy_key":"location_notice","policy_version":${LocationNotice.POLICY_VERSION},"accepted_at":"2026-10-01T03:00:00.000Z"}]""")
        assertEquals(NoticeState(needed = false, required = true), notice().state(7))
        assertTrue(notice().allowsSale(7))
        assertTrue(consentRows().isEmpty())
        bundle("", """[{"policy_key":"location_notice","policy_version":${LocationNotice.POLICY_VERSION - 1},"accepted_at":"2026-01-01T03:00:00.000Z"},
            {"policy_key":"other_policy","policy_version":${LocationNotice.POLICY_VERSION},"accepted_at":"2026-10-01T03:00:00.000Z"}]""")
        assertEquals(NoticeState(needed = true, required = true), notice().state(7))
        bundle("", "[]")
        assertTrue(notice().state(7).needed)
    }

    @Test
    fun acceptanceIsStoredAndQueuedOnceAndOpensTheSale() = runBlocking {
        bundle("")
        val n = notice()
        assertTrue(n.accept(7, "bn", shownAtMs = wall - 30_000))
        assertFalse("a second tap queues nothing", n.accept(7, "bn", shownAtMs = wall))
        assertFalse("a relaunch still knows", notice().accept(7, "en", shownAtMs = wall))
        val rows = consentRows()
        assertEquals(1, rows.size)
        assertEquals(listOf(7L), syncs)
        val record = Json.parseToJsonElement(rows.single().payloadJson).jsonObject
        val payload = record["payload"]!!.jsonObject
        assertEquals(setOf("policy_key", "policy_version", "accepted", "locale", "shown_at"), payload.keys) // additionalProperties: false
        assertEquals("location_notice", payload["policy_key"]!!.jsonPrimitive.content)
        assertEquals(LocationNotice.POLICY_VERSION.toString(), payload["policy_version"]!!.jsonPrimitive.content)
        assertEquals("true", payload["accepted"]!!.jsonPrimitive.content)
        assertEquals("bn", payload["locale"]!!.jsonPrimitive.content)
        assertEquals("2026-10-05T01:59:30.000Z", payload["shown_at"]!!.jsonPrimitive.content)
        assertEquals("true", record["captured_offline"]!!.jsonPrimitive.content)
        assertEquals(rows.single().clientUuid, record["family_uuid"]!!.jsonPrimitive.content)
        assertFalse(record.containsKey("route_id"))
        assertEquals(NoticeState(needed = false, required = true), n.state(7))
        assertTrue(n.allowsSale(7))
    }

    @Test
    fun whenTheSettingIsOffTheNoticeStillShowsButTheSaleIsNotHeld() = runBlocking {
        bundle("""{"key":"cfg.app.location_notice_required","value":false,"scope_type":"global","requires_ack":false}""")
        val n = notice()
        assertEquals(NoticeState(needed = true, required = false), n.state(7))
        assertTrue(n.allowsSale(7))
        assertTrue(consentRows().isEmpty())
    }

    @Test
    fun aShownAtAfterTheCaptureIsClampedAndABadLocaleWritesNothing() = runBlocking {
        bundle("")
        val n = notice()
        val bad = runCatching { n.accept(7, "fr", shownAtMs = wall) }
        assertTrue(bad.isFailure)
        assertTrue(consentRows().isEmpty())
        assertEquals(NoticeState(needed = true, required = true), n.state(7))
        n.accept(7, "en", shownAtMs = wall + 60_000)
        val payload = Json.parseToJsonElement(consentRows().single().payloadJson).jsonObject["payload"]!!.jsonObject
        assertEquals("2026-10-05T02:00:00.000Z", payload["shown_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun aNewBundleKeepsTheAcceptanceAndCapturedOfflineIsLiveConnectivity() = runBlocking {
        bundle("")
        offline = false
        notice().accept(7, "bn", shownAtMs = wall)
        bundle("") // the next day's full bundle
        assertEquals(NoticeState(needed = false, required = true), notice().state(7))
        val record = Json.parseToJsonElement(consentRows().single().payloadJson).jsonObject
        assertEquals("false", record["captured_offline"]!!.jsonPrimitive.content)
    }
}
