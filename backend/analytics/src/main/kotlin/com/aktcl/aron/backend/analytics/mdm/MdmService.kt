package com.aktcl.aron.backend.analytics.mdm

import com.aktcl.aron.backend.analytics.devices.DeviceService
import com.aktcl.aron.backend.analytics.devices.EnrolmentTokenCreateRequest
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuditLog
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Best-effort FCM nudge of one phone (the DPC pulls on every sync anyway). Wired in the app module; a no-op without FCM. */
fun interface MdmPush { fun nudge(deviceId: Long) }

private val FLAGS = listOf("rooted", "mock_location", "outdated_app", "developer_options", "adb_enabled", "not_device_owner", "location_off", "auto_time_off", "policy_drift", "stale_contact")
private val FLAG_COLUMN = mapOf(
    "rooted" to "f_rooted", "mock_location" to "f_mock", "outdated_app" to "f_outdated", "developer_options" to "f_dev", "adb_enabled" to "f_adb",
    "not_device_owner" to "f_notowner", "location_off" to "f_loc", "auto_time_off" to "f_time", "policy_drift" to "f_drift", "stale_contact" to "f_stale",
)
private val COMMANDS = setOf("lock", "unlock", "message", "locate", "reboot", "factory_reset")
private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
private val HHMM = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
private val STRINGS = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
private val JSON = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }
private val SYSTEM_STATES = "('pending_approval','queued','sent')"

/**
 * D-MDM-01: device list and detail with compliance flags, policies, commands (client-idempotent, second approver for a factory reset),
 * the phone's pull and ack, enrolment tokens (through [DeviceService]) and the activity log. Not reach-scoped: the section is for
 * SUPERADMIN and ADMIN (national). Needs tables of docs/requests/db-mdm-device-command.md.
 */
