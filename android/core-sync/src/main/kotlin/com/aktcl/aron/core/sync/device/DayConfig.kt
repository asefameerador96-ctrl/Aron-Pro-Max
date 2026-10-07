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
        calendar = runCatching { ref.section("calendar")?.let(Calendar::parse) }.getOrNull()
    }

    /**
     * Contract `CalendarSection`: a dated entry decides (an emergency off-day wins over a make-up day), otherwise the weekend
     * days are off. Null when the phone has no calendar yet (the DPC then treats the day as working: the rep checked in).
     */
    fun isWorkingDay(businessDate: String): Boolean? = calendar?.isWorkingDay(businessDate)

    internal class Calendar(private val weekendDays: Set<Int>, private val entries: Map<String, List<Pair<String, Boolean>>>) {
        fun isWorkingDay(date: String): Boolean? {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
            val dated = entries[date].orEmpty()
            if (dated.any { it.first == "emergency_off" }) return false
            if (dated.isNotEmpty()) return dated.any { it.second }
            return day.dayOfWeek.value !in weekendDays
        }

        companion object {
            fun parse(text: String): Calendar {
                val o = Json.parseToJsonElement(text).jsonObject
                val weekend = o["weekend_days"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet() ?: setOf(5)
                val entries = o["entries"]?.jsonArray.orEmpty().mapNotNull { e ->
                    val m = e.jsonObject
                    val date = m["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val kind = m["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    date to (kind to (m["selling_day"]?.jsonPrimitive?.booleanOrNull ?: false))
                }.groupBy({ it.first }, { it.second })
                return Calendar(weekend, entries)
            }
        }
    }
}
