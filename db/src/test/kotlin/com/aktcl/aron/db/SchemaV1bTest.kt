package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Row N-006 acceptance: inserting the same client_uuid twice into any device-originated table violates the unique
 * key; every table has a timestamptz column and a Dhaka business_date column. Plus the invariants v1b enforces:
 * synced rows are immutable outside their named state columns, memo arithmetic (docs/24 s7.4), the global registry.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaV1bTest {
    companion object {
        /** Every table a sync record type (docs/24 s4.2) is stored in, plus the registry; partitioned ones marked. */
        val DEVICE_TABLES = listOf(
            "attendance_event", "route_day_event", "day_exception", "stock_movement", "visit", "visit_skip", "memo",
            "memo_line", "memo_discount", "qc_entry_line", "print_event", "memo_void", "due_collection", "survey_response",
            "distribution_check", "distribution_check_line", "call_assessment", "call_assessment_answer",
            "outlet_change_request", "outlet_request_event", "task", "task_event", "visit_plan", "visit_plan_outlet",
            "leave_application", "feedback", "media", "geo_breadcrumb", "cfg_ack",
            // contract v1.1
            "content_view", "redemption", "redemption_line", "gift_photo", "price_compliance_check", "risk_signal_review",
            "activity_log", "app_error", "sale_abort", "user_consent",
        )
        val PARTITIONED = setOf("visit", "memo", "memo_line")

        /** Tables of row N-006 that must carry business_date and a timestamptz column. */
        val V1B_TABLES = DEVICE_TABLES + listOf(
            "geo_fix", "route_day", "supervisor_day", "qc_entry", "due_ledger", "indent_movement", "final_submit",
            "submit_void_event", "route_day_void_barrier", "domain_event", "loyalty_ledger", "risk_signal",
        )

        @JvmStatic fun deviceTables() = DEVICE_TABLES
        @JvmStatic fun v1bTables() = V1B_TABLES

        /** Values the generic row builder cannot guess (patterns, ranges, cross-column rules). */
        val OVERRIDES: Map<String, Map<String, String>> = mapOf(
            "*" to mapOf(
                "business_date" to "'2026-10-05'",
                "captured_at" to "'2026-10-05T04:00:00Z'",
                "config_version" to "0",
            ),
            "route_day_event" to mapOf("route_ids" to "'{1}'", "online" to "true", "bundle_valid_for" to "'2026-10-05'", "offline_start" to "false"),
            "visit" to mapOf("radius_m_used" to "100", "max_accuracy_m_used" to "100"),
            "memo" to mapOf(
                "memo_no" to "'sr0001-261005-001'",
                "gross_mtk" to "0", "offer_discount_mtk" to "0", "drp_discount_mtk" to "0", "qc_deduction_mtk" to "0",
                "round_adj_mtk" to "0", "net_mtk" to "0", "paid_mtk" to "0", "due_mtk" to "0", "is_credit" to "false",
            ),
            "memo_void" to mapOf("memo_no" to "'sr0001-261005-001'"),
            "due_collection" to mapOf("amount_mtk" to "10"),
            "media" to mapOf(
                "sha256" to "decode(repeat('ab', 32), 'hex')",
                "blob_path" to "'photos/2026-10-05/' || gen_random_uuid() || '/' || gen_random_uuid() || '.jpg'",
                "mime" to "'image/jpeg'",
            ),
            "outlet_request_event" to mapOf("via" to "'device'"),
            "gift_photo" to mapOf("redemption_client_uuid" to "gen_random_uuid()"),
            "sale_abort" to mapOf("memo_no" to "'sr0001-261005-002'"),
            "user_consent" to mapOf("policy_key" to "'location_notice'"),
            "app_error" to mapOf("app_version" to "'1.0.3+103'"),
            "activity_log" to mapOf("events" to "'[{\"at\": \"2026-10-05T04:00:00.000Z\", \"screen\": \"home\", \"action\": \"open\"}]'"),
        )
    }

    private lateinit var db: TestDatabase
    private val fk = mutableMapOf<String, String>()

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { c ->
            c.exec(
                """
                INSERT INTO app.wing (code, name) VALUES ('W1', 'Wing 1');
                INSERT INTO app.division (code, name, wing_id) SELECT 'D1', 'Division 1', id FROM app.wing;
                INSERT INTO app.territory (code, name, division_id) SELECT 'T1', 'Territory 1', id FROM app.division;
                INSERT INTO app.zone (code, name, territory_id) SELECT 'Z1', 'Zone 1', id FROM app.territory;
                INSERT INTO app.cluster (zone_id, name) SELECT id, 'Bazar' FROM app.zone;
                INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'R-1', 'Route 1', id, 'sr', 'daily', 127 FROM app.zone;
                INSERT INTO app.app_user (username, full_name, role) VALUES ('sr0001', 'SR One', 'SR'), ('amo0001', 'AMO One', 'AMO');
                INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel)
                  SELECT 'O-1', 'দোকান এক', 'মালিক', z.id, r.id, c.id, 'GT' FROM app.zone z, app.route r, app.cluster c;
                INSERT INTO app.product_node (level, name) VALUES ('category', 'Cigarette');
                INSERT INTO app.product_node (level, parent_id, name) SELECT 'segment', id, 'Medium' FROM app.product_node WHERE name = 'Cigarette';
                INSERT INTO app.product_node (level, parent_id, name) SELECT 'brand', id, 'Maxim' FROM app.product_node WHERE name = 'Medium';
                INSERT INTO app.product_node (level, parent_id, name) SELECT 'variant', id, 'Maxim Regular' FROM app.product_node WHERE name = 'Maxim';
                INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default)
                  SELECT 'MaxR-10S', id, 'cigarette', 'Maxim Regular 10', 'MaxR-10S', 'stick', 10, 'stick' FROM app.product_node WHERE level = 'variant';
                INSERT INTO app.programme (kind, code, name_en, active_from, active_to) VALUES ('diamond_league', 'DL-2026-10', 'Diamond League', '2026-10-01', '2026-10-31');
                INSERT INTO app.gift (programme_id, code, name_en, points_cost) SELECT id, 'mug', 'Mug', 100 FROM app.programme;
                INSERT INTO app.content_item (kind, title_en, asset_url, sha256, bytes, valid_from, valid_to, sequence)
                  VALUES ('kv', 'KV', 'https://example.invalid/kv.jpg', decode(repeat('ab', 32), 'hex'), 100, '2026-10-01', '2026-10-31', 1);
                INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, score, config_version)
                  VALUES ('GEO_MOCK', 4, '2026-10-05', 'user', '1', 100, 0);
                INSERT INTO app.qc_entry (visit_client_uuid, business_date, user_id, outlet_id)
                  SELECT gen_random_uuid(), '2026-10-05', u.id, o.id FROM app.app_user u, app.outlet o WHERE u.username = 'sr0001';
                """.trimIndent(),
            )
            fk["user"] = c.scalar("SELECT id FROM app.app_user WHERE username = 'sr0001'")!!
            fk["user2"] = c.scalar("SELECT id FROM app.app_user WHERE username = 'amo0001'")!!
            fk["route"] = c.scalar("SELECT id FROM app.route")!!
            fk["outlet"] = c.scalar("SELECT id FROM app.outlet")!!
            fk["sku"] = c.scalar("SELECT id FROM app.sku")!!
            fk["brand"] = c.scalar("SELECT id FROM app.product_node WHERE level = 'brand'")!!
            fk["qc_entry"] = c.scalar("SELECT id FROM app.qc_entry")!!
            fk["programme"] = c.scalar("SELECT id FROM app.programme")!!
            fk["gift"] = c.scalar("SELECT id FROM app.gift")!!
            fk["content"] = c.scalar("SELECT id FROM app.content_item")!!
            fk["signal"] = c.scalar("SELECT id FROM app.risk_signal")!!
        }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun <T> tx(block: (Connection) -> T): T = db.connect().use { c ->
        c.autoCommit = false
        try { block(c) } finally { c.rollback() }
    }

    /**
     * An INSERT of one valid row into app.[table] with the given client_uuid and business date: required columns are
     * filled from their type, the first value of their enumeration CHECK, the seeded master rows, or [OVERRIDES].
     */
    private fun insertSql(c: Connection, table: String, clientUuid: UUID, businessDate: String = "2026-10-05"): String {
        data class Col(val name: String, val type: String, val udt: String)
        val cols = c.prepareStatement(
            """
            SELECT column_name, data_type, udt_name FROM information_schema.columns
             WHERE table_schema = 'app' AND table_name = ? AND is_nullable = 'NO' AND column_default IS NULL
               AND is_generated = 'NEVER' AND is_identity = 'NO' ORDER BY ordinal_position
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(Col(rs.getString(1), rs.getString(2), rs.getString(3))) } }
        }
        val checks = c.column("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ('app.' || ?)::regclass AND contype = 'c'", table)
            .filterNotNull()
        fun firstEnumValue(col: String): String? =
            checks.firstNotNullOfOrNull { Regex("\\(+$col = ANY \\(+ARRAY\\['([^']+)'").find(it)?.groupValues?.get(1) }
        val values = cols.associate { col ->
            val v = OVERRIDES[table]?.get(col.name) ?: OVERRIDES["*"]!![col.name] ?: when {
                col.name == "client_uuid" -> "'$clientUuid'"
                col.name.endsWith("user_id") || col.name == "submitted_by" || col.name == "voided_by" -> fk["user"]!!
                col.name == "outlet_id" -> fk["outlet"]!!
                col.name == "route_id" -> fk["route"]!!
                col.name == "sku_id" -> fk["sku"]!!
                col.name == "brand_id" -> fk["brand"]!!
                col.name == "qc_entry_id" -> fk["qc_entry"]!!
                col.name == "programme_id" -> fk["programme"]!!
                col.name == "gift_id" -> fk["gift"]!!
                col.name == "content_id" -> fk["content"]!!
                col.name == "signal_id" -> fk["signal"]!!
                firstEnumValue(col.name) != null -> "'${firstEnumValue(col.name)}'"
                col.type == "uuid" -> "gen_random_uuid()"
                col.type == "date" -> "'2026-10-05'"
                col.type.startsWith("timestamp") -> "'2026-10-05T04:00:00Z'"
                col.type == "boolean" -> "false"
                col.type == "jsonb" -> "'{}'"
                col.type == "bytea" -> "decode(repeat('ab', 32), 'hex')"
                col.type == "ARRAY" -> if (col.udt == "_int8") "'{1}'" else "'{}'"
                col.type in setOf("integer", "smallint", "bigint", "numeric", "double precision", "real") -> "1"
                else -> "'ab'"
            }
            col.name to v
        }.toMutableMap()
        OVERRIDES[table]?.let { values.putAll(it) }          // also nullable columns a cross-column CHECK needs
        values["client_uuid"] = "'$clientUuid'"
        if ("business_date" in values) values["business_date"] = "'$businessDate'"
        return "INSERT INTO app.$table (${values.keys.joinToString()}) VALUES (${values.values.joinToString()})"
    }

    @ParameterizedTest
    @MethodSource("deviceTables")
    fun sameClientUuidTwiceViolatesTheUniqueKey(table: String) = tx { c ->
        val id = UUID.randomUUID()
        c.exec(insertSql(c, table, id))
        c.exec("SAVEPOINT s")
        val again = assertFailsWith<SQLException>("second insert of $id into $table") { c.exec(insertSql(c, table, id)) }
        assertEquals("23505", again.sqlState, again.message)
        c.exec("ROLLBACK TO SAVEPOINT s")
        // Under another business date too: the partitioned tables refuse it through app.client_uuid_once.
        val otherDate = assertFailsWith<SQLException>("same $id on another date in $table") {
            c.exec(insertSql(c, table, id, "2026-10-06"))
        }
        assertEquals("23505", otherDate.sqlState, otherDate.message)
    }

    @ParameterizedTest
    @MethodSource("v1bTables")
    fun everyTableHasAUtcInstantAndADhakaBusinessDate(table: String) = db.connect().use { c ->
        val types = c.column(
            "SELECT column_name || ':' || data_type FROM information_schema.columns WHERE table_schema = 'app' AND table_name = ?",
            table,
        ).filterNotNull()
        assertTrue("business_date:date" in types, "$table lacks business_date date: $types")
        assertTrue(types.any { it.endsWith(":timestamp with time zone") }, "$table has no timestamptz column")
        assertTrue(types.none { it.endsWith(":timestamp without time zone") }, "$table has a timestamp without time zone")
        val nullable = c.scalar(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'app' AND table_name = ? AND column_name = 'business_date'",
            table,
        )
        assertEquals("NO", nullable, "$table.business_date must be NOT NULL")
    }

    @Test
    fun deviceTablesKeepMoneyInBigintMilliTaka() = db.connect().use { c ->
        val bad = c.column(
            """
            SELECT table_name || '.' || column_name || ' ' || data_type FROM information_schema.columns
             WHERE table_schema IN ('app','dw') AND column_name LIKE '%\_mtk%' AND data_type <> 'bigint'
               AND NOT (column_name = 'round_adj_mtk' AND data_type = 'smallint')
            """.trimIndent(),
        )
        assertEquals(emptyList(), bad)
    }

    @Test
    fun theRegistryIsGloballyUniqueAcrossDatesAndTypes() = tx { c ->
        val id = UUID.randomUUID()
        val sql = "INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, user_id, first_batch_uuid, business_date) " +
            "VALUES ('$id', '%s', decode(repeat('01', 32), 'hex'), 'accepted', ${fk["user"]}, gen_random_uuid(), '%s')"
        c.exec(sql.format("memo", "2026-10-05"))
        val e = assertFailsWith<SQLException> { c.exec(sql.format("visit", "2026-11-05")) }
        assertEquals("23505", e.sqlState)
    }

    @Test
    fun syncedRowsChangeOnlyInTheirStateColumnsAndAreNeverDeleted() = tx { c ->
        val id = UUID.randomUUID()
        c.exec(insertSql(c, "memo", id))
        c.exec("UPDATE app.memo SET status = 'voided', voided_by_client_uuid = gen_random_uuid(), status_changed_at = now() WHERE client_uuid = '$id'")
        val forbidden = listOf(
            "UPDATE app.memo SET memo_kind = 'zero_sale' WHERE client_uuid = '$id'",
            "UPDATE app.memo SET business_date = '2026-10-04' WHERE client_uuid = '$id'",
            "DELETE FROM app.memo WHERE client_uuid = '$id'",
        )
        val stock = UUID.randomUUID()
        c.exec(insertSql(c, "stock_movement", stock))
        val forbiddenStock = listOf(
            "UPDATE app.stock_movement SET qty_entered = 5, qty_base = 5 WHERE client_uuid = '$stock'",
            "DELETE FROM app.stock_movement WHERE client_uuid = '$stock'",
        )
        (forbidden + forbiddenStock).forEach { sql ->
            c.exec("SAVEPOINT s")
            assertEquals("42501", assertFailsWith<SQLException>(sql) { c.exec(sql) }.sqlState, sql)
            c.exec("ROLLBACK TO SAVEPOINT s")
        }
        c.exec("UPDATE app.stock_movement SET voided_at = now() WHERE client_uuid = '$stock'")   // data-void tombstone is allowed
    }

    @Test
    fun aMemoThatBreaksTheTotalEquationsCannotBeStored() {
        val base = "INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_client_uuid, " +
            "outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, " +
            "qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count) " +
            "VALUES (gen_random_uuid(), gen_random_uuid(), '2026-10-05', ${fk["user"]}, now(), 0, gen_random_uuid(), ${fk["outlet"]}, " +
            "'sr0001-261005-%s', 'sale', now(), '2026-10-05', 'outlet', %s, 0, 0, 0, %s, %s, %s, %s, %s, 1, 0, 0)"
        // 116,416 mtk gross rounds to a net of 116,420 (round_adj +4), paid 100,000 and due 16,420 on credit: valid.
        tx { it.exec(base.format("001", 116416, 4, 116420, 100000, 16420, true)) }
        val broken = listOf(
            base.format("002", 116416, 0, 116416, 116416, 0, false),     // net not a whole paisa
            base.format("003", 116416, 4, 116420, 100000, 16000, true),  // paid + due <> net
            base.format("004", 116416, 4, 116420, 100000, 16420, false), // due > 0 but not credit
            base.format("005", 116416, 6, 116422, 116422, 0, false),     // |round_adj| > 5
        )
        broken.forEach { sql -> assertEquals("23514", assertFailsWith<SQLException> { tx { it.exec(sql) } }.sqlState, sql) }
    }

    @Test
    fun aMemoLineMustReproduceItsGross() {
        val line = "INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, memo_client_uuid, " +
            "line_no, sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, " +
            "price_per_qty, gross_mtk) VALUES (gen_random_uuid(), gen_random_uuid(), '2026-10-05', ${fk["user"]}, now(), 0, gen_random_uuid(), " +
            "1, ${fk["sku"]}, 'sale', %s, '%s', 10, %s, 'outlet', '2026-01-01', 7935, 1, %s)"
        tx { it.exec(line.format(2, "pack", 20, 158700)) }   // 2 packs of 10 = 20 sticks x 7,935 mtk
        assertEquals("23514", assertFailsWith<SQLException> { tx { it.exec(line.format(2, "pack", 20, 158000)) } }.sqlState)
        assertEquals("23514", assertFailsWith<SQLException> { tx { it.exec(line.format(2, "pack", 2, 15870)) } }.sqlState)
    }

    @Test
    fun partitionsExistForTheBuildMonthsAndRowsLandInTheirMonth() = tx { c ->
        listOf("visit", "memo", "memo_line", "geo_fix", "domain_event").forEach { t ->
            assertEquals("app.${t}_y2026m10", c.scalar("SELECT to_regclass('app.${t}_y2026m10')::text"))
            assertEquals("app.${t}_default", c.scalar("SELECT to_regclass('app.${t}_default')::text"))
        }
        val id = UUID.randomUUID()
        c.exec(insertSql(c, "visit", id))
        assertEquals("app.visit_y2026m10", c.scalar("SELECT tableoid::regclass::text FROM app.visit WHERE client_uuid = '$id'"))
    }

    @Test
    fun oneHandOverPhotoPerAsthaAssignmentAndPerRedeemedUnit() = tx { c ->
        c.exec(
            "INSERT INTO app.gift_assignment (programme_id, quarter, outlet_id, route_id, gift_id) " +
                "VALUES (${fk["programme"]}, '2026-Q4', ${fk["outlet"]}, ${fk["route"]}, ${fk["gift"]})",
        )
        val assignment = c.scalar("SELECT id FROM app.gift_assignment")!!
        val photo = "INSERT INTO app.gift_photo (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, " +
            "programme_kind, outlet_id, gift_id, gift_assignment_id, redemption_client_uuid, unit_no, photo_uuid) VALUES " +
            "(gen_random_uuid(), gen_random_uuid(), '2026-10-05', ${fk["user"]}, now(), 0, '%s', ${fk["outlet"]}, ${fk["gift"]}, %s, %s, %s, gen_random_uuid())"
        c.exec(photo.format("astha", assignment, "NULL", "NULL"))
        c.exec("SAVEPOINT s")
        assertEquals("23505", assertFailsWith<SQLException> { c.exec(photo.format("astha", assignment, "NULL", "NULL")) }.sqlState)
        c.exec("ROLLBACK TO SAVEPOINT s")
        val redemption = UUID.randomUUID()
        c.exec(photo.format("campaign", "NULL", "'$redemption'", "1"))
        c.exec(photo.format("campaign", "NULL", "'$redemption'", "2"))
        assertEquals("23505", assertFailsWith<SQLException> { c.exec(photo.format("campaign", "NULL", "'$redemption'", "2")) }.sqlState)
    }

    @Test
    fun attendanceAllowsOneCheckInPerUserAndDay() = tx { c ->
        c.exec(insertSql(c, "attendance_event", UUID.randomUUID()))
        assertEquals("23505", assertFailsWith<SQLException> { c.exec(insertSql(c, "attendance_event", UUID.randomUUID())) }.sqlState)
    }
}
