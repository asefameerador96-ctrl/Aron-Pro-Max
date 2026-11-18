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

    // ---- D-AGATE-26: DISCOVERY scan (a HEURISTIC, honestly): every non-test source file under android/*/src/main that uses a Room transaction (`withTransaction`) or inserts into the outbox through
    // a variable or DAO named `outbox...` must be on ONE of two named lists, so a writer of that kind added later cannot slip past the Developer options gate unnoticed. NOT discovered: a direct DAO
    // insert without a transaction and without the outbox (it is not caught by this scan; review it by hand).

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
        for (rel in gatedFiles) {
            val f = listOf(File(rel), File("android/$rel"), File("../$rel")).firstOrNull { it.isFile }
            assertTrue("$rel is on gatedFiles but does not exist (renamed? update the list)", f != null)
            assertTrue("$rel must call ActionGate.requireOpen()", "ActionGate.requireOpen()" in f!!.readText())
        }
        // a stale gated entry: it must still be a file the discovery finds, except ProgrammeStore (it writes through the repository, so it has no marker of its own)
        val staleGated = gatedFiles - discovered() - setOf("app-sr/src/main/kotlin/com/aktcl/aron/sr/ProgrammeStore.kt")
        assertEquals("these gated files no longer write (stale entries hide a moved writer): $staleGated", emptySet<String>(), staleGated)
    }

    /**
     * D-AGATE-26 (RESTORED and strengthened after the lead's check): the gate comes BEFORE the force-photo claim and the visit write in the visit commit of the SR and AMO days (the committer lambda
     * of `visitFlow`), and before the transaction in the TSO outbox entry. Moving the gate below `claimPhoto(` or `recordVisitOpen(` fails this test.
     */
    @Test fun theVisitCommitsAndTheTsoEntryGateBeforeTheirClaimsAndWrites() {
        for ((rel, label) in listOf("app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt" to "SR", "app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt" to "AMO")) {
            val src = file(rel)
            val write = src.indexOf("capture.recordVisitOpen(v, f)")
            assertTrue("$label: the visit commit was not found", write > 0)
            val from = src.lastIndexOf("committer = { v, f ->", write)
            assertTrue("$label: the committer lambda of visitFlow was not found", from in 0 until write)
            val segment = src.substring(from, write + "capture.recordVisitOpen(v, f)".length)
            val gate = segment.indexOf("ActionGate.requireOpen()")
            val claim = segment.indexOf("claimPhoto(")
            assertTrue("$label: the visit commit must call ActionGate.requireOpen()", gate >= 0)
            assertTrue("$label: the gate comes before the photo claim", claim < 0 || gate < claim)
            assertTrue("$label: the gate comes before the visit write", gate < segment.indexOf("capture.recordVisitOpen(v, f)"))
        }
        val tsoFile = file("app-tso/src/main/kotlin/com/aktcl/aron/tso/TsoOutbox.kt")
        val tso = tsoFile.substring(tsoFile.indexOf("suspend fun enqueueAll("))
        assertTrue("the TSO outbox entry (enqueueAll) gates before its transaction", tso.indexOf("ActionGate.requireOpen()") in 0 until tso.indexOf("withTransaction"))
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

    /** Each exemption names ONE function: a same-named overload added later would be exempt silently, so each name must be declared exactly once in its file. */
    @Test fun eachNamedExemptionIsOneUniqueFunction() {
        val files = gatedFiles.associateBy { it.substringAfterLast('/') }
        for ((name, fns) in gatedByCaller) for (fn in fns) {
            val n = Regex("""\bfun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?$fn\(""").findAll(file(files.getValue(name))).count()
            assertEquals("$name.$fn must be declared exactly once (pin the signature if you add an overload)", 1, n)
        }
    }

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

    /** D-AGATE-26: outlet verification and the outlet request gate BEFORE their photo claim (as the visit commits do). */
    @Test fun theAmoPhotoClaimsComeAfterTheGate() {
        val src = file("app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoRecordWriter.kt")
        for (fn in listOf("suspend fun decideRequest(", "suspend fun commitOutletRequest(")) {
            val body = src.substring(src.indexOf(fn)); val gate = body.indexOf("ActionGate.requireOpen()"); val claim = body.indexOf("claimPhoto(")
            assertTrue("$fn gates before it claims a photo", gate in 0 until claim)
        }
    }
}
