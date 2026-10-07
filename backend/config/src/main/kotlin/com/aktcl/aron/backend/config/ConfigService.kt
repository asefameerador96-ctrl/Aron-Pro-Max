package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The config change workflow of docs/24 s9.3 and s9.4 (backend:config, row F-API-037).
 *
 * A change set is validated (key, scope level, scope node, type, bounds, dates), classified (max risk of its items),
 * and then: C0/C1 apply at once; C2 is `scheduled` for `cfg.sys.c2_delay_min` and cancellable; C3 waits for a second
 * SUPERADMIN, outside the freeze windows and within `cfg.sys.c3_max_per_hour`. Applying a change takes one global
 * advisory lock, closes the rows it supersedes (`effective_to`, never an UPDATE of the value), inserts the new rows
 * under a new monotonic `config_version`, writes the audit row and `NOTIFY cfg_changed`, all in one transaction.
 */
/** Whether a TSO's reach covers a zone (implemented over the masterdata reach resolver at wiring time). */
fun interface NodeReach { fun coversZone(p: AronPrincipal, zoneId: Long): Boolean }

class ConfigService(private val db: Database, val resolver: ConfigResolver, private val clock: AronClock = AronClock.SYSTEM, private val nodeReach: NodeReach? = null) {
    private val dhaka = ZoneId.of("Asia/Dhaka")

    // ---------------------------------------------------------------- reads

    fun keys(area: String?): List<ConfigKeyDto> = resolver.registry().values.filter { !it.retired && (area == null || it.area == area) }
        .sortedBy { it.key }.map { it.toDto() }

    fun values(key: String, scopeType: String?, scopeId: Long?, history: Boolean, limit: Int, cursor: Long?): ConfigValuePage {
        if (key !in resolver.registry()) throw ApiProblem(ProblemCode.ERR_CFG_UNKNOWN_KEY, key)
        val rows = db.jdbi.withHandle<List<ConfigValueDto>, Exception> { h ->
            val where = mutableListOf("v.key = :k")
            if (scopeType != null) where += "v.scope_type = :st"
            if (scopeId != null) where += "v.scope_id = :si"
            if (!history) where += "(v.effective_to IS NULL OR v.effective_to > :now)"
            if (cursor != null) where += "v.id < :c"
            val q = h.createQuery("SELECT v.* FROM app.cfg_value v WHERE ${where.joinToString(" AND ")} ORDER BY v.id DESC LIMIT :lim").bind("k", key).bind("lim", limit + 1)
            if (scopeType != null) q.bind("st", scopeType)
            if (scopeId != null) q.bind("si", scopeId)
            if (!history) q.bind("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC))
            if (cursor != null) q.bind("c", cursor)
            q.map { rs, _ -> valueDto(rs) }.list()
        }
        return ConfigValuePage(rows.take(limit), if (rows.size > limit) rows[limit - 1].id.toString() else null)
    }

    fun resolve(key: String, nodeType: String, nodeId: Long, at: Instant): ResolvedConfigValue {
        if (key !in resolver.registry()) throw ApiProblem(ProblemCode.ERR_CFG_UNKNOWN_KEY, key)
        if (nodeType !in Precedence.rank) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad node_type", errors = listOf(FieldError("query.node_type", "invalid_value")))
        applyDue()
        val chain = resolver.chainOf(nodeType, nodeId)
        return resolver.resolve(key, chain, at).toDto()
    }

    fun change(id: Long): ConfigChangeDto = db.jdbi.withHandle<ConfigChangeDto?, Exception> { h -> loadChange(h, id) } ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "change $id")

    fun changes(status: String?, key: String?, from: java.time.LocalDate?, to: java.time.LocalDate?, limit: Int, cursor: Long?): ConfigChangePage {
        applyDue()
        val rows = db.jdbi.withHandle<List<ConfigChangeDto>, Exception> { h ->
            val where = mutableListOf("TRUE")
            if (status != null) where += "c.status = :s"
            if (key != null) where += "EXISTS (SELECT 1 FROM jsonb_array_elements(c.items) i WHERE i->>'key' = :k)"
            if (from != null) where += "app.dhaka_date(c.requested_at) >= :from"
            if (to != null) where += "app.dhaka_date(c.requested_at) <= :to"
            if (cursor != null) where += "c.change_id < :c"
            val q = h.createQuery("SELECT c.* FROM app.cfg_change c WHERE ${where.joinToString(" AND ")} ORDER BY c.change_id DESC LIMIT :lim").bind("lim", limit + 1)
            if (status != null) q.bind("s", status)
            if (key != null) q.bind("k", key)
            if (from != null) q.bind("from", from)
            if (to != null) q.bind("to", to)
            if (cursor != null) q.bind("c", cursor)
            q.map { rs, _ -> changeDto(rs) }.list()
        }
        return ConfigChangePage(rows.take(limit), if (rows.size > limit) rows[limit - 1].change_id.toString() else null)
    }

    fun versions(limit: Int, cursor: Long?): ConfigVersionPage {
        val rows = db.jdbi.withHandle<List<ConfigVersionDto>, Exception> { h ->
            val q = h.createQuery("SELECT * FROM app.cfg_version ${if (cursor != null) "WHERE config_version < :c" else ""} ORDER BY config_version DESC LIMIT :lim").bind("lim", limit + 1)
            if (cursor != null) q.bind("c", cursor)
            q.map { rs, _ -> versionDto(rs) }.list()
        }
        return ConfigVersionPage(rows.take(limit), if (rows.size > limit) rows[limit - 1].version.toString() else null)
    }

