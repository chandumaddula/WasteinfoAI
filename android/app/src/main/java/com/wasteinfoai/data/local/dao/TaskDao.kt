package com.wasteinfoai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wasteinfoai.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTasks(tasks: List<TaskEntity>)

    @Query("SELECT * FROM tasks ORDER BY assignedAt DESC")
    fun getAllTasksFlow(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): TaskEntity?

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks SET isResolvedLocally = 1, resolutionNote = :note, status = 'RESOLVED' WHERE id = :id")
    suspend fun markResolvedLocally(id: String, note: String?)
}
