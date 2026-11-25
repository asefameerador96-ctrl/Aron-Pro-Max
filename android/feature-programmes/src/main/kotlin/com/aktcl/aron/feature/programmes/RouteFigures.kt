package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.SkuProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A SKU target of the route for the bundle month (`TargetRow`): [targetMilli] in thousandths of the SKU's base unit ([unit]: stick, piece or dozen). */
data class SkuTarget(val skuId: Long, val month: String, val targetMilli: Long, val unit: String, val memoTarget: Long)

/** The server's achievement of a SKU (`AchievementRow`), sale lines only, in base units. A SKU with no sales is absent from the bundle (achieved 0). [throughDate]: the last closed day it covers. */
data class SkuAchieved(val skuId: Long, val qtyBase: Long, val memoCount: Long, val throughDate: String?, val asOf: String?)

/** `RouteSnapshot.calendar` exactly as sent. The day counts are the server's: they are NEVER recomputed on the phone, only read through [SkuProgress.dayCounts]. */
data class SentCalendar(
    val month: String, val workingDays: Int, val elapsedBeforeToday: Int, val remainingAfterToday: Int, val todayIsWorking: Boolean,
    val lastMonthWorkingDays: Int, val throughDate: String, val asOf: String?,
) {
    fun skuCalendar(): SkuProgress.Calendar = SkuProgress.Calendar(elapsedBeforeToday, remainingAfterToday, todayIsWorking)
    fun dayCounts(convention: SkuProgress.DayConvention = SkuProgress.DEFAULT_CONVENTION): SkuProgress.DayCounts = SkuProgress.dayCounts(skuCalendar(), convention)
}

/**
 * Target and achieved base units for one pack size (`KpiPackFigure`). [categoryCode] is the SKU category of the entry (contract 1.25.0, lower case); null from an older server, which does not send it
 * (the phone then resolves the category from its product tree, as before).
 */
data class PackFigure(val packFactor: Int, val target: Long, val achieved: Long, val categoryCode: String? = null)

data class KpiTotal(val target: Long, val achieved: Long, val bp: Long?)

/** A brand row of the KPI (`KpiBrand`). [kpiTodayMtk] is null until the owner defines it (Q-G). */
data class KpiBrand(
    val brandId: Long, val volumeTarget: Long, val volumeAchieved: Long, val volumeBp: Long?, val memoTarget: Long, val memoAchieved: Long, val memoBp: Long?,
    val kpiAllocMtk: Long, val kpiTodayMtk: Long?, val byPack: List<PackFigure>,
)

data class KpiCategory(val categoryCode: String, val volumeTarget: Long, val volumeAchieved: Long, val volumeBp: Long?, val byPack: List<PackFigure>)

data class KpiCpr(val targetBp: Long?, val allocMtk: Long?, val achievedBp: Long?)

/** `RouteSnapshot.kpi`. [achievedMtk] and [achievementBp] are null until the owner defines them (Q-F): the screens show a dash, never a number made up here. */
data class RouteKpi(
    val month: String, val throughDate: String, val asOf: String?, val allocatedMtk: Long?, val achievedMtk: Long?, val achievementBp: Long?,
    val volume: KpiTotal, val memo: KpiTotal, val brands: List<KpiBrand>, val brandsTruncated: Boolean, val categories: List<KpiCategory>, val cpr: KpiCpr?,
)

/** `RouteSnapshot.star` (Match Box Lifting Star, screen 62). Decimal3 members are thousandths of the programme unit; null where a divisor is 0 (a dash, never 0). */
data class RouteStar(
    val programmeId: Long, val name: String, val nameBn: String?, val month: String, val throughDate: String, val asOf: String?, val srName: String?, val srCode: String?, val routeName: String,
    val lmwd: Int, val wd: Int, val ewd: Int, val targetMilli: Long, val stdMilli: Long, val projectionMilli: Long?, val achievementBp: Long?, val lmsMilli: Long, val lmadsMilli: Long?,
    val padsMilli: Long?, val growthBp: Long?, val statusEn: String, val statusBn: String, val achievedSlab: String?, val slabAmountMtk: Long?, val projectedSlab: String?, val projectedAmountMtk: Long?,
)

data class CampaignFigure(val target: Long, val achieved: Long, val remaining: Long)