class MdmService(
    private val db: Database, private val keys: JwtKeys, private val devices: DeviceService, private val clock: AronClock = AronClock.SYSTEM, private val push: MdmPush = MdmPush { },
) {
    private fun ts(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)
    private fun OffsetDateTime?.wire() = this?.toInstant()?.wire()
    private fun bad(field: String, why: String = "invalid_value", msg: String = "invalid $field"): Nothing = throw ApiProblem(ProblemCode.ERR_VALIDATION, msg, errors = listOf(FieldError("/$field", why)))
    private fun audit(h: Handle, p: AronPrincipal, entity: String, id: String, action: String, before: JsonElement?, after: JsonElement?, reason: String?, requestId: String?) =
        AuditLog.write(h, p, entity, id, action, before, after, reason, requestId, via = "api")

    // ---------------- devices ----------------

    private val deviceSql = """
        WITH rep AS (
          SELECT DISTINCT ON (device_id) device_id, battery_pct, mock_location_apps, dev_options_enabled, adb_enabled, location_enabled, auto_time_enabled, report ->> 'network_type' AS net
            FROM app.device_status_report ORDER BY device_id, reported_at DESC, id DESC),
        rel AS (SELECT flavour, max(version_code) AS vc, (array_agg(version_name || '+' || version_code ORDER BY version_code DESC))[1] AS latest FROM app.app_release WHERE status = 'published' GROUP BY flavour),
        dev AS (
          SELECT d.id, d.device_uuid::text AS uuid, d.flavour, d.status, d.device_owner, d.device_info ->> 'manufacturer' AS mf, d.device_info ->> 'model' AS model, d.app_version, rel.latest,
                 d.last_contact_at, rep.battery_pct, rep.net, d.mdm_policy_id, p.name AS policy_name, d.trust_level, d.integrity_verdict, d.enrolled_at, d.lockdown_level, d.zone_id,
                 (d.integrity_verdict = 'fail') AS f_rooted,
                 (cardinality(coalesce(rep.mock_location_apps, '{}')) > 0) AS f_mock,
                 (rel.vc IS NOT NULL AND d.app_version ~ '[+][0-9]+$' AND split_part(d.app_version, '+', 2)::bigint < rel.vc) AS f_outdated,
                 coalesce(rep.dev_options_enabled, false) AS f_dev, coalesce(rep.adb_enabled, false) AS f_adb, (NOT d.device_owner) AS f_notowner,
                 (rep.location_enabled IS FALSE) AS f_loc, (rep.auto_time_enabled IS FALSE) AS f_time,
                 (EXISTS (SELECT 1 FROM app.device_policy x WHERE x.device_id = d.id) AND d.policy_version_applied IS DISTINCT FROM (SELECT max(policy_version) FROM app.device_policy x WHERE x.device_id = d.id)) AS f_drift,
                 (d.last_contact_at IS NULL OR d.last_contact_at < :now - interval '48 hours') AS f_stale,
                 (SELECT coalesce(array_agg(u.username ORDER BY b.bind_ordinal), '{}') FROM app.device_binding b JOIN app.app_user u ON u.id = b.user_id WHERE b.device_id = d.id AND b.status = 'active') AS users,
                 (SELECT count(*) FROM app.device_command c WHERE c.device_id = d.id AND c.state IN $SYSTEM_STATES) AS pending
            FROM app.device d LEFT JOIN rep ON rep.device_id = d.id LEFT JOIN rel ON rel.flavour = d.flavour LEFT JOIN app.mdm_policy p ON p.id = d.mdm_policy_id
           WHERE d.status IN ('enrolled','active','suspended') OR :all)
        SELECT * FROM dev
    """.trimIndent()

    private fun flags(rs: ResultSet) = FLAGS.filter { rs.getBoolean(FLAG_COLUMN.getValue(it)) }

    private fun device(rs: ResultSet): MdmDto = MdmDto(
        rs.getLong("id"), rs.getString("uuid"), rs.getString("flavour"), rs.getString("status"), rs.getBoolean("device_owner"),
        (rs.getArray("users").array as Array<*>).map { it as String }, listOfNotNull(rs.getString("mf"), rs.getString("model")).joinToString(" ").ifBlank { null },
        rs.getString("app_version"), rs.getString("latest"), rs.getObject("last_contact_at", OffsetDateTime::class.java).wire(),
        rs.getObject("battery_pct") as Int?, rs.getString("net")?.takeIf { it in setOf("wifi", "mobile", "offline") } ?: if (rs.getObject("battery_pct") == null) null else "unknown",
        rs.getObject("mdm_policy_id") as Long?, rs.getString("policy_name"), flags(rs), rs.getInt("pending"),
    )

    fun listDevices(status: String?, flavour: String?, flag: String?, policyId: Long?, search: String?, limit: Int, after: Long?): MdmDevicePage {
        if (status != null && status !in setOf("enrolled", "active", "suspended", "revoked", "replaced")) bad("status")
        if (flavour != null && flavour !in setOf("sr", "amo", "tso")) bad("flavour")
        if (flag != null && flag !in FLAG_COLUMN) bad("flag")
        val w = mutableListOf("true")
        if (status != null) w += "status = :status"; if (flavour != null) w += "flavour = :flavour"; if (flag != null) w += FLAG_COLUMN.getValue(flag)
        if (policyId != null) w += "mdm_policy_id = :policy"; if (after != null) w += "id < :after"
        if (search != null) w += "((coalesce(mf, '') || ' ' || coalesce(model, '')) ILIKE :q OR uuid ILIKE :q OR EXISTS (SELECT 1 FROM unnest(users) un WHERE un ILIKE :q))"
        return db.readJdbi.withHandle<MdmDevicePage, Exception> { h ->
            val q = h.createQuery("$deviceSql WHERE ${w.joinToString(" AND ")} ORDER BY id DESC LIMIT :lim").bind("lim", limit + 1).bind("now", ts(clock.now())).bind("all", status != null)
            status?.let { q.bind("status", it) }; flavour?.let { q.bind("flavour", it) }; policyId?.let { q.bind("policy", it) }; after?.let { q.bind("after", it) }
            search?.let { q.bind("q", "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%") }
            val rows = q.map { rs, _ -> device(rs) }.list()
            MdmDevicePage(rows.take(limit), if (rows.size > limit) rows[limit - 1].device_id.toString() else null)
        }
    }

    fun getDevice(id: Long): MdmDeviceDetail = db.readJdbi.withHandle<MdmDeviceDetail, Exception> { h ->
        val row = h.createQuery("$deviceSql WHERE id = :id").bind("id", id).bind("now", ts(clock.now())).bind("all", true)
            .map { rs, _ -> Triple(device(rs), listOf(rs.getString("trust_level"), rs.getString("integrity_verdict"), rs.getString("lockdown_level")), rs.getObject("enrolled_at", OffsetDateTime::class.java).wire()!! to (rs.getObject("zone_id") as Long?)) }
            .findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such device")
        val report = h.createQuery("SELECT report::text FROM app.device_status_report WHERE device_id = :d ORDER BY reported_at DESC, id DESC LIMIT 1").bind("d", id).mapTo(String::class.java).findOne().orElse(null)?.let { JSON.parseToJsonElement(it) }
        val policy = row.first.policy_id?.let { policyById(h, it) }
        MdmDeviceDetail(row.first, row.third.first, row.second[0], row.second[1], row.second[2], row.third.second, report, policy, commandsOf(h, id, 20, null).map { it.first })
    }

    // ---------------- activity and alerts ----------------

    /** Newest first; the cursor is the `at` of the last row (microseconds since epoch), so equal timestamps may repeat across a page edge, never vanish unseen twice. */
    fun activity(deviceId: Long?, limit: Int, after: Long?): MdmActivityPage = db.readJdbi.withHandle<MdmActivityPage, Exception> { h ->
        if (deviceId != null) h.createQuery("SELECT 1 FROM app.device WHERE id = :i").bind("i", deviceId).mapTo(Int::class.java).findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such device")
        val sql = """
            SELECT * FROM (
              SELECT a.at, CASE WHEN a.entity = 'device_command' THEN 'command' WHEN a.entity = 'enrolment_token' THEN 'enrolment' WHEN a.entity = 'mdm_policy' OR a.action = 'mdm_policy_assign' THEN 'policy' ELSE 'state' END AS kind,
                     CASE WHEN a.entity = 'device' THEN a.entity_id::bigint WHEN a.entity = 'device_command' THEN (a.after ->> 'device_id')::bigint ELSE NULL END AS dev,
                     a.entity || ' ' || a.action || CASE WHEN a.after ->> 'type' IS NOT NULL THEN ' ' || (a.after ->> 'type') ELSE '' END AS summary, a.actor_username AS actor, a.reason, a.entity_id AS ref
                FROM app.audit_log a
               WHERE a.entity IN ('device','device_command','mdm_policy','enrolment_token') AND (a.entity <> 'device' OR a.entity_id ~ '^[0-9]+$')
              UNION ALL
              SELECT r.reported_at, 'status', r.device_id, 'status report ' || coalesce(r.trigger, 'periodic') || ' battery ' || r.battery_pct || '%', NULL, NULL, r.id::text
                FROM app.device_status_report r WHERE r.trigger IN ('enrolment','policy_applied','boot','integrity_change','app_update','directive')
            ) x WHERE (CAST(:dev AS bigint) IS NULL OR dev = :dev) AND (CAST(:after AS bigint) IS NULL OR at < to_timestamp(CAST(:after AS bigint) / 1000000.0))
            ORDER BY at DESC LIMIT :lim
        """.trimIndent()
        val rows = h.createQuery(sql).bind("dev", deviceId).bind("after", after).bind("lim", limit + 1).map { rs, _ ->
            val at = rs.getObject("at", OffsetDateTime::class.java)
            MdmActivityEntry(at.wire()!!, rs.getString("kind"), rs.getObject("dev") as Long?, rs.getString("summary"), rs.getString("actor"), rs.getString("reason"), rs.getString("ref")) to at.toInstant()
        }.list()
        val cut = rows.take(limit)
        MdmActivityPage(cut.map { it.first }, if (rows.size > limit) cut.last().second.let { it.epochSecond * 1_000_000 + it.nano / 1000 }.toString() else null)
    }

    fun alerts(flag: String?, limit: Int, after: Long?): MdmAlertPage {
        if (flag != null && flag !in FLAG_COLUMN) bad("flag")
        val offset = after ?: 0L
        return db.readJdbi.withHandle<MdmAlertPage, Exception> { h ->
            val all = h.createQuery("$deviceSql WHERE status IN ('enrolled','active','suspended') ORDER BY id DESC").bind("now", ts(clock.now())).bind("all", false).map { rs, _ ->
                val d = device(rs)
                val since = (rs.getObject("last_contact_at", OffsetDateTime::class.java) ?: rs.getObject("enrolled_at", OffsetDateTime::class.java)).wire()!!
                d.compliance_flags.filter { flag == null || it == flag }.map { f ->
                    MdmAlert(d.device_id, d.device_uuid, d.users, d.flavour, f, since, if (f == "outdated_app") "${d.app_version} < ${d.latest_app_version}" else null)
                }
            }.list().flatten()
            val page = all.drop(offset.toInt()).take(limit + 1)
            MdmAlertPage(page.take(limit), if (page.size > limit) (offset + limit).toString() else null)
        }
    }

    // ---------------- policies ----------------

    private fun validate(w: MdmPolicyWrite) {
        if (w.name.trim().length !in 2..80) bad("name", "out_of_range")
        if ((w.description?.length ?: 0) > 300) bad("description", "out_of_range")
        if (w.allowed_packages.size > 300 || w.allowed_packages.any { !PACKAGE.matches(it) }) bad("allowed_packages")
        if (w.block_windows.size > 14) bad("block_windows", "out_of_range")
        w.block_windows.forEachIndexed { i, b ->
            if (b.days.isEmpty() || b.days.size > 7 || b.days.any { it !in 1..7 } || b.days.toSet().size != b.days.size) bad("block_windows/$i/days")
            if (!HHMM.matches(b.start)) bad("block_windows/$i/start"); if (!HHMM.matches(b.end)) bad("block_windows/$i/end")
            if (b.start == b.end) bad("block_windows/$i/end", "invalid_value", "a window cannot start and end at the same time")
            if (b.packages.size > 200 || b.packages.any { !PACKAGE.matches(it) }) bad("block_windows/$i/packages")
        }
        if (w.kiosk.packages.size > 10 || w.kiosk.packages.any { !PACKAGE.matches(it) }) bad("kiosk/packages")
        if (w.kiosk.enabled && w.kiosk.packages.isEmpty()) bad("kiosk/packages", "required", "kiosk needs at least one package")
        if (w.location_sampling.mode !in setOf("off", "on_demand", "interval")) bad("location_sampling/mode")
        if (w.location_sampling.mode == "interval" && w.location_sampling.interval_min !in 5..120) bad("location_sampling/interval_min", "out_of_range")
        if (w.location_sampling.mode != "interval" && w.location_sampling.interval_min != null) bad("location_sampling/interval_min", "invalid_value", "interval_min only with mode interval")
    }

    private fun docOf(w: MdmPolicyWrite): JsonObject = buildJsonObject {
        put("allowed_packages", JSON.encodeToJsonElement(STRINGS, w.allowed_packages.distinct().sorted()))
        put("block_windows", JSON.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(MdmBlockWindow.serializer()), w.block_windows.map { it.copy(days = it.days.sorted(), packages = it.packages.distinct().sorted()) }))
        put("kiosk", JSON.encodeToJsonElement(MdmKiosk.serializer(), w.kiosk))
        put("location_sampling", JSON.encodeToJsonElement(MdmLocationSampling.serializer(), w.location_sampling))
    }

    private fun policyRow(rs: ResultSet): MdmPolicy {
        val doc = JSON.parseToJsonElement(rs.getString("doc")).jsonObject
        return MdmPolicy(
            rs.getLong("id"), rs.getString("name"), rs.getString("description"),
            JSON.decodeFromJsonElement(STRINGS, doc.getValue("allowed_packages")),
            JSON.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(MdmBlockWindow.serializer()), doc.getValue("block_windows")),
            JSON.decodeFromJsonElement(MdmKiosk.serializer(), doc.getValue("kiosk")), JSON.decodeFromJsonElement(MdmLocationSampling.serializer(), doc.getValue("location_sampling")),
            rs.getInt("version"), rs.getInt("n"), rs.getObject("updated_at", OffsetDateTime::class.java).wire()!!,
        )
    }

    private val policySql = "SELECT p.*, (SELECT count(*) FROM app.device d WHERE d.mdm_policy_id = p.id)::int AS n FROM app.mdm_policy p"
    private fun policyById(h: Handle, id: Long): MdmPolicy? = h.createQuery("$policySql WHERE p.id = :i").bind("i", id).map { rs, _ -> policyRow(rs) }.findOne().orElse(null)

    fun listPolicies(limit: Int, after: Long?): MdmPolicyPage = db.readJdbi.withHandle<MdmPolicyPage, Exception> { h ->
        val rows = h.createQuery("$policySql WHERE (CAST(:after AS bigint) IS NULL OR p.id > :after) ORDER BY p.id LIMIT :lim").bind("after", after).bind("lim", limit + 1).map { rs, _ -> policyRow(rs) }.list()
        MdmPolicyPage(rows.take(limit), if (rows.size > limit) rows[limit - 1].policy_id.toString() else null)
    }

    fun getPolicy(id: Long): MdmPolicy = db.readJdbi.withHandle<MdmPolicy?, Exception> { policyById(it, id) } ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such policy")

    fun createPolicy(p: AronPrincipal, w: MdmPolicyWrite, requestId: String?): MdmPolicy {
        validate(w)
        return db.jdbi.inTransaction<MdmPolicy, Exception> { h ->
            val dup = h.createQuery("SELECT EXISTS (SELECT 1 FROM app.mdm_policy WHERE lower(name) = lower(:n))").bind("n", w.name.trim()).mapTo(Boolean::class.java).one()
            if (dup) throw ApiProblem(ProblemCode.ERR_CONFLICT, "a policy with this name exists")
            val id = h.createQuery("INSERT INTO app.mdm_policy (name, description, doc, created_by, updated_by) VALUES (:n, :d, CAST(:doc AS jsonb), :u, :u) RETURNING id")
                .bind("n", w.name.trim()).bind("d", w.description).bind("doc", docOf(w).toString()).bind("u", p.userId).mapTo(Long::class.java).one()
            audit(h, p, "mdm_policy", id.toString(), "create", null, buildJsonObject { put("name", w.name.trim()) }, w.reason, requestId)
            policyById(h, id)!!
        }
    }

    /** Replaces the policy (optimistic `version`); every device on it gets an apply_policy command so the change reaches the phone. */
    fun updatePolicy(p: AronPrincipal, id: Long, w: MdmPolicyWrite, requestId: String?): MdmPolicy {
        validate(w)
        val v = w.version ?: bad("version", "required")
        return db.jdbi.inTransaction<MdmPolicy, Exception> { h ->
            val cur = h.createQuery("SELECT version FROM app.mdm_policy WHERE id = :i FOR UPDATE").bind("i", id).mapTo(Int::class.java).findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such policy")
            if (cur != v) throw ApiProblem(ProblemCode.ERR_CONFLICT, "the policy changed (version $cur); reload and retry")
            if (h.createQuery("SELECT EXISTS (SELECT 1 FROM app.mdm_policy WHERE lower(name) = lower(:n) AND id <> :i)").bind("n", w.name.trim()).bind("i", id).mapTo(Boolean::class.java).one()) throw ApiProblem(ProblemCode.ERR_CONFLICT, "a policy with this name exists")
            h.createUpdate("UPDATE app.mdm_policy SET name = :n, description = :d, doc = CAST(:doc AS jsonb), version = version + 1, updated_by = :u WHERE id = :i")
                .bind("n", w.name.trim()).bind("d", w.description).bind("doc", docOf(w).toString()).bind("u", p.userId).bind("i", id).execute()
            h.createUpdate("UPDATE app.device SET mdm_policy_version = :v WHERE mdm_policy_id = :i").bind("v", cur + 1).bind("i", id).execute()
            audit(h, p, "mdm_policy", id.toString(), "update", buildJsonObject { put("version", cur) }, buildJsonObject { put("version", cur + 1); put("name", w.name.trim()) }, w.reason, requestId)
            val deviceIds = h.createQuery("SELECT id FROM app.device WHERE mdm_policy_id = :i AND status IN ('enrolled','active','suspended')").bind("i", id).mapTo(Long::class.java).list()
            deviceIds.forEach { queuePolicyCommand(h, p, it, id, "policy ${w.name.trim()} v${cur + 1}", requestId) }
            afterCommit(deviceIds)
            policyById(h, id)!!
        }
    }

    fun assignPolicy(p: AronPrincipal, deviceId: Long, req: MdmPolicyAssignRequest, requestId: String?): MdmDeviceDetail {
        if (req.reason.trim().length !in 3..300) bad("reason", "out_of_range")
        db.jdbi.useTransaction<Exception> { h ->
            val cur = h.createQuery("SELECT mdm_policy_id, status FROM app.device WHERE id = :i FOR UPDATE").bind("i", deviceId).map { rs, _ -> (rs.getObject(1) as Long?) to rs.getString(2) }.findOne().orElse(null)
                ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such device")
            if (cur.second in setOf("revoked", "replaced")) throw ApiProblem(ProblemCode.ERR_CONFLICT, "the device is ${cur.second}")
            val ver = req.policy_id?.let { pid -> h.createQuery("SELECT version FROM app.mdm_policy WHERE id = :i").bind("i", pid).mapTo(Int::class.java).findOne().orElse(null) ?: bad("policy_id", "not_found") }
            h.createUpdate("UPDATE app.device SET mdm_policy_id = :p, mdm_policy_version = :v WHERE id = :i").bind("p", req.policy_id).bind("v", ver).bind("i", deviceId).execute()
            audit(h, p, "device", deviceId.toString(), "mdm_policy_assign", buildJsonObject { put("policy_id", cur.first?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) },
                buildJsonObject { put("policy_id", req.policy_id?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) }, req.reason.trim(), requestId)
            queuePolicyCommand(h, p, deviceId, req.policy_id, req.reason.trim(), requestId)
        }
        afterCommit(listOf(deviceId))
        return getDevice(deviceId)
    }

    private fun afterCommit(ids: List<Long>) { ids.forEach { runCatching { push.nudge(it) } } }

    /** The current policy of the device as an apply_policy command {policy_id, version, policy}; a cleared assignment sends {policy_id: null} so the phone drops its stored policy. */
    private fun queuePolicyCommand(h: Handle, p: AronPrincipal, deviceId: Long, policyId: Long?, reason: String, requestId: String?) {
        val params = if (policyId == null) buildJsonObject { put("policy_id", kotlinx.serialization.json.JsonNull) } else policyById(h, policyId)!!.let { pol ->
            buildJsonObject {
                put("policy_id", pol.policy_id); put("version", pol.version)
                put("policy", buildJsonObject {
                    put("allowed_packages", JSON.encodeToJsonElement(STRINGS, pol.allowed_packages))
                    put("block_windows", JSON.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(MdmBlockWindow.serializer()), pol.block_windows))
                    put("kiosk", JSON.encodeToJsonElement(MdmKiosk.serializer(), pol.kiosk)); put("location_sampling", JSON.encodeToJsonElement(MdmLocationSampling.serializer(), pol.location_sampling))
                })
            }
        }
        insertCommand(h, p, UUID.randomUUID(), deviceId, "apply_policy", params, reason.take(300).ifBlank { "policy" }, 72, requestId)
    }

    // ---------------- commands ----------------

    private fun commandRow(rs: ResultSet) = MdmCommand(
        rs.getString("command_id"), rs.getLong("device_id"), rs.getString("type"), rs.getString("params")?.let { JSON.parseToJsonElement(it).jsonObject }, effectiveState(rs), rs.getString("reason"),
        rs.getObject("created_at", OffsetDateTime::class.java).wire()!!, rs.getString("by_name"), rs.getString("ap_name"), rs.getObject("approved_at", OffsetDateTime::class.java).wire(),
        rs.getObject("sent_at", OffsetDateTime::class.java).wire(), rs.getObject("acked_at", OffsetDateTime::class.java).wire(), rs.getObject("expires_at", OffsetDateTime::class.java).wire()!!,
        rs.getString("result")?.let { JSON.parseToJsonElement(it).jsonObject },
    )

    /** A queued, sent or pending command past its expiry reads as expired even before the next pull writes it. */
    private fun effectiveState(rs: ResultSet): String {
        val s = rs.getString("state")
        return if (s in setOf("queued", "sent", "pending_approval") && rs.getObject("expires_at", OffsetDateTime::class.java).toInstant().isBefore(clock.now())) "expired" else s
    }

    private val commandSql = """
        SELECT c.*, cu.username AS by_name, au.username AS ap_name FROM app.device_command c
          LEFT JOIN app.app_user cu ON cu.id = c.created_by LEFT JOIN app.app_user au ON au.id = c.approved_by
    """.trimIndent()

    private fun commandsOf(h: Handle, deviceId: Long, limit: Int, after: Long?): List<Pair<MdmCommand, Long>> =
        h.createQuery("$commandSql WHERE c.device_id = :d AND (CAST(:after AS bigint) IS NULL OR c.seq < :after) ORDER BY c.seq DESC LIMIT :lim")
            .bind("d", deviceId).bind("after", after).bind("lim", limit).map { rs, _ -> commandRow(rs) to rs.getLong("seq") }.list()

    fun listCommands(deviceId: Long, limit: Int, after: Long?): MdmCommandPage = db.readJdbi.withHandle<MdmCommandPage, Exception> { h ->
        h.createQuery("SELECT 1 FROM app.device WHERE id = :i").bind("i", deviceId).mapTo(Int::class.java).findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such device")
        val rows = commandsOf(h, deviceId, limit + 1, after)
        MdmCommandPage(rows.take(limit).map { it.first }, if (rows.size > limit) rows[limit - 1].second.toString() else null)
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    private fun sign(id: UUID, deviceUuid: String, type: String, created: Instant, expires: Instant, params: JsonObject): String =
        keys.signer.sign(JWSHeader(JWSAlgorithm.ES256), listOf("aron-command-v1", id.toString(), deviceUuid, type, created.wire(), expires.wire(), params.toString()).joinToString("\n").toByteArray()).toString()

    private fun insertCommand(h: Handle, p: AronPrincipal, id: UUID, deviceId: Long, type: String, params: JsonObject, reason: String, ttlH: Int, requestId: String?): Pair<MdmCommand, Boolean> {
        val now = clock.now(); val exp = now.plus(Duration.ofHours(ttlH.toLong()))
        // The signature covers params as the phone will see them: read back from jsonb (key order and spacing are jsonb's), parsed, printed compact.
        val norm = JSON.parseToJsonElement(h.createQuery("SELECT CAST(:p AS jsonb)::text").bind("p", params.toString()).mapTo(String::class.java).one()).jsonObject
        val dev = h.createQuery("SELECT device_uuid::text, status FROM app.device WHERE id = :i").bind("i", deviceId).map { rs, _ -> rs.getString(1) to rs.getString(2) }.findOne().orElse(null)
            ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such device")
        val hash = sha("$deviceId|$type|$params|$reason|$ttlH")
        val needsApproval = type == "factory_reset"
        val inserted = h.createUpdate(
            """
            INSERT INTO app.device_command (command_id, device_id, type, params, state, reason, request_sha256, sig, created_by, created_at, expires_at)
            VALUES (:id, :d, :t, CAST(:p AS jsonb), :s, :r, :h, :sig, :u, :now, :e) ON CONFLICT (command_id) DO NOTHING
            """.trimIndent(),
        ).bind("id", id).bind("d", deviceId).bind("t", type).bind("p", norm.toString()).bind("s", if (needsApproval) "pending_approval" else "queued").bind("r", reason).bind("h", hash)
            .bind("sig", if (needsApproval) null else sign(id, dev.first, type, now, exp, norm)).bind("u", p.userId).bind("now", ts(now)).bind("e", ts(exp)).execute() == 1
        if (!inserted) {
            val same = h.createQuery("SELECT device_id = :d AND request_sha256 = :h FROM app.device_command WHERE command_id = :id").bind("d", deviceId).bind("h", hash).bind("id", id).mapTo(Boolean::class.java).one()
            if (!same) throw ApiProblem(ProblemCode.ERR_CONFLICT, "command_id already used for a different command")
            return h.createQuery("$commandSql WHERE c.command_id = :id").bind("id", id).map { rs, _ -> commandRow(rs) }.one() to false
        }
        if (dev.second in setOf("revoked", "replaced")) throw ApiProblem(ProblemCode.ERR_CONFLICT, "the device is ${dev.second}")
        audit(h, p, "device_command", id.toString(), "create", null, buildJsonObject { put("device_id", deviceId); put("type", type); put("state", if (needsApproval) "pending_approval" else "queued") }, reason, requestId)
        return h.createQuery("$commandSql WHERE c.command_id = :id").bind("id", id).map { rs, _ -> commandRow(rs) }.one() to true
    }

    fun createCommand(p: AronPrincipal, deviceId: Long, req: MdmCommandCreateRequest, requestId: String?): Pair<MdmCommand, Boolean> {
        val id = runCatching { UUID.fromString(req.command_id) }.getOrNull()?.takeIf { it.toString() == req.command_id.lowercase() } ?: bad("command_id")
        if (req.type !in COMMANDS) bad("type"); if (req.reason.trim().length !in 3..300) bad("reason", "out_of_range"); if (req.ttl_h !in 1..72) bad("ttl_h", "out_of_range")
        val params = req.params ?: JsonObject(emptyMap())
        fun text(k: String) = (params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (req.type) {
            "message" -> {
                val bn = text("text_bn"); val en = text("text_en")
                if (bn.isNullOrBlank() && en.isNullOrBlank()) bad("params", "required", "a message needs text_bn or text_en")
                if ((bn?.length ?: 0) > 300 || (en?.length ?: 0) > 300) bad("params", "out_of_range")
                if (params.keys.any { it !in setOf("text_bn", "text_en") }) bad("params")
            }
            "lock" -> { if (params.keys.any { it != "message" } || (text("message")?.length ?: 0) > 300) bad("params") }
            else -> if (params.isNotEmpty()) bad("params", "invalid_value", "${req.type} takes no params")
        }
        val out = db.jdbi.inTransaction<Pair<MdmCommand, Boolean>, Exception> { h -> insertCommand(h, p, id, deviceId, req.type, params, req.reason.trim(), req.ttl_h, requestId) }
        if (out.second && out.first.state == "queued") afterCommit(listOf(deviceId))
        return out
    }

    /** Second approver: a person other than the requester approves or rejects a command in `pending_approval` (factory reset). */
    fun decide(p: AronPrincipal, commandId: String, req: MdmCommandDecisionRequest, requestId: String?): MdmCommand {
        val id = runCatching { UUID.fromString(commandId) }.getOrNull() ?: bad("command_id")
        if (req.decision !in setOf("approve", "reject")) bad("decision"); if (req.reason.trim().length !in 3..300) bad("reason", "out_of_range")
        val result = db.jdbi.inTransaction<MdmCommand, Exception> { h ->
            val cur = h.createQuery("SELECT c.state, c.created_by, c.type, c.params::text, c.created_at, c.expires_at, c.device_id, d.device_uuid::text FROM app.device_command c JOIN app.device d ON d.id = c.device_id WHERE c.command_id = :i FOR UPDATE OF c")
                .bind("i", id).map { rs, _ -> arrayOf<Any?>(rs.getString(1), rs.getObject(2) as Long?, rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime::class.java), rs.getObject(6, OffsetDateTime::class.java), rs.getLong(7), rs.getString(8)) }.findOne().orElse(null)
                ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such command")
            if (cur[0] != "pending_approval") throw ApiProblem(ProblemCode.ERR_CONFLICT, "the command is ${cur[0]}, not waiting for approval")
            if (cur[1] == p.userId) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a second person must decide this command")
            val now = clock.now()
            if ((cur[5] as OffsetDateTime).toInstant().isBefore(now)) throw ApiProblem(ProblemCode.ERR_CONFLICT, "the command expired")
            if (req.decision == "approve") {
                val sig = sign(id, cur[7] as String, cur[2] as String, (cur[4] as OffsetDateTime).toInstant(), (cur[5] as OffsetDateTime).toInstant(), JSON.parseToJsonElement(cur[3] as String).jsonObject)
                h.createUpdate("UPDATE app.device_command SET state = 'queued', sig = :s, approved_by = :u, approved_at = :now, decision_reason = :r WHERE command_id = :i")
                    .bind("s", sig).bind("u", p.userId).bind("now", ts(now)).bind("r", req.reason.trim()).bind("i", id).execute()
            } else {
                h.createUpdate("UPDATE app.device_command SET state = 'rejected', approved_by = :u, approved_at = :now, decision_reason = :r WHERE command_id = :i")
                    .bind("u", p.userId).bind("now", ts(now)).bind("r", req.reason.trim()).bind("i", id).execute()
            }
            audit(h, p, "device_command", id.toString(), req.decision, buildJsonObject { put("state", "pending_approval") },
                buildJsonObject { put("device_id", cur[6] as Long); put("type", cur[2] as String); put("state", if (req.decision == "approve") "queued" else "rejected") }, req.reason.trim(), requestId)
            h.createQuery("$commandSql WHERE c.command_id = :id").bind("id", id).map { rs, _ -> commandRow(rs) }.one()
        }
        if (result.state == "queued") afterCommit(listOf(result.device_id))
        return result
    }

    // ---------------- the phone's side ----------------

    /** Queued and sent commands of this phone, oldest first; expired ones are closed; queued become sent. Pulled on every sync. */
    fun pull(device: DeviceService.DeviceRow): DeviceCommandList = db.jdbi.inTransaction<DeviceCommandList, Exception> { h ->
        val now = ts(clock.now())
        h.createUpdate("UPDATE app.device_command SET state = 'expired' WHERE device_id = :d AND state IN ('pending_approval','queued','sent') AND expires_at <= :now").bind("d", device.id).bind("now", now).execute()
        val rows = h.createQuery("SELECT command_id::text, type, params::text, created_at, expires_at, sig FROM app.device_command WHERE device_id = :d AND state IN ('queued','sent') ORDER BY seq LIMIT 50 FOR UPDATE")
            .bind("d", device.id).map { rs, _ ->
                DeviceCommand(rs.getString(1), rs.getString(2), JSON.parseToJsonElement(rs.getString(3)).jsonObject, rs.getObject(4, OffsetDateTime::class.java).wire()!!, rs.getObject(5, OffsetDateTime::class.java).wire()!!, rs.getString(6))
            }.list()
        if (rows.isNotEmpty()) h.createUpdate("UPDATE app.device_command SET state = 'sent', sent_at = :now WHERE device_id = :d AND state = 'queued'").bind("d", device.id).bind("now", now).execute()
        DeviceCommandList(rows)
    }

    /** Idempotent: the first result wins; a late ack of an expired command changes nothing and says so. */
    fun ack(device: DeviceService.DeviceRow, commandId: String, req: DeviceCommandAck): DeviceCommandAckResult {
        val id = runCatching { UUID.fromString(commandId) }.getOrNull() ?: bad("command_id")
        if (req.state !in setOf("acked", "failed")) bad("state")
        if ((req.result?.toString()?.length ?: 0) > 2048) bad("result", "out_of_range")
        return db.jdbi.inTransaction<DeviceCommandAckResult, Exception> { h ->
            val cur = h.createQuery("SELECT state, expires_at FROM app.device_command WHERE command_id = :i AND device_id = :d FOR UPDATE").bind("i", id).bind("d", device.id)
                .map { rs, _ -> rs.getString(1) to rs.getObject(2, OffsetDateTime::class.java) }.findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such command")
            if (cur.first !in setOf("queued", "sent")) return@inTransaction DeviceCommandAckResult(commandId, cur.first)
            val now = clock.now()
            h.createUpdate("UPDATE app.device_command SET state = :s, acked_at = :now, result = CAST(:r AS jsonb) WHERE command_id = :i")
                .bind("s", req.state).bind("now", ts(now)).bind("r", req.result?.toString()).bind("i", id).execute()
            AuditLog.write(h, null, "device_command", id.toString(), req.state, null, buildJsonObject { put("device_id", device.id) }, null, null, via = "device")
            DeviceCommandAckResult(commandId, req.state)
        }
    }

    // ---------------- enrolment ----------------

    fun createToken(p: AronPrincipal, req: EnrolmentTokenCreateRequest) = devices.createToken(p, req)
    fun listTokens(activeOnly: Boolean, limit: Int, after: Long?) = devices.listTokens(activeOnly, limit, after)
    fun revokeToken(p: AronPrincipal, id: Long) = devices.revokeToken(p, id)
}

