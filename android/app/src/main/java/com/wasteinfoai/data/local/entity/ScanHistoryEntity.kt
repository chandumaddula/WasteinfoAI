package com.wasteinfoai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_history")
data class ScanHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val imagePath: String,
    val category: String,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val ruleId: String? = null,
    val isHazardous: Boolean = false,
    val disposalMethod: String? = null,
    val targetBinColor: String? = null,
    val isSynced: Boolean = false,
    val specificItem: String? = null,
    val materialCode: String? = null,
    val isUserConfirmed: Boolean = false,
    val userConfirmedCategory: String? = null,
    val detectedBarcode: String? = null
)
