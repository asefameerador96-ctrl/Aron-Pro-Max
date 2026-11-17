package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.ServerEnv
import java.io.StringReader
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bulk loader on SYNTHETIC data shaped like the owner's October workbook. The real workbook is never loaded here or into the test account: it is
 * fleet-sized personal data (standing rules 1 and 8, `docs/reference/master-data-profile-2026-10.md` s5). Every test runs on a database of its own.
 */
class MasterDataLoaderTest {
    private lateinit var fresh: FreshDb

    private fun withDb(block: () -> Unit) {
        fresh = FreshDb.create()
        try { block() } finally { fresh.close() }
    }

    private val today = LocalDate.parse("2026-11-13")   // date-pin-ok: the load's business date is an input, not a clock read

    private fun options(
        runId: UUID = UUID.randomUUID(), dry: Boolean = false, batch: Int = 500, confirm: Boolean = false, allowShort: Boolean = false,
        businessDate: LocalDate = today, storeNoPlan: Boolean = true, afterBatch: ((Int) -> Unit)? = null,
    ) = LoadOptions(
        env = ServerEnv.DEV, syntheticTestData = true, batchSize = batch, coordinatesConfirmed = confirm, dryRun = dry, runId = runId, businessDate = businessDate,
        storeOutletsWithoutVisitPlan = storeNoPlan, shortRepIdsAllowedForTests = allowShort, afterBatchForTests = afterBatch,
    )

    private fun load(csv: String, o: LoadOptions = options()): LoadReport = MasterDataLoader(fresh.db, o).load { StringReader(csv) }

