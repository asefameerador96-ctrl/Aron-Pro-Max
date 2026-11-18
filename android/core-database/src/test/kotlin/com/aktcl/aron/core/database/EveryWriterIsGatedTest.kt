package com.aktcl.aron.core.database

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source scan (D-OWNER-MDM-02, lead final pass): every public writer of [CaptureRepository] that opens a transaction calls `ActionGate.requireOpen()` BEFORE anything else
 * (before the purge check, the check-in gate and any read), so the Developer options block always comes first and a writer added later cannot forget it.
 * Exempt, by name: records that describe the phone or a photo that already exists, not an operational act.
 */
class EveryWriterIsGatedTest {
    private val exempt = setOf("recordBreadcrumb", "recordMediaMeta", "recordDeviceStatus", "addPhoneMark")

    private fun source(): String {
        val rel = "src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt"
        val f = listOf(File(rel), File("android/core-database/$rel")).first { it.isFile }
        return f.readText()
    }

    private data class Fn(val name: String, val body: String)

    private fun writers(): List<Fn> {
        val s = source()
        val heads = Regex("""\n    ((?:internal )?suspend fun )(\w+)\(""").findAll(s).toList()
        return heads.mapIndexed { i, m ->
            val end = heads.getOrNull(i + 1)?.range?.first ?: s.length
            Fn(m.groupValues[2], s.substring(m.range.first, end))
        }
    }

    @Test fun everyTransactionalWriterGatesFirst() {
        val ws = writers().filter { "withTransaction" in it.body && it.name !in exempt }
        assertTrue("the scan found only ${ws.size} writers: it is broken", ws.size >= 12)
        val bad = ws.filter { fn ->
            val gate = fn.body.indexOf("ActionGate.requireOpen()")
            val bodyStart = fn.body.indexOf('\n', fn.body.indexOf(") ") .coerceAtLeast(0)).coerceAtLeast(0)
            val firstWork = Regex("""purged\(|requireCheckedIn\(|requireUuids\(|AttendanceGate|capture\.|outbox\.|\.insert|\.upsert|require\(""").find(fn.body, bodyStart)?.range?.first ?: Int.MAX_VALUE
            gate < 0 || gate > firstWork
        }.map { it.name }
        assertEquals("these writers do not call ActionGate.requireOpen() first inside their transaction: $bad", emptyList<String>(), bad)
    }

    @Test fun theExemptNamesStillExist() {
        val names = writers().map { it.name }.toSet()
        assertEquals("a stale allow-list hides a renamed writer", emptySet<String>(), exempt - names)
    }

    private fun file(rel: String): String = listOf(File(rel), File("android/$rel"), File("../$rel")).first { it.isFile }.readText()

    /**
     * D-AGATE-25 (lead, after the integrity train): the gate also guards the writers that live OUTSIDE CaptureRepository: the TSO outbox entry, the TSO Final Submit and the SR and AMO visit
     * commit (the claim of a photo must not outlive a refused visit). Each must call `ActionGate.requireOpen()`, and where the writer is a single function the call comes before its first write.
     */
    @Test fun theWritersOutsideTheRepositoryAreGatedToo() {
        for (rel in listOf("app-tso/src/main/kotlin/com/aktcl/aron/tso/TsoOutbox.kt", "app-tso/src/main/kotlin/com/aktcl/aron/tso/TsoReadModels.kt", "app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt", "app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt")) {
            assertTrue("$rel must call ActionGate.requireOpen()", "ActionGate.requireOpen()" in file(rel))
        }
        val tsoFile = file("app-tso/src/main/kotlin/com/aktcl/aron/tso/TsoOutbox.kt")
        val tso = tsoFile.substring(tsoFile.indexOf("suspend fun enqueueAll("))
        val gate = tso.indexOf("ActionGate.requireOpen()")
        val firstWrite = tso.indexOf("withTransaction")
        assertTrue("the TSO outbox entry (enqueueAll) gates before its transaction", gate in 0 until firstWrite)
        val visitGate = file("app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt")
        assertTrue("the SR visit commit gates before the photo claim and the visit write", visitGate.indexOf("ActionGate.requireOpen()") < visitGate.indexOf("capture.recordVisitOpen(v, f)"))
        val amo = file("app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt")
        assertTrue("the AMO visit commit gates before its visit write", amo.indexOf("ActionGate.requireOpen()") < amo.indexOf("capture.recordVisitOpen("))
    }

    /**
     * The programme writers (lane android-sr-programmes): `ProgrammeStore.saveProgramme` and `saveGiftPhoto` call `ActionGate.requireOpen()` before anything else, and the photo save gates BEFORE the claim of the
     * photo, so a closed gate never leaves a claim behind.
     */
    @Test fun theProgrammeStoreWritersGateFirstAndBeforeThePhotoClaim() {
        val src = file("app-sr/src/main/kotlin/com/aktcl/aron/sr/ProgrammeStore.kt")
        for (name in listOf("saveProgramme", "saveGiftPhoto")) {
            val start = src.indexOf("suspend fun $name(")
            assertTrue("$name not found: the scan is broken", start >= 0)
            val body = src.substring(start, src.indexOf("\n    suspend fun ", start + 10).let { if (it < 0) src.length else it })
            val gate = body.indexOf("ActionGate.requireOpen()")
            assertTrue("$name must call ActionGate.requireOpen()", gate >= 0)
            val firstWork = Regex("""claimPhoto\(|withTransaction|db\.|\.insert|\.upsert|require\(""").find(body, body.indexOf("{", body.indexOf("withContext")))?.range?.first ?: Int.MAX_VALUE
            assertTrue("$name gates before any work", gate < firstWork)
        }
        val photo = src.substring(src.indexOf("suspend fun saveGiftPhoto("))
        assertTrue("the gift photo save gates before the claim", photo.indexOf("ActionGate.requireOpen()") < photo.indexOf("claimPhoto(photoUuid"))
    }

    /** D-AGATE-25: the check-out's open-work guard (item 6) runs AFTER the Developer options gate, never before it. */
    @Test fun theCheckOutGuardRunsAfterTheGate() {
        val body = writers().first { it.name == "recordCheckOut" }.body
        assertTrue(body.indexOf("ActionGate.requireOpen()") in 0 until body.indexOf("guard?.invoke()"))
    }
}
