package com.aktcl.aron.backend.notify

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.NotificationInbox
import com.aktcl.aron.contract.NotificationItem
import com.aktcl.aron.contract.NotificationReadRequest
import com.aktcl.aron.contract.NotificationReadResult
import com.aktcl.aron.contract.ProblemCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InboxDeps(val db: Database, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/**
 * The bell inbox of the TSO app (D-TSO-MAN-26, SH-08; contract 1.10.0 `listMyNotifications`, `markMyNotificationsRead`).
 * Rows are written by producers through `Inbox.add` (backend platform) in their own transaction.
 *
 * - `GET /v1/me/notifications`: the caller's rows only (the user is the token's user, never a parameter), newest
 *   first by id, `before_id` pages back (`next_before_id`), `limit` 1..100 (50). `unread_count` counts every unread row
 *   of the caller. The page and the count come from one statement, so they agree with each other.
 * - `POST /v1/me/notifications/read`: sets `read_at` once on the caller's unread rows with `id <= up_to_id` and answers
 *   the unread rows left. Idempotent: a repeat, or an `up_to_id` at or below rows already read, changes nothing and
 *   answers the same count; an `up_to_id` above the newest row is accepted.
 * Any signed-in caller may read its own inbox (the TSO phone today); no scope ids are read from the request.
 */
fun Route.inboxRoutes(d: InboxDeps) {
    authenticated(d.guard) {
        get("/me/notifications") {
            val p = call.principal
            val q = call.request.queryParameters
            val before = q["before_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw invalid("query.before_id") }
            val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..MAX_PAGE } ?: throw invalid("query.limit") } ?: DEFAULT_PAGE
            val inbox = withContext(Dispatchers.IO) { InboxStore.page(d.db, p.userId, before, limit, d.clock.now()) }
            call.respond(inbox)
        }
        post("/me/notifications/read") {
            val p = call.principal
            val req = call.receiveStrict(NotificationReadRequest.serializer())
            if (req.upToId < 1) throw invalid("/up_to_id")
            val left = withContext(Dispatchers.IO) { InboxStore.markRead(d.db, p.userId, req.upToId, d.clock.now()) }
            call.respond(NotificationReadResult(unreadCount = left))
        }
    }
}

private const val DEFAULT_PAGE = 50
private const val MAX_PAGE = 100

private fun invalid(pointer: String) =
    ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, "out_of_range")))

/** SQL of the inbox reads and the mark-read (own rows only: every statement is keyed by the token's user id). */
internal object InboxStore {
    fun page(db: Database, userId: Long, beforeId: Long?, limit: Int, now: Instant): NotificationInbox {
        var unread = 0
        val rows = db.jdbi.withHandle<List<NotificationItem>, Exception> { h ->
            // One statement, one snapshot: the count and the page cannot disagree. The LEFT JOIN keeps the count row
            // when the page is empty. One row more than the page tells whether an older page exists.
            h.createQuery(
                """
                WITH unread AS (
                  SELECT count(*)::int AS c FROM app.user_notification WHERE user_id = :u AND read_at IS NULL
                ), page AS (
                  SELECT id, kind, ref_type, ref_id, title_en, title_bn, body_en, body_bn, created_at, business_date, read_at
                    FROM app.user_notification
                   WHERE user_id = :u AND (CAST(:before AS bigint) IS NULL OR id < CAST(:before AS bigint))
                   ORDER BY id DESC
                   LIMIT :fetch
                )
                SELECT unread.c, page.* FROM unread LEFT JOIN page ON true ORDER BY page.id DESC NULLS LAST
                """.trimIndent(),
            ).bind("u", userId).bind("before", beforeId).bind("fetch", limit + 1)
                .map { rs, _ ->
                    unread = rs.getInt("c")
                    if (rs.getObject("id") == null) null
                    else NotificationItem(
                        id = rs.getLong("id"),
                        kind = rs.getString("kind"),
                        refType = rs.getString("ref_type"),
                        refId = rs.getString("ref_id"),
                        titleEn = rs.getString("title_en"),
                        titleBn = rs.getString("title_bn"),
                        bodyEn = rs.getString("body_en"),
                        bodyBn = rs.getString("body_bn"),
                        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(),
                        businessDate = rs.getObject("business_date", LocalDate::class.java).toString(),
                        readAt = rs.getObject("read_at", OffsetDateTime::class.java)?.toInstant()?.wire(),
                    )
                }.list().filterNotNull()
        }
        val more = rows.size > limit
        val items = if (more) rows.take(limit) else rows
        return NotificationInbox(asOf = now.wire(), unreadCount = unread, items = items, nextBeforeId = if (more) items.last().id else null)
    }

