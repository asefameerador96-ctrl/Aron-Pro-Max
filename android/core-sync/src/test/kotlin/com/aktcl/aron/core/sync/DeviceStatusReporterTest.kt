package com.aktcl.aron.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.DeviceInfo
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.geo.integrity.IntegrityResult
import com.aktcl.aron.core.geo.integrity.IntegritySignals
import com.aktcl.aron.core.geo.integrity.IntegritySignalsTracker
import com.aktcl.aron.core.geo.integrity.IntegrityUnavailable
import com.aktcl.aron.core.session.TrustedClockSource
import com.aktcl.aron.core.sync.device.DayConfig
import com.aktcl.aron.core.sync.device.DeviceFactsSnapshot
import com.aktcl.aron.core.sync.device.DeviceStatusReporter
import com.aktcl.aron.core.sync.device.IntegrityState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-031 / N-026 wiring: device_status records from the sync worker (docs/24 s10.3, s8.7; R12, R13, R18). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DeviceStatusReporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private var elapsed = 10_000_000L
    private val wall = 1_791_165_600_000L // 2026-10-05T02:00Z
    private val clock by lazy { TrustedClockSource(tmp.newFile("anchors"), { 41 }, { elapsed }, { wall + (elapsed - 10_000_000L) }) }
    private val state = IntegrityState.Memory()
    private var signals = IntegritySignals(false, false, true, true, emptyList(), emptyList())
    private var signalsFail = false
    private var evidenceCalls = 0
    private var evidence: IntegrityResult = IntegrityResult.Evidence("tok.en", "n0nce")
    private val facts = DeviceFactsSnapshot(
        deviceInfo = DeviceInfo("samsung", "SM-A065F", 34, "14", null, "arm64-v8a", 4096),
        deviceOwner = true, lockdownLevelApplied = "prod", policyVersionApplied = null, policyApplyErrors = listOf("restriction:no_safe_boot"),
        restrictionsApplied = mapOf("no_safe_boot" to false), batteryOptimisationIgnored = true, blockingActive = false, blockingSinceMs = null,
        suspendedPackages = null, locationEnabled = true, locationMode = "unknown", timeZone = "Asia/Dhaka", playServicesVersion = null,
        batteryPct = 81, charging = false, freeStorageMb = 900,
    )
    private val tracker by lazy { IntegritySignalsTracker(tmp.newFolder("integrity")) }
    private val reporter by lazy {
        DeviceStatusReporter(
            facts = { facts }, signals = { if (signalsFail) error("no settings") else signals }, tracker = tracker,
            evidence = { evidenceCalls++; evidence }, state = state, clock = clock, appVersion = "1.0.3+10003",
        )
    }

    private fun records() = runBlocking { db.outboxDao().nextPending(100) }.filter { it.recordType == "device_status" }
    private fun payload(i: Int = records().lastIndex): JsonObject = Json.parseToJsonElement(records()[i].payloadJson).jsonObject["payload"]!!.jsonObject

    @Test
    fun theFirstRunReportsSignalsAndCollectsEvidenceThenNothingUntilSomethingChanges() = runBlocking {
        val uuid = reporter.beforeBatch(db)
        assertNotNull(uuid)
        val p = payload()
        assertEquals("tok.en", p["play_integrity"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals("n0nce", p["play_integrity"]!!.jsonObject["nonce"]!!.jsonPrimitive.content)
        assertTrue("exactly one of token and marker (R12)", "play_integrity_unavailable" !in p)
        assertEquals(0, p["root_hints"]!!.jsonArray.size) // a clean phone sends an empty list (R13)
        assertEquals(JsonNull, p["policy_version_applied"]) // required member, written as null
        assertEquals(uuid, state.integrityRef)
        assertEquals(1, records().size)
        // Second run: same signals, token fresh: nothing queued, no Play call.
        assertNull(reporter.beforeBatch(db))
        assertEquals(1, evidenceCalls)
        // A change in the signals is reported once, without a new token.
        signals = signals.copy(adbEnabled = true, rootHints = listOf("test_keys"))
        assertNotNull(reporter.beforeBatch(db))
        assertEquals("integrity_change", payload()["trigger"]!!.jsonPrimitive.content)
        assertEquals("test_keys", payload()["root_hints"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue("play_integrity" !in payload() && "play_integrity_unavailable" !in payload())
        assertNull(reporter.beforeBatch(db))
        assertEquals(1, evidenceCalls)
    }

    @Test
    fun noTokenGivesTheMarkerAndAFailedAttemptIsNotRetriedEveryBatch() = runBlocking {
        evidence = IntegrityResult.Unavailable(IntegrityUnavailable.OFFLINE)
        reporter.beforeBatch(db)
        assertEquals("offline", payload()["play_integrity_unavailable"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertTrue("play_integrity" !in payload())
        assertNull("no token, no integrity_ref", state.integrityRef)
        elapsed += 10 * 60_000L
        assertNull(reporter.beforeBatch(db)) // within the retry pause: no second Play call
        assertEquals(1, evidenceCalls)
        elapsed += 60 * 60_000L
        evidence = IntegrityResult.Evidence("t2", "n2")
        assertNotNull(reporter.beforeBatch(db))
        assertEquals(2, evidenceCalls)
        assertEquals("t2", payload()["play_integrity"]!!.jsonObject["token"]!!.jsonPrimitive.content)
    }

    @Test
    fun loginOrCheckInAsksForEvidenceEvenWhenTheTokenIsFresh() = runBlocking {
        reporter.beforeBatch(db)
        reporter.wantEvidence()
        assertNotNull(reporter.beforeBatch(db))
        assertEquals(2, evidenceCalls)
        assertFalse(state.evidenceWanted)
    }

    @Test
    fun unreadableSignalsAreUnknownNotClean() = runBlocking {
        signalsFail = true
        reporter.beforeBatch(db)
        assertTrue("R18: missing means unknown, not an empty list", "root_hints" !in payload())
    }

    @Test
    fun aThrowingEvidenceSourceStillSendsTheMarkerAndNeverThrows() = runBlocking {
        val r = DeviceStatusReporter({ facts }, { signals }, tracker, { error("boom") }, state, clock, "1")
        assertNotNull(r.beforeBatch(db))
        assertEquals("api_error", payload()["play_integrity_unavailable"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        val broken = DeviceStatusReporter({ error("no facts") }, { signals }, tracker, { evidence }, IntegrityState.Memory(), clock, "1")
        assertNull(broken.beforeBatch(db)) // never throws into the upload
    }

    @Test
    fun theRecordIsItsOwnFamilyWithoutARoute() = runBlocking {
        reporter.beforeBatch(db)
        val row = records().single()
        assertEquals(row.clientUuid, row.familyUuid)
        assertEquals(0, row.rank)
        val record = Json.parseToJsonElement(row.payloadJson).jsonObject
        assertTrue("route_id" !in record)
        assertEquals("2026-10-05", record["business_date"]!!.jsonPrimitive.content)
    }

    @Test
    fun theCalendarDecidesWorkingDays() {
        val c = DayConfig.Calendar.parse(
            """{"weekend_days":[5],"entries":[
              {"id":1,"date":"2026-10-08","scope_type":"global","scope_id":0,"kind":"holiday","selling_day":false,"name_en":"h"},
              {"id":2,"date":"2026-10-09","scope_type":"zone","scope_id":5012,"kind":"makeup_day","selling_day":true,"name_en":"m"},
              {"id":3,"date":"2026-10-10","scope_type":"zone","scope_id":5012,"kind":"emergency_off","selling_day":false,"name_en":"e"}]}""",
        )
        assertEquals(true, c.isWorkingDay("2026-10-07")) // Wednesday
        assertEquals(false, c.isWorkingDay("2026-10-08")) // holiday
        assertEquals(true, c.isWorkingDay("2026-10-09")) // Friday, but a make-up day
        assertEquals(false, c.isWorkingDay("2026-10-10")) // emergency off
        assertEquals(false, c.isWorkingDay("2026-10-16")) // plain Friday
        assertNull(c.isWorkingDay("not-a-date"))
        assertNull(DayConfig().isWorkingDay("2026-10-07")) // no bundle yet: unknown
    }

    @Test
    fun fixSettingsAndTheCalendarComeFromTheBundle() = runBlocking {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val raw = JsonObject(base + ("config" to Json.parseToJsonElement(
            """{"config_version":318,"values":[
              {"key":"cfg.geo.fix_timeout_s","value":20,"scope_type":"global","requires_ack":false,"effective_from":null},
              {"key":"cfg.geo.fix_accuracy_mode","value":"high","scope_type":"global","requires_ack":false,"effective_from":null},
              {"key":"cfg.geo.require_precise","value":false,"scope_type":"global","requires_ack":false,"effective_from":null}],"scheduled":[]}""",
        )) + ("calendar" to Json.parseToJsonElement("""{"weekend_days":[5],"entries":[]}""")))
        com.aktcl.aron.core.database.repo.ReferenceRepository(db).apply(
            com.aktcl.aron.core.database.reference.BundleReference.json.decodeFromJsonElement(com.aktcl.aron.core.database.reference.BundleReference.serializer(), raw), raw,
        )
        val config = DayConfig()
        config.refresh(db, "2026-10-05T02:00:00.000Z")
        assertEquals(20, config.fixSettings.timeoutS)
        assertEquals(com.aktcl.aron.core.geo.FixPriority.HIGH_ACCURACY, config.fixSettings.priority)
        assertEquals(60, config.fixSettings.reuseMaxAgeS) // not in the bundle: the default
        assertFalse(config.fixSettings.requirePrecise)
        assertEquals(false, config.isWorkingDay("2026-10-09"))
        assertEquals(true, config.isWorkingDay("2026-10-07"))
    }
}
