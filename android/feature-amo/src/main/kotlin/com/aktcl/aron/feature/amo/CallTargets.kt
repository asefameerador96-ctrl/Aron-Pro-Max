package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One target card figure: calls [done] against the [target]; a null target means the number is not configured (the card prints "—"). */
data class CallTarget(val done: Int, val target: Int?)

/**
 * The four target cards of the AMO Home (screenshot 9; D-AMO-MAN-02): today's target, the month's call target, the control-call target and
 * the joint-call target. They come from `GET /v1/supervisor/call-targets` (contract section 3, built by lane/amo-foundation).
 */
data class CallTargets(val today: CallTarget, val month: CallTarget, val control: CallTarget, val joint: CallTarget) {
    companion object {
        /** The contract answer `{ today: {done, target}, month: {..}, control: {..}, joint: {..} }`; a malformed answer is null, never a crash. */
        fun parse(json: String?): CallTargets? {
            val o = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull() ?: return null
            fun one(k: String): CallTarget? {
                val e = o[k] as? JsonObject ?: return null
                val done = (e["done"] as? JsonPrimitive)?.intOrNull ?: return null
                // an absent or JSON-null target is "unset"
                return CallTarget(done, (e["target"] as? JsonPrimitive)?.intOrNull)
            }
            return CallTargets(one("today") ?: return null, one("month") ?: return null, one("control") ?: return null, one("joint") ?: return null)
        }
    }
}

/**
 * Where the Home reads the target cards from. The real source (the endpoint client) lands with lane/amo-foundation; until then the app
 * uses [UnsetCallTargets], so every card prints "—" and nothing on Home waits for the network.
 */
fun interface CallTargetsSource { suspend fun load(month: String): CallTargets? }

/** The fixture used until the server endpoint lands: no targets, so every card prints "—". */
object UnsetCallTargets : CallTargetsSource { override suspend fun load(month: String): CallTargets? = null }

/** Formulas 4.3 of the build contract (integer arithmetic; the server owns the live numbers, the app and the tests share the rule). */
object CallTargetMath {
    /** Today's target = the month's target / working days of the month, rounded half up, at least 1 (0 stays 0: nothing is due). Null when unset. */
    fun todayTarget(monthTarget: Int?, workingDays: Int): Int? {
        if (monthTarget == null || workingDays <= 0) return null
        if (monthTarget == 0) return 0
        return maxOf(1, (2 * monthTarget + workingDays) / (2 * workingDays))
    }

    /** One visit as the card counts it: kind `amo_control_call` / `amo_joint_call`, business date, closed and not abandoned. */
    data class CountedVisit(val kind: String, val businessDate: String, val closed: Boolean, val abandoned: Boolean) {
        val counts: Boolean get() = closed && !abandoned
    }

    const val CONTROL = "amo_control_call"
    const val JOINT = "amo_joint_call"

    /**
     * The four cards from the three configured monthly numbers, the working days of the month and the caller's visits of the month:
     * done today = closed non-abandoned visits on [today]; month figures = the same over the month; control and joint = by visit kind.
     */
    fun compute(
        monthCall: Int?, monthControl: Int?, monthJoint: Int?, workingDays: Int, today: String, visits: List<CountedVisit>,
    ): CallTargets {
        val counted = visits.filter { it.counts }
        return CallTargets(
            today = CallTarget(counted.count { it.businessDate == today }, todayTarget(monthCall, workingDays)),
            month = CallTarget(counted.size, monthCall),
            control = CallTarget(counted.count { it.kind == CONTROL }, monthControl),
            joint = CallTarget(counted.count { it.kind == JOINT }, monthJoint),
        )
    }

    /** "done/target" in Latin digits (the caller localises the digits), or "—" while the target is unset. */
    fun figure(t: CallTarget?, dash: String = "—"): String = if (t?.target == null) dash else "${t.done}/${t.target}"
}
