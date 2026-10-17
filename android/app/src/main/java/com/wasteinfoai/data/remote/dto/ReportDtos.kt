package com.wasteinfoai.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ReportCreateRequest(
    @SerializedName("client_uuid") val clientUuid: String,
    @SerializedName("waste_category") val wasteCategory: String,
    @SerializedName("description") val description: String,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("manual_location") val manualLocation: String? = null,
    @SerializedName("evidence_ref") val evidenceRef: String? = null,
    @SerializedName("client_created_at") val clientCreatedAt: String? = null
)

data class ReportResponse(
    @SerializedName("id") val id: String,
    @SerializedName("client_uuid") val clientUuid: String,
    @SerializedName("owner_id") val ownerId: String?,
    @SerializedName("institution_id") val institutionId: String?,
    @SerializedName("waste_category") val wasteCategory: String,
    @SerializedName("description") val description: String,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("manual_location") val manualLocation: String?,
    @SerializedName("evidence_ref") val evidenceRef: String?,
    @SerializedName("status") val status: String,
    @SerializedName("version") val version: Int,
    @SerializedName("duplicate_of_id") val duplicateOfId: String?,
    @SerializedName("assigned_worker_id") val assignedWorkerId: String?,
    @SerializedName("reviewed_at") val reviewedAt: String?,
    @SerializedName("review_note") val reviewNote: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class ReportListResponse(
    @SerializedName("items") val items: List<ReportResponse>,
    @SerializedName("next_cursor") val nextCursor: String?,
    @SerializedName("has_more") val hasMore: Boolean
)

data class ReportUpdateRequest(
    @SerializedName("expected_version") val expectedVersion: Int,
    @SerializedName("waste_category") val wasteCategory: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("manual_location") val manualLocation: String? = null,
    @SerializedName("evidence_ref") val evidenceRef: String? = null
)

data class ReviewReportRequest(
    @SerializedName("decision") val decision: String, // VERIFIED, REJECTED, DUPLICATE
    @SerializedName("note") val note: String? = null,
    @SerializedName("duplicate_of_id") val duplicateOfId: String? = null,
    @SerializedName("expected_version") val expectedVersion: Int? = null
)

data class AssignReportRequest(
    @SerializedName("worker_id") val workerId: String,
    @SerializedName("expected_version") val expectedVersion: Int? = null
)

data class ResolveReportRequest(
    @SerializedName("note") val note: String? = null,
    @SerializedName("expected_version") val expectedVersion: Int? = null
)
