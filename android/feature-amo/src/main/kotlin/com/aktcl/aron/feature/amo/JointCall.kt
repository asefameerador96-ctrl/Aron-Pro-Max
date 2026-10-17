package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class RubricCriterion(val id: Long, val labelEn: String, val labelBn: String?, val answerType: String, val enabled: Boolean)
data class JointRubric(val rubricId: Long, val version: Int, val criteria: List<RubricCriterion>) {
    /** Criteria the AMO scores: enabled `score_1_5` ones. Disabled placeholders (unknown rubric items 4 and 5, G-man-051) are shown but never scored or sent. */
    val scored: List<RubricCriterion> get() = criteria.filter { it.enabled && it.answerType == "score_1_5" }
}

/** The assigned SR of a route, read-only on the Joint Call (F-AMO-011). */
data class AssignedSr(val userId: Long, val name: String)

/** Joint Call assessment (F-AMO-011): 1 to 5 stars per scored criterion, star 1 preselected (assumption, docs/25 row note). */
data class JointState(val stars: Map<Long, Int> = emptyMap()) {
    fun star(id: Long, value: Int): JointState = copy(stars = stars + (id to value.coerceIn(1, 5)))
    fun of(id: Long): Int = stars[id] ?: 1
}

object JointCall {
    fun rubric(rubricsJson: String?): JointRubric? {
        val arr = runCatching { Json.parseToJsonElement(rubricsJson ?: return null) }.getOrNull()
        val list = (arr as? JsonArray) ?: ((arr as? JsonObject)?.get("items") as? JsonArray) ?: return null
        val r = list.firstNotNullOfOrNull { e ->
            val o = e as? JsonObject ?: return@firstNotNullOfOrNull null
            if ((o["kind"] as? JsonPrimitive)?.contentOrNull != "joint_call") return@firstNotNullOfOrNull null
            o
        } ?: return null
        val id = (r["rubric_id"] as? JsonPrimitive)?.longOrNull ?: return null
        val version = (r["version"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return null
        val criteria = (r["criteria"] as? JsonArray).orEmpty().mapNotNull { c ->
            val o = c as? JsonObject ?: return@mapNotNull null
            RubricCriterion(
                (o["criterion_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                (o["label_en"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                (o["label_bn"] as? JsonPrimitive)?.contentOrNull,
                (o["answer_type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
        return JointRubric(id, version, criteria)
    }

    /** The SR (role SR) of the supervisor team whose routes include [routeId]; the first by user id when several cover it; null when none. */
    fun assignedSr(supervisorJson: String?, routeId: Long): AssignedSr? {
        val team = (runCatching { Json.parseToJsonElement(supervisorJson ?: return null) as? JsonObject }.getOrNull()?.get("team") as? JsonArray) ?: return null
        return team.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if ((o["role"] as? JsonPrimitive)?.contentOrNull != "SR") return@mapNotNull null
            val routes = (o["route_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            if (routeId !in routes) return@mapNotNull null
            AssignedSr((o["user_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null, (o["full_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null)
        }.minByOrNull { it.userId }
    }

    /** The records of one Save: a `call_assessment` (family = the visit, rank 1) and one answer per scored criterion (rank 2). */
    fun records(visitUuid: String, rubric: JointRubric, state: JointState, assessedUserId: Long?, uuid: () -> String): List<PerfAssessment.Planned> {
        val scored = rubric.scored
        val total = scored.sumOf { state.of(it.id) }
        val assessmentUuid = uuid()
        val head = PerfAssessment.Planned(
            assessmentUuid, "call_assessment", visitUuid, 1,
            com.aktcl.aron.core.database.record.AmoPayloads.callAssessment("joint_call", visitUuid, rubric.rubricId, rubric.version, assessedUserId, total, scored.size * 5),
        )
        return listOf(head) + scored.map {
            PerfAssessment.Planned(uuid(), "call_assessment_answer", visitUuid, 2, com.aktcl.aron.core.database.record.AmoPayloads.callAssessmentAnswer(assessmentUuid, it.id, state.of(it.id), null, null))
        }
    }
}
