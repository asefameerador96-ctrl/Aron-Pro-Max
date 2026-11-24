package com.aktcl.aron.backend.platform

import java.sql.Connection
import javax.sql.DataSource

/** A pre-flight check found data that a pending migration would refuse; nothing was changed (or, in dev, the repair did not finish). The message holds zone codes, counts and ids only, never a name. */
class PreflightFailed(message: String) : RuntimeException(message)

/**
 * Pre-flight of data-dependent migrations (D-SV2-12, docs/30): the migrate role runs these READ-ONLY checks BEFORE Flyway applies anything, so a
 * migration whose guard raises on existing data stops the deploy while the schema is still at the old version, not half way through a train.
 * Every raising data guard of a pending migration has a mirror here (the SQL lives in `db/preflight`, packaged at `classpath:db/preflight`).
 *
 * Outcome by environment, decided from the RAW `ARON_ENV` the deploy itself declares (the job's value, set by the Bicep parameter, never by a caller; [DEV] exactly):
 * everything else, an unset value included, FAILS with what to do; dev ONLY repairs its test data (see [ClusterNames.repair]), re-checks, and fails if anything is left.
 * A check that does not apply (its migration already applied, or the schema is too old / empty) is skipped; when NOTHING needed doing the run reports ONE line saying so ([nothingToDo]), so the migrate console shows that the pre-flight ran.
 * The report lines carry zone codes, counts and ids, never a name.
 */
object MigrationPreflight {
    /** One group of rows a pending migration would refuse: where (a zone code) and the ids involved. */
    data class Finding(val where: String, val ids: List<Long>)

    interface Check {
        val id: String
        /** True when the migration is still pending and the tables it reads exist. */
        fun applies(c: Connection): Boolean
        /** Dev repair only: takes the lock the repair needs, BEFORE the findings are read inside the repair's transaction. */
        fun lock(c: Connection)
        /** Read-only. */
        fun findings(c: Connection): List<Finding>
        /** Dev only, inside the caller's transaction: makes the findings go away without deleting anything; returns the ids it changed. */
        fun repair(c: Connection, findings: List<Finding>): List<Long>
        /** What the person must do in a non-dev environment. */
        val advice: String
        /** False for a guard with NO automatic repair (a business decision): it fails the deploy in EVERY environment, dev included, and changes nothing. */
        val devRepair: Boolean get() = true
        /** What [Finding.where] names in the report line ("zone", for the cluster check). */
        val whereLabel: String get() = "zone"
    }

    val CHECKS: List<Check> = listOf(ClusterNames, TitaniumTier)

    /** The only value of the raw `ARON_ENV` that may repair: an unset value is NOT dev here (Settings' own default stays as it is elsewhere), nor is anything else (staging, prod, a typo). */
    const val DEV = "dev"

    /**
     * The one line a run with nothing to do reports (so the deploy's migrate console shows that the pre-flight ran, D-OPS-DIAGNOSE-API-01): the check ids and, for each, `clean` (it applies
     * and found nothing) or `not pending` (its migration is already applied, or the tables it reads do not exist). Ids and two words only: never data.
     */
    fun nothingToDo(states: List<Pair<String, String>>) = "pre-flight: nothing to do (checks: " + states.joinToString(", ") { "${it.first}=${it.second}" } + ")"

    fun run(ds: DataSource, declaredEnv: String?, report: (String) -> Unit = {}, checks: List<Check> = CHECKS) {
        val states = ArrayList<Pair<String, String>>()
        var acted = false
        for (check in checks) {
            if (!check.devRepair) { states += check.id to strict(ds, check, report); continue }
            val found = readOnly(ds) { c -> if (check.applies(c)) check.findings(c) else null }
            if (found == null) { states += check.id to NOT_PENDING; continue }
            if (found.isEmpty()) { states += check.id to CLEAN; continue }
            acted = true
            val summary = describe(check, found)
            if (declaredEnv != DEV) {
                report("preflight ${check.id}: $summary")
                throw PreflightFailed("pre-flight ${check.id} failed, nothing was changed: $summary. ${check.advice}")
            }
            report("preflight ${check.id} (dev): $summary; repairing the test data (lowest id kept, the others get an id suffix)")
            val changed = try {
                ds.connection.use { c ->
                    c.autoCommit = false
                    try {
                        c.createStatement().use { it.execute("SET LOCAL lock_timeout = '10s'") }
                        check.lock(c)                          // the lock first, then the findings: nothing can change between the read and the repair
                        val again = check.findings(c)
                        val ids = if (again.isEmpty()) emptyList() else check.repair(c, again)
                        c.commit()
                        ids
                    } catch (e: Exception) {
                        runCatching { c.rollback() }; throw e
                    }
                }
            } catch (e: Exception) {
                // Only the SQLState, never the detail or the cause: a unique-violation detail carries a name.
                val state = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
                report("preflight ${check.id} (dev): the repair failed (SQLState ${state ?: "none"}); nothing was changed")
                throw PreflightFailed("pre-flight ${check.id}: the dev repair failed (SQLState ${state ?: "none"}); nothing was changed")
            }
            report("preflight ${check.id} (dev): renamed cluster ids $changed")
            val left = readOnly(ds) { c -> check.findings(c) }
            if (left.isNotEmpty()) throw PreflightFailed("pre-flight ${check.id}: ${describe(check, left)} remain after the dev repair")
        }
        if (!acted) report(nothingToDo(states))
    }

