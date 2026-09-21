package de.photosync.data.sync

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.workDataOf
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val IMMEDIATE_WORK = "photosync-media-sync"
    private const val PUSH_WAKE_WORK = "photosync-media-push-wake"
    private const val PERIODIC_WORK = "photosync-media-inventory"
    private const val TRANSFER_WORK = "photosync-media-transfer"

    fun watchMedia(context: Context, rearm: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<MediaWakeWorker>()
            .setConstraints(Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
                .build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("photosync-media-observer",
            if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    internal fun transferConstraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("photosync-local-control",
            ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<LocalScanWorker>(15, TimeUnit.MINUTES).build())
        val request = PeriodicWorkRequestBuilder<MediaSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("photosync-local-scan", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalScanWorker>().build())
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Successful bounded work continues without incrementing WorkManager's failure backoff. */
    fun continueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setInitialDelay(1, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun transferNow(context: Context, wifiOnly: Boolean, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setInputData(workDataOf(MediaSyncWorker.MEDIA_TRANSFER to true))
            .setConstraints(transferConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            TRANSFER_WORK,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun networkPolicyChanged(context: Context) {
        // Cancelling the unconstrained control run also cancels an upload which was
        // started before WLAN-only was enabled. The replacement control/transfer
        // jobs resume from the durable queue under the new constraints.
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(IMMEDIATE_WORK)
    }

    fun continueTransfer(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setInputData(workDataOf(MediaSyncWorker.MEDIA_TRANSFER to true))
            .setInitialDelay(1, TimeUnit.SECONDS)
            .setConstraints(transferConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            TRANSFER_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        )
    }

    /**
     * A push uses an independent unique slot, so an immediate worker currently in
     * genuine error backoff cannot delay a newly available server change.
     */
    fun wakeNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            PUSH_WAKE_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
