package com.aktcl.aron.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Makes every NEW user database file `auto_vacuum = INCREMENTAL` (F-SYS-028 follow-up), so `LocalPurge`'s
 * `PRAGMA incremental_vacuum` returns the purged pages to the file system. The pragma runs in `onConfigure`, before Room
 * creates any table, which is the only moment NONE can change without a VACUUM. On an existing NONE file it is a no-op;
 * an existing FULL file (the framework SQLite default) switches to INCREMENTAL in place, which SQLite allows without a
 * rewrite. No file is ever rewritten here, so no open, sale or kill risks a long exclusive VACUUM. Files created before this change (pilot and dev phones only) keep reclaiming nothing until the
 * logout wipe or a reinstall recreates them.
 */
class IncrementalVacuumFactory(private val delegate: SupportSQLiteOpenHelper.Factory?) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val inner = configuration.callback
        val wrapped = object : SupportSQLiteOpenHelper.Callback(inner.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                // Before the delegate (Room sets its own pragmas there); never fails the open.
                runCatching { db.query("PRAGMA auto_vacuum = INCREMENTAL").use { it.moveToFirst() } }
                inner.onConfigure(db)
            }
            override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) = inner.onCorruption(db)
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(wrapped)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
            .build()
        return (delegate ?: FrameworkSQLiteOpenHelperFactory()).create(config)
    }
}
