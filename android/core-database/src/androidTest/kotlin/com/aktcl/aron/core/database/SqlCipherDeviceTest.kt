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
