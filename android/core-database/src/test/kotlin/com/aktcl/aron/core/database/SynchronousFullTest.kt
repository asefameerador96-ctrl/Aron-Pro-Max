package com.aktcl.aron.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DD-01 (audit 2026-10-15; docs/17 s5): a committed sale survives a battery pull. The database is WAL (a kill mid-write
 * is safe) and the writer runs `synchronous = FULL` (2): every commit reaches storage before it is acknowledged, so a
 * printed memo is never rolled back by a power loss before the next checkpoint. Checked on the connection Room writes
 * through, after the WAL switch that would otherwise leave NORMAL (1).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SynchronousFullTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<AronDatabase>()

    @After fun tearDown() {
        opened.forEach { it.close() }
        listOf(903L).forEach { context.deleteDatabase(AronDatabase.fileName(it)) }
    }

    private fun pragma(db: AronDatabase, name: String): Long =
        db.openHelper.writableDatabase.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun theWriterIsWalAndSynchronousFull() {
        val db = AronDatabase.open(context, 903, null).also { opened += it }
        assertEquals("wal", db.openHelper.writableDatabase.query("PRAGMA journal_mode").use { it.moveToFirst(); it.getString(0) })
        assertEquals(2L, pragma(db, "synchronous")) // 2 = FULL
        // A write keeps it (the pragma is per connection, never reset by a transaction).
        db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS t_probe (x INTEGER)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO t_probe VALUES (1)")
        assertEquals(2L, pragma(db, "synchronous"))
    }
}
