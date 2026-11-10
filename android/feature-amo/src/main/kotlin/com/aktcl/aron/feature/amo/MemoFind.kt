package com.aktcl.aron.feature.amo

import java.time.Instant

/** One live memo of this phone for the outlet, with what [MemoFind] needs to tell whether the server figure already holds it. */
data class LocalDue(
    val uuid: String,
    val businessDate: String,
    val committedAtIso: String,
    val dueMtk: Long,
    /** The memo this one replaced (an edit), if any. */
    val supersedesUuid: String? = null,
)

/** Memo find (screenshot 42): the outlet drop-down shows the outlet's total outstanding next to "বাকি" so a due outlet stands out. */
object MemoFind {
    /**
     * The outlet's outstanding due: the bundle's `open_due_mtk` (the server's figure as of [openDueAsOf]) plus what this phone's own memos add to it.
     *
     * The server figure holds a memo when it was committed at or before the as-of instant (a date-only as-of means the end of that business day).
     * A live memo committed after it adds its due; when it is an EDIT of earlier memos, the due of the latest earlier version the figure already
     * holds is taken off first, so the edit counts once, not twice and not as a second debt. With no as-of nothing can be assumed to be in the
     * figure, so every live memo's due is added and no edit is netted. A credit (negative) is never subtracted; the total never goes below zero.
     * [all] holds every memo of the outlet on the phone, superseded ones included (they only serve to net an edit).
     */
    fun outstandingMtk(openDueMtk: Long, openDueAsOf: String?, all: List<LocalDue>): Long {
        val byId = all.associateBy { it.uuid }
        val replaced = all.mapNotNull { it.supersedesUuid }.toSet()
        val live = all.filter { it.uuid !in replaced }
        fun inFigure(m: LocalDue): Boolean = when {
            openDueAsOf == null -> false
            openDueAsOf.length <= 10 -> m.businessDate <= openDueAsOf
            else -> runCatching { !Instant.parse(m.committedAtIso).isAfter(Instant.parse(openDueAsOf)) }.getOrDefault(m.businessDate <= openDueAsOf.take(10))
        }
        var extra = 0L
        for (m in live) {
            if (inFigure(m)) continue
            var held = 0L
            var prev = m.supersedesUuid?.let(byId::get)
            var guard = 0
            while (prev != null && guard++ < 50) { if (inFigure(prev)) { held = prev.dueMtk.coerceAtLeast(0); break }; prev = prev.supersedesUuid?.let(byId::get) }
            extra += (m.dueMtk.coerceAtLeast(0) - held)
        }
        return (openDueMtk.coerceAtLeast(0) + extra).coerceAtLeast(0)
    }
}
