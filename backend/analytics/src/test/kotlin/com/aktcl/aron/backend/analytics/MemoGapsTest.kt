package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/** F-SYS-069 / F-API-017a: gaps in a memo series, burned numbers explained by sale_abort, voids never gaps, scope by reach. */
class MemoGapsTest : ReportFixture() {
    // sr001 holds 001 (a1), 002 (a2), 003 (voided a3), 004 (zero-line a6); add 007 and 008 so that 005 and 006 are missing,
    // explain 005 by a sale_abort (commit_failed) and add 010 on the second phone block (501...) with 502 missing.
    override val extraSql = """
        SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000b7', '00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O1', 'sr001-261004-007', TIMESTAMPTZ '2026-10-04 08:00Z', 0, 0, 0, 'active');
        SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000b8', '00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O1', 'sr001-261004-008', TIMESTAMPTZ '2026-10-04 08:10Z', 0, 0, 0, 'active');
        SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000b9', '00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O1', 'sr001-261004-501', TIMESTAMPTZ '2026-10-04 08:20Z', 0, 0, 0, 'active');
        SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000ba', '00000000-0000-4000-8000-000000000001', 'sr001', 'R1', 'O1', 'sr001-261004-503', TIMESTAMPTZ '2026-10-04 08:30Z', 0, 0, 0, 'active');
        INSERT INTO app.sale_abort (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_no, reason, outlet_id)
          SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, TIMESTAMPTZ '2026-10-04 06:30Z', 1, 'sr001-261004-005', 'commit_failed', o.id
            FROM app.app_user u, app.route r, app.outlet o WHERE u.username = 'sr001' AND r.code = 'R1' AND o.code = 'O1';
    """.trimIndent()

    @Test
    fun gapsAreListedAndABurnedNumberIsExplained() = app {
        val o = run(10, Role.ANALYST, "memo-number-gaps").result()
        val rows = o.rows()
        assertEquals(listOf("sr001-261004-005", "sr001-261004-006", "sr001-261004-502"), rows.map { it.str("memo_no")!! })
        assertEquals("sale_aborted", rows.by("memo_no", "sr001-261004-005").str("explanation")); assertEquals("commit_failed", rows.by("memo_no", "sr001-261004-005").str("abort_reason"))
        assertEquals("missing", rows.by("memo_no", "sr001-261004-006").str("explanation"))
        assertEquals(1, rows.by("memo_no", "sr001-261004-502").num("device_ordinal").toInt())      // the second phone's block
        // The voided 003 and the zero-line 004 are memos, never gaps; the other users' series have none.
        assertEquals(3, o["total_rows"]!!.toString().toInt())
        assertEquals(0, run(14, Role.TSO, "memo-number-gaps").result().rows().size)                  // zone 2 does not see zone 1's series
        assertEquals(3, run(11, Role.TSO, "memo-number-gaps").result().rows().size)
    }
}
