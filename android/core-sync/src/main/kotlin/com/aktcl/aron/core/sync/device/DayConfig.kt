package com.aktcl.aron.core.sync.device

import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.geo.FixPriority
import com.aktcl.aron.core.geo.FixSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * Settings that synchronous device code reads without I/O (the fix manager, the DPC's blocking calendar), taken from the
 * active user's bundle. [refresh] reads Room; call it after login, a bundle apply and a config delta. Until the first
 * refresh the contract defaults apply (FixSettings(), calendar unknown).
 */
class DayConfig {
    @Volatile var fixSettings: FixSettings = FixSettings()
        private set

    @Volatile private var calendar: Calendar? = null

    suspend fun refresh(db: AronDatabase, nowIso: String) {
        val ref = ReferenceRepository(db)
        suspend fun value(key: String) = runCatching { ref.config(key, nowIso)?.let { Json.parseToJsonElement(it) as? JsonPrimitive } }.getOrNull()
        val d = FixSettings()
        fixSettings = FixSettings(
            timeoutS = value("cfg.geo.fix_timeout_s")?.intOrNull ?: d.timeoutS,
            priority = value("cfg.geo.fix_accuracy_mode")?.let { FixPriority.ofConfig(it.contentOrNull) } ?: d.priority,
            reuseMaxAgeS = value("cfg.geo.fix_reuse_max_age_s")?.intOrNull ?: d.reuseMaxAgeS,
            requirePrecise = value("cfg.geo.require_precise")?.booleanOrNull ?: d.requirePrecise,
        )
        // Emergency off-days arrive only in a config delta (calendar_changes, D-542), so both sections count.
        calendar = runCatching { ref.section("calendar")?.let { Calendar.parse(it, ref.section("calendar_changes")) } }.getOrNull()
    }

    /**
     * Contract `CalendarSection` plus the delta's `calendar_changes`, decided as the server's `DayPlan.isSellingDay`
     * (backend/platform): among the dated entries the most specific scope decides (zone over territory over division over
     * wing over global), and at equal scope an off-day wins over a make-up day; with no dated entry the weekend days are off.
     * The bundle carries only the entries of the user's own zones. Null when the phone has no calendar yet (the DPC then
     * treats the day as working: the rep checked in).
     */
    fun isWorkingDay(businessDate: String): Boolean? = calendar?.isWorkingDay(businessDate)

    internal data class Entry(val date: String, val specificity: Int, val sellingDay: Boolean)

    internal class Calendar(private val weekendDays: Set<Int>, private val byDate: Map<String, List<Entry>>) {
        fun isWorkingDay(date: String): Boolean? {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
            val dated = byDate[date].orEmpty()
            if (dated.isNotEmpty()) {
                val top = dated.maxOf { it.specificity }
                return dated.filter { it.specificity == top }.all { it.sellingDay }
            }
            return day.dayOfWeek.value !in weekendDays
        }

        companion object {
            private val SPECIFICITY = mapOf("global" to 0, "wing" to 1, "division" to 2, "territory" to 3, "zone" to 4)

            fun parse(text: String, changes: String? = null): Calendar {
                val o = Json.parseToJsonElement(text).jsonObject
                val weekend = o["weekend_days"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet() ?: setOf(5)
                val changed = changes?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }.orEmpty()
                // Keyed by holiday id: a later change of the same holiday replaces the earlier version.
                val byId = LinkedHashMap<String, Entry>()
                (o["entries"]?.jsonArray.orEmpty() + changed).forEachIndexed { i, e ->
                    val m = e as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
                    val date = m["date"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                    val id = m["id"]?.jsonPrimitive?.contentOrNull ?: "#$i"
                    val scope = SPECIFICITY[m["scope_type"]?.jsonPrimitive?.contentOrNull] ?: 0
                    byId[id] = Entry(date, scope, m["selling_day"]?.jsonPrimitive?.booleanOrNull ?: false)
                }
                return Calendar(weekend, byId.values.groupBy { it.date })
            }
        }
    }
}
