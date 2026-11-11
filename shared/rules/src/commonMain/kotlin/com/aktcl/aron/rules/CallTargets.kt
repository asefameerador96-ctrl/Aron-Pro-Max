package com.aktcl.aron.rules

/**
 * The AMO Dashboard target cards (AMO build contract s4.3; D-AMO-MAN-06). Long arithmetic, one rounding per figure.
 * The monthly targets are the config numbers `cfg.amo.call_target_month`, `control_call_target_month` and
 * `joint_call_target_month` (null = not set, the card prints [Percent.DASH]).
 */
object CallTargets {
    /**
     * Today's share of a monthly call target: round_half_up(monthly / workingDays) = (2 monthly + W) div (2 W), at least 1
     * for a target above zero (a day with a positive monthly target always asks for one call). Null when [monthly] is null
     * (unset); 0 when [monthly] is 0. A month with no working days counts as 1.
     */
    fun todayTarget(monthly: Long?, workingDays: Int): Long? {
        if (monthly == null) return null
        require(monthly >= 0L) { "target must be >= 0: $monthly" }
        if (monthly == 0L) return 0L
        val w = maxOf(workingDays, 1).toLong()
        return maxOf((checkedAdd(checkedMul(2L, monthly), w)) / (2L * w), 1L)
    }
}
