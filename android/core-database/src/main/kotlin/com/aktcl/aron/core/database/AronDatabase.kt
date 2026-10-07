package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.aktcl.aron.core.database.dao.CaptureDao
import com.aktcl.aron.core.database.dao.OutboxDao
import com.aktcl.aron.core.database.dao.PrintDao
import com.aktcl.aron.core.database.dao.ReferenceDao
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.MemoCounterEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.entity.PrintEventEntity
import com.aktcl.aron.core.database.entity.PrintJobEntity
import com.aktcl.aron.core.database.entity.TaskEntity
import com.aktcl.aron.core.database.entity.TaskEventEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.ConfigValueEntity
import com.aktcl.aron.core.database.entity.PriceEntity
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
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // v2 (F-SYS-006): price, config_value, bundle_section
        AutoMigration(from = 2, to = 3), // v3: due_collection, visit_skip, day_submit, outlet_change_request, task_event, task, memo_counter, print_event, print_job, memo.printed_at/print_count
        AutoMigration(from = 3, to = 4), // v4 (F-SYS-072): outbox.sig, the record signature made once per row
        AutoMigration(from = 4, to = 5), // v5 (F-SR-020/021): content_item, outlet_content_assignment, survey, survey_question, content_view, survey_response
    ],
    entities = [
        RouteEntity::class, OutletEntity::class, SkuEntity::class,
        GeoFixEntity::class, AttendanceEventEntity::class, StockMovementEntity::class,
        VisitEntity::class, VisitCloseEntity::class, MemoEntity::class, MemoLineEntity::class,
        MemoDiscountEntity::class, QcLineEntity::class,
        OutboxEntity::class, SyncMetaEntity::class,
        PriceEntity::class, ConfigValueEntity::class, BundleSectionEntity::class,
        DueCollectionEntity::class, VisitSkipEntity::class, DaySubmitEntity::class, OutletChangeRequestEntity::class,
        TaskEventEntity::class, TaskEntity::class, MemoCounterEntity::class, PrintEventEntity::class, PrintJobEntity::class,
        com.aktcl.aron.core.database.entity.ContentItemEntity::class, com.aktcl.aron.core.database.entity.OutletContentAssignmentEntity::class,
        com.aktcl.aron.core.database.entity.SurveyEntity::class, com.aktcl.aron.core.database.entity.SurveyQuestionEntity::class,
        com.aktcl.aron.core.database.entity.ContentViewEntity::class, com.aktcl.aron.core.database.entity.SurveyResponseEntity::class,
    ],
)
abstract class AronDatabase : RoomDatabase() {
    abstract fun captureDao(): CaptureDao
    abstract fun outboxDao(): OutboxDao
    abstract fun referenceDao(): ReferenceDao
    abstract fun printDao(): PrintDao

    companion object {
        /** Device-originated tables: each is keyed by a client UUID and must reject a second insert of it. */
        val DEVICE_TABLES: List<String> = listOf(
            "geo_fix", "attendance_event", "stock_movement", "visit", "visit_close", "memo", "memo_line", "memo_discount", "qc_line",
            "due_collection", "visit_skip", "day_submit", "outlet_change_request", "task_event", "print_event",
            "content_view", "survey_response",
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
                .openHelperFactory(IncrementalVacuumFactory(openHelperFactory)) // new files reclaim purged pages
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
