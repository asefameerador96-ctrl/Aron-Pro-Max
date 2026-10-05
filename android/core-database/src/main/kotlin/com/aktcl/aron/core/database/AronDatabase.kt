package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.aktcl.aron.core.database.dao.CaptureDao
import com.aktcl.aron.core.database.dao.OutboxDao
import com.aktcl.aron.core.database.dao.ReferenceDao
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.QcLineEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.entity.VisitEntity

/**
 * One user's database on the phone (`aron-u<user_id>.db`, docs/24 s5.2, D24-32): reference data of the day bundle, the
 * SR day's captures and the outbox. Schema JSON is exported to android/core-database/schemas; every later version ships
 * a Migration with a test, and destructive migration is never enabled.
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        RouteEntity::class, OutletEntity::class, SkuEntity::class,
        GeoFixEntity::class, AttendanceEventEntity::class, StockMovementEntity::class,
        VisitEntity::class, VisitCloseEntity::class, MemoEntity::class, MemoLineEntity::class,
        MemoDiscountEntity::class, QcLineEntity::class,
        OutboxEntity::class, SyncMetaEntity::class,
    ],
)
abstract class AronDatabase : RoomDatabase() {
    abstract fun captureDao(): CaptureDao
    abstract fun outboxDao(): OutboxDao
    abstract fun referenceDao(): ReferenceDao

    companion object {
        /** Device-originated tables: each is keyed by a client UUID and must reject a second insert of it. */
        val DEVICE_TABLES: List<String> = listOf(
            "geo_fix", "attendance_event", "stock_movement", "visit", "visit_close", "memo", "memo_line", "memo_discount", "qc_line",
        )

        fun fileName(userId: Long): String {
            require(userId > 0) { "user id must be positive" }
            return "aron-u$userId.db"
        }

        /**
         * Opens the database of [userId]. Production passes the SQLCipher [openHelperFactory] keyed by a Keystore-wrapped
         * passphrase (docs/24 s5.2; [SqlCipher.factory]); tests pass null for plain SQLite. WAL keeps a kill mid-write safe.
         */
        fun open(context: Context, userId: Long, openHelperFactory: SupportSQLiteOpenHelper.Factory?): AronDatabase =
            Room.databaseBuilder(context.applicationContext, AronDatabase::class.java, fileName(userId))
                .apply { if (openHelperFactory != null) openHelperFactory(openHelperFactory) }
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