    fun currentVersion(): Long = db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT COALESCE(max(config_version), 0) FROM app.cfg_version").mapTo(Long::class.java).one() }

    // ---------------------------------------------------------------- write: create

    /** POST /admin/config/changes. [clientUuid] (Idempotency-Key) makes a replay return the first answer. */
    fun create(p: AronPrincipal, req: ConfigChangeRequestIn, clientUuid: UUID?, requestId: String?, revertOf: Long? = null, kind: String = "change"): ConfigChangeDto {
        val tso = p.role == Role.TSO
        if (p.role != Role.ADMIN && p.role != Role.SUPERADMIN && !tso) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "config changes are made by ADMIN or SUPERADMIN")
        if (req.reason.trim().length < 10 || req.reason.length > 500) throw ApiProblem(ProblemCode.ERR_CFG_REASON_REQUIRED, "a reason of 10 to 500 characters is required", errors = listOf(FieldError("body.reason", "length")))
        if (req.changes.isEmpty() || req.changes.size > 100) throw ApiProblem(ProblemCode.ERR_VALIDATION, "1 to 100 changes", errors = listOf(FieldError("body.changes", "out_of_range")))
        if (req.break_glass && p.role != Role.SUPERADMIN) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "break-glass is for a SUPERADMIN")
        if (req.break_glass && tso) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "break-glass is for a SUPERADMIN")
        val now = clock.now()
        val reg = resolver.registry()
        val items = req.changes.mapIndexed { i, it -> prepare(it, i, reg, now) }
        // Two items on one key and scope with overlapping windows would race each other inside one version.
        items.groupBy { Triple(it.def.key, it.item.scope_type, it.item.scope_id) }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw ApiProblem(ProblemCode.ERR_VALIDATION, "${it.first} appears twice at ${it.second}:${it.third}", errors = listOf(FieldError("body.changes", "duplicate")))
        }
        if (tso) {
            // A TSO proposes (or, with cfg.geo.tso_radius_mode = apply, sets) the radius of outlets, routes and zones of own reach only.
            val reach = nodeReach ?: throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "TSO radius proposals are not enabled")
            for (pr in items) {
                if (pr.def.key != "cfg.geo.radius_m" || pr.item.scope_type !in setOf("outlet", "route", "zone") || pr.item.value is JsonNull)
                    throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a TSO changes only the radius of outlets, routes and zones")
                val zone = db.jdbi.withHandle<Long?, Exception> { h ->
                    h.createQuery(when (pr.item.scope_type) { "zone" -> "SELECT id FROM app.zone WHERE id = :i"; "route" -> "SELECT zone_id FROM app.route WHERE id = :i"; else -> "SELECT zone_id FROM app.outlet WHERE id = :i" })
                        .bind("i", pr.item.scope_id).mapTo(Long::class.java).findOne().orElse(null)
                }
                if (zone == null || !reach.coversZone(p, zone)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "outside your territory")
            }
        }
        val tsoPropose = tso && resolver.resolve("cfg.geo.tso_radius_mode", listOf(ScopeNode("global", 0)), now).value.let { (it as JsonPrimitive).content } == "propose"
        return db.jdbi.inTransaction<ConfigChangeDto, Exception> { h ->
            if (clientUuid != null) {
                h.createQuery("SELECT change_id, requested_by FROM app.cfg_change WHERE client_uuid = CAST(:u AS uuid)").bind("u", clientUuid.toString()).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.findOne().orElse(null)?.let { (id, by) ->
                    if (by != p.userId) throw ApiProblem(ProblemCode.ERR_CONFLICT, "idempotency key already used")
                    return@inTransaction loadChange(h, id)!!
                }
            }
            val outItems = items.map { it.withOld(oldValueAt(h, it)) }
            val risk = outItems.maxOf { pr ->
                val node = ScopeNode(pr.item.scope_type, pr.item.scope_id)
                val chain = resolver.chainOf(h, node.type, node.id)
                val at = pr.from ?: now
                val before = resolver.resolve(pr.def.key, chain, at).value
                val after = if (pr.item.value !is JsonNull) pr.item.value else resolver.resolve(pr.def.key, chain.filterNot { it == node }, at).value
                var c = RiskClassifier.classify(pr.def, node.type, pr.item.value.takeUnless { v -> v is JsonNull }, before, after)
                // F-ADM-012: an outlet override above 3 x the radius its zone resolves to needs an approver (C3).
                if (pr.def.key == "cfg.geo.radius_m" && node.type == "outlet" && pr.item.value !is JsonNull) {
                    val zoneValue = (resolver.resolve(pr.def.key, chain.filterNot { it == node }, at).value as? JsonPrimitive)?.doubleOrNull
                    val nv = (pr.item.value as? JsonPrimitive)?.doubleOrNull
                    if (zoneValue != null && nv != null && nv > 3 * zoneValue) c = 3
                }
                c
            }
            if (risk >= 3 && p.role != Role.SUPERADMIN) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a class C3 change is requested by a SUPERADMIN")
            val delayMin = if (risk == 2) cfgInt(h, "cfg.sys.c2_delay_min", now) else 0
            var effItems = outItems
            if (req.break_glass) {
                val mode = (resolver.resolve("cfg.sys.break_glass_mode", listOf(ScopeNode("global", 0)), now).value as JsonPrimitive).content
                val maxH = cfgInt(h, "cfg.sys.break_glass_max_h", now)
                if (mode == "off") throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "break-glass is switched off")
                val restore = revertOf != null
                if (mode == "restore_only" && !restore) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "break-glass may only restore")
                if (!restore) outItems.forEachIndexed { i, pr ->
                    if (!isRestrictive(pr, now)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "break-glass may only restore or restrict", errors = listOf(FieldError("body.changes[$i]", "not_restrictive")))
                }
                if (!restore) effItems = outItems.map { pr -> val cap = now.plusSeconds(maxH * 3600L); if (pr.to == null || pr.to.isAfter(cap)) Prepared(pr.def, pr.item, pr.from, cap, pr.old) else pr }
            }
            val status = when { req.break_glass -> "applied"; tsoPropose -> "pending_approval"; risk >= 3 -> "pending_approval"; risk == 2 && delayMin > 0 -> "scheduled"; else -> "applied" }
            val applyAt = if (status == "scheduled") now.plusSeconds(delayMin * 60L) else null
            if (applyAt != null) outItems.forEachIndexed { i, pr ->
                if (pr.to != null && !pr.to.isAfter(applyAt)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "effective_to ends before the change applies", errors = listOf(FieldError("body.changes[$i].effective_to", "before_apply")))
            }
            val blast = blastRadius(h, outItems)
            val inserted = h.createQuery(
                "INSERT INTO app.cfg_change (status, items, reason, risk_class, requested_by, requested_at, apply_at, blast_radius, client_uuid, is_revert_of) " +
                    "VALUES ('pending_approval', CAST(:items AS jsonb), :reason, :risk, :by, :at, :apply, CAST(:blast AS jsonb), CAST(:cu AS uuid), :rv) ON CONFLICT (client_uuid) DO NOTHING RETURNING change_id",
            ).bind("items", itemsJson(effItems)).bind("reason", req.reason.trim()).bind("risk", risk).bind("by", p.userId)
                .bind("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("apply", applyAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) })
                .bind("blast", Json.encodeToString(blast)).bind("cu", clientUuid?.toString()).bind("rv", revertOf).mapTo(Long::class.java).findOne().orElse(null)
            if (inserted == null) {
                // A concurrent request with the same Idempotency-Key won the insert: answer with its change.
                val (id, by) = h.createQuery("SELECT change_id, requested_by FROM app.cfg_change WHERE client_uuid = CAST(:u AS uuid)").bind("u", clientUuid.toString()).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.one()
                if (by != p.userId) throw ApiProblem(ProblemCode.ERR_CONFLICT, "idempotency key already used")
                return@inTransaction loadChange(h, id)!!
            }
            val changeId: Long = inserted
            AuditWriter.write(h, p, "cfg_change", changeId.toString(), if (req.break_glass) "break_glass" else "create", null, JsonPrimitive(status), req.reason, requestId)
            when (status) {
                "applied" -> commit(h, changeId, p.userId, now, kind, requestId, p)
                "scheduled" -> h.createUpdate("UPDATE app.cfg_change SET status = 'scheduled' WHERE change_id = :id").bind("id", changeId).execute()
                // pending_approval is the insert default
            }
            loadChange(h, changeId)!!
        }
    }

    // ---------------------------------------------------------------- write: revert and rollback

    /**
     * POST /admin/config/versions/{v}/rollback. `revert_this_version` restores what version v replaced; `rollback_to_this_version`
     * restores the whole state as of v. Both create a NEW change (never deleting history) that goes through the normal risk route.
     */
    fun rollback(p: AronPrincipal, version: Long, mode: String, reason: String, requestId: String?, breakGlass: Boolean = false): ConfigChangeDto {
        if (mode != "revert_this_version" && mode != "rollback_to_this_version") throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad mode", errors = listOf(FieldError("body.mode", "invalid_value")))
        val now = clock.now()
        val items = db.jdbi.withHandle<List<ConfigChangeItemIn>, Exception> { h ->
            h.createQuery("SELECT 1 FROM app.cfg_version WHERE config_version = :v").bind("v", version).mapTo(Int::class.java).findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "version $version")
            data class K(val key: String, val type: String, val id: Long)
            val touched: Set<K> = h.createQuery(
                if (mode == "revert_this_version") "SELECT DISTINCT key, scope_type, scope_id FROM app.cfg_value WHERE config_version = :v OR superseded_in_version = :v"
                else "SELECT DISTINCT key, scope_type, scope_id FROM app.cfg_value WHERE config_version > :v OR superseded_in_version > :v",
            ).bind("v", version).map { rs, _ -> K(rs.getString(1), rs.getString(2), rs.getLong(3)) }.list().toSet()
            val at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
            touched.mapNotNull { k ->
                // Target: the state before v (revert) or as of v (rollback); null = no row, i.e. remove the override.
                val target = h.createQuery(
                    if (mode == "revert_this_version")
                        "SELECT value FROM app.cfg_value WHERE key = :k AND scope_type = :t AND scope_id = :i AND superseded_in_version = :v ORDER BY effective_from DESC LIMIT 1"
                    else "SELECT value FROM app.cfg_value WHERE key = :k AND scope_type = :t AND scope_id = :i AND config_version <= :v AND (superseded_in_version IS NULL OR superseded_in_version > :v) ORDER BY effective_from DESC LIMIT 1",
                ).bind("k", k.key).bind("t", k.type).bind("i", k.id).bind("v", version).mapTo(String::class.java).findOne().map { Json.parseToJsonElement(it) }.orElse(JsonNull)
                val current = h.createQuery("SELECT value FROM app.cfg_value WHERE key = :k AND scope_type = :t AND scope_id = :i AND effective_from <= :now AND (effective_to IS NULL OR effective_to > :now)")
                    .bind("k", k.key).bind("t", k.type).bind("i", k.id).bind("now", at).mapTo(String::class.java).findOne().map { Json.parseToJsonElement(it) }.orElse(JsonNull)
                if (target == current) null else ConfigChangeItemIn(k.key, k.type, k.id, target)
            }.sortedWith(compareBy({ it.key }, { it.scope_type }, { it.scope_id }))
        }
        if (items.isEmpty()) throw ApiProblem(ProblemCode.ERR_CONFLICT, "nothing to change: the current values already equal that state")
        if (items.size > 100) throw ApiProblem(ProblemCode.ERR_VALIDATION, "more than 100 values would change; split the rollback")
        val text = (if (mode == "rollback_to_this_version") "Rollback to v$version: " else "Revert v$version: ") + reason
        return create(p, ConfigChangeRequestIn(text.take(500), breakGlass, items), null, requestId, revertOf = version, kind = if (mode == "rollback_to_this_version") "rollback" else "revert")
    }

    // ---------------------------------------------------------------- write: decide

    fun decide(p: AronPrincipal, changeId: Long, req: ConfigDecisionIn, requestId: String?): ConfigChangeDto {
        if (req.decision !in setOf("approve", "reject", "cancel", "adopt")) throw ApiProblem(ProblemCode.ERR_VALIDATION, "decision must be approve, reject, cancel or adopt", errors = listOf(FieldError("body.decision", "invalid_value")))
        if (p.role != Role.ADMIN && p.role != Role.SUPERADMIN) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "not allowed to decide config changes")
        val now = clock.now()
        var pendingAdopt: ConfigChangeDto? = null
        return db.jdbi.inTransaction<ConfigChangeDto, Exception> { h ->
            val row = h.createQuery("SELECT status, requested_by, risk_class FROM app.cfg_change WHERE change_id = :id FOR UPDATE").bind("id", changeId)
                .map { rs, _ -> Triple(rs.getString(1), rs.getLong(2), rs.getInt(3)) }.findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "change $changeId")
            val (status, requestedBy, risk) = row
            fun state(): Nothing = throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "the change is $status")
            when (req.decision) {
                "approve" -> {
                    if (status != "pending_approval") state()
                    if (p.role != Role.SUPERADMIN) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a C3 change is approved by a SUPERADMIN")
                    if (requestedBy == p.userId) throw ApiProblem(ProblemCode.ERR_CFG_SELF_APPROVAL, "the approver is never the requester")
                    if (inFreezeWindow(h, now)) throw ApiProblem(ProblemCode.ERR_CFG_FREEZE_WINDOW, "no class C3 change inside the freeze windows")
                    h.execute("SELECT pg_advisory_xact_lock(7242001)") // serialises the hourly cap with every commit
                    val cap = cfgInt(h, "cfg.sys.c3_max_per_hour", now)
                    val recent = h.createQuery("SELECT count(*) FROM app.cfg_change WHERE risk_class = 3 AND approved_at > :since AND status IN ('applied','reverted')")
                        .bind("since", OffsetDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC)).mapTo(Int::class.java).one()
                    if (recent >= cap) throw ApiProblem(ProblemCode.ERR_CONFLICT, "at most $cap class C3 changes per hour", retryAfterS = 600)
                    h.createUpdate("UPDATE app.cfg_change SET approver = :a, approved_at = :at, decided_at = :at, decision_note = :n WHERE change_id = :id")
                        .bind("a", p.userId).bind("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("n", req.note).bind("id", changeId).execute()
                    AuditWriter.write(h, p, "cfg_change", changeId.toString(), "approve", JsonPrimitive(status), JsonPrimitive("applied"), req.note, requestId)
                    commit(h, changeId, p.userId, now, kindOf(h, changeId), requestId, p)
                }
                "adopt" -> {
                    if (status != "pending_approval") state()
                    val proposerRole = h.createQuery("SELECT u.role FROM app.cfg_change c JOIN app.app_user u ON u.id = c.requested_by WHERE c.change_id = :id").bind("id", changeId).mapTo(String::class.java).one()
                    if (proposerRole != "TSO") throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "only a TSO proposal is adopted")
                    val orig = loadChange(h, changeId)!!
                    h.createUpdate("UPDATE app.cfg_change SET status = 'cancelled', decided_at = :at, decision_note = :n WHERE change_id = :id")
                        .bind("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("n", ("adopted: " + (req.note ?: "")).take(500)).bind("id", changeId).execute()
                    AuditWriter.write(h, p, "cfg_change", changeId.toString(), "adopt", JsonPrimitive(status), JsonPrimitive("cancelled"), req.note, requestId)
                    pendingAdopt = orig
                }
                "reject", "cancel" -> {
                    if (status != "pending_approval" && status != "scheduled") state()
                    val own = requestedBy == p.userId
                    if (req.decision == "reject" && (p.role != Role.SUPERADMIN || own)) {
                        throw if (own) ApiProblem(ProblemCode.ERR_CFG_SELF_APPROVAL, "the requester cancels instead of rejecting") else ApiProblem(ProblemCode.ERR_FORBIDDEN, "a change is rejected by a SUPERADMIN")
                    }
                    if (req.decision == "cancel" && !own && p.role != Role.SUPERADMIN) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "only the requester or a SUPERADMIN cancels a change")
                    val target = if (req.decision == "reject") "rejected" else "cancelled"
                    h.createUpdate("UPDATE app.cfg_change SET status = :s, decided_at = :at, decision_note = :n WHERE change_id = :id")
                        .bind("s", target).bind("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("n", req.note).bind("id", changeId).execute()
                    AuditWriter.write(h, p, "cfg_change", changeId.toString(), req.decision, JsonPrimitive(status), JsonPrimitive(target), req.note, requestId)
                }
            }
            loadChange(h, changeId)!!
        }.let { c ->
            val orig = pendingAdopt
            if (orig != null) {
                // The adopting editor becomes the requester of a fresh change set with the proposal's items; it takes the normal risk route.
                val items = orig.changes.map { ConfigChangeItemIn(it.key, it.scope_type, it.scope_id, it.value, it.effective_from, it.effective_to) }
                create(p, ConfigChangeRequestIn(("Adopted TSO proposal #$changeId: " + orig.reason).take(500), false, items), null, requestId)
            } else c
        }.also { if (risk3(it)) resolver.invalidate() }
    }

    private fun risk3(c: ConfigChangeDto) = c.risk_class >= 3

    // ---------------------------------------------------------------- scheduled application (worker tick)

    /** Applies every `scheduled` change whose `apply_at` has passed. Safe to call from any replica (row locks, SKIP LOCKED). Returns the count. */
    fun applyDue(): Int {
        val now = clock.now()
        var n = 0
        while (true) {
            val done = db.jdbi.inTransaction<Boolean, Exception> { h ->
                val row = h.createQuery("SELECT change_id, requested_by FROM app.cfg_change WHERE status = 'scheduled' AND apply_at <= :now ORDER BY apply_at, change_id LIMIT 1 FOR UPDATE SKIP LOCKED")
                    .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.findOne().orElse(null) ?: return@inTransaction false
                AuditWriter.write(h, null, "cfg_change", row.first.toString(), "schedule_apply", JsonPrimitive("scheduled"), JsonPrimitive("applied"), null, null, via = "job")
                commit(h, row.first, row.second, now, "schedule_apply", null, null)
                true
            }
            if (!done) break
            n++
        }
        return n
    }

    // ---------------------------------------------------------------- internals

    private class Prepared(val def: KeyDef, val item: ConfigChangeItemIn, val from: Instant?, val to: Instant?, val old: JsonElement = JsonNull) {
        fun withOld(o: JsonElement) = Prepared(def, item, from, to, o)
    }

    private fun parseTs(s: String?, pointer: String): Instant? = s?.let {
        runCatching { Instant.parse(it) }.getOrNull()?.takeIf { t -> t.isAfter(Instant.parse("2000-01-01T00:00:00Z")) && t.isBefore(Instant.parse("2100-01-01T00:00:00Z")) }
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad timestamp", errors = listOf(FieldError(pointer, "invalid_value")))
    }

    private fun prepare(it: ConfigChangeItemIn, i: Int, reg: Map<String, KeyDef>, now: Instant): Prepared {
        val ptr = "body.changes[$i]"
        val def = reg[it.key]?.takeIf { d -> !d.retired } ?: throw ApiProblem(ProblemCode.ERR_CFG_UNKNOWN_KEY, it.key, errors = listOf(FieldError("$ptr.key", "unknown_key")))
        if (it.scope_type !in def.scopeLevels) throw ApiProblem(ProblemCode.ERR_CFG_SCOPE_NOT_ALLOWED, "${it.key} cannot be set at ${it.scope_type}", errors = listOf(FieldError("$ptr.scope_type", "not_allowed")))
        if ((it.scope_type == "global") != (it.scope_id == 0L) || it.scope_id < 0) throw ApiProblem(ProblemCode.ERR_VALIDATION, "scope_id is 0 exactly for global", errors = listOf(FieldError("$ptr.scope_id", "invalid_value")))
        val from = parseTs(it.effective_from, "$ptr.effective_from")
        val to = parseTs(it.effective_to, "$ptr.effective_to")
        if (from != null && from.isBefore(now.minusSeconds(60))) throw ApiProblem(ProblemCode.ERR_VALIDATION, "effective_from is in the past", errors = listOf(FieldError("$ptr.effective_from", "in_past")))
        val start = from ?: now
        if (to != null && !to.isAfter(now)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "effective_to is already past", errors = listOf(FieldError("$ptr.effective_to", "in_past")))
        if (to != null && !to.isAfter(start)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "effective_to must follow effective_from", errors = listOf(FieldError("$ptr.effective_to", "invalid_range")))
        if (def.futureDatedOnly) {
            val startOfNextDhakaDay = now.atZone(dhaka).toLocalDate().plusDays(1).atStartOfDay(dhaka).toInstant()
            if (it.value !is JsonNull && (from == null || from.isBefore(startOfNextDhakaDay) || from.atZone(dhaka).toLocalTime() != LocalTime.MIDNIGHT))
                throw ApiProblem(ProblemCode.ERR_VALIDATION, "${def.key} is future-dated: effective_from must be a future Dhaka midnight", errors = listOf(FieldError("$ptr.effective_from", "future_midnight_required")))
        }
        if (!resolver.nodeExists(it.scope_type, it.scope_id)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "${it.scope_type} ${it.scope_id} does not exist", errors = listOf(FieldError("$ptr.scope_id", "unknown_node")))
        val v = it.value
        if (v !is JsonNull) {
            ConfigValidator.check(def, v, "$ptr.value") { k -> resolver.resolve(k, listOf(ScopeNode("global", 0)), now).value.let { e -> (e as? JsonPrimitive)?.doubleOrNull } }
        }
        return Prepared(def, it, from, to)
    }

    /** The value of the row valid now at exactly this key and scope (what the change replaces), else JSON null. */
    private fun oldValueAt(h: Handle, p: Prepared): JsonElement {
        val at = p.from ?: clock.now()
        return h.createQuery("SELECT value FROM app.cfg_value WHERE key = :k AND scope_type = :st AND scope_id = :si AND effective_from <= :at AND (effective_to IS NULL OR effective_to > :at)")
            .bind("k", p.def.key).bind("st", p.item.scope_type).bind("si", p.item.scope_id).bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .mapTo(String::class.java).findOne().map { Json.parseToJsonElement(it) }.orElse(JsonNull)
    }

    private fun itemsJson(items: List<Prepared>): String = Json.encodeToString(items.map {
        ConfigChangeItemOut(it.def.key, it.item.scope_type, it.item.scope_id, it.item.value ?: JsonNull, it.from?.wire(), it.to?.wire(), it.old)
    })

    /** Zones, routes, outlets and devices a change would touch (docs/24 s9; the counts equal the devices targeted at commit). */
    private fun blastRadius(h: Handle, items: List<Prepared>): BlastRadius {
        var zones = 0; var routes = 0; var outlets = 0; var devices = 0
        for (it in items) {
            val b = blast(h, it.item.scope_type, it.item.scope_id)
            zones = maxOf(zones, b.zones); routes = maxOf(routes, b.routes); outlets = maxOf(outlets, b.outlets); devices = maxOf(devices, b.devices)
        }
        return BlastRadius(zones, routes, outlets, devices)
    }

    internal fun blast(h: Handle, type: String, id: Long): BlastRadius {
        val zoneFilter = zoneFilterSql(type)
        val z = "SELECT z.id FROM app.zone z WHERE $zoneFilter"
        fun count(sql: String) = h.createQuery(sql).also { if (type != "global") it.bind("id", id) }.mapTo(Int::class.java).one()
        val devicesSql = when (type) {
            "user" -> "SELECT count(*) FROM app.device_binding b WHERE b.user_id = :id AND b.unbound_at IS NULL"
            "device" -> "SELECT 1"
            "role" -> "SELECT count(DISTINCT b.device_id) FROM app.device_binding b JOIN app.app_user u ON u.id = b.user_id JOIN app.role_def r ON r.role = u.role WHERE b.unbound_at IS NULL AND r.ordinal = :id"
            else -> "SELECT count(DISTINCT b.device_id) FROM app.device_binding b JOIN app.app_user u ON u.id = b.user_id WHERE b.unbound_at IS NULL AND (u.home_zone_id IN ($z) OR EXISTS " +
                "(SELECT 1 FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.user_id = u.id AND a.ended_at IS NULL AND (a.valid_to IS NULL OR a.valid_to > current_date) AND r.zone_id IN ($z)))"
        }
        return when (type) {
            "role" -> BlastRadius(0, 0, 0, count(devicesSql))
            "user" -> BlastRadius(0, 0, 0, count(devicesSql))
            "device" -> BlastRadius(0, 0, 0, 1)
            else -> BlastRadius(
                zones = count("SELECT count(*) FROM ($z) x"), routes = count("SELECT count(*) FROM app.route r WHERE r.zone_id IN ($z)"),
                outlets = count("SELECT count(*) FROM app.outlet o WHERE o.status = 'active' AND o.zone_id IN ($z)"), devices = count(devicesSql),
            )
        }
    }

    /** `revert` / `rollback` for a change made by a rollback request (reason prefix), else `change`. */
    private fun kindOf(h: Handle, changeId: Long): String = h.createQuery("SELECT is_revert_of, reason FROM app.cfg_change WHERE change_id = :id").bind("id", changeId)
        .map { rs, _ -> if (rs.getObject(1) == null) "change" else if (rs.getString(2).startsWith("Rollback to v")) "rollback" else "revert" }.one()

    /** True when the item moves the value the node resolves to in the key's restrictive direction (break-glass: restore or restrict only). */
    private fun isRestrictive(pr: Prepared, now: Instant): Boolean {
        if (pr.item.value is JsonNull) return false
        val chain = resolver.chainOf(pr.item.scope_type, pr.item.scope_id)
        val cur = resolver.resolve(pr.def.key, chain, now).value
        val nv = pr.item.value
        val c = (cur as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull; val n = (nv as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        return when (pr.def.restrictiveDir) {
            "up" -> c != null && n != null && n >= c
            "down" -> c != null && n != null && n <= c
            "enum_order" -> {
                val order = (pr.def.bounds["enum"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
                val ci = order.indexOf((cur as? JsonPrimitive)?.content); val ni = order.indexOf((nv as? JsonPrimitive)?.content)
                ci >= 0 && ni >= ci
            }
            else -> false
        }
    }

    /** SQL predicate on `app.zone z` for the zones a scope node covers (binds :id except for global). */
    internal fun zoneFilterSql(type: String): String = when (type) {
        "global" -> "TRUE"
        "wing" -> "z.territory_id IN (SELECT t.id FROM app.territory t JOIN app.division d ON d.id = t.division_id WHERE d.wing_id = :id)"
        "division" -> "z.territory_id IN (SELECT t.id FROM app.territory t WHERE t.division_id = :id)"
        "territory" -> "z.territory_id = :id"
        "zone" -> "z.id = :id"
        "route" -> "z.id = (SELECT zone_id FROM app.route WHERE id = :id)"
        "outlet" -> "z.id = (SELECT zone_id FROM app.outlet WHERE id = :id)"
        "geo_class" -> "z.id IN (SELECT o.zone_id FROM app.outlet o JOIN app.geo_class_def g ON g.geo_class = o.geo_class WHERE g.ordinal = :id)"
        else -> "FALSE"
    }

    /** Applies the change [changeId] under a new version; requires the change row to be locked or just inserted by this transaction. */
    private fun commit(h: Handle, changeId: Long, committedBy: Long, now: Instant, kind: String, requestId: String?, actor: AronPrincipal?): Boolean {
        h.execute("SELECT pg_advisory_xact_lock(7242001)")
        val row = h.createQuery("SELECT items, reason, risk_class, is_revert_of FROM app.cfg_change WHERE change_id = :id").bind("id", changeId)
            .map { rs, _ -> arrayOf(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getObject(4)) }.one()
        val items = Json.decodeFromString<List<ConfigChangeItemOut>>(row[0] as String)
        val reason = row[1] as String
        val risk = row[2] as Int
        val revertOf = (row[3] as? Number)?.toLong()
        // A change that waited too long is expired, never applied late: its window already ended, or a future-dated key's midnight has passed.
        val reg = resolver.registry()
        val stale = items.any { i ->
            (i.effective_to?.let { t -> !Instant.parse(t).isAfter(now) } == true) ||
                (reg[i.key]?.futureDatedOnly == true && i.value !is JsonNull && i.effective_from?.let { f -> Instant.parse(f).isBefore(now) } == true)
        }
        if (stale) {
            h.createUpdate("UPDATE app.cfg_change SET status = 'expired', decided_at = COALESCE(decided_at, :at) WHERE change_id = :id").bind("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("id", changeId).execute()
            AuditWriter.write(h, actor, "cfg_change", changeId.toString(), "expire", null, JsonPrimitive("expired"), "effective window passed before the change could apply", requestId, via = if (actor == null) "job" else "web")
            return false
        }
        val version = h.createQuery("SELECT COALESCE(max(config_version), 0) + 1 FROM app.cfg_version").mapTo(Long::class.java).one()
        val summary = (items.take(3).joinToString("; ") { "${it.key}@${it.scope_type}${if (it.scope_type == "global") "" else ":" + it.scope_id}" } + if (items.size > 3) " (+${items.size - 3})" else "").take(500)
        h.createUpdate("INSERT INTO app.cfg_version (config_version, kind, change_id, committed_at, committed_by, summary, max_risk_class, is_revert_of) VALUES (:v, :k, :c, :at, :by, :s, :r, :rv)")
            .bind("v", version).bind("k", kind).bind("c", changeId).bind("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("by", committedBy)
            .bind("s", summary).bind("r", risk).bind("rv", revertOf).execute()
        val applied = items.map { it.copy(old_value = applyItem(h, it, version, changeId, committedBy, now, reason)) }
        h.createUpdate("UPDATE app.cfg_change SET status = 'applied', config_version = :v, items = CAST(:items AS jsonb), decided_at = COALESCE(decided_at, :at) WHERE change_id = :id")
            .bind("v", version).bind("items", Json.encodeToString(applied)).bind("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("id", changeId).execute()
        AuditWriter.write(h, actor, "cfg_version", version.toString(), "commit", null, Json.parseToJsonElement(Json.encodeToString(applied)), reason, requestId, via = if (actor == null) "job" else "web")
        h.createUpdate("SELECT pg_notify('cfg_changed', :v)").bind("v", version.toString()).execute()
        return true
    }

    /** Closes what the item supersedes, inserts its row, and returns the value it replaced (JSON null when none). */
    private fun applyItem(h: Handle, it: ConfigChangeItemOut, version: Long, changeId: Long, by: Long, now: Instant, reason: String): JsonElement {
        var from = it.effective_from?.let { s -> Instant.parse(s) }?.let { f -> if (f.isBefore(now)) now else f } ?: now
        var to = it.effective_to?.let { s -> Instant.parse(s) }
        data class Row(val id: Long, val from: Instant, val to: Instant?, val value: String)
        fun overlapping(f: Instant): List<Row> = h.createQuery(
            "SELECT id, effective_from, effective_to, value FROM app.cfg_value WHERE key = :k AND scope_type = :st AND scope_id = :si " +
                "AND (effective_to IS NULL OR effective_to > :from) AND (CAST(:to AS timestamptz) IS NULL OR effective_from < CAST(:to AS timestamptz)) ORDER BY effective_from FOR UPDATE",
        ).bind("k", it.key).bind("st", it.scope_type).bind("si", it.scope_id).bind("from", OffsetDateTime.ofInstant(f, ZoneOffset.UTC)).bind("to", to?.let { t -> OffsetDateTime.ofInstant(t, ZoneOffset.UTC) })
            .map { rs, _ -> Row(rs.getLong(1), rs.getObject(2, OffsetDateTime::class.java).toInstant(), rs.getObject(3, OffsetDateTime::class.java)?.toInstant(), rs.getString(4)) }.list()
        var rows = overlapping(from)
        // Two changes at one scope inside the same millisecond: the later one starts 1 ms after the earlier (a row cannot close at its own start).
        while (rows.any { r -> r.from == from }) { from = from.plusMillis(1); rows = overlapping(from) }
        val tsFrom = OffsetDateTime.ofInstant(from, ZoneOffset.UTC)
        var old: JsonElement = JsonNull
        for (r in rows) {
            if (!r.from.isBefore(from)) {
                // A later scheduled row exists: the new row fills the gap up to it; a row starting at the same instant is a conflict.
                if (r.from == from) throw ApiProblem(ProblemCode.ERR_CONFLICT, "${it.key} already has a value starting at that instant at this scope")
                to = if (to == null || r.from.isBefore(to)) r.from else to
            } else {
                old = Json.parseToJsonElement(r.value)
                h.createUpdate("UPDATE app.cfg_value SET effective_to = :to, superseded_in_version = :v WHERE id = :id").bind("to", tsFrom).bind("v", version).bind("id", r.id).execute()
            }
        }
        if (it.value !is JsonNull) {
            h.createUpdate(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, change_id, created_by, reason) " +
                    "VALUES (:k, :st, :si, CAST(:v AS jsonb), :from, :to, :ver, :cid, :by, :reason)",
            ).bind("k", it.key).bind("st", it.scope_type).bind("si", it.scope_id).bind("v", it.value.toString()).bind("from", tsFrom)
                .bind("to", to?.let { t -> OffsetDateTime.ofInstant(t, ZoneOffset.UTC) }).bind("ver", version).bind("cid", changeId).bind("by", by).bind("reason", reason.take(500)).execute()
        }
        return old
    }

    private fun cfgInt(h: Handle, key: String, now: Instant): Int = h.createQuery(
        "SELECT COALESCE((SELECT value FROM app.cfg_value WHERE key = :k AND scope_type = 'global' AND effective_from <= :now AND (effective_to IS NULL OR effective_to > :now)), (SELECT default_value FROM app.cfg_key WHERE key = :k))::text",
    ).bind("k", key).bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).mapTo(String::class.java).one().toDouble().toInt()

    private val defaultFreeze = listOf(420 to 570, 990 to 1170)

    /** Freeze windows in Dhaka minutes; a window with from > to crosses midnight. A malformed stored value falls back to the defaults (the writer refuses one). */
    private fun inFreezeWindow(h: Handle, now: Instant): Boolean {
        val raw = h.createQuery(
            "SELECT COALESCE((SELECT value FROM app.cfg_value WHERE key = 'cfg.sys.change_freeze_windows' AND scope_type = 'global' AND effective_from <= :now AND (effective_to IS NULL OR effective_to > :now)), (SELECT default_value FROM app.cfg_key WHERE key = 'cfg.sys.change_freeze_windows'))::text",
        ).bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).mapTo(String::class.java).one()
        val t = now.atZone(dhaka).toLocalTime().let { it.hour * 60 + it.minute }
        fun mins(s: String) = s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()
        val windows = runCatching { (Json.parseToJsonElement(raw) as JsonArray).map { w -> val o = w as JsonObject; mins(o["from"]!!.jsonPrimitive.content) to mins(o["to"]!!.jsonPrimitive.content) } }.getOrDefault(defaultFreeze)
        return windows.any { (f, e) -> if (f < e) t in f until e else (t >= f || t < e) }
    }

    // ---------------------------------------------------------------- mapping

    private fun loadChange(h: Handle, id: Long): ConfigChangeDto? = h.createQuery("SELECT * FROM app.cfg_change WHERE change_id = :id").bind("id", id).map { rs, _ -> changeDto(rs) }.findOne().orElse(null)

    private fun changeDto(rs: java.sql.ResultSet) = ConfigChangeDto(
        change_id = rs.getLong("change_id"), status = rs.getString("status"), risk_class = rs.getInt("risk_class"),
        changes = Json.decodeFromString(rs.getString("items")), reason = rs.getString("reason"), requested_by = rs.getLong("requested_by"),
        requested_at = rs.getObject("requested_at", OffsetDateTime::class.java).toInstant().wire(), approved_by = rs.getObject("approver") as Long?,
        approved_at = rs.getObject("approved_at", OffsetDateTime::class.java)?.toInstant()?.wire(), apply_at = rs.getObject("apply_at", OffsetDateTime::class.java)?.toInstant()?.wire(),
        config_version = rs.getObject("config_version") as Long?, is_revert_of = rs.getObject("is_revert_of") as Long?,
        blast_radius = Json { ignoreUnknownKeys = true }.decodeFromString(rs.getString("blast_radius")),
    )

    private fun versionDto(rs: java.sql.ResultSet) = ConfigVersionDto(
        version = rs.getLong("config_version"), kind = rs.getString("kind"), committed_at = rs.getObject("committed_at", OffsetDateTime::class.java).toInstant().wire(),
        committed_by = rs.getLong("committed_by"), summary = rs.getString("summary"), max_risk_class = rs.getInt("max_risk_class"),
        is_revert_of = rs.getObject("is_revert_of") as Long?, change_id = rs.getObject("change_id") as Long?,
    )

    private fun valueDto(rs: java.sql.ResultSet) = ConfigValueDto(
        id = rs.getLong("id"), key = rs.getString("key"), scope_type = rs.getString("scope_type"), scope_id = rs.getLong("scope_id"),
        value = Json.parseToJsonElement(rs.getString("value")), effective_from = rs.getObject("effective_from", OffsetDateTime::class.java).toInstant().wire(),
        effective_to = rs.getObject("effective_to", OffsetDateTime::class.java)?.toInstant()?.wire(), config_version = rs.getLong("config_version"),
        superseded_in_version = rs.getObject("superseded_in_version") as Long?, set_by = (rs.getObject("created_by") as Long?) ?: 0L,
        set_at = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(), reason = rs.getString("reason"),
    )
}

fun KeyDef.toDto() = ConfigKeyDto(key, area, kind, valueType, default, bounds, scopeLevels, riskClass, effect, delivery, requiresAck, futureDatedOnly, restrictiveDir, editorPermission, descriptionEn, descriptionBn)

fun Resolved.toDto() = ResolvedConfigValue(
    key.key, value, scopeType, scopeId, effectiveFrom?.wire(), effectiveTo?.wire(), configVersion, key.requiresAck, key.bounds,
)
