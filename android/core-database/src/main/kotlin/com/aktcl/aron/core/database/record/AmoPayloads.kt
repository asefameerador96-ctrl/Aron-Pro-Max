package com.aktcl.aron.core.database.record

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Payload objects of the AMO records (contract `*Payload` schemas), built member by member so a field the contract does not
 * have cannot be sent; `AmoPayloadContractTest` compares every builder with the contract. Optional members that are null
 * are left out; required nullable members are written as null.
 */
object AmoPayloads {
    private fun kotlinx.serialization.json.JsonObjectBuilder.opt(k: String, v: Any?) {
        when (v) { null -> Unit; is String -> put(k, v); is Boolean -> put(k, v); is Number -> put(k, v); is JsonElement -> put(k, v); else -> error("unsupported") }
    }

    fun distributionCheck(visitUuid: String, posmPresent: Boolean?, note: String?) = buildJsonObject {
        put("visit_client_uuid", visitUuid); opt("posm_present", posmPresent); opt("note", note?.take(500))
    }

    /** OOS implies present: an out-of-stock line that is not present is refused here, as the server would. */
    fun distributionLine(checkUuid: String, brandId: Long, present: Boolean, oos: Boolean): JsonObject {
        require(present || !oos) { "OOS needs the brand to be present" }
        return buildJsonObject { put("check_client_uuid", checkUuid); put("brand_id", brandId); put("present", present); put("oos", oos) }
    }

    fun callAssessment(
        kind: String, visitUuid: String?, rubricId: Long, rubricVersion: Int, assessedUserId: Long?, totalScore: Int, maxScore: Int, delegateTask: Boolean = false, visitPlanOutletUuid: String? = null,
    ): JsonObject {
        require(kind == "joint_call" || kind == "retailer_questionnaire") { "kind" }
        require(rubricVersion >= 1 && totalScore in 0..1000 && maxScore in 0..1000) { "ranges" }
        return buildJsonObject {
            put("kind", kind); opt("visit_client_uuid", visitUuid); opt("visit_plan_outlet_client_uuid", visitPlanOutletUuid); put("rubric_id", rubricId); put("rubric_version", rubricVersion)
            opt("assessed_user_id", assessedUserId); put("total_score", totalScore); put("max_score", maxScore); put("delegate_task", delegateTask)
        }
    }

    fun callAssessmentAnswer(assessmentUuid: String, criterionId: Long, score: Int?, text: String?, bool: Boolean?): JsonObject {
        require(score == null || score in 1..5) { "score is 1 to 5" }
        return buildJsonObject {
            put("assessment_client_uuid", assessmentUuid); put("criterion_id", criterionId)
            put("score", score?.let { JsonPrimitive(it) } ?: JsonNull)
            opt("answer_text", text?.take(1000)); opt("answer_bool", bool)
        }
    }

    fun priceCompliance(visitUuid: String, skuId: Long, priceType: String, referenceMtk: Long, observedMtk: Long, compliant: Boolean, note: String?): JsonObject {
        require(referenceMtk >= 0 && observedMtk >= 0) { "money is not negative" }
        return buildJsonObject {
            put("visit_client_uuid", visitUuid); put("sku_id", skuId); put("price_type", priceType)
            put("reference_price_mtk", referenceMtk); put("observed_price_mtk", observedMtk); put("compliant", compliant); opt("note", note?.take(300))
        }
    }

    fun riskReview(signalId: Long, action: String, note: String?): JsonObject {
        require(action in setOf("reviewed", "dismissed", "confirmed")) { "action" }
        return buildJsonObject { put("signal_id", signalId); put("action", action); opt("note", note?.take(500)) }
    }

    /** [fix] is the contract `GeoFix` object (from the stored fix row). */
    fun outletRequestVerification(requestUuid: String, decision: String, subChannelId: Long?, geoClass: String?, fix: JsonObject, photoUuid: String?, note: String?): JsonObject {
        require(decision == "verified" || decision == "discarded") { "decision" }
        require(geoClass == null || geoClass in setOf("Hill", "Urban", "SemiUrban", "Rural")) { "geo_class" }
        return buildJsonObject {
            put("request_uuid", requestUuid); put("decision", decision); opt("sub_channel_id", subChannelId); opt("geo_class", geoClass)
            put("fix", fix); opt("photo_uuid", photoUuid); opt("note", note?.take(500))
        }
    }

    private val TASK_TYPE = Regex("^[a-z][a-z0-9_]{1,40}$")

    fun task(typeCode: String, assigneeUserId: Long, outletId: Long?, title: String, description: String?, dueDate: String?, sourceVisitUuid: String?): JsonObject {
        require(TASK_TYPE.matches(typeCode)) { "task_type_code" }
        require(title.isNotBlank() && title.length <= 120) { "title is 1 to 120 characters" }
        return buildJsonObject {
            put("task_type_code", typeCode); put("assignee_user_id", assigneeUserId); opt("outlet_id", outletId); put("title", title)
            opt("description", description?.take(1000)); opt("due_date", dueDate); opt("source_visit_client_uuid", sourceVisitUuid)
        }
    }
}
