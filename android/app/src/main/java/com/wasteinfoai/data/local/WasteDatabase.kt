package com.wasteinfoai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.wasteinfoai.data.local.dao.FeedbackDao
import com.wasteinfoai.data.local.dao.ReportDao
import com.wasteinfoai.data.local.dao.ScanDao
import com.wasteinfoai.data.local.dao.TaskDao
import com.wasteinfoai.data.local.entity.FeedbackChoice
import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.local.entity.ScanFeedbackEntity
import com.wasteinfoai.data.local.entity.ScanHistoryEntity
import com.wasteinfoai.data.local.entity.SyncState
import com.wasteinfoai.data.local.entity.TaskEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromSyncState(value: SyncState): String = value.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = runCatching { SyncState.valueOf(value) }.getOrDefault(SyncState.LOCAL_DRAFT)

    @TypeConverter
    fun fromFeedbackChoice(value: FeedbackChoice): String = value.name

    @TypeConverter
    fun toFeedbackChoice(value: String): FeedbackChoice = runCatching { FeedbackChoice.valueOf(value) }.getOrDefault(FeedbackChoice.NOT_SURE)
}

@Database(
    entities = [
        ScanHistoryEntity::class,
        ReportDraftEntity::class,
        TaskEntity::class,
        ScanFeedbackEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class WasteDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao
    abstract fun reportDao(): ReportDao
    abstract fun taskDao(): TaskDao
    abstract fun feedbackDao(): FeedbackDao

    companion object {
        private const val DB_NAME = "wasteinfoai.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_history ADD COLUMN specificItem TEXT")
                db.execSQL("ALTER TABLE scan_history ADD COLUMN materialCode TEXT")
                db.execSQL("ALTER TABLE scan_history ADD COLUMN isUserConfirmed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scan_history ADD COLUMN userConfirmedCategory TEXT")
                db.execSQL("ALTER TABLE scan_history ADD COLUMN detectedBarcode TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scan_feedback (
                        feedbackUuid TEXT PRIMARY KEY NOT NULL,
                        scanUuid TEXT NOT NULL,
                        originalCategory TEXT NOT NULL,
                        originalConfidence REAL NOT NULL,
                        topKJson TEXT NOT NULL,
                        detectorLabel TEXT,
                        userChoice TEXT NOT NULL,
                        userConfirmedCategory TEXT,
                        modelVersion TEXT NOT NULL,
                        preprocessingVersion TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        hasUploadConsent INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL,
                        syncErrorMessage TEXT
                    )
                """.trimIndent())
            }
        }

        @Volatile
        private var INSTANCE: WasteDatabase? = null

        fun getInstance(context: Context): WasteDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WasteDatabase::class.java,
                    DB_NAME
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
        }
    }
}