    private fun <T> sql(sql: String, type: Class<T>): T = fresh.db.jdbi.withHandle<T, Exception> { h -> h.createQuery(sql).mapTo(type).one() }
    private fun count(sql: String): Int = sql("SELECT count(*)::int FROM $sql", Int::class.javaObjectType)
    private fun one(sql: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }
    private fun exec(sql: String) { fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) } }

    // ---- hand-made rows for the tests that need to control every value --------------------------------------------------------------------------

    private val head = SyntheticMaster.HEADER.joinToString(",")

    /** One input row with the 32 columns of the profile; every argument has a sensible default so a test names only what it is about. */
    private fun line(
        code: String, zone: String = "Z1", territory: String = "T1", route: String = "R1", cluster: String = "C1", name: String = "Shop $code", owner: String = "Owner $code",
        rep: String = "ab0001", phone: String = "01711000001", freq: String = "6", day: String = "Daily", lat: String = "23.70000000", lng: String = "90.40000000",
        channel: String = "GT Channel", sub: String = "GT",
    ) = listOf(
        "1", "Wing 1", "11", "Division 11", territory, "Territory $territory", "", "", zone, "Zone $zone", route, "Route $route", freq, day, rep, if (rep.isEmpty()) "" else "Rep $rep",
        "", code, name, owner, phone, "", cluster, "Transit Hub", if (rep.isEmpty()) "" else "Active", lat, lng, "Rural", channel, sub, "2024-03-05 10:15:00", "",
    ).joinToString(",")

    private fun csv(vararg rows: String) = (listOf(head) + rows).joinToString("\n") + "\n"

    /** The same text under another run id must not be accepted by accident: text of any error or report that could carry a value of the file. */
    private fun assertNoPersonalData(text: String) {
        for (forbidden in listOf("Outlet ", "Owner ", "Rep ", "N/A", "Shop ", "Blocked")) assertTrue(!text.contains(forbidden), "carries a value of the file '$forbidden':\n$text")
        assertTrue(!Regex("(^|[^0-9])(01[3-9][0-9]{8}|8801[3-9][0-9]{8})([^0-9]|$)").containsMatchIn(text), "carries a phone number:\n$text")
    }

    // ---- the promises --------------------------------------------------------------------------------------------------------------------------

    /** The whole run on 2,000 synthetic rows: a dry run writes nothing, the load writes everything, and every input row is counted exactly once. */
    @Test
    fun aDryRunWritesNothingAndTheLoadLoadsEveryRowExactlyOnce() = withDb {
        val (csv, facts) = SyntheticMaster.csv(2_000, seed = 7)
        val dry = load(csv, options(dry = true))
        assertEquals(facts.rows.toLong(), dry.rowsRead)
        assertEquals(0, count("app.outlet"), "a dry run writes nothing")
        assertEquals(0, count("app.route"))
        assertEquals(0, count("app.wing"))
        assertTrue(dry.text().contains("DRY RUN"))

        val run = load(csv)
        val o = run.of("outlet")
        assertEquals(facts.rows.toLong(), run.rowsRead)
        assertEquals(facts.rows, o.total, "every input row is counted exactly once: ${run.text()}")
        assertEquals(0, o.error, run.text())
        assertEquals(facts.rows, count("app.outlet"))
        // the dry run said exactly what the load did
        for (e in listOf("wing", "division", "territory", "house", "zone", "route", "cluster", "outlet", "sales_rep", "route_assignment")) {
            assertEquals(run.of(e).created, dry.of(e).created, "dry run: $e created")
            assertEquals(run.of(e).updated, dry.of(e).updated, "dry run: $e updated")
            assertEquals(run.of(e).held, dry.of(e).held, "dry run: $e held")
        }
        for (level in listOf("wing", "division", "territory", "house", "zone", "route", "cluster")) {
            assertEquals(run.of(level).created, count("app.$level"), "$level rows")
            assertTrue(run.of(level).created > 0, level)
        }
        assertEquals(count("app.zone"), count("app.zone z JOIN app.territory t ON t.id = z.territory_id"), "every zone hangs under a territory")
        assertTrue(count("app.route WHERE visit_kind = 'none' AND visit_days_mask = 0") > 0)
        assertEquals("No visit plan", one("SELECT display_label FROM app.route WHERE visit_kind = 'none' LIMIT 1"))
        assertTrue(count("app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.visit_kind = 'none'") > 0)
        assertEquals(facts.outletsWithoutRep, count("app.outlet WHERE status = 'archived'"))
        assertTrue(count("app.outlet WHERE source_contact_number IS NOT NULL") > 0)
        assertTrue(count("app.outlet WHERE source_sub_channel IS NOT NULL") > 0)
        assertEquals("2024-03-05 04:15:00+00", one("SELECT source_created_at::text FROM app.outlet ORDER BY id LIMIT 1"))
        assertEquals(0, count("app.outlet WHERE contact_number IS NOT NULL AND contact_number !~ '^01[3-9][0-9]{8}$'"))
        assertEquals(0, count("app.outlet WHERE location_confirmed"))
        assertEquals(facts.noCoordinates, count("app.outlet WHERE lat IS NULL"))
        assertEquals(count("app.outlet"), count("app.outlet_placement_history WHERE valid_to IS NULL"))
        assertEquals(count("app.outlet"), count("app.outlet_location_history"))
        assertTrue(run.issues.getOrDefault("route_with_more_than_one_rep", 0) >= facts.routesWithTwoReps.size, run.text())
        assertTrue(run.issues.getOrDefault("route_visit_pattern_includes_friday", 0) >= facts.fridayRoutes.size)
        assertTrue(run.issues.getOrDefault("zone_with_two_parents", 0) >= facts.zonesWithTwoTerritories.size)
        assertEquals(facts.codelessRows, run.issues.getOrDefault("outlet_row_without_a_code", 0))
        assertEquals(facts.outsideBangladesh, run.issues.getOrDefault("coordinates_outside_bangladesh", 0))
        assertNoPersonalData(run.text())
        assertEquals(run.of("sales_rep").created, count("app.app_user WHERE role = 'SR'"))
        assertEquals(0, count("app.app_user WHERE role = 'SR' AND password_hash IS NOT NULL"))
        assertEquals(facts.routesWithTwoReps.size, count("app.route_assignment WHERE kind = 'cover'"))
        // the staging table of a finished run is gone
        assertNull(one("SELECT to_regclass('mdload.outlet_row')::text"))
    }

    /** The same file again changes NOTHING: no row, no version bump, no new history row; every row is reported unchanged, by the dry run as well. */
    @Test
    fun loadingTheSameFileAgainChangesNothing() = withDb {
        val (csv, facts) = SyntheticMaster.csv(900, wings = 1, seed = 11)
        load(csv)
        val outlets = count("app.outlet")
        val versions = one("SELECT md5(string_agg(code || ':' || version, ',' ORDER BY code)) FROM app.outlet")
        val history = count("app.outlet_location_history") to count("app.outlet_placement_history")
        val assignments = count("app.route_assignment")
        val dry = load(csv, options(dry = true))
        assertEquals(facts.rows, dry.of("outlet").unchanged, "the dry run is exact: ${dry.text()}")
        val again = load(csv)
        val o = again.of("outlet")
        assertEquals(facts.rows, o.total)
        assertEquals(0, o.created); assertEquals(0, o.updated)
        assertEquals(facts.rows - o.error, o.unchanged, again.text())
        assertEquals(outlets, count("app.outlet"))
        assertEquals(versions, one("SELECT md5(string_agg(code || ':' || version, ',' ORDER BY code)) FROM app.outlet"), "no version was bumped")
        assertEquals(history, count("app.outlet_location_history") to count("app.outlet_placement_history"))
        assertEquals(assignments, count("app.route_assignment"))
        assertEquals(0, again.of("route").created + again.of("route").updated)
        assertEquals(0, again.of("cluster").created + again.of("cluster").updated)
        assertEquals(0, again.of("sales_rep").created + again.of("sales_rep").updated)
    }

    /**
     * Finding L1. A run is resumed under the same run id and THE SAME FILE: the finished batches are skipped, the rest applied, the result is that of an
     * uninterrupted run. A DIFFERENT file under that run id is refused with a message (a corrected file is a new run), never skipped as "unchanged".
     */
    @Test
    fun aRunIdIsBoundToItsFileAndAResumeLosesNothing() = withDb {
        val (csv, _) = SyntheticMaster.csv(1_200, wings = 1, seed = 23)
        val runId = UUID.randomUUID()
        assertFailsWith<IllegalStateException> { load(csv, options(runId = runId, batch = 300, afterBatch = { if (it == 2) throw IllegalStateException("interrupted") })) }
        val afterPart = count("app.outlet")
        assertEquals(600, afterPart, "two batches of 300 committed before the interruption")
        val resumed = load(csv, options(runId = runId, batch = 300))
        assertEquals(2, resumed.batchesSkipped, resumed.text())
        assertEquals(2, resumed.batchesApplied)
        assertEquals(1_200, count("app.outlet"), "resuming lost no row")

        // the same run id over a CORRECTED file: refused, and the message names no value of the file
        val corrected = csv.replace("Outlet 5,", "Outlet Five Corrected,")
        assertTrue(corrected != csv, "the synthetic file has an outlet 5")
        val refused = assertFailsWith<LoaderException> { load(corrected, options(runId = runId, batch = 300)) }
        assertTrue(refused.message!!.contains("different file") && refused.message!!.contains("NEW run id"), refused.message)
        assertNoPersonalData(refused.message!! + refused.stackTraceToString())
        assertEquals("Outlet 5", one("SELECT name FROM app.outlet WHERE name = 'Outlet 5'"), "nothing of the corrected file was applied under the old run id")
        // a NEW run id over the corrected file applies exactly the correction
        val fixed = load(corrected, options(batch = 300))
        assertEquals(1, fixed.of("outlet").updated, fixed.text())
        assertEquals(1_199, fixed.of("outlet").unchanged)
        assertEquals("Outlet Five Corrected", one("SELECT name FROM app.outlet WHERE name = 'Outlet Five Corrected'"))
    }

    /**
     * Finding L2. An outlet code that appears on more than one row, inside one batch or across batches, holds EVERY row of that code as a row error: no
     * winner, no overwrite, no aborted load. A codeless row repeated (the same generated code) is held the same way.
     */
    @Test
    fun everyRowOfADuplicatedOutletCodeIsHeldAndNoWinnerIsPicked() = withDb {
        val rows = listOf(
            line("D1", name = "Shop X"), line("D1", name = "Shop Y"),                    // same batch
            line("OK1"), line("OK2"), line("OK3"), line("OK4"),
            line("D2", name = "Shop P"), line("OK5"), line("OK6"), line("OK7"),
            line("D2", name = "Shop Q", cluster = "C2"),                                // another batch, another cluster
            line("", name = "Twin"), line("", name = "Twin"),                           // the same generated code twice
            line("OK8"),
        )
        val file = csv(*rows.toTypedArray())
        val dry = load(file, options(dry = true, batch = 4))
        val run = load(file, options(batch = 4))
        val o = run.of("outlet")
        assertEquals(rows.size, o.total, run.text())
        assertEquals(6, o.error, "D1 x2, D2 x2, the twins x2: ${run.text()}")
        assertEquals(8, o.created)
        assertEquals(dry.of("outlet").error, o.error, "the dry run reports the same")
        assertEquals(0, count("app.outlet WHERE code IN ('D1', 'D2') OR name IN ('Shop X', 'Shop Y', 'Shop P', 'Shop Q', 'Twin')"), "no shop of a duplicated code was loaded")
        assertEquals(8, count("app.outlet WHERE code LIKE 'OK%'"))
        assertEquals(6, run.issues["outlet_code_twice_in_the_file"], "every row of D1, D2 and the generated twin code is counted: ${run.text()}")
        // loading again is stable
        val again = load(file, options(batch = 4))
        assertEquals(0, again.of("outlet").created + again.of("outlet").updated)
        assertEquals(6, again.of("outlet").error)
    }

    /**
     * Finding L3. A value a table would refuse is a ROW error that the dry run reports, never an aborted transaction; and when the database refuses a batch
     * anyway, nothing that carries the failure (message, stack trace, cause chain) holds a value of the file.
     */
    @Test
    fun valuesThatATableWouldRefuseAreRowErrorsAndNoFailureCarriesAValueOfTheFile() = withDb {
        val longName = "N".repeat(121)
        val rows = listOf(
            line("G1"), line("bad code!"), line("G2", route = "R 1"), line("G3", name = longName), line("G4", zone = "Z 4"), line("G5", rep = "AB"),
            line("G6", territory = ""), line("a".repeat(33)),
        )
        val file = csv(*rows.toTypedArray()) + "1,Wing 1,11,Division 11,T1,Territory T1\n"   // and a record that is too short
        val dry = load(file, options(dry = true))
        val run = load(file)
        val o = run.of("outlet")
        assertEquals(rows.size + 1, o.total, run.text())
        assertEquals(dry.of("outlet").error, o.error, "the dry run reports the same errors")
        assertEquals(setOf("G1", "G5"), fresh.db.jdbi.withHandle<Set<String>, Exception> { h -> h.createQuery("SELECT code FROM app.outlet").mapTo(String::class.java).set() })
        for (issue in listOf("outlet_code_invalid", "route_code_invalid", "outlet_name_too_long", "zone_code_invalid", "territory_code_missing", "csv_record_has_the_wrong_number_of_cells")) {
            assertTrue((run.issues[issue] ?: 0) >= 1, "$issue in ${run.text()}")
        }
        assertEquals(1, run.rowsUnreadable)
        assertNoPersonalData(run.text())

        // the database refuses a batch anyway (a rule the shared set does not know): the failure carries SQLSTATE and constraint, never a value
        exec("ALTER TABLE app.outlet ADD CONSTRAINT zz_test_block CHECK (name <> 'Blocked Shop Name')")
        val blocked = csv(line("B1", name = "Blocked Shop Name", owner = "Blocked Owner", phone = "01899887766"))
        val e = assertFailsWith<LoaderException> { load(blocked) }
        assertTrue(e.message!!.contains("SQLSTATE 23514") && e.message!!.contains("zz_test_block"), e.message)
        assertNull(e.cause)
        assertNoPersonalData(e.message + "\n" + e.stackTraceToString() + "\n" + generateSequence<Throwable>(e) { it.cause }.joinToString { it.toString() })
        assertTrue(!e.toString().contains("01899887766") && !e.stackTraceToString().contains("Blocked"))
    }

    /**
     * Finding L4. Route, cluster and zone must agree. A zone move is applied as a whole (zone, cluster, route, placement history); a row whose route is in
     * another zone is an error that moves nothing; a row whose cluster does not exist in its zone does not count as "unchanged".
     */
    @Test
    fun aZoneMoveIsAppliedWholeAndARouteOfAnotherZoneIsAnErrorThatMovesNothing() = withDb {
        load(csv(line("X1", zone = "ZA", territory = "TA", route = "RA", cluster = "CA"), line("X2", zone = "ZB", territory = "TB", route = "RB", cluster = "CB", rep = "ab0002")))
        val before = one("SELECT concat_ws('|', z.code, r.code, c.name) FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id JOIN app.route r ON r.id = o.route_id JOIN app.cluster c ON c.id = o.cluster_id WHERE o.code = 'X1'")
        assertEquals("ZA|RA|CA", before)

        // 1. the route says zone A but the row says zone B: an error, X1 is untouched
        val mixed = load(csv(line("X1", zone = "ZB", territory = "TB", route = "RA", cluster = "CB", rep = "ab0002")))
        assertEquals(1, mixed.of("outlet").error, mixed.text())
        assertEquals(1, mixed.issues["route_is_not_in_the_zone_of_the_row"])
        assertEquals(0, mixed.of("outlet").unchanged, "an error is never counted unchanged")
        assertEquals(before, one("SELECT concat_ws('|', z.code, r.code, c.name) FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id JOIN app.route r ON r.id = o.route_id JOIN app.cluster c ON c.id = o.cluster_id WHERE o.code = 'X1'"))

        // 2. zone, route and cluster all say B: the outlet moves as a whole
        val moved = load(csv(line("X1", zone = "ZB", territory = "TB", route = "RB", cluster = "CB", rep = "ab0002"), line("X2", zone = "ZB", territory = "TB", route = "RB", cluster = "CB", rep = "ab0002")), options(businessDate = today.plusDays(1)))
        assertEquals(1, moved.of("outlet").updated, moved.text())
        assertEquals("ZB|RB|CB", one("SELECT concat_ws('|', z.code, r.code, c.name) FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id JOIN app.route r ON r.id = o.route_id JOIN app.cluster c ON c.id = o.cluster_id WHERE o.code = 'X1'"))
        assertEquals(2, count("app.outlet_placement_history h JOIN app.outlet o ON o.id = h.outlet_id WHERE o.code = 'X1'"), "the placement history records the move")
        assertEquals(1, count("app.outlet_placement_history h JOIN app.outlet o ON o.id = h.outlet_id WHERE o.code = 'X1' AND h.valid_to IS NULL"))
        // 3. the zone and the cluster change and the row has NO route: the old route (of the old zone) is cleared, never left behind
        val noRoute = load(csv(line("X1", zone = "ZA", territory = "TA", route = "", cluster = "CA")), options(businessDate = today.plusDays(2)))
        assertEquals(1, noRoute.of("outlet").updated, noRoute.text())
        assertNull(one("SELECT route_id::text FROM app.outlet WHERE code = 'X1'"))
        assertEquals("ZA", one("SELECT z.code FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id WHERE o.code = 'X1'"))
        // no outlet of the database sits in a zone that is not the zone of its route and its cluster
        assertEquals(0, count("app.outlet o LEFT JOIN app.route r ON r.id = o.route_id JOIN app.cluster c ON c.id = o.cluster_id WHERE c.zone_id <> o.zone_id OR (r.id IS NOT NULL AND r.zone_id <> o.zone_id)"))
    }

    // ---- the lows -----------------------------------------------------------------------------------------------------------------------------

    /** One loader at a time; a leftover staging table of a failed run is dropped by the next run; an environment guard in code. */
    @Test
    fun oneLoaderAtATimeTheStagingTableDoesNotSurviveAndTheEnvironmentGuardHolds() = withDb {
        val file = csv(line("L1"))
        fresh.db.jdbi.open().use { other ->
            other.createQuery("SELECT pg_advisory_lock(:k)").bind("k", MasterDataLoader.LOCK_KEY).mapTo(String::class.java).findOne()
            val e = assertFailsWith<LoaderException> { load(file) }
            assertTrue(e.message!!.contains("another master-data load is running"), e.message)
            assertEquals(0, count("app.outlet"))
        }
        // a failed run leaves its staging table; the next run drops it first and drops its own at the end
        exec("CREATE SCHEMA IF NOT EXISTS mdload")
        exec("CREATE UNLOGGED TABLE mdload.outlet_row (leftover text)")
        load(file)
        assertNull(one("SELECT to_regclass('mdload.outlet_row')::text"))
        assertEquals(1, count("app.outlet"))
        // the guard: not the final account and not flagged synthetic -> refused, before anything is read
        val refused = assertFailsWith<LoaderException> { MasterDataLoader(fresh.db, LoadOptions(env = ServerEnv.DEV, dryRun = true)).load { StringReader(file) } }
        assertTrue(refused.message!!.contains("final account") && refused.message!!.contains("synthetic"), refused.message)
        // the final account profile passes the guard
        MasterDataLoader(fresh.db, LoadOptions(env = ServerEnv.PROD, dryRun = true, businessDate = today)).load { StringReader(file) }
    }

    /** A record with more or fewer cells than the header is an error row, not a half row read as empty values. */
    @Test
    fun aShortOrLongRecordIsCountedAsUnreadable() = withDb {
        val ok = line("S1")
        val short = ok.substringBeforeLast(",").substringBeforeLast(",")
        val long = "$ok,extra,cells"
        val run = load(csv(ok, short, long, line("S2")))
        assertEquals(4, run.of("outlet").total)
        assertEquals(2, run.of("outlet").error)
        assertEquals(2, run.rowsUnreadable)
        assertEquals(setOf("S1", "S2"), fresh.db.jdbi.withHandle<Set<String>, Exception> { h -> h.createQuery("SELECT code FROM app.outlet").mapTo(String::class.java).set() })
    }

    /** A rep id that names an existing user of another role is a row error and that user is not overwritten; a held cover rep is counted. */
    @Test
    fun aRepNeverOverwritesAUserOfAnotherRoleAndHeldCoversAreCounted() = withDb {
        exec("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('ab0009', 'Existing Tso', 'TSO', false)")
        val file = csv(line("R1", rep = "ab0009"), line("R2", rep = "ab0001"), line("R2b", rep = "ab0001"), line("R2c", rep = "xy1"))
        val run = load(file)
        assertEquals(1, run.of("sales_rep").error, run.text())
        assertEquals("Existing Tso|TSO", one("SELECT full_name || '|' || role FROM app.app_user WHERE username = 'ab0009'"), "the TSO is untouched")
        assertEquals(0, count("app.route_assignment a JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'ab0009'"))
        // route R1 has rep ab0009 (another role: error), ab0001 (primary), xy1 (a held cover: 3 characters, the gate is closed)
        assertEquals(1, run.of("sales_rep").held)
        assertEquals(1, run.of("route_assignment").held, "the held cover is counted: ${run.text()}")
        assertEquals(1, run.of("route_assignment").error)
    }

    /** An outlet archived because its route had no rep is reactivated when a later file gives the route a rep; one closed by hand is left alone. */
    @Test
    fun anOutletArchivedForNoRepIsReactivatedWhenTheRouteGetsOne() = withDb {
        load(csv(line("A1", rep = ""), line("A2", rep = "")))
        assertEquals(2, count("app.outlet WHERE status = 'archived'"))
        exec("UPDATE app.outlet SET status = 'closed', closed_at = now() WHERE code = 'A2'")
        val later = load(csv(line("A1", rep = "ab0001"), line("A2", rep = "ab0001")))
        assertEquals("active", one("SELECT status FROM app.outlet WHERE code = 'A1'"))
        assertEquals("closed", one("SELECT status FROM app.outlet WHERE code = 'A2'"), "an outlet closed by hand stays closed")
        assertEquals(1, later.issues["outlet_reactivated_its_route_has_a_rep_now"])
    }

    /** The load is run with an EARLIER business date than the open placement row: the row is corrected in place, never closed before it opened. */
    @Test
    fun aPlacementThatStartsAfterTheBusinessDateIsCorrectedInPlace() = withDb {
        load(csv(line("P1", cluster = "C1")), options(businessDate = today.plusDays(10)))
        val run = load(csv(line("P1", cluster = "C2")), options(businessDate = today))
        assertEquals(1, run.of("outlet").updated, run.text())
        assertEquals(1, count("app.outlet_placement_history WHERE valid_to IS NULL"))
        assertEquals("C2", one("SELECT c.name FROM app.outlet_placement_history h JOIN app.cluster c ON c.id = h.cluster_id WHERE h.valid_to IS NULL"))
        assertEquals(1, count("app.outlet_placement_history"))
    }

    /** The owner's coordinates switch confirms only the pins inside Bangladesh. */
    @Test
    fun theCoordinatesSwitchConfirmsOnlyThePinsInsideBangladesh() = withDb {
        val (csv, facts) = SyntheticMaster.csv(800, wings = 1, seed = 41)
        val run = load(csv, options(confirm = true))
        assertEquals(0, run.of("outlet").error)
        assertEquals(count("app.outlet") - facts.noCoordinates - facts.outsideBangladesh, count("app.outlet WHERE location_confirmed"))
        assertEquals(0, count("app.outlet WHERE location_confirmed AND (lat < 20.5 OR lat > 26.7 OR lng < 88.0 OR lng > 92.7)"))
    }

    /** B2 (D-MD-24): a rep whose id is shorter than 4 characters is HELD, with its id in the report, and no user is created for it. */
    @Test
    fun aRepIdTooShortForTheInstalledAppIsHeldNotCreated() = withDb {
        val (csv, facts) = SyntheticMaster.csv(1_500, wings = 1, seed = 31)
        assertTrue(facts.shortRepIds.isNotEmpty())
        val run = load(csv)
        assertEquals(facts.shortRepIds.size, run.of("sales_rep").held, run.text())
        facts.shortRepIds.forEach { assertEquals(0, count("app.app_user WHERE username = '$it'")) }
        assertTrue(run.of("route_assignment").held > 0)
        val allowed = load(csv, options(allowShort = true))
        assertEquals(facts.shortRepIds.size, allowed.of("sales_rep").created)
    }

    /** The gate's own open side is the config key, never an option: with it set and the minimum app version reached, a 3-character rep loads. */
    @Test
    fun theShortRepIdGateOpensWithTheConfigKeyAndNothingElse() = withDb {
        val file = csv(line("K1", rep = "xy1"))
        assertEquals(1, load(file).of("sales_rep").held)
        assertEquals(0, count("app.app_user WHERE username = 'xy1'"))
        exec("UPDATE app.cfg_key SET default_value = '40'::jsonb WHERE key = 'cfg.release.short_rep_id_version_code'")
        assertEquals(1, load(file).of("sales_rep").held, "the release is named but the minimum app version has not reached it")
        exec("UPDATE app.cfg_key SET default_value = '{\"sr\": 40, \"amo\": 40, \"tso\": 1}'::jsonb WHERE key = 'cfg.release.min_version_code'")
        val open = load(file)
        assertEquals(1, open.of("sales_rep").created, open.text())
        assertEquals(1, count("app.app_user WHERE username = 'xy1'"))
    }

    /** Outlets of a route with no visit plan are held when the owner has not said they are stored. */
    @Test
    fun outletsOfARouteWithNoVisitPlanAreHeldWhenTheOwnerHasNotSaidTheyAreStored() = withDb {
        val file = csv(line("V1"), line("V2", route = "R2", freq = "", day = ""))
        val held = load(file, options(storeNoPlan = false))
        assertEquals(1, held.of("outlet").held)
        assertEquals(1, held.of("outlet").created)
        assertEquals(0, count("app.outlet WHERE code = 'V2'"))
        val stored = load(file)
        assertEquals(1, stored.of("outlet").created, stored.text())
        assertEquals("none", one("SELECT r.visit_kind FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE o.code = 'V2'"))
    }
}
