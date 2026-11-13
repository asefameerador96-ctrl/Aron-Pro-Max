package com.aktcl.aron.backend.masterdata.loader

import com.aktcl.aron.backend.masterdata.ImportRules
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.masterdata.loader.MasterCsv.Column as C
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.Query
import org.jdbi.v3.core.statement.SqlStatement
import java.io.Reader
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The owner's switches and the operator's knobs for one load run. */
class LoadOptions(
    /** Input rows per batch. One batch is one transaction: it is applied whole or not at all, and a finished batch is never applied again. */
    val batchSize: Int = 5_000,
    /** The owner says the workbook's coordinates are confirmed: pins inside Bangladesh load confirmed and replace a pin the field confirmed (D-MD-26). */
    val coordinatesConfirmed: Boolean = false,
    /** The owner says outlets whose route has no visit plan are STORED (D-MD-03): the route gets visit_kind 'none' and the outlets load as usual. */
    val storeOutletsWithoutVisitPlan: Boolean = true,
    /** Status of an outlet on a route with no rep (the owner's default: archived, so nobody's coverage counts it). */
    val unassignedOutletStatus: String = "archived",
    /** Business date the assignments and the placement history start from. */
    val businessDate: LocalDate = LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")),
    /** Reads the file and reports, writes nothing. The report is the one the commit would produce. */
    val dryRun: Boolean = false,
    /** The run's id. Give the SAME id to resume an interrupted run: its finished batches are skipped. */
    val runId: UUID = UUID.randomUUID(),
    /** app_user.id recorded as created_by / updated_by; null for an unattended load. */
    val by: Long? = null,
    /** Allows SR users with a 3-character id. Default false = the server's own rule (D-MD-24): such reps are HELD, never created. */
    val allowShortRepIds: Boolean = false,
)

/**
 * The bulk loader for the owner's master-data workbook (M09, M12; the push-2 design in `docs/status/masterdata-fit.md`). It is a server-side TOOL, not an
 * endpoint: the portal import (`POST /v1/admin/outlets/import`) stays the path for a sheet of up to 5,000 rows that an administrator loads by hand.
 *
 * Properties the lane owes the owner, each proved by a test in `MasterDataLoaderTest`:
 *  * **Idempotent.** Running the same file again creates nothing, changes nothing and reports every row `unchanged`.
 *  * **Resumable.** The file is read in batches; each batch is one transaction that also records itself in `mdload.batch`. A run interrupted anywhere is
 *    resumed by passing the same `runId`: finished batches are skipped, and the result is the same as an uninterrupted run.
 *  * **Dry run.** `dryRun` produces the full report (including the scan's exceptions) and writes nothing at all.
 *  * **Every input row is counted exactly once** as created, updated, unchanged, held or error; the report states the equality against the rows read.
 *  * **No personal data leaves the database.** The report and every log line carry counts and OUR codes only — never an outlet name, an owner name, a rep
 *    name or a phone number (the repository is public; standing rule 8).
 *  * **The rules are not re-implemented.** Phones, channels, tiers, geo classes, coordinates, timestamps, visit plans and generated codes all come from
 *    [ImportRules], the same object the portal import calls, so the two paths cannot drift.
 *
 * Difference from the portal import, on purpose: where the portal REFUSES a row so that a human fixes the sheet (a tier that cannot belong to a channel the
 * row changes), the loader LOADS the outlet without that tier, keeps the field's text in `source_sub_channel` and counts it. The workbook is the owner's
 * truth and a load must not drop an outlet over a tier.
 */
class MasterDataLoader(private val db: Database, private val options: LoadOptions = LoadOptions()) {

