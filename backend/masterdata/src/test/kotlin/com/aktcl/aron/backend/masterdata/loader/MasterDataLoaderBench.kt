package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.ServerEnv
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test

/**
 * Throughput and memory measurement for the 737,159-row estimate in docs/reference/master-data-loader.md. Not a gate: it does nothing unless
 * ARON_LOADER_BENCH=1, so CI never pays for it. Synthetic data only.
 */
class MasterDataLoaderBench {
    private fun usedMb(): Long { repeat(3) { System.gc(); Thread.sleep(100) }; return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1 shl 20) }

    @Test
    fun measure() {
        if (System.getenv("ARON_LOADER_BENCH") != "1") return
        val rows = (System.getenv("ARON_LOADER_BENCH_ROWS") ?: "40000").toInt()
        val (csv, facts) = SyntheticMaster.csv(rows, wings = 10, seed = 5)
        FreshDb.create().use { fresh ->
            val day = LocalDate.parse("2026-11-13") // date-pin-ok: an input, not a clock read
            fun opts() = LoadOptions(env = ServerEnv.DEV, syntheticTestData = true, batchSize = 5000, businessDate = day, shortRepIdsAllowedForTests = true)
            val first = MasterDataLoader(fresh.db, opts()).load { StringReader(csv) }
            val again = MasterDataLoader(fresh.db, opts()).load { StringReader(csv) }
            println("BENCH rows=${facts.rows} first_ms=${first.millis} second_ms=${again.millis} rows_per_s=${facts.rows * 1000L / maxOf(1, first.millis)}")
            println(first.text())
        }
    }

    private fun buildScan(rows: Int): MasterScan {
        val (csv, _) = SyntheticMaster.csv(rows, wings = 10, seed = 5)   // the CSV text is garbage as soon as this returns: only the scan stays alive
        val scan = MasterScan(listOf("Rural", "Urban", "SemiUrban", "Hill"), Instant.now())
        MasterCsv(StringReader(csv)).use { c -> scan.header(c); while (true) scan.add(c.next() ?: break) }
        return scan
    }

    /** The heap the scan (pass 1) retains after reading [rows] rows: what the loader keeps in memory besides one batch (the CSV text is not counted). */
    @Test
    fun measureScanMemory() {
        if (System.getenv("ARON_LOADER_BENCH") != "1") return
        val rows = (System.getenv("ARON_LOADER_BENCH_ROWS") ?: "40000").toInt()
        val before = usedMb()
        val scan = buildScan(rows)
        val after = usedMb()
        println("BENCH_MEM rows=$rows scan_heap_mb=${after - before} routes=${scan.routes.size} clusters=${scan.clusters.size} reps=${scan.reps.size}")
        check(scan.rows == rows.toLong())
    }
}
