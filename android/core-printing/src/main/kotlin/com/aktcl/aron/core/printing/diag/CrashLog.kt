package com.aktcl.aron.core.printing.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The last crash and the last print error of this app, in app-private storage, for the Printer diagnostics export
 * (owner P0 2026-10-24: the app closed on Print and no trace reached us). F-SYS-032's `ErrorReporter` uploads crashes as
 * `app_error` records and deletes its files at the next start; this keeps one copy of each on the phone so Settings →
 * Printer → Export carries the trace even offline. Two small files, overwritten; nothing is sent anywhere by this.
 *
 * [install] chains in front of the handler already there (the app calls it after `ErrorReporter.start`), writes the
 * crash, then lets the previous handler run as before. [handled] records a print-path error that was caught and shown to
 * the seller. Neither ever throws.
 */
object CrashLog {
    @Volatile private var dir: File? = null
    @Volatile private var build: String = "?"
    private val handlerInstalled = AtomicBoolean(false)
    internal var nowMs: () -> Long = System::currentTimeMillis

    /** Application.onCreate: the folder, the build ("versionName+versionCode") and the crash handler (once per process). */
    fun install(context: Context, build: String) {
        use(dirOf(context), build)
        if (!handlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            crashed(t, e)
            previous?.uncaughtException(t, e)
        }
    }

    /** The folder and build without the handler (tests, and an app that only wants [handled] kept). */
    internal fun use(dir: File?, build: String) {
        this.dir = dir
        this.build = build
    }

    fun dirOf(context: Context): File = File(context.noBackupFilesDir, "print-diag")

    /** What the uncaught-exception handler writes. */
    internal fun crashed(thread: Thread, e: Throwable) = write(CRASH, "uncaught", thread.name, e)

    /** A print-path error that was caught (the seller saw "not printed"); [where] is a short code such as "print". */
    fun handled(where: String, e: Throwable) = write(ERROR, where, Thread.currentThread().name, e)

    private fun write(name: String, where: String, thread: String, e: Throwable) {
        try {
            val d = dir ?: return
            d.mkdirs()
            val text = buildString {
                appendLine("time: ${Instant.ofEpochMilli(nowMs())}")
                appendLine("build: $build")
                appendLine("thread: $thread")
                appendLine("where: $where")
                append(scrub(e.stackTraceToString()).take(MAX_STACK))
            }
            val tmp = File(d, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(d, name))) { File(d, name).delete(); tmp.renameTo(File(d, name)) }
        } catch (_: Throwable) {
            // a dying process or a full disk: never make it worse
        }
    }

    /** Phone numbers and long digit runs ([Redact]) and Bangla text (outlet and owner names) leave the trace. */
    internal fun scrub(s: String): String = Redact.text(s).replace(BANGLA, "<bn>")

    /** The two records for the export, or "none"; read from this app's folder whether or not [install] ran. */
    fun exportText(context: Context): String = exportText(dirOf(context))

    internal fun exportText(d: File?): String = buildString {
        for ((title, name) in listOf("Last crash" to CRASH, "Last print error (caught, shown to the seller)" to ERROR)) { // i18n-ignore: technical diagnostics text, English by design (owner P0 2026-10-24 export)
            val text = d?.let { File(it, name) }?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            appendLine("$title: " + if (text == null) "none" else "") // i18n-ignore: technical diagnostics text, English by design (owner P0 2026-10-24 export)
            text?.lineSequence()?.forEach { appendLine("  $it") }
        }
    }

    /**
     * Why Android ended this app's last processes (API 30+): crash, ANR ("app not responding": the main thread was stuck),
     * low memory, the user, ... For an ANR the main thread's stack is added from the trace Android kept, so a freeze that
     * looked like a crash is told apart from one. Never throws.
     */
    fun exitReasons(context: Context, max: Int = 5): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Exit reasons: not available before Android 11\n" // i18n-ignore: technical diagnostics text, English by design (owner P0 2026-10-24 export)
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return "Exit reasons: unavailable\n" // i18n-ignore: technical diagnostics text, English by design (owner P0 2026-10-24 export)
            val list = am.getHistoricalProcessExitReasons(null, 0, max)
            buildString {
                appendLine("Exit reasons (newest first, ${list.size}):") // i18n-ignore: technical diagnostics text, English by design (owner P0 2026-10-24 export)
                for (x in list) {
                    appendLine("  ${Instant.ofEpochMilli(x.timestamp)} ${reasonName(x.reason)} importance=${x.importance} ${scrub(x.description ?: "")}".trimEnd()) // i18n-ignore: technical diagnostics text, English by design (owner P0 2026-10-24 export)
                    if (x.reason == ApplicationExitInfo.REASON_ANR) {
                        val trace = runCatching { x.traceInputStream?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
                        trace?.let { mainThreadOf(it) }?.lineSequence()?.forEach { appendLine("    $it") }
                    }
                }
            }
        } catch (e: Throwable) {
            "Exit reasons: ${e.javaClass.simpleName}\n" // i18n-ignore: technical diagnostics text, English by design (owner P0 2026-10-24 export)
        }
    }

    /** The main thread's block of an ANR trace ("\"main\" ..." to the blank line), scrubbed and capped. */
    internal fun mainThreadOf(trace: String): String? {
        val lines = trace.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return null
        val end = (start until lines.size).firstOrNull { lines[it].isBlank() } ?: lines.size
        return scrub(lines.subList(start, end).take(MAX_ANR_LINES).joinToString("\n"))
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "REASON_$r"
    }

    private const val CRASH = "last_crash.txt"
    private const val ERROR = "last_print_error.txt"
    private const val MAX_STACK = 6_000
    private const val MAX_ANR_LINES = 60
    private val BANGLA = Regex("[ঀ-৿]+")
}