    /** Reads [csv] (and an optional supervisor roster, not implemented in this push) and loads it. The reader is consumed twice, so [open] is called twice. */
    fun load(open: () -> Reader): LoadReport {
        val report = LoadReport()
        report.dryRun = options.dryRun
        val t0 = System.nanoTime()
        val scan = MasterScan()
        MasterCsv(open()).use { csv ->
            csv.missingColumns().forEach { report.issue("column_missing_in_file", it.name.lowercase()) }
            while (true) {
                val row = csv.next() ?: break
                scan.add(row)
            }
        }
        scan.report(report)
        if (!options.dryRun) db.jdbi.useHandle<Exception> { h -> ensureStaging(h); openRun(h) }
        writeHierarchy(scan, report)
        loadOutlets(scan, open, report)
        writeReps(scan, report)
        if (!options.dryRun) db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("DELETE FROM mdload.outlet_row WHERE run_id = :r").bind("r", options.runId).execute()
            h.createUpdate("UPDATE mdload.run SET finished_at = now() WHERE run_id = :r").bind("r", options.runId).execute()
        }
        report.millis = (System.nanoTime() - t0) / 1_000_000
        return report
    }

    // ---- staging ------------------------------------------------------------------------------------------------------------------------------

    /**
     * The loader's own schema. `mdload.run` and `mdload.batch` are small and logged (they are what makes a run resumable after a crash);
     * `mdload.outlet_row` is UNLOGGED (one batch at a time, deleted as soon as the batch is applied and purged at the end of the run) and holds the
     * file's values, so it never outlives the run. No migration owns this schema: it belongs to the tool, not to the product.
     */
    private fun ensureStaging(h: Handle) {
        h.execute("CREATE SCHEMA IF NOT EXISTS mdload")
        h.execute(
            "CREATE TABLE IF NOT EXISTS mdload.run (run_id uuid PRIMARY KEY, started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz, options text NOT NULL)",
        )
        h.execute(
            "CREATE TABLE IF NOT EXISTS mdload.batch (run_id uuid NOT NULL, batch_no int NOT NULL, rows int NOT NULL, created int NOT NULL, updated int NOT NULL, " +
                "unchanged int NOT NULL, errors int NOT NULL, fingerprint text NOT NULL, done_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (run_id, batch_no))",
        )
        h.execute(
            "CREATE UNLOGGED TABLE IF NOT EXISTS mdload.outlet_row (run_id uuid NOT NULL, batch_no int NOT NULL, row_no bigint NOT NULL, code text NOT NULL, " +
                "name text NOT NULL, owner_name text NOT NULL, phone text, source_phone text, address text, zone_code text NOT NULL, route_code text, " +
                "cluster_name text NOT NULL, channel text, sub_channel text, geo_class text, lat double precision, lng double precision, in_bd boolean NOT NULL, " +
                "src_created timestamptz, src_updated timestamptz, status text NOT NULL, PRIMARY KEY (run_id, batch_no, row_no))",
        )
    }

    private fun openRun(h: Handle) {
        h.createUpdate("INSERT INTO mdload.run (run_id, options) VALUES (:r, :o) ON CONFLICT (run_id) DO NOTHING")
            .bind("r", options.runId)
            .bind("o", "batch=${options.batchSize} confirmed=${options.coordinatesConfirmed} no_plan_stored=${options.storeOutletsWithoutVisitPlan} unassigned=${options.unassignedOutletStatus}")
            .execute()
    }

    // ---- hierarchy ----------------------------------------------------------------------------------------------------------------------------

    /** Counts an upsert whose RETURNING gives one row per written row, true when it was an INSERT. [total] is how many inputs it had. */
    private fun count(h: Handle, c: LoadReport.Counts, total: Int, presentSql: String, codes: List<String>, q: Query) {
        val written = q.mapTo(Boolean::class.javaObjectType).list()
        val created = written.count { it }
        c.created += created
        c.updated += written.size - created
        val present = h.createQuery(presentSql).bindArray("codes", String::class.java, codes).mapTo(Int::class.java).one()
        c.unchanged += present - written.size
        c.error += total - present
    }

    private fun <T : SqlStatement<T>> T.arr(name: String, values: List<String?>): T = bindArray(name, String::class.java, values)

    private fun writeHierarchy(scan: MasterScan, report: LoadReport) {
        if (options.dryRun) { planHierarchyCountsForDryRun(scan, report); return }
        db.jdbi.useTransaction<Exception> { h ->
            val by = options.by
            // wing: no parent
            scan.wings.values.chunked(CHUNK).forEach { ws ->
                val codes = ws.map { it.code }
                count(
                    h, report.of("wing"), ws.size, "SELECT count(*)::int FROM app.wing WHERE code = ANY(:codes)", codes,
                    h.createQuery(
                        "INSERT INTO app.wing (code, name, created_by, updated_by) SELECT s.code, s.name, :by, :by FROM unnest(:codes::text[], :names::text[]) AS s(code, name) " +
                            "ON CONFLICT (code) DO UPDATE SET name = excluded.name, updated_by = :by WHERE app.wing.name IS DISTINCT FROM excluded.name RETURNING (xmax = 0)",
                    ).arr("codes", codes).arr("names", ws.map { it.name.ifEmpty { it.code } }).bindAny("by", by),
                )
            }
            child(h, report, scan.divisions, "division", "wing", "wing_id")
            child(h, report, scan.territories, "territory", "division", "division_id")
            child(h, report, scan.houses, "house", "territory", "territory_id")
            // zone: parent territory plus the house that serves it
            scan.zones.values.chunked(CHUNK).forEach { zs ->
                val codes = zs.map { it.code }
                count(
                    h, report.of("zone"), zs.size, "SELECT count(*)::int FROM app.zone WHERE code = ANY(:codes)", codes,
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
            // route: zone, and the visit plan the scan decided
            scan.routes.values.chunked(CHUNK).forEach { rs ->
                val codes = rs.map { it.code }
                val plans = rs.map { scan.planOf(it) }
                count(
                    h, report.of("route"), rs.size, "SELECT count(*)::int FROM app.route WHERE code = ANY(:codes)", codes,
                    h.createQuery(
                        """
                        INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, created_by, updated_by)
                        SELECT s.code, s.name, s.label, z.id, 'sr', s.kind, s.mask::smallint, :by, :by
                          FROM unnest(:codes::text[], :names::text[], :labels::text[], :zones::text[], :kinds::text[], :masks::int[]) AS s(code, name, label, zcode, kind, mask)
                          JOIN app.zone z ON z.code = s.zcode
                        ON CONFLICT (code) DO UPDATE SET name = excluded.name, display_label = excluded.display_label, zone_id = excluded.zone_id,
                             visit_kind = excluded.visit_kind, visit_days_mask = excluded.visit_days_mask, updated_by = :by
                         WHERE app.route.name IS DISTINCT FROM excluded.name OR app.route.display_label IS DISTINCT FROM excluded.display_label
                            OR app.route.zone_id IS DISTINCT FROM excluded.zone_id OR app.route.visit_kind IS DISTINCT FROM excluded.visit_kind
                            OR app.route.visit_days_mask IS DISTINCT FROM excluded.visit_days_mask
                        RETURNING (xmax = 0)
                        """.trimIndent(),
                    ).arr("codes", codes).arr("names", rs.map { it.name.take(120) }).arr("labels", plans.map { it.label })
                        .arr("zones", rs.map { it.zone }).arr("kinds", plans.map { it.kind })
                        .bindArray("masks", Int::class.javaObjectType, plans.map { it.mask }).bindAny("by", by),
                )
            }
            // cluster: keyed by (zone, name), type from the scan
            scan.clusters.keys.chunked(CHUNK).forEach { keys ->
                val zones = keys.map { it.substringBefore('\u001f') }
                val names = keys.map { it.substringAfter('\u001f').take(120) }
                val types = keys.map { scan.clusterType(it).take(60) }
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
                val c = report.of("cluster")
                val created = written.count { it }
                c.created += created
                c.updated += written.size - created
                val present = h.createQuery("SELECT count(*)::int FROM app.cluster c JOIN app.zone z ON z.id = c.zone_id JOIN unnest(:zones::text[], :names::text[]) AS s(zcode, name) ON s.zcode = z.code AND s.name = c.name")
                    .arr("zones", zones).arr("names", names).mapTo(Int::class.java).one()
                c.unchanged += present - written.size
                c.error += keys.size - present
            }
        }
    }

    private fun child(h: Handle, report: LoadReport, nodes: Map<String, MasterScan.Node>, table: String, parentTable: String, parentColumn: String) {
        nodes.values.chunked(CHUNK).forEach { ns ->
            val codes = ns.map { it.code }
            count(
                h, report.of(table), ns.size, "SELECT count(*)::int FROM app.$table WHERE code = ANY(:codes)", codes,
                h.createQuery(
                    """
                    INSERT INTO app.$table (code, name, $parentColumn, created_by, updated_by)
                    SELECT s.code, s.name, p.id, :by, :by FROM unnest(:codes::text[], :names::text[], :parents::text[]) AS s(code, name, pcode)
                      JOIN app.$parentTable p ON p.code = s.pcode
                    ON CONFLICT (code) DO UPDATE SET name = excluded.name, $parentColumn = excluded.$parentColumn, updated_by = :by
                     WHERE app.$table.name IS DISTINCT FROM excluded.name OR app.$table.$parentColumn IS DISTINCT FROM excluded.$parentColumn
                    RETURNING (xmax = 0)
                    """.trimIndent(),
                ).arr("codes", codes).arr("names", ns.map { it.name.ifEmpty { it.code }.take(120) }).arr("parents", ns.map { it.parent() }).bindAny("by", options.by),
            )
        }
    }

    /** A dry run reports what the hierarchy WOULD do by comparing the scan with what is stored, without writing. */
    private fun planHierarchyCountsForDryRun(scan: MasterScan, report: LoadReport) {
        db.jdbi.useHandle<Exception> { h ->
            fun existing(table: String, codes: Collection<String>): Set<String> =
                if (codes.isEmpty()) emptySet() else codes.chunked(CHUNK).flatMap { ch ->
                    h.createQuery("SELECT code FROM app.$table WHERE code = ANY(:codes)").bindArray("codes", String::class.java, ch).mapTo(String::class.java).list()
                }.toSet()
            for ((table, codes) in listOf(
                "wing" to scan.wings.keys, "division" to scan.divisions.keys, "territory" to scan.territories.keys,
                "house" to scan.houses.keys, "zone" to scan.zones.keys, "route" to scan.routes.keys,
            )) {
                val have = existing(table, codes)
                val c = report.of(table)
                c.created += codes.count { it !in have }
                c.updated += have.size // a dry run cannot say which of the existing rows differ without reading every column: it reports them as "would be looked at"
            }
            val c = report.of("cluster")
            c.created += scan.clusters.size
        }
    }

    // ---- outlets ------------------------------------------------------------------------------------------------------------------------------

    /** One normalised outlet row on its way to the staging table. */
    private class Stage(
        val rowNo: Long, val code: String, val name: String, val owner: String, val phone: String?, val sourcePhone: String?, val address: String?,
        val zone: String, val route: String?, val cluster: String, val channel: String?, val subChannel: String?, val geoClass: String?,
        val lat: Double?, val lng: Double?, val inBd: Boolean, val created: Instant?, val updated: Instant?, val status: String,
    )

    private fun loadOutlets(scan: MasterScan, open: () -> Reader, report: LoadReport) {
        val geoClasses = db.jdbi.withHandle<List<String>, Exception> { h -> h.createQuery("SELECT geo_class FROM app.geo_class_def").mapTo(String::class.java).list() }
        // A finished batch is skipped only when it held EXACTLY these rows (a resumed run may read a file that grew, so the batch at position n can
        // hold other rows than it did; applying a batch again is harmless, skipping the wrong one would lose rows).
        val done: Map<Int, String> = if (options.dryRun) emptyMap() else db.jdbi.withHandle<Map<Int, String>, Exception> { h ->
            h.createQuery("SELECT batch_no, fingerprint FROM mdload.batch WHERE run_id = :r").bind("r", options.runId)
                .map { rs, _ -> rs.getInt(1) to rs.getString(2) }.list().toMap()
        }
        val now = Instant.now()
        var batchNo = 0
        val batch = ArrayList<Stage>(options.batchSize)
        MasterCsv(open()).use { csv ->
            while (true) {
                val row = csv.next()
                if (row != null) {
                    val s = stage(row, scan, geoClasses, now, report) ?: continue
                    batch += s
                    if (batch.size < options.batchSize) continue
                }
                if (batch.isNotEmpty()) {
                    batchNo++
                    if (done[batchNo] == fingerprint(batch)) { report.batchesSkipped++; report.of("outlet").unchanged += batch.size } else applyBatch(batchNo, batch, report)
                    batch.clear()
                }
                if (row == null) break
            }
        }
    }

    /** What the batch held: its row numbers and codes. Two runs over the same file give the same value; a run over a file that changed does not. */
    private fun fingerprint(batch: List<Stage>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        batch.forEach { md.update("${it.rowNo}\u001f${it.code}\n".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Normalises one input row with [ImportRules]; null (counted as an error) when the row cannot name an outlet at all. */
    private fun stage(row: MasterCsv.Row, scan: MasterScan, geoClasses: List<String>, now: Instant, report: LoadReport): Stage? {
        val c = report.of("outlet")
        val zone = row[C.ZONE_CODE]
        val cluster = row[C.CLUSTER_NAME]
        val name = row[C.OUTLET_NAME]
        if (zone.isEmpty() || cluster.isEmpty() || name.isEmpty()) {
            c.error++
            report.issue(if (zone.isEmpty()) "outlet_row_without_zone" else if (cluster.isEmpty()) "outlet_row_without_cluster" else "outlet_row_without_name", row[C.OUTLET_CODE].ifEmpty { null })
            return null
        }
        val route = row[C.ROUTE_CODE].ifEmpty { null }
        val ph = ImportRules.phone(row[C.CONTACT_NUMBER])
        if (ph.unusable) report.issue("phone_unusable")
        else if (ph.repaired) report.issue("phone_repaired")
        val channel = when (val p = ImportRules.channel(row[C.CHANNEL])) {
            is ImportRules.ChannelParse.Known -> p.code
            is ImportRules.ChannelParse.Unknown -> { report.issue("channel_not_one_of_the_six", row[C.OUTLET_CODE].ifEmpty { null }); null }
            ImportRules.ChannelParse.Empty -> { report.issue("channel_empty_in_file"); null }
        }
        val sub = row[C.SUB_CHANNEL].takeUnless { ImportRules.isNoSubChannel(it) }
        val geo = ImportRules.geoClass(row[C.GEO_CLASS], geoClasses)
        if (row[C.GEO_CLASS].isNotEmpty() && geo == null && row[C.GEO_CLASS] != "--") report.issue("geo_class_unknown")
        var lat = row[C.LATITUDE].toDoubleOrNull()
        var lng = row[C.LONGITUDE].toDoubleOrNull()
        if ((lat == null) != (lng == null)) { report.issue("coordinates_incomplete"); lat = null; lng = null }
        if (lat != null && (lat !in -90.0..90.0 || lng!! !in -180.0..180.0)) { report.issue("coordinates_out_of_range"); lat = null; lng = null }
        val inBd = lat != null && ImportRules.inBangladesh(lat, lng!!)
        if (lat != null && !inBd) report.issue("coordinates_outside_bangladesh", row[C.OUTLET_CODE].ifEmpty { null })
        val code = row[C.OUTLET_CODE].ifEmpty {
            report.issue("outlet_row_without_a_code")
            ImportRules.generatedOutletCode(zone, route, cluster, name, row[C.OWNER_NAME])
        }
        val hasRep = scan.routes[row[C.ROUTE_CODE]]?.reps?.isNotEmpty() == true
        return Stage(
            row.no, code, name.take(120), row[C.OWNER_NAME].ifEmpty { name }.take(120), ph.value, if (ph.unusable) row[C.CONTACT_NUMBER].take(60) else null,
            row[C.ADDRESS].take(300).ifEmpty { null }, zone, route, cluster.take(120), channel, sub?.take(120), geo,
            lat?.let { ImportRules.round8(it) }, lng?.let { ImportRules.round8(it) }, inBd,
            ImportRules.timestamp(row[C.CREATED_AT], now), ImportRules.timestamp(row[C.UPDATED_AT], now),
            if (hasRep) "active" else options.unassignedOutletStatus,
        )
    }

    /** One batch, one transaction: stage, create, update, histories, counts, and the batch's own row in `mdload.batch`. */
    private fun applyBatch(batchNo: Int, batch: List<Stage>, report: LoadReport) {
        val c = report.of("outlet")
        if (options.dryRun) {
            db.jdbi.useHandle<Exception> { h ->
                val codes = batch.map { it.code }
                val have = h.createQuery("SELECT count(*)::int FROM app.outlet WHERE code = ANY(:codes)").bindArray("codes", String::class.java, codes).mapTo(Int::class.java).one()
                c.created += batch.size - have
                c.updated += have
            }
            report.batchesApplied++
            return
        }
        db.jdbi.useTransaction<Exception> { h ->
            h.createUpdate("DELETE FROM mdload.outlet_row WHERE run_id = :r AND batch_no = :b").bind("r", options.runId).bind("b", batchNo).execute()
            stageInsert(h, batchNo, batch)
            val created = h.createQuery(CREATE_SQL).bind("run", options.runId).bind("b", batchNo).bind("confirm", options.coordinatesConfirmed)
                .bindAny("by", options.by).bind("today", options.businessDate).mapTo(Int::class.java).list().size
            val updated = h.createQuery(UPDATE_SQL).bind("run", options.runId).bind("b", batchNo).bind("confirm", options.coordinatesConfirmed)
                .bindAny("by", options.by).mapTo(Int::class.java).list().size
            val candidates = h.createUpdate(CANDIDATE_SQL).bind("run", options.runId).bind("b", batchNo).bind("confirm", options.coordinatesConfirmed).bindAny("by", options.by).execute()
            h.createUpdate(PIN_HISTORY_SQL).bind("run", options.runId).bind("b", batchNo).bindAny("by", options.by).execute()
            h.createUpdate(PLACEMENT_SQL).bind("run", options.runId).bind("b", batchNo).bind("today", options.businessDate).bindAny("by", options.by).execute()
            val present = h.createQuery(
                "SELECT count(*)::int FROM mdload.outlet_row s JOIN app.outlet o ON o.code = s.code WHERE s.run_id = :run AND s.batch_no = :b",
            ).bind("run", options.runId).bind("b", batchNo).mapTo(Int::class.java).one()
            c.created += created
            c.updated += updated
            c.unchanged += present - created - updated
            c.error += batch.size - present
            if (candidates > 0) report.issue("master_pin_kept_as_a_candidate", times = candidates)
            h.createUpdate(
                "INSERT INTO mdload.batch (run_id, batch_no, rows, created, updated, unchanged, errors, fingerprint) VALUES (:r, :b, :n, :c, :u, :un, :e, :f) " +
                    "ON CONFLICT (run_id, batch_no) DO UPDATE SET rows = excluded.rows, created = excluded.created, updated = excluded.updated, " +
                    "unchanged = excluded.unchanged, errors = excluded.errors, fingerprint = excluded.fingerprint, done_at = now()",
            ).bind("r", options.runId).bind("b", batchNo).bind("n", batch.size).bind("c", created).bind("u", updated)
                .bind("un", present - created - updated).bind("e", batch.size - present).bind("f", fingerprint(batch)).execute()
            h.createUpdate("DELETE FROM mdload.outlet_row WHERE run_id = :r AND batch_no = :b").bind("r", options.runId).bind("b", batchNo).execute()
        }
        report.batchesApplied++
    }

    private fun stageInsert(h: Handle, batchNo: Int, batch: List<Stage>) {
        h.createUpdate(
            """
            INSERT INTO mdload.outlet_row (run_id, batch_no, row_no, code, name, owner_name, phone, source_phone, address, zone_code, route_code, cluster_name,
                                           channel, sub_channel, geo_class, lat, lng, in_bd, src_created, src_updated, status)
            SELECT :run, :b, * FROM unnest(:rowno::bigint[], :code::text[], :name::text[], :owner::text[], :phone::text[], :sphone::text[], :addr::text[],
                                           :zone::text[], :route::text[], :cluster::text[], :channel::text[], :sub::text[], :geo::text[],
                                           :lat::float8[], :lng::float8[], :inbd::boolean[], :screated::timestamptz[], :supdated::timestamptz[], :status::text[])
            """.trimIndent(),
        ).bind("run", options.runId).bind("b", batchNo)
            .bindArray("rowno", Long::class.javaObjectType, batch.map { it.rowNo })
            .arr("code", batch.map { it.code }).arr("name", batch.map { it.name }).arr("owner", batch.map { it.owner })
            .arr("phone", batch.map { it.phone }).arr("sphone", batch.map { it.sourcePhone }).arr("addr", batch.map { it.address })
            .arr("zone", batch.map { it.zone }).arr("route", batch.map { it.route }).arr("cluster", batch.map { it.cluster })
            .arr("channel", batch.map { it.channel }).arr("sub", batch.map { it.subChannel }).arr("geo", batch.map { it.geoClass })
            .bindArray("lat", Double::class.javaObjectType, batch.map { it.lat }).bindArray("lng", Double::class.javaObjectType, batch.map { it.lng })
            .bindArray("inbd", Boolean::class.javaObjectType, batch.map { it.inBd })
            // Instants travel as ISO-8601 text and are cast by the statement (::timestamptz[]): JDBI has no array binder for java.sql.Timestamp.
            .arr("screated", batch.map { it.created?.toString() })
            .arr("supdated", batch.map { it.updated?.toString() })
            .arr("status", batch.map { it.status })
            .execute()
    }

    // ---- reps and assignments -----------------------------------------------------------------------------------------------------------------

    private fun writeReps(scan: MasterScan, report: LoadReport) {
        val rc = report.of("sales_rep")
        val ac = report.of("route_assignment")
        val allowShort = options.allowShortRepIds || !options.dryRun && db.jdbi.withHandle<Boolean, Exception> { h ->
            com.aktcl.aron.backend.masterdata.shortRepIdAllowed(h, com.aktcl.aron.contract.Role.SR)
        }
        val held = scan.reps.values.filter { it.id.length < 4 && !allowShort }
        held.forEach { report.issue("sales_rep_held_needs_app_release", it.id) }
        rc.held += held.size
        val heldIds = held.map { it.id }.toSet()
        val loadable = scan.reps.values.filter { it.id !in heldIds }
        if (options.dryRun) {
            db.jdbi.useHandle<Exception> { h ->
                val have = loadable.map { it.id }.chunked(CHUNK).sumOf { ch ->
                    h.createQuery("SELECT count(*)::int FROM app.app_user WHERE username = ANY(:codes)").bindArray("codes", String::class.java, ch).mapTo(Int::class.java).one()
                }
                rc.created += loadable.size - have
                rc.updated += have
            }
            scan.routes.values.forEach { r -> ac.created += r.reps.keys.count { it !in heldIds } }
            return
        }
        db.jdbi.useTransaction<Exception> { h ->
            loadable.chunked(CHUNK).forEach { rs ->
                val ids = rs.map { it.id }
                count(
                    h, rc, rs.size, "SELECT count(*)::int FROM app.app_user WHERE username = ANY(:codes)", ids,
                    h.createQuery(
                        """
                        INSERT INTO app.app_user (username, full_name, role, employee_code, phone, locale, home_zone_id, must_change_password, created_by, updated_by)
                        SELECT s.id, s.name, 'SR', s.id, s.phone, 'bn', z.id, true, :by, :by
                          FROM unnest(:codes::text[], :names::text[], :phones::text[], :zones::text[]) AS s(id, name, phone, zcode)
                          LEFT JOIN app.zone z ON z.code = s.zcode
                        ON CONFLICT (lower(username)) DO UPDATE SET full_name = excluded.full_name, employee_code = excluded.employee_code,
                             phone = COALESCE(excluded.phone, app.app_user.phone), home_zone_id = COALESCE(excluded.home_zone_id, app.app_user.home_zone_id), updated_by = :by
                         WHERE app.app_user.full_name IS DISTINCT FROM excluded.full_name
                            OR (excluded.phone IS NOT NULL AND app.app_user.phone IS DISTINCT FROM excluded.phone)
                            OR (excluded.home_zone_id IS NOT NULL AND app.app_user.home_zone_id IS DISTINCT FROM excluded.home_zone_id)
                        RETURNING (xmax = 0)
                        """.trimIndent(),
                    ).arr("codes", ids).arr("names", rs.map { it.name.ifEmpty { it.id }.take(120) }).arr("phones", rs.map { it.phone })
                        .arr("zones", rs.map { r -> r.zones.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key }).bindAny("by", options.by),
                )
            }
            // assignments: the primary of each route, then the others as open-ended covers; an assignment that is already open is left alone.
            val pairs = ArrayList<Triple<String, String, String>>()   // route code, username, kind
            for (r in scan.routes.values) {
                val (primary, covers) = scan.repsOf(r)
                if (primary != null && primary !in heldIds) pairs += Triple(r.code, primary, "primary")
                covers.filter { it !in heldIds }.forEach { pairs += Triple(r.code, it, "cover") }
                if (primary != null && primary in heldIds) ac.held++
            }
            pairs.chunked(CHUNK).forEach { ps ->
                val written = h.createQuery(
                    """
                    INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, created_by)
                    SELECT rt.id, u.id, s.kind, :today, :by
                      FROM unnest(:routes::text[], :users::text[], :kinds::text[]) AS s(rcode, username, kind)
                      JOIN app.route rt ON rt.code = s.rcode
                      JOIN app.app_user u ON u.username = s.username
                     WHERE NOT EXISTS (SELECT 1 FROM app.route_assignment a WHERE a.route_id = rt.id AND a.user_id = u.id AND a.kind = s.kind AND a.valid_to IS NULL)
                       AND NOT (s.kind = 'primary' AND EXISTS (SELECT 1 FROM app.route_assignment a WHERE a.route_id = rt.id AND a.kind = 'primary' AND a.valid_to IS NULL))
                    RETURNING 1
                    """.trimIndent(),
                ).arr("routes", ps.map { it.first }).arr("users", ps.map { it.second }).arr("kinds", ps.map { it.third })
                    .bind("today", options.businessDate).bindAny("by", options.by).mapTo(Int::class.java).list().size
                ac.created += written
                ac.unchanged += ps.size - written
            }
        }
    }

    private fun <T : SqlStatement<T>> T.bindAny(name: String, v: Any?): T = if (v == null) bindNull(name, java.sql.Types.NULL) else bind(name, v)

    companion object {
        /** Rows per statement for the small entities (arrays passed to unnest). */
        private const val CHUNK = 5_000

        /**
         * The batch's staged rows with everything resolved: the zone, the cluster of that zone, the route, the sub-channel named by the file, and the
         * channel in force (the file's, else the one the sub-channel belongs to, else the stored one, else GT - the GT default applies on CREATE only,
         * D-MD-25, so the stored channel is preferred over it on an update).
         */
        private const val RESOLVED = """
            WITH s AS (
              SELECT r.*, z.id AS zone_id, cl.id AS cluster_id, rt.id AS route_id,
                     sc.id AS sub_id, sc.channel AS sub_channel_of, (sc.status = 'active') AS sub_active,
                     o.id AS outlet_id, o.channel AS cur_channel, o.sub_channel_id AS cur_sub, o.lat AS cur_lat, o.lng AS cur_lng,
                     o.location_basis AS cur_basis, o.location_confirmed AS cur_confirmed, o.route_id AS cur_route, o.cluster_id AS cur_cluster,
                     csc.channel AS cur_sub_channel_of
                FROM mdload.outlet_row r
                LEFT JOIN app.zone z ON z.code = r.zone_code
                LEFT JOIN app.cluster cl ON cl.zone_id = z.id AND cl.name = r.cluster_name
                LEFT JOIN app.route rt ON rt.code = r.route_code
                LEFT JOIN app.sub_channel sc ON lower(sc.code) = lower(r.sub_channel)
                LEFT JOIN app.outlet o ON o.code = r.code
                LEFT JOIN app.sub_channel csc ON csc.id = o.sub_channel_id
               WHERE r.run_id = :run AND r.batch_no = :b
            ), e AS (
              SELECT s.*, COALESCE(s.channel, CASE WHEN s.sub_id IS NOT NULL THEN s.sub_channel_of END, s.cur_channel, 'GT') AS eff_channel
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
            $RESOLVED
            INSERT INTO app.outlet (code, name, owner_name, contact_number, source_contact_number, address, zone_id, route_id, cluster_id, channel,
                                    sub_channel_id, source_sub_channel, geo_class, lat, lng, location_basis, location_confirmed, outlet_kind, status,
                                    source_created_at, source_updated_at, created_by, updated_by)
            SELECT f.code, f.name, f.owner_name, f.phone, f.source_phone, f.address, f.zone_id, f.route_id, f.cluster_id, f.eff_channel,
                   f.eff_sub, f.eff_source_sub, f.geo_class, f.lat, f.lng,
                   CASE WHEN f.lat IS NOT NULL THEN 'master' ELSE 'none' END, (:confirm AND f.in_bd), 'retail', f.status,
                   f.src_created, f.src_updated, :by, :by
              FROM f
             WHERE f.outlet_id IS NULL AND f.zone_id IS NOT NULL AND f.cluster_id IS NOT NULL
            RETURNING 1
        """.trimIndent()

        /** Outlets the batch changes. A row that would change nothing updates nothing (no version bump, no history): it is `unchanged` (D-MD-26). */
        private val UPDATE_SQL = """
            $RESOLVED, g AS (
              SELECT f.*,
                     (f.lat IS NOT NULL AND f.cur_lat IS NOT DISTINCT FROM f.lat AND f.cur_lng IS NOT DISTINCT FROM f.lng) AS same_pin,
                     (f.lat IS NOT NULL AND NOT (f.cur_lat IS NOT DISTINCT FROM f.lat AND f.cur_lng IS NOT DISTINCT FROM f.lng)
                        AND ((NOT f.in_bd AND f.cur_lat IS NOT NULL) OR (f.in_bd AND (f.cur_confirmed OR f.cur_basis = 'provisional') AND NOT :confirm))) AS candidate
                FROM f
            )
            UPDATE app.outlet o SET
              name = g.name, owner_name = g.owner_name, cluster_id = g.cluster_id, route_id = COALESCE(g.route_id, o.route_id),
              channel = g.eff_channel, sub_channel_id = g.eff_sub, source_sub_channel = COALESCE(g.eff_source_sub, o.source_sub_channel),
              contact_number = COALESCE(g.phone, o.contact_number),
              source_contact_number = CASE WHEN g.phone IS NOT NULL THEN NULL ELSE COALESCE(g.source_phone, o.source_contact_number) END,
              address = COALESCE(g.address, o.address), geo_class = COALESCE(g.geo_class, o.geo_class),
              source_created_at = COALESCE(g.src_created, o.source_created_at), source_updated_at = COALESCE(g.src_updated, o.source_updated_at),
              lat = CASE WHEN g.lat IS NOT NULL AND NOT g.same_pin AND NOT g.candidate THEN g.lat ELSE o.lat END,
              lng = CASE WHEN g.lat IS NOT NULL AND NOT g.same_pin AND NOT g.candidate THEN g.lng ELSE o.lng END,
              location_basis = CASE WHEN g.lat IS NOT NULL AND NOT g.same_pin AND NOT g.candidate THEN 'master' ELSE o.location_basis END,
              location_confirmed = CASE WHEN g.lat IS NOT NULL AND NOT g.same_pin AND NOT g.candidate THEN (:confirm AND g.in_bd)
                                        WHEN g.same_pin AND :confirm AND g.in_bd AND o.location_basis = 'master' THEN true ELSE o.location_confirmed END,
              updated_by = :by
            FROM g
            WHERE o.id = g.outlet_id AND g.zone_id IS NOT NULL AND g.cluster_id IS NOT NULL
              AND (o.name IS DISTINCT FROM g.name OR o.owner_name IS DISTINCT FROM g.owner_name OR o.cluster_id IS DISTINCT FROM g.cluster_id
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
             WHERE f.outlet_id IS NOT NULL AND f.lat IS NOT NULL
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
             WHERE NOT EXISTS (
                     SELECT 1 FROM app.outlet_location_history x
                      WHERE x.outlet_id = o.id AND x.basis <> 'candidate' AND x.lat IS NOT DISTINCT FROM o.lat AND x.lng IS NOT DISTINCT FROM o.lng)
        """.trimIndent()

        /** The placement history: one open row per outlet, re-opened when the route or the cluster changed. */
        private val PLACEMENT_SQL = """
            $RESOLVED, p AS (
              SELECT o.id AS outlet_id, o.route_id, o.cluster_id,
                     (SELECT h.id FROM app.outlet_placement_history h WHERE h.outlet_id = o.id AND h.valid_to IS NULL ORDER BY h.valid_from DESC, h.id DESC LIMIT 1) AS open_id,
                     (SELECT h.route_id FROM app.outlet_placement_history h WHERE h.outlet_id = o.id AND h.valid_to IS NULL ORDER BY h.valid_from DESC, h.id DESC LIMIT 1) AS open_route,
                     (SELECT h.cluster_id FROM app.outlet_placement_history h WHERE h.outlet_id = o.id AND h.valid_to IS NULL ORDER BY h.valid_from DESC, h.id DESC LIMIT 1) AS open_cluster
                FROM f JOIN app.outlet o ON o.code = f.code
            ), closed AS (
              UPDATE app.outlet_placement_history h SET valid_to = :today
                FROM p WHERE h.id = p.open_id AND (h.route_id IS DISTINCT FROM p.route_id OR h.cluster_id IS DISTINCT FROM p.cluster_id) AND h.valid_from < :today
              RETURNING h.outlet_id
            ), moved AS (
              UPDATE app.outlet_placement_history h SET route_id = p.route_id, cluster_id = p.cluster_id
                FROM p WHERE h.id = p.open_id AND (h.route_id IS DISTINCT FROM p.route_id OR h.cluster_id IS DISTINCT FROM p.cluster_id) AND h.valid_from = :today
              RETURNING h.outlet_id
            )
            INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from, created_by)
            SELECT p.outlet_id, p.route_id, p.cluster_id, :today, :by
              FROM p WHERE p.open_id IS NULL OR p.outlet_id IN (SELECT outlet_id FROM closed)
        """.trimIndent()
    }
}
