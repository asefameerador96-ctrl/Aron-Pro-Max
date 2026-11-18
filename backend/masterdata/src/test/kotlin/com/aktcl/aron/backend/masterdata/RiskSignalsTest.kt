package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.RegistryDefaults
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-SYS-013: teleport speed, zero jitter, one coordinate across a route, mock fixes and phone-versus-server disagreement, replayed from fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiskSignalsTest {
    private val t = GeoThresholds(60, 500, 8, 2, 80, 30, 8, 10)
    private val t0 = Instant.parse("2027-01-03T04:00:00Z")
    private fun fix(i: Int, secs: Long, lat: Double, lng: Double, acc: Double? = 12.0, route: Long? = 1, mock: Boolean = false, purpose: String = "visit_open") =
        FixPoint(i.toLong(), 7, route, t0.plusSeconds(secs), lat, lng, acc, mock, purpose, "visit", UUID.randomUUID().toString())

    @Test
    fun teleportNeedsBothDistanceAndSpeed() {
        // ~1.1 km in 60 s = 66 km/h: fires. The same hop in 10 minutes = 6.6 km/h: quiet. 300 m in 5 s is fast but under the minimum distance.
        assertNotNull(RiskRules.teleport(listOf(fix(1, 0, 23.80, 90.36), fix(2, 60, 23.81, 90.36)), t))
        assertNull(RiskRules.teleport(listOf(fix(1, 0, 23.80, 90.36), fix(2, 600, 23.81, 90.36)), t))
        assertNull(RiskRules.teleport(listOf(fix(1, 0, 23.80, 90.36), fix(2, 5, 23.8027, 90.36)), t))
        // Out-of-order input is ordered by capture time first.
        assertNotNull(RiskRules.teleport(listOf(fix(2, 60, 23.81, 90.36), fix(1, 0, 23.80, 90.36)), t))
    }

    @Test
    fun zeroJitterNeedsEnoughStillPairsWithIdenticalAccuracy() {
        val still = (0 until 9).map { fix(it, it * 120L, 23.8000001 + it * 1e-7, 90.36) }
        assertNotNull(RiskRules.zeroJitter(still, t))
        // A real phone drifts: 10 m between fixes, and accuracy varies.
        val drifting = (0 until 9).map { fix(it, it * 120L, 23.80 + it * 0.0001, 90.36, acc = 10.0 + it) }
        assertNull(RiskRules.zeroJitter(drifting, t))
        assertNull(RiskRules.zeroJitter(still.take(5), t), "fewer fixes than the minimum")
    }

    @Test
    fun oneCoordinateAcrossARouteAndMockFixes() {
        val same = (0 until 10).map { fix(it, it * 600L, 23.8, 90.36 + it * 1e-6) }
        assertEquals("GEO_ROUTE_SINGLE_POINT", RiskRules.routeSinglePoint(same, t)?.code)
        val spread = (0 until 10).map { fix(it, it * 600L, 23.80 + it * 0.002, 90.36) }
        assertNull(RiskRules.routeSinglePoint(spread, t))
        assertNull(RiskRules.routeSinglePoint(same.take(5), t), "fewer visits than the minimum")
        assertEquals("GEO_MOCK", RiskRules.mock(listOf(fix(1, 0, 23.8, 90.36, mock = true)))?.code)
        assertNull(RiskRules.mock(spread))
    }

    private lateinit var env: SeededAdminDb
    @BeforeAll fun up() { env = SeededAdminDb() }
    @AfterAll fun down() = env.close()

    @Test
    fun evaluationStoresIdempotentSignalsWithWeightsAndKeepsAReviewersStatus() {
        val db = env.fresh.db
        val cfg = DbServerConfig(db, RegistryDefaults())
        val uid = env.ids.getValue("sr1001")
        val day = LocalDate.parse("2027-01-03")
        val base = Instant.parse("2027-01-03T03:00:00Z")
        db.jdbi.useHandle<Exception> { h ->
            // A teleporting phone with a mocked fix: 23.80 then 23.81 (1.1 km) 60 s later.
            listOf(Triple(0L, 23.80, false), Triple(60L, 23.81, true)).forEach { (s, lat, mock) ->
                h.createUpdate(
                    "INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present) " +
                        "VALUES (:d, 'visit', :u, :uid, :at, 'visit_open', 'ok', :lat, 90.36, 12, 'fused', :m, false, true, false, false, true, false)",
                ).bind("d", day).bind("u", UUID.randomUUID()).bind("uid", uid).bind("at", java.time.OffsetDateTime.ofInstant(base.plusSeconds(s), java.time.ZoneOffset.UTC)).bind("lat", lat).bind("m", mock).execute()
            }
        }
        val ev = RiskSignalEvaluator(db, cfg)
        assertEquals(2, ev.evaluateDay(day))
        fun count() = env.scalar("SELECT count(*) FROM app.risk_signal WHERE user_id = $uid AND business_date = '$day'")!!.toInt()
        assertEquals(2, count())
        assertEquals("100.00", env.scalar("SELECT score FROM app.risk_signal WHERE code = 'GEO_MOCK' AND user_id = $uid"))
        assertEquals("40.00", env.scalar("SELECT score FROM app.risk_signal WHERE code = 'GEO_TELEPORT' AND user_id = $uid"))
        db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.risk_signal SET status = 'confirmed' WHERE code = 'GEO_TELEPORT' AND user_id = $uid") }
        assertEquals(2, ev.evaluateDay(day)); assertEquals(2, count(), "re-evaluation upserts, never duplicates")
        assertEquals("confirmed", env.scalar("SELECT status FROM app.risk_signal WHERE code = 'GEO_TELEPORT' AND user_id = $uid"))
        // A fix voided by a data void no longer counts on the next evaluation (the existing rows stay for review).
        assertTrue(env.scalar("SELECT evidence::text FROM app.risk_signal WHERE code = 'GEO_MOCK' AND user_id = $uid")!!.contains("mock_fixes"))
    }

    private fun sf(phone: String, vararg codes: String, uuid: String = UUID.randomUUID().toString()) = PhoneSignalFix(phone, 7, 1, uuid, codes.toList())

    @Test
    fun phoneSignalsMapOntoTheExistingRiskCodesPerPhoneAndNeverOntoAUserSubject() {
        val hits = PhoneSignalRules.fromFixes(listOf(
            sf("9", "mock_app_selected", "jump"), sf("9", "speed", "hook_framework"), sf("9", "mock_app_selected"), sf("5", "fix_stale"), sf("5", "brand_new_code"),
        ))
        assertTrue(hits.all { it.subjectType == "device" })
        val p9 = hits.filter { it.subjectId == "9" }.associateBy { it.code }
        assertEquals(setOf("GEO_MOCK", "GEO_TELEPORT", "DEVICE_INTEGRITY_FAIL"), p9.keys)
        assertEquals(4, p9.getValue("GEO_MOCK").severity)
        assertEquals("""{"mock_app_selected":2}""", p9.getValue("GEO_MOCK").evidence["codes"].toString())
        assertEquals("""{"jump":1,"speed":1}""", p9.getValue("GEO_TELEPORT").evidence["codes"].toString())
        assertEquals(listOf("GEO_STALE_FIX"), hits.filter { it.subjectId == "5" }.map { it.code }, "an unknown code makes no hit")
        assertTrue(PhoneSignalRules.fromFixes(emptyList()).isEmpty())
    }

    @Test
    fun anIntegrityEventCountsOnceAndOnlyWhenTheGateBlocked() {
        val a = IntegrityEventRow("9", 7, "2027-01-03T04:00:00Z", listOf("dev_options"), "none")
        val hits = PhoneSignalRules.fromIntegrityEvents(listOf(
            a, a, IntegrityEventRow("9", 7, "2027-01-03T05:00:00Z", listOf("adb", "adb_wifi"), "none"),
            IntegrityEventRow("9", 7, "2027-01-03T06:00:00Z", listOf("dev_options"), "lab"), IntegrityEventRow("5", 8, "2027-01-03T06:00:00Z", listOf("dev_options"), "debug_build"),
        ))
        assertEquals(1, hits.size, "the exempt-only phone makes no hit")
        val h = hits.single(); assertEquals("DEVICE_DEBUG_ENABLED", h.code); assertEquals("9", h.subjectId)
        assertEquals("2", h.evidence["blocked_events"].toString()); assertEquals("1", h.evidence["exempt_events"].toString())
    }

    @Test
    fun storedFixSignalsReachTheRiskTableIdempotentlyAndAnOlderPhoneMakesNone() {
        val db = env.fresh.db
        val cfg = DbServerConfig(db, RegistryDefaults())
        val uid = env.ids.getValue("sr1001")
        val day = LocalDate.parse("2027-02-04")
        fun fix(signals: String?, lat: Double, at: String) = db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                "INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present, signals) " +
                    "VALUES (:d, 'visit', :u, :uid, CAST(:at AS timestamptz), 'visit_open', 'ok', :lat, 90.36, 12, 'fused', false, false, false, false, false, true, false, CAST(:sig AS text[]))",
            ).bind("d", day).bind("u", UUID.randomUUID()).bind("uid", uid).bind("at", at).bind("lat", lat).bind("sig", signals).execute()
        }
        fix(null, 23.80, "2027-02-04T03:00:00Z") // an older phone: NULL, unknown, no hit
        fix("{}", 23.8001, "2027-02-04T05:00:00Z") // checked and clean
        val ev = RiskSignalEvaluator(db, cfg)
        assertEquals(0, ev.evaluateDay(day))
        fix("{jump,provider_disagree,hook_framework}", 23.8002, "2027-02-04T07:00:00Z")
        assertEquals(3, ev.evaluateDay(day)); assertEquals(3, ev.evaluateDay(day), "re-evaluation upserts")
        assertEquals(3, env.scalar("SELECT count(*) FROM app.risk_signal WHERE user_id = $uid AND business_date = '$day' AND subject_type = 'device'")!!.toInt())
        assertEquals("40.00", env.scalar("SELECT score FROM app.risk_signal WHERE code = 'GEO_TELEPORT' AND business_date = '$day' AND subject_type = 'device'"))
    }

    // ---- B1 (lead review of 49e6abc6..0f69f3b9): stored phone evidence is never trusted as structure and one bad row never stops the evaluation ----

    @Test
    fun theStoredEventsAreReadDefensivelyAndOnlyKnownCodesCount() {
        fun read(j: String?) = IntegrityEventReader.read("9", 7, j)
        val bad = read("""[{"at":"2027-01-03T04:00:00Z","flags":[{"x":1}],"exempt":"none"},{"at":"2027-01-03T04:00:00Z","flags":"dev_options","exempt":"none"},"x",7,null,[],{"flags":["adb"],"exempt":"none"},{"at":"not a time","flags":["adb"],"exempt":"none"}]""")
        assertEquals(1, bad.events.size, "the first element has a readable time and a list of flags: its object flag is dropped, the event stays"); assertEquals(emptyList(), bad.events.single().flags)
        assertEquals(7, bad.skipped)
        val good = read("""[{"at":"2027-01-03T04:00:00Z","flags":["adb","dev_options","adb","rooted","\u0000"],"exempt":"none"},{"at":"2027-01-03T05:00:00Z","flags":["adb"],"exempt":"made_up"},{"at":"2027-01-03T06:00:00Z","flags":["adb"]}]""")
        assertEquals(listOf("adb", "dev_options"), good.events[0].flags, "known codes only, distinct, ordered")
        assertEquals("unknown", good.events[1].exempt); assertEquals("unknown", good.events[2].exempt)
        assertEquals(0, read("not json").events.size); assertEquals(0, read("{}").events.size); assertEquals(0, read(null).events.size)
        // An unknown or missing `exempt` is never counted as a blocked onset.
        assertTrue(PhoneSignalRules.fromIntegrityEvents(good.events.drop(1)).isEmpty())
    }

    private fun insertDevice(): Long = env.fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery(
            "INSERT INTO app.device (device_uuid, flavour, app_package, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256) " +
                "VALUES (CAST(:u AS uuid), 'sr', 'com.aktcl.aron.sr', false, 'prod', CAST('{}' AS jsonb), :t, decode(repeat('ab', 32), 'hex')) RETURNING id",
        ).bind("u", UUID.randomUUID().toString()).bind("t", "thumb-" + UUID.randomUUID()).mapTo(Long::class.java).one()
    }

    private fun statusReport(device: Long, uid: Long, day: LocalDate, report: String) = env.fresh.db.jdbi.useHandle<Exception> { h ->
        h.createUpdate(
            "INSERT INTO app.device_status_report (source, device_id, user_id, business_date, reported_at, app_version, device_owner, lockdown_level_applied, blocking_active, location_enabled, dev_options_enabled, adb_enabled, auto_time_enabled, pending_rows, battery_pct, report) " +
                "VALUES ('online', :d, :u, :bd, now(), '1.0.0+1', false, 'prod', false, true, false, false, true, 0, 80, CAST(:r AS jsonb))",
        ).bind("d", device).bind("u", uid).bind("bd", day).bind("r", report).execute()
    }

    /** One malformed stored report (a tampered or newer phone) used to throw out of the whole evaluation: no user got a hit for that date. */
    @Test
    fun oneMalformedStoredStatusReportDoesNotStopTheEvaluationOfTheOthers() {
        val db = env.fresh.db
        val cfg = DbServerConfig(db, RegistryDefaults())
        val day = LocalDate.parse("2027-04-05")
        val a = env.ids.getValue("sr1001")
        val b = env.ids.values.first { it != a }
        db.jdbi.useHandle<Exception> { h ->
            // user A: a teleporting phone with a mocked fix (two hits)
            listOf(Triple(0L, 23.80, false), Triple(60L, 23.81, true)).forEach { (secs, lat, mock) ->
                h.createUpdate(
                    "INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present) " +
                        "VALUES (:d, 'visit', :u, :uid, CAST(:at AS timestamptz), 'visit_open', 'ok', :lat, 90.36, 12, 'fused', :m, false, true, false, false, true, false)",
                ).bind("d", day).bind("u", UUID.randomUUID()).bind("uid", a).bind("at", Instant.parse("2027-04-05T03:00:00Z").plusSeconds(secs).toString()).bind("lat", lat).bind("m", mock).execute()
            }
        }
        // user B: a report whose events are malformed in every way a tampered phone could make them, and one good blocked onset after them
        val dev = insertDevice()
        statusReport(dev, b, day, """{"integrity_events":[{"at":"2027-04-05T04:00:00Z","flags":[{"x":1}],"exempt":"none"},{"at":["x"],"flags":[[]],"exempt":{"a":1}},"x",{"at":"2027-04-05T05:00:00Z","flags":["dev_options"],"exempt":"none"}]}""")
        statusReport(dev, b, day, """{"integrity_events":"not an array"}""")
        statusReport(dev, b, day, """{"integrity_events":[{"at":"2027-04-05T06:00:00Z","flags":[1,2],"exempt":null}]}""")
        val ev = RiskSignalEvaluator(db, cfg)
        val n = ev.evaluateDay(day)
        assertEquals(3, n, "A's two fix hits and B's one readable onset: nothing was thrown away")
        assertEquals(2, env.scalar("SELECT count(*) FROM app.risk_signal WHERE user_id = $a AND business_date = '$day'")!!.toInt())
        assertEquals(1, env.scalar("SELECT count(*) FROM app.risk_signal WHERE code = 'DEVICE_DEBUG_ENABLED' AND user_id = $b AND business_date = '$day'")!!.toInt())
        assertEquals(3, ev.evaluateDay(day), "and it stays idempotent")
    }

    @Test
    fun aFailingDateNeverStopsTheOther() {
        val job = RiskSignalJob(RiskSignalEvaluator(env.fresh.db, DbServerConfig(env.fresh.db, RegistryDefaults())))
        val seen = mutableListOf<LocalDate>(); val today = LocalDate.parse("2027-04-05")
        job.runDates(today) { d -> seen += d; if (d == today) error("this date is broken") }
        assertEquals(listOf(today, today.minusDays(1)), seen)
        seen.clear(); job.runDates(today) { d -> seen += d; if (d == today.minusDays(1)) error("yesterday is broken") }
        assertEquals(listOf(today, today.minusDays(1)), seen)
    }

    @Test
    fun aSourceThatFailsInTheDatabaseIsSkippedWithoutAbortingTheTransactionOfTheOthers() {
        val db = env.fresh.db
        val day = LocalDate.parse("2027-04-06")
        val uid = env.ids.getValue("sr1001")
        db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                "INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present) " +
                    "VALUES (:d, 'visit', :u, :uid, CAST('2027-04-06T03:00:00Z' AS timestamptz), 'visit_open', 'ok', 23.8, 90.36, 12, 'fused', true, false, true, false, false, true, false)",
            ).bind("d", day).bind("u", UUID.randomUUID()).bind("uid", uid).execute()
        }
        // A config that makes one source throw (the weights are read once; the integrity source reads a view that is dropped for this test's transaction only).
        val n = db.jdbi.inTransaction<Int, Exception> { h ->
            h.execute("ALTER TABLE app.device_status_report RENAME TO device_status_report_x")
            try { RiskSignalEvaluator(db, DbServerConfig(db, RegistryDefaults())).evaluate(h, day) } finally { h.execute("ALTER TABLE app.device_status_report_x RENAME TO device_status_report") }
        }
        assertEquals(1, n, "the missing table broke the integrity-events source only; the mock hit was still stored")
        assertEquals(1, env.scalar("SELECT count(*) FROM app.risk_signal WHERE user_id = $uid AND business_date = '$day'")!!.toInt())
    }

    // ---- review lows: events dated by their own time, shared phones, the device UUID as the subject ----

    private fun deviceUuid(id: Long) = env.scalar("SELECT device_uuid::text FROM app.device WHERE id = $id")!!

    @Test
    fun aGateOnsetCountsOnTheDateItHappenedNotOnTheDateOfTheReportThatCarriedIt() {
        val db = env.fresh.db; val cfg = DbServerConfig(db, RegistryDefaults())
        val uid = env.ids.getValue("sr1001"); val dev = insertDevice()
        val d1 = LocalDate.parse("2027-05-10"); val d2 = d1.plusDays(1)
        // reported the next day (a kill, a late sync, a re-send across Dhaka midnight): one onset of d1 (22:00 UTC = 04:00 Dhaka d2!), see below, and two of d2
        statusReport(dev, uid, d2, """{"integrity_events":[{"at":"2027-05-10T09:00:00Z","flags":["adb"],"exempt":"none"},{"at":"2027-05-10T20:00:00Z","flags":["dev_options"],"exempt":"none"},{"at":"2027-05-11T07:00:00Z","flags":["adb"],"exempt":"none"}]}""")
        // the same first event sent again in a later report (identity: phone, user, time, flags): it must not count twice
        statusReport(dev, uid, d2, """{"integrity_events":[{"at":"2027-05-10T09:00:00Z","flags":["adb"],"exempt":"none"}]}""")
        val ev = RiskSignalEvaluator(db, cfg)
        ev.evaluateDay(d1); ev.evaluateDay(d2)
        fun blocked(d: LocalDate) = env.scalar("SELECT evidence ->> 'blocked_events' FROM app.risk_signal WHERE code = 'DEVICE_DEBUG_ENABLED' AND business_date = '$d' AND user_id = $uid")
        assertEquals("1", blocked(d1), "09:00Z = 15:00 Dhaka on the 10th; the re-sent copy counts once")
        assertEquals("2", blocked(d2), "20:00Z on the 10th is 02:00 Dhaka on the 11th, plus 07:00Z on the 11th")
        assertEquals(deviceUuid(dev), env.scalar("SELECT subject_id FROM app.risk_signal WHERE code = 'DEVICE_DEBUG_ENABLED' AND business_date = '$d1' AND user_id = $uid"), "the subject is the device UUID, like the other device rows")
    }

    @Test
    fun aSharedPhoneGivesEachRepHisOwnRowAndASinglePhoneKeepsThePlainDeviceUuid() {
        val db = env.fresh.db; val cfg = DbServerConfig(db, RegistryDefaults())
        val a = env.ids.getValue("sr1001"); val b = env.ids.values.first { it != a }
        val shared = insertDevice(); val solo = insertDevice(); val day = LocalDate.parse("2027-05-12")
        fun fix(dev: Long, uid: Long, signals: String) = db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                "INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, device_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present, signals) " +
                    "VALUES (:d, 'visit', :u, :uid, :dev, CAST('2027-05-12T04:00:00Z' AS timestamptz), 'visit_open', 'ok', 23.8, 90.36, 12, 'fused', false, false, false, false, false, true, false, CAST(:sig AS text[]))",
            ).bind("d", day).bind("u", UUID.randomUUID()).bind("uid", uid).bind("dev", dev).bind("sig", signals).execute()
        }
        fix(shared, a, "{hook_framework}"); fix(shared, b, "{emulator}") // two reps, one phone, the same risk code
        fix(solo, a, "{root_hint}")                                       // one rep, one phone
        RiskSignalEvaluator(db, cfg).evaluateDay(day)
        val rows = db.jdbi.withHandle<List<Triple<String, String, Long>>, Exception> { h ->
            h.createQuery("SELECT code, subject_id, user_id FROM app.risk_signal WHERE business_date = :d AND code = 'DEVICE_INTEGRITY_FAIL' ORDER BY subject_id").bind("d", day).map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getLong(3)) }.list()
        }
        assertEquals(3, rows.size, "one row per rep on the shared phone, one for the solo phone: $rows")
        assertEquals(setOf("${deviceUuid(shared)}#$a" to a, "${deviceUuid(shared)}#$b" to b, deviceUuid(solo) to a), rows.map { it.second to it.third }.toSet())
        assertTrue(rows.all { it.second.length <= 64 })
    }

    @Test
    fun disambiguateOnlyTouchesCodesThatTwoUsersShareOnOnePhone() {
        fun hit(code: String, phone: String, uid: Long) = RiskHit(code, 3, "device", phone, uid, null, kotlinx.serialization.json.JsonObject(emptyMap()))
        val out = PhoneSignalRules.disambiguate(listOf(hit("A", "p", 1), hit("A", "p", 2), hit("B", "p", 1), hit("A", "q", 1), RiskHit("A", 3, "user", "p", 5, null, kotlinx.serialization.json.JsonObject(emptyMap()))))
        assertEquals(listOf("p#1", "p#2", "p", "q", "p"), out.map { it.subjectId })
    }

    @Test
    fun aRepWhoAppearsLaterOnASharedPhoneDoesNotLeaveAnEarlierPlainRowBesideTheNewOne() {
        val db = env.fresh.db; val cfg = DbServerConfig(db, RegistryDefaults())
        val a = env.ids.getValue("sr1001"); val b = env.ids.values.first { it != a }; val dev = insertDevice(); val day = LocalDate.parse("2027-05-14")
        fun fix(uid: Long, sig: String) = db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                "INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, device_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present, signals) " +
                    "VALUES (:d, 'visit', :u, :uid, :dev, CAST('2027-05-14T04:00:00Z' AS timestamptz), 'visit_open', 'ok', 23.8, 90.36, 12, 'fused', false, false, false, false, false, true, false, CAST(:sig AS text[]))",
            ).bind("d", day).bind("u", UUID.randomUUID()).bind("uid", uid).bind("dev", dev).bind("sig", sig).execute()
        }
        val ev = RiskSignalEvaluator(db, cfg)
        fix(a, "{hook_framework}"); ev.evaluateDay(day) // 10:00: rep A alone, plain device uuid
        val plain = deviceUuid(dev)
        assertEquals(1, env.scalar("SELECT count(*) FROM app.risk_signal WHERE subject_id = '$plain' AND business_date = '$day'")!!.toInt())
        db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.risk_signal SET status = 'confirmed' WHERE subject_id = '$plain' AND business_date = '$day'") } // a reviewer acts
        fix(b, "{emulator}"); ev.evaluateDay(day); ev.evaluateDay(day) // 14:00: rep B appears
        val rows = db.jdbi.withHandle<List<Pair<String, String>>, Exception> { h -> h.createQuery("SELECT subject_id, status FROM app.risk_signal WHERE code = 'DEVICE_INTEGRITY_FAIL' AND business_date = :d ORDER BY subject_id").bind("d", day).map { rs, _ -> rs.getString(1) to rs.getString(2) }.list() }
        assertEquals(2, rows.size); assertEquals(setOf("$plain#$a" to "confirmed", "$plain#$b" to "open"), rows.toSet(), "one row per rep and date, the reviewer status carried")
    }

    @Test
    fun eventsAreIdentifiedByInstantAndPerUserNotByTimestampString() {
        val same = listOf(
            IntegrityEventRow("p", 1, "2027-01-03T04:00:00Z", listOf("adb"), "none"), IntegrityEventRow("p", 1, "2027-01-03T04:00:00.000Z", listOf("adb"), "none"),
            IntegrityEventRow("p", 2, "2027-01-03T04:00:00Z", listOf("adb"), "none"),
        )
        val hits = PhoneSignalRules.fromIntegrityEvents(same)
        assertEquals(2, hits.size, "two reps with the identical instant on one phone are both counted")
        assertEquals("1", hits.first { it.userId == 1L }.evidence["blocked_events"].toString(), "the same instant written two ways counts once")
    }

    /** Review: the phone-signal DEVICE_INTEGRITY_FAIL row used to overwrite the Play Integrity row's evidence (same code, device UUID, date). Both writers' members stay, the higher score wins. */
    @Test
    fun aPhoneSignalRowMergesWithThePlayIntegrityRowInsteadOfOverwritingIt() {
        val db = env.fresh.db; val cfg = DbServerConfig(db, RegistryDefaults())
        val a = env.ids.getValue("sr1001"); val dev = insertDevice(); val day = LocalDate.parse("2027-05-16"); val uuid = deviceUuid(dev)
        db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, score, evidence, config_version) VALUES ('DEVICE_INTEGRITY_FAIL', 4, '$day', 'device', '$uuid', $a, 90, '{\"verdict\":\"fail\",\"play_integrity\":true}', 1)")
            h.createUpdate(
                "INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, device_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present, signals) " +
                    "VALUES (:d, 'visit', :u, :uid, :dev, CAST('2027-05-16T04:00:00Z' AS timestamptz), 'visit_open', 'ok', 23.8, 90.36, 12, 'fused', false, false, false, false, false, true, false, CAST('{root_hint}' AS text[]))",
            ).bind("d", day).bind("u", UUID.randomUUID()).bind("uid", a).bind("dev", dev).execute()
        }
        RiskSignalEvaluator(db, cfg).evaluateDay(day); RiskSignalEvaluator(db, cfg).evaluateDay(day)
        assertEquals(1, env.scalar("SELECT count(*) FROM app.risk_signal WHERE code = 'DEVICE_INTEGRITY_FAIL' AND subject_id = '$uuid' AND business_date = '$day'")!!.toInt())
        val ev = env.scalar("SELECT evidence::text FROM app.risk_signal WHERE code = 'DEVICE_INTEGRITY_FAIL' AND subject_id = '$uuid' AND business_date = '$day'")!!
        assertTrue(ev.contains("\"verdict\"") && ev.contains("\"phone_signals\""), ev)
        assertEquals("90.00", env.scalar("SELECT score FROM app.risk_signal WHERE code = 'DEVICE_INTEGRITY_FAIL' AND subject_id = '$uuid' AND business_date = '$day'"))
    }
}
