package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class BlastRadiusReport(val zones: Int, val routes: Int, val outlets: Int, val users: Int, val devices: Int)

@Serializable
data class RadiusWhatIf(val scope_type: String, val scope_id: Long?, val value: Int, val days: Int, val visits_evaluated: Int, val to_valid: Int, val to_invalid: Int, val unchanged: Int)

@Serializable
data class ConfigVersionDetail(val config_version: Long, val committed_at: String, val change_id: Long?, val values: List<ResolvedConfigValue>)

@Serializable
data class ZoneReach(val zone_id: Long, val targeted: Int, val applied: Int)

@Serializable
data class ConfigReach(
    val version: Long, val committed_at: String, val devices_targeted: Int, val devices_applied: Int, val devices_acked: Int, val devices_pending: Int,
    val p95_reach_min: Double?, val by_zone: List<ZoneReach>,
)

@Serializable
data class PendingDevice(val device_id: Long, val user_id: Long, val zone_id: Long, val applied_version: Long, val last_contact_at: String?, val lag_min: Int)

@Serializable
data class PendingDevicePage(val items: List<PendingDevice>, val next_cursor: String?)

/**
 * Read-only config tools (rows F-API-058, 059, 062 reads, 063): what-if on stored fixes, blast radius, the version
 * detail and the reach view. "Targeted" devices are active bound phones whose user's zone (home or assigned route)
 * is under the change's scope; "applied" means `device.config_version_applied >= version`; "acked" means a
 * `config_ack` row at or above the version (written by the sync handler).
 */
