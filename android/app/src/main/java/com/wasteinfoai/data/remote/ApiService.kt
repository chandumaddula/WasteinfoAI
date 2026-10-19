package com.wasteinfoai.data.remote

import com.wasteinfoai.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit contract matching FastAPI backend routes at /api/v1
 */
interface ApiService {

    // Auth
    @POST("api/v1/auth/token")
    suspend fun login(@Body request: LoginRequest): Response<TokenResponse>

    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<UserResponse>

    @GET("api/v1/auth/me")
    suspend fun getProfile(): Response<UserResponse>

    // Reports
    @POST("api/v1/reports")
    suspend fun createReport(@Body request: ReportCreateRequest): Response<ReportResponse>

    @GET("api/v1/reports")
    suspend fun listReports(
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
        @Query("status") status: String? = null
    ): Response<ReportListResponse>

    @GET("api/v1/reports/{report_id}")
    suspend fun getReportById(@Path("report_id") reportId: String): Response<ReportResponse>

    @PATCH("api/v1/reports/{report_id}")
    suspend fun updateReport(
        @Path("report_id") reportId: String,
        @Body request: ReportUpdateRequest
    ): Response<ReportResponse>

    @POST("api/v1/reports/{report_id}/review")
    suspend fun reviewReport(
        @Path("report_id") reportId: String,
        @Body request: ReviewReportRequest
    ): Response<ReportResponse>

    @POST("api/v1/reports/{report_id}/assign")
    suspend fun assignReport(
        @Path("report_id") reportId: String,
        @Body request: AssignReportRequest
    ): Response<ReportResponse>

    @POST("api/v1/reports/{report_id}/resolve")
    suspend fun resolveReport(
        @Path("report_id") reportId: String,
        @Body request: ResolveReportRequest
    ): Response<ReportResponse>

    // Offline Sync
    @POST("api/v1/sync/reports")
    suspend fun syncBatch(@Body request: SyncBatchRequest): Response<SyncBatchResponse>

    @GET("api/v1/sync/changes")
    suspend fun syncChanges(
        @Query("cursor") cursor: String? = null,
        @Query("since") since: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<SyncChangesResponse>
}
