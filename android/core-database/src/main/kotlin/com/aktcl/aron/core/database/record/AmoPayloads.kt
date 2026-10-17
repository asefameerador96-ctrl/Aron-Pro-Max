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
    /** Members of each AMO payload: required, and all allowed (the contract's; `AmoRecordsTest` compares them with it). */
    class Shape(val schema: String, val required: Set<String>, val allowed: Set<String>)

    val SHAPES: Map<String, Shape> = listOf(
        Shape("DistributionCheckPayload", setOf("visit_client_uuid"), setOf("visit_client_uuid", "posm_present", "note")),
        Shape("DistributionCheckLinePayload", setOf("check_client_uuid", "brand_id", "present", "oos"), setOf("check_client_uuid", "brand_id", "present", "oos")),
        Shape("CallAssessmentPayload", setOf("kind", "rubric_id", "rubric_version", "total_score", "max_score", "delegate_task"),
            setOf("kind", "visit_client_uuid", "visit_plan_outlet_client_uuid", "rubric_id", "rubric_version", "assessed_user_id", "total_score", "max_score", "delegate_task")),
        Shape("CallAssessmentAnswerPayload", setOf("assessment_client_uuid", "criterion_id"), setOf("assessment_client_uuid", "criterion_id", "score", "answer_text", "answer_bool")),
        Shape("PriceComplianceCheckPayload", setOf("visit_client_uuid", "sku_id", "price_type", "reference_price_mtk", "observed_price_mtk", "compliant"),
            setOf("visit_client_uuid", "sku_id", "price_type", "reference_price_mtk", "observed_price_mtk", "compliant", "note")),
        Shape("RiskReviewPayload", setOf("signal_id", "action"), setOf("signal_id", "action", "note")),
        Shape("OutletRequestVerificationPayload", setOf("request_uuid", "decision", "fix"), setOf("request_uuid", "decision", "sub_channel_id", "geo_class", "fix", "photo_uuid", "note")),
        Shape("TaskPayload", setOf("task_type_code", "assignee_user_id", "title"),
            setOf("task_type_code", "assignee_user_id", "outlet_id", "title", "description", "due_date", "source_visit_client_uuid")),
    ).associateBy { it.schema }.let { m ->
        mapOf(
            "distribution_check" to m.getValue("DistributionCheckPayload"), "distribution_check_line" to m.getValue("DistributionCheckLinePayload"),
            "call_assessment" to m.getValue("CallAssessmentPayload"), "call_assessment_answer" to m.getValue("CallAssessmentAnswerPayload"),
            "price_compliance_check" to m.getValue("PriceComplianceCheckPayload"), "risk_review" to m.getValue("RiskReviewPayload"),
            "outlet_request_verification" to m.getValue("OutletRequestVerificationPayload"), "task" to m.getValue("TaskPayload"),
        )
    }

    /** Null when [payload] fits [type]'s payload (every required member, no member the contract lacks), else what is wrong. */
    fun problem(type: String, payload: JsonObject): String? {
        val shape = SHAPES[type] ?: return "not an AMO record type: $type"
        val missing = shape.required - payload.keys
        if (missing.isNotEmpty()) return "$type payload lacks $missing"
        val extra = payload.keys - shape.allowed
        if (extra.isNotEmpty()) return "$type payload has members the contract lacks: $extra"
        return null
    }

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
