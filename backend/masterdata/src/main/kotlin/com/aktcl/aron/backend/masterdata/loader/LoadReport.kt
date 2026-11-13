package com.aktcl.aron.backend.masterdata.loader

/**
 * What a load run did, in COUNTS AND CODES ONLY: no outlet name, owner name, rep name or phone number ever reaches a report or a log line
 * (the repository is public and the workbook is personal data; standing rule 8 and `docs/reference/master-data-profile-2026-10.md`).
 * The codes that may appear are OUR business keys: wing, division, territory, house, zone, route, outlet and cluster codes.
 */
class LoadReport {
    /** One line of the report: what happened to the inputs of one kind. For outlets, created + updated + unchanged + held + error == rows read. */
    class Counts {
        var created: Int = 0
        var updated: Int = 0
        var unchanged: Int = 0
        /** Deliberately not applied, and why is an issue code (today: a rep whose id is too short for the installed app, D-MD-24). */
        var held: Int = 0
        var error: Int = 0
        val total: Int get() = created + updated + unchanged + held + error
        override fun toString() = "created=$created updated=$updated unchanged=$unchanged held=$held error=$error"
    }

    /** Input rows read from the file (blank lines are not rows). */
    var rowsRead: Long = 0

    /** Rows the loader could not even read as a row (a short record, an unreadable number): counted, never silently dropped. */
    var rowsUnreadable: Long = 0

    val entities: MutableMap<String, Counts> = linkedMapOf(
        "wing" to Counts(), "division" to Counts(), "territory" to Counts(), "house" to Counts(), "zone" to Counts(),
        "route" to Counts(), "cluster" to Counts(), "outlet" to Counts(), "sales_rep" to Counts(), "route_assignment" to Counts(),
    )

    fun of(entity: String): Counts = entities.getValue(entity)

    /** Issue code to how many times it happened (`phone_unusable`, `tier_mismatch`, `route_two_reps`, ...). */
    val issues: MutableMap<String, Int> = sortedMapOf()

    /** Up to [SAMPLES] example CODES per issue, so the owner can look at the real rows in the workbook. Never a name or a phone. */
    val samples: MutableMap<String, MutableList<String>> = sortedMapOf()

    fun issue(code: String, sample: String? = null, times: Int = 1) {
        issues[code] = (issues[code] ?: 0) + times
        if (sample != null) {
            val list = samples.getOrPut(code) { ArrayList() }
            if (list.size < SAMPLES) list += sample
        }
    }

    /** Batches applied in this run (resumed runs count only the batches this run applied). */
    var batchesApplied: Int = 0
    var batchesSkipped: Int = 0
    var dryRun: Boolean = false
    var millis: Long = 0

    /** The whole report as text, safe to paste into a status file or a log. */
    fun text(): String = buildString {
        appendLine("Master-data load report${if (dryRun) " (DRY RUN: nothing was written)" else ""}")
        appendLine("rows read: $rowsRead, unreadable: $rowsUnreadable, batches applied: $batchesApplied, skipped (already done): $batchesSkipped, ${millis} ms")
        appendLine()
        appendLine("| what | created | updated | unchanged | held | error | total |")
        appendLine("|---|---|---|---|---|---|---|")
        entities.forEach { (k, c) -> appendLine("| $k | ${c.created} | ${c.updated} | ${c.unchanged} | ${c.held} | ${c.error} | ${c.total} |") }
        val outlets = of("outlet")
        appendLine()
        appendLine("every input row is counted exactly once: outlet total ${outlets.total} ${if (outlets.total.toLong() == rowsRead) "==" else "!="} rows read $rowsRead")
        if (issues.isNotEmpty()) {
            appendLine()
            appendLine("| issue | count | example codes |")
            appendLine("|---|---|---|")
            issues.forEach { (k, n) -> appendLine("| $k | $n | ${samples[k]?.joinToString(", ") ?: ""} |") }
        }
    }

    companion object {
        const val SAMPLES = 10
    }
}