class ConfigTools(private val db: Database, private val service: ConfigService, private val resolver: ConfigResolver, private val clock: AronClock = AronClock.SYSTEM, private val nodeReach: NodeReach? = null) {
    /** Non-national callers (TSO, DMO, WM) see only scopes whose zones are all inside their reach; global is national only. */
    fun requireReach(p: com.aktcl.aron.backend.platform.AronPrincipal, type: String?, id: Long?) {
        val nr = nodeReach ?: return
        if (nr.national(p)) return
        val (t, i) = checkScope(type, id)
        if (t == "global") throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "national scope is outside your reach")
        val zones = db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT z.id FROM app.zone z WHERE ${service.zoneFilterSql(t)}").also { q -> q.bind("id", i) }.mapTo(Long::class.java).list() }
        if (zones.isEmpty() || zones.any { !nr.coversZone(p, it) }) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "scope outside your reach")
    }

    fun requireZone(p: com.aktcl.aron.backend.platform.AronPrincipal, zone: Long?) {
        val nr = nodeReach ?: return
        if (nr.national(p)) return
        if (zone == null || !nr.coversZone(p, zone)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "name a zone inside your reach")
    }


    private fun checkScope(type: String?, id: Long?): Pair<String, Long> {
        val t = type ?: "global"
        if (t !in Precedence.rank) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad scope_type")
        val i = id ?: 0L
        if ((t == "global") != (i == 0L) || !resolver.nodeExists(t, i)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "unknown scope node")
        return t to i
    }

    fun blastRadius(type: String?, id: Long?): BlastRadiusReport {
        val (t, i) = checkScope(type, id)
        return db.jdbi.withHandle<BlastRadiusReport, Exception> { h ->
            val b = service.blast(h, t, i)
            val users = when (t) {
                "user" -> 1
                "device" -> h.createQuery("SELECT count(DISTINCT b.user_id) FROM app.device_binding b WHERE b.device_id = :id AND b.status = 'active'").bind("id", i).mapTo(Int::class.java).one()
                "role" -> h.createQuery("SELECT count(*) FROM app.app_user u JOIN app.role_def r ON r.role = u.role WHERE r.ordinal = :id AND u.status = 'active'").bind("id", i).mapTo(Int::class.java).one()
                else -> h.createQuery(
                    "SELECT count(*) FROM app.app_user u WHERE u.status = 'active' AND u.role IN ('SR','AMO','TSO') AND (u.home_zone_id IN (SELECT z.id FROM app.zone z WHERE ${service.zoneFilterSql(t)}) OR EXISTS " +
                        "(SELECT 1 FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.user_id = u.id AND a.ended_at IS NULL AND (a.valid_to IS NULL OR a.valid_to > current_date) AND r.zone_id IN (SELECT z.id FROM app.zone z WHERE ${service.zoneFilterSql(t)})))",
                ).also { q -> if (t != "global") q.bind("id", i) }.mapTo(Int::class.java).one()
            }
            BlastRadiusReport(b.zones, b.routes, b.outlets, users, b.devices)
        }
    }

    /** Re-evaluates stored visit fixes under a candidate radius (`distance <= value`), against the verdict each visit got. */
    fun whatIf(type: String?, id: Long?, value: Int, days: Int): RadiusWhatIf {
        if (value !in 10..5000 || days !in 1..90) throw ApiProblem(ProblemCode.ERR_VALIDATION, "value 10..5000 and days 1..90")
        val (t, i) = checkScope(type, id)
        val since = java.time.LocalDate.ofInstant(clock.now(), java.time.ZoneId.of("Asia/Dhaka")).minusDays((days - 1).toLong())
        val counts = db.jdbi.withHandle<IntArray, Exception> { h ->
            val scopeSql = when (t) {
                "outlet" -> "o.id = :id"
                "route" -> "o.route_id = :id"
                else -> "o.zone_id IN (SELECT z.id FROM app.zone z WHERE ${service.zoneFilterSql(t)})"
            }
            val q = h.createQuery(
                "SELECT count(*) FILTER (WHERE verdict = 'out_of_range' AND d <= :val) AS to_valid, count(*) FILTER (WHERE verdict = 'in_range' AND d > :val) AS to_invalid, count(*) AS n " +
                    "FROM (SELECT v.verdict AS verdict, COALESCE(v.server_distance_m, v.distance_m) AS d FROM app.visit v JOIN app.outlet o ON o.id = v.outlet_id " +
                    "WHERE v.business_date >= :since AND v.voided_at IS NULL AND v.verdict IN ('in_range','out_of_range') AND COALESCE(v.server_distance_m, v.distance_m) IS NOT NULL AND $scopeSql) x",
            ).bind("val", value.toDouble()).bind("since", since)
            if (t != "global") q.bind("id", i)
            q.map { rs, _ -> intArrayOf(rs.getInt("to_valid"), rs.getInt("to_invalid"), rs.getInt("n")) }.one()
        }
        return RadiusWhatIf(t, if (t == "global") null else i, value, days, counts[2], counts[0], counts[1], counts[2] - counts[0] - counts[1])
    }

    fun versionDetail(version: Long): ConfigVersionDetail {
        val row = db.jdbi.withHandle<Pair<Instant, Long?>?, Exception> { h ->
            h.createQuery("SELECT committed_at, change_id FROM app.cfg_version WHERE config_version = :v").bind("v", version)
                .map { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() to (rs.getObject(2) as Long?) }.findOne().orElse(null)
        } ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "version $version")
        val reg = resolver.registry()
        val values = db.jdbi.withHandle<List<ResolvedConfigValue>, Exception> { h ->
            h.createQuery("SELECT * FROM app.cfg_value WHERE config_version = :v ORDER BY key, scope_type, scope_id").bind("v", version).map { rs, _ ->
                reg[rs.getString("key")]?.let { d ->
                    Resolved(d, kotlinx.serialization.json.Json.parseToJsonElement(rs.getString("value")), rs.getString("scope_type"), rs.getLong("scope_id"),
                        rs.getObject("effective_from", OffsetDateTime::class.java).toInstant(), rs.getObject("effective_to", OffsetDateTime::class.java)?.toInstant(), version).toDto()
                }
            }.list().filterNotNull()
        }
        return ConfigVersionDetail(version, row.first.wire(), row.second, values)
    }

    private val deviceZone = "COALESCE(d.zone_id, u.home_zone_id, (SELECT r.zone_id FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.user_id = u.id AND a.ended_at IS NULL AND (a.valid_to IS NULL OR a.valid_to > current_date) ORDER BY a.valid_from DESC LIMIT 1))"

    private fun targetedSql(zoneSel: Boolean) = "FROM app.device d JOIN app.device_binding b ON b.device_id = d.id AND b.status = 'active' JOIN app.app_user u ON u.id = b.user_id " +
        "WHERE d.status IN ('enrolled','active') ${if (zoneSel) "AND $deviceZone = :zone" else ""}"

    fun reach(version: Long, zone: Long?): ConfigReach {
        val committed = db.jdbi.withHandle<Instant?, Exception> { h -> h.createQuery("SELECT committed_at FROM app.cfg_version WHERE config_version = :v").bind("v", version).map { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() }.findOne().orElse(null) }
            ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "version $version")
        return db.jdbi.withHandle<ConfigReach, Exception> { h ->
            val rows = h.createQuery("SELECT d.id, $deviceZone AS zone_id, COALESCE(d.config_version_applied, 0) AS applied, EXISTS (SELECT 1 FROM app.cfg_ack a WHERE a.device_id = d.id AND a.acked_config_version >= :v AND a.voided_at IS NULL) AS acked ${targetedSql(zone != null)}")
                .bind("v", version).also { q -> if (zone != null) q.bind("zone", zone) }.map { rs, _ -> Triple(rs.getObject("zone_id") as Long?, rs.getLong("applied") >= version, rs.getBoolean("acked")) }.list()
            val byZone = rows.filter { it.first != null }.groupBy { it.first!! }.map { (z, r) -> ZoneReach(z, r.size, r.count { it.second }) }.sortedBy { it.zone_id }
            ConfigReach(version, committed.wire(), rows.size, rows.count { it.second }, rows.count { it.third }, rows.count { !it.second }, null, byZone)
        }
    }

    fun pending(version: Long, zone: Long?, limit: Int, cursor: Long?): PendingDevicePage {
        db.jdbi.withHandle<Boolean, Exception> { h -> h.createQuery("SELECT true FROM app.cfg_version WHERE config_version = :v").bind("v", version).mapTo(Boolean::class.java).findOne().isPresent }.also { if (!it) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "version $version") }
        val now = clock.now()
        val rows = db.jdbi.withHandle<List<PendingDevice>, Exception> { h ->
            val q = h.createQuery(
                "SELECT d.id, u.id AS uid, $deviceZone AS zone_id, COALESCE(d.config_version_applied, 0) AS applied, d.last_contact_at, (SELECT committed_at FROM app.cfg_version WHERE config_version = :v) AS committed ${targetedSql(zone != null)} " +
                    "AND COALESCE(d.config_version_applied, 0) < :v ${if (cursor != null) "AND d.id > :c" else ""} ORDER BY d.id LIMIT :lim",
            ).bind("v", version).bind("lim", limit + 1)
            if (zone != null) q.bind("zone", zone)
            if (cursor != null) q.bind("c", cursor)
            q.map { rs, _ ->
                val committed = rs.getObject("committed", OffsetDateTime::class.java).toInstant()
                PendingDevice(rs.getLong("id"), rs.getLong("uid"), (rs.getObject("zone_id") as Long?) ?: 0L, rs.getLong("applied"), rs.getObject("last_contact_at", OffsetDateTime::class.java)?.toInstant()?.wire(),
                    maxOf(0L, java.time.Duration.between(committed, now).toMinutes()).toInt())
            }.list()
        }
        return PendingDevicePage(rows.take(limit), if (rows.size > limit) rows[limit - 1].device_id.toString() else null)
    }
}
