package de.photosync.ui.partner

import android.content.Context
import android.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import de.photosync.data.remote.AssetDto
import de.photosync.data.remote.PartnerAlbumDto
import de.photosync.data.offline.OfflineMode
import de.photosync.data.offline.OfflineStatus
import de.photosync.data.offline.OfflineAssetEntity
import java.io.File

@Composable
fun PartnerGallery(applicationContext: Context, baseUrl: String, userId: String) {
    val gallery: PartnerGalleryViewModel = viewModel(factory = PartnerGalleryViewModel.factory(applicationContext, baseUrl, userId))
    val state by gallery.state.collectAsStateWithLifecycle()
    var album by remember { mutableStateOf<PartnerAlbumDto?>(null) }
    var asset by remember { mutableStateOf<AssetDto?>(null) }
    asset?.let { selected ->
        BackHandler { asset = null }
        AssetViewer(selected, gallery) { asset = null }
        return
    }
    album?.let { selected ->
        BackHandler { album = null }
        AlbumGrid(selected, gallery, { album = null }) { asset = it }
        return
    }
    AlbumOverview(state, gallery) { album = it }
}

@Composable
private fun AlbumOverview(state: PartnerGalleryState, gallery: PartnerGalleryViewModel, onAlbum: (PartnerAlbumDto) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Partneralben", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = gallery::refresh, enabled = !state.loading) { Text("Aktualisieren") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatBytes(state.cacheBytes) + " Cache", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = gallery::cycleCacheLimit) { Text("Limit " + formatBytes(state.cacheMaxBytes)) }
            TextButton(onClick = gallery::clearCache) { Text("Leeren") }
        }
        state.error?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, modifier = Modifier.weight(1f))
                    TextButton(onClick = gallery::refresh) { Text("Erneut") }
                }
            }
        }
        when {
            state.loading && state.albums.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.albums.isEmpty() && state.error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Der Partner hat noch keine freigegebenen Alben.") }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.albums, key = PartnerAlbumDto::id) { item -> AlbumCard(item, gallery) { onAlbum(item) } }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: PartnerAlbumDto, gallery: PartnerGalleryViewModel, click: () -> Unit) {
    val state by gallery.state.collectAsStateWithLifecycle()
    val offline = state.offlineAlbums[album.id]
    Card(Modifier.fillMaxWidth().clickable(onClick = click)) {
        album.cover?.let { cover -> Cover(cover.assetId, cover.version, cover.sha256, gallery) }
            ?: Box(Modifier.fillMaxWidth().aspectRatio(1.2f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("Keine Vorschau") }
        Column(Modifier.padding(12.dp)) {
            Text(album.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(album.assetCount.toString() + " Fotos/Videos", style = MaterialTheme.typography.bodySmall)
            Text(album.owner.displayName, style = MaterialTheme.typography.bodySmall)
            Text("Optimiert ca. " + formatBytes(album.optimizedBytes.toLongOrNull() ?: 0), style = MaterialTheme.typography.bodySmall)
            Text("Original ca. " + formatBytes(album.originalBytes.toLongOrNull() ?: 0), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { gallery.setAlbumOffline(album, OfflineMode.NONE) }) { Text("Nicht offline") }
                TextButton(onClick = { gallery.setAlbumOffline(album, OfflineMode.OPTIMIZED) }) { Text("Optimiert") }
                TextButton(onClick = { gallery.setAlbumOffline(album, OfflineMode.ORIGINAL) }) { Text("Original") }
            }
            offline?.let { Text("Offline: " + it.desiredMode + " · " + it.status, style = MaterialTheme.typography.bodySmall) }
            if (offline?.status == OfflineStatus.FAILED || offline?.status == OfflineStatus.RETRY) {
                TextButton(onClick = { gallery.retryOffline(album.id) }) { Text("Download erneut versuchen") }
            }
        }
    }
}

@Composable
private fun Cover(id: String, version: String, sha256: String?, gallery: PartnerGalleryViewModel) {
    var file by remember(id, version, sha256) { mutableStateOf<File?>(null) }
    var complete by remember(id, version, sha256) { mutableStateOf(false) }
    LaunchedEffect(id, version, sha256) { file = runCatching { gallery.thumbnail(id, version, sha256) }.getOrNull(); complete = true }
    Box(Modifier.fillMaxWidth().aspectRatio(1.2f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (file != null) AsyncImage(file, "Cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
        else if (complete) Text("Vorschau nicht verfügbar") else CircularProgressIndicator(Modifier.width(24.dp))
    }
}

@Composable
private fun AlbumGrid(album: PartnerAlbumDto, gallery: PartnerGalleryViewModel, back: () -> Unit, select: (AssetDto) -> Unit) {
    val assets = remember(album.id) { gallery.assets(album) }.collectAsLazyPagingItems()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = back) { Text("Zurück") }
            Column(Modifier.padding(start = 12.dp)) {
                Text(album.title, style = MaterialTheme.typography.titleLarge)
                Text(album.assetCount.toString() + " Fotos/Videos", style = MaterialTheme.typography.bodySmall)
            }
        }
        when (assets.loadState.refresh) {
            LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is LoadState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(onClick = assets::retry) { Text("Erneut versuchen") } }
            is LoadState.NotLoading -> LazyVerticalGrid(
                columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(count = assets.itemCount, key = { index -> assets.peek(index)?.id ?: index }) { index ->
                    assets[index]?.let { entry -> Tile(entry, gallery) { select(entry) } }
                }
                if (assets.loadState.append is LoadState.Loading) item { Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                if (assets.loadState.append is LoadState.Error) item { TextButton(onClick = assets::retry) { Text("Mehr laden") } }
            }
        }
    }
}

