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
import kotlinx.coroutines.async
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
        reporter.wantEvidence("check_in")
        assertNull("Sales Submit never waits for Play", reporter.beforeBatch(db, allowEvidence = false))
        assertEquals(1, evidenceCalls)
        assertNotNull(reporter.beforeBatch(db))
        assertEquals(2, evidenceCalls)
        assertEquals("check_in", payload()["trigger"]!!.jsonPrimitive.content)
        assertNull(state.evidenceWanted)
    }

    @Test
    fun unreadableSignalsSendNothingRatherThanCleanGuesses() = runBlocking {
        signalsFail = true
        reporter.wantEvidence()
        assertNull(reporter.beforeBatch(db))
        assertEquals(0, evidenceCalls)
        assertEquals("periodic", state.evidenceWanted) // still asked for: the next readable run collects it
    }

    @Test
    fun aBuildWithoutPlayIntegritySendsNotConfiguredWithoutAnyCall() = runBlocking {
        val r = DeviceStatusReporter({ facts }, { signals }, tracker, { evidenceCalls++; evidence }, state, clock, "1", evidenceConfigured = false)
        assertNotNull(r.beforeBatch(db))
        assertEquals(0, evidenceCalls)
        assertEquals("not_configured", payload()["play_integrity_unavailable"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSlowPlayIntegrityIsCutAtTheDeadline() = runBlocking {
        val r = DeviceStatusReporter({ facts }, { signals }, tracker, { kotlinx.coroutines.delay(60_000); evidence }, state, clock, "1", evidenceDeadlineMs = 200)
        assertNotNull(r.beforeBatch(db))
        assertEquals("timeout", payload()["play_integrity_unavailable"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
    }

    /** Checker (wiring round 1, defect 2): two workers of one user at once asked Play twice and queued two reports. */
    @Test
    fun twoConcurrentRunsAskPlayIntegrityOnce() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        state.evidenceWanted = "periodic"
        val r = DeviceStatusReporter({ facts }, { signals }, tracker, { calls.incrementAndGet(); kotlinx.coroutines.delay(200); evidence }, state, clock, "1")
        listOf(
            async(kotlinx.coroutines.Dispatchers.Default) { r.beforeBatch(db) },
            async(kotlinx.coroutines.Dispatchers.Default) { r.beforeBatch(db) },
        ).map { it.await() }
        assertEquals(1, calls.get())
        assertEquals(1, records().size)
    }

    /** Checker (wiring round 1, defect 1): an emergency off-day arrives only in a config delta's calendar_changes. */
    @Test
    fun anEmergencyOffDayFromAConfigDeltaMakesTheDayNonWorking() = runBlocking {
        db.referenceDao().insertSections(listOf(
            com.aktcl.aron.core.database.entity.BundleSectionEntity("calendar", """{"weekend_days":[5],"entries":[]}"""),
            com.aktcl.aron.core.database.entity.BundleSectionEntity("calendar_changes", """[{"id":9,"date":"2026-10-06","scope_type":"zone","scope_id":3,"kind":"emergency_off","selling_day":false,"name_en":"Hartal"}]"""),
        ))
        val cfg = DayConfig()
        cfg.refresh(db, "2026-10-06T03:00:00.000Z")
        assertEquals(false, cfg.isWorkingDay("2026-10-06"))
        assertEquals(true, cfg.isWorkingDay("2026-10-07"))
    }

    /** Follow-up of F-SYS-079: the check-out gate time and the jitter come from the user's config, within bounds. */
    @Test
    fun theCheckoutGateAndJitterAreReadFromConfig() = runBlocking {
        val cfg = DayConfig()
        cfg.refresh(db, "2026-10-06T03:00:00.000Z")
        assertEquals(17 * 60, cfg.checkoutEarliestMinutes) // nothing configured: the defaults
        assertEquals(90, cfg.checkoutJitterS)
        fun row(key: String, json: String) = com.aktcl.aron.core.database.entity.ConfigValueEntity(
            key = key, valueJson = json, scopeType = "global", scopeId = null, effectiveFrom = null, effectiveTo = null,
            configVersion = 1, requiresAck = false, scheduled = false,
        )
        db.referenceDao().insertConfig(listOf(row("cfg.day.checkout_earliest_time", "\"18:30\""), row("cfg.sync.checkout_jitter_s", "45")))
        cfg.refresh(db, "2026-10-06T03:00:00.000Z")
        assertEquals(18 * 60 + 30, cfg.checkoutEarliestMinutes)
        assertEquals(45, cfg.checkoutJitterS)
        assertEquals(17 * 60, DayConfig.minutesOf("17:00:00"))
        listOf("23:00", "11:59", "17:61", "5pm", "", null).forEach { assertNull(it, DayConfig.minutesOf(it)) }
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
        // As the server's app.is_working_day: a selling entry wins over an off entry on the same date.
        val both = DayConfig.Calendar.parse("""{"weekend_days":[5],"entries":[
              {"id":4,"date":"2026-10-12","scope_type":"global","scope_id":0,"kind":"holiday","selling_day":false,"name_en":"h"},
              {"id":5,"date":"2026-10-12","scope_type":"zone","scope_id":1,"kind":"makeup_day","selling_day":true,"name_en":"m"}]}""",
            // A later change of holiday 5 cancels the make-up day.
            """[{"id":5,"date":"2026-10-12","scope_type":"zone","scope_id":1,"kind":"makeup_day","selling_day":false,"name_en":"m"}]""")
        assertEquals(false, both.isWorkingDay("2026-10-12"))
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

    /** Checker round 2 (defect 6): as the server's DayPlan, the most specific scope decides and an off-day wins at equal scope. */
    @Test
    fun emergencyOffInTheZoneBeatsAGlobalOrZoneMakeUpDay() {
        val c = DayConfig.Calendar.parse(
            """{"weekend_days":[5],"entries":[
              {"id":1,"date":"2026-10-09","scope_type":"global","scope_id":0,"kind":"makeup_day","selling_day":true,"name_en":"m"},
              {"id":2,"date":"2026-10-11","scope_type":"zone","scope_id":7,"kind":"makeup_day","selling_day":true,"name_en":"m"},
              {"id":5,"date":"2026-10-12","scope_type":"global","scope_id":0,"kind":"holiday","selling_day":false,"name_en":"h"},
              {"id":6,"date":"2026-10-12","scope_type":"zone","scope_id":7,"kind":"makeup_day","selling_day":true,"name_en":"m"}]}""",
            """[{"id":3,"date":"2026-10-09","scope_type":"zone","scope_id":7,"kind":"emergency_off","selling_day":false,"name_en":"hartal"},
               {"id":4,"date":"2026-10-11","scope_type":"zone","scope_id":7,"kind":"emergency_off","selling_day":false,"name_en":"hartal"}]""",
        )
        assertEquals(false, c.isWorkingDay("2026-10-09"))
        assertEquals(false, c.isWorkingDay("2026-10-11"))
        assertEquals(true, c.isWorkingDay("2026-10-12")) // a zone make-up day beats a global holiday
    }

    /** Checker round 2 (defect 5): Sales Submit never waits behind a background run that is inside Play Integrity. */
    @Test
    fun daySubmitDoesNotWaitForABackgroundRunsEvidence() = runBlocking {
        val inPlay = kotlinx.coroutines.CompletableDeferred<Unit>()
        val r = DeviceStatusReporter({ facts }, { signals }, tracker, { inPlay.complete(Unit); kotlinx.coroutines.delay(3_000); evidence }, state, clock, "1")
        val background = async(kotlinx.coroutines.Dispatchers.Default) { r.beforeBatch(db, allowEvidence = true) }
        inPlay.await()
        val t0 = System.nanoTime()
        r.beforeBatch(db, allowEvidence = false)
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        background.await()
        assertTrue("Sales Submit waited $waitedMs ms", waitedMs < 500)
    }

    /** Checker round 3 (note C): an entry of an unknown scope is ignored, as the server's bundle does. */
    @Test
    fun unknownScopeIsIgnoredAsTheServerDoes() {
        val c = DayConfig.Calendar.parse("""{"weekend_days":[5],"entries":[
          {"id":1,"date":"2026-10-09","scope_type":"global","scope_id":0,"kind":"makeup_day","selling_day":true,"name_en":"m"},
          {"id":2,"date":"2026-10-09","scope_type":"region","scope_id":9,"kind":"holiday","selling_day":false,"name_en":"h"}]}""")
        assertEquals(true, c.isWorkingDay("2026-10-09"))
    }
}
