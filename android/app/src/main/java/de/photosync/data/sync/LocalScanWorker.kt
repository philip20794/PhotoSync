package de.photosync.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.photosync.data.local.AppDatabase
import de.photosync.data.remote.RetrofitFactory
import java.io.File

/** Inventory requires no connectivity and never issues an HTTP request. */
class LocalScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val lock = WorkerLock.tryAcquire(File(applicationContext.filesDir, "sync.lock")) ?: return Result.retry()
        return lock.use {
            val db = AppDatabase.get(applicationContext)
            val session = db.appStateDao().getSession() ?: return Result.success()
            val server = db.appStateDao().getServer() ?: return Result.success()
            if (SyncEngine(applicationContext, db, RetrofitFactory.create(server.baseUrl), session).inventorySharedAlbums()) Result.success()
            else Result.retry()
        }
    }
}
