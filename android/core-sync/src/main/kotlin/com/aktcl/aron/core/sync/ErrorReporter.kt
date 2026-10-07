package com.aktcl.aron.core.sync

import androidx.room.withTransaction
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.record.AppErrorReport
import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.session.TrustedClockSource
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import java.io.File

/**
 * Privacy-aware scrubbing for `app_error` (F-SYS-032, docs/21): what leaves the phone keeps the exception class, the
 * stack frames and the shape of the message, never a token, a URL query (SAS), a username, a phone number, an amount,
 * an id, a quoted value or Bangla text (the
 * names of outlets and owners are Bangla or quoted in practice); [names] (the user's outlet, owner and cluster names) are
 * removed too when known.
 */
object ErrorScrubber {
    private val digits = Regex("""\+?\d[\d\s-]{4,}\d""")
    private val quoted = Regex("""(['"`])(?:(?!\1).){1,200}\1""")
    private val bangla = Regex("""[ঀ-৿][ঀ-৿\s]*""")
    private val email = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val urlQuery = Regex("""\?[^\s)'"]*""") // SAS (sig=, sv=, se=) and every other query value (docs/21)
    private val bearer = Regex("""(?i)bearer\s+\S+""")
    private val jwt = Regex("""eyJ[\w-]*\.[\w-]*\.[\w-]*""")
    private val username = Regex("""\b[a-z]{2,5}\d{3,}\b""") // sr334001

    fun scrub(text: String?, names: Collection<String> = emptyList()): String? {
        if (text == null) return null
        var t: String = text
        names.filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { n -> t = t.replace(n, "<name>", ignoreCase = true) }
        t = bearer.replace(t, "Bearer <t>")
        t = jwt.replace(t, "<t>")
        t = urlQuery.replace(t, "?<q>")
        t = email.replace(t, "<email>")
        t = username.replace(t, "<user>")
        t = quoted.replace(t, "<q>")
        t = bangla.replace(t, "<bn>")
        t = digits.replace(t, "<n>")
        return t
    }
}

/** A crash file: the report plus the monotonic capture fields of the moment it happened. */
@kotlinx.serialization.Serializable
internal data class CrashFile(val report: AppErrorReport, val elapsedMs: Long, val bootCount: Int)

/**
 * Error reporting (F-SYS-032). A crash is written as a small scrubbed file by the uncaught-exception handler (no
 * database work in a dying process), then the previous handler runs as before. At the next start [drain] moves the
 * files into the signed-in user's outbox as `app_error` records (the file's uuid is the record's client uuid, so a drain
 * cut short never queues one twice) and adds the ANRs Android recorded since the last drain. [handled] queues a caught
 * error at once. Records ride the next upload (telemetry: never quarantined, no sync asked for); at most [MAX_FILES]
 * crash files wait and at most [MAX_PER_RUN] records are queued per start, so a crash loop cannot spend the data pack.
 */
