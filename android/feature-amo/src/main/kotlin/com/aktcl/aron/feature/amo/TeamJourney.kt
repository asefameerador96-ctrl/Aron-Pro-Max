package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One row of `GET /v1/team/members` (D-AMO-MAN-09): an SR on one route, or a vacant route ([userId] null). [phone] is in clear only for an
 * AMO reading their own zone; [phoneMasked] for every other reader.
 */
data class TeamMember(
    val userId: Long?, val name: String?, val routeId: Long, val routeName: String, val routeDays: List<Int>, val phone: String?, val phoneMasked: String?,
    val totalOutlets: Int, val vacant: Boolean, val routeCode: String? = null,
)

enum class CallStatus { NEW_SALE, EDITED_SALE, NO_SALE }

data class JourneyOutlet(val id: Long, val name: String, val code: String, val phone: String?, val cluster: String?)

/** One call of the SR's day (contract `TeamJourney.calls[]`); distances in metres, times in seconds. */
data class JourneyCall(
    val visitUuid: String, val outlet: JourneyOutlet, val status: CallStatus, val openedAt: String, val closedAt: String?, val durationS: Long?,
    val distanceFromPrevMetres: Double?, val runningMetres: Double, val gapToNextS: Long?,
)

data class TeamJourney(val userName: String, val routeName: String, val businessDate: String, val calls: List<JourneyCall>, val runningMetres: Double)

/**
 * Team members and the Sales Journey (screenshots 73, 74; D-AMO-MAN-07, R14). The server computes the distances, durations and gaps
 * (haversine on the outlets' base points, closed_at - opened_at); the phone only parses and prints them: durations as "৭ মিনিট ৪৬ সেকেন্ড",
 * distances in metres with two decimals as the screenshot prints them (277.32) in the user's digits, times on the 12-hour Dhaka clock in Latin digits.
 */
object TeamJourneys {
    private fun obj(json: String?): JsonObject? = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull()
    private fun s(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun l(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    private fun d(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    /** The members list in the server's order (SR name, route); a malformed answer is empty, never a crash. */
    fun members(json: String?): List<TeamMember> = (obj(json)?.get("items") as? JsonArray).orEmpty().mapNotNull { e ->
        val m = e as? JsonObject ?: return@mapNotNull null
        TeamMember(
            l(m, "user_id"), s(m, "full_name"), l(m, "route_id") ?: return@mapNotNull null, s(m, "route_name").orEmpty(),
            (m["route_days"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.filter { it in 1..7 }.sorted(),
            s(m, "phone")?.takeIf { it.isNotBlank() }, s(m, "phone_masked")?.takeIf { it.isNotBlank() }, (m["total_outlets"] as? JsonPrimitive)?.intOrNull ?: 0,
            (m["vacant"] as? JsonPrimitive)?.booleanOrNull ?: (l(m, "user_id") == null), s(m, "route_code")?.takeIf { it.isNotBlank() },
        )
    }

    fun journey(json: String?): TeamJourney? {
        val o = obj(json) ?: return null
        val calls = (o["calls"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val out = c["outlet"] as? JsonObject ?: return@mapNotNull null
            JourneyCall(
                s(c, "visit_uuid") ?: return@mapNotNull null,
                // The brief asks for the real phone; the contract field today is `phone_masked`, so a clear `phone` wins when the server sends one.
                JourneyOutlet(l(out, "id") ?: 0L, s(out, "name").orEmpty(), s(out, "code").orEmpty(), s(out, "phone") ?: s(out, "phone_masked"), s(out, "cluster")),
                when (s(c, "status")) { "new_sale" -> CallStatus.NEW_SALE; "edited_sale" -> CallStatus.EDITED_SALE; else -> CallStatus.NO_SALE },
                s(c, "opened_at") ?: return@mapNotNull null, s(c, "closed_at"), l(c, "duration_s"), d(c, "distance_from_prev_m"), d(c, "distance_so_far_m") ?: 0.0, l(c, "gap_to_next_s"),
            )
        }
        return TeamJourney(s(o["user"] as? JsonObject, "full_name").orEmpty(), s(o["route"] as? JsonObject, "name").orEmpty(), s(o, "business_date").orEmpty(), calls, d(o, "distance_m") ?: 0.0)
    }

    // ---- printing --------------------------------------------------------------------------------------------------------------

    /** "৭ মিনিট ৪৬ সেকেন্ড" / "7 min 46 s"; seconds only under a minute ("৪৫ সেকেন্ড"); whole seconds, never negative. [minWord] and [secWord] come from the string layer. */
    fun duration(seconds: Long, minWord: String, secWord: String, locale: UiLocale): String {
        val t = seconds.coerceAtLeast(0L)
        val m = t / 60L; val sec = t % 60L
        val sPart = Formats.digits(sec.toString(), locale) + " " + secWord
        return if (m == 0L) sPart else Formats.digits(m.toString(), locale) + " " + minWord + " " + sPart
    }

    /** Metres with two decimals (HALF_UP, the server's precision) in the user's digits: "২৭৭.৩২" / "277.32"; the unit word is added by the screen. */
    fun metres(m: Double): BigDecimal = BigDecimal.valueOf(if (m.isFinite()) m.coerceAtLeast(0.0) else 0.0).setScale(2, RoundingMode.HALF_UP)

    fun metresText(m: Double, locale: UiLocale): String = Formats.digits(metres(m).toPlainString(), locale)

    private val dhaka = ZoneId.of("Asia/Dhaka")
    private val clock = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH).withZone(dhaka)

    /** "9:43:04 AM": Dhaka time, 12-hour, Latin digits and AM/PM in both languages (screenshot 74); a bad stamp prints as it is. */
    fun clock12(iso: String): String = runCatching { clock.format(Instant.parse(iso)) }.getOrDefault(iso)

    /** "9:43:04 AM - 9:48:47 AM"; an open call prints only its start. */
    fun range(c: JourneyCall): String = clock12(c.openedAt) + (c.closedAt?.let { " - " + clock12(it) } ?: "")

    /** The outlet line "Name (code-phone-cluster)" with the parts that exist (screenshot 74). */
    fun outletLine(o: JourneyOutlet): String {
        val tail = listOfNotNull(o.code.takeIf { it.isNotBlank() }, o.phone?.takeIf { it.isNotBlank() }, o.cluster?.takeIf { it.isNotBlank() }).joinToString("-")
        return if (tail.isEmpty()) o.name else "${o.name} ($tail)"
    }

    /** "01952-582092" (five digits, hyphen, the rest) for a clear 11-digit number; a masked or odd number prints as it is. */
    fun phoneText(p: String?): String {
        if (p.isNullOrBlank()) return "***********"
        val digits = p.filter { it.isDigit() }
        return if (digits.length == 11 && digits.length == p.length) digits.take(5) + "-" + digits.drop(5) else p
    }

    /** "Name(Sat, Mon, Wed)" or "NameDaily": [dayName] maps an ISO weekday to its short name; six or seven days (Saturday to Thursday plus) is "Daily". */
    fun routeLabel(name: String, days: List<Int>, daily: String, dayName: (Int) -> String): String {
        if (days.isEmpty()) return name
        if (days.size >= 6) return "$name $daily"
        val order = listOf(6, 7, 1, 2, 3, 4, 5) // the Bangladesh week: Saturday first
        return "$name (" + days.sortedBy { order.indexOf(it) }.joinToString(", ") { dayName(it) } + ")"
    }
}
