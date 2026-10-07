package com.aktcl.aron.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.CaptureRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** SQLCipher needs its native library: device or CI emulator only. Proves the per-user file is encrypted and reopens. */
@RunWith(AndroidJUnit4::class)
class SqlCipherDeviceTest {
    @Test
    fun theUserDatabaseIsEncryptedAndSurvivesAReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val userId = 990001L
        context.deleteDatabase(AronDatabase.fileName(userId))
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        var db = AronDatabase.open(context, userId, SqlCipher.factory(key))
        val (visit, fix) = TestRowsDevice.visit()
        CaptureRepository(db).recordVisitOpen(visit, fix)
        db.close()
        val bytes = File(context.getDatabasePath(AronDatabase.fileName(userId)).path).readBytes()
        assertFalse("plain SQLite header found", String(bytes, 0, 16, Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        db = AronDatabase.open(context, userId, SqlCipher.factory(key))
        assertEquals(1, db.outboxDao().countInState("pending"))
        db.close()
        val wrong = AronDatabase.open(context, userId, SqlCipher.factory(ByteArray(32)))
        assertTrue(runCatching { wrong.openHelper.writableDatabase }.isFailure)
        context.deleteDatabase(AronDatabase.fileName(userId))
    }

    /**
     * F-SYS-071: nothing written reaches the disk in clear, in the main file or its WAL, journal and shm side files, while
     * the database is open and after it closes; a copy of the file under another user's name cannot be opened with that
     * user's key (each user has an own random key, DatabaseKeysTest).
     */
    @Test
    fun noPlaintextReachesAnyFileAndACopyIsUnreadableToAnotherUser() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (a, b) = 990002L to 990003L
        listOf(a, b).forEach { context.deleteDatabase(AronDatabase.fileName(it)) }
        val marker = "ARON-F071-PLAINTEXT-MARKER-দোকান"
        // Positive control: without SQLCipher the same write is found, so the search below can find a leak.
        val plain = AronDatabase.open(context, b, null)
        plain.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity("f071.marker", marker))
        plain.close()
        val plainFile = context.getDatabasePath(AronDatabase.fileName(b)).readBytes()
        assertTrue("control: plaintext found", String(plainFile, Charsets.UTF_8).contains(marker))
        context.deleteDatabase(AronDatabase.fileName(b))
        val db = AronDatabase.open(context, a, SqlCipher.factory(ByteArray(32) { (it + 11).toByte() }))
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity("f071.marker", marker))
        val needle = marker.toByteArray(Charsets.UTF_8)
        fun files() = context.getDatabasePath(AronDatabase.fileName(a)).let { f -> f.parentFile!!.listFiles { x -> x.name.startsWith(f.name) }!!.toList() }
        fun leaks() = files().filter { f -> f.readBytes().let { bytes -> (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } } } }
        assertTrue("the open database has files", files().isNotEmpty())
        assertEquals("plaintext while open", emptyList<File>(), leaks())
        db.close()
        assertEquals("plaintext after close", emptyList<File>(), leaks())
        context.getDatabasePath(AronDatabase.fileName(a)).copyTo(context.getDatabasePath(AronDatabase.fileName(b)), overwrite = true)
        val copy = AronDatabase.open(context, b, SqlCipher.factory(ByteArray(32) { (it + 99).toByte() }))
        assertTrue(runCatching { copy.openHelper.writableDatabase }.isFailure)
        runCatching { copy.close() }
        // And the copy is intact: user A's key opens it and reads the marker back.
        val again = AronDatabase.open(context, b, SqlCipher.factory(ByteArray(32) { (it + 11).toByte() }))
        assertEquals(marker, again.referenceDao().meta("f071.marker"))
        again.close()
        listOf(a, b).forEach { context.deleteDatabase(AronDatabase.fileName(it)) }
    }
}

private object TestRowsDevice {
    fun visit(): Pair<com.aktcl.aron.core.database.entity.VisitEntity, com.aktcl.aron.core.database.entity.GeoFixEntity> {
        val uuid = com.aktcl.aron.core.common.ClientIds.newUuid()
        val meta = com.aktcl.aron.core.database.entity.CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, null, true, 10231, null, "2026-10-05:3", false, 318)
        val fix = com.aktcl.aron.core.database.entity.GeoFixEntity(
            clientUuid = com.aktcl.aron.core.common.ClientIds.newUuid(), ownerClientUuid = uuid, purpose = "visit_open", fixStatus = "ok",
            lat = 23.79, lng = 90.40, accuracyM = 10.0, provider = "fused", isMock = false, reused = false,
            deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        val visit = com.aktcl.aron.core.database.entity.VisitEntity(
            uuid, meta, "sr_call", 50001, "2026-10-05T04:31:07.120Z", 1, true, null, fix.clientUuid, "in_range", 10.0, 100, 50,
            "master", 23.79, 90.40, "sale_allowed",
        )
        return visit to fix
    }
}
