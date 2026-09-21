package de.photosync.data.sync

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import de.photosync.data.remote.PhotoSyncApi
import de.photosync.data.remote.PushTokenRequest

class SyncMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Ignore payload contents: push cannot advance cursors or mutate media.
        SyncScheduler.wakeNow(applicationContext)
    }
    override fun onDeletedMessages() { SyncScheduler.wakeNow(applicationContext) }
    override fun onNewToken(token: String) {
        PushRegistration.remember(applicationContext, token)
        SyncScheduler.wakeNow(applicationContext)
    }
}

object PushRegistration {
    private fun preferences(context: Context) = context.getSharedPreferences("sync-push", Context.MODE_PRIVATE)
    fun remember(context: Context, token: String) {
        // FCM callback may be the last callback before the process exits.
        check(preferences(context).edit().putString("token", token).commit())
    }
    fun refresh(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            remember(context, token)
            SyncScheduler.wakeNow(context)
        }
    }
    suspend fun register(context: Context, api: PhotoSyncApi) {
        val token = preferences(context).getString("token", null) ?: return
        api.registerPushToken(PushTokenRequest(token))
    }
}
