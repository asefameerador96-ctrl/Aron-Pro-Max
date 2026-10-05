package com.aktcl.aron.rules

/** Redemption arithmetic of docs/24 s4.14.2: `points_total = cash points + sum(line points)`, `cash_mtk = cash points x rate`. */
object LoyaltyMath {
    /** Cash value in mtk of [cashPoints] at [rateMtkPerPoint] (default 2,000 mtk = 2 Tk a point); exact integer maths. */
    fun cashMtk(cashPoints: Long, rateMtkPerPoint: Long = 2_000L): Long {
        require(cashPoints >= 0 && rateMtkPerPoint >= 0) { "cashPoints and rate must be >= 0" }
        return checkedMul(cashPoints, rateMtkPerPoint)
    }

    /** Total points of a redemption: cash points plus every gift line's points. */
    fun pointsTotal(cashPoints: Long, linePoints: List<Long>): Long {
        require(cashPoints >= 0 && linePoints.all { it >= 0 }) { "points must be >= 0" }
        return linePoints.fold(cashPoints) { a, p -> checkedAdd(a, p) }
    }

    /** True when the phone's stated totals equal the recomputed ones and cash points are within [cashMaxPoints] (else `arithmetic_mismatch`). */
    fun isConsistent(
        statedPointsTotal: Long, statedCashMtk: Long, cashPoints: Long, linePoints: List<Long>,
        rateMtkPerPoint: Long = 2_000L, cashMaxPoints: Long = 199L,
    ): Boolean = cashPoints <= cashMaxPoints &&
        statedPointsTotal == pointsTotal(cashPoints, linePoints) && statedCashMtk == cashMtk(cashPoints, rateMtkPerPoint)
}
