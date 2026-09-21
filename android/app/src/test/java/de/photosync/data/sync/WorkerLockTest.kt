package de.photosync.data.sync

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkerLockTest {
    @Test fun simultaneousWorkersCannotStealOwnershipAndReleaseAllowsRetry() {
        val directory = Files.createTempDirectory("sync-lock-test").toFile()
        val path = java.io.File(directory, "lock")
        try {
            val first = requireNotNull(WorkerLock.tryAcquire(path))
            first.use { repeat(100) { assertNull(WorkerLock.tryAcquire(path)) } }
            requireNotNull(WorkerLock.tryAcquire(path)).close()
        } finally { path.delete(); directory.delete() }
    }
}
