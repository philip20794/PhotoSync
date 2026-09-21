package de.photosync.data.sync

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** OS-owned lock: released on process death, unlike a persisted 'running' flag. */
class WorkerLock private constructor(private val file: RandomAccessFile, private val lock: java.nio.channels.FileLock) : AutoCloseable {
    override fun close() { try { lock.release() } finally { file.close() } }
    companion object {
        fun tryAcquire(path: File): WorkerLock? {
            path.parentFile?.mkdirs()
            val file = RandomAccessFile(path, "rw")
            return try {
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) { file.close(); null } else WorkerLock(file, lock)
            } catch (error: Throwable) { file.close(); throw error }
        }
    }
}
