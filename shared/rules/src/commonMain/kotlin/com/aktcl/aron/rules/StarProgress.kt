package com.aktcl.aron.rules

import java.math.BigInteger

/**
 * Match Box Lifting Star monthly snapshot (manual screen 62; lane T-KPI1, N-8; D-TKPI-07). Exact rational arithmetic on integers, rounded ONCE at the
 * end, half away from zero (so -39.865 prints -39.87), never a floating point. Formulas, proven against the owner's screenshot:
 *   PADS = STD / EWD        Projection = PADS x WD        Achievement = Projection / Target
 *   LMADS = LMS / LMWD      Growth = PADS / LMADS - 1
 * STD is the programme quantity sold from the 1st to the last closed day, LMS the same over last month, both in the programme's unit
 * (base units divided by [Input.unitDivisor], default 1). Screenshot (Target 20.00, STD 2.833, EWD 10, WD 26, LMS 12.25, LMWD 26) gives
 * projection 7.366 (shown 7.37), achievement 36.83%, LMADS 0.471, PADS 0.283, growth -39.87%.
 */
object StarProgress {
    /** [targetMilli] is the target in thousandths of the programme unit; the quantities are base units sold; days are working days. */
    data class Input(val targetMilli: Long, val stdBase: Long, val lmsBase: Long, val unitDivisor: Long, val ewd: Int, val wd: Int, val lmwd: Int) {
        init {
            require(targetMilli >= 0 && stdBase >= 0 && lmsBase >= 0 && unitDivisor >= 1 && ewd >= 0 && wd >= 0 && lmwd >= 0) { "star inputs must be >= 0 and the divisor >= 1" }
        }
    }

    /** Thousandths for the Decimal3 members; basis points for the percentages (growth is signed). Null where a divisor is 0. */
    data class Result(
        val targetMilli: Long, val stdMilli: Long, val projectionMilli: Long?, val achievementBp: Long?, val actualBp: Long?,
        val lmsMilli: Long, val lmadsMilli: Long?, val padsMilli: Long?, val growthBp: Long?,
    )

    private fun big(v: Long) = BigInteger.valueOf(v)

    /** round(n / d) half away from zero; d > 0. */
    fun roundDiv(n: BigInteger, d: BigInteger): BigInteger {
        require(d.signum() > 0) { "divisor must be positive" }
        val two = BigInteger.TWO
        val a = n.abs().multiply(two).add(d).divide(d.multiply(two))
        return if (n.signum() < 0) a.negate() else a
    }

    fun compute(i: Input): Result {
        val dv = big(i.unitDivisor)
        // All in milli-units: value_milli = base * 1000 / divisor.
        val std = roundDiv(big(i.stdBase).multiply(big(1000)), dv)
        val lms = roundDiv(big(i.lmsBase).multiply(big(1000)), dv)
        val ewd = big(i.ewd.toLong()); val wd = big(i.wd.toLong()); val lmwd = big(i.lmwd.toLong())
        val tgt = big(i.targetMilli)
        // PADS(milli) = stdBase*1000 / (divisor*ewd)
        val pads = if (i.ewd == 0) null else roundDiv(big(i.stdBase).multiply(big(1000)), dv.multiply(ewd))
        // Projection(milli) = stdBase*1000*wd / (divisor*ewd)
        val projNum = big(i.stdBase).multiply(big(1000)).multiply(wd)
        val projDen = dv.multiply(ewd)
        val proj = if (i.ewd == 0) null else roundDiv(projNum, projDen)
        // Achievement bp = projection / target = (stdBase*1000*wd*10000) / (divisor*ewd*targetMilli)
        val ach = if (i.ewd == 0 || i.targetMilli == 0L) null else roundDiv(projNum.multiply(big(10_000)), projDen.multiply(tgt)).toLong()
        val actual = if (i.targetMilli == 0L) null else roundDiv(big(i.stdBase).multiply(big(1000)).multiply(big(10_000)), dv.multiply(tgt)).toLong()
        val lmads = if (i.lmwd == 0) null else roundDiv(big(i.lmsBase).multiply(big(1000)), dv.multiply(lmwd))
        // Growth bp = (PADS / LMADS - 1) x 10000 = (stdBase*lmwd / (ewd*lmsBase) - 1) x 10000
        val growth = if (i.ewd == 0 || i.lmwd == 0 || i.lmsBase == 0L) null else {
            val num = big(i.stdBase).multiply(lmwd); val den = ewd.multiply(big(i.lmsBase))
            roundDiv(num.subtract(den).multiply(big(10_000)), den).toLong()
        }
        return Result(i.targetMilli, std.toLong(), proj?.toLong(), ach, actual, lms.toLong(), lmads?.toLong(), pads?.toLong(), growth)
    }

    /** One slab rung of `programme.rules.slabs`: reached when the percentage in basis points is at least [minBp]. */
    data class Rung(val label: String, val minBp: Long, val amountMtk: Long)

    /** The highest rung with minBp <= [bp]; null when [bp] is null or no rung is reached. */
    fun rungFor(rungs: List<Rung>, bp: Long?): Rung? = if (bp == null) null else rungs.filter { it.minBp <= bp }.maxByOrNull { it.minBp }

    /** A status line of `programme.rules.status_rules`: the highest [minBp] not above the projection wins. */
    data class StatusRule(val minBp: Long, val en: String, val bn: String)

    val DEFAULT_STATUS: List<StatusRule> = listOf(StatusRule(0, "Keep Pushing", "চালিয়ে যান"), StatusRule(10_000, "On Track", "লক্ষ্যের পথে"))

    fun status(rules: List<StatusRule>, projectionBp: Long?): StatusRule {
        val list = rules.ifEmpty { DEFAULT_STATUS }
        val bp = projectionBp ?: 0L
        return list.filter { it.minBp <= bp }.maxByOrNull { it.minBp } ?: list.minByOrNull { it.minBp }!!
    }
}
