package com.aktcl.aron.feature.amo

import java.time.Instant

/** One memo of this phone for the outlet (superseded ones too), with the collections (Mark Paid) recorded against it: (captured-at ISO, amount in milli-taka). */
data class LocalDue(
    val uuid: String,
    val businessDate: String,
    val committedAtIso: String,
    val dueMtk: Long,
    /** The memo this one replaced (an edit), if any. */
    val supersedesUuid: String? = null,
    val collections: List<Pair<String, Long>> = emptyList(),
)

/** Memo find (screenshot 42): the outlet drop-down shows the outlet's total outstanding next to "বাকি" so a due outlet stands out. */
object MemoFind {
    /**
     * The outlet's outstanding due: the bundle's `open_due_mtk` (the server's figure as of [openDueAsOf]) corrected by every memo and collection
     * this phone holds for the outlet ([all]: every day, superseded versions included, with their Mark Paid records).
     *
     * An edit chain is one memo: its due is the latest version's, less everything collected against any version. The server figure is taken to
     * hold a version committed at or before the as-of instant (a date-only as-of = the end of that business day) and a collection captured at or
     * before it; what the chain contributes is its current open due minus what the figure already holds of it, so an edit, an earlier day's memo
     * and a later Mark Paid each count once. With no as-of nothing can be assumed held. A credit memo (negative due) is never subtracted;
     * the total never goes below zero.
     */
    fun outstandingMtk(openDueMtk: Long, openDueAsOf: String?, all: List<LocalDue>): Long {
        val byId = all.associateBy { it.uuid }
        val replaced = all.mapNotNull { it.supersedesUuid }.toSet()
        fun inFigure(iso: String, date: String?): Boolean = when {
            openDueAsOf == null -> false
            openDueAsOf.length <= 10 -> (date ?: iso.take(10)) <= openDueAsOf
            else -> runCatching { !Instant.parse(iso).isAfter(Instant.parse(openDueAsOf)) }.getOrDefault(iso.take(10) <= openDueAsOf.take(10))
        }
        var extra = 0L
        for (live in all.filter { it.uuid !in replaced }) {
            val chain = generateSequence(live) { it.supersedesUuid?.let(byId::get) }.take(50).toList()
            val collected = chain.flatMap { it.collections }
            val current = live.dueMtk.coerceAtLeast(0) - collected.sumOf { it.second }
            val heldVersion = chain.firstOrNull { inFigure(it.committedAtIso, it.businessDate) }
            val held = heldVersion?.let { v -> v.dueMtk.coerceAtLeast(0) - collected.filter { inFigure(it.first, null) }.sumOf { it.second } } ?: 0L
            extra += current - held
        }
        return (openDueMtk.coerceAtLeast(0) + extra).coerceAtLeast(0)
    }
}
