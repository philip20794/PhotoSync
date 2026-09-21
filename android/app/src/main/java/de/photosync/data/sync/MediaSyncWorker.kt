package de.photosync.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.PhotoSyncRepository
import de.photosync.data.remote.RetrofitFactory
import kotlinx.coroutines.CancellationException
import java.io.File

class MediaSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val lock = WorkerLock.tryAcquire(File(applicationContext.filesDir, "sync.lock")) ?: return Result.success()
        return lock.use { runLocked() }
    }

    private suspend fun runLocked(): Result {
        val database = AppDatabase.get(applicationContext)
        val server = database.appStateDao().getServer() ?: return Result.success()
        val session = database.appStateDao().getSession() ?: return Result.success()
        val credentials = SecureCredentialStore(applicationContext)
        val token = credentials.readAccessToken() ?: return Result.success()
        val api = RetrofitFactory.create(server.baseUrl, fixedToken = token)
        val scope = remoteScope(server.baseUrl, session.userId)
        val settings = database.settingsDao().get(scope)
            ?: de.photosync.data.local.SyncSettingsEntity(scope).also { database.settingsDao().save(it) }
        val transferWorker = inputData.getBoolean(MEDIA_TRANSFER, false)
        return try {
            val remoteMore = RemoteSyncEngine(applicationContext, database, api, scope).run()
            var foreground = false
            val result = SyncEngine(applicationContext, database, api, session, beforeUpload = {
                if (!foreground) {
                    setForeground(TransferForeground.info(applicationContext, 1001, "PhotoSync synchronisiert Medien"))
                    foreground = true
                }
            }).run(allowMediaTransfers = !settings.wifiOnly || transferWorker)
            try { PushRegistration.register(applicationContext, api) } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Wakeup optimization must never block authoritative sync.
            }
            if (result.retry) {
                database.settingsDao().markFailure(
                    scope,
                    System.currentTimeMillis(),
                    "Synchronisierung wartet auf einen erneuten Versuch",
                )
                if (runAttemptCount >= 2) SyncErrorNotifier.show(
                    applicationContext, database, scope, "Ein Synchronisierungsvorgang schlägt wiederholt fehl.",
                )
            } else {
                database.settingsDao().markSuccess(scope, System.currentTimeMillis())
            }
            if (settings.wifiOnly && !transferWorker && result.moreWork) {
                SyncScheduler.transferNow(applicationContext, wifiOnly = true)
            }
            when (syncWorkDisposition(result.retry, remoteMore || (result.moreWork && (!settings.wifiOnly || transferWorker)))) {
                SyncWorkDisposition.RETRY -> Result.retry()
                SyncWorkDisposition.CONTINUE -> {
                    if (transferWorker) SyncScheduler.continueTransfer(applicationContext, settings.wifiOnly)
                    else SyncScheduler.continueNow(applicationContext)
                    Result.success()
                }
                SyncWorkDisposition.SUCCESS -> Result.success()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            database.settingsDao().markFailure(scope, System.currentTimeMillis(), error.message ?: "Synchronisierung fehlgeschlagen")
            if (!error.isAuthenticationFailure()) {
                if (runAttemptCount >= 2) SyncErrorNotifier.show(
                    applicationContext, database, scope, "Der Server oder ein Medientransfer ist wiederholt nicht erreichbar.",
                )
                return Result.retry()
            }
            SyncErrorNotifier.show(applicationContext, database, scope, "Die Anmeldung ist nicht mehr gültig.")
            // Clear the token first: even a process kill before the Room update leaves the UI
            // in an unauthenticated state and prevents another request with a revoked token.
            if (credentials.readAccessToken() == token && database.appStateDao().getSession()?.deviceId == session.deviceId) {
                PhotoSyncRepository(applicationContext, database, credentials).signOut()
            }
            Result.success()
        }
    }

    companion object { const val MEDIA_TRANSFER = "media_transfer" }
}

internal enum class SyncWorkDisposition { SUCCESS, CONTINUE, RETRY }

internal fun syncWorkDisposition(realError: Boolean, moreWork: Boolean): SyncWorkDisposition = when {
    realError -> SyncWorkDisposition.RETRY
    moreWork -> SyncWorkDisposition.CONTINUE
    else -> SyncWorkDisposition.SUCCESS
}
