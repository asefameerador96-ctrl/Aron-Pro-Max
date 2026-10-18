package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AuditLog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-AUDIT-PII-01: every audit row goes through the platform writer (which redacts), and the write and read lists agree. */
class AuditWriteRedactionTest {
    private val root = File(System.getProperty("aron.repoRoot"))

    @Test
    fun noAuditWriteBypassesThePlatformWriter() {
        val insert = Regex("""(?i)(insert\s+into|copy)\s+("?app"?\.)?"?audit_log\b""")
        val sources = root.resolve("backend").listFiles()!!.filter { it.isDirectory }.flatMap { m ->
            m.resolve("src/main").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension in setOf("kt", "sql") }?.toList().orEmpty()
        } + listOf("db/migrations", "db/seed", "db/src/main", "infra", "tools", "scripts").flatMap { d ->
            root.resolve(d).walkTopDown().filter { it.isFile && it.extension in setOf("sql", "kt", "py", "sh", "ps1", "bicep") }.toList()
        }
        assertTrue(sources.size > 100, "the scan found the sources (${sources.size})")
        val writers = sources.filter { insert.containsMatchIn(it.readText()) }.map { it.relativeTo(root).path }
        assertEquals(listOf("backend/platform/src/main/kotlin/com/aktcl/aron/backend/platform/AuditLog.kt"), writers, "only AuditLog.write inserts audit rows")
    }

    @Test
    fun theWriteRedactionAndTheReadMaskNameTheSameMembers() {
        // listAudit masks every member the writer redacts, plus the coordinates the writer keeps for an outlet pin.
        assertEquals(AuditMask.PII, AuditLog.PII + AuditLog.GEO)
        AuditMask.PII.forEach { assertTrue(AuditMask.personal(it), it) }
    }
}
