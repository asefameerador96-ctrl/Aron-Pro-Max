package com.aktcl.aron.feature.amo

/** Memo find (screenshot 42): the outlet drop-down shows the outlet's total outstanding next to "বাকি" so a due outlet stands out. */
object MemoFind {
    /**
     * The outlet's outstanding due: the bundle's `open_due_mtk` (the server's figure as of [openDueAsOf]) plus the dues of this phone's own live
     * memos dated AFTER that day (memos of the as-of day or earlier are already in the server figure, so adding them would count them twice).
     * With no as-of date nothing in the bundle figure can be assumed, so every local due is added. A credit (negative) due is never subtracted.
     */
    fun outstandingMtk(openDueMtk: Long, openDueAsOf: String?, localDues: List<Pair<String, Long>>): Long {
        val asOf = openDueAsOf?.take(10)
        return openDueMtk.coerceAtLeast(0) + localDues.filter { (date, _) -> asOf == null || date > asOf }.sumOf { it.second.coerceAtLeast(0) }
    }
}