data class CampaignSku(val skuId: Long, val targetQtyBase: Long, val soldQtyBase: Long)

/** One outlet's campaign target (`RouteCampaign`): STD and memo, the outlet's figures from every route's memos, and the SKU table. */
data class RouteCampaign(
    val outletId: Long, val programmeId: Long, val labelEn: String, val labelBn: String?, val month: String, val throughDate: String, val asOf: String?,
    val std: CampaignFigure, val memo: CampaignFigure, val skus: List<CampaignSku>,
)

/**
 * The SR programme figures of ONE route snapshot, parsed once from the stored `route.<id>` section (contract 1.24.0; the figures rule is in the `RouteSnapshot` description). Every member is the
 * SERVER's figure, complete through its `through_date`; nothing here adds a local memo to it or subtracts from it (see [FigureFrame]). An absent or null section parses to null / empty, and a member
 * that is malformed is dropped on its own (the others stay), never replaced by a made-up number. [truncated] says the bundle cut a list (never silent).
 */
data class RouteFigures(
    val routeId: Long?, val targets: List<SkuTarget>, val achievement: List<SkuAchieved>, val calendar: SentCalendar?, val kpi: RouteKpi?, val star: RouteStar?,
    val campaigns: List<RouteCampaign>, val campaignsTruncated: Boolean, val targetsTruncated: Boolean, val starTruncated: Boolean,
) {
    /** The month of the figures: the calendar's, else the first section that has one. */
    val month: String? get() = calendar?.month ?: kpi?.month ?: star?.month ?: targets.firstOrNull()?.month ?: campaigns.firstOrNull()?.month

    /** The last closed day the figures cover: the calendar's, else the newest through_date of any section; null when the bundle has none. */
    val throughDate: String? get() = calendar?.throughDate ?: kpi?.throughDate ?: star?.throughDate ?: achievement.mapNotNull { it.throughDate }.maxOrNull() ?: campaigns.firstOrNull()?.throughDate

    fun achievedOf(skuId: Long): SkuAchieved? = achievement.firstOrNull { it.skuId == skuId }

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
        val EMPTY = RouteFigures(null, emptyList(), emptyList(), null, null, null, emptyList(), false, false, false)

        /** The stored route section (`route.<id>`) or a whole route snapshot as JSON text; null when it is not a JSON object. */
        fun parse(json: String?): RouteFigures? {
            val o = json?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
            return RouteFigures(
                routeId = o.long("route_id"),
                targets = o.list("targets").mapNotNull(::target),
                achievement = o.list("achievement_mtd").mapNotNull(::achieved),
                calendar = (o["calendar"] as? JsonObject)?.let(::calendar),
                kpi = (o["kpi"] as? JsonObject)?.let(::kpi),
                star = (o["star"] as? JsonObject)?.let(::star),
                campaigns = o.list("campaigns").mapNotNull(::campaign),
                campaignsTruncated = o.bool("campaigns_truncated") == true, targetsTruncated = o.bool("targets_truncated") == true, starTruncated = o.bool("star_truncated") == true,
            )
        }

        private fun target(e: JsonElement): SkuTarget? {
            val o = e as? JsonObject ?: return null
            if (o.str("product_level") != "sku" || o.str("scope_type") != "route") return null
            return SkuTarget(o.long("product_id") ?: return null, o.str("month") ?: return null, AsthaModel.decimal3(o["std_target"]) ?: return null, o.str("std_unit") ?: return null, o.long("memo_target") ?: return null)
        }

        private fun achieved(e: JsonElement): SkuAchieved? {
            val o = e as? JsonObject ?: return null
            if (o.str("product_level") != "sku") return null
            return SkuAchieved(o.long("product_id") ?: return null, o.long("achieved_qty_base") ?: return null, o.long("memo_count") ?: return null, o.str("through_date"), o.str("as_of"))
        }

        private fun calendar(o: JsonObject): SentCalendar? {
            val e = o.int("elapsed_working_days_before_today"); val r = o.int("remaining_working_days_after_today")
            if (e == null || r == null || e < 0 || r < 0) return null
            return SentCalendar(o.str("month") ?: return null, o.int("working_days") ?: return null, e, r, o.bool("today_is_working") ?: return null, o.int("last_month_working_days") ?: return null, o.str("through_date") ?: return null, o.str("as_of"))
        }

        private fun packs(o: JsonObject): List<PackFigure> = o.list("by_pack").mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            PackFigure(p.int("pack_factor")?.takeIf { it > 0 } ?: return@mapNotNull null, p.long("target") ?: return@mapNotNull null, p.long("achieved") ?: return@mapNotNull null, p.str("category_code")?.trim()?.lowercase()?.takeIf { it.isNotBlank() })
        }

        private fun total(o: JsonObject?): KpiTotal? = o?.let { KpiTotal(it.long("target") ?: return null, it.long("achieved") ?: return null, it.long("bp")) }

        private fun kpi(o: JsonObject): RouteKpi? = RouteKpi(
            o.str("month") ?: return null, o.str("through_date") ?: return null, o.str("as_of"), o.long("allocated_mtk"), o.long("achieved_mtk"), o.long("achievement_bp"),
            total(o["volume"] as? JsonObject) ?: return null, total(o["memo"] as? JsonObject) ?: return null,
            o.list("brands").mapNotNull { e ->
                val b = e as? JsonObject ?: return@mapNotNull null
                KpiBrand(b.long("brand_id") ?: return@mapNotNull null, b.long("volume_target") ?: return@mapNotNull null, b.long("volume_achieved") ?: return@mapNotNull null, b.long("volume_bp"), b.long("memo_target") ?: return@mapNotNull null,
                    b.long("memo_achieved") ?: return@mapNotNull null, b.long("memo_bp"), b.long("kpi_alloc_mtk") ?: return@mapNotNull null, b.long("kpi_today_mtk"), packs(b))
            },
            o.bool("brands_truncated") == true,
            o.list("categories").mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                KpiCategory(c.str("category_code") ?: return@mapNotNull null, c.long("volume_target") ?: return@mapNotNull null, c.long("volume_achieved") ?: return@mapNotNull null, c.long("volume_bp"), packs(c))
            },
            (o["cpr"] as? JsonObject)?.let { KpiCpr(it.long("target_bp"), it.long("alloc_mtk"), it.long("achieved_bp")) },
        )

        private fun star(o: JsonObject): RouteStar? = RouteStar(
            o.long("programme_id") ?: return null, o.str("name_en") ?: return null, o.str("name_bn"), o.str("month") ?: return null, o.str("through_date") ?: return null, o.str("as_of"),
            o.str("sr_name"), o.str("sr_code"), o.str("route_name") ?: return null, o.int("lmwd") ?: return null, o.int("wd") ?: return null, o.int("ewd") ?: return null,
            AsthaModel.decimal3(o["target"]) ?: return null, AsthaModel.decimal3(o["std"]) ?: return null, AsthaModel.decimal3(o["projection"]), o.long("achievement_bp"),
            AsthaModel.decimal3(o["lms"]) ?: return null, AsthaModel.decimal3(o["lmads"]), AsthaModel.decimal3(o["pads"]), o.long("growth_bp"),
            o.str("status_en") ?: return null, o.str("status_bn") ?: return null, o.str("achieved_slab"), o.long("slab_amount_mtk"), o.str("projected_slab"), o.long("projected_amount_mtk"),
        )

        private fun figure(o: JsonObject?): CampaignFigure? = o?.let { CampaignFigure(it.long("target") ?: return null, it.long("achieved") ?: return null, it.long("remaining") ?: return null) }

        private fun campaign(e: JsonElement): RouteCampaign? {
            val o = e as? JsonObject ?: return null
            return RouteCampaign(
                o.long("outlet_id") ?: return null, o.long("programme_id") ?: return null, o.str("label_en") ?: return null, o.str("label_bn"), o.str("month") ?: return null, o.str("through_date") ?: return null, o.str("as_of"),
                figure(o["std"] as? JsonObject) ?: return null, figure(o["memo"] as? JsonObject) ?: return null,
                o.list("skus").mapNotNull { s -> (s as? JsonObject)?.let { CampaignSku(it.long("sku_id") ?: return@mapNotNull null, it.long("target_qty_base") ?: return@mapNotNull null, it.long("sold_qty_base") ?: return@mapNotNull null) } },
            )
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.takeIf { s -> s != "null" }?.toLongOrNull() }
        private fun JsonObject.int(k: String): Int? = long(k)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.list(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()
    }
}
