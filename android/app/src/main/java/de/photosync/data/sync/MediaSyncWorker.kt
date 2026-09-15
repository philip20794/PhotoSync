package de.photosync.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.remote.RetrofitFactory

class MediaSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val database = AppDatabase.get(applicationContext)
        val server = database.appStateDao().getServer() ?: return Result.success()
        val session = database.appStateDao().getSession() ?: return Result.success()
        val credentials = SecureCredentialStore(applicationContext)
        if (credentials.readAccessToken() == null) return Result.success()
        val api = RetrofitFactory.create(server.baseUrl, credentials)
        val result = SyncEngine(applicationContext, database, api, session).run()
        return when {
            result.retry -> Result.retry()
            result.moreWork -> {
                SyncScheduler.runNow(applicationContext)
                Result.success()
            }
            else -> Result.success()
        }
    }
}
