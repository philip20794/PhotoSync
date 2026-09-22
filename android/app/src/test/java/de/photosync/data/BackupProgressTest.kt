package de.photosync.data

import de.photosync.data.local.BackupTransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupProgressTest {
    @Test fun inventoryIsIndeterminateUntilEveryBackupAlbumWasScanned() {
        assertTrue(backupProgressUi(BackupTransferProgress(2, 1, 0, 0, 0, null), false, true).indeterminate)
    }
    @Test fun completedAndResumableBytesContributeWithoutPrematureCompletion() {
        val progress = backupProgressUi(BackupTransferProgress(1, 1, 1_000, 500, 750, null), false, true)
        assertFalse(progress.indeterminate); assertEquals(75, progress.percent); assertEquals("75 % gesichert", progress.status)
    }
    @Test fun completeOnlyAfterServerConfirmedBytesCoverAllMedia() {
        val progress = backupProgressUi(BackupTransferProgress(1, 1, 1_000, 1_000, 1_000, null), false, true)
        assertEquals(100, progress.percent); assertEquals("Backup vollständig", progress.status)
    }
    @Test fun wifiOnlyWaitAndErrorsHaveUnderstandableStates() {
        assertEquals("Wartet auf WLAN", backupProgressUi(BackupTransferProgress(1, 1, 1_000, 0, 0, null), true, false).status)
        val error = backupProgressUi(BackupTransferProgress(1, 1, 1_000, 0, 0, "Netzwerkfehler"), false, true)
        assertEquals("Backup braucht Aufmerksamkeit", error.status); assertTrue(error.isError)
    }
}