    private const val CLEAN = "clean"
    private const val NOT_PENDING = "not pending"

    /** A check with no automatic repair: it fails in every environment (dev included) before any migration and changes nothing. */
    private fun strict(ds: DataSource, check: Check, report: (String) -> Unit): String {
        val found = readOnly(ds) { c -> if (check.applies(c)) check.findings(c) else null }
        if (found == null) return NOT_PENDING
        if (found.isEmpty()) return CLEAN
        val summary = describe(check, found)
        report("preflight ${check.id}: $summary")
        throw PreflightFailed("pre-flight ${check.id} failed in every environment (no automatic repair), nothing was changed: $summary. ${check.advice}")
    }

    private fun describe(check: Check, found: List<Finding>) =
        "${found.size} group(s): " + found.joinToString("; ") { "${check.whereLabel} ${it.where} ids ${it.ids}" }

    private fun <T> readOnly(ds: DataSource, block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try {
            c.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            block(c)
        } finally { c.rollback() }
    }

    /** V0191: clusters of one zone whose names differ only by case, spacing or composition (the unique index app.cluster (zone_id, app.norm_cluster_name(name))). */
    object ClusterNames : Check {
        override val id = "V0191-cluster-names"
        override val advice = "In the admin portal (Master data, Clusters) rename or merge the listed clusters so that their names differ by more than case, spacing or composition, then deploy again."
        private const val RESOURCE = "db/preflight/V0191__cluster_names.sql"
        private const val NAME_MAX = 120   // app.cluster.name CHECK (length(name) BETWEEN 1 AND 120)

        private val sql: String by lazy {
            val text = ClusterNames::class.java.classLoader.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("$RESOURCE is not on the classpath")
            text.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        }

        override fun applies(c: Connection): Boolean = c.createStatement().use { st ->
            // V0191 pending = the clusters and zones tables exist and Flyway's own history has no successful V0191 row (not the function's existence: the history is the record of what ran).
            val tables = st.executeQuery("SELECT to_regclass('app.cluster') IS NOT NULL AND to_regclass('app.zone') IS NOT NULL").use { it.next() && it.getBoolean(1) }
            if (!tables) return@use false
            val hasHistory = st.executeQuery("SELECT to_regclass('flyway_schema_history') IS NOT NULL").use { it.next() && it.getBoolean(1) }
            if (!hasHistory) return@use true
            !st.executeQuery("SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success AND version ~ '^0*191$')").use { it.next() && it.getBoolean(1) }
        }

        override fun lock(c: Connection) { c.createStatement().use { it.execute("LOCK TABLE app.cluster IN SHARE ROW EXCLUSIVE MODE") } }

