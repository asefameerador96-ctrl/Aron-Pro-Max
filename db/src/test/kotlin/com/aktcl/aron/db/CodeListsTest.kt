package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Audit AUD-DA-04: a database built from the migrations alone (no seed, as QA, staging and production are) has the
 * system code lists, and the closed lists match the constraints that use their codes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeListsTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()   // migrations only, never SeedLoader
    }

    @AfterAll
    fun tearDown() = db.close()

    /** The literal values of the IN-list CHECK on a column, from the catalogue. */
    private fun checkValues(c: java.sql.Connection, table: String, column: String): List<String> =
        c.column(
            "SELECT pg_get_constraintdef(k.oid) FROM pg_constraint k JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = ANY(k.conkey) " +
                "WHERE k.conrelid = ?::regclass AND k.contype = 'c' AND a.attname = ? AND cardinality(k.conkey) = 1",
            table, column,
        ).filterNotNull().first { it.contains("::text") }
            .let { Regex("'([^']+)'::text").findAll(it).map { m -> m.groupValues[1] }.toList().sorted() }

    @Test
    fun everyCodeListHasActiveItemsWithoutTheSeed() = db.connect().use { c ->
        assertEquals("0", c.scalar("SELECT count(*) FROM app.app_user WHERE pilot"), "this database must not be seeded")
        assertEquals(
            emptyList(),
            c.column(
                "SELECT l.list_key FROM app.code_list l WHERE NOT EXISTS (SELECT 1 FROM app.code_list_item i " +
                    "WHERE i.list_key = l.list_key AND i.valid_to IS NULL) ORDER BY 1",
            ),
        )
        assertEquals("22", c.scalar("SELECT count(*) FROM app.code_list"))   // V0199: + cluster_type; V0131: + ticket_issue_type, ticket_resolution_code; V0173: + zero_sale_reason, call_photo_skip
    }

    @Test
    fun closedListsMatchTheConstraintsThatUseThem() = db.connect().use { c ->
        assertEquals(checkValues(c, "app.visit", "outcome_code"), c.column("SELECT code FROM app.code_list_item WHERE list_key = 'visit_outcome' ORDER BY code"))
        assertEquals(checkValues(c, "app.due_collection", "payment_mode"), c.column("SELECT code FROM app.code_list_item WHERE list_key = 'payment_mode' ORDER BY code"))
        assertEquals(checkValues(c, "app.outlet", "channel"), c.column("SELECT attrs ->> 'value' FROM app.code_list_item WHERE list_key = 'channel' ORDER BY 1"))
        assertEquals(
            c.column("SELECT geo_class || ':' || ordinal FROM app.geo_class_def ORDER BY 1"),
            c.column("SELECT (attrs ->> 'value') || ':' || (attrs ->> 'ordinal') FROM app.code_list_item WHERE list_key = 'geo_class' ORDER BY 1"),
        )
        assertEquals(
            emptyList(),
            c.column("SELECT code FROM app.code_list_item WHERE list_key = 'qc_fault_type' AND coalesce(attrs ->> 'group', '') NOT IN ('MFC','MKT')"),
        )
        // The geo rule assigns no_outlet_location by name (D-95); the contract's skip default is not_reached.
        assertEquals("1", c.scalar("SELECT count(*) FROM app.code_list_item WHERE list_key = 'force_reason' AND code = 'no_outlet_location'"))
        assertEquals("1", c.scalar("SELECT count(*) FROM app.code_list_item WHERE list_key = 'skip_reason' AND code = 'not_reached'"))
    }

    @Test
    fun theSubChannelsOfD258AndTheFieldsAsthaTiersExistAsMasterRowsAndListItems() = db.connect().use { c ->
        assertEquals(
            listOf("Astha:Diamond", "Astha:Gold", "Astha:Platinum", "Astha:Silver", "Astha:Titanium", "DCC:DCC", "GT:GT", "HoReCa:HoReCa", "MT:MT", "RCC:RCC"),
            c.column("SELECT channel || ':' || code FROM app.sub_channel ORDER BY 1"),
        )
        // D-MD-07: the tiers follow the field (Gold, Diamond, Platinum, Titanium); Silver is retired, never deleted.
        assertEquals(listOf("Astha:Gold:active", "Astha:Diamond:active", "Astha:Platinum:active", "Astha:Titanium:active", "Astha:Silver:inactive"),
            c.column("SELECT channel || ':' || code || ':' || status FROM app.sub_channel WHERE channel = 'Astha' ORDER BY CASE code WHEN 'Gold' THEN 1 WHEN 'Diamond' THEN 2 WHEN 'Platinum' THEN 3 WHEN 'Titanium' THEN 4 ELSE 5 END"))
        // push 1b: no valid_to (the bundle would drop the label of every outlet still on Silver); the item is flagged retired, the master row is inactive.
        assertEquals("1", c.scalar("SELECT count(*) FROM app.code_list_item WHERE list_key = 'sub_channel' AND code = 'silver' AND valid_to IS NULL AND attrs @> '{\"retired\": true}'"))
        assertEquals(listOf("bazar_hub", "commercial_hub", "industrial_hub", "residential", "transit_hub", "university_hub"), c.column("SELECT code FROM app.code_list_item WHERE list_key = 'cluster_type' ORDER BY 1"))
        assertEquals("0", c.scalar("SELECT count(*) FROM app.code_list_item WHERE list_key = 'cluster_type' AND label_bn IS NULL"))
        assertEquals(
            "0",
            c.scalar(
                "SELECT count(*) FROM app.code_list_item i WHERE i.list_key = 'sub_channel' " +
                    "AND NOT EXISTS (SELECT 1 FROM app.sub_channel s WHERE s.code = i.attrs ->> 'value' AND s.channel = i.attrs ->> 'channel')",
            ),
        )
    }
}
