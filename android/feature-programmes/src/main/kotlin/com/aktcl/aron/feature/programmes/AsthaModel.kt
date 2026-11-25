package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.AsthaQuarters
import com.aktcl.aron.rules.EntrySpec
import com.aktcl.aron.rules.EntryUnits
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.QtyUnit
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

enum class PackKind { PACKET, DOZEN, BOX, BASE, UNKNOWN }

/** [packs] whole packs and [looseMilli] loose base units (thousandths) of one pack size. */
data class PackLine(val packFactor: Int, val packs: Long, val looseMilli: Long, val kind: PackKind, val baseKey: String = "")

/** Achievement of one pack size of a brand in base units (thousandths as sent), SRM-B-63: one line per pack size, never summed across sizes. */
data class AsthaPack(val packFactor: Int, val stdAchievedMilli: Long)

/**
 * A month kept from an online read: the server's `as_of` of the read (shown) and the Dhaka DATE of that read (`YYYY-MM-DD`, the same server field, compared with the month's last day by calendar).
 * [clean] was recorded when the read request was SENT, under the sync lock: this phone held no memo of the month that was pending, sent and unanswered, quarantined, or acknowledged after its last bundle
 * request (null on a file older than this record: not clean). [awaiting] is the number of this phone's own sale memos of the month that were NOT acknowledged then (not in the read's figure); it is
 * shown until a newer read is stored, even when those memos are acknowledged later.
 */
data class FetchedMonth(val asOf: String?, val readDate: String?, val clean: Boolean? = null, val awaiting: Int = 0) {
    /** True when the read was taken after the month's last day (a calendar comparison of dates; an unknown read date is not). */
    fun endedAtRead(month: String): Boolean = readDate != null && readDate > java.time.YearMonth.parse(month).atEndOfMonth().toString()

    /** A month is FINAL only when it had ended when it was read AND this phone had nothing of it outstanding when the read was sent. A month that is not final is read again whenever the page is opened online. */
    fun isFinal(month: String): Boolean = endedAtRead(month) && clean == true
}

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

/** What the bundle says about how one SKU is counted (`products.skus[]`: category, base unit, base units per pack, default entry unit); defaults by category when the bundle omits them. */
data class SkuUnit(val category: String, val baseUnit: String, val basePerPack: Int, val entryDefault: String) {
    /** The entry spec of this SKU by the shared rule of the Sale screens. */
    fun entry(): Pair<String, EntrySpec> = category to EntryUnits.resolve(baseUnit, basePerPack, entryDefault)
}

/** A brand's names from the bundle's product tree (`products.nodes`, level brand). */
data class BrandName(val en: String, val bn: String?) {
    fun text(locale: UiLocale): String = if (locale == UiLocale.BN) bn?.takeIf { it.isNotBlank() } ?: en else en
}

