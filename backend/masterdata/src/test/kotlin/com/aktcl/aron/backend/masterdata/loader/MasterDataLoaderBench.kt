package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.platform.FreshDb
import java.io.StringReader
import java.time.LocalDate
import kotlin.test.Test

/**
 * Throughput measurement for the 737,159-row estimate in docs/status/masterdata-fit.md. Not a gate: it is skipped unless ARON_LOADER_BENCH=1,
 * so CI never pays for it. Synthetic data only.
 */
class MasterDataLoaderBench {
    @Test
    fun measure() {
        if (System.getenv("ARON_LOADER_BENCH") != "1") return
        val rows = (System.getenv("ARON_LOADER_BENCH_ROWS") ?: "40000").toInt()
        val (csv, facts) = SyntheticMaster.csv(rows, wings = 10, seed = 5)
        FreshDb.create().use { fresh ->
            val o = LoadOptions(batchSize = 5000, businessDate = LocalDate.parse("2026-11-13"), allowShortRepIds = true) // date-pin-ok: an input, not a clock read
            val first = MasterDataLoader(fresh.db, o).load { StringReader(csv) }
            val again = MasterDataLoader(fresh.db, LoadOptions(batchSize = 5000, businessDate = LocalDate.parse("2026-11-13"), allowShortRepIds = true)).load { StringReader(csv) } // date-pin-ok
            println("BENCH rows=${facts.rows} first_ms=${first.millis} second_ms=${again.millis} rows_per_s=${facts.rows * 1000L / maxOf(1, first.millis)}")
            println(first.text())
        }
    }
}
