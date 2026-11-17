package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.masterdata.shortRepIdAllowed
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ServerEnv
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.contract.Role
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.Query
import org.jdbi.v3.core.statement.SqlStatement
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import java.io.InputStreamReader
import java.io.Reader
import java.io.Writer
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A failure of the loader, with a message that carries codes and counts ONLY. It never has a cause: a database error's own text echoes the row that
 * broke it (a shop name, an owner, a phone number), so the cause is dropped at the batch boundary ([MasterDataLoader.sanitize]) and nothing that prints
 * this exception, its stack trace or its cause chain can leak a value of the file (finding L3). [retryable] marks a serialization failure or a deadlock: the
 * outlet batch runs again on a fresh snapshot (round 3).
 */
class LoaderException(message: String, internal val retryable: Boolean = false) : RuntimeException(message, null, false, true)

/**
 * The owner's switches and the operator's knobs for one load run. The ENVIRONMENT is not an option: the loader reads the process's own [Settings]
 * (`ARON_ENV`), so a caller cannot claim to be the final account (round 3).
 */
class LoadOptions(
    /** Input rows per batch. One batch is one transaction: it is applied whole or not at all, and a finished batch is never applied again. */
    val batchSize: Int = 5_000,
    /** The owner says the workbook's coordinates are confirmed: pins inside Bangladesh load confirmed and replace a pin the field confirmed (D-MD-26). */
    val coordinatesConfirmed: Boolean = false,
    /** The owner says outlets whose route has no visit plan are STORED (D-MD-03): the route gets visit_kind 'none' and the outlets load as usual. Off: they are HELD. */
    val storeOutletsWithoutVisitPlan: Boolean = true,
    /** Status of an outlet on a route with no rep (the owner's default: archived, so nobody's coverage counts it). */
    val unassignedOutletStatus: String = "archived",
    /** Business date the assignments and the placement history start from. */
    val businessDate: LocalDate = LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")),
    /** Runs the whole load in ONE transaction that is rolled back: the report is exactly the one the commit would produce, and nothing is written. */
    val dryRun: Boolean = false,
    /**
     * The run's id. Give the SAME id, the SAME file AND the SAME options to resume an interrupted run: a different file, or different options, under an
     * existing run id is refused (a finished batch was applied under the old ones).
     */
    val runId: UUID = UUID.randomUUID(),
    /** app_user.id recorded as created_by / updated_by; null for an unattended load. */
    val by: Long? = null,
    /**
     * The explicit "this file is synthetic test data" flag: the only way to run the loader outside the final account. INTERNAL on purpose: no CLI and no API
     * can set it, and the loader's own tests are its only users (round 3; the environment itself comes from [Settings]).
     */
    internal val syntheticTestData: Boolean = false,
    /**
     * Allows SR users with a 3-character id WITHOUT the B2 config gate. Internal on purpose: no CLI and no API can reach it; the only way a short rep id
     * loads is `cfg.release.short_rep_id_version_code` (D-MD-24). The loader's own tests use it to prove the gate's open side.
     */
    internal val shortRepIdsAllowedForTests: Boolean = false,
    /** Test hook: called after each outlet batch has committed (a test throws from it to interrupt a run). */
    internal val afterBatchForTests: ((Int) -> Unit)? = null,
    /** Test hook: called INSIDE an outlet batch's transaction, after its problems were counted and before it writes: (handle, batch number, attempt). */
    internal val insideBatchForTests: ((Handle, Int, Int) -> Unit)? = null,
) {
    /** Every option that changes WHAT a run writes, by name: bound to the run id, so a resume under other options is refused (round 3, R2). */
    internal fun bound(): Map<String, String> = sortedMapOf(
        "batchSize" to batchSize.toString(), "coordinatesConfirmed" to coordinatesConfirmed.toString(),
        "storeOutletsWithoutVisitPlan" to storeOutletsWithoutVisitPlan.toString(), "unassignedOutletStatus" to unassignedOutletStatus,
        "businessDate" to businessDate.toString(), "by" to (by?.toString() ?: ""), "shortRepIdsAllowedForTests" to shortRepIdsAllowedForTests.toString(),
    )
}

/** What a step or an outlet batch did, in counts and issue codes: it is stored with the step, so a resumed run reports it again (round 3, R1). */
private class Outcome {
    var created = 0
    var updated = 0
    var unchanged = 0
    var held = 0
    var error = 0
    val issues = LinkedHashMap<String, StoredIssue>()
    /** Outlet batches only: how many ACCEPTED rows name each (rep id + 0x1f + zone code): a rep's home zone comes from these, never from a rejected row. */
    val repZones = HashMap<String, Int>()

    fun issue(code: String, sample: String? = null, times: Int = 1) {
        val i = issues.getOrPut(code) { StoredIssue(0, ArrayList()) }
        i.n += times
        if (sample != null && i.s.size < LoadReport.SAMPLES) i.s += sample
    }

    fun applyTo(c: LoadReport.Counts, report: LoadReport) {
        c.created += created; c.updated += updated; c.unchanged += unchanged; c.held += held; c.error += error
        issues.forEach { (code, i) -> report.issue(code, null, i.n); i.s.forEach { report.issue(code, it, 0) } }
    }

    fun detail(): String = Json.encodeToString(StoredDetail(issues, repZones))

    companion object {
        fun from(created: Int, updated: Int, unchanged: Int, held: Int, error: Int, detail: String?): Outcome? {
            val d = detail?.let { runCatching { Json.decodeFromString<StoredDetail>(it) }.getOrNull() } ?: return null
            return Outcome().also {
                it.created = created; it.updated = updated; it.unchanged = unchanged; it.held = held; it.error = error
                it.issues.putAll(d.issues); it.repZones.putAll(d.repZones)
            }
        }
    }
}

@Serializable
internal class StoredIssue(var n: Int, val s: MutableList<String>)

@Serializable
internal class StoredDetail(val issues: Map<String, StoredIssue> = emptyMap(), val repZones: Map<String, Int> = emptyMap())

/** A finished unit of work read back from the ledger: outlet batches (`mdload.batch`) and every other step (`mdload.step`). */
private class Stored(val fingerprint: String, val outcome: Outcome)

/** Writes everything it reads to [sink]: pass 1 keeps ONE immutable copy of the file, and pass 2 reads that copy, never the original again (round 3). */
private class TeeReader(private val src: Reader, private val sink: Writer) : Reader() {
    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        val n = src.read(cbuf, off, len)
        if (n > 0) sink.write(cbuf, off, n)
        return n
    }
    override fun close() = src.close()
}

/**
 * The bulk loader for the owner's master-data workbook (M09, M12; the push-2 design in `docs/status/masterdata-fit.md`). It is a server-side TOOL, not an
 * endpoint: the portal import (`POST /v1/admin/outlets/import`) stays the path for a sheet of up to 5,000 rows that an administrator loads by hand.
 *
 * Properties the lane owes the owner, each proved by a test in `MasterDataLoaderTest`:
 *  * **One loader at a time.** A session-level advisory lock; a second loader is refused, never queued, and the lock is re-checked before every transaction:
 *    a lost lock (a dropped connection) stops the run, which can be resumed.
 *  * **Idempotent.** Running the same file again creates nothing, changes nothing and reports every row `unchanged`.
 *  * **Resumable, and bound to its file and its options.** The file is read in batches; each batch is one transaction that also records itself in
 *    `mdload.batch` with a hash of the whole content of its rows, its counts and its issue codes; every hierarchy, rep and assignment chunk records itself in
 *    `mdload.step` the same way. A run is resumed by passing the same `runId`, the SAME file and the SAME options; a different file or different options
 *    under it are refused with a message that names no value. A resumed run's report is the report of an uninterrupted run: a skipped unit reports the counts
 *    and the issue codes it stored (round 3, R1).
 *  * **One file, read once.** Pass 1 keeps an immutable copy of what it read and pass 2 reads the copy, so a file replaced during a long run is never half
 *    applied.
 *  * **Exact dry run.** The whole load runs in one transaction that is rolled back, so the dry run's report is the commit's report, unchanged-versus-updated
 *    included. (It holds its row locks until the rollback: run it in a quiet window, or on a restored copy.)
 *  * **Every input row is counted exactly once** as created, updated, unchanged, held or error. An error is a malformed record, a value a table would refuse
 *    (validated in both passes by [NormRow], one shared rule set), every row of a duplicated outlet code (none wins, finding L2), or a row whose zone,
 *    cluster or route does not resolve or disagree (finding L4). One outlet batch reads one consistent snapshot (REPEATABLE READ), so a concurrent portal edit
 *    can never turn a counted row into an uncounted one: the batch runs again.
 *  * **No personal data leaves the database.** The report and every exception message carry counts and OUR codes only; the database error is replaced at
 *    the batch boundary by its SQLSTATE and constraint name (finding L3).
 *  * **The rules are not re-implemented.** Phones, channels, tiers, geo classes, coordinates, timestamps, visit plans and generated codes all come from
 *    [com.aktcl.aron.backend.masterdata.ImportRules], and the shapes from [com.aktcl.aron.backend.masterdata.MasterRules].
 *  * **Environment guard in code, from the process settings.** Final account, or the internal synthetic-data flag; nothing else runs.
 *
 * Difference from the portal import, on purpose: where the portal REFUSES a row so that a human fixes the sheet (a tier that cannot belong to a channel the
 * row changes), the loader LOADS the outlet without that tier, keeps the field's text in `source_sub_channel` and counts it. The workbook is the owner's
 * truth and a load must not drop an outlet over a tier.
 */