        override fun findings(c: Connection): List<Finding> = c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add(Finding(rs.getString("zone_code"), (rs.getArray("cluster_ids").array as Array<*>).map { (it as Number).toLong() }))
                }
            }
        }

        /** The character class of V0191's normalisation, read from the packaged check itself (one copy). */
        private val classLiteral: String by lazy { Regex("normalize\\(c\\.name, NFC\\), ('\\[\\\\s[^']*\\]\\+'), ' ', 'g'\\)").find(sql)!!.groupValues[1] }

        /** True when another cluster of the zone already has the candidate as its exact name or its normalised name. */
        private fun taken(c: Connection, zoneCode: String, self: Long, candidate: String): Boolean {
            fun norm(x: String) = "lower(btrim(regexp_replace(normalize($x, NFC), $classLiteral, ' ', 'g')))"
            return c.prepareStatement(
                "SELECT 1 FROM app.cluster k JOIN app.zone z ON z.id = k.zone_id WHERE z.code = ? AND k.id <> ? AND (k.name = ? OR ${norm("k.name")} = ${norm("CAST(? AS text)")}) LIMIT 1",
            ).use { ps -> ps.setString(1, zoneCode); ps.setLong(2, self); ps.setString(3, candidate); ps.setString(4, candidate); ps.executeQuery().use { it.next() } }
        }

        /**
         * Dev only. For each group keep the lowest id unchanged and rename the others by appending " #<id>" (the base cut so that the whole name stays within
         * [NAME_MAX] characters): unique under the normalisation (the id differs) and under the exact unique index. One transaction (the caller's), one audit
         * row per renamed cluster (through AuditLog.write, no actor, via `job`; the old and the new name are in the audit row, in the database, never in a log).
         */
        override fun repair(c: Connection, findings: List<Finding>): List<Long> {
            val renamed = ArrayList<Long>()
            for (group in findings) for (id in group.ids.drop(1)) {
                val old = c.prepareStatement("SELECT name FROM app.cluster WHERE id = ? FOR UPDATE").use { ps ->
                    ps.setLong(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                } ?: continue
                // The candidate is " #<id>" (then " #<id>-2", "-3", ...): checked against the exact AND the normalised names of the zone, so it never collides with a row that already carries that name.
                var renamedTo = ""
                for (attempt in 1..50) {
                    val suffix = if (attempt == 1) " #$id" else " #$id-$attempt"
                    val keep = old.codePointCount(0, old.length).coerceAtMost(NAME_MAX - suffix.length)
                    val candidate = old.substring(0, old.offsetByCodePoints(0, keep)).trimEnd() + suffix
                    if (!taken(c, group.where, id, candidate)) { renamedTo = candidate; break }
                }
                if (renamedTo.isEmpty()) error("no free name for cluster $id")
                c.prepareStatement("UPDATE app.cluster SET name = ? WHERE id = ?").use { ps -> ps.setString(1, renamedTo); ps.setLong(2, id); ps.executeUpdate() }
                // Through the platform's one audit writer (it redacts, and the hash chain is the trigger's): the caller's own connection and transaction, no actor (via `job`).
                org.jdbi.v3.core.Jdbi.create(c).open().use { h ->
                    AuditLog.write(
                        h, null, "cluster", id.toString(), "preflight_rename",
                        kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(old)) },
                        kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(renamedTo)) },
                        "Dev pre-flight of V0191: names of one zone that differed only by case, spacing or composition; the lowest id kept its name, this one got an id suffix",
                        null, via = "job",
                    )
                }
                renamed += id
            }
            return renamed
        }

    }

    /**
     * V0199: the Astha tier Titanium. V0199 raises when `Titanium` is already a sub-channel of another channel, or the `titanium` sub_channel code-list item
     * already names another channel (the SQL in `db/preflight` mirrors both guards exactly). Which channel is right is a business decision, so this check has
     * NO automatic repair and fails the deploy in every environment, dev included. Applies while V0199 is pending: the tables exist and Flyway's history has
     * no successful V0199 row.
     */
    object TitaniumTier : Check {
        override val id = "V0199-titanium-tier"
        override val devRepair = false
        override val whereLabel = "finding"
        override val advice = "In the admin portal (Master data, Code lists, sub_channel) set the channel of the Titanium item to Astha, or retire it; if the sub-channel row itself " +
            "holds Titanium under another channel, ask the database owner for a one-off correction (which channel is right is a business decision, so nothing is repaired automatically), then deploy again."
        private const val RESOURCE = "db/preflight/V0199__astha_tiers_cluster_types.sql"

        private val sql: String by lazy {
            val text = TitaniumTier::class.java.classLoader.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("$RESOURCE is not on the classpath")
            text.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        }

        override fun applies(c: Connection): Boolean = c.createStatement().use { st ->
            val tables = st.executeQuery("SELECT to_regclass('app.sub_channel') IS NOT NULL AND to_regclass('app.code_list_item') IS NOT NULL").use { it.next() && it.getBoolean(1) }
            if (!tables) return@use false
            val hasHistory = st.executeQuery("SELECT to_regclass('flyway_schema_history') IS NOT NULL").use { it.next() && it.getBoolean(1) }
            if (!hasHistory) return@use true
            !st.executeQuery("SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success AND version ~ '^0*199$')").use { it.next() && it.getBoolean(1) }
        }

        override fun findings(c: Connection): List<Finding> = c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add(Finding(rs.getString("where_code"), (rs.getArray("ids").array as Array<*>).map { (it as Number).toLong() }))
                }
            }
        }

        /** No repair, so nothing to lock first. */
        override fun lock(c: Connection) {}

        override fun repair(c: Connection, findings: List<Finding>): List<Long> = throw PreflightFailed("pre-flight $id has no automatic repair")
    }
}