@Composable
private fun Tile(asset: AssetDto, gallery: PartnerGalleryViewModel, click: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = click), contentAlignment = Alignment.Center) {
        AssetThumbnail(asset, gallery)
        if (asset.mimeType.startsWith("video/")) Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp), color = MaterialTheme.colorScheme.scrim.copy(alpha = .65f)) {
            Text(asset.durationMillis?.let(::duration) ?: "VIDEO", modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

@Composable
private fun AssetThumbnail(asset: AssetDto, gallery: PartnerGalleryViewModel, modifier: Modifier = Modifier) {
    val offline by remember(asset.id) { gallery.offlineAsset(asset.id) }.collectAsStateWithLifecycle(initialValue = null)
    var file by remember(asset.id, asset.derivatives) { mutableStateOf<File?>(null) }
    LaunchedEffect(asset.id, asset.derivatives) { file = runCatching { gallery.thumbnail(asset) }.getOrNull() }
    val durable = offline?.takeIf { it.status == OfflineStatus.READY }?.localPath?.let(::File)?.takeIf(File::isFile)
    (durable ?: file)?.let { image -> AsyncImage(image, asset.originalFileName, modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low) }
}

@Composable
private fun AssetViewer(asset: AssetDto, gallery: PartnerGalleryViewModel, back: () -> Unit) {
    val offline by remember(asset.id) { gallery.offlineAsset(asset.id) }.collectAsStateWithLifecycle(initialValue = null)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = back) { Text("Zurück") }
            Text(asset.originalFileName, modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { gallery.setAssetOffline(asset, OfflineMode.OPTIMIZED) }) { Text("Optimiert offline") }
            TextButton(onClick = { gallery.setAssetOffline(asset, OfflineMode.ORIGINAL) }) { Text("Original offline") }
        }
        offline?.let { Text("Lokal: " + (it.actualVariant ?: "nicht vorhanden") + " · " + it.status, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        if (asset.mimeType.startsWith("video/")) Video(asset, gallery, offline) else Photo(asset, gallery, offline)
    }
}

@Composable
private fun Photo(asset: AssetDto, gallery: PartnerGalleryViewModel, offline: OfflineAssetEntity?) {
    var thumb by remember(asset.id, asset.derivatives) { mutableStateOf<File?>(null) }
    var optimized by remember(asset.id, asset.derivatives) { mutableStateOf<File?>(null) }
    LaunchedEffect(asset.id, asset.derivatives) {
        thumb = runCatching { gallery.thumbnail(asset) }.getOrNull()
        optimized = runCatching { gallery.optimizedImage(asset) }.getOrNull()
    }
    var scale by remember(asset.id) { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 6f) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val durable = offline?.takeIf { it.status == OfflineStatus.READY }?.localPath?.let(::File)?.takeIf(File::isFile)
        AsyncImage(durable ?: optimized ?: thumb, asset.originalFileName, Modifier.fillMaxSize().transformable(transform).graphicsLayer(scaleX = scale, scaleY = scale), contentScale = ContentScale.Fit)
        if (thumb == null) CircularProgressIndicator()
    }
}

@Composable
private fun Video(asset: AssetDto, gallery: PartnerGalleryViewModel, offline: OfflineAssetEntity?) {
    val localPath = offline?.takeIf { it.status == OfflineStatus.READY }?.localPath?.takeIf { File(it).isFile }
    val player = remember(asset.id, asset.derivatives, localPath) { if (localPath != null) gallery.localVideoPlayer(localPath) else gallery.videoPlayer(asset) }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AssetThumbnail(asset, gallery)
        AndroidView(factory = { context -> PlayerView(context).apply { this.player = player; useController = true; setShutterBackgroundColor(Color.TRANSPARENT) } }, modifier = Modifier.fillMaxSize())
    }
}

private fun duration(value: String): String {
    val seconds = value.toLongOrNull()?.div(1000) ?: return "VIDEO"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024L * 1024L) (bytes / (1024L * 1024L * 1024L)).toString() + " GB" else (bytes / (1024L * 1024L)).toString() + " MB"
