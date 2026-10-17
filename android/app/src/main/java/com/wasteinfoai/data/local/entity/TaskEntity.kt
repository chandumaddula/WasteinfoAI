package com.wasteinfoai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String, // Server report ID
    val wasteCategory: String,
    val description: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val manualLocation: String? = null,
    val evidenceRef: String? = null,
    val status: String = "ASSIGNED",
    val version: Int = 1,
    val assignedAt: Long = System.currentTimeMillis(),
    val isResolvedLocally: Boolean = false,
    val resolutionNote: String? = null
)
