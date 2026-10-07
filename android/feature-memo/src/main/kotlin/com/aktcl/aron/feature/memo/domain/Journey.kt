package com.aktcl.aron.feature.memo.domain

enum class JourneyStatus { NotVisited, Visited, Sold }

data class PlannedOutlet(val outletId: Long, val name: String, val sequence: Int)
/** A visit's final outcome as stored (contract `VisitOutcome` wire name); `abandoned` does not count as visited. */
data class JourneyVisit(val outletId: Long, val outcome: String)
data class JourneyRow(val outlet: PlannedOutlet, val status: JourneyStatus)
data class Journey(val rows: List<JourneyRow>, val planned: Int, val visited: Int, val sold: Int, val notVisited: Int)

object JourneyBuilder {
    /** Route progress of today's planned outlets (F-SR-067); counts equal the KPI strip: visited = outlets with a non-abandoned visit. */
    fun build(planned: List<PlannedOutlet>, visits: List<JourneyVisit>): Journey {
        val counted = visits.filter { it.outcome != "abandoned" }
        val sold = counted.filter { it.outcome == "sold" }.map { it.outletId }.toSet()
        val visited = counted.map { it.outletId }.toSet()
        val rows = planned.sortedBy { it.sequence }.map {
            JourneyRow(it, when { it.outletId in sold -> JourneyStatus.Sold; it.outletId in visited -> JourneyStatus.Visited; else -> JourneyStatus.NotVisited })
        }
        val v = rows.count { it.status != JourneyStatus.NotVisited }
        return Journey(rows, rows.size, v, rows.count { it.status == JourneyStatus.Sold }, rows.size - v)
    }
}
