package com.wasteinfoai.data.repository

import com.wasteinfoai.core.common.Resource
import com.wasteinfoai.data.local.dao.TaskDao
import com.wasteinfoai.data.local.entity.TaskEntity
import com.wasteinfoai.data.remote.ApiService
import com.wasteinfoai.data.remote.dto.ResolveReportRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TaskRepository(
    private val taskDao: TaskDao,
    private val apiService: ApiService
) {

    fun getAssignedTasksFlow(): Flow<List<TaskEntity>> = taskDao.getAllTasksFlow()

    suspend fun refreshAssignedTasks(): Resource<List<TaskEntity>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.listReports(status = "ASSIGNED")
            if (response.isSuccessful && response.body() != null) {
                val reports = response.body()!!.items
                val entities = reports.map { r ->
                    TaskEntity(
                        id = r.id,
                        wasteCategory = r.wasteCategory,
                        description = r.description,
                        latitude = r.latitude,
                        longitude = r.longitude,
                        manualLocation = r.manualLocation,
                        evidenceRef = r.evidenceRef,
                        status = r.status,
                        version = r.version
                    )
                }
                taskDao.insertOrUpdateTasks(entities)
                Resource.Success(entities)
            } else {
                Resource.Error("Failed to fetch assigned tasks: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Resource.Error("Offline: Loading cached tasks", e)
        }
    }

    suspend fun resolveTask(taskId: String, note: String?): Resource<Unit> = withContext(Dispatchers.IO) {
        try {
            taskDao.markResolvedLocally(taskId, note)
            val response = apiService.resolveReport(taskId, ResolveReportRequest(note = note))
            if (response.isSuccessful) {
                Resource.Success(Unit)
            } else {
                Resource.Error("Server resolution pending (${response.code()})")
            }
        } catch (e: Exception) {
            Resource.Error("Marked resolved locally. Will sync when online.", e)
        }
    }
}
