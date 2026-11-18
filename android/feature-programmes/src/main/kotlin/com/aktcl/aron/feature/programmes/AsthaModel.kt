package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Achievement of one pack size of a brand in base units (thousandths as sent), SRM-B-63: one line per pack size, never summed across sizes. */
data class AsthaPack(val packFactor: Int, val stdAchievedMilli: Long)

/**
 * One Astha target line of the stored bundle section `programmes.astha_targets` (contract 1.15.0). `outletId` null is the route row;
 * `brandId` null is the All Brand memo row. STD is kept in thousandths (Decimal3 parsed exactly, no floating point); money is integer
 * milli-taka (rule 5 and the money rule).
 */
data class AsthaRow(
    val routeId: Long, val outletId: Long?, val brandId: Long?, val month: String,
    val stdTargetMilli: Long, val stdAchievedMilli: Long, val memoTarget: Long, val memoAchieved: Long,
    val valueTargetMtk: Long, val valueAchievedMtk: Long, val byPack: List<AsthaPack>,
)

/** A brand's names from the bundle's product tree (`products.nodes`, level brand). */
data class BrandName(val en: String, val bn: String?) {
    fun text(locale: UiLocale): String = if (locale == UiLocale.BN) bn?.takeIf { it.isNotBlank() } ?: en else en
}

/** What the Astha screens read: all rows, the display-only "as of", the cut flag and the brand names. */
data class AsthaData(val rows: List<AsthaRow>, val asOf: String?, val truncated: Boolean, val brands: Map<Long, BrandName>) {
    /** The route rows (no outlet), the screen of the "route info" tab. */
    val routeRows: List<AsthaRow> get() = rows.filter { it.outletId == null }

    /** The months the data holds for the route or an outlet, ascending ("2026-11"). */
    fun months(outletId: Long? = null): List<String> = rows.filter { it.outletId == outletId }.map { it.month }.distinct().sorted()

    /** Brand rows (brand id set) of a month for the route or an outlet, ordered by brand name. */
    fun brandRows(month: String, outletId: Long? = null, locale: UiLocale = UiLocale.EN): List<AsthaRow> =
        rows.filter { it.outletId == outletId && it.month == month && it.brandId != null }.sortedBy { brandName(it.brandId, locale) }

    /** The All Brand memo row of a month (brand id null), or null. */
    fun allBrandRow(month: String, outletId: Long? = null): AsthaRow? = rows.firstOrNull { it.outletId == outletId && it.month == month && it.brandId == null }

    fun brandName(id: Long?, locale: UiLocale): String = id?.let { brands[it]?.text(locale) } ?: id?.let { "#$it" } ?: ""

    /** Distinct outlets that have Astha rows (the shop-wise tab). */
    val outletIds: List<Long> get() = rows.mapNotNull { it.outletId }.distinct()
}

object AsthaModel {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses the stored `programmes` section (raw JSON text, null = never downloaded or no active programme) and the stored `products`
     * section for the brand names. Anything malformed in one row is skipped, never a crash; a section that is not an object is null.
     */
    fun parse(programmesJson: String?, productsJson: String?): AsthaData? {
        val prog = programmesJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        val rows = (prog["astha_targets"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::row) }
        val truncated = (prog["astha_truncated"] as? JsonPrimitive)?.booleanOrNull ?: false
        val asOf = (prog["astha_as_of"] as? JsonPrimitive)?.contentOrNull
        return AsthaData(rows, asOf, truncated, brands(productsJson))
    }

    private fun brands(productsJson: String?): Map<Long, BrandName> {
        val p = productsJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
        return (p["nodes"] as? JsonArray).orEmpty().mapNotNull { n ->
            val o = n as? JsonObject ?: return@mapNotNull null
            if (o.str("level")?.lowercase() != "brand") return@mapNotNull null
            val id = o.long("id") ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            id to BrandName(name, o.str("name_bn"))
        }.toMap()
    }

