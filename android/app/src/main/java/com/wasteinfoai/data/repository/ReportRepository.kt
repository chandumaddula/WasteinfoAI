package com.wasteinfoai.data.repository

import com.wasteinfoai.core.common.Resource
import com.wasteinfoai.data.local.dao.ReportDao
import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.local.entity.SyncState
import com.wasteinfoai.data.remote.ApiService
import com.wasteinfoai.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class ReportRepository(
    private val reportDao: ReportDao,
    private val apiService: ApiService
) {

    fun getAllReportsFlow(): Flow<List<ReportDraftEntity>> = reportDao.getAllReportsFlow()

    suspend fun getByClientUuid(clientUuid: String): ReportDraftEntity? = withContext(Dispatchers.IO) {
        reportDao.getByClientUuid(clientUuid)
    }

    suspend fun createAndSaveDraft(
        wasteCategory: String,
        description: String,
        latitude: Double?,
        longitude: Double?,
        manualLocation: String?,
        evidenceRef: String?,
        localImagePath: String?,
        isReadyToSync: Boolean = true
    ): ReportDraftEntity = withContext(Dispatchers.IO) {
        val clientUuid = UUID.randomUUID().toString()
        val entity = ReportDraftEntity(
            clientUuid = clientUuid,
            wasteCategory = wasteCategory,
            description = description,
            latitude = latitude,
            longitude = longitude,
            manualLocation = manualLocation,
            evidenceRef = evidenceRef,
            localImagePath = localImagePath,
            syncStatus = if (isReadyToSync) SyncState.PENDING_SYNC else SyncState.LOCAL_DRAFT,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        reportDao.insertOrUpdate(entity)
        entity
    }

    suspend fun saveReport(report: ReportDraftEntity) = withContext(Dispatchers.IO) {
        reportDao.insertOrUpdate(report)
    }

    suspend fun submitReport(report: ReportDraftEntity): Resource<ReportResponse> = withContext(Dispatchers.IO) {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val request = ReportCreateRequest(
            clientUuid = report.clientUuid,
            wasteCategory = report.wasteCategory,
            description = report.description,
            latitude = report.latitude,
            longitude = report.longitude,
            manualLocation = report.manualLocation,
            evidenceRef = report.evidenceRef,
            clientCreatedAt = isoFormat.format(Date(report.createdAt))
        )

        try {
            reportDao.updateSyncState(report.clientUuid, SyncState.SYNCING)
            val response = apiService.createReport(request)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val syncedEntity = report.copy(
                    syncStatus = SyncState.SYNCED,
                    serverId = body.id,
                    serverStatus = body.status,
                    serverVersion = body.version,
                    reviewNote = body.reviewNote,
                    assignedWorkerId = body.assignedWorkerId,
                    lastSyncError = null
                )
                reportDao.insertOrUpdate(syncedEntity)
                Resource.Success(body)
            } else if (response.code() == 409) {
                // Idempotency conflict
                reportDao.updateSyncState(report.clientUuid, SyncState.CONFLICT, "Idempotency conflict: report with this client UUID already exists with differing payload")
                Resource.Error("Conflict: Report with this client UUID exists on server with different content.")
            } else if (response.code() == 422) {
                reportDao.updateSyncState(report.clientUuid, SyncState.PERMANENT_FAILURE, "Validation error: ${response.message()}")
                Resource.Error("Validation error: ${response.message()}")
            } else {
                reportDao.updateSyncState(report.clientUuid, SyncState.PENDING_SYNC, "Server returned ${response.code()}: ${response.message()}")
                Resource.Error("Server error ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            // Keep in PENDING_SYNC for offline worker retry
            reportDao.updateSyncState(report.clientUuid, SyncState.PENDING_SYNC, e.localizedMessage)
            Resource.Error("Network offline: Saved locally and queued for background sync.")
        }
    }

    suspend fun markAllPendingAsSynced() = withContext(Dispatchers.IO) {
        reportDao.markAllPendingAsSynced()
    }

    suspend fun syncPendingReports(): Resource<Int> = withContext(Dispatchers.IO) {
        val pending = reportDao.getPendingBatch(50)
        if (pending.isEmpty()) return@withContext Resource.Success(0)

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val requestItems = pending.map { report ->
            ReportCreateRequest(
                clientUuid = report.clientUuid,
                wasteCategory = report.wasteCategory,
                description = report.description,
                latitude = report.latitude,
                longitude = report.longitude,
                manualLocation = report.manualLocation,
                evidenceRef = report.evidenceRef,
                clientCreatedAt = isoFormat.format(Date(report.createdAt))
            )
        }

        try {
            val response = apiService.syncBatch(SyncBatchRequest(items = requestItems))
            if (response.isSuccessful && response.body() != null) {
                val batchResult = response.body()!!
                var syncedCount = 0
                for (item in batchResult.results) {
                    val local = pending.find { it.clientUuid == item.clientUuid } ?: continue
                    when (item.status) {
                        "ACCEPTED", "ALREADY_PROCESSED" -> {
                            val serverReport = item.report
                            val updated = local.copy(
                                syncStatus = SyncState.SYNCED,
                                serverId = serverReport?.id ?: local.serverId,
                                serverStatus = serverReport?.status ?: local.serverStatus,
                                serverVersion = serverReport?.version ?: local.serverVersion,
                                reviewNote = serverReport?.reviewNote ?: local.reviewNote,
                                assignedWorkerId = serverReport?.assignedWorkerId ?: local.assignedWorkerId,
                                lastSyncError = null
                            )
                            reportDao.insertOrUpdate(updated)
                            syncedCount++
                        }
                        "CONFLICT" -> {
                            reportDao.updateSyncState(item.clientUuid, SyncState.CONFLICT, item.error?.message ?: "Idempotency conflict")
                        }
                        "REJECTED" -> {
                            reportDao.updateSyncState(item.clientUuid, SyncState.PERMANENT_FAILURE, item.error?.message ?: "Rejected by server")
                        }
                        else -> {
                            reportDao.updateSyncState(item.clientUuid, SyncState.PENDING_SYNC, "Unknown status: ${item.status}")
                        }
                    }
                }
                Resource.Success(syncedCount)
            } else {
                Resource.Error("Server returned code ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            Resource.Error("Could not connect to server: ${e.localizedMessage ?: "Network error"}")
        }
    }

    suspend fun fetchRemoteReports(status: String? = null): Resource<List<ReportResponse>> = withContext(Dispatchers.IO) {
        try {
            val res = apiService.listReports(limit = 100, status = status)
            if (res.isSuccessful && res.body() != null) {
                Resource.Success(res.body()!!.items)
            } else {
                Resource.Error("Failed to fetch reports: ${res.code()} ${res.message()}")
            }
        } catch (e: Exception) {
            Resource.Error("Could not reach backend: ${e.localizedMessage}")
        }
    }

    suspend fun reviewReport(
        reportId: String,
        decision: String,
        note: String?,
        duplicateOfId: String? = null
    ): Resource<ReportResponse> = withContext(Dispatchers.IO) {
        try {
            val req = ReviewReportRequest(decision = decision, note = note, duplicateOfId = duplicateOfId)
            val res = apiService.reviewReport(reportId, req)
            if (res.isSuccessful && res.body() != null) {
                Resource.Success(res.body()!!)
            } else {
                Resource.Error("Review failed (${res.code()}): ${res.message()}")
            }
        } catch (e: Exception) {
            Resource.Error("Error submitting review: ${e.localizedMessage}", e)
        }
    }

    suspend fun assignReport(reportId: String, workerId: String): Resource<ReportResponse> = withContext(Dispatchers.IO) {
        try {
            val req = AssignReportRequest(workerId = workerId)
            val res = apiService.assignReport(reportId, req)
            if (res.isSuccessful && res.body() != null) {
                Resource.Success(res.body()!!)
            } else {
                Resource.Error("Assign failed (${res.code()}): ${res.message()}")
            }
        } catch (e: Exception) {
            Resource.Error("Error assigning worker: ${e.localizedMessage}", e)
        }
    }

    suspend fun resolveReport(reportId: String, note: String?): Resource<ReportResponse> = withContext(Dispatchers.IO) {
        try {
            val req = ResolveReportRequest(note = note)
            val res = apiService.resolveReport(reportId, req)
            if (res.isSuccessful && res.body() != null) {
                Resource.Success(res.body()!!)
            } else {
                Resource.Error("Resolve failed (${res.code()}): ${res.message()}")
            }
        } catch (e: Exception) {
            Resource.Error("Error resolving report: ${e.localizedMessage}", e)
        }
    }
}
