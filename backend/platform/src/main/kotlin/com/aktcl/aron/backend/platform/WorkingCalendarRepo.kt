package com.aktcl.aron.backend.platform

import com.aktcl.aron.rules.CalendarLevel
import com.aktcl.aron.rules.CalendarMark
import com.aktcl.aron.rules.DayKind
import com.aktcl.aron.rules.NodeCalendar
import com.aktcl.aron.rules.NodePath
import com.aktcl.aron.rules.WorkingDays
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jdbi.v3.core.Handle
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The working calendar of a month at the nodes of the geography, loaded once (D-TSO-MAN-08, CR-6). It holds the month's
 * live declarations of every level (`app.calendar_holiday`), the wing and division `cfg.calendar.weekend_days` rows valid
 * at the load instant, and the global weekend; [at] resolves them for one node path with the one count,
 * [com.aktcl.aron.rules.WorkingDays]. Built by [WorkingCalendarRepo.load]; holds no handle, so one load serves every node of
 * an answer (a territory and each of its zones).
 */
class WorkingCalendars internal constructor(
    val month: YearMonth,
    private val marks: List<CalendarMark>,
    private val divisionWeekend: Map<Long, Set<DayOfWeek>>,
    private val wingWeekend: Map<Long, Set<DayOfWeek>>,
    private val globalWeekend: Set<DayOfWeek>,
) {
    /** `cfg.calendar.weekend_days` resolved at the node: the division's row, else the wing's, else the global value. */
    fun weekendAt(path: NodePath): Set<DayOfWeek> =
        path.divisionId?.let { divisionWeekend[it] } ?: path.wingId?.let { wingWeekend[it] } ?: globalWeekend

    /** The node's resolved calendar: its weekend and the off and make-up dates of the declarations on its path. */
    fun at(path: NodePath): NodeCalendar = WorkingDays.resolve(path, weekendAt(path), marks)

    /** W: the working days of [month] at the node (D-TSO-MAN-08). */
    fun workingDays(path: NodePath): Int = WorkingDays.month(kotlinx.datetime.YearMonth(month.year, month.monthValue), at(path))
}

/**
 * Loads what the one working-day count needs (D-TSO-MAN-08): the declarations of the month on every level, the weekend
 * resolved at a node (`cfg.calendar.weekend_days`: division row, else wing row, else global), and the path of a node. The
 * counting itself is [WorkingDays] (shared/rules), so the national dashboard, the admin calendar, Target Status and Daily
 * Tracking count the same days. `dw.v_calendar` stays global with the Friday weekend (V0140 comment); WorkingDaysViewParityTest
 * proves the two agree for global scope.
 */
object WorkingCalendarRepo {
    const val WEEKEND_KEY: String = "cfg.calendar.weekend_days"

    /**
     * The calendars of [month]. The wing and division weekend rows are those valid at [at] (as ConfigResolver resolves a
     * scoped value); the global weekend comes from [config] when given (the server's global value, DbServerConfig in
     * production), else from the database (the global row valid at [at], else the registry default). A missing or malformed
     * value is the Friday default, a list with no valid day is no weekend ([WorkingDays.weekendOf]).
     */
    fun load(h: Handle, month: YearMonth, at: Instant, config: ServerConfig? = null): WorkingCalendars {
        val rows = h.createQuery(
            """
            SELECT scope_type, scope_id, value::text FROM app.cfg_value
             WHERE key = :k AND scope_type IN ('wing', 'division') AND effective_from <= :at AND (effective_to IS NULL OR effective_to > :at)
            """,
        ).bind("k", WEEKEND_KEY).bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .map { rs, _ -> Triple(rs.getString(1), rs.getLong(2), weekendOf(rs.getString(3)?.let(::parse))) }.list()
        fun level(type: String) = rows.filter { it.first == type && it.third != null }.associate { it.second to it.third!! }
        val global = if (config != null) weekendOf(runCatching { config.value(WEEKEND_KEY) }.getOrNull()) else weekendOf(globalFromDb(h, at))
        return WorkingCalendars(month, marks(h, month.atDay(1), month.atEndOfMonth()), level("division"), level("wing"), global ?: WorkingDays.DEFAULT_WEEKEND)
    }

    /** The live declarations dated in [from]..[to] on every level (a revoked one is not live); unknown levels or kinds are skipped. */
    fun marks(h: Handle, from: LocalDate, to: LocalDate): List<CalendarMark> = h.createQuery(
        "SELECT date, scope_type, scope_id, kind FROM app.calendar_holiday WHERE revoked_at IS NULL AND date BETWEEN :f AND :t",
    ).bind("f", from).bind("t", to).map { rs, _ ->
        val level = CalendarLevel.fromWire(rs.getString(2)); val kind = DayKind.fromWire(rs.getString(4))
        if (level == null || kind == null) null else CalendarMark(rs.getObject(1, LocalDate::class.java).toKotlinLocalDate(), level, rs.getLong(3), kind)
    }.list().filterNotNull()

