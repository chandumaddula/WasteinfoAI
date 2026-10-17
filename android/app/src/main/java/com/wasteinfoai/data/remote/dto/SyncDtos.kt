package com.wasteinfoai.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SyncBatchRequest(
    @SerializedName("items") val items: List<ReportCreateRequest>
)

data class SyncItemResult(
    @SerializedName("index") val index: Int,
    @SerializedName("client_uuid") val clientUuid: String,
    @SerializedName("status") val status: String, // ACCEPTED, ALREADY_PROCESSED, CONFLICT, REJECTED
    @SerializedName("report") val report: ReportResponse?,
    @SerializedName("error") val error: ApiErrorDetail?
)

data class SyncBatchResponse(
    @SerializedName("results") val results: List<SyncItemResult>,
    @SerializedName("server_time") val serverTime: String
)

data class SyncChangesResponse(
    @SerializedName("items") val items: List<ReportResponse>,
    @SerializedName("next_cursor") val nextCursor: String?,
    @SerializedName("has_more") val hasMore: Boolean,
    @SerializedName("server_time") val serverTime: String
)

data class ApiErrorDetail(
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("request_id") val requestId: String?
)

data class ApiErrorEnvelope(
    @SerializedName("error") val error: ApiErrorDetail
)
