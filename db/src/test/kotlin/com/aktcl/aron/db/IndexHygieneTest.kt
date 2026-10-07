package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AUD-PERF-08 (V0039): no btree index may be a leading-prefix duplicate of another index on the same table. Such an
 * index serves no query the longer one cannot, and costs a write on every insert of a hot capture table.
 * An index is redundant when it is a plain (non-unique, no INCLUDE, no expression) btree whose key columns, operator
 * classes, collations and sort options are the leading keys of another btree with the same predicate. Indexes on
 * partitions are checked once, on their parent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexHygieneTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() { db = TestPostgres.createDatabase().migrated() }

    @AfterAll
    fun tearDown() = db.close()

    private val prefixDuplicates = """
        WITH ix AS (
          SELECT i.indexrelid, i.indrelid, i.indisunique, i.indnkeyatts, i.indnatts,
                 string_to_array(i.indkey::text, ' ')::int[]        AS keys,
                 string_to_array(i.indclass::text, ' ')::oid[]      AS ops,
                 string_to_array(i.indcollation::text, ' ')::oid[]  AS colls,
                 string_to_array(i.indoption::text, ' ')::int[]     AS opts,
                 pg_get_expr(i.indpred, i.indrelid)                 AS pred,
                 i.indexprs IS NOT NULL                             AS has_expr,
                 am.amname
            FROM pg_index i
            JOIN pg_class ic ON ic.oid = i.indexrelid
            JOIN pg_am am ON am.oid = ic.relam
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
           WHERE n.nspname IN ('app', 'dw', 'stg') AND NOT t.relispartition
        )
        SELECT a.indexrelid::regclass::text || ' duplicates a prefix of ' || b.indexrelid::regclass::text
          FROM ix a JOIN ix b ON b.indrelid = a.indrelid AND b.indexrelid <> a.indexrelid
         WHERE a.amname = 'btree' AND b.amname = 'btree'
           AND NOT a.indisunique AND NOT a.has_expr AND NOT b.has_expr
           AND a.indnatts = a.indnkeyatts
           AND a.pred IS NOT DISTINCT FROM b.pred
           AND a.indnkeyatts <= b.indnkeyatts
           AND a.keys[1:a.indnkeyatts]  = b.keys[1:a.indnkeyatts]
           AND a.ops[1:a.indnkeyatts]   = b.ops[1:a.indnkeyatts]
           AND a.colls[1:a.indnkeyatts] = b.colls[1:a.indnkeyatts]
           AND a.opts[1:a.indnkeyatts]  = b.opts[1:a.indnkeyatts]
           -- an exact twin is reported once (the higher oid), unless the other one is unique
           AND (a.indnkeyatts < b.indnkeyatts OR b.indisunique OR a.indexrelid > b.indexrelid)
         ORDER BY 1
    """.trimIndent()

    @Test
    fun noIndexIsALeadingPrefixDuplicateOfAnother() = db.connect().use { c ->
        assertEquals(emptyList(), c.column(prefixDuplicates))
    }

    @Test
    fun theCheckFindsAPrefixAnExactTwinAndIgnoresPartialAndUniqueIndexes() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec("CREATE INDEX hyg_prefix ON app.memo_discount (memo_client_uuid, kind)")    // memo_client_uuid alone is now a prefix
            c.exec("CREATE INDEX hyg_twin ON app.memo_line (sku_id, business_date)")           // exact twin of a V0007 index
            c.exec("CREATE INDEX hyg_partial ON app.memo_discount (business_date) WHERE kind = 'drp'")   // different predicate: kept
            c.exec("CREATE INDEX hyg_desc ON app.memo_discount (business_date DESC)")         // different sort option: kept
            c.exec("CREATE UNIQUE INDEX hyg_unique ON app.memo_discount (client_uuid, kind)")  // client_uuid's UNIQUE is unique: kept
            assertEquals(
                listOf(
                    "app.hyg_twin duplicates a prefix of app.memo_line_sku_id_business_date_idx",
                    "app.memo_discount_memo_client_uuid_idx duplicates a prefix of app.hyg_prefix",
                ).sorted(),
                c.column(prefixDuplicates).map { it!! }.sorted(),
            )
        } finally { c.rollback(); c.autoCommit = true }
    }

    @Test
    fun v0039DroppedTheShorterIndexAndKeptTheLongerOne() = db.connect().use { c ->
        assertEquals(
            listOf("domain_event_pkey", "memo_discount_business_date_kind_idx", "memo_no_unique", "qc_entry_line_business_date_sku_id_idx"),
            c.column(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'app' AND indexname IN ('memo_no_lookup', 'memo_no_unique', " +
                    "'memo_discount_business_date_idx', 'memo_discount_business_date_kind_idx', 'domain_event_id', 'domain_event_pkey', " +
                    "'qc_entry_line_business_date_idx', 'qc_entry_line_business_date_sku_id_idx') ORDER BY 1",
            ),
        )
    }
}
