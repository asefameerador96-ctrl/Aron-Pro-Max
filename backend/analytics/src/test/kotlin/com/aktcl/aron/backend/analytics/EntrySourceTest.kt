package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/** db request entry-source: the Aggregator copies app.memo / app.visit `entry_source` into the dw facts, and Online/Offline shows manual (backfilled) memos apart. */
class EntrySourceTest : ReportFixture() {
    // The memo and visit tables refuse updates, so the fixture marks rows manual with triggers off (superuser test database).
    override val extraSql = """
        SET session_replication_role = replica;
        UPDATE app.memo SET entry_source = 'manual', entered_by_user_id = (SELECT id FROM app.app_user WHERE username = 'sr002')
         WHERE memo_no = (SELECT min(memo_no) FROM app.memo WHERE status = 'active' AND voided_at IS NULL);
        UPDATE app.visit SET entry_source = 'manual', entered_by_user_id = (SELECT id FROM app.app_user WHERE username = 'sr002')
         WHERE client_uuid::text = (SELECT min(client_uuid::text) FROM app.visit);
        SET session_replication_role = DEFAULT;
    """.trimIndent()

    private fun count(sql: String) = fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery(sql).mapTo(Int::class.java).one() }

    @Test
    fun theFactsCarryEntrySourceFromTheSourceRows() = app {
        assertEquals(1, count("SELECT count(*) FROM dw.fact_memo WHERE entry_source = 'manual'"))
        assertEquals(count("SELECT count(*) FROM app.memo") - 1, count("SELECT count(*) FROM dw.fact_memo WHERE entry_source = 'device'"))
        assertEquals(1, count("SELECT count(*) FROM dw.fact_visit WHERE entry_source = 'manual'"))
        // A rebuild changes nothing (the projection is idempotent) and a later change of source follows the row.
        AggregationWorker(fresh.db).also { it.requestRebuild(day); it.runUntilIdle() }
        assertEquals(1, count("SELECT count(*) FROM dw.fact_memo WHERE entry_source = 'manual'"))
    }

    @Test
    fun theFactsCarryTheWebUserWhoKeyedAManualRowAndDeviceRowsNameNoOne() = app {
        val keyer = count("SELECT id FROM app.app_user WHERE username = 'sr002'")
        fun check() {
            assertEquals(1, count("SELECT count(*) FROM dw.fact_memo WHERE entry_source = 'manual' AND entered_by_user_id = $keyer"))
            assertEquals(1, count("SELECT count(*) FROM dw.fact_visit WHERE entry_source = 'manual' AND entered_by_user_id = $keyer"))
            assertEquals(0, count("SELECT count(*) FROM dw.fact_memo WHERE entry_source = 'device' AND entered_by_user_id IS NOT NULL"))
            assertEquals(0, count("SELECT count(*) FROM dw.fact_visit WHERE entry_source = 'device' AND entered_by_user_id IS NOT NULL"))
        }
        check()
        AggregationWorker(fresh.db).also { it.requestRebuild(day); it.runUntilIdle() }
        check()
    }

    @Test
    fun onlineOfflineShowsManualMemosApartAndKeepsTheDeviceFigures() = app {
        val r = run(10, Role.ANALYST, "online-offline").result()
        val rows = r.rows()
        val manual = rows.sumOf { it.num("manual_memos").toInt() }
        val device = rows.sumOf { it.num("online_memos").toInt() + it.num("offline_memos").toInt() }
        assertEquals(1, manual)
        assertEquals(count("SELECT count(*) FROM dw.fact_memo WHERE status = 'active' AND line_count > 0"), manual + device)
        assertEquals(1, r.total("manual_memos").toInt())
        // Scope still applies: a zone-2 TSO sees the manual memo only if it is in zone 2.
        val z2Manual = count("SELECT count(*) FROM dw.fact_memo WHERE entry_source = 'manual' AND status = 'active' AND line_count > 0 AND zone_id = $z2")
        assertEquals(z2Manual, run(14, Role.TSO, "online-offline").result().rows().sumOf { it.num("manual_memos").toInt() })
    }
}