    /** Marks the caller's unread rows up to [upToId] read at [now]; returns the caller's unread rows left. */
    fun markRead(db: Database, userId: Long, upToId: Long, now: Instant): Int = db.jdbi.inTransaction<Int, Exception> { h ->
        // `read_at IS NULL` keeps the first read time (V0133 writes read_at once); a concurrent repeat waits on the
        // row locks, re-checks the condition and changes nothing.
        h.createUpdate("UPDATE app.user_notification SET read_at = :now WHERE user_id = :u AND id <= :upTo AND read_at IS NULL")
            .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("u", userId).bind("upTo", upToId).execute()
        h.createQuery("SELECT count(*)::int FROM app.user_notification WHERE user_id = :u AND read_at IS NULL")
            .bind("u", userId).mapTo(Int::class.java).one()
    }
}

/**
 * Deletes inbox rows older than `cfg.notify.inbox_retention_days` (D-TSO-MAN-26; 90 by default, bounded 30..365 as the
 * registry says, so a stored value outside the bounds cannot shorten or stretch the window). Runs in the worker only:
 * the worker's database role (`worker_rw`, inherited by `jobs_rw`) holds DELETE on `app.user_notification` (V0133) and
 * the API's (`api_rw`) does not. Deletes in chunks of [chunk] rows, each in its own short transaction under a 60 s
 * statement timeout, until a chunk comes back short. Idempotent: a second run deletes nothing new. Read or unread does
 * not matter: a notice past the window is gone, as the inbox description says.
 */
class InboxPruneJob(
    private val db: Database,
    private val config: ServerConfig,
    private val clock: AronClock = AronClock.SYSTEM,
    private val chunk: Int = 5_000,
    private val everyHours: Long = 6,
) {
    private val log = LoggerFactory.getLogger("aron.inbox-prune")

    /** The retention window in days now (the registry bounds; the default when the key cannot be read). */
    fun retentionDays(): Int = runCatching { config.int(RETENTION_KEY) }.getOrDefault(DEFAULT_DAYS).coerceIn(MIN_DAYS, MAX_DAYS)

    /** Prunes the rows created before now minus the window; returns how many were deleted. */
    fun runOnce(): Int {
        val cutoff = OffsetDateTime.ofInstant(clock.now().minus(Duration.ofDays(retentionDays().toLong())), ZoneOffset.UTC)
        var total = 0
        repeat(MAX_CHUNKS) {
            val n = db.jdbi.inTransaction<Int, Exception> { h ->
                h.execute("SET LOCAL statement_timeout = '60s'")
                h.createUpdate(
                    "DELETE FROM app.user_notification WHERE id IN (SELECT id FROM app.user_notification WHERE created_at < :cutoff ORDER BY id LIMIT :n)",
                ).bind("cutoff", cutoff).bind("n", chunk).execute()
            }
            total += n
            if (n < chunk) return total
        }
        return total
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "inbox-prune").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { runOnce() }.onSuccess { if (it > 0) log.info("inbox: {} notification(s) past {} pruned", it, RETENTION_KEY) }
                .onFailure { log.error("inbox prune failed", it) }
        }, 11, everyHours * 60, TimeUnit.MINUTES)
    }

    companion object {
        const val RETENTION_KEY = "cfg.notify.inbox_retention_days"
        const val DEFAULT_DAYS = 90
        const val MIN_DAYS = 30
        const val MAX_DAYS = 365
        /** Upper bound of chunks per run (a run never loops forever; the next run continues). */
        private const val MAX_CHUNKS = 200
    }
}
