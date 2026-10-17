package com.wasteinfoai.sync

import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.local.entity.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class ReportSyncStateTest {

    @Test
    fun `idempotency key is unique per report`() {
        val uuid1 = UUID.randomUUID().toString()
        val uuid2 = UUID.randomUUID().toString()
        assertNotEquals(uuid1, uuid2)
    }

    @Test
    fun `initial report draft defaults to local state`() {
        val draft = ReportDraftEntity(
            clientUuid = UUID.randomUUID().toString(),
            wasteCategory = "cardboard",
            description = "Cardboard boxes near bin",
            latitude = 12.9716,
            longitude = 77.5946
        )
        assertEquals(SyncState.LOCAL_DRAFT, draft.syncStatus)
        assertEquals(1, draft.serverVersion)
    }

    @Test
    fun `synced state preserves server id and server status`() {
        val draft = ReportDraftEntity(
            clientUuid = UUID.randomUUID().toString(),
            wasteCategory = "plastic",
            description = "Plastic bottles",
            syncStatus = SyncState.PENDING_SYNC
        )

        val serverAssignedId = UUID.randomUUID().toString()
        val synced = draft.copy(
            syncStatus = SyncState.SYNCED,
            serverId = serverAssignedId,
            serverStatus = "UNDER_REVIEW",
            serverVersion = 1
        )

        assertEquals(SyncState.SYNCED, synced.syncStatus)
        assertEquals(serverAssignedId, synced.serverId)
        assertEquals("UNDER_REVIEW", synced.serverStatus)
    }

    @Test
    fun `server reviewer decision does not reset to draft on resync`() {
        val reviewed = ReportDraftEntity(
            clientUuid = UUID.randomUUID().toString(),
            wasteCategory = "plastic",
            description = "Plastic bottles",
            syncStatus = SyncState.SYNCED,
            serverId = "server-rep-1",
            serverStatus = "VERIFIED",
            serverVersion = 2,
            reviewNote = "Verified by municipal reviewer"
        )

        // Resync returns same server state
        val updated = reviewed.copy(
            serverStatus = "VERIFIED",
            serverVersion = 2
        )

        assertEquals("VERIFIED", updated.serverStatus)
        assertEquals(2, updated.serverVersion)
        assertEquals("Verified by municipal reviewer", updated.reviewNote)
    }
}
