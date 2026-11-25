package com.aktcl.aron.core.database

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Function heads for the per-function scans. LIMITS (honest): a line-start `fun` with the usual modifiers (private, internal, public, override, protected, open, operator, tailrec, infix,
     * inline, suspend, abstract, external); a lambda held in a PROPERTY (`val x = Foo(committer = { ... })`) has no `fun` head, so its body is attributed to the PRECEDING function (the visit
     * commit lambdas are therefore checked by their own test, which cuts the lambda by its text, not by this regex).
     */
    private val funHeadRegex = Regex("""\n\s*(?:(?:private|internal|public|override|protected|open|operator|tailrec|infix|inline|suspend|abstract|external)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\(""")

    /**
     * D-AGATE-32 (blind spot closed): the DAO write methods are DERIVED from the DAO sources (`@Insert`, `@Update`, `@Delete`, `@Upsert`, and an `@Query` that starts with INSERT, UPDATE, DELETE or
     * REPLACE; the annotation may be on the line before the function), so a write method added to a DAO later is discovered with no edit here. A call `.name(` of one of them in a main source file
     * outside the DAO package marks that file as a writer, with or without a transaction and with or without the outbox. HEURISTIC, honestly: a method of ANOTHER class with the same name would
     * mark its file too (a false positive is named on a list, never silently ignored); a write through reflection or a raw `execSQL` / `compileStatement` is not seen (the second marker below
     * finds those so that none exists outside the DAO package).
     */
    private val daoWriteAnnotation = Regex("""(@(?:Insert|Update|Delete|Upsert)\b(?:\([^)]*\))?|@Query\(\s*"\s*(?:INSERT|UPDATE|DELETE|REPLACE)[^"]*"\s*\))\s*(?:abstract\s+|open\s+)*(?:suspend\s+)?fun\s+(\w+)""", RegexOption.IGNORE_CASE)

    private fun daoWriteNames(): Set<String> {
        val dir = root().resolve("core-database/src/main/kotlin/com/aktcl/aron/core/database/dao")
        val names = dir.listFiles { f -> f.extension == "kt" }.orEmpty().flatMap { f -> daoWriteAnnotation.findAll(f.readText()).map { it.groupValues[2] }.toList() }.toSet()
        assertTrue("the DAO scan found only ${names.size} write methods: it is broken", names.size >= 40)
        return names
    }

    private val writeMarker: Regex by lazy {
        // `insert` alone is the outbox DAO's: it is matched through the outbox pattern, not as a bare method name
        val direct = (daoWriteNames() - "insert").joinToString("|")
        Regex("""[oO]utbox(Dao\(\))?\.insert|withTransaction|\.($direct)\(|\bexecSQL\(|compileStatement\(""")
    }

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
        // D-AGATE-32: files the DIRECT-DAO discovery (no transaction, no outbox) found. Every one writes the key-value table `sync_meta`, the outbox bookkeeping or the schema, never a field record.
        "app-amo/src/main/kotlin/com/aktcl/aron/amo/RoomCallTargetsStore.kt" to "call-targets cache in sync_meta (a read model of the server's answer)",
        "app-tso/src/main/kotlin/com/aktcl/aron/tso/TicketsHost.kt" to "drops the local mirror keys of tickets and events the server already answers (sync_meta); the ticket save itself is TicketDevice.save, gated (photo-claim sweep)",
        "core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/RouteDayLock.kt" to "the server's void of a submit cycle recorded in sync_meta (a sync result, not a field act)",
        "core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/LocalPurge.kt" to "retention purge of rows already acknowledged by the server (raw SQL; its own purge rules)",
        "core-database/src/main/kotlin/com/aktcl/aron/core/database/Migration9To10.kt" to "schema migration (raw SQL, runs once at database open)",
        "core-sync/src/main/kotlin/com/aktcl/aron/core/sync/BundleDownloader.kt" to "bundle refresh bookkeeping in sync_meta",
        "core-sync/src/main/kotlin/com/aktcl/aron/core/sync/ResumeConfigCheck.kt" to "config version and refresh flags in sync_meta",
        "core-sync/src/main/kotlin/com/aktcl/aron/core/sync/SyncRules.kt" to "removes ack-resolution stash keys in sync_meta",
        "core-sync/src/main/kotlin/com/aktcl/aron/core/sync/SyncWork.kt" to "purge cutoff and purge date markers in sync_meta",
        "feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/DayCloseFailed.kt" to "failed-close counters in sync_meta (they drive the lock-out page, they are not a record)",
        "feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/RoomDayAdapters.kt" to "marks a refused outbox row unseen (sync bookkeeping)",
    )

    private fun root(): File = listOf(File("."), File("android"), File("..")).first { File(it, "core-database/src/main").isDirectory || File(it, "android/core-database/src/main").isDirectory }
        .let { if (File(it, "core-database/src/main").isDirectory) it else File(it, "android") }

    private fun discovered(): Set<String> {
        val r = root()
        return r.walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath && "/build/" !in it.invariantSeparatorsPath && "/core/database/dao/" !in it.invariantSeparatorsPath }
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
            assertFalse("$label: nothing that takes the force-photo uuid may run before the gate (any call with it is a claim)", segment.substring(0, gate).contains("geoForcePhotoUuid"))
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
        // TsoReadModels: `plans` retires a plan MIRROR row once the server plan holds the outlet; `cachePut` writes the read cache: read-model refreshes, not operational records
        "TsoReadModels.kt" to setOf("plans", "cachePut", "refresh"),
        // D-AGATE-32: `dismissRolloverNotice` deletes the phone's own rollover marks (clearPhoneMarks: the notice has been seen), a record of the phone, not an act; the TSO `refresh` overrides
        // (leave, feedback) cache the server's answer and drop the local "not sent yet" mirror rows the server now holds: a read-model refresh
        // SrDay.saveSurvey: the survey answers go through CaptureRepository (gated first inside the transaction); this transaction only groups them
        "SrDay.kt" to setOf("saveSurvey", "dismissRolloverNotice"),
        "AmoDay.kt" to setOf("dismissRolloverNotice"),
    )

    /** How many declarations a named exemption must have (default one). `TsoReadModels.refresh` is the override of two sources (leave, feedback): both are read-model refreshes. */
    private val exemptDeclarations = mapOf("TsoReadModels.kt.refresh" to 2)

    /** Each exemption names ONE function: a same-named overload added later would be exempt silently, so each name must be declared exactly once in its file. */
    @Test fun eachNamedExemptionIsOneUniqueFunction() {
        val files = gatedFiles.associateBy { it.substringAfterLast('/') }
        for ((name, fns) in gatedByCaller) for (fn in fns) {
            val n = Regex("""\bfun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?$fn\(""").findAll(file(files.getValue(name))).count()
            val want = exemptDeclarations["$name.$fn"] ?: 1
            assertEquals("$name.$fn must be declared exactly $want time(s) (pin the signature if you add an overload)", want, n)
        }
    }

    @Test fun everyWriteInTheGatedAppFilesIsAfterTheGateOrBehindACallerThatIs() {
        val funHead = funHeadRegex
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

    /**
     * D-AGATE-32: the direct-DAO discovery does not rely on one hand-kept name list. (a) The derived write-method names include the ones that were the blind spot (a plain `@Insert` used
     * outside a transaction, a `@Query("DELETE ...")`, a multi-line annotation), so the scan cannot silently shrink. (b) No file other than the repositories and the named lists writes
     * through the DAO: `everyWriteSourceIsOnANamedList` fails for a new one.
     */
    @Test fun theDirectDaoDiscoveryKnowsTheBlindSpotMethods() {
        val names = daoWriteNames()
        for (n in listOf("insertFix", "insertMemo", "clearPhoneMarks", "deleteMeta", "putMeta", "markMemoPrinted", "upsertJob", "markRefusedUnseen", "returnToPending")) assertTrue("the DAO scan must know $n", n in names)
        // a read method must never be taken for a writer
        for (n in listOf("meta", "tasks", "jobs", "phoneMarks", "clusterCreates")) assertFalse("$n is a read", n in names)
        assertTrue("a direct call is a marker", writeMarker.containsMatchIn("capture.insertFix(row)"))
        assertTrue("a delete in a launched block is a marker", writeMarker.containsMatchIn("background.launch { runCatching { capture.clearPhoneMarks(upTo) } }"))
        assertFalse("a read is not a marker", writeMarker.containsMatchIn("val m = dao.meta(key)"))
    }

    /**
     * D-AGATE-32: in [CaptureRepository] every function that calls a DAO write WITHOUT opening a transaction either gates first or is named with the reason. (Those with a transaction are
     * scanned by [everyTransactionalWriterGatesFirst].)
     */
    private val repositoryDirectWritersNotGated = mapOf(
        "addPhoneMark" to "a record of the phone (a mark), not an act",
        "clearPhoneMarks" to "removes marks already sent",
        "writeDaySubmit" to "private body of recordDaySubmit and recordCheckOut, which gate first inside their transaction (everyTransactionalWriterGatesFirst)",
    )

    @Test fun everyDirectDaoWriterOfTheRepositoryGatesOrIsNamed() {
        val s = source(); val heads = funHeadRegex.findAll(s).toList()
        val direct = (daoWriteNames() - "insert").joinToString("|")
        val call = Regex("""\.($direct)\(""")
        val found = ArrayList<String>(); val bad = ArrayList<String>()
        heads.forEachIndexed { i, m ->
            val body = s.substring(m.range.first, heads.getOrNull(i + 1)?.range?.first ?: s.length)
            if ("withTransaction" in body || !call.containsMatchIn(body)) return@forEachIndexed
            val fn = m.groupValues[1]; found += fn
            val gate = body.indexOf("ActionGate.requireOpen()")
            if ((gate < 0 || gate > call.find(body)!!.range.first) && fn !in repositoryDirectWritersNotGated) bad += fn
        }
        assertEquals("these repository functions write through the DAO outside a transaction without the gate first and are not named: $bad", emptyList<String>(), bad)
        assertEquals("a stale allow-list hides a renamed writer", emptySet<String>(), repositoryDirectWritersNotGated.keys - found.toSet())
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

    /**
     * D-AGATE-31 (lead, SR twin of the AMO fix): a photo CLAIM (`claimPhoto(`, `.attach(` or `claim(` on the media queue) that runs before a refused write leaves a claimed photo waiting for an ack
     * that never comes. Every call site in app-sr, app-amo and app-tso main sources must have `ActionGate.requireOpen()` earlier in its own function, or be named here with where the gate is.
     */
    private val claimsGatedElsewhere = mapOf(
        // the implementations of the claim itself (they are what the callers above call)
        "SrDay.kt.claimPhotoIo" to "the claim implementation (SR)", "AmoDay.kt.claimPhoto" to "the claim implementation (AMO)",
        // wiring lambdas (attributed to the PRECEDING function: see funHeadRegex): the one that hands claimPhoto to the AMO record writer (the writer gates before it calls it: decideRequest,
        // commitOutletRequest, theAmoPhotoClaimsComeAfterTheGate) and the one that hands the media attach to TicketDevice (TicketDevice.save gates before its claims)
        "AmoDay.kt.memoHeader" to "wiring lambda; AmoRecordWriter gates before every call", "TicketsHost.kt.refreshConfig" to "wiring lambda; TicketDevice.save gates before its claims",
        // the survey claims run AFTER the gated repository transaction (recordSurveyResponse gates first) except the replay branch below
        "SrDay.kt.saveSurvey" to "after the gated transaction (recordSurveyResponse gates first)",
    )

    @Test fun everyPhotoClaimComesAfterTheGateOrIsNamed() {
        val claimCall = Regex("""(?<!fun )(?<!val )(?<!\.)\b(claimPhoto|claim)\(|\.attach\(""")
        val found = ArrayList<String>(); val bad = ArrayList<String>()
        for (rel in listOf("app-sr", "app-amo", "app-tso").flatMap { m -> root().resolve("$m/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root()).invariantSeparatorsPath }.toList() }) {
            val src = file(rel); val name = rel.substringAfterLast('/')
            val heads = funHeadRegex.findAll(src).toList()
            for (m in claimCall.findAll(src)) {
                // skip declarations and parameter lists: only a call inside a function body counts
                val h = heads.lastOrNull { it.range.first < m.range.first } ?: continue
                val fn = h.groupValues[1]
                val body = src.substring(h.range.first, m.range.first)
                found += "$name.$fn"
                val gated = "ActionGate.requireOpen()" in body
                if (!gated && "$name.$fn" !in claimsGatedElsewhere) bad += "$name.$fn@" + src.substring(0, m.range.first).count { it == '\n' }
            }
        }
        assertTrue("the claim sweep found only ${found.size} call sites: it is broken", found.size >= 8)
        assertEquals("these photo claims have no gate before them in their function and are not named (with where the gate is): $bad", emptyList<String>(), bad)
        val stale = claimsGatedElsewhere.keys - found.toSet()
        assertEquals("a stale claim allow-list hides a moved claim: $stale", emptySet<String>(), stale)
    }

    /** D-AGATE-31: the SR outlet-request committer gates before its fix read and its photo claims (the SR twin of the AMO `commitOutletRequest`). */
    @Test fun theSrOutletRequestCommitterGatesBeforeItsClaims() {
        val src = file("app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt")
        val body = src.substring(src.indexOf("override suspend fun commitAll("))
        val gate = body.indexOf("ActionGate.requireOpen()"); val claim = body.indexOf("claimPhoto(")
        assertTrue("commitAll must call the gate before it reads a fix or claims a photo", gate in 0 until minOf(claim, body.indexOf("readFix(")))
    }
}
