package com.wasteinfoai.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wasteinfoai.WasteInfoAIApp
import com.wasteinfoai.data.local.entity.SyncState
import com.wasteinfoai.data.remote.dto.ReportCreateRequest
import com.wasteinfoai.data.remote.dto.SyncBatchRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Background WorkManager worker responsible for synchronizing pending offline reports
 * with the FastAPI backend via POST /api/v1/sync/reports.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "wasteinfoai_offline_sync"
        private const val TAG = "SyncWorker"
        private const val MAX_BATCH_SIZE = 50
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting offline sync work (attempt #$runAttemptCount)...")
        val app = applicationContext as? WasteInfoAIApp ?: return Result.failure()
        val reportDao = app.appContainer.wasteDatabase.reportDao()
        val apiService = app.appContainer.apiService

        val pending = reportDao.getPendingBatch(MAX_BATCH_SIZE)
        if (pending.isEmpty()) {
            Log.i(TAG, "No pending reports found for sync.")
            return Result.success()
        }

        Log.i(TAG, "Syncing batch of ${pending.size} pending reports...")

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

        return try {
            val response = apiService.syncBatch(SyncBatchRequest(items = requestItems))
            if (response.isSuccessful && response.body() != null) {
                val batchResult = response.body()!!
                Log.i(TAG, "Sync batch response received with ${batchResult.results.size} item results.")

                // Process item results individually
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
                            Log.i(TAG, "Report ${item.clientUuid} synced successfully (${item.status})")
                        }
                        "CONFLICT" -> {
                            reportDao.updateSyncState(
                                item.clientUuid,
                                SyncState.CONFLICT,
                                item.error?.message ?: "Idempotency conflict on server"
                            )
                            Log.w(TAG, "Report ${item.clientUuid} resulted in CONFLICT")
                        }
                        "REJECTED" -> {
                            reportDao.updateSyncState(
                                item.clientUuid,
                                SyncState.PERMANENT_FAILURE,
                                item.error?.message ?: "Rejected by server validation"
                            )
                            Log.e(TAG, "Report ${item.clientUuid} REJECTED by server: ${item.error?.message}")
                        }
                        else -> {
                            reportDao.updateSyncState(
                                item.clientUuid,
                                SyncState.PENDING_SYNC,
                                "Unknown item status: ${item.status}"
                            )
                        }
                    }
                }
                Result.success()
            } else if (response.code() in 500..599) {
                // Retryable server error: retry with exponential backoff
                Log.w(TAG, "Server error (${response.code()}), retrying with backoff...")
                Result.retry()
            } else {
                Log.e(TAG, "Sync request failed with status: ${response.code()} ${response.message()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network exception during sync: ${e.message}", e)
            Result.retry()
        }
    }
}
