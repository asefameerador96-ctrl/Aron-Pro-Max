package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.platform.FreshDb
import org.junit.jupiter.api.TestInstance
import java.io.StringReader
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bulk loader (push 2) on SYNTHETIC data shaped like the owner's October workbook. The real workbook is never loaded here or into the test account:
 * it is fleet-sized personal data (standing rules 1 and 8, `docs/reference/master-data-profile-2026-10.md` s5).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MasterDataLoaderTest {
    /** A database of its own per test: a load writes the whole hierarchy, so two tests must not see each other's rows. */
    private lateinit var fresh: FreshDb

    private fun withDb(block: () -> Unit) {
        fresh = FreshDb.create()
        try { block() } finally { fresh.close() }
    }

    private val today = LocalDate.parse("2026-11-13")   // date-pin-ok: the load's business date is an input, not a clock read

    private fun options(
        runId: UUID = UUID.randomUUID(), dry: Boolean = false, batch: Int = 500, confirm: Boolean = false, allowShort: Boolean = false,
    ) = LoadOptions(batchSize = batch, coordinatesConfirmed = confirm, dryRun = dry, runId = runId, businessDate = today, allowShortRepIds = allowShort)

    private fun load(csv: String, o: LoadOptions = options()): LoadReport = MasterDataLoader(fresh.db, o).load { StringReader(csv) }

    private fun <T> sql(sql: String, type: Class<T>): T = fresh.db.jdbi.withHandle<T, Exception> { h -> h.createQuery(sql).mapTo(type).one() }
    private fun count(sql: String): Int = sql("SELECT count(*)::int FROM $sql", Int::class.javaObjectType)
    private fun one(sql: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }

    /** The whole run on 2,000 synthetic rows: a dry run writes nothing, the load writes everything, and every input row is counted exactly once. */
    @Test
    fun aDryRunWritesNothingAndTheLoadLoadsEveryRowExactlyOnce() = withDb {
        val (csv, facts) = SyntheticMaster.csv(2_000, seed = 7)
        val before = count("app.outlet")
        val dry = load(csv, options(dry = true))
        assertEquals(facts.rows.toLong(), dry.rowsRead)
        assertEquals(before, count("app.outlet"), "a dry run writes nothing")
        assertEquals(0, count("app.route"))
        assertTrue(dry.of("outlet").created > 0 && dry.of("route").created > 0)
        assertTrue(dry.text().contains("DRY RUN"))

        val run = load(csv)
        val o = run.of("outlet")
        assertEquals(facts.rows.toLong(), run.rowsRead)
        assertEquals(facts.rows, o.total, "every input row is counted exactly once: ${run.text()}")
        assertEquals(facts.rows - o.error, count("app.outlet"))
        assertEquals(0, o.error, run.text())
        assertEquals(0, o.unchanged)
        // the hierarchy is there, each level once
        for (level in listOf("wing", "division", "territory", "house", "zone", "route", "cluster")) {
            assertEquals(run.of(level).created, count("app.$level"), "$level rows")
            assertTrue(run.of(level).created > 0, "$level")
        }
        assertEquals(count("app.zone"), count("app.zone z JOIN app.territory t ON t.id = z.territory_id"), "every zone hangs under a territory")
        assertEquals(0, run.issues.getOrDefault("outlet_code_twice_in_the_file", 0), "the synthetic file must carry unique outlet codes")
        // a route with no visit plan is stored as such and plans nothing; its outlets are stored (the owner's switch)
        assertTrue(count("app.route WHERE visit_kind = 'none' AND visit_days_mask = 0") > 0)
        assertEquals("No visit plan", one("SELECT display_label FROM app.route WHERE visit_kind = 'none' LIMIT 1"))
        assertTrue(count("app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.visit_kind = 'none'") > 0)
        // outlets of a route with no rep take the owner's default status; the others are active
        assertEquals(facts.outletsWithoutRep, count("app.outlet WHERE status = 'archived'"))
        // the field's values that we cannot use are kept, never lost (D-MD-23)
        assertTrue(count("app.outlet WHERE source_contact_number IS NOT NULL") > 0)
        assertTrue(count("app.outlet WHERE source_sub_channel IS NOT NULL") > 0)
        assertEquals("2024-03-05 04:15:00+00", one("SELECT source_created_at::text FROM app.outlet ORDER BY id LIMIT 1"))
        // phones are repaired on every shape
        assertEquals(0, count("app.outlet WHERE contact_number IS NOT NULL AND contact_number !~ '^01[3-9][0-9]{8}$'"))
        // pins: inside Bangladesh and unconfirmed (the owner's switch was off), outside the box stored but never confirmed
        assertEquals(0, count("app.outlet WHERE location_confirmed"))
        assertEquals(facts.noCoordinates, count("app.outlet WHERE lat IS NULL"))
        // every outlet has exactly one open placement row and one location-history row
        assertEquals(count("app.outlet"), count("app.outlet_placement_history WHERE valid_to IS NULL"))
        assertEquals(count("app.outlet"), count("app.outlet_location_history"))
        // the exceptions are reported by code, never by name
        assertTrue(run.issues.getOrDefault("route_with_more_than_one_rep", 0) >= facts.routesWithTwoReps.size, run.text())
        assertTrue(run.issues.getOrDefault("route_visit_pattern_includes_friday", 0) >= facts.fridayRoutes.size)
        assertTrue(run.issues.getOrDefault("zone_with_two_parents", 0) >= facts.zonesWithTwoTerritories.size)
        assertTrue(run.issues.getOrDefault("outlet_row_without_a_code", 0) == facts.codelessRows)
        assertTrue(run.issues.getOrDefault("coordinates_outside_bangladesh", 0) == facts.outsideBangladesh)
        assertNoPersonalData(run)

        // reps and assignments: one SR per rep id, one primary per route, the extra rep of a two-rep route a cover
        assertEquals(run.of("sales_rep").created, count("app.app_user WHERE role = 'SR'"))
        assertEquals(0, count("app.app_user WHERE role = 'SR' AND password_hash IS NOT NULL"))
        assertEquals(count("app.route_assignment WHERE kind = 'primary' AND valid_to IS NULL"), count("app.route r WHERE EXISTS (SELECT 1 FROM app.route_assignment a WHERE a.route_id = r.id AND a.kind = 'primary')"))
        assertEquals(facts.routesWithTwoReps.size, count("app.route_assignment WHERE kind = 'cover'"))
    }

    /** The same file again changes NOTHING: no row, no version bump, no new history row; every row is reported unchanged. */
    @Test
    fun loadingTheSameFileAgainChangesNothing() = withDb {
        val (csv, facts) = SyntheticMaster.csv(900, wings = 1, seed = 11)
        load(csv)
        val outlets = count("app.outlet")
        val versions = one("SELECT md5(string_agg(code || ':' || version, ',' ORDER BY code)) FROM app.outlet")
        val history = count("app.outlet_location_history") to count("app.outlet_placement_history")
        val assignments = count("app.route_assignment")
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

    /** A run interrupted part-way is resumed under the same run id: the batches that finished are skipped, the rest are applied, and the result is the same. */
    @Test
    fun anInterruptedRunIsResumedAndTheResultIsTheSame() = withDb {
        val (csv, _) = SyntheticMaster.csv(1_200, wings = 1, seed = 23)
        val lines = csv.lines()
        val head = lines.first()
        val part = (listOf(head) + lines.drop(1).take(620)).joinToString("\n") + "\n"
        val runId = UUID.randomUUID()
        val first = load(part, options(runId = runId, batch = 300))
        assertEquals(620, first.rowsRead.toInt())
        val afterPart = count("app.outlet")
        val rest = load(csv, options(runId = runId, batch = 300))
        assertEquals(2, rest.batchesSkipped, "the two full batches of the interrupted run are skipped: ${rest.text()}")
        assertTrue(rest.batchesApplied >= 2)
        assertEquals(1_200, count("app.outlet"), "resuming lost no row")
        assertTrue(count("app.outlet") > afterPart)
        // and the same file a third time changes nothing at all
        val third = load(csv, options(batch = 300))
        assertEquals(0, third.of("outlet").created + third.of("outlet").updated, third.text())
    }

    /** B2 (D-MD-24): a rep whose id is shorter than 4 characters is HELD, with its id in the report, and no user is created for it. */
    @Test
    fun aRepIdTooShortForTheInstalledAppIsHeldNotCreated() = withDb {
        val (csv, facts) = SyntheticMaster.csv(1_500, wings = 1, seed = 31)
        assertTrue(facts.shortRepIds.isNotEmpty(), "the synthetic file must carry a 3-character rep id")
        val run = load(csv)
        assertEquals(facts.shortRepIds.size, run.of("sales_rep").held, run.text())
        assertEquals(facts.shortRepIds.size, run.issues["sales_rep_held_needs_app_release"])
        facts.shortRepIds.forEach { assertEquals(0, count("app.app_user WHERE username = '$it'")) }
        assertTrue(run.of("route_assignment").held > 0, "the route of a held rep gets no assignment")
        // with the release in place the same file creates them
        val allowed = load(csv, options(allowShort = true))
        assertEquals(facts.shortRepIds.size, allowed.of("sales_rep").created)
        facts.shortRepIds.forEach { assertEquals(1, count("app.app_user WHERE username = '$it'")) }
    }

    /** The owner's coordinates switch: with it on the pins inside Bangladesh load confirmed, and a pin outside the box is never confirmed. */
    @Test
    fun theCoordinatesSwitchConfirmsOnlyThePinsInsideBangladesh() = withDb {
        val (csv, facts) = SyntheticMaster.csv(800, wings = 1, seed = 41)
        val run = load(csv, options(confirm = true))
        assertEquals(0, run.of("outlet").error)
        val confirmed = count("app.outlet WHERE location_confirmed")
        assertEquals(count("app.outlet") - facts.noCoordinates - facts.outsideBangladesh, confirmed)
        assertEquals(0, count("app.outlet WHERE location_confirmed AND (lat < 20.5 OR lat > 26.7 OR lng < 88.0 OR lng > 92.7)"))
    }

    /** Every line the loader hands to a human carries counts and OUR codes only: never an outlet name, an owner name, a rep name or a phone number. */
    private fun assertNoPersonalData(run: LoadReport) {
        val text = run.text()
        for (forbidden in listOf("Outlet ", "Owner ", "Rep ", "N/A", "Cluster ")) {
            assertTrue(!text.contains(forbidden), "the report must not carry personal data, found '$forbidden' in:\n$text")
        }
        val phone = Regex("(^|[^0-9])(01[3-9][0-9]{8}|8801[3-9][0-9]{8})([^0-9]|$)")
        assertTrue(!phone.containsMatchIn(text), "the report must not carry a phone number:\n$text")
    }
}
