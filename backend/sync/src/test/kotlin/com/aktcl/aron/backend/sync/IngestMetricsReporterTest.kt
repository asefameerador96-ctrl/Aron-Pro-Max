package com.aktcl.aron.backend.sync

import kotlin.test.Test
import kotlin.test.assertTrue

/** OPS-03: the per-minute ingest line carries the deltas since the previous line, every value an integer the alert regexes extract. */
class IngestMetricsReporterTest {
    @Test
    fun aLineCarriesTheDeltasSinceThePreviousLine() {
        val r = IngestMetricsReporter()
        r.report()
        IngestMetrics.batches.increment(); IngestMetrics.records.add(3); IngestMetrics.accepted.add(2); IngestMetrics.rejected.increment()
        val line = r.report()
        assertTrue(line.startsWith("ingest_minute batches=1 records=3 accepted=2 duplicate=0 rejected=1 quarantined=0 parked_server_error=0"), line)
        assertTrue(Regex("^ingest_minute( [a-z_]+=[0-9]+)+$").matches(line), line)
        assertTrue(" db_statements=0" in line, "no database: zero statements")
        val next = r.report()
        assertTrue(" batches=0 records=0 accepted=0 " in next, "deltas, not totals: $next")
    }
}
