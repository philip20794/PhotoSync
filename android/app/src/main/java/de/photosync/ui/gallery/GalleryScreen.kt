package de.photosync.ui.gallery

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import de.photosync.data.local.AlbumSyncProgress
import de.photosync.data.media.MediaAccess
import de.photosync.data.media.currentMediaAccess
import de.photosync.data.media.requestedMediaPermissions
import de.photosync.domain.model.LocalAlbum
import de.photosync.domain.model.LocalMedia
import de.photosync.domain.model.LocalMediaKind

@Composable
fun LocalGallery(applicationContext: Context, deviceId: String) {
    val gallery: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(applicationContext, deviceId))
    val state by gallery.state.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(currentMediaAccess(applicationContext)) }
    var selectedAlbum by remember { mutableStateOf<LocalAlbum?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = currentMediaAccess(applicationContext)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = currentMediaAccess(applicationContext)
    }
    LaunchedEffect(access) { gallery.refresh(access != MediaAccess.NONE) }

    val album = selectedAlbum
    if (album != null) {
        BackHandler { selectedAlbum = null }
        AlbumMediaGrid(
            album = album,
            gallery = gallery,
            progress = state.syncProgress[album.id],
            onSharing = { gallery.setSharing(album, it) },
            onBack = { selectedAlbum = null },
        )
        return
    }

    when (access) {
        MediaAccess.NONE -> PermissionRequest { permissionLauncher.launch(requestedMediaPermissions()) }
        MediaAccess.PARTIAL, MediaAccess.FULL -> AlbumGrid(
            state = state,
            partialAccess = access == MediaAccess.PARTIAL,
            onManagePermission = { permissionLauncher.launch(requestedMediaPermissions()) },
            onRefresh = { gallery.refresh(true) },
            onAlbum = { selectedAlbum = it },
            onSharing = gallery::setSharing,
        )
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fotos und Videos anzeigen", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("PhotoSync benötigt Lesezugriff. Es verändert keine Dateien und erzeugt keine Ordner.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Zugriff auswählen") }
    }
}

@Composable
private fun AlbumGrid(
    state: GalleryUiState,
    partialAccess: Boolean,
    onManagePermission: () -> Unit,
    onRefresh: () -> Unit,
    onAlbum: (LocalAlbum) -> Unit,
    onSharing: (LocalAlbum, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (partialAccess) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Nur ausgewählte Medien sichtbar", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onManagePermission) { Text("Ändern") }
                }
            }
        }
        when {
            state.loading && state.albums.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Medien konnten nicht gelesen werden.")
                Button(onClick = onRefresh) { Text("Erneut versuchen") }
            }
            state.albums.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Keine zugänglichen lokalen Alben gefunden.")
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.albums, key = LocalAlbum::id) { album ->
                    AlbumCard(
                        album = album,
                        progress = state.syncProgress[album.id],
                        onAlbum = onAlbum,
                        onSharing = { onSharing(album, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(
    album: LocalAlbum,
    progress: AlbumSyncProgress?,
    onAlbum: (LocalAlbum) -> Unit,
    onSharing: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable { onAlbum(album) }) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(album.coverUri).build(),
            contentDescription = "Cover von ${album.name}",
            modifier = Modifier.fillMaxWidth().aspectRatio(1.2f).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text("${album.imageCount} Bilder · ${album.videoCount} Videos", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Teilen", modifier = Modifier.weight(1f))
                Switch(
                    checked = progress?.shareRequested == true,
                    onCheckedChange = onSharing,
                )
            }
            syncProgressText(progress)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AlbumMediaGrid(
    album: LocalAlbum,
    gallery: GalleryViewModel,
    progress: AlbumSyncProgress?,
    onSharing: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val flow = remember(album.id) { gallery.media(album) }
    val media = flow.collectAsLazyPagingItems()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Zurück") }
            Column(Modifier.weight(1f)) {
                Text(album.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text("${album.imageCount} Bilder · ${album.videoCount} Videos", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Teilen")
                    Switch(checked = progress?.shareRequested == true, onCheckedChange = onSharing)
                }
                syncProgressText(progress)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        when (val refresh = media.loadState.refresh) {
            LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is LoadState.Error -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Album konnte nicht geladen werden.")
                Button(onClick = media::retry) { Text("Erneut versuchen") }
            }
            is LoadState.NotLoading -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(count = media.itemCount, key = { index -> media.peek(index)?.uri ?: index }) { index ->
                    media[index]?.let { item -> MediaTile(item) }
                }
                if (media.loadState.append is LoadState.Loading) {
                    item { Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(media: LocalMedia) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(media.uri).build(),
            contentDescription = media.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
        )
        if (media.kind == LocalMediaKind.VIDEO) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    media.durationMillis?.let(::formatDuration) ?: "VIDEO",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun syncProgressText(progress: AlbumSyncProgress?): String? {
    progress ?: return null
    if (!progress.shareRequested) return if (progress.remoteShared) "Freigabe wird beendet …" else null
    if (!progress.remoteShared) return progress.lastError ?: "Freigabe wird vorbereitet …"
    progress.lastError?.let { return it }
    if (progress.lastScanAt == null) return "Medien werden gesucht …"
    if (progress.totalCount == 0L) return "Geteilt · keine Medien"
    val percent = if (progress.totalBytes > 0) (progress.uploadedBytes * 100 / progress.totalBytes).coerceIn(0, 100) else 0
    return when {
        progress.failedCount > 0 -> "${progress.completedCount}/${progress.totalCount} · ${progress.failedCount} fehlgeschlagen"
        progress.completedCount == progress.totalCount -> "${progress.completedCount} Medien synchronisiert"
        else -> "${progress.completedCount}/${progress.totalCount} · $percent %"
    }
}
