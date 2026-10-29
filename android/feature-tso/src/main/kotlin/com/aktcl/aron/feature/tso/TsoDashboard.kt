package com.aktcl.aron.feature.tso

import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.Qty
import com.aktcl.aron.rules.Targets
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The TSO Dashboard cards (D-TSO-MAN-09, 10, 12; build contract s4.2; screenshots 6 to 8). Every card reads one
 * `POST /v1/dashboards/national/...` answer; the server sends raw integers and the phone formats them ONLY through the shared rules
 * (Percent, Qty, Targets: one rounding per figure, from the integers, never from a rounded intermediate or a server `*_bp`). The
 * scope is the token's reach; the request carries only the business date.
 */
enum class TsoDashCard(val path: String, val key: String) {
    SALES("sales", "sales"), GEO("geo-fencing", "geo"), CHANNEL_STD("channel-std", "chstd"), CHANNEL_CALLS("channel-calls", "chcalls"),
    CPR("cpr", "cpr"), BRAND_CPR("brand-cpr", "brandcpr"), LOGIN_SUBMIT("login-submit", "login"), SEGMENT("segment-share", "segment"),
}

/** One category of the Sales card: A = total sales (sold lines, base units), T = the month's target in scope (null: none loaded). */
data class SalesRow(val category: String, val baseUnit: String, val totalSales: Long?, val monthlyTarget: Long?, val achievementPctBp: Long?)

/** The Sales card: the four categories and W, the working days of the month at the scope's node (D-TSO-MAN-08). */
data class SalesCards(val asOf: String, val workingDays: Long, val rows: List<SalesRow>)

/** Geo Fencing: completed calls and the geo-valid ones. */
data class GeoCard(val asOf: String, val totalCalls: Long, val geoValidated: Long)

/** One channel of a channel card: its value (sticks for STD, calling outlets for Successful Call). */
data class ChannelSlice(val channel: String, val value: Long)
data class ChannelCard(val asOf: String, val channels: List<ChannelSlice>, val total: Long)

/** Live CPR: target outlets and successful calls. */
data class CprCard(val asOf: String, val targetOutlets: Long, val successfulCalls: Long)

/** One brand of Brand CPR: distinct (route, outlet) pairs that bought it. */
data class BrandRow(val code: String, val name: String, val outletsBought: Long)

/** Brand CPR: [denominator] says which whole the server's config uses (successful_calls by default, D-TSO-MAN-06). */
data class BrandCprCard(val asOf: String, val targetOutlets: Long, val successfulCalls: Long, val denominator: String, val brands: List<BrandRow>) {
    /** The whole of every brand's ratio: the successful calls, or the target outlets when the config says so. */
    val whole: Long get() = if (denominator == "target_outlets") targetOutlets else successfulCalls
}

/** Login and Bikroy Joma status: target routes, routes with a successful login, routes submitted. */
data class LoginSubmitCard(val asOf: String, val targetRoutes: Long, val successfulLogin: Long, val submitted: Long)

/** One segment of the value and volume card: its gross value and its quantity in its own unit family. */
data class SegmentRow(val code: String, val name: String, val baseUnit: String, val valueMtk: Long, val qtyBase: Long)
data class SegmentCard(val asOf: String, val totalValueMtk: Long, val familyTotals: Map<String, Long>, val segments: List<SegmentRow>)

/**
 * One (route, user) row of Not Logged In or Not Uploaded (D-TSO-MAN-12). A vacant route has an SR row with no user: user_id,
 * username and display_name all null (contract `LoginSubmitUserRow`). A real user may have no full name ([displayName] null).
 */
data class LoginSubmitUser(
    val routeId: Long, val routeName: String, val depName: String, val username: String?, val displayName: String?, val role: String,
    val userId: Long? = null,
) {
    /** A route with no user ("SR Not Set"): decided on the user, never on a missing display name. */
    val vacant: Boolean get() = userId == null && username == null

    /** The list key: the route and its user (the role for a vacant route's row). */
    val key: String get() = "$routeId:${username ?: userId?.toString() ?: role}"
}
data class LoginSubmitUsers(val asOf: String, val tab: String, val routeCount: Long, val truncated: Boolean, val items: List<LoginSubmitUser>)

/** The two tabs of screenshot 8, in screen order. */
enum class LoginSubmitTab(val wire: String) { NOT_LOGGED_IN("not_logged_in"), NOT_UPLOADED("not_uploaded") }

/** By Channel STD (sticks) or By Channel Successful Call (outlets); the ▸ on the card switches them (D-TSO-MAN-41). */
enum class ChannelMode { STD, CALLS }

