package de.photosync.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MediaWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SyncScheduler.runNow(applicationContext)
        SyncScheduler.watchMedia(applicationContext, rearm = true)
        return Result.success()
    }
}
