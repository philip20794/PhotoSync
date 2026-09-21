package de.photosync.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.photosync.data.local.AppDatabase

object SyncErrorNotifier {
    private const val CHANNEL = "sync_errors"

    suspend fun show(context: Context, database: AppDatabase, scope: String, message: String) {
        if (database.settingsDao().get(scope)?.notifySyncErrors != true) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL,
            "Synchronisierungsfehler",
            NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PhotoSync benötigt Aufmerksamkeit")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(scope.hashCode(), notification)
    }
}