/** What the Astha screens read: all rows, the display-only "as of", the cut flag and the brand names. */
data class AsthaData(val rows: List<AsthaRow>, val asOf: String?, val truncated: Boolean, val brands: Map<Long, BrandName>, val brandCategory: Map<Long, String> = emptyMap(), val skuBrand: Map<Long, Long> = emptyMap(), val skuPack: Map<Long, Int> = emptyMap(), val skuUnits: Map<Long, SkuUnit> = emptyMap(),
    /** The months the bundle says did not fit its bound (`astha_truncated_months`); empty when the server sends none. */
    val truncatedMonths: Set<String> = emptySet(),
    /** Months whose rows were read online and kept on the phone (month to the server's `as_of` of that read): their figures are the server's as of that read, with no local arithmetic. */
    val fetchedMonths: Map<String, FetchedMonth> = emptyMap(),
) {
    /** The "data as of" of a month: the read's own time for a fetched month, else the bundle's. */
    fun asOfOf(month: String): String? = if (month in fetchedMonths) fetchedMonths[month]?.asOf else asOf

    /** True when the bundle cut this month's rows short: its rows are shown with a "list cut short" note and never replaced by a read (the bundle month always comes from the bundle). */
    fun cutShort(month: String): Boolean = month !in fetchedMonths && cutByBundle(month)

    /** True when a month came from an online read that is not final (read before the month ended, or with memos of this phone outstanding): the figure can have grown. */
    fun readNotFinal(month: String): Boolean = fetchedMonths[month]?.isFinal(month) == false

    /** The bundle says it cut this month: it lists the month, or (an older server that lists none) sets the plain `astha_truncated` flag and holds rows of the month. */
    fun cutByBundle(month: String): Boolean = if (truncatedMonths.isEmpty()) truncated && rows.any { it.month == month } else month in truncatedMonths

    /** Months of the quarter the phone knows nothing of (no row, and no earlier online read that found none). */
    fun missingMonths(months: List<String>): List<String> = months.filter { m -> m !in fetchedMonths && rows.none { it.month == m } }

    /** True when the quarter is worth reading online: a month the phone knows nothing of, or one that was read before its last day (a month is final only when read after it ended with nothing of this phone outstanding). */
    fun wantsRead(months: List<String>): Boolean = missingMonths(months).isNotEmpty() || months.any { readNotFinal(it) }

    /** The pack line of a brand's achievement in the units the field counts (rule 5): packets of sticks, dozens or boxes by the brand's category. */
    fun packLine(brandId: Long?, p: AsthaPack): PackLine {
        if (brandId == null) return AsthaModel.packLineOf(null, p)
        // the SKUs of this brand with this pack size decide the unit through the SHARED rule (EntryUnits.resolve, the one the Sale screens use); a brand or size with no SKU, or SKUs
        // that disagree, is unknown: a neutral number, never a unit word that may be wrong
        val units = skuUnits.filter { (sku, _) -> skuBrand[sku] == brandId }.values.filter { it.basePerPack == p.packFactor }.mapNotNull { u -> runCatching { u.entry() }.getOrNull() }.distinct() // a base unit the shared rule does not know is neutral, never a crash in composition
        val one = units.singleOrNull() ?: return AsthaModel.packLineOf(null, p)
        return AsthaModel.packLineOf(one, p)
    }

    /** The word key (`stick`, `piece`, `dozen`) of the base unit this brand's figures are stored in, or null when unknown (no SKU, or SKUs of different categories). */
    fun baseUnitKey(brandId: Long?): String? = skuUnits.filter { (sku, _) -> skuBrand[sku] == brandId }.values.map { it.category }.distinct().singleOrNull()?.let { EntryUnits.baseLabelKey(it) }

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
        val cutMonths = (prog["astha_truncated_months"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { m -> Regex("^\\d{4}-(0[1-9]|1[0-2])$").matches(m) } }.toSet()
        return AsthaData(rows, asOf, truncated, brands(productsJson), brandCategories(productsJson), skuBrands(productsJson), skuPackFactors(productsJson), skuUnits(productsJson), cutMonths)
    }

    /** sku id -> its brand node id: the SKU's variant node, walked up the product tree to the brand level; SKUs whose chain has no brand are left out. */
    fun skuBrands(productsJson: String?): Map<Long, Long> {
        val p = productsJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
        val parents = (p["nodes"] as? JsonArray).orEmpty().mapNotNull { n ->
            val o = n as? JsonObject ?: return@mapNotNull null
            (o.long("id") ?: return@mapNotNull null) to Pair(o.str("level")?.lowercase().orEmpty(), o.long("parent_id"))
        }.toMap()
        fun brandOf(id: Long): Long? { var cur: Long? = id; var guard = 0; while (cur != null && guard++ < 8) { val n = parents[cur] ?: return null; if (n.first == "brand") return cur; cur = n.second }; return null }
        return (p["skus"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val sku = o.long("sku_id") ?: o.long("id") ?: return@mapNotNull null
            val variant = o.long("variant_id") ?: return@mapNotNull null
            brandOf(variant)?.let { sku to it }
        }.toMap()
    }

    /**
     * brand node id -> the category code of its SKUs (`products.skus[].category_code`, the seed sets no code on the category nodes), lower case.
     * A brand whose SKUs disagree, or that has none, is left out: its unit is then not guessed.
     */
    fun brandCategories(productsJson: String?): Map<Long, String> {
        val p = productsJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
        val skuBrand = skuBrands(productsJson)
        val perBrand = HashMap<Long, MutableSet<String>>()
        (p["skus"] as? JsonArray).orEmpty().forEach { e ->
            val o = e as? JsonObject ?: return@forEach
            val sku = o.long("sku_id") ?: o.long("id") ?: return@forEach
            val brand = skuBrand[sku] ?: return@forEach
            val cat = o.str("category_code")?.lowercase()?.takeIf { it.isNotBlank() } ?: return@forEach
            perBrand.getOrPut(brand) { HashSet() }.add(cat)
        }
        return perBrand.filter { it.value.size == 1 }.mapValues { it.value.first() }
    }

    /** sku id -> base units in one pack (`products.skus[].base_per_pack`), the grouping the server uses for `by_pack`. */
    private fun skuPackFactors(productsJson: String?): Map<Long, Int> {
        val p = productsJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
        return (p["skus"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val sku = o.long("sku_id") ?: o.long("id") ?: return@mapNotNull null
            sku to (o.long("base_per_pack")?.toInt()?.takeIf { it >= 1 } ?: return@mapNotNull null)
        }.toMap()
    }

    /** sku id -> its counting rule; a SKU with no base unit or entry default in the bundle gets the defaults of its category (cigarette and bidi: sticks counted in packets, lighter: pieces in boxes, match: dozens). */
    private fun skuUnits(productsJson: String?): Map<Long, SkuUnit> {
        val p = productsJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
        return (p["skus"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val sku = o.long("sku_id") ?: o.long("id") ?: return@mapNotNull null
            val cat = o.str("category_code")?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val base = o.str("base_unit") ?: when (cat) { "lighter" -> "piece"; "match" -> "dozen"; else -> "stick" }
            val per = o.long("base_per_pack")?.toInt()?.takeIf { it >= 1 } ?: 1
            val entry = o.str("entry_unit_default") ?: if (cat == "match") "dozen" else "pack"
            sku to SkuUnit(cat, base, per, entry)
        }.toMap()
    }

    private val log = java.util.logging.Logger.getLogger("AsthaModel")

    /**
     * One pack size of a brand in the units the field counts, decided by the SHARED rule ([EntryUnits.resolve], the same the Sale screens use): a PACK entry unit gives packets
     * (cigarette, bidi) or boxes (lighter) with loose base units, a dozen base unit gives dozens (whole and fractional, no loose pieces), and a base unit counted one at a time gives
     * that base unit's word ([PackKind.BASE]). [entry] null (unknown category or SKU) prints the number in neutral base units and is logged, never a unit word that may be wrong.
     */
    fun packLineOf(entry: Pair<String, EntrySpec>?, p: AsthaPack): PackLine {
        if (entry == null) { log.log(java.util.logging.Level.WARNING, "astha_pack_unknown_category", p.packFactor); return PackLine(p.packFactor, p.stdAchievedMilli / 1000L, p.stdAchievedMilli % 1000L, PackKind.UNKNOWN) }
        val (category, spec) = entry
        // a match counted in packs of more than one base unit is not a case the field uses (the dozen IS the match's base unit): neutral, never a wrong word
        if (category == "match" && spec.unit == QtyUnit.PACK) return packLineOf(null, p)
        return when (spec.unit) {
            QtyUnit.PACK -> { val unit = p.packFactor * 1000L; PackLine(p.packFactor, p.stdAchievedMilli / unit, p.stdAchievedMilli % unit, if (category == "lighter") PackKind.BOX else PackKind.PACKET, EntryUnits.baseLabelKey(category)) }
            QtyUnit.DOZEN -> PackLine(p.packFactor, p.stdAchievedMilli / 1000L, p.stdAchievedMilli % 1000L, PackKind.DOZEN, "dozen")
            else -> PackLine(p.packFactor, p.stdAchievedMilli / 1000L, p.stdAchievedMilli % 1000L, PackKind.BASE, EntryUnits.baseLabelKey(category))
        }
    }

    /** The unit of a pack size when only the category is known (kept for callers without SKUs): the category's defaults through the same shared rule. */
    fun packLine(category: String?, p: AsthaPack): PackLine = packLineOf(
        when (category) {
            "cigarette", "bidi" -> category to EntryUnits.resolve("stick", p.packFactor, "pack")
            "lighter" -> category to EntryUnits.resolve("piece", p.packFactor, "pack")
            "match" -> category to EntryUnits.resolve("dozen", p.packFactor, "dozen")
            else -> null
        }, p,
    )

    fun brands(productsJson: String?): Map<Long, BrandName> {
        val p = productsJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
        return (p["nodes"] as? JsonArray).orEmpty().mapNotNull { n ->
            val o = n as? JsonObject ?: return@mapNotNull null
            if (o.str("level")?.lowercase() != "brand") return@mapNotNull null
            val id = o.long("id") ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            id to BrandName(name, o.str("name_bn"))
        }.toMap()
    }

    internal fun row(o: JsonObject): AsthaRow? {
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

    // The quarter naming is ONE shared function (shared rules AsthaQuarters, the server's own algorithm): the phone never re-derives it.

    /** The year of the quarter key a month belongs to (the year of the quarter's first month). */
    fun quarterYearOf(month: String, startMonth: Int = 1): Int = AsthaQuarters.of(month, startMonth)?.year ?: month.take(4).toInt()

    /** The quarter a month belongs to, as the screen's `Q-n` (n counts from the start month; default January). */
    fun quarterOf(month: String, startMonth: Int = 1): Int = AsthaQuarters.of(month, startMonth)?.index ?: 1

    /** The three month keys ("2026-01") of quarter [q] (1..4) of key year [year] for a start month, as the server names them. */
    fun monthsOfQuarter(year: Int, q: Int, startMonth: Int = 1): List<String> = AsthaQuarters.parse(quarterKey(year, q), startMonth)?.months.orEmpty()

    /** The quarter key `YYYY-Qn` the server uses. */
    fun quarterKey(year: Int, q: Int): String = "%04d-Q%d".format(java.util.Locale.ROOT, year, q)
}
