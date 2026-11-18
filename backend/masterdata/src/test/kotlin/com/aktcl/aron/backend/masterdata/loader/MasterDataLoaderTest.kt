package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.ServerEnv
import com.aktcl.aron.backend.platform.Settings
import org.jdbi.v3.core.Handle
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

    /** The process settings the loader reads its environment from: the test account runs ARON_ENV=dev, the final account prod. */
    private val devSettings = Settings.load(mapOf("ARON_ROLE" to "migrate", "ARON_ENV" to "dev"))
    private val prodSettings = Settings.load(mapOf("ARON_ROLE" to "migrate", "ARON_ENV" to "prod", "ARON_DB_URL" to "jdbc:postgresql://localhost/none"))

    private fun options(
        runId: UUID = UUID.randomUUID(), dry: Boolean = false, batch: Int = 500, confirm: Boolean = false, allowShort: Boolean = false,
        businessDate: LocalDate = today, storeNoPlan: Boolean = true, afterBatch: ((Int) -> Unit)? = null, inside: ((Handle, Int, Int) -> Unit)? = null,
    ) = LoadOptions(
        batchSize = batch, coordinatesConfirmed = confirm, dryRun = dry, runId = runId, businessDate = businessDate, storeOutletsWithoutVisitPlan = storeNoPlan,
    ).forTests(allowShortRepIds = allowShort, afterBatch = afterBatch, insideBatch = inside)

    private fun load(csv: String, o: LoadOptions = options()): LoadReport = MasterDataLoader(fresh.db, devSettings, o).load { StringReader(csv) }

    private fun <T> sql(sql: String, type: Class<T>): T = fresh.db.jdbi.withHandle<T, Exception> { h -> h.createQuery(sql).mapTo(type).one() }
    private fun count(sql: String): Int = sql("SELECT count(*)::int FROM $sql", Int::class.javaObjectType)
    private fun one(sql: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }
    private fun exec(sql: String) { fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) } }

    /** Runs [sql] on ANOTHER connection, committed at once (JDBI reuses the current thread's handle for a nested useHandle, which would run inside the loader's own transaction). */
    private fun execElsewhere(sql: String) { fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } } }

    // ---- hand-made rows for the tests that need to control every value --------------------------------------------------------------------------

    private val head = SyntheticMaster.HEADER.joinToString(",")

    /** One input row with the 32 columns of the profile; every argument has a sensible default so a test names only what it is about. */
    private fun line(
        code: String, zone: String = "Z1", territory: String = "T1", route: String = "R1", cluster: String = "C1", name: String = "Shop $code", owner: String = "Owner $code",
        rep: String = "ab0001", phone: String = "01711000001", freq: String = "6", day: String = "Daily", lat: String = "23.70000000", lng: String = "90.40000000",
        channel: String = "GT Channel", sub: String = "GT", routeName: String = "Route $route",
    ) = listOf(
        "1", "Wing 1", "11", "Division 11", territory, "Territory $territory", "", "", zone, "Zone $zone", route, routeName, freq, day, rep, if (rep.isEmpty()) "" else "Rep $rep",
        "", code, name, owner, phone, "", cluster, "Transit Hub", if (rep.isEmpty()) "" else "Active", lat, lng, "Rural", channel, sub, "2024-03-05 10:15:00", "",
    ).joinToString(",")

    private fun csv(vararg rows: String) = (listOf(head) + rows).joinToString("\n") + "\n"

    /** The same text under another run id must not be accepted by accident: text of any error or report that could carry a value of the file. */
    private fun assertNoPersonalData(text: String) {
        for (forbidden in listOf("Outlet ", "Owner ", "Rep ", "N/A", "Cluster ", "Shop ", "Blocked")) assertTrue(!text.contains(forbidden), "carries a value of the file '$forbidden':\n$text")
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
        assertEquals(facts.outletsWithoutRep + facts.outletsOnHeldRepRoutes, count("app.outlet WHERE status = 'archived'"), "no rep, or only a rep that is held for the installed app")
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
        val refused = assertFailsWith<LoaderException> { MasterDataLoader(fresh.db, devSettings, LoadOptions(dryRun = true)).load { StringReader(file) } }
        assertTrue(refused.message!!.contains("final account") && refused.message!!.contains("synthetic"), refused.message)
        // the final account profile passes the guard; the environment is the process settings' (round 3), and the synthetic flag is not part of the public API
        MasterDataLoader(fresh.db, prodSettings, LoadOptions(dryRun = true, businessDate = today)).load { StringReader(file) }
        // The guard is real only if NO public constructor of another module can take the test switches or an environment: a constructor property marked internal
        // still lets any module pass it, so the public constructor must not have those parameters at all (round 3 review).
        val publicParams = LoadOptions::class.constructors.filter { it.visibility == kotlin.reflect.KVisibility.PUBLIC }.flatMap { c -> c.parameters.map { it.name } }
        assertTrue(publicParams.isNotEmpty() && publicParams.containsAll(listOf("batchSize", "runId", "dryRun")), "the public constructor is the operator's: $publicParams")
        for (forbidden in listOf("syntheticTestData", "shortRepIdsAllowedForTests", "afterBatchForTests", "insideBatchForTests", "env")) {
            assertTrue(forbidden !in publicParams, "a public constructor parameter '$forbidden' would let any module bypass the guard: $publicParams")
        }
        assertTrue(LoadOptions::class.members.filter { it.visibility == kotlin.reflect.KVisibility.PUBLIC }.none { it.name in setOf("syntheticTestData", "shortRepIdsAllowedForTests", "forTests") })
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
        assertEquals(listOf("row 2", "row 3"), run.samples["csv_record_has_the_wrong_number_of_cells"]!!.toList(), "the owner can find a malformed row by its number")
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

    // ---- round 3 (the lead's review of e18090f1..1cd7892d) ----------------------------------------------------------------------------------------

    /** Everything a report says that a resume must reproduce: the counts of every entity, every issue and its example codes (not the timings or the batch bookkeeping). */
    private fun shape(r: LoadReport) = listOf(r.rowsRead.toString(), r.rowsUnreadable.toString()) + r.entities.map { (k, c) -> "$k $c" } + r.issues.map { (k, n) -> "issue $k $n ${r.samples[k]}" }

    private fun loadOn(db: com.aktcl.aron.backend.platform.Database, csv: String, o: LoadOptions): LoadReport = MasterDataLoader(db, devSettings, o).load { StringReader(csv) }

    /**
     * R1. A resumed run reports a skipped batch for what it was: the created, updated, unchanged and error counts and the issue codes it stored, so the final
     * report of an interrupted-and-resumed run EQUALS the report of an uninterrupted one. The skipped batches here hold error rows (a route stored in another
     * zone), rows that were created, and the hierarchy, a new route and a new rep are written before the interruption.
     */
    @Test
    fun aResumedRunReportsExactlyWhatAnUninterruptedRunReports() = withDb {
        val base = csv(
            line("X1", zone = "ZA", territory = "TA", route = "RA", cluster = "CA"), line("X2", zone = "ZB", territory = "TB", route = "RB", cluster = "CB", rep = "ab0002"),
            line("X3", zone = "ZA", territory = "TA", route = "RA", cluster = "CA"), line("X4", zone = "ZA", territory = "TA", route = "RA", cluster = "CA"),
        )
        val second = csv(
            // batch 1: two rows whose route RA is stored in zone ZA but whose zone says ZB (errors), two new outlets on a NEW route with a NEW rep
            line("X1", zone = "ZB", territory = "TB", route = "RA", cluster = "CB"), line("X3", zone = "ZB", territory = "TB", route = "RA", cluster = "CB"),
            line("N1", zone = "ZA", territory = "TA", route = "RN", cluster = "CA", rep = "ab0003"), line("N2", zone = "ZA", territory = "TA", route = "RN", cluster = "CA", rep = "ab0003"),
            // batch 2: new outlets and one that changes nothing
            line("N3", zone = "ZA", territory = "TA", route = "RN", cluster = "CA", rep = "ab0003"), line("N4", zone = "ZA", territory = "TA", route = "RN", cluster = "CA", rep = "ab0003"),
            line("N5", zone = "ZA", territory = "TA", route = "RN", cluster = "CA", rep = "ab0003"), line("X2", zone = "ZB", territory = "TB", route = "RB", cluster = "CB", rep = "ab0002"),
            // batch 3
            line("N6", zone = "ZA", territory = "TA", route = "RN", cluster = "CA", rep = "ab0003"), line("N7", zone = "ZA", territory = "TA", route = "RN", cluster = "CA", rep = "ab0003"),
        )
        load(base)
        val twin = FreshDb.create()
        try {
            loadOn(twin.db, base, options())
            val uninterrupted = loadOn(twin.db, second, options(batch = 4))
            assertEquals(2, uninterrupted.of("outlet").error, uninterrupted.text())
            assertEquals(2, uninterrupted.issues["route_is_not_in_the_zone_of_the_row"], uninterrupted.text())

            val runId = UUID.randomUUID()
            assertFailsWith<IllegalStateException> { load(second, options(runId = runId, batch = 4, afterBatch = { if (it == 2) throw IllegalStateException("interrupted") })) }
            val resumed = load(second, options(runId = runId, batch = 4))
            assertEquals(2, resumed.batchesSkipped, resumed.text())
            assertEquals(1, resumed.batchesApplied)
            assertTrue(resumed.stepsSkipped > 0, "the hierarchy written before the interruption is not written again: ${resumed.text()}")
            assertEquals(shape(uninterrupted), shape(resumed), "the resumed report is the uninterrupted report")
            assertEquals(resumed.of("outlet").total.toLong(), resumed.rowsRead)
            assertEquals(1, resumed.of("route").created, "the route created before the interruption is still reported created: ${resumed.text()}")
            assertEquals(1, resumed.of("sales_rep").created)
            assertNoPersonalData(resumed.text())
            // and the database is the same
            for (t in listOf("app.outlet", "app.route", "app.route_assignment", "app.app_user")) assertEquals(count(t), twin.db.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT count(*)::int FROM $t").mapTo(Int::class.java).one() }, t)
        } finally { twin.close() }
    }

    /**
     * R2. A run id is bound to its OPTIONS as well as its file: a resume under other options is refused, with a message that names the options and no value,
     * and nothing is written. The original options resume fine.
     */
    @Test
    fun aResumeUnderOtherOptionsIsRefusedAndNamesNoValue() = withDb {
        val (csv, _) = SyntheticMaster.csv(600, wings = 1, seed = 5)
        val runId = UUID.randomUUID()
        assertFailsWith<IllegalStateException> { load(csv, options(runId = runId, batch = 200, afterBatch = { if (it == 1) throw IllegalStateException("interrupted") })) }
        val partial = count("app.outlet")
        for ((changed, name) in listOf(
            options(runId = runId, batch = 200, confirm = true) to "coordinatesConfirmed",
            options(runId = runId, batch = 200, businessDate = today.plusDays(1)) to "businessDate",
            options(runId = runId, batch = 200, storeNoPlan = false) to "storeOutletsWithoutVisitPlan",
            options(runId = runId, batch = 100) to "batchSize",
        )) {
            val refused = assertFailsWith<LoaderException> { load(csv, changed) }
            assertTrue(refused.message!!.contains("different options") && refused.message!!.contains(name) && refused.message!!.contains("NEW run id"), refused.message)
            assertTrue(!refused.message!!.contains("true") && !refused.message!!.contains("false") && !refused.message!!.contains(today.toString()), "no option value: ${refused.message}")
            assertEquals(partial, count("app.outlet"), "a refused resume writes nothing")
        }
        val resumed = load(csv, options(runId = runId, batch = 200))
        assertEquals(1, resumed.batchesSkipped)
        assertEquals(600, count("app.outlet"))
    }

    /** Pass 2 reads the copy pass 1 kept, never the caller's file again: a file replaced during the run is never half applied. */
    @Test
    fun aFileReplacedDuringTheRunIsNeverHalfApplied() = withDb {
        var opened = 0
        val first = csv(line("F1"), line("F2"))
        val replaced = csv(line("F1"), line("F2"), line("F3"), line("REPLACED"))
        val run = MasterDataLoader(fresh.db, devSettings, options()).load { StringReader(if (opened++ == 0) first else replaced) }
        assertEquals(1, opened, "the file is opened once")
        assertEquals(2, run.of("outlet").created)
        assertEquals(setOf("F1", "F2"), fresh.db.jdbi.withHandle<Set<String>, Exception> { h -> h.createQuery("SELECT code FROM app.outlet").mapTo(String::class.java).set() })
    }

    /**
     * A row pass 2 rejects moves nothing: a rep's home zone does not follow rows that name a route stored in another zone, and that route is not renamed or
     * re-planned; the pair of the rep and that route is an ERROR, never "unchanged".
     */
    @Test
    fun aRejectedRowMovesNeitherTheRepNorTheRouteOfAnotherZone() = withDb {
        load(csv(line("Q1", zone = "ZA", territory = "TA", route = "RA", cluster = "CA", day = "Daily", freq = "6"), line("Q2", zone = "ZA", territory = "TA", route = "RA", cluster = "CA", day = "Daily", freq = "6")))
        val routeBefore = one("SELECT concat_ws('|', name, display_label, visit_kind, visit_days_mask) FROM app.route WHERE code = 'RA'")
        assertEquals("ZA", one("SELECT z.code FROM app.app_user u JOIN app.zone z ON z.id = u.home_zone_id WHERE u.username = 'ab0001'"))
        // the whole route is re-zoned, renamed and re-planned in the file; the portal still has it in ZA
        val run = load(csv(
            line("Q1", zone = "ZB", territory = "TB", route = "RA", cluster = "CB", routeName = "Renamed", day = "Monday", freq = "1"),
            line("Q2", zone = "ZB", territory = "TB", route = "RA", cluster = "CB", routeName = "Renamed", day = "Monday", freq = "1"),
        ))
        assertEquals(2, run.of("outlet").error, run.text())
        assertEquals(2, run.issues["route_is_not_in_the_zone_of_the_row"])
        assertEquals(routeBefore, one("SELECT concat_ws('|', name, display_label, visit_kind, visit_days_mask) FROM app.route WHERE code = 'RA'"), "the route is not renamed or re-planned")
        assertEquals(1, run.of("route").error, "a route stored in another zone is an error, not 'unchanged': ${run.text()}")
        assertEquals(0, run.of("route").unchanged + run.of("route").updated)
        assertEquals("ZA", one("SELECT z.code FROM app.app_user u JOIN app.zone z ON z.id = u.home_zone_id WHERE u.username = 'ab0001'"), "the rep's home zone follows no rejected row")
        assertEquals(1, run.of("route_assignment").error, run.text())
        assertEquals(0, run.of("route_assignment").unchanged)
        assertEquals(1, run.issues["route_assignment_route_is_in_another_zone_than_the_file_says"])
    }

    /** A different primary rep in the file for a route that has an open primary is HELD with an issue code (never "unchanged"); a pair whose route is gone is an error. */
    @Test
    fun aDifferentPrimaryRepIsReportedAndAPairWhoseRouteWasNotCreatedIsAnError() = withDb {
        load(csv(line("P1", route = "R1", rep = "ab0001"), line("P2", route = "R2", rep = "ab0002")))
        val run = load(csv(line("P1", route = "R1", rep = "ab0005"), line("P2", route = "R2", rep = "ab0002")))
        val a = run.of("route_assignment")
        assertEquals(1, a.held, run.text())
        assertEquals(1, a.unchanged, "only the pair that is already open is unchanged")
        assertEquals(0, a.created)
        assertEquals(1, run.issues["route_assignment_primary_differs_from_the_open_primary"])
        assertEquals(listOf("R1"), run.samples["route_assignment_primary_differs_from_the_open_primary"]!!.toList())
        assertEquals(0, count("app.route_assignment a JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'ab0005'"), "the new rep gets nothing: the portal owns a reassignment")
        // a route the portal removed between the hierarchy and the assignments (a test hook plays the portal): the pair is an error
        val file = csv(line("W1", route = "R9", rep = "ab0007"))
        val lost = load(file, options(afterBatch = { exec("UPDATE app.route SET code = 'R9moved' WHERE code = 'R9'") }))
        assertEquals(1, lost.of("route_assignment").error, lost.text())
        assertEquals(0, lost.of("route_assignment").unchanged)
        assertEquals(1, lost.issues["route_assignment_route_was_not_created"])
    }

    /**
     * One consistent snapshot per batch. A concurrent portal edit between the batch's problem count and its writes (here a cluster renamed by another
     * connection) must never turn a row into an uncounted one: the row is counted created (the snapshot never saw the edit) or, after a retry on a fresh
     * snapshot, counted an error for what it has become. It is never "unchanged" while missing from the database.
     */
    @Test
    fun aConcurrentPortalEditCannotTurnARowIntoAnUncountedOne() = withDb {
        load(csv(line("E1", cluster = "C1")))
        var edited = false
        val file = csv(line("E1", cluster = "C1"), line("Y1", cluster = "C1", name = "Shop Y1"))
        var isolation: String? = null
        val run = load(file, options(inside = { h, _, attempt ->
            isolation = h.createQuery("SHOW transaction_isolation").mapTo(String::class.java).one()
            if (attempt == 1 && !edited) { edited = true; execElsewhere("UPDATE app.cluster SET name = 'C1 renamed' WHERE name = 'C1'") }
        }))
        assertTrue(edited)
        assertEquals("repeatable read", isolation, "an outlet batch reads one snapshot")
        val o = run.of("outlet")
        assertEquals(2, o.total, run.text())
        assertEquals(count("app.outlet WHERE code = 'Y1'"), o.created, "created is exactly what exists: ${run.text()}")
        if (o.created == 0) {
            // the batch failed on the stale snapshot (SQLSTATE 40001) and ran again: both rows are now counted for what they have become
            assertEquals(2, o.error, run.text())
            assertEquals(2, run.issues["cluster_does_not_resolve"])
        } else assertEquals(1, o.unchanged, run.text())
    }

    /** The advisory lock is re-checked before every transaction: a lock lost to a dropped connection stops the run (it is resumable) instead of racing a second loader. */
    @Test
    fun aLostAdvisoryLockStopsTheRun() = withDb {
        val (csv, _) = SyntheticMaster.csv(900, wings = 1, seed = 9)
        val e = assertFailsWith<LoaderException> {
            load(csv, options(batch = 300, afterBatch = {
                if (it == 1) exec("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype = 'advisory' AND ((classid::bigint << 32) | objid::bigint) = ${MasterDataLoader.LOCK_KEY}")
            }))
        }
        assertTrue(e.message!!.contains("advisory lock") && e.message!!.contains("was lost"), e.message)
        assertEquals(300, count("app.outlet"), "nothing was written after the lock was lost")
    }

    /** A held rep (3 characters, the gate closed) or a rep that is another role's user does not make a route "have a rep"; a route with a loadable cover does. */
    @Test
    fun aHeldOrForeignRepDoesNotGiveARouteARep() = withDb {
        exec("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('ab0009', 'Existing Tso', 'TSO', false)")
        load(csv(
            line("H1", route = "RH", rep = "xy1"), line("H2", route = "RF", rep = "ab0009"), line("H3", route = "RG", rep = "ab0001"),
            line("H4", route = "RC", rep = "xy1"), line("H5", route = "RC", rep = "ab0001"),
        ))
        assertEquals("archived", one("SELECT status FROM app.outlet WHERE code = 'H1'"), "held rep")
        assertEquals("archived", one("SELECT status FROM app.outlet WHERE code = 'H2'"), "another role's user")
        assertEquals("active", one("SELECT status FROM app.outlet WHERE code = 'H3'"))
        assertEquals("active", one("SELECT status FROM app.outlet WHERE code = 'H4'"), "a route with a loadable cover")
    }

    /** The case-insensitive sub-channel join cannot fan out: a portal that holds 'GT' and 'gt' still loads one outlet per row, and the exact case wins. */
    @Test
    fun twoSubChannelsThatDifferOnlyInCaseDoNotDuplicateARow() = withDb {
        exec("INSERT INTO app.sub_channel (channel, code, name) VALUES ('GT', 'gt', 'gt twin')")
        val run = load(csv(line("T1", sub = "GT"), line("T2", sub = "gt")))
        assertEquals(2, run.of("outlet").created, run.text())
        assertEquals("GT", one("SELECT s.code FROM app.outlet o JOIN app.sub_channel s ON s.id = o.sub_channel_id WHERE o.code = 'T1'"))
        assertEquals("gt", one("SELECT s.code FROM app.outlet o JOIN app.sub_channel s ON s.id = o.sub_channel_id WHERE o.code = 'T2'"))
        assertEquals(0, load(csv(line("T1", sub = "GT"), line("T2", sub = "gt"))).of("outlet").updated)
    }

    /** An outlet that already holds the retired Silver tier keeps it when the file still says Silver (as the portal does); the text is not turned into a drop. */
    @Test
    fun anOutletThatHoldsTheRetiredTierKeepsItWhenTheFileStillSaysSo() = withDb {
        val file = csv(line("S1", channel = "Astha", sub = "Silver"))
        assertEquals(1, load(file).of("outlet").created)
        assertNull(one("SELECT sub_channel_id::text FROM app.outlet WHERE code = 'S1'"), "a new outlet never gets the retired tier")
        exec("UPDATE app.outlet SET sub_channel_id = (SELECT id FROM app.sub_channel WHERE code = 'Silver') WHERE code = 'S1'")
        val again = load(file)
        assertEquals(0, again.of("outlet").updated, again.text())
        assertEquals("Silver", one("SELECT s.code FROM app.outlet o JOIN app.sub_channel s ON s.id = o.sub_channel_id WHERE o.code = 'S1'"))
    }

    /** Case variants of a code and codeless rows are reported (the rows still load: the table keys codes case-sensitively); the report says unreadable rows are part of the errors. */
    @Test
    fun caseVariantsAndGeneratedCodesAreReported() = withDb {
        val run = load(csv(line("cv1"), line("CV1"), line("OTHER"), line("", name = "Anon")))
        assertEquals(4, run.of("outlet").created, run.text())
        assertEquals(2, run.issues["outlet_code_differs_from_another_only_in_case"], run.text())
        assertEquals(setOf("cv1", "CV1"), run.samples["outlet_code_differs_from_another_only_in_case"]!!.toSet())
        assertEquals(1, run.issues["outlet_created_under_a_generated_code"])
        // the same shop with an edited name is a NEW generated code: a second shop, and the report line says so
        val later = load(csv(line("cv1"), line("CV1"), line("OTHER"), line("", name = "Anon edited")))
        assertEquals(1, later.of("outlet").created)
        assertEquals(1, later.issues["outlet_created_under_a_generated_code"], later.text())
        assertEquals(2, count("app.outlet WHERE code LIKE 'gen.%'"))
        val short = load(csv(line("U1"), line("U2").substringBeforeLast(",")))
        assertTrue(short.text().contains("of which unreadable: 1") && short.text().contains("counted among the outlet errors"), short.text())
    }

    /** A kill -9 leaves the gzip copy of the file (personal data) in the temp directory: the next run deletes every stale copy before it makes its own. */
    @Test
    fun aStaleCopyOfAFileLeftByAKilledRunIsSweptAtStart() = withDb {
        val stale = java.nio.file.Files.createTempFile("aron-masterdata-", ".csv.gz")
        try {
            assertTrue(java.nio.file.Files.exists(stale))
            load(csv(line("W1")))
            assertTrue(!java.nio.file.Files.exists(stale), "the stale copy of an earlier run was deleted")
        } finally { java.nio.file.Files.deleteIfExists(stale) }
    }

    /**
     * The lock is asked again inside the batch's transaction, before its commit: a holder connection that is dropped DURING a batch stops that batch from
     * committing, so a second loader that may have started in the gap never meets a half-run of this one.
     */
    @Test
    fun aLockLostDuringABatchStopsThatBatchFromCommitting() = withDb {
        val (csv, _) = SyntheticMaster.csv(600, wings = 1, seed = 13)
        val e = assertFailsWith<LoaderException> {
            load(csv, options(batch = 300, inside = { _, batchNo, _ ->
                if (batchNo == 1) execElsewhere("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype = 'advisory' AND ((classid::bigint << 32) | objid::bigint) = ${MasterDataLoader.LOCK_KEY}")
            }))
        }
        assertTrue(e.message!!.contains("was lost"), e.message)
        assertEquals(0, count("app.outlet"), "the batch that was running when the lock was lost did not commit")
    }
}
