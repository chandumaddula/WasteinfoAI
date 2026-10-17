package com.wasteinfoai.data.repository

import com.wasteinfoai.data.local.dao.ScanDao
import com.wasteinfoai.data.local.entity.ScanHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ScanRepository(private val scanDao: ScanDao) {

    fun getScanHistory(): Flow<List<ScanHistoryEntity>> = scanDao.getAllScans()

    suspend fun saveScan(scan: ScanHistoryEntity): Long = withContext(Dispatchers.IO) {
        scanDao.insertScan(scan)
    }

    suspend fun getScanById(id: Long): ScanHistoryEntity? = withContext(Dispatchers.IO) {
        scanDao.getScanById(id)
    }

    suspend fun deleteScan(id: Long) = withContext(Dispatchers.IO) {
        scanDao.deleteScan(id)
    }
}
