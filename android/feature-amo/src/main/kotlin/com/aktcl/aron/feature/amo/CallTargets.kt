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
 * Where the Home reads the target cards from (the fixture form; the app uses [CachedCallTargets] through [CallTargetsFeed]). [UnsetCallTargets]
 * makes every card print "—" and nothing on Home waits for the network.
 */
fun interface CallTargetsSource { suspend fun load(month: String): CallTargets? }

/** The fixture used until the server endpoint lands: no targets, so every card prints "—". */
object UnsetCallTargets : CallTargetsSource { override suspend fun load(month: String): CallTargets? = null }

/** What one read of `GET /v1/supervisor/call-targets` gave: the answer body, "not for you / not offered" (403, 404, 501), or no answer (offline, timeout, a server error). */
sealed interface CallTargetsFetch {
    data class Fresh(val json: String) : CallTargetsFetch
    data object Gone : CallTargetsFetch
    data object Unavailable : CallTargetsFetch
}

/** The last good answer per Dhaka month (`YYYY-MM`), kept on the phone (Room `sync_meta` in the app) so the cards still show something offline. */
interface CallTargetsStore {
    suspend fun read(month: String): String?
    suspend fun write(month: String, json: String)
    suspend fun clear(month: String)
}

/** Fresh = just read (or nothing to say: no targets for this user); Stale = the kept copy is shown because the read failed; Failed = no answer and no kept copy. */
enum class CallTargetsStatus { Fresh, Stale, Failed }

data class CallTargetsResult(val targets: CallTargets?, val status: CallTargetsStatus)

/** Where the Home reads the cards and their state from; never throws, never blocks a sale. */
fun interface CallTargetsFeed { suspend fun load(month: String): CallTargetsResult }

/**
 * The real source behind [CallTargetsFeed] (D-AMO-SHELL-10): the network read, capped at [timeoutMs], with a kept copy. A good answer is
 * parsed, stored per month and returned [CallTargetsStatus.Fresh]; a failed or malformed read returns the last good copy of that month
 * (the store, else the one held in memory) as [CallTargetsStatus.Stale] and NEVER a blank; with no copy at all it is
 * [CallTargetsStatus.Failed]. A refusal ([CallTargetsFetch.Gone]) drops the copy: the server no longer offers the cards to this user.
 */
class CachedCallTargets(
    private val fetch: suspend (month: String) -> CallTargetsFetch,
    private val store: CallTargetsStore,
    private val timeoutMs: Long = 8_000,
) : CallTargetsFeed {
    @Volatile private var kept: Pair<String, CallTargets>? = null

    override suspend fun load(month: String): CallTargetsResult {
        val read = try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { fetch(month) } ?: CallTargetsFetch.Unavailable
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            CallTargetsFetch.Unavailable
        }
        when (read) {
            is CallTargetsFetch.Fresh -> CallTargets.parse(read.json)?.let { t ->
                runCatching { store.write(month, read.json) }
                kept = month to t
                return CallTargetsResult(t, CallTargetsStatus.Fresh)
            }
            CallTargetsFetch.Gone -> {
                runCatching { store.clear(month) }
                if (kept?.first == month) kept = null
                return CallTargetsResult(null, CallTargetsStatus.Fresh)
            }
            CallTargetsFetch.Unavailable -> Unit
        }
        val saved = runCatching { store.read(month) }.getOrNull()?.let { CallTargets.parse(it) } ?: kept?.takeIf { it.first == month }?.second
        return if (saved != null) CallTargetsResult(saved, CallTargetsStatus.Stale) else CallTargetsResult(null, CallTargetsStatus.Failed)
    }
}

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

    /**
     * "done/target" in Latin digits (the caller localises the digits). A target that is not set prints "done/—" (D-AMO-SHELL-11: the AMO
     * sees their own count even while the target programme is deferred); "—" alone only when there is no card data at all.
     */
    fun figure(t: CallTarget?, dash: String = "—"): String = when {
        t == null -> dash
        t.target == null -> "${t.done}/$dash"
        else -> "${t.done}/${t.target}"
    }
}