class MasterDataLoader(private val db: Database, private val settings: Settings, private val options: LoadOptions) {
    /** The shared handle of a dry run: its transaction is rolled back at the end. Null on a real run (each batch opens its own transaction). */
    private var dry: Handle? = null

    /** The session that holds the advisory lock; asked before every transaction whether it still does. */
    private var lock: Handle? = null

    /** Per rep id, the zone codes of the rows pass 2 ACCEPTED, with counts: a rep's home zone comes from these (round 3). */
    private val repZones = HashMap<String, HashMap<String, Int>>()

    fun load(open: () -> Reader): LoadReport {
        if (settings.env != ServerEnv.PROD && !options.syntheticTestData) {
            throw LoaderException(
                "the master-data loader runs only in the final account (profile PROD, from the process settings) or with the internal synthetic-test-data flag: " +
                    "the real workbook is fleet-sized personal data and is never loaded into the test account (docs/28, standing rules 1 and 8)",
            )
        }
        val report = LoadReport()
        report.dryRun = options.dryRun
        val t0 = System.nanoTime()
        val holder = db.jdbi.open()
        try {
            val got = holder.createQuery("SELECT pg_try_advisory_lock(:k)").bind("k", LOCK_KEY).mapTo(Boolean::class.javaObjectType).one()
            if (!got) throw LoaderException("another master-data load is running (advisory lock $LOCK_KEY is held); two loaders never run at once")
            lock = holder
            run(open, report)
        } finally {
            lock = null
            runCatching { holder.createQuery("SELECT pg_advisory_unlock(:k)").bind("k", LOCK_KEY).mapTo(Boolean::class.javaObjectType).one() }
            holder.close()
        }
        report.millis = (System.nanoTime() - t0) / 1_000_000
        return report
    }

    /** True while the session that took the advisory lock is alive and still holds it. A pool, proxy or idle timeout can drop that connection silently. */
    private fun assertLockHeld() {
        val holder = lock ?: return
        val held = runCatching {
            holder.createQuery(
                "SELECT count(*)::int FROM pg_locks WHERE locktype = 'advisory' AND granted AND pid = pg_backend_pid() AND ((classid::bigint << 32) | objid::bigint) = :k",
            ).bind("k", LOCK_KEY).mapTo(Int::class.java).one() > 0
        }.getOrDefault(false)
        if (!held) {
            throw LoaderException(
                "the loader's advisory lock $LOCK_KEY was lost (its connection was dropped): nothing more is written, because a second loader may have started; " +
                    "check that no other load runs, then resume with the same run id, file and options",
            )
        }
    }

    private fun run(open: () -> Reader, report: LoadReport) {
        val geoClasses = db.jdbi.withHandle<List<String>, Exception> { h -> h.createQuery("SELECT geo_class FROM app.geo_class_def").mapTo(String::class.java).list() }
        val now = Instant.now()
        val scan = MasterScan(geoClasses, now)
        // ONE immutable copy of the file: pass 1 reads the caller's file once and keeps what it read (compressed, owner-only, in the temp directory, deleted at
        // the end); pass 2 reads the copy. A file that is replaced during a long run is therefore never half applied (round 3).
        val copy = Files.createTempFile("aron-masterdata-", ".csv.gz")
        try {
            GZIPOutputStream(Files.newOutputStream(copy)).bufferedWriter(Charsets.UTF_8).use { sink ->
                MasterCsv(TeeReader(open(), sink)).use { csv ->
                    scan.header(csv)
                    csv.missingColumns().forEach { report.issue("column_missing_in_file", it.name.lowercase()) }
                    while (true) scan.add(csv.next() ?: break)
                }
            }
            scan.report(report)
            val fileHash = scan.fileDigest
            val reopen: () -> Reader = { InputStreamReader(GZIPInputStream(Files.newInputStream(copy)), Charsets.UTF_8) }

            if (options.dryRun) {
                val h = db.jdbi.open()
                dry = h
                try {
                    h.begin()
                    pass2(scan, fileHash, reopen, geoClasses, now, report)
                } finally {
                    runCatching { h.rollback() }
                    h.close()
                    dry = null
                }
            } else {
                pass2(scan, fileHash, reopen, geoClasses, now, report)
                tx("finish the run") { h ->
                    h.createUpdate("UPDATE mdload.run SET finished_at = now() WHERE run_id = :r").bind("r", options.runId).execute()
                    h.execute("DROP TABLE IF EXISTS mdload.outlet_row")
                }
            }
        } finally {
            runCatching { Files.deleteIfExists(copy) }
        }
    }

    private fun pass2(scan: MasterScan, fileHash: String, open: () -> Reader, geoClasses: List<String>, now: Instant, report: LoadReport) {
        tx("prepare the staging tables") { h -> ensureStaging(h); openRun(h, fileHash) }
        val steps = if (options.dryRun) emptyMap() else readSteps()
        writeHierarchy(scan, report, steps)
        val reps = planReps(scan)
        loadOutlets(scan, open, geoClasses, now, report, reps)
        writeReps(scan, report, reps, steps)
    }

    /**
     * One unit of work: its own transaction on a real run, the shared (rolled-back) transaction on a dry run. Any failure leaves as a [LoaderException]. The
     * advisory lock is re-checked first. [snapshot] reads one consistent snapshot (REPEATABLE READ; not on a dry run, whose single transaction is the snapshot).
     */
    private fun <R> tx(what: String, snapshot: Boolean = false, block: (Handle) -> R): R {
        assertLockHeld()
        return try {
            val shared = dry
            when {
                shared != null -> block(shared)
                snapshot -> db.jdbi.inTransaction<R, Exception>(TransactionIsolationLevel.REPEATABLE_READ) { h -> block(h) }
                else -> db.jdbi.inTransaction<R, Exception> { h -> block(h) }
            }
        } catch (e: LoaderException) {
            throw e
        } catch (e: Throwable) {
            throw sanitize(what, e)
        }
    }

    /** The only thing taken from a failure: its class, SQLSTATE and constraint NAME. Never its message, never its cause (they carry the row). */
    private fun sanitize(what: String, e: Throwable): LoaderException {
        var state: String? = null
        var constraint: String? = null
        var t: Throwable? = e
        var hops = 0
        while (t != null && hops++ < 16) {
            if (t is java.sql.SQLException) {
                state = t.sqlState
                constraint = runCatching {
                    val m = t.javaClass.getMethod("getServerErrorMessage").invoke(t)
                    m?.javaClass?.getMethod("getConstraint")?.invoke(m) as String?
                }.getOrNull()
                break
            }
            t = t.cause
        }
        return LoaderException(
            "$what failed (${e.javaClass.simpleName}${state?.let { ", SQLSTATE $it" }.orEmpty()}${constraint?.let { ", constraint $it" }.orEmpty()}); the batch was rolled back and the run can be resumed",
            retryable = state == "40001" || state == "40P01",
        )
    }

    // ---- staging ------------------------------------------------------------------------------------------------------------------------------