    private fun row(o: JsonObject): AsthaRow? {
        val route = o.long("route_id") ?: return null
        val month = o.str("month")?.takeIf { Regex("^\\d{4}-(0[1-9]|1[0-2])$").matches(it) } ?: return null
        val packs = (o["by_pack"] as? JsonArray).orEmpty().mapNotNull { e ->
            val po = e as? JsonObject ?: return@mapNotNull null
            val f = po.long("pack_factor")?.toInt()?.takeIf { it >= 1 } ?: return@mapNotNull null
            AsthaPack(f, decimal3(po["std_achieved"]) ?: return@mapNotNull null)
        }.sortedBy { it.packFactor }
        return AsthaRow(
            route, o.long("outlet_id"), o.long("brand_id"), month,
            decimal3(o["std_target"]) ?: 0L, decimal3(o["std_achieved"]) ?: 0L, o.long("memo_target") ?: 0L, o.long("memo_achieved") ?: 0L,
            o.long("value_target_mtk") ?: 0L, o.long("value_achieved_mtk") ?: 0L, packs,
        )
    }

    /** "88235.294" -> 88235294 (thousandths) exactly; a bad text is null. */
    fun decimal3(e: JsonElement?): Long? {
        val s = (e as? JsonPrimitive)?.contentOrNull ?: return null
        val m = Regex("^(-?)(\\d{1,13})(?:\\.(\\d{1,3}))?$").matchEntire(s) ?: return null
        val frac = (m.groupValues[3]).padEnd(3, '0')
        val v = m.groupValues[2].toLong() * 1000L + frac.toLong()
        return if (m.groupValues[1] == "-") -v else v
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    /** Percent in hundredths of a percent, half up; 0 when the target is 0 (the contract's rule for the Astha rows), never a dash. */
    fun pctBp(achieved: Long, target: Long): Long = if (target <= 0L || achieved <= 0L) 0L else Percent.bp(achieved, target) ?: 0L

    /** "104.94%" in the locale's digits. */
    fun pctText(achieved: Long, target: Long, locale: UiLocale): String = Percent.text(pctBp(achieved, target), 2, locale)

    /** The remaining part (target minus achieved, never negative) in the same unit. */
    fun remaining(target: Long, achieved: Long): Long = (target - achieved).coerceAtLeast(0L)

    /** STD thousandths as text: whole STD with up to 3 decimals trimmed ("2240", "12.5"), grouped, digits per [locale]. */
    fun stdText(milli: Long, locale: UiLocale): String {
        val a = if (milli < 0) -milli else milli
        val whole = Formats.integer(a / 1000L, locale)
        val frac = (a % 1000L).toString().padStart(3, '0').trimEnd('0')
        return (if (milli < 0) "-" else "") + whole + if (frac.isEmpty()) "" else "." + Formats.digits(frac, locale)
    }

    /** A count (memos) with grouping and digits per [locale]. */
    fun countText(n: Long, locale: UiLocale): String = Formats.integer(n, locale)

    /**
     * Value in milli-taka as text with the taka sign after the amount, digits per [locale]. A whole-taka amount has no decimals (it has to fit a
     * narrow column); any fraction is shown in full (two decimals), so nothing is rounded away.
     */
    fun valueText(mtk: Long, locale: UiLocale): String = Formats.money(mtk, locale, decimals = if (mtk % 1000L == 0L) 0 else 2)

    /** The quarter a month belongs to, as the screen's `Q-n` (quarter start month January by default, cfg.astha.quarter_start_month is 1 unless set). */
    fun quarterOf(month: String, startMonth: Int = 1): Int {
        val m = month.substring(5, 7).toInt()
        return ((m - startMonth + 12) % 12) / 3 + 1
    }

    /** The three month keys ("2026-01") of quarter [q] (1..4) of [year] for a quarter start month (default January). */
    fun monthsOfQuarter(year: Int, q: Int, startMonth: Int = 1): List<String> = (0..2).map { i ->
        val idx = (startMonth - 1) + (q - 1) * 3 + i
        val y = year + idx / 12
        val m = idx % 12 + 1
        "%04d-%02d".format(y, m)
    }
}
