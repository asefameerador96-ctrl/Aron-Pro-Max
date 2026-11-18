package com.aktcl.aron.core.database.record

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Payload objects of the SR programme records (contract 1.17.0: `redemption`, `redemption_line`, `gift_choice`), built member by member so a
 * member the contract does not have cannot be sent. Lane android-sr-programmes. Required members of [SHAPES] are the contract's; points are
 * integers and money is integer milli-taka (no float anywhere).
 */
object ProgrammePayloads {
    class Shape(val required: Set<String>, val allowed: Set<String>)

    val SHAPES: Map<String, Shape> = mapOf(
        "redemption" to Shape(
            setOf("outlet_id", "programme_id", "balance_before_points", "points_total", "cash_points", "cash_mtk", "cash_rate_mtk_per_point", "line_count", "confirmed_at"),
            setOf("outlet_id", "programme_id", "visit_client_uuid", "balance_before_points", "points_total", "cash_points", "cash_mtk", "cash_rate_mtk_per_point", "line_count", "confirmed_at", "fix"),
        ),
        "redemption_line" to Shape(
            setOf("redemption_client_uuid", "gift_id", "qty", "points_each", "points_total"),
            setOf("redemption_client_uuid", "gift_id", "qty", "points_each", "points_total"),
        ),
        "gift_choice" to Shape(setOf("outlet_id", "gift_id", "quarter"), setOf("outlet_id", "gift_id", "quarter", "fix")),
    )

    /** Null when [payload] fits [type] (every required member, no member the contract lacks, the contract's ranges), else what is wrong. */
    fun problem(type: String, payload: JsonObject): String? {
        val shape = SHAPES[type] ?: return "not a programme record type: $type"
        val missing = shape.required - payload.keys
        if (missing.isNotEmpty()) return "$type payload lacks $missing"
        val extra = payload.keys - shape.allowed
        if (extra.isNotEmpty()) return "$type payload has members the contract lacks: $extra"
        fun n(k: String) = (payload[k] as? JsonPrimitive)?.content?.toLongOrNull()
        return when (type) {
            "redemption" -> when {
                (n("balance_before_points") ?: -1) < 0 -> "balance_before_points is an integer from 0"
                (n("points_total") ?: 0) !in 1..100_000 -> "points_total is 1 to 100000"
                (n("cash_points") ?: -1) !in 0..100_000 -> "cash_points is 0 to 100000"
                (n("cash_mtk") ?: -1) < 0 -> "cash_mtk is integer milli-taka from 0"
                (n("cash_rate_mtk_per_point") ?: -1) !in 0..100_000 -> "cash_rate_mtk_per_point is 0 to 100000"
                n("cash_mtk") != (n("cash_points") ?: 0) * (n("cash_rate_mtk_per_point") ?: 0) -> "cash_mtk must equal cash_points x the rate"
                (n("line_count") ?: -1) !in 0..20 -> "line_count is 0 to 20"
                else -> null
            }
            "redemption_line" -> when {
                (n("qty") ?: 0) !in 1..50 -> "qty is 1 to 50"
                (n("points_each") ?: 0) !in 1..100_000 -> "points_each is 1 to 100000"
                n("points_total") != (n("qty") ?: 0) * (n("points_each") ?: 0) -> "points_total must equal qty x points_each"
                else -> null
            }
            "gift_choice" -> if (Regex("^[0-9]{4}-Q[1-4]$").matches((payload["quarter"] as? JsonPrimitive)?.content.orEmpty())) null else "quarter is YYYY-Qn"
            else -> null
        }
    }

    /**
     * A basket the SR confirmed. [balanceBefore] is the balance the phone showed; the contract says it is not below zero, so an overdrawn
     * phone balance goes out as 0 (the server holds the real figure and flags the overdraw itself). [pointsTotal] = [cashPoints] + the sum of the lines.
     */
    fun redemption(
        outletId: Long, programmeId: Long, visitUuid: String?, balanceBefore: Long, pointsTotal: Long, cashPoints: Long, cashRateMtkPerPoint: Long,
        lineCount: Int, confirmedAt: String, fix: JsonObject? = null,
    ) = buildJsonObject {
        put("outlet_id", outletId); put("programme_id", programmeId); visitUuid?.let { put("visit_client_uuid", it) }
        put("balance_before_points", balanceBefore.coerceAtLeast(0L)); put("points_total", pointsTotal); put("cash_points", cashPoints)
        put("cash_mtk", cashPoints * cashRateMtkPerPoint); put("cash_rate_mtk_per_point", cashRateMtkPerPoint)
        put("line_count", lineCount); put("confirmed_at", confirmedAt); fix?.let { put("fix", it) }
    }

    fun redemptionLine(redemptionUuid: String, giftId: Long, qty: Int, pointsEach: Long) = buildJsonObject {
        put("redemption_client_uuid", redemptionUuid); put("gift_id", giftId); put("qty", qty); put("points_each", pointsEach); put("points_total", qty * pointsEach)
    }

    fun giftChoice(outletId: Long, giftId: Long, quarter: String, fix: JsonObject? = null) = buildJsonObject {
        put("outlet_id", outletId); put("gift_id", giftId); put("quarter", quarter); fix?.let { put("fix", it) }
    }
}
