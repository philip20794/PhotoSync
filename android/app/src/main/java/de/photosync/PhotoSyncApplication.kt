package de.photosync

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import de.photosync.data.local.AppDatabase
import de.photosync.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class PhotoSyncApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { SyncScheduler.runNow(this@PhotoSyncApplication) }
    }
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedulePeriodic(this)
        SyncScheduler.watchMedia(this)
        de.photosync.data.offline.OfflineLegacyCleanupWorker.enqueue(this)
        de.photosync.data.sync.PushRegistration.refresh(this)
        // Fast foreground/process-live hint. The persisted content-URI job covers
        // process absence, and the periodic full scan covers missed notifications.
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        scope.launch {
            AppDatabase.get(this@PhotoSyncApplication).appStateDao().observeSession().filterNotNull().collect {
                SyncScheduler.runNow(this@PhotoSyncApplication)
            }
        }
    }
}