    /** The path of every zone in [zoneIds] (wing, division, territory, zone from the master tables); unknown ids are left out. */
    fun zonePaths(h: Handle, zoneIds: Collection<Long>): Map<Long, NodePath> {
        if (zoneIds.isEmpty()) return emptyMap()
        return h.createQuery(
            """
            SELECT z.id, t.id, d.id, d.wing_id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id
             WHERE z.id = ANY(:z)
            """,
        ).bindArray("z", Long::class.javaObjectType, zoneIds.distinct()).map { rs, _ -> rs.getLong(1) to NodePath(rs.getLong(4), rs.getLong(3), rs.getLong(2), rs.getLong(1)) }
            .list().toMap()
    }

    /**
     * The path of a geo node at its own level (D-TSO-MAN-08 "W of the node's own path"): a territory node has wing, division
     * and territory, so a declaration of one of its zones does not count for it. `national` is the global path; a house,
     * which is no calendar level, takes its territory's path. Null for an unknown node or level.
     */
    fun pathOf(h: Handle, level: String, id: Long): NodePath? = when (level) {
        "national" -> NodePath.GLOBAL
        "zone" -> zonePaths(h, listOf(id))[id]
        "territory" -> h.createQuery("SELECT t.id, d.id, d.wing_id FROM app.territory t JOIN app.division d ON d.id = t.division_id WHERE t.id = :id")
            .bind("id", id).map { rs, _ -> NodePath(rs.getLong(3), rs.getLong(2), rs.getLong(1)) }.findOne().orElse(null)
        "house" -> h.createQuery("SELECT t.id, d.id, d.wing_id FROM app.house hs JOIN app.territory t ON t.id = hs.territory_id JOIN app.division d ON d.id = t.division_id WHERE hs.id = :id")
            .bind("id", id).map { rs, _ -> NodePath(rs.getLong(3), rs.getLong(2), rs.getLong(1)) }.findOne().orElse(null)
        "division" -> h.createQuery("SELECT d.id, d.wing_id FROM app.division d WHERE d.id = :id")
            .bind("id", id).map { rs, _ -> NodePath(rs.getLong(2), rs.getLong(1)) }.findOne().orElse(null)
        "wing" -> h.createQuery("SELECT w.id FROM app.wing w WHERE w.id = :id").bind("id", id).mapTo(Long::class.java).findOne().orElse(null)?.let { NodePath(it) }
        else -> null
    }

    /**
     * The lowest common ancestor of [paths] (D-TSO-MAN-08: one zone gives the zone, one territory the territory, mixed or
     * none gives global): the levels the paths share from the wing down, null from the first level where they differ.
     */
    fun commonPath(paths: Collection<NodePath>): NodePath {
        if (paths.isEmpty()) return NodePath.GLOBAL
        fun same(f: (NodePath) -> Long?): Long? = paths.map(f).distinct().singleOrNull()
        val w = same { it.wingId } ?: return NodePath.GLOBAL
        val d = same { it.divisionId } ?: return NodePath(w)
        val t = same { it.territoryId } ?: return NodePath(w, d)
        val z = same { it.zoneId } ?: return NodePath(w, d, t)
        return NodePath(w, d, t, z)
    }

    private fun globalFromDb(h: Handle, at: Instant): JsonElement? = h.createQuery(
        """
        SELECT coalesce((SELECT v.value FROM app.cfg_value v WHERE v.key = k.key AND v.scope_type = 'global' AND v.effective_from <= :at
                            AND (v.effective_to IS NULL OR v.effective_to > :at) ORDER BY v.effective_from DESC LIMIT 1), k.default_value)::text
          FROM app.cfg_key k WHERE k.key = :k
        """,
    ).bind("k", WEEKEND_KEY).bind("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).mapTo(String::class.java).findOne().orElse(null)?.let(::parse)

    private fun parse(s: String): JsonElement? = runCatching { Json.parseToJsonElement(s) }.getOrNull()

    /** A weekend value: ISO day numbers (numbers or numeric strings); null when the value is missing or not a list. */
    private fun weekendOf(v: JsonElement?): Set<DayOfWeek>? =
        runCatching { v!!.jsonArray.mapNotNull { it.jsonPrimitive.content.toIntOrNull() } }.getOrNull()?.let { WorkingDays.weekendOf(it) }
}