class ErrorReporter(
    private val dir: File,
    private val db: suspend (userId: Long) -> AronDatabase,
    private val clock: TrustedClockSource,
    private val appVersion: String,
    private val offline: () -> Boolean,
    /** The signed-in user after the cold-start restore (crash files and ANRs go to their outbox). */
    private val activeUser: suspend () -> Long? = { null },
    /** The signed-in user right now, read without waiting (the crashing thread): crash files are kept per user. */
    private val currentUser: () -> Long? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Installs the crash handler once per process, chained in front of the existing one. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(previous))
    }

    private inner class Handler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try { writeCrash(e, "crash") } catch (_: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }

    /**
     * Application.onCreate: the crash handler at once, then (off the main thread) the waiting crash files and the new
     * ANRs into the signed-in user's outbox. Nobody signed in: the files wait for the next start.
     */
    fun start(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
        install()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val user = runCatching { activeUser() }.getOrNull() ?: return@launch
            val marker = File(dir, "anr.since")
            val since = runCatching { marker.readText().trim().toLong() }.getOrDefault(0L)
            val (_, handledUntil) = drainCounted(user, anrsSince(context, since))
            handledUntil?.let { latest -> runCatching { dir.mkdirs(); marker.writeText(latest.toString()) } }
        }
    }

    /** ANRs Android recorded for this app after [sinceMs] (API 30+; nothing before): (time, description). */
    private fun anrsSince(context: android.content.Context, sinceMs: Long): List<Pair<Long, String>> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(null, 0, 10)
                .filter { it.reason == android.app.ApplicationExitInfo.REASON_ANR && it.timestamp > sinceMs }
                .map { it.timestamp to (it.description ?: "ANR") }
        }.getOrDefault(emptyList())
    }

    /** Writes [e] as a pending crash file of the user signed in now (`<uuid>.u<user>.json`). Returns the file's uuid. */
    fun writeCrash(e: Throwable, kind: String, screen: String? = null): String? {
        dir.mkdirs()
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: emptyList()
        if (files.size >= MAX_FILES) files.take(files.size - MAX_FILES + 1).forEach { it.delete() }
        val uuid = ClientIds.newUuid()
        val user = runCatching { currentUser() }.getOrNull() ?: 0L
        val crash = CrashFile(report(e, kind, screen, emptyList()), clock.elapsedRealtimeMs(), clock.bootCountNow())
        val tmp = File(dir, "$uuid.tmp")
        tmp.writeText(json.encodeToString(CrashFile.serializer(), crash))
        tmp.renameTo(File(dir, "$uuid.u$user.json"))
        return uuid
    }

    private fun report(e: Throwable, kind: String, screen: String?, names: Collection<String>) = AppErrorReport(
        occurredAt = SyncEngine.iso(clock.nowMs()),
        kind = kind,
        exceptionClass = e.javaClass.name.take(200),
        message = ErrorScrubber.scrub(e.message, names)?.take(500),
        stack = ErrorScrubber.scrub(e.stackTraceToString(), names)?.take(16_000),
        screen = screen?.takeIf { SCREEN.matches(it) },
        appVersion = appVersion,
    )

    /** A caught error worth knowing (never for expected offline failures). Never throws. */
    suspend fun handled(userId: Long, e: Throwable, screen: String? = null): Unit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val database = db(userId)
            queue(database, listOf(Entry(ClientIds.newUuid(), report(e, "handled", screen, names(database)), null, null)))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    /**
     * Moves the waiting crash files and the new ANRs ([anrs]: (exit time ms, description)) into [userId]'s outbox, scrubbed
     * again with the user's names. Files are deleted only after their rows are committed. Never throws; returns rows queued.
     */
    suspend fun drain(userId: Long, anrs: List<Pair<Long, String>> = emptyList()): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { drainOnIo(userId, anrs) }

    private suspend fun drainOnIo(userId: Long, anrs: List<Pair<Long, String>>): Int = drainCounted(userId, anrs).first

    /**
     * (rows queued, newest ANR time committed). Files of [userId] and of nobody (a crash before login) are drained; files
     * of another user wait for that user (a shared phone never mixes them). An ANR already queued (same time) is skipped.
     */
    internal suspend fun drainCounted(userId: Long, anrs: List<Pair<Long, String>>): Pair<Int, Long?> = try {
        val database = db(userId)
        val names = names(database)
        val mine = Regex("""^[0-9a-f-]{36}\.u($userId|0)\.json$""")
        val files = dir.listFiles { f -> mine.matches(f.name) }?.sortedBy { it.lastModified() }?.take(MAX_PER_RUN) ?: emptyList()
        val fromFiles = files.mapNotNull { f ->
            runCatching { json.decodeFromString(CrashFile.serializer(), f.readText()) }.getOrNull()?.let { c ->
                Entry(f.name.substringBefore('.'), c.report.copy(message = ErrorScrubber.scrub(c.report.message, names), stack = ErrorScrubber.scrub(c.report.stack, names)), c.elapsedMs, c.bootCount)
            } ?: run { f.delete(); null }
        }
        val freshAnrs = anrs.sortedBy { it.first }.filter { (at, _) -> !anrQueued(database, SyncEngine.iso(at)) }.take(MAX_PER_RUN - fromFiles.size)
        val fromAnrs = freshAnrs.map { (at, description) ->
            Entry(ClientIds.newUuid(), AppErrorReport(SyncEngine.iso(at), "anr", "ANR", ErrorScrubber.scrub(description, names)?.take(500), null, null, appVersion), null, null)
        }
        val n = queue(database, fromFiles + fromAnrs)
        files.forEach { it.delete() }
        // The marker moves to the newest ANR handled: queued now, or found already queued.
        val handled = anrs.filter { (at, _) -> anrQueued(database, SyncEngine.iso(at)) }.maxOfOrNull { it.first }
        n to handled
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (_: Exception) {
        0 to null
    }

    private data class Entry(val uuid: String, val report: AppErrorReport, val elapsedMs: Long?, val bootCount: Int?)

    private fun anrQueued(database: AronDatabase, occurredAt: String): Boolean = runCatching {
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM outbox WHERE record_type = 'app_error' AND payload_json LIKE ?",
            arrayOf<Any>("%\"occurred_at\":\"$occurredAt\",\"kind\":\"anr\"%"),
        ).use { it.moveToFirst(); it.getInt(0) > 0 }
    }.getOrDefault(false)

    private suspend fun queue(database: AronDatabase, entries: List<Entry>): Int {
        if (entries.isEmpty()) return 0
        val ref = ReferenceRepository(database)
        val bundleVersion = ref.bundleVersion()
        val configVersion = ref.configVersionHeld()
        val offlineNow = runCatching { offline() }.getOrDefault(true)
        return database.withTransaction {
            var n = 0
            for (e in entries) {
                if (database.outboxDao().byClientUuid(e.uuid) != null) continue // a drain cut short before the files went
                val r = e.report
                val at = runCatching { java.time.Instant.parse(r.occurredAt).toEpochMilli() }.getOrElse { clock.nowMs() }
                val date = com.aktcl.aron.rules.BusinessDate.of(at).toString()
                if (queuedOn(database, date) >= MAX_PER_DAY) continue // a crash loop cannot spend the data pack
                val meta = CaptureMeta(
                    businessDate = date,
                    capturedAt = r.occurredAt,
                    capturedElapsedMs = e.elapsedMs ?: clock.elapsedRealtimeMs(),
                    bootCount = e.bootCount ?: clock.bootCountNow(),
                    clockOffsetMs = clock.clockOffsetMs(),
                    capturedOffline = offlineNow,
                    routeId = null,
                    bundleVersion = bundleVersion,
                    configVersion = configVersion,
                )
                database.outboxDao().insert(listOf(RecordMapping.appError(e.uuid, meta, r)))
                n++
            }
            n
        }
    }

    private fun queuedOn(database: AronDatabase, date: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox WHERE record_type = 'app_error' AND business_date = ?", arrayOf<Any>(date))
            .use { it.moveToFirst(); it.getInt(0) }

    /** Outlet, owner and cluster names of the bundle, and the names typed into outlet requests (`*name*` members). */
    private fun names(database: AronDatabase): List<String> = runCatching {
        val r = database.openHelper.readableDatabase
        val bundle = r.query("SELECT name, name_bn, owner_name, cluster_name FROM outlet").use { c ->
            buildList { while (c.moveToNext()) for (i in 0 until 4) c.getString(i)?.let(::add) }
        }
        val typed = runCatching {
            r.query("SELECT proposed_json FROM outlet_change_request").use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val o = runCatching { Json.parseToJsonElement(c.getString(0)) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: continue
                        o.filterKeys { "name" in it }.values.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }.forEach(::add)
                    }
                }
            }
        }.getOrDefault(emptyList())
        (bundle + typed).distinct()
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_FILES = 20
        const val MAX_PER_RUN = 20
        const val MAX_PER_DAY = 50
        private val SCREEN = Regex("^[a-z][a-z0-9_.]{1,59}$")
    }
}
