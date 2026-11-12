package com.aktcl.aron.backend.platform

import org.slf4j.Logger
import kotlin.system.exitProcess

/**
 * What a worker job does when its pass throws (lead's ruling on push A3). An Exception is the job's business: it logs and the schedule goes on
 * (every job wraps its pass in `runCatching`, so a thrown exception never cancels a `scheduleWithFixedDelay` task). An [Error] (out of memory,
 * stack overflow, a linkage error) must not be swallowed: a worker that is alive but no longer aggregating is the worst state, because the
 * dashboards freeze while the health probe stays green. [exitIfFatal] logs the Error once (class and stack frames, never the message, which could
 * carry data) and ends the process with a non-zero code so the platform restarts the revision.
 */
object WorkerExit {
    /** Test seam: the process exit. */
    @Volatile var hook: (Int) -> Unit = { exitProcess(it) }
    const val CODE = 70
}

fun Throwable.exitIfFatal(log: Logger, what: String = "worker job") {
    if (this !is Error) return
    log.error("{}: fatal {} (frames {}); exiting so the revision restarts", what, javaClass.name, stackTrace.take(12).joinToString(" <- "))
    WorkerExit.hook(WorkerExit.CODE)
}
