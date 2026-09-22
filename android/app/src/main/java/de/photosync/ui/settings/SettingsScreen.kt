package de.photosync.ui.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(context: Context, baseUrl: String, userId: String) {
    val model: SettingsViewModel = viewModel(
        key = "settings-$userId",
        factory = SettingsViewModel.factory(context, baseUrl, userId),
    )
    val state by model.state.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.setNotifyErrors(granted)
    }
    var displayName by rememberSaveable(userId) { mutableStateOf("") }
    var deviceName by rememberSaveable(userId) { mutableStateOf("") }
    var initialized by remember(userId) { mutableStateOf(false) }
    LaunchedEffect(state.displayName, state.deviceName) {
        if (!initialized || (!state.working && state.error == null)) {
            displayName = state.displayName
            deviceName = state.deviceName
            initialized = true
        }
    }
    SettingsContent(
        state = state,
        displayName = displayName,
        deviceName = deviceName,
        onDisplayNameChange = { displayName = it }, onDeviceNameChange = { deviceName = it },
        onSave = { model.saveProfile(displayName, deviceName) }, onWifiOnly = model::setWifiOnly,
        onAutoBackup = model::setAutoBackup, onRestoreBackup = model::restoreBackup, onClearCache = model::clearCache,
        onNotifyErrors = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else model.setNotifyErrors(enabled)
        },
    )
}

/** Stateful actions are supplied by the container so this exact settings UI is previewable. */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    displayName: String,
    deviceName: String,
    onDisplayNameChange: (String) -> Unit = {}, onDeviceNameChange: (String) -> Unit = {}, onSave: () -> Unit = {},
    onWifiOnly: (Boolean) -> Unit = {}, onAutoBackup: (Boolean) -> Unit = {},
    onRestoreBackup: (de.photosync.data.remote.PartnerAlbumDto) -> Unit = {},
    onClearCache: () -> Unit = {}, onNotifyErrors: (Boolean) -> Unit = {},
) {
    val preferences = state.preferences
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Passe PhotoSync an deinen Alltag an.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SettingsCard("Profil") {
            OutlinedTextField(displayName, onDisplayNameChange, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Dein Name") })
            OutlinedTextField(deviceName, onDeviceNameChange, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Dieses Gerät") })
            Text(
                state.partnerName?.let { "Verbunden mit $it" } ?: "Noch kein Partner verbunden",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSave,
                enabled = !state.working && displayName.isNotBlank() && deviceName.isNotBlank(),
            ) { Text("Änderungen speichern") }
        }

        SettingsCard("Synchronisierung") {
            SettingSwitch("Nur über WLAN", "Schont dein mobiles Datenvolumen", preferences?.wifiOnly == true, state.working, onWifiOnly)
            SettingSwitch("Automatisch sichern", "Neue Fotos privat auf deinem Server sichern", preferences?.autoBackupEnabled == true, state.working, onAutoBackup)
            state.backupProgress?.let { BackupProgress(it) }
        }

        SettingsCard("Backup wiederherstellen") {
            if (state.backups.isEmpty()) {
                Text(
                    "Noch keine eigenen serverseitigen Backups vorhanden.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.backups.forEach { album ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(album.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${album.assetCount} Medien · ${formatBytes(album.originalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { onRestoreBackup(album) },
                            enabled = !state.working,
                        ) { Text("Herunterladen") }
                    }
                }
            }
        }

        SettingsCard("Benachrichtigungen") {
            SettingSwitch("Bei Problemen informieren", "Meldet sich nur, wenn deine Aufmerksamkeit nötig ist", preferences?.notifySyncErrors != false, state.working, onNotifyErrors)
        }

        SettingsCard("Speicher") {
            Text("Temporäre Dateien können jederzeit sicher entfernt werden. Offline gespeicherte Alben bleiben erhalten.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClearCache, enabled = !state.working) { Text("Temporäre Dateien leeren") }
        }

        SettingsCard("Verbindung") {
            Text(
                when (preferences?.serverReachable) { true -> "PhotoSync ist verbunden"; false -> "PhotoSync ist gerade offline"; null -> "Verbindung wird geprüft" },
                style = MaterialTheme.typography.titleMedium,
                color = if (preferences?.serverReachable == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text("Zuletzt synchronisiert: ${formatTime(preferences?.lastSuccessfulSyncAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.working) CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(label: String, supporting: String, checked: Boolean, working: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = !working)
    }
}

private fun formatTime(value: Long?): String =
    value?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "noch nie"

private fun formatBytes(value: String): String {
    val bytes = value.toLongOrNull() ?: return "Größe unbekannt"
    val mebibytes = bytes / (1024.0 * 1024.0)
    return if (mebibytes < 1024) "%.1f MiB".format(mebibytes) else "%.1f GiB".format(mebibytes / 1024.0)
}

@Composable
private fun BackupProgress(progress: de.photosync.data.BackupProgressUi) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (progress.indeterminate) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { (progress.percent ?: 0).toFloat() / 100f }, modifier = Modifier.fillMaxWidth())
        Text(progress.status, style = MaterialTheme.typography.bodySmall,
            color = if (progress.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
