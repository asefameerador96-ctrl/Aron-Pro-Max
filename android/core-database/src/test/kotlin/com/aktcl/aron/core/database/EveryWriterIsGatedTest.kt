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

    // ---- D-AGATE-26: DISCOVERY scan. Every non-test source file under android/*/src/main that opens a Room write transaction or inserts into the outbox must be on ONE of two named lists, so a
    // writer added later cannot slip past the Developer options gate unnoticed.

    private val writeMarker = Regex("""[oO]utbox(Dao\(\))?\.insert|withTransaction""")

    /** Files whose writers are OPERATIONAL: each must call `ActionGate.requireOpen()` (CaptureRepository is scanned per function above). */
    private val gatedFiles = setOf(
        "core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt",
        "app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt", "app-sr/src/main/kotlin/com/aktcl/aron/sr/ProgrammeStore.kt",
        "app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt", "app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoRecordWriter.kt",
        "app-tso/src/main/kotlin/com/aktcl/aron/tso/TsoOutbox.kt", "app-tso/src/main/kotlin/com/aktcl/aron/tso/TsoReadModels.kt",
    )

    /** SYSTEM writers, named with the reason they are NOT gated: they record the phone itself, a consent, a counter or a ledger, never a field act. */
    private val systemWriters = mapOf(
        "core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/ConsentRepository.kt" to "consent record",
        "core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/ReferenceRepository.kt" to "bundle download and the config acknowledgement",
        "core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/RoomPrintLedger.kt" to "print ledger (what was printed, reprints included)",
        "core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/MemoNumbers.kt" to "memo number reservation counter",
        "core-sync/src/main/kotlin/com/aktcl/aron/core/sync/ActivityLog.kt" to "activity log",
        "core-sync/src/main/kotlin/com/aktcl/aron/core/sync/ErrorReporter.kt" to "error reporter",
        "core-sync/src/main/kotlin/com/aktcl/aron/core/sync/SyncEngine.kt" to "sync bookkeeping (acks, digest, counters)",
    )

    private fun root(): File = listOf(File("."), File("android"), File("..")).first { File(it, "core-database/src/main").isDirectory || File(it, "android/core-database/src/main").isDirectory }
        .let { if (File(it, "core-database/src/main").isDirectory) it else File(it, "android") }

    private fun discovered(): Set<String> {
        val r = root()
        return r.walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath && "/build/" !in it.invariantSeparatorsPath }
            .filter { writeMarker.containsMatchIn(it.readText()) }.map { it.relativeTo(r).invariantSeparatorsPath }.toSet()
    }

    @Test fun everyWriteSourceIsOnANamedList() {
        val found = discovered()
        assertTrue("the discovery found only ${found.size} files: it is broken", found.size >= 8)
        val unknown = found - gatedFiles - systemWriters.keys
        assertEquals("these files write (outbox insert or a Room write transaction) but are on neither list: gate them (ActionGate.requireOpen() first) and add them to gatedFiles, or add them to systemWriters WITH a reason: $unknown", emptySet<String>(), unknown)
        val stale = (systemWriters.keys - found)
        assertEquals("a stale system allow-list hides a renamed writer: $stale", emptySet<String>(), stale)
    }

    @Test fun everyGatedFileCallsTheGate() {
        for (rel in gatedFiles) assertTrue("$rel must call ActionGate.requireOpen()", "ActionGate.requireOpen()" in file(rel))
    }

    /**
     * Inside the gated APP files (not the repository, scanned per function above) every function that opens a write transaction or inserts into the outbox must call the gate itself
     * BEFORE the write, or be named here with the reason it is already behind the gate.
     */
    private val gatedByCaller = mapOf(
        // the four audit saves write through CaptureRepository.recordAmo / recordSurveyResponse, which gate first inside their own transaction (scanned above)
        "AmoRecordWriter.kt" to setOf("saveJointCallLocked", "saveSurveyLocked", "savePriceComplianceLocked", "saveAssessmentLocked"),
        // the survey answers go through CaptureRepository (gated first inside the transaction); this transaction only groups them
        "SrDay.kt" to setOf("saveSurvey"),
        // retires a plan MIRROR row once the server plan holds the outlet: a read-model refresh, not an operational record
        "TsoReadModels.kt" to setOf("plans"),
    )

    @Test fun everyWriteInTheGatedAppFilesIsAfterTheGateOrBehindACallerThatIs() {
        val funHead = Regex("""\n\s*(?:(?:private|internal|public|override|protected|inline|suspend)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\(""")
        val bad = ArrayList<String>()
        for (rel in gatedFiles.filter { it.startsWith("app-") }) {
            val src = file(rel); val name = rel.substringAfterLast('/')
            val heads = funHead.findAll(src).toList()
            heads.forEachIndexed { i, m ->
                val body = src.substring(m.range.first, heads.getOrNull(i + 1)?.range?.first ?: src.length)
                val w = writeMarker.find(body) ?: return@forEachIndexed
                val gate = body.indexOf("ActionGate.requireOpen()")
                val fn = m.groupValues[1]
                if ((gate < 0 || gate > w.range.first) && fn !in (gatedByCaller[name] ?: emptySet())) bad += "$name.$fn"
            }
        }
        assertEquals("these functions write without the gate first and are not named as gated by their caller (with a reason): $bad", emptyList<String>(), bad)
    }

    /** D-AGATE-25: the check-out's open-work guard (item 6) runs AFTER the Developer options gate, never before it. */
    @Test fun theCheckOutGuardRunsAfterTheGate() {
        val body = writers().first { it.name == "recordCheckOut" }.body
        assertTrue(body.indexOf("ActionGate.requireOpen()") in 0 until body.indexOf("guard?.invoke()"))
    }

    /** D-AGATE-26: outlet verification and the outlet request gate BEFORE their photo claim (as the visit commits do). */
    @Test fun theAmoPhotoClaimsComeAfterTheGate() {
        val src = file("app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoRecordWriter.kt")
        for (fn in listOf("suspend fun decideRequest(", "suspend fun commitOutletRequest(")) {
            val body = src.substring(src.indexOf(fn)); val gate = body.indexOf("ActionGate.requireOpen()"); val claim = body.indexOf("claimPhoto(")
            assertTrue("$fn gates before it claims a photo", gate in 0 until claim)
        }
    }
}
