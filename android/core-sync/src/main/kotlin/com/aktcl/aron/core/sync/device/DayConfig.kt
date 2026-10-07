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
     * Contract `CalendarSection` plus the delta's `calendar_changes`, decided as the server's `app.is_working_day`: a dated
     * entry that is a selling day wins (a make-up day beats a weekend and an off-day), any other dated entry makes the day
     * off, otherwise the weekend days are off. Null when the phone has no calendar yet (the DPC then treats the day as
     * working: the rep checked in).
     */
    fun isWorkingDay(businessDate: String): Boolean? = calendar?.isWorkingDay(businessDate)

    internal class Calendar(private val weekendDays: Set<Int>, private val sellingByDate: Map<String, List<Boolean>>) {
        fun isWorkingDay(date: String): Boolean? {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
            val dated = sellingByDate[date].orEmpty()
            if (dated.any { it }) return true
            if (dated.isNotEmpty()) return false
            return day.dayOfWeek.value !in weekendDays
        }

        companion object {
            fun parse(text: String, changes: String? = null): Calendar {
                val o = Json.parseToJsonElement(text).jsonObject
                val weekend = o["weekend_days"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet() ?: setOf(5)
                val changed = changes?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }.orEmpty()
                // Keyed by holiday id: a later change of the same holiday replaces the earlier version.
                val byId = LinkedHashMap<String, Pair<String, Boolean>>()
                (o["entries"]?.jsonArray.orEmpty() + changed).forEachIndexed { i, e ->
                    val m = e as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
                    val date = m["date"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                    val id = m["id"]?.jsonPrimitive?.contentOrNull ?: "#$i"
                    byId[id] = date to (m["selling_day"]?.jsonPrimitive?.booleanOrNull ?: false)
                }
                return Calendar(weekend, byId.values.groupBy({ it.first }, { it.second }))
            }
        }
    }
}