/** Pure parsing and the formatting the cards print (s4.2). No timer, no network, no floating point in any figure. */
object TsoDashboards {
    private fun obj(json: String?): JsonObject? = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull()
    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun l(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    private fun arr(o: JsonObject, k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** The order of the Sales card (screenshot 6); a category the server adds later follows them. */
    val categoryOrder: List<String> = listOf("cigarette", "bidi", "lighter", "match")

    fun sales(json: String?): SalesCards? {
        val o = obj(json) ?: return null
        val rows = arr(o, "cards").mapNotNull { c ->
            // achievement_pct_bp is int64 (R-F-16, F1 ask 15): decoded as a Long; the ring itself is computed from the integers.
            SalesRow(s(c, "category_code") ?: return@mapNotNull null, s(c, "base_unit").orEmpty(), l(c, "total_sales"), l(c, "monthly_target_qty"), l(c, "achievement_pct_bp"))
        }
        val ordered = categoryOrder.mapNotNull { code -> rows.firstOrNull { it.category == code } } + rows.filter { it.category !in categoryOrder }
        return SalesCards(s(o, "as_of") ?: return null, l(o, "working_days") ?: 0L, ordered)
    }

    fun geo(json: String?): GeoCard? = obj(json)?.let { o -> GeoCard(s(o, "as_of") ?: return null, l(o, "total_calls") ?: 0L, l(o, "geo_validated") ?: 0L) }

    fun channels(json: String?): ChannelCard? = obj(json)?.let { o ->
        ChannelCard(s(o, "as_of") ?: return null, arr(o, "channels").mapNotNull { c -> ChannelSlice(s(c, "channel") ?: return@mapNotNull null, l(c, "value") ?: 0L) }, l(o, "total") ?: 0L)
    }

    fun cpr(json: String?): CprCard? = obj(json)?.let { o -> CprCard(s(o, "as_of") ?: return null, l(o, "target_outlets") ?: 0L, l(o, "successful_calls") ?: 0L) }

    fun brandCpr(json: String?): BrandCprCard? = obj(json)?.let { o ->
        BrandCprCard(
            s(o, "as_of") ?: return null, l(o, "target_outlets") ?: 0L, l(o, "successful_calls") ?: 0L, s(o, "denominator") ?: "successful_calls",
            arr(o, "brands").mapNotNull { b -> BrandRow(s(b, "brand_code") ?: return@mapNotNull null, s(b, "brand_name") ?: s(b, "brand_code").orEmpty(), l(b, "outlets_bought") ?: 0L) },
        )
    }

    fun loginSubmit(json: String?): LoginSubmitCard? = obj(json)?.let { o ->
        LoginSubmitCard(s(o, "as_of") ?: return null, l(o, "target_routes") ?: 0L, l(o, "successful_login") ?: 0L, l(o, "submitted") ?: 0L)
    }

    fun segments(json: String?): SegmentCard? = obj(json)?.let { o ->
        SegmentCard(
            s(o, "as_of") ?: return null, l(o, "total_value_mtk") ?: 0L,
            arr(o, "families").mapNotNull { f -> (s(f, "base_unit") ?: return@mapNotNull null) to (l(f, "total_qty_base") ?: 0L) }.toMap(),
            arr(o, "segments").mapNotNull { g ->
                SegmentRow(s(g, "segment_code") ?: return@mapNotNull null, s(g, "segment_name") ?: s(g, "segment_code").orEmpty(), s(g, "base_unit").orEmpty(), l(g, "value_mtk") ?: 0L, l(g, "qty_base") ?: 0L)
            },
        )
    }

    fun users(json: String?): LoginSubmitUsers? = obj(json)?.let { o ->
        LoginSubmitUsers(
            s(o, "as_of") ?: return null, s(o, "tab").orEmpty(), l(o, "route_count") ?: 0L, (o["truncated"] as? JsonPrimitive)?.booleanOrNull ?: false,
            arr(o, "items").mapNotNull { r ->
                LoginSubmitUser(l(r, "route_id") ?: return@mapNotNull null, s(r, "route_name").orEmpty(), s(r, "dep_name").orEmpty(), s(r, "username"), s(r, "display_name"), s(r, "role") ?: "SR", l(r, "user_id"))
            },
        )
    }

    // ---- the printed figures (s4.2), each one rounding from the integers -------------------------------------------------------

    /** Total Sales: Qty.text(A) ("40.0", "3.0 K"); a missing A prints the dash. */
    fun totalSales(r: SalesRow, locale: UiLocale): String = Qty.text(r.totalSales, locale)

    /** Total Target, the daily target: Qty.text(T, W) ("1.7 K"); T null or W = 0 prints the dash (R-F-13). */
    fun totalTarget(r: SalesRow, w: Long, locale: UiLocale): String = Qty.text(r.monthlyTarget, w, locale)

    /** The sales ring in tenths: Targets.dailyRingTenths(A, T, W), uncapped; null without A or T, at T = 0 and at W = 0. */
    fun ringTenths(r: SalesRow, w: Long): Long? {
        val a = r.totalSales ?: return null
        val t = r.monthlyTarget ?: return null
        return Targets.dailyRingTenths(a, t, w)
    }

    fun ring(r: SalesRow, w: Long, locale: UiLocale): String = Percent.text(ringTenths(r, w), 1, locale)

    /** Geo Fencing ring: geo-valid of completed calls, tenths ("100.0%"). */
    fun geoTenths(g: GeoCard): Long? = Percent.tenths(g.geoValidated, g.totalCalls)

    /** A channel's share, basis points of the card total, printed with 2 decimals ("100.00%"). */
    fun channelBp(c: ChannelSlice, total: Long): Long? = Percent.bp(c.value, total)

    /** A pie slice's angle in tenths of a degree, half up, from the integers (drawing only; 0 when the card total is 0). */
    fun sweepTenths(c: ChannelSlice, total: Long): Long = if (total <= 0L) 0L else (c.value * 7200L + total) / (2L * total)

    /** CPR ring: successful calls of target outlets, tenths (29 of 1,186 is 2.4%, never 2.5%). */
    fun cprTenths(c: CprCard): Long? = Percent.tenths(c.successfulCalls, c.targetOutlets)

    /** A brand's CPR, whole percent over the configured whole (2 of 3 is 67%). */
    fun brandWhole(b: BrandRow, card: BrandCprCard): Long? = Percent.whole(b.outletsBought, card.whole)

    /** Brand CPR exact to 2 decimals, for the line's height (the axis and the labels print whole percent). */
    fun brandBp(b: BrandRow, card: BrandCprCard): Long? = Percent.bp(b.outletsBought, card.whole)

    /** Login Status: routes with a successful login of target routes, whole percent (2 of 5 is 40%). */
    fun loginWhole(c: LoginSubmitCard): Long? = Percent.whole(c.successfulLogin, c.targetRoutes)

    /** Bikroy Joma Status: submitted of TARGET routes, whole percent (1 of 5 is 20%; D-TSO-MAN-07). */
    fun bikroyWhole(c: LoginSubmitCard): Long? = Percent.whole(c.submitted, c.targetRoutes)

    /** A segment's value share in basis points (750 of 1,000 milli-taka is 7500). */
    fun valueBp(g: SegmentRow, card: SegmentCard): Long? = Percent.bp(g.valueMtk, card.totalValueMtk)

    /** A segment's volume share inside its own unit family (sticks with sticks; never units added across families). */
    fun volumeBp(g: SegmentRow, card: SegmentCard): Long? = card.familyTotals[g.baseUnit]?.let { Percent.bp(g.qtyBase, it) }

    /** A segment's value share as a whole percent from the integers (the line labels). */
    fun valueWhole(g: SegmentRow, card: SegmentCard): Long? = Percent.whole(g.valueMtk, card.totalValueMtk)

    fun volumeWhole(g: SegmentRow, card: SegmentCard): Long? = card.familyTotals[g.baseUnit]?.let { Percent.whole(g.qtyBase, it) }

    /**
     * The y-axis ticks of a line chart in whole percent: [steps] + 1 labels from 0 to the highest point [maxBp], tick k =
     * round_half_up(maxBp x k / (steps x 100)) = Percent.whole(maxBp x k, steps x 10000). Brand CPR (6 steps, top 6667 bp) gives
     * 0, 11, 22, 33, 44, 56, 67 as screenshot 7.
     */
    fun axisTicks(maxBp: Long, steps: Int): List<Long> = (0..steps).map { k -> Percent.whole(maxBp * k, steps * 10_000L) ?: 0L }

    /** "as of hh:mm" in Dhaka time from the server's stamp; null when the stamp is missing or unreadable. */
    fun asOfClock(iso: String?): String? = instantOf(iso)?.let { runCatching { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Dhaka")).format(it) }.getOrNull() }

    /** An RFC 3339 stamp as an instant ("Z" or an offset, any number of fraction digits); null when it is not one. */
    fun instantOf(iso: String?): Instant? = iso?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /** The oldest of [stamps] by time (never by string order: fraction digits and offsets differ); unreadable stamps are skipped. */
    fun oldest(stamps: List<String>): String? = stamps.mapNotNull { s -> instantOf(s)?.let { it to s } }.minByOrNull { it.first }?.second

    /** The request body of every card: only the business date (the scope is the token's; the client never sends scope ids). */
    fun filterBody(businessDate: String): String = """{"date":"$businessDate"}"""
}
