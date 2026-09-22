package de.photosync.ui.trash

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.photosync.data.local.AppDatabase
import de.photosync.data.local.SecureCredentialStore
import de.photosync.data.remote.AssetDto
import de.photosync.data.remote.DerivativeDto
import de.photosync.data.remote.PhotoSyncApi
import de.photosync.data.remote.TrashAssetDto
import de.photosync.data.remote.AssetIdsRequest
import de.photosync.data.remote.RetrofitFactory
import de.photosync.ui.partner.PartnerCacheKey
import de.photosync.ui.partner.PartnerMediaCache
import de.photosync.ui.partner.PartnerVariant
import de.photosync.ui.partner.partnerCacheKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.Instant

data class TrashState(
    val assets: List<TrashAssetDto> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val working: Boolean = false,
    val error: String? = null,
)

class TrashViewModel(
    private val api: PhotoSyncApi,
    private val cache: PartnerMediaCache,
    private val scope: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TrashState())
    val state: StateFlow<TrashState> = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            runCatching { api.trash() }
                .onSuccess { response -> mutableState.update { it.copy(assets = response.assets, selected = emptySet(), loading = false, working = false) } }
                .onFailure { error -> mutableState.update { it.copy(loading = false, working = false, error = error.message ?: "Papierkorb konnte nicht geladen werden") } }
        }
    }

    fun toggle(id: String) = mutableState.update { current ->
        current.copy(selected = if (id in current.selected) current.selected - id else current.selected + id)
    }

    fun restoreSelected() {
        val selected = selectedAssets()
        perform(selected) {
        val ids = selected.map { it.id }
        if (ids.size == 1) api.restoreTrashAsset(ids.single()) else api.restoreTrashAssets(AssetIdsRequest(ids))
        }
    }

    fun purgeSelected() {
        val selected = selectedAssets()
        perform(selected) {
        api.purgeTrashAssets(AssetIdsRequest(selected.map { it.id }))
        }
    }

    fun empty() = perform(mutableState.value.assets) { api.emptyTrash() }

    suspend fun thumbnail(asset: TrashAssetDto): File? {
        if (asset.availableVariants.none { it == "thumbnail" } && asset.derivatives.none { it.kind == "thumbnail" && it.status == "ready" }) return null
        val dto = asset.asAsset()
        return cache.imageFile(dto.partnerCacheKey(scope, PartnerVariant.THUMBNAIL)) {
            api.downloadTrashThumbnail(asset.id)
        }
    }

    private fun selectedAssets() = mutableState.value.assets.filter { it.id in mutableState.value.selected }

    private fun perform(affected: List<TrashAssetDto>, action: suspend () -> Any) {
        viewModelScope.launch {
            mutableState.update { it.copy(working = true, error = null) }
            runCatching {
                action()
                affected.forEach { cache.invalidateAsset(scope, it.asAsset()) }
            }
                .onSuccess { refresh() }
                .onFailure { error -> mutableState.update { it.copy(working = false, error = error.message ?: "Papierkorb-Aktion fehlgeschlagen") } }
        }
    }

    companion object {
        fun factory(context: Context, baseUrl: String, userId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val credentials = SecureCredentialStore(context)
                return TrashViewModel(
                    RetrofitFactory.create(baseUrl, credentials),
                    PartnerMediaCache.get(context, credentials),
                    de.photosync.data.sync.remoteScope(baseUrl, userId),
                ) as T
            }
        }
    }
}

private fun TrashAssetDto.asAsset() = AssetDto(
    id = id, ownerId = ownerId, albumId = albumId, originalFileName = originalFileName,
    mimeType = mimeType, capturedAt = capturedAt, fileSize = fileSize, width = width, height = height,
    durationMillis = durationMillis, sha256 = sha256, status = status, derivatives = derivatives,
    createdAt = createdAt, updatedAt = updatedAt,
)

@Composable
fun TrashScreen(applicationContext: Context, baseUrl: String, userId: String) {
    val viewModel: TrashViewModel = viewModel(factory = TrashViewModel.factory(applicationContext, baseUrl, userId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrashContent(
        state = state,
        onRefresh = viewModel::refresh,
        onRestore = viewModel::restoreSelected,
        onPurge = viewModel::purgeSelected,
        onEmpty = viewModel::empty,
        onToggle = viewModel::toggle,
        thumbnail = viewModel::thumbnail,
    )
}

/** The app's trash layout, with side effects supplied by the ViewModel container. */
@Composable
internal fun TrashContent(
    state: TrashState,
    onRefresh: () -> Unit = {}, onRestore: () -> Unit = {}, onPurge: () -> Unit = {}, onEmpty: () -> Unit = {},
    onToggle: (String) -> Unit = {},
    thumbnail: suspend (TrashAssetDto) -> File? = { null },
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Papierkorb", style = MaterialTheme.typography.headlineMedium)
                Text("Gelöschte Fotos bleiben 90 Tage erhalten", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRefresh, enabled = !state.loading && !state.working) { Text("Aktualisieren") }
        }
        if (state.selected.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.selected.size} ausgewählt", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onRestore, enabled = !state.working) { Text("Wiederherstellen") }
                    TextButton(onClick = onPurge, enabled = !state.working) { Text("Löschen") }
                }
            }
        } else if (state.assets.isNotEmpty()) {
            TextButton(onClick = onEmpty, enabled = !state.working, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Papierkorb leeren") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        when {
            state.loading && state.assets.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.assets.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Alles aufgeräumt", style = MaterialTheme.typography.headlineSmall)
                    Text("Der Papierkorb ist leer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.assets, key = TrashAssetDto::id) { asset -> TrashRow(asset, asset.id in state.selected, !state.working, onToggle, thumbnail) }
            }
        }
    }
}

@Composable
private fun TrashRow(
    asset: TrashAssetDto,
    selected: Boolean,
    enabled: Boolean,
    toggle: (String) -> Unit,
    thumbnail: suspend (TrashAssetDto) -> File?,
) {
    var file by remember(asset.id, asset.updatedAt) { mutableStateOf<File?>(null) }
    LaunchedEffect(asset.id, asset.updatedAt) { file = runCatching { thumbnail(asset) }.getOrNull() }
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { toggle(asset.id) },
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { toggle(asset.id) }, enabled = enabled)
            if (file != null) AsyncImage(file, asset.originalFileName, Modifier.size(72.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
            else Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) { Text("Foto", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(asset.originalFileName, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                Text(asset.originalAlbum.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Noch ${((asset.remainingRetentionSeconds + 86_399) / 86_400).coerceAtLeast(0)} Tage verfügbar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