    /**
     * The loader's own schema. `mdload.run`, `mdload.batch` and `mdload.step` are small and logged (they make a run resumable and report it again) and hold
     * no value of the file but its hash and OUR issue codes. `mdload.outlet_row` is UNLOGGED and holds the file's values for one batch at a time: it is DROPPED
     * at the end of a finished run and, after a failed run, at the start of the next one (a leftover never survives into a new run). No migration owns this
     * schema: it belongs to the tool.
     */
    private fun ensureStaging(h: Handle) {
        h.execute("CREATE SCHEMA IF NOT EXISTS mdload")
        h.execute(
            "CREATE TABLE IF NOT EXISTS mdload.run (run_id uuid PRIMARY KEY, started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz, options text NOT NULL, file_hash text NOT NULL)",
        )
        h.execute(
            "CREATE TABLE IF NOT EXISTS mdload.batch (run_id uuid NOT NULL, batch_no int NOT NULL, rows int NOT NULL, created int NOT NULL, updated int NOT NULL, " +
                "unchanged int NOT NULL, errors int NOT NULL, fingerprint text NOT NULL, done_at timestamptz NOT NULL DEFAULT now(), detail jsonb, PRIMARY KEY (run_id, batch_no))",
        )
        h.execute("ALTER TABLE mdload.batch ADD COLUMN IF NOT EXISTS detail jsonb")
        h.execute(
            "CREATE TABLE IF NOT EXISTS mdload.step (run_id uuid NOT NULL, step text NOT NULL, fingerprint text NOT NULL, created int NOT NULL, updated int NOT NULL, " +
                "unchanged int NOT NULL, held int NOT NULL, errors int NOT NULL, detail jsonb NOT NULL, done_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (run_id, step))",
        )
        h.execute("DROP TABLE IF EXISTS mdload.outlet_row")
        h.execute(
            "CREATE UNLOGGED TABLE mdload.outlet_row (run_id uuid NOT NULL, batch_no int NOT NULL, row_no bigint NOT NULL, code text NOT NULL, " +
                "name text NOT NULL, owner_name text NOT NULL, phone text, source_phone text, address text, zone_code text NOT NULL, route_code text, " +
                "cluster_name text NOT NULL, channel text, sub_channel text, geo_class text, lat double precision, lng double precision, in_bd boolean NOT NULL, " +
                "src_created timestamptz, src_updated timestamptz, status text NOT NULL, rep_id text, generated boolean NOT NULL, PRIMARY KEY (run_id, batch_no, row_no))",
        )
    }

    /**
     * Binds the run id to its file AND to its options: an existing run id with another file's hash, or with other options, is refused (findings L1, R2). The
     * messages name no value of the file and no value of an option: only the hash prefixes and the NAMES of the options that differ.
     */
    private fun openRun(h: Handle, fileHash: String) {
        val wanted = options.bound()
        h.createUpdate("INSERT INTO mdload.run (run_id, options, file_hash) VALUES (:r, :o, :f) ON CONFLICT (run_id) DO NOTHING")
            .bind("r", options.runId).bind("f", fileHash).bind("o", wanted.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .execute()
        val row = h.createQuery("SELECT file_hash, options FROM mdload.run WHERE run_id = :r").bind("r", options.runId)
            .map { rs, _ -> rs.getString(1) to rs.getString(2) }.one()
        if (row.first != fileHash) {
            throw LoaderException(
                "run ${options.runId} was started on a different file (its file hash is ${row.first.take(12)}, this file's is ${fileHash.take(12)}): a run id resumes only the " +
                    "file it began with. Start a NEW run id for the corrected file; the load is idempotent, so a new run over it is safe.",
            )
        }
        val recorded = row.second.lines().filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val differing = (wanted.keys + recorded.keys).toSortedSet().filter { wanted[it] != recorded[it] }
        if (differing.isNotEmpty()) {
            throw LoaderException(
                "run ${options.runId} was started with different options (${differing.joinToString(", ")}): the batches it finished were applied under the options it began " +
                    "with, so a resume under others would mix both in one report. Resume with the original options, or start a NEW run id (the load is idempotent, so a new run is safe).",
            )
        }
    }

    // ---- the ledger of finished steps -----------------------------------------------------------------------------------------------------------

    private fun readSteps(): Map<String, Stored> = db.jdbi.withHandle<Map<String, Stored>, Exception> { h ->
        h.createQuery("SELECT step, fingerprint, created, updated, unchanged, held, errors, detail::text FROM mdload.step WHERE run_id = :r").bind("r", options.runId)
            .map { rs, _ ->
                val o = Outcome.from(rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8))
                if (o == null) null else rs.getString(1) to Stored(rs.getString(2), o)
            }.list().filterNotNull().toMap()
    }

    private fun fp(parts: List<String?>): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update((it ?: "\u0000").toByteArray()); md.update(31) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * One chunk of hierarchy, rep or assignment work, ONE transaction that also records itself in `mdload.step` with its counts and issue codes. On a resume a
     * chunk that is on the ledger with the same fingerprint is not written again: its stored counts and issue codes go into the report, so the resumed
     * run's report is the uninterrupted run's (round 3, R1). A dry run records and skips nothing.
     */
    private fun step(entity: String, key: String, fingerprint: String, report: LoadReport, steps: Map<String, Stored>, work: (Handle, Outcome) -> Unit) {
        val prior = steps[key]
        if (prior != null && prior.fingerprint == fingerprint) {
            prior.outcome.applyTo(report.of(entity), report)
            report.stepsSkipped++
            return
        }
        val o = Outcome()
        tx("write $entity") { h ->
            work(h, o)
            if (!options.dryRun) {
                h.createUpdate(
                    "INSERT INTO mdload.step (run_id, step, fingerprint, created, updated, unchanged, held, errors, detail) VALUES (:r, :k, :f, :c, :u, :un, :h, :e, :d::jsonb) " +
                        "ON CONFLICT (run_id, step) DO UPDATE SET fingerprint = excluded.fingerprint, created = excluded.created, updated = excluded.updated, " +
                        "unchanged = excluded.unchanged, held = excluded.held, errors = excluded.errors, detail = excluded.detail, done_at = now()",
                ).bind("r", options.runId).bind("k", key).bind("f", fingerprint).bind("c", o.created).bind("u", o.updated).bind("un", o.unchanged)
                    .bind("h", o.held).bind("e", o.error).bind("d", o.detail()).execute()
            }
        }
        o.applyTo(report.of(entity), report)
    }

    // ---- hierarchy ----------------------------------------------------------------------------------------------------------------------------

    /**
     * Counts an upsert whose RETURNING gives one row per written row, true when it was an INSERT. [total] is how many inputs it had; [refused] of the inputs
     * exist but were deliberately not written (a route stored in another zone): they are errors, never "unchanged".
     */
    private fun count(h: Handle, o: Outcome, total: Int, presentSql: String, codes: List<String>, q: Query, refused: Int = 0) {
        val written = q.mapTo(Boolean::class.javaObjectType).list()
        val created = written.count { it }
        o.created += created
        o.updated += written.size - created
        val present = h.createQuery(presentSql).bindArray("codes", String::class.java, codes).mapTo(Int::class.java).one()
        o.unchanged += present - written.size - refused
        o.error += total - present + refused
    }

    private fun <T : SqlStatement<T>> T.arr(name: String, values: List<String?>): T = bindArray(name, String::class.java, values)
    private fun <T : SqlStatement<T>> T.bindAny(name: String, v: Any?): T = if (v == null) bindNull(name, java.sql.Types.NULL) else bind(name, v)

