package com.aktcl.aron.core.system.integrity

import java.io.File

/** One gate onset: which flags were on, when (UTC ms and the phone's boot count), in which build, and whether it was exempt. */
data class IntegrityEvent(val atUtcMs: Long, val flags: List<String>, val exempt: String, val appVersion: String)

/**
 * Durable, append-only list of gate events waiting to ride the next sync as an integrity signal (D-OWNER-MDM-02 ii).
 * One line per event, capped at [CAP] (oldest dropped), written atomically; [ack] drops what the server has taken.
 * Never throws: a failed write loses a log line, never the app.
 */
class IntegrityEventLog(private val dir: File) {
    private val file get() = File(dir, "integrity-events.txt")

    @Synchronized
    fun record(e: IntegrityEvent) {
        runCatching {
            val lines = (pendingLines() + encode(e)).takeLast(CAP)
            dir.mkdirs()
            val tmp = File(dir, "integrity-events.tmp")
            tmp.writeText(lines.joinToString("\n"))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    @Synchronized
    fun pending(): List<IntegrityEvent> = pendingLines().mapNotNull(::decode)

    /** Removes the first [count] events (those sent and acknowledged). */
    @Synchronized
    fun ack(count: Int) {
        runCatching {
            val rest = pendingLines().drop(count)
            if (rest.isEmpty()) file.delete() else file.writeText(rest.joinToString("\n"))
        }
    }

    private fun pendingLines(): List<String> = runCatching { file.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } }.getOrNull().orEmpty()

    private fun encode(e: IntegrityEvent) = listOf(e.atUtcMs, e.flags.joinToString(","), e.exempt, e.appVersion.replace('|', '_').replace('\n', ' ')).joinToString("|")

    private fun decode(line: String): IntegrityEvent? = runCatching {
        val p = line.split('|')
        IntegrityEvent(p[0].toLong(), p[1].split(',').filter { it.isNotEmpty() }, p[2], p[3])
    }.getOrNull()

    companion object { const val CAP = 200 }
}
