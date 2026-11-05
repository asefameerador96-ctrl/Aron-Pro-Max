package com.aktcl.aron.rules

/** Unit of a ticket's age text (D-TSO-MAN-25). */
enum class AgeUnit { JUST_NOW, MINUTES, HOURS, DAYS }

/** A ticket's age: [value] whole [unit]s, rounded down (0 for [AgeUnit.JUST_NOW]). */
data class TicketAge(val unit: AgeUnit, val value: Long)

/** One ticket of the cached list: its client UUID and its status wire code (SupportTicketStatus). */
data class TicketRow(val uuid: String, val status: String)

/**
 * The Ticket Dashboard counts (D-TSO-MAN-25, TK-06, TK-07). Total = every status; Open = new + in_progress; the two
 * percentages are whole percent from the raw counts, 0 when there are no tickets.
 */
data class TicketCounts(
    val total: Long,
    val new: Long,
    val inProgress: Long,
    val resolved: Long,
    val closed: Long,
    val cancelled: Long,
) {
    val open: Long get() = new + inProgress

    /** Resolved % = [Percent.whole] (resolved, total); 0 when total = 0. 18 of 723 -> 2. */
    val resolvedPct: Long get() = TicketMath.pct(resolved, total)

    /** Open % = [Percent.whole] (new + in_progress, total); 0 when total = 0. */
    val openPct: Long get() = TicketMath.pct(open, total)

    fun resolvedPctText(locale: UiLocale): String = Percent.text(resolvedPct, 0, locale)

    fun openPctText(locale: UiLocale): String = Percent.text(openPct, 0, locale)
}

/**
 * Ticket arithmetic shared by the phone and the server (D-TSO-MAN-25; build contract s4.4): dashboard counts, the
 * offline merge, the whole percentages and the age text. Integers only.
 */
object TicketMath {
    const val NEW: String = "new"
    const val IN_PROGRESS: String = "in_progress"
    const val RESOLVED: String = "resolved"
    const val CLOSED: String = "closed"
    const val CANCELLED: String = "cancelled"

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /** Whole percent of [n] in [total]; 0 when [total] is 0 (the dashboard prints "0%", not a dash). */
    fun pct(n: Long, total: Long): Long = Percent.whole(n, total) ?: 0L

    /**
     * Counts by status. Every status counts in the total, also a code this version does not know (a ticket is a ticket);
     * only the five known codes have their own bucket.
     */
    fun counts(statuses: Iterable<String>): TicketCounts {
        var total = 0L; var new = 0L; var inProgress = 0L; var resolved = 0L; var closed = 0L; var cancelled = 0L
        for (s in statuses) {
            total++
            when (s) {
                NEW -> new++
                IN_PROGRESS -> inProgress++
                RESOLVED -> resolved++
                CLOSED -> closed++
                CANCELLED -> cancelled++
            }
        }
        return TicketCounts(total, new, inProgress, resolved, closed, cancelled)
    }

    /**
     * Offline counts (D-TSO-MAN-25): the cached list plus the tickets still in the outbox, merged by UUID (case-insensitive).
     * An unsent ticket the cache does not hold counts as New; one the cache already holds (the server took it, the ack was
     * lost) counts once, with the cached status. A UUID repeated in the cache counts once (first row).
     */
    fun counts(cached: Iterable<TicketRow>, unsentUuids: Iterable<String>): TicketCounts {
        val byUuid = LinkedHashMap<String, String>()
        for (row in cached) byUuid.getOrPut(row.uuid.lowercase()) { row.status }
        for (uuid in unsentUuids) byUuid.getOrPut(uuid.lowercase()) { NEW }
        return counts(byUuid.values)
    }

    /**
     * Age from the capture time and the trusted now (D-TSO-MAN-25): under 1 minute "just now", under 1 hour whole minutes,
     * under 24 hours whole hours, else whole days, each rounded down. A capture time after now (clock skew) is "just now".
     */
    fun age(nowMs: Long, capturedMs: Long): TicketAge {
        // capturedMs < nowMs makes the true difference positive, so a negative Long result can only be a wrap: saturate it.
        val ms = if (capturedMs >= nowMs) 0L else (nowMs - capturedMs).let { if (it < 0L) Long.MAX_VALUE else it }
        return when {
            ms < MINUTE_MS -> TicketAge(AgeUnit.JUST_NOW, 0L)
            ms < HOUR_MS -> TicketAge(AgeUnit.MINUTES, ms / MINUTE_MS)
            ms < DAY_MS -> TicketAge(AgeUnit.HOURS, ms / HOUR_MS)
            else -> TicketAge(AgeUnit.DAYS, ms / DAY_MS)
        }
    }

    /**
     * The age as the ticket list prints it: en "just now", "5m ago", "23h ago", "4d ago"; bn "এইমাত্র", "৫ মিনিট আগে",
     * "২৩ ঘণ্টা আগে", "৪ দিন আগে" (Bengali digits).
     */
    fun ageText(nowMs: Long, capturedMs: Long, locale: UiLocale): String {
        val a = age(nowMs, capturedMs)
        val n = Formats.digits(a.value.toString(), locale)
        return if (locale == UiLocale.BN) {
            when (a.unit) {
                AgeUnit.JUST_NOW -> "এইমাত্র"
                AgeUnit.MINUTES -> "$n মিনিট আগে"
                AgeUnit.HOURS -> "$n ঘণ্টা আগে"
                AgeUnit.DAYS -> "$n দিন আগে"
            }
        } else {
            when (a.unit) {
                AgeUnit.JUST_NOW -> "just now"
                AgeUnit.MINUTES -> "${n}m ago"
                AgeUnit.HOURS -> "${n}h ago"
                AgeUnit.DAYS -> "${n}d ago"
            }
        }
    }
}