    private fun writeHierarchy(scan: MasterScan, report: LoadReport, steps: Map<String, Stored>) {
        val by = options.by
        scan.wings.values.chunked(CHUNK).forEachIndexed { i, ws ->
            val codes = ws.map { it.code }
            step("wing", "wing:$i", fp(codes), report, steps) { h, o ->
                count(
                    h, o, ws.size, "SELECT count(*)::int FROM app.wing WHERE code = ANY(:codes)", codes,
                    h.createQuery(
                        "INSERT INTO app.wing (code, name, created_by, updated_by) SELECT s.code, s.name, :by, :by FROM unnest(:codes::text[], :names::text[]) AS s(code, name) " +
                            "ON CONFLICT (code) DO UPDATE SET name = excluded.name, updated_by = :by WHERE app.wing.name IS DISTINCT FROM excluded.name RETURNING (xmax = 0)",
                    ).arr("codes", codes).arr("names", ws.map { it.name.ifEmpty { it.code } }).bindAny("by", by),
                )
            }
        }
        child(report, steps, scan.divisions, "division", "wing", "wing_id")
        child(report, steps, scan.territories, "territory", "division", "division_id")
        child(report, steps, scan.houses, "house", "territory", "territory_id")
        scan.zones.values.chunked(CHUNK).forEachIndexed { i, zs ->
            val codes = zs.map { it.code }
            step("zone", "zone:$i", fp(codes), report, steps) { h, o ->
                count(
                    h, o, zs.size, "SELECT count(*)::int FROM app.zone WHERE code = ANY(:codes)", codes,
                    h.createQuery(
                        """
                        INSERT INTO app.zone (code, name, territory_id, house_id, created_by, updated_by)
                        SELECT s.code, s.name, t.id, hh.id, :by, :by
                          FROM unnest(:codes::text[], :names::text[], :parents::text[], :houses::text[]) AS s(code, name, pcode, hcode)
                          JOIN app.territory t ON t.code = s.pcode
                          LEFT JOIN app.house hh ON hh.code = s.hcode
                        ON CONFLICT (code) DO UPDATE SET name = excluded.name, territory_id = excluded.territory_id,
                             house_id = COALESCE(excluded.house_id, app.zone.house_id), updated_by = :by
                         WHERE app.zone.name IS DISTINCT FROM excluded.name OR app.zone.territory_id IS DISTINCT FROM excluded.territory_id
                            OR (excluded.house_id IS NOT NULL AND app.zone.house_id IS DISTINCT FROM excluded.house_id)
                        RETURNING (xmax = 0)
                        """.trimIndent(),
                    ).arr("codes", codes).arr("names", zs.map { it.name.ifEmpty { it.code } }).arr("parents", zs.map { it.parent() })
                        .arr("houses", zs.map { z -> scan.zoneHouses[z.code]?.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key }).bindAny("by", by),
                )
            }
        }
        scan.routes.values.chunked(CHUNK).forEachIndexed { i, rs ->
            val codes = rs.map { it.code }
            step("route", "route:$i", fp(codes), report, steps) { h, o ->
                val plans = rs.map { scan.planOf(it) }
                // A route is NEVER moved to another zone by the loader, and a route stored in another zone than the file says is NEVER renamed or re-planned
                // either (round 3): its outlets carry the zone, and changing the route alone would leave every outlet of it that is not in this file with a
                // route that is not what the portal shows. It is reported by code and counted an ERROR; the outlet rows that name it are errors too
                // (route_is_not_in_the_zone_of_the_row) and move nothing.
                val elsewhere = h.createQuery(
                    "SELECT s.code FROM unnest(:codes::text[], :zones::text[]) AS s(code, zcode) JOIN app.route r ON r.code = s.code JOIN app.zone z ON z.id = r.zone_id WHERE z.code <> s.zcode",
                ).arr("codes", codes).arr("zones", rs.map { it.zone() }).mapTo(String::class.java).list()
                elsewhere.forEach { o.issue("route_stored_in_another_zone_than_the_file_says", it) }
                count(
                    h, o, rs.size, "SELECT count(*)::int FROM app.route WHERE code = ANY(:codes)", codes,
                    h.createQuery(
                        """
                        INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, created_by, updated_by)
                        SELECT s.code, s.name, s.label, z.id, 'sr', s.kind, s.mask::smallint, :by, :by
                          FROM unnest(:codes::text[], :names::text[], :labels::text[], :zones::text[], :kinds::text[], :masks::int[]) AS s(code, name, label, zcode, kind, mask)
                          JOIN app.zone z ON z.code = s.zcode
                        ON CONFLICT (code) DO UPDATE SET name = excluded.name, display_label = excluded.display_label,
                             visit_kind = excluded.visit_kind, visit_days_mask = excluded.visit_days_mask, updated_by = :by
                         WHERE app.route.zone_id = excluded.zone_id
                           AND (app.route.name IS DISTINCT FROM excluded.name OR app.route.display_label IS DISTINCT FROM excluded.display_label
                             OR app.route.visit_kind IS DISTINCT FROM excluded.visit_kind OR app.route.visit_days_mask IS DISTINCT FROM excluded.visit_days_mask)
                        RETURNING (xmax = 0)
                        """.trimIndent(),
                    ).arr("codes", codes).arr("names", rs.map { it.name }).arr("labels", plans.map { it.label })
                        .arr("zones", rs.map { it.zone() }).arr("kinds", plans.map { it.kind })
                        .bindArray("masks", Int::class.javaObjectType, plans.map { it.mask }).bindAny("by", by),
                    refused = elsewhere.size,
                )
            }
        }
        scan.clusters.keys.chunked(CHUNK).forEachIndexed { i, keys ->
            step("cluster", "cluster:$i", fp(keys), report, steps) { h, o ->
                val zones = keys.map { it.substringBefore('\u001f') }
                val names = keys.map { it.substringAfter('\u001f') }
                val types = keys.map { scan.clusterType(it) }
                val written = h.createQuery(
                    """
                    INSERT INTO app.cluster (zone_id, name, cluster_type, created_by, updated_by)
                    SELECT z.id, s.name, s.ctype, :by, :by FROM unnest(:zones::text[], :names::text[], :types::text[]) AS s(zcode, name, ctype)
                      JOIN app.zone z ON z.code = s.zcode
                    ON CONFLICT (zone_id, name) DO UPDATE SET cluster_type = excluded.cluster_type, updated_by = :by
                     WHERE app.cluster.cluster_type IS DISTINCT FROM excluded.cluster_type
                    RETURNING (xmax = 0)
                    """.trimIndent(),
                ).arr("zones", zones).arr("names", names).arr("types", types).bindAny("by", by).mapTo(Boolean::class.javaObjectType).list()
                val created = written.count { it }
                o.created += created
                o.updated += written.size - created
                val present = h.createQuery("SELECT count(*)::int FROM app.cluster c JOIN app.zone z ON z.id = c.zone_id JOIN unnest(:zones::text[], :names::text[]) AS s(zcode, name) ON s.zcode = z.code AND s.name = c.name")
                    .arr("zones", zones).arr("names", names).mapTo(Int::class.java).one()
                o.unchanged += present - written.size
                o.error += keys.size - present
            }
        }
    }

    private fun child(report: LoadReport, steps: Map<String, Stored>, nodes: Map<String, MasterScan.Node>, table: String, parentTable: String, parentColumn: String) {
        nodes.values.chunked(CHUNK).forEachIndexed { i, ns ->
            val codes = ns.map { it.code }
            step(table, "$table:$i", fp(codes), report, steps) { h, o ->
                count(
                    h, o, ns.size, "SELECT count(*)::int FROM app.$table WHERE code = ANY(:codes)", codes,
                    h.createQuery(
                        """
                        INSERT INTO app.$table (code, name, $parentColumn, created_by, updated_by)
                        SELECT s.code, s.name, p.id, :by, :by FROM unnest(:codes::text[], :names::text[], :parents::text[]) AS s(code, name, pcode)
                          JOIN app.$parentTable p ON p.code = s.pcode
                        ON CONFLICT (code) DO UPDATE SET name = excluded.name, $parentColumn = excluded.$parentColumn, updated_by = :by
                         WHERE app.$table.name IS DISTINCT FROM excluded.name OR app.$table.$parentColumn IS DISTINCT FROM excluded.$parentColumn
                        RETURNING (xmax = 0)
                        """.trimIndent(),
                    ).arr("codes", codes).arr("names", ns.map { it.name.ifEmpty { it.code } }).arr("parents", ns.map { it.parent() }).bindAny("by", options.by),
                )
            }
        }
    }

    // ---- reps: which of them can be loaded, decided BEFORE the outlets (an outlet is active only when a rep that WILL load serves its route) -----------

    /** The reps of the file that cannot load: held for the installed app (B2), or ids that name a user of another role. Neither makes a route "have a rep". */
    private class RepPlan(val held: Set<String>, val foreign: Set<String>) {
        fun loadable(id: String) = id !in held && id !in foreign
    }

