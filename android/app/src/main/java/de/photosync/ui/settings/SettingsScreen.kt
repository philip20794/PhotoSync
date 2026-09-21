package de.photosync.ui.settings

import android.Manifest
import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
    val preferences = state.preferences
    Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Konto", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text("Anzeigename") })
        OutlinedTextField(deviceName, { deviceName = it }, Modifier.fillMaxWidth(), label = { Text("Gerätename") })
        Text(if (state.partnerName == null) "Partner: noch nicht verbunden" else "Partner: ${state.partnerName}")
        Button(
            onClick = { model.saveProfile(displayName, deviceName) },
            enabled = !state.working && displayName.isNotBlank() && deviceName.isNotBlank(),
        ) { Text("Namen speichern") }

        Section("Synchronisation")
        SettingSwitch(
            "Medien nur über WLAN synchronisieren",
            preferences?.wifiOnly == true,
            state.working,
            model::setWifiOnly,
        )
        SettingSwitch("Auto-Backup", preferences?.autoBackupEnabled == true, state.working, model::setAutoBackup)
        Text(
            "Auto-Backup sichert neue private Originale. Ausschalten stoppt nur neue private Sicherungen; vorhandene Backups bleiben erhalten.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Speicher")
        Text("Cache: ${Formatter.formatFileSize(context, state.cacheBytes)}")
        Text("Offline-Dateien: ${Formatter.formatFileSize(context, state.offlineBytes)}")
        CacheLimit(state.cacheMaxBytes, state.working, model::setCacheMax)
        OutlinedButton(onClick = model::clearCache, enabled = !state.working) { Text("Cache leeren") }
        Text("Offline-Dateien und eigene Originalmedien werden dabei nicht gelöscht.", style = MaterialTheme.typography.bodySmall)

        Section("Status")
        Text("Letzter erfolgreicher Sync: ${formatTime(preferences?.lastSuccessfulSyncAt)}")
        Text("Server: ${when (preferences?.serverReachable) { true -> "erreichbar"; false -> "nicht erreichbar"; null -> "noch nicht geprüft" }}")
        Text("Auto-Backup: ${if (preferences?.autoBackupEnabled == true) "aktiv" else "aus"}")

        Section("Benachrichtigungen")
        SettingSwitch("Bei Sync-Fehlern benachrichtigen", preferences?.notifySyncErrors != false, state.working) { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else model.setNotifyErrors(enabled)
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.working) CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, working: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = !working)
    }
}

@Composable
private fun CacheLimit(current: Long, working: Boolean, onSelect: (Long) -> Unit) {
    val values = listOf(500L, 1024L, 2048L, 5120L, 10240L).map { it * 1024L * 1024L }
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = !working) {
            Text("Cache-Maximum: ${if (current > 0) formatLimit(current) else "…"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { bytes ->
                DropdownMenuItem(
                    text = { Text(formatLimit(bytes)) },
                    onClick = { expanded = false; onSelect(bytes) },
                )
            }
        }
    }
}

private fun formatLimit(bytes: Long): String =
    if (bytes >= 1024L * 1024L * 1024L) "${bytes / (1024L * 1024L * 1024L)} GB" else "${bytes / (1024L * 1024L)} MB"

private fun formatTime(value: Long?): String =
    value?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "noch nie"
