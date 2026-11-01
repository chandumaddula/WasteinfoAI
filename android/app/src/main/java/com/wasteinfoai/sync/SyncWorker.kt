package com.wasteinfoai.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wasteinfoai.WasteInfoAIApp
import com.wasteinfoai.data.local.dao.ReportDao
import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.local.entity.SyncState
import com.wasteinfoai.data.remote.ApiService
import com.wasteinfoai.data.remote.dto.ReportCreateRequest
import com.wasteinfoai.data.remote.dto.SyncBatchRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Background WorkManager worker responsible for synchronizing pending offline reports
 * with the FastAPI backend via POST /api/v1/sync/reports and synchronizing user feedback
 * with strict consent checks and idempotency.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "wasteinfoai_offline_sync"
        private const val TAG = "SyncWorker"
        private const val MAX_BATCH_SIZE = 50
        private const val MAX_BATCHES_PER_RUN = 5 // drain a backlog in one run instead of 50 reports / 15 min
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting offline sync work (attempt #$runAttemptCount)...")
        val app = applicationContext as? WasteInfoAIApp ?: return Result.failure()
        val reportDao = app.appContainer.wasteDatabase.reportDao()
        val apiService = app.appContainer.apiService

        var reportResult: Result = Result.success()
        var batches = 0
        while (batches < MAX_BATCHES_PER_RUN) {
            val pending = reportDao.getPendingBatch(MAX_BATCH_SIZE)
            if (pending.isEmpty()) {
                if (batches == 0) Log.i(TAG, "No pending reports found for sync.")
                break
            }
            reportResult = syncReports(reportDao, apiService, pending)
            batches++
            if (reportResult != Result.success() || pending.size < MAX_BATCH_SIZE) break
        }

        // Decoupled, consent-aware feedback synchronization
        syncPendingFeedback(app)

        return reportResult
    }

    private suspend fun syncReports(
        reportDao: ReportDao,
        apiService: ApiService,
        pending: List<ReportDraftEntity>
    ): Result {
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
            } else {
                val code = response.code()
                when {
                    // transient: retry with backoff
                    code in 500..599 || code == 408 || code == 429 -> {
                        Log.w(TAG, "Transient sync error ($code), retrying with backoff...")
                        Result.retry()
                    }
                    // not logged in / session expired: keep reports pending, do not hammer the server
                    code == 401 || code == 403 -> {
                        Log.w(TAG, "Sync not authorized ($code); reports stay pending until the next login.")
                        Result.success()
                    }
                    // other 4xx will never succeed by retrying the same request
                    else -> {
                        Log.e(TAG, "Sync request rejected with status: $code ${response.message()}")
                        Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network exception during sync: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun syncPendingFeedback(app: WasteInfoAIApp) {
        runCatching {
            val feedbackDao = app.appContainer.wasteDatabase.feedbackDao()
            val pendingFeedback = feedbackDao.getPendingFeedbackBatch(MAX_BATCH_SIZE)
            if (pendingFeedback.isEmpty()) return

            Log.i(TAG, "Evaluating ${pendingFeedback.size} feedback items for consent-aware sync...")
            for (feedback in pendingFeedback) {
                // Rule 1: Respect user consent strictly
                if (!feedback.hasUploadConsent) {
                    feedbackDao.updateSyncState(feedback.feedbackUuid, SyncState.LOCAL_DRAFT)
                    continue
                }

                // BUG FIX: this branch used to mark feedback SYNCED without sending anything, silently
                // discarding consented feedback. Never mark SYNCED unless an upload actually succeeded.
                // TODO: add a feedback endpoint to ApiService, upload using feedbackUuid as the idempotency key,
                //       and call feedbackDao.updateSyncState(feedback.feedbackUuid, SyncState.SYNCED) on success only.
                Log.d(TAG, "Consented feedback ${feedback.feedbackUuid} is waiting for an upload endpoint")
            }
        }.onFailure { e ->
            Log.w(TAG, "Non-fatal error processing feedback sync: ${e.message}")
        }
    }
}