    private fun planReps(scan: MasterScan): RepPlan {
        // The same question the portal asks (D-MD-24), read on a dry run as well so the dry run says what the real run would do. The test-only override is
        // internal to this module: no CLI and no API can pass it.
        val allowShort = options.shortRepIdsAllowedForTests || tx("read the short-rep-id gate") { h -> shortRepIdAllowed(h, Role.SR) }
        val held = scan.reps.values.filter { it.id.length < com.aktcl.aron.backend.masterdata.MasterRules.APP_MIN_USERNAME_LENGTH && !allowShort }.map { it.id }.toSet()
        // A rep id that names an existing user of ANOTHER role is a row error and never overwrites that user.
        val foreign = HashSet<String>()
        scan.reps.keys.filter { it !in held }.chunked(CHUNK).forEach { ids ->
            foreign += tx("check the roles of existing users") { h ->
                h.createQuery("SELECT lower(username) FROM app.app_user WHERE lower(username) = ANY(:ids) AND role <> 'SR'").bindArray("ids", String::class.java, ids).mapTo(String::class.java).set()
            }
        }
        return RepPlan(held, foreign)
    }

    // ---- outlets ------------------------------------------------------------------------------------------------------------------------------

    /** A row that passed [NormRow.of], with the status pass 1 decided for it. */
    private class Staged(val r: NormRow, val status: String)

    private fun loadOutlets(scan: MasterScan, open: () -> Reader, geoClasses: List<String>, now: Instant, report: LoadReport, reps: RepPlan) {
        val c = report.of("outlet")
        // A finished batch is skipped only when it held EXACTLY these rows (a hash of every loaded value of every row), and the run id is bound to the file hash
        // and to the options already, so a skipped batch is the very batch that ran (finding L1). Its stored counts and issue codes go into the report (R1).
        val done: Map<Int, Stored> = if (options.dryRun) emptyMap() else db.jdbi.withHandle<Map<Int, Stored>, Exception> { h ->
            h.createQuery("SELECT batch_no, fingerprint, created, updated, unchanged, errors, detail::text FROM mdload.batch WHERE run_id = :r").bind("r", options.runId)
                .map { rs, _ ->
                    val o = Outcome.from(rs.getInt(3), rs.getInt(4), rs.getInt(5), 0, rs.getInt(6), rs.getString(7))
                    if (o == null) null else rs.getInt(1) to Stored(rs.getString(2), o)
                }.list().filterNotNull().toMap()
        }
        var batchNo = 0
        val batch = ArrayList<Staged>(options.batchSize)
        MasterCsv(open()).use { csv ->
            while (true) {
                val row = csv.next()
                if (row != null) {
                    stage(row, scan, geoClasses, now, report, reps)?.let { batch += it }
                    if (batch.size < options.batchSize) continue
                }
                if (batch.isNotEmpty()) {
                    batchNo++
                    val fingerprint = fingerprint(batch)
                    val prior = done[batchNo]
                    if (prior != null && prior.fingerprint == fingerprint) {
                        prior.outcome.applyTo(c, report)
                        prior.outcome.repZones.forEach { (k, n) -> addRepZone(k, n) }
                        report.batchesSkipped++
                    } else applyBatch(batchNo, batch, fingerprint, report)
                    batch.clear()
                    options.afterBatchForTests?.invoke(batchNo)
                }
                if (row == null) break
            }
        }
    }

    private fun addRepZone(key: String, n: Int) {
        repZones.getOrPut(key.substringBefore('\u001f')) { HashMap() }.merge(key.substringAfter('\u001f'), n, Int::plus)
    }

