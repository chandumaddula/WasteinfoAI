package com.wasteinfoai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.wasteinfoai.data.local.dao.ReportDao
import com.wasteinfoai.data.local.dao.ScanDao
import com.wasteinfoai.data.local.dao.TaskDao
import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.local.entity.ScanHistoryEntity
import com.wasteinfoai.data.local.entity.SyncState
import com.wasteinfoai.data.local.entity.TaskEntity

class Converters {
    @TypeConverter
    fun fromSyncState(value: SyncState): String = value.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = runCatching { SyncState.valueOf(value) }.getOrDefault(SyncState.LOCAL_DRAFT)
}

@Database(
    entities = [
        ScanHistoryEntity::class,
        ReportDraftEntity::class,
        TaskEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class WasteDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao
    abstract fun reportDao(): ReportDao
    abstract fun taskDao(): TaskDao

    companion object {
        private const val DB_NAME = "wasteinfoai.db"

        @Volatile
        private var INSTANCE: WasteDatabase? = null

        fun getInstance(context: Context): WasteDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WasteDatabase::class.java,
                    DB_NAME
                )
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
        }
    }
}
