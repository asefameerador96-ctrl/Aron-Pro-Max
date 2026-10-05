package com.aktcl.aron.rules

/**
 * Money is a Long count of milli-taka (mtk): 1 Tk = 1,000 mtk, 1 paisa = 10 mtk (docs/24 s7.1, D-15).
 * Day-1 seed of the money rules; the shared lane adds memo totals, discount lines and the rest of s7.
 */
object Money {
    const val MTK_PER_TAKA: Long = 1_000L
    const val MTK_PER_PAISA: Long = 10L

    /** Integer division rounding half away from zero (docs/24 s7.3, s7.6). [divisor] must be positive. */
    fun divHalfUp(dividend: Long, divisor: Long): Long {
        require(divisor > 0) { "divisor must be positive" }
        val q = dividend / divisor
        val r = dividend % divisor
        return when {
            r == 0L -> q
            dividend > 0 -> if (2 * r >= divisor) q + 1 else q
            else -> if (2 * -r >= divisor) q - 1 else q
        }
    }

    /** Rounds an mtk amount to a whole paisa, half away from zero (cfg.memo.rounding_mode = half_up_paisa). */
    fun roundToPaisaHalfUp(mtk: Long): Long = divHalfUp(mtk, MTK_PER_PAISA) * MTK_PER_PAISA

    /** Line gross in mtk: div_half_up(qty_base x base_price_mtk, price_per_qty) (docs/24 s7.3, s7.6). */
    fun lineGrossMtk(qtyBase: Long, basePriceMtk: Long, pricePerQty: Long = 1L): Long =
        divHalfUp(checkedMul(qtyBase, basePriceMtk), pricePerQty)

    /** Formats mtk as taka with [decimals] (0..3) digits, half away from zero, `,` thousands grouping: 4_391_000 -> "4,391.00". */
    fun formatTaka(mtk: Long, decimals: Int = 2): String {
        require(decimals in 0..3) { "decimals must be 0..3" }
        val unit = when (decimals) { 0 -> 1_000L; 1 -> 100L; 2 -> 10L; else -> 1L }
        val scaled = divHalfUp(mtk, unit)
        val negative = scaled < 0
        val abs = if (negative) -scaled else scaled
        val scale = when (decimals) { 0 -> 1L; 1 -> 10L; 2 -> 100L; else -> 1_000L }
        val whole = (abs / scale).toString().reversed().chunked(3).joinToString(",").reversed()
        val frac = if (decimals == 0) "" else "." + (abs % scale).toString().padStart(decimals, '0')
        return (if (negative) "-" else "") + whole + frac
    }
}