    /** A hash of EVERY loaded value of the batch's rows, in order. */
    private fun fingerprint(batch: List<Staged>): String {
        val md = MessageDigest.getInstance("SHA-256")
        batch.forEach { it.r.digestInto(md); md.update(it.status.toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads one input row through [NormRow.of] (the same validation as pass 1) and counts what is not an outlet to load: a malformed record, a value a table
     * would refuse, every row of a duplicated code (none wins), a route without a visit plan when the owner has not said they are stored.
     */
    private fun stage(raw: MasterCsv.Row, scan: MasterScan, geoClasses: List<String>, now: Instant, report: LoadReport, reps: RepPlan): Staged? {
        val c = report.of("outlet")
        val res = NormRow.of(raw, geoClasses, now)
        res.notes.forEach { report.issue(it) }
        val r = res.row
        if (r == null) {
            c.error++
            res.errors.forEach { report.issue(it) }
            return null
        }
        if (r.code in scan.duplicateCodes) {
            c.error++
            report.issue("outlet_code_twice_in_the_file", r.code)
            return null
        }
        // Not an error: app.outlet.code is unique case-sensitively, so both rows load; but a shop keyed both ways in the workbook loads twice, and the owner sees it.
        if (scan.hasCaseTwin(r.code)) report.issue("outlet_code_differs_from_another_only_in_case", r.code)
        if (!options.storeOutletsWithoutVisitPlan && r.route != null && scan.routes[r.route]?.let { scan.planOf(it).kind } == "none") {
            c.held++
            report.issue("outlet_held_route_has_no_visit_plan", r.route)
            return null
        }
        // A rep that is held for the installed app or is another role's user does not serve the route: the route "has a rep" only when one that WILL load does.
        val hasRep = r.route != null && scan.routes[r.route]?.reps?.keys?.any { reps.loadable(it) } == true
        return Staged(r, if (hasRep) "active" else options.unassignedOutletStatus)
    }

    /**
     * One batch, one transaction on one consistent snapshot (REPEATABLE READ): stage, create, update, histories, counts, and the batch's own row in
     * `mdload.batch` with its counts and issue codes. A concurrent portal edit of a row of the batch is then either not seen at all or a serialization failure
     * (SQLSTATE 40001), and the batch runs again on a fresh snapshot, where the row is counted for what it has become. Counts reach the report only after the
     * transaction committed.
     */
    private fun applyBatch(batchNo: Int, batch: List<Staged>, fingerprint: String, report: LoadReport) {
        var attempt = 0
        while (true) {
            attempt++
            val o = Outcome()
            try {
                applyBatchOnce(batchNo, batch, fingerprint, attempt, o)
            } catch (e: LoaderException) {
                if (e.retryable && attempt < BATCH_ATTEMPTS && !options.dryRun) continue
                throw e
            }
            o.applyTo(report.of("outlet"), report)
            o.repZones.forEach { (k, n) -> addRepZone(k, n) }
            report.batchesApplied++
            return
        }
    }

    private fun applyBatchOnce(batchNo: Int, batch: List<Staged>, fingerprint: String, attempt: Int, o: Outcome) {
        tx("apply outlet batch $batchNo", snapshot = true) { h ->
            h.createUpdate("DELETE FROM mdload.outlet_row WHERE run_id = :r AND batch_no = :b").bind("r", options.runId).bind("b", batchNo).execute()
            stageInsert(h, batchNo, batch)
            // Rows that cannot be applied are ERRORS, counted by reason (never "unchanged"): zone or cluster that do not resolve, a route that does not exist
            // or belongs to another zone, an outlet that was merged.
            var problems = 0
            h.createQuery("$RESOLVED SELECT problem, count(*)::int, (array_agg(code ORDER BY row_no))[1 : ${LoadReport.SAMPLES}] FROM f WHERE problem IS NOT NULL GROUP BY problem ORDER BY problem")
                .bind("run", options.runId).bind("b", batchNo)
                .map { rs, _ -> Triple(rs.getString(1), rs.getInt(2), (rs.getArray(3).array as Array<*>).map { it.toString() }) }.list()
                .forEach { (problem, n, codes) -> problems += n; o.issue(problem, null, n); codes.forEach { o.issue(problem, it, 0) } }
            // the accepted rows' reps and zones: a rep's home zone is decided by these, never by a row this batch rejected
            h.createQuery("$RESOLVED SELECT rep_id, zone_code, count(*)::int FROM f WHERE problem IS NULL AND rep_id IS NOT NULL GROUP BY rep_id, zone_code")
                .bind("run", options.runId).bind("b", batchNo)
                .map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getInt(3)) }.list()
                .forEach { (rep, zone, n) -> o.repZones.merge("$rep\u001f$zone", n, Int::plus) }
            options.insideBatchForTests?.invoke(h, batchNo, attempt)
            val reactivated = h.createQuery("$RESOLVED SELECT count(*)::int FROM f WHERE problem IS NULL AND outlet_id IS NOT NULL AND cur_status = 'archived' AND cur_closed IS NULL AND status = 'active'")
                .bind("run", options.runId).bind("b", batchNo).mapTo(Int::class.java).one()
            val made = h.createQuery(CREATE_SQL).bind("run", options.runId).bind("b", batchNo).bind("confirm", options.coordinatesConfirmed)
                .bindAny("by", options.by).map { rs, _ -> rs.getString(1) to rs.getBoolean(2) }.list()
            val created = made.size
            val updated = h.createQuery(UPDATE_SQL).bind("run", options.runId).bind("b", batchNo).bind("confirm", options.coordinatesConfirmed)
                .bindAny("by", options.by).mapTo(Int::class.java).list().size
            val candidates = h.createUpdate(CANDIDATE_SQL).bind("run", options.runId).bind("b", batchNo).bind("confirm", options.coordinatesConfirmed).bindAny("by", options.by).execute()
            h.createUpdate(PIN_HISTORY_SQL).bind("run", options.runId).bind("b", batchNo).bindAny("by", options.by).execute()
            h.createUpdate(PLACEMENT_SQL).bind("run", options.runId).bind("b", batchNo).bind("today", options.businessDate).bindAny("by", options.by).execute()
            o.created = created
            o.updated = updated
            o.error = problems
            o.unchanged = batch.size - problems - created - updated
            if (candidates > 0) o.issue("master_pin_kept_as_a_candidate", null, candidates)
            if (reactivated > 0) o.issue("outlet_reactivated_its_route_has_a_rep_now", null, reactivated)
            // A codeless row is keyed by a code made from its name and owner: a LATER file with an edited name creates a second shop and leaves the first. Every
            // shop created under a generated code is therefore reported, so the owner can look for a renamed twin.
            made.filter { it.second }.forEach { o.issue("outlet_created_under_a_generated_code", it.first) }
            if (!options.dryRun) {
                h.createUpdate(
                    "INSERT INTO mdload.batch (run_id, batch_no, rows, created, updated, unchanged, errors, fingerprint, detail) VALUES (:r, :b, :n, :c, :u, :un, :e, :f, :d::jsonb) " +
                        "ON CONFLICT (run_id, batch_no) DO UPDATE SET rows = excluded.rows, created = excluded.created, updated = excluded.updated, " +
                        "unchanged = excluded.unchanged, errors = excluded.errors, fingerprint = excluded.fingerprint, detail = excluded.detail, done_at = now()",
                ).bind("r", options.runId).bind("b", batchNo).bind("n", batch.size).bind("c", created).bind("u", updated)
                    .bind("un", o.unchanged).bind("e", problems).bind("f", fingerprint).bind("d", o.detail()).execute()
            }
            h.createUpdate("DELETE FROM mdload.outlet_row WHERE run_id = :r AND batch_no = :b").bind("r", options.runId).bind("b", batchNo).execute()
        }
    }

    private fun stageInsert(h: Handle, batchNo: Int, batch: List<Staged>) {
        h.createUpdate(
            """
            INSERT INTO mdload.outlet_row (run_id, batch_no, row_no, code, name, owner_name, phone, source_phone, address, zone_code, route_code, cluster_name,
                                           channel, sub_channel, geo_class, lat, lng, in_bd, src_created, src_updated, status, rep_id, generated)
            SELECT :run, :b, * FROM unnest(:rowno::bigint[], :code::text[], :name::text[], :owner::text[], :phone::text[], :sphone::text[], :addr::text[],
                                           :zone::text[], :route::text[], :cluster::text[], :channel::text[], :sub::text[], :geo::text[],
                                           :lat::float8[], :lng::float8[], :inbd::boolean[], :screated::timestamptz[], :supdated::timestamptz[], :status::text[],
                                           :rep::text[], :gen::boolean[])
            """.trimIndent(),
        ).bind("run", options.runId).bind("b", batchNo)
            .bindArray("rowno", Long::class.javaObjectType, batch.map { it.r.rowNo })
            .arr("code", batch.map { it.r.code }).arr("name", batch.map { it.r.name }).arr("owner", batch.map { it.r.owner })
            .arr("phone", batch.map { it.r.phone }).arr("sphone", batch.map { it.r.sourcePhone }).arr("addr", batch.map { it.r.address })
            .arr("zone", batch.map { it.r.zone }).arr("route", batch.map { it.r.route }).arr("cluster", batch.map { it.r.cluster })
            .arr("channel", batch.map { it.r.channel }).arr("sub", batch.map { it.r.subChannel }).arr("geo", batch.map { it.r.geoClass })
            .bindArray("lat", Double::class.javaObjectType, batch.map { it.r.lat }).bindArray("lng", Double::class.javaObjectType, batch.map { it.r.lng })
            .bindArray("inbd", Boolean::class.javaObjectType, batch.map { it.r.inBd })
            // Instants travel as ISO-8601 text and are cast by the statement (::timestamptz[]): JDBI has no array binder for java.sql.Timestamp.
            .arr("screated", batch.map { it.r.created?.toString() }).arr("supdated", batch.map { it.r.updated?.toString() })
            .arr("status", batch.map { it.status }).arr("rep", batch.map { it.r.repId })
            .bindArray("gen", Boolean::class.javaObjectType, batch.map { it.r.codeGenerated })
            .execute()
    }

    // ---- reps and assignments -----------------------------------------------------------------------------------------------------------------

    private fun writeReps(scan: MasterScan, report: LoadReport, plan: RepPlan, steps: Map<String, Stored>) {
        val rc = report.of("sales_rep")
        val ac = report.of("route_assignment")
        plan.held.sorted().forEach { report.issue("sales_rep_held_needs_app_release", it) }
        rc.held += plan.held.size
        plan.foreign.forEach { report.issue("sales_rep_is_a_user_of_another_role", it) }
        rc.error += plan.foreign.size
        val loadable = scan.reps.values.filter { plan.loadable(it.id) }
        loadable.chunked(CHUNK).forEachIndexed { i, rs ->
            val ids = rs.map { it.id }
            step("sales_rep", "rep:$i", fp(ids), report, steps) { h, o ->
                count(
                    h, o, rs.size, "SELECT count(*)::int FROM app.app_user WHERE lower(username) = ANY(:codes)", ids,
                    h.createQuery(
                        """
                        INSERT INTO app.app_user (username, full_name, role, employee_code, phone, locale, home_zone_id, must_change_password, created_by, updated_by)
                        SELECT s.id, s.name, 'SR', s.id, s.phone, 'bn', z.id, true, :by, :by
                          FROM unnest(:codes::text[], :names::text[], :phones::text[], :zones::text[]) AS s(id, name, phone, zcode)
                          LEFT JOIN app.zone z ON z.code = s.zcode
                        ON CONFLICT (lower(username)) DO UPDATE SET full_name = excluded.full_name, employee_code = excluded.employee_code,
                             phone = COALESCE(excluded.phone, app.app_user.phone), home_zone_id = COALESCE(excluded.home_zone_id, app.app_user.home_zone_id), updated_by = :by
                         WHERE app.app_user.role = 'SR' AND (app.app_user.full_name IS DISTINCT FROM excluded.full_name
                            OR (excluded.phone IS NOT NULL AND app.app_user.phone IS DISTINCT FROM excluded.phone)
                            OR (excluded.home_zone_id IS NOT NULL AND app.app_user.home_zone_id IS DISTINCT FROM excluded.home_zone_id))
                        RETURNING (xmax = 0)
                        """.trimIndent(),
                    ).arr("codes", ids).arr("names", rs.map { it.name.ifEmpty { it.id } }).arr("phones", rs.map { it.phone })
                        // the home zone is the zone most of the rep's ACCEPTED rows name: a row pass 2 rejected never moves a rep (round 3)
                        .arr("zones", rs.map { r -> repZones[r.id]?.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key }).bindAny("by", options.by),
                )
            }
        }
        // assignments: the primary of each route, then the others as open-ended covers; an assignment that is already open is left alone. Every pair of a held
        // rep (primary OR cover) is counted held, every pair of a rep that is another role's user an error.
        class Pair4(val route: String, val user: String, val kind: String, val zone: String)
        val pairs = ArrayList<Pair4>()
        for (r in scan.routes.values) {
            val (primary, covers) = scan.repsOf(r)
            val all = listOfNotNull(primary?.let { it to "primary" }) + covers.map { it to "cover" }
            for ((rep, kind) in all) when {
                rep in plan.held -> ac.held++
                rep in plan.foreign -> ac.error++
                else -> pairs += Pair4(r.code, rep, kind, r.zone())
            }
        }
        pairs.chunked(CHUNK).forEachIndexed { i, ps ->
            step("route_assignment", "assign:$i", fp(ps.flatMap { listOf(it.route, it.user, it.kind, it.zone) }), report, steps) { h, o ->
                // Every pair is classified ONCE, in the order below, and counted for what it is: a pair whose route was not created, or is stored in another zone
                // than the file says, is an ERROR; a primary that differs from the route's open primary is HELD with its code (the portal owns a reassignment
                // once the route exists) and is never "unchanged"; an assignment that is already open is unchanged.
                h.createQuery(
                    """
                    WITH s AS (SELECT * FROM unnest(:routes::text[], :users::text[], :kinds::text[], :zones::text[]) AS s(rcode, username, kind, zcode)),
                    j AS (
                      SELECT s.*, rt.id AS route_id, z.code AS route_zone, u.id AS user_id,
                             EXISTS (SELECT 1 FROM app.route_assignment a WHERE a.route_id = rt.id AND a.user_id = u.id AND a.kind = s.kind AND a.valid_to IS NULL) AS same_open,
                             EXISTS (SELECT 1 FROM app.route_assignment a WHERE a.route_id = rt.id AND a.kind = 'primary' AND a.valid_to IS NULL) AS primary_open
                        FROM s LEFT JOIN app.route rt ON rt.code = s.rcode LEFT JOIN app.zone z ON z.id = rt.zone_id LEFT JOIN app.app_user u ON lower(u.username) = s.username
                    ), c AS (
                      SELECT j.*, CASE WHEN route_id IS NULL THEN 'route_assignment_route_was_not_created'
                                       WHEN route_zone <> zcode THEN 'route_assignment_route_is_in_another_zone_than_the_file_says'
                                       WHEN user_id IS NULL THEN 'route_assignment_rep_was_not_created'
                                       WHEN same_open THEN 'unchanged'
                                       WHEN kind = 'primary' AND primary_open THEN 'route_assignment_primary_differs_from_the_open_primary'
                                       ELSE 'created' END AS cls
                        FROM j
                    ), ins AS (
                      INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, created_by)
                      SELECT route_id, user_id, kind, :today, :by FROM c WHERE cls = 'created' RETURNING 1
                    )
                    SELECT cls, count(*)::int, (array_agg(rcode ORDER BY rcode))[1 : ${LoadReport.SAMPLES}] FROM c GROUP BY cls ORDER BY cls
                    """.trimIndent(),
                ).arr("routes", ps.map { it.route }).arr("users", ps.map { it.user }).arr("kinds", ps.map { it.kind }).arr("zones", ps.map { it.zone })
                    .bind("today", options.businessDate).bindAny("by", options.by)
                    .map { rs, _ -> Triple(rs.getString(1), rs.getInt(2), (rs.getArray(3).array as Array<*>).map { it.toString() }) }.list()
                    .forEach { (cls, n, codes) ->
                        when (cls) {
                            "created" -> o.created += n
                            "unchanged" -> o.unchanged += n
                            "route_assignment_primary_differs_from_the_open_primary" -> { o.held += n; o.issue(cls, null, n); codes.forEach { o.issue(cls, it, 0) } }
                            else -> { o.error += n; o.issue(cls, null, n); codes.forEach { o.issue(cls, it, 0) } }
                        }
                    }
            }
        }
    }

    companion object {
        /** Rows per statement for the small entities (arrays passed to unnest); each chunk is its own transaction. */
        private const val CHUNK = 5_000

        /** An outlet batch that fails with a serialization failure or a deadlock (a concurrent portal edit) runs again on a fresh snapshot, this many times at most. */
        private const val BATCH_ATTEMPTS = 3

        /** Session-level advisory lock key of the loader (the role-DDL test lock is 7204190014). */
        const val LOCK_KEY = 7_204_190_077L

        /**
         * The batch's staged rows with everything resolved: the zone, the cluster of that zone, the route and ITS zone, the sub-channel named by the file, and
         * the channel in force (the file's, else the one the sub-channel belongs to, else the stored one, else GT - the GT default applies on CREATE only,
         * D-MD-25, so the stored channel is preferred over it on an update). `problem` names why a row cannot be applied; such a row is an ERROR, never
         * "unchanged" (finding L4: route, cluster and zone must agree, as in the portal import).
         */
        private const val RESOLVED = """
            WITH s AS (
              SELECT r.*, z.id AS zone_id, cl.id AS cluster_id, rt.id AS route_id, rt.zone_id AS route_zone,
                     sc.id AS sub_id, sc.channel AS sub_channel_of, (sc.status = 'active' OR sc.id = o.sub_channel_id) AS sub_active,
                     o.id AS outlet_id, o.channel AS cur_channel, o.sub_channel_id AS cur_sub, o.lat AS cur_lat, o.lng AS cur_lng,
                     o.location_basis AS cur_basis, o.location_confirmed AS cur_confirmed, o.route_id AS cur_route, o.cluster_id AS cur_cluster,
                     o.zone_id AS cur_zone, o.status AS cur_status, o.closed_at AS cur_closed, csc.channel AS cur_sub_channel_of
                FROM mdload.outlet_row r
                LEFT JOIN app.zone z ON z.code = r.zone_code
                LEFT JOIN app.cluster cl ON cl.zone_id = z.id AND cl.name = r.cluster_name
                LEFT JOIN app.route rt ON rt.code = r.route_code
                LEFT JOIN LATERAL (SELECT x.id, x.channel, x.status FROM app.sub_channel x WHERE lower(x.code) = lower(r.sub_channel)
                                    ORDER BY (x.code = r.sub_channel) DESC, x.id LIMIT 1) sc ON true
                LEFT JOIN app.outlet o ON o.code = r.code
                LEFT JOIN app.sub_channel csc ON csc.id = o.sub_channel_id
               WHERE r.run_id = :run AND r.batch_no = :b
            ), e AS (
              SELECT s.*, COALESCE(s.channel, CASE WHEN s.sub_id IS NOT NULL THEN s.sub_channel_of END, s.cur_channel, 'GT') AS eff_channel,
                     CASE WHEN s.zone_id IS NULL THEN 'zone_does_not_resolve'
                          WHEN s.cluster_id IS NULL THEN 'cluster_does_not_resolve'
                          WHEN s.route_code IS NOT NULL AND s.route_id IS NULL THEN 'route_does_not_resolve'
                          WHEN s.route_id IS NOT NULL AND s.route_zone <> s.zone_id THEN 'route_is_not_in_the_zone_of_the_row'
                          WHEN s.cur_status = 'merged' THEN 'outlet_was_merged' END AS problem
                FROM s
            ), f AS (
              SELECT e.*,
                     CASE WHEN e.sub_id IS NOT NULL AND e.sub_channel_of = e.eff_channel AND e.sub_active THEN e.sub_id
                          WHEN e.sub_id IS NULL AND e.sub_channel IS NULL AND e.cur_sub IS NOT NULL AND e.cur_sub_channel_of = e.eff_channel THEN e.cur_sub END AS eff_sub,
                     CASE WHEN e.sub_channel IS NOT NULL AND NOT (e.sub_id IS NOT NULL AND e.sub_channel_of = e.eff_channel AND e.sub_active)
                          THEN left(e.sub_channel, 120) END AS eff_source_sub
                FROM e
            )
        """

        /** Outlets the batch creates. The GT default, the tier and the pin follow the importer's create rules (D-MD-25, D-MD-26). */
        private val CREATE_SQL = """
            $RESOLVED, ins AS (
            INSERT INTO app.outlet (code, name, owner_name, contact_number, source_contact_number, address, zone_id, route_id, cluster_id, channel,
                                    sub_channel_id, source_sub_channel, geo_class, lat, lng, location_basis, location_confirmed, outlet_kind, status,
                                    source_created_at, source_updated_at, created_by, updated_by)
            SELECT f.code, f.name, f.owner_name, f.phone, f.source_phone, f.address, f.zone_id, f.route_id, f.cluster_id, f.eff_channel,
                   f.eff_sub, f.eff_source_sub, f.geo_class, f.lat, f.lng,
                   CASE WHEN f.lat IS NOT NULL THEN 'master' ELSE 'none' END, (:confirm AND f.in_bd), 'retail', f.status,
                   f.src_created, f.src_updated, :by, :by
              FROM f
             WHERE f.outlet_id IS NULL AND f.problem IS NULL
            RETURNING code
            )
            SELECT ins.code, f.generated FROM ins JOIN f ON f.code = ins.code
        """.trimIndent()

        /**
         * Outlets the batch changes. A row that would change nothing updates nothing (no version bump, no history): it is `unchanged` (D-MD-26). A ZONE MOVE
         * is applied as a whole: the zone, the cluster (it belongs to the new zone), the route (the row's, which must be in the new zone - a row that has none
         * clears the old one) and, below, the placement history; a row whose route is in another zone is an error and moves nothing (finding L4).
         * An outlet archived because its route had no rep is reactivated when the file now gives the route a rep (it was never closed by hand).
         */
        private val UPDATE_SQL = """
            $RESOLVED, g AS (
              SELECT f.*,
                     (f.lat IS NOT NULL AND f.cur_lat IS NOT DISTINCT FROM f.lat AND f.cur_lng IS NOT DISTINCT FROM f.lng) AS same_pin,
                     (f.lat IS NOT NULL AND NOT (f.cur_lat IS NOT DISTINCT FROM f.lat AND f.cur_lng IS NOT DISTINCT FROM f.lng)
                        AND ((NOT f.in_bd AND f.cur_lat IS NOT NULL) OR (f.in_bd AND (f.cur_confirmed OR f.cur_basis = 'provisional') AND NOT :confirm))) AS candidate,
                     (f.cur_status = 'archived' AND f.cur_closed IS NULL AND f.status = 'active') AS reactivate
                FROM f WHERE f.problem IS NULL
            )
            UPDATE app.outlet o SET
              name = g.name, owner_name = g.owner_name, zone_id = g.zone_id, cluster_id = g.cluster_id,
              route_id = CASE WHEN o.zone_id IS DISTINCT FROM g.zone_id THEN g.route_id ELSE COALESCE(g.route_id, o.route_id) END,
              channel = g.eff_channel, sub_channel_id = g.eff_sub, source_sub_channel = COALESCE(g.eff_source_sub, o.source_sub_channel),
              contact_number = COALESCE(g.phone, o.contact_number),
              source_contact_number = CASE WHEN g.phone IS NOT NULL THEN NULL ELSE COALESCE(g.source_phone, o.source_contact_number) END,
              address = COALESCE(g.address, o.address), geo_class = COALESCE(g.geo_class, o.geo_class),
              source_created_at = COALESCE(g.src_created, o.source_created_at), source_updated_at = COALESCE(g.src_updated, o.source_updated_at),
              status = CASE WHEN g.reactivate THEN 'active' ELSE o.status END,
              lat = CASE WHEN g.lat IS NOT NULL AND NOT g.same_pin AND NOT g.candidate THEN g.lat ELSE o.lat END,
              lng = CASE WHEN g.lat IS NOT NULL AND NOT g.same_pin AND NOT g.candidate THEN g.lng ELSE o.lng END,
              location_basis = CASE WHEN g.lat IS NOT NULL AND NOT g.same_pin AND NOT g.candidate THEN 'master' ELSE o.location_basis END,
              location_confirmed = CASE WHEN g.lat IS NOT NULL AND NOT g.same_pin AND NOT g.candidate THEN (:confirm AND g.in_bd)
                                        WHEN g.same_pin AND :confirm AND g.in_bd AND o.location_basis = 'master' THEN true ELSE o.location_confirmed END,
              updated_by = :by
            FROM g
            WHERE o.id = g.outlet_id
              AND (o.name IS DISTINCT FROM g.name OR o.owner_name IS DISTINCT FROM g.owner_name OR o.cluster_id IS DISTINCT FROM g.cluster_id
                OR o.zone_id IS DISTINCT FROM g.zone_id OR g.reactivate
                OR (g.route_id IS NOT NULL AND o.route_id IS DISTINCT FROM g.route_id) OR o.channel IS DISTINCT FROM g.eff_channel
                OR o.sub_channel_id IS DISTINCT FROM g.eff_sub OR (g.eff_source_sub IS NOT NULL AND o.source_sub_channel IS DISTINCT FROM g.eff_source_sub)
                OR (g.phone IS NOT NULL AND o.contact_number IS DISTINCT FROM g.phone) OR (g.phone IS NULL AND g.source_phone IS NOT NULL AND o.source_contact_number IS DISTINCT FROM g.source_phone)
                OR (g.address IS NOT NULL AND o.address IS DISTINCT FROM g.address) OR (g.geo_class IS NOT NULL AND o.geo_class IS DISTINCT FROM g.geo_class)
                OR (g.src_created IS NOT NULL AND o.source_created_at IS DISTINCT FROM g.src_created)
                OR (g.src_updated IS NOT NULL AND o.source_updated_at IS DISTINCT FROM g.src_updated)
                OR (g.lat IS NOT NULL AND NOT g.same_pin AND NOT g.candidate)
                OR (g.same_pin AND :confirm AND g.in_bd AND o.location_basis = 'master' AND NOT o.location_confirmed))
            RETURNING 1
        """.trimIndent()

        /** A master pin that must not replace the field's pin is recorded once as a candidate and is never the pin in force (D-MD-26). */
        private val CANDIDATE_SQL = """
            $RESOLVED
            INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis, created_by)
            SELECT f.outlet_id, f.lat, f.lng, 'migration', 'candidate', :by
              FROM f
             WHERE f.problem IS NULL AND f.outlet_id IS NOT NULL AND f.lat IS NOT NULL
               AND NOT (f.cur_lat IS NOT DISTINCT FROM f.lat AND f.cur_lng IS NOT DISTINCT FROM f.lng)
               AND ((NOT f.in_bd AND f.cur_lat IS NOT NULL) OR (f.in_bd AND (f.cur_confirmed OR f.cur_basis = 'provisional') AND NOT :confirm))
               AND NOT EXISTS (SELECT 1 FROM app.outlet_location_history x WHERE x.outlet_id = f.outlet_id AND x.basis = 'candidate' AND x.lat = f.lat AND x.lng = f.lng)
        """.trimIndent()

        /** The pin history of the outlets this batch created or re-pinned (the staging row is gone by the time the next batch runs, so this runs per batch). */
        private val PIN_HISTORY_SQL = """
            $RESOLVED
            INSERT INTO app.outlet_location_history (outlet_id, lat, lng, source, basis, created_by)
            SELECT o.id, o.lat, o.lng, 'migration', CASE WHEN o.lat IS NOT NULL THEN 'master' ELSE 'none' END, :by
              FROM f JOIN app.outlet o ON o.code = f.code
             WHERE f.problem IS NULL AND NOT EXISTS (
                     SELECT 1 FROM app.outlet_location_history x
                      WHERE x.outlet_id = o.id AND x.basis <> 'candidate' AND x.lat IS NOT DISTINCT FROM o.lat AND x.lng IS NOT DISTINCT FROM o.lng)
        """.trimIndent()

        /**
         * The placement history: one open row per outlet, re-opened when the route or the cluster changed. An open row that starts today or LATER than the
         * business date (a load run with an earlier business date than an existing placement) is corrected in place: closing it would give it a valid_to
         * before its valid_from, which the table refuses.
         */
        private val PLACEMENT_SQL = """
            $RESOLVED, p AS (
              SELECT o.id AS outlet_id, o.route_id, o.cluster_id,
                     (SELECT h.id FROM app.outlet_placement_history h WHERE h.outlet_id = o.id AND h.valid_to IS NULL ORDER BY h.valid_from DESC, h.id DESC LIMIT 1) AS open_id
                FROM f JOIN app.outlet o ON o.code = f.code WHERE f.problem IS NULL
            ), closed AS (
              UPDATE app.outlet_placement_history h SET valid_to = :today
                FROM p WHERE h.id = p.open_id AND (h.route_id IS DISTINCT FROM p.route_id OR h.cluster_id IS DISTINCT FROM p.cluster_id) AND h.valid_from < :today
              RETURNING h.outlet_id
            ), moved AS (
              UPDATE app.outlet_placement_history h SET route_id = p.route_id, cluster_id = p.cluster_id
                FROM p WHERE h.id = p.open_id AND (h.route_id IS DISTINCT FROM p.route_id OR h.cluster_id IS DISTINCT FROM p.cluster_id) AND h.valid_from >= :today
              RETURNING h.outlet_id
            )
            INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from, created_by)
            SELECT p.outlet_id, p.route_id, p.cluster_id, :today, :by
              FROM p WHERE p.open_id IS NULL OR p.outlet_id IN (SELECT outlet_id FROM closed)
        """.trimIndent()
    }
}
