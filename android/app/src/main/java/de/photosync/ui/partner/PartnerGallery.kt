package de.photosync.ui.partner

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import android.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import de.photosync.ui.gallery.pinchToResizeGrid
import java.io.File
import kotlinx.coroutines.flow.collect

@Composable
fun PartnerGallery(applicationContext: Context, baseUrl: String, userId: String) {
    val gallery: PartnerGalleryViewModel = viewModel(factory = PartnerGalleryViewModel.factory(applicationContext, baseUrl, userId))
    val state by gallery.state.collectAsStateWithLifecycle()
    var album by remember { mutableStateOf<PartnerAlbumDto?>(null) }
    var asset by remember { mutableStateOf<AssetDto?>(null) }
    var actionAsset by remember { mutableStateOf<AssetDto?>(null) }
    LaunchedEffect(gallery) { gallery.share.collect { event -> if (event is PartnerShareEvent.Ready) {
        val uri = FileProvider.getUriForFile(applicationContext, applicationContext.packageName + ".files", event.file)
        applicationContext.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = event.mimeType; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Bild teilen").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } } }
    asset?.let { selected ->
        Dialog(
            onDismissRequest = { asset = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = ComposeColor.Black) { AssetViewer(selected, gallery) { asset = null } }
        }
        return
    }
    album?.let { selected ->
        Dialog(
            onDismissRequest = { album = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) { AlbumGrid(selected, gallery, { album = null }, { asset = it }, { actionAsset = it }); actionAsset?.let { PartnerImageActions(it, gallery) { actionAsset = null } } }
            }
        }
        return
    }
    AlbumOverview(state, gallery) { album = it }
}

@Composable
private fun AlbumOverview(state: PartnerGalleryState, gallery: PartnerGalleryViewModel, onAlbum: (PartnerAlbumDto) -> Unit) {
    PartnerAlbumOverviewContent(
        state = state,
        showTopBar = false,
        onRefresh = gallery::refresh,
        onAlbum = onAlbum,
        onSetOffline = gallery::setAlbumOffline,
        onRetryOffline = gallery::retryOffline,
        cover = { album -> album.cover?.let { Cover(it.assetId, it.version, it.sha256, gallery) } },
    )
}

/** Stateless partner-album overview, shared by the live screen and debug previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PartnerAlbumOverviewContent(
    state: PartnerGalleryState,
    initialColumns: Int = 2,
    showTopBar: Boolean = true,
    onRefresh: () -> Unit = {},
    onAlbum: (PartnerAlbumDto) -> Unit = {}, onSetOffline: (PartnerAlbumDto, String) -> Unit = { _, _ -> },
    onRetryOffline: (String) -> Unit = {},
    cover: @Composable (PartnerAlbumDto) -> Unit = {},
) {
    var columns by rememberSaveable { mutableIntStateOf(initialColumns.coerceIn(1, 4)) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = if (showTopBar) {
            Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)
        } else {
            Modifier.fillMaxSize()
        },
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Partner", style = MaterialTheme.typography.titleLarge)
                            Text("Geteilte Alben", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = { TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Aktualisieren") } },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        when {
            state.loading && state.albums.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.albums.isEmpty() && state.error == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Der Partner hat noch keine freigegebenen Alben.") }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().pinchToResizeGrid(columns, 1, 4) { columns = it },
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (columns >= 3) 4.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (columns >= 3) 8.dp else 16.dp),
            ) {
                state.error?.let { message -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) } }
                items(state.albums, key = PartnerAlbumDto::id) { item ->
                    PartnerAlbumCard(item, state.offlineAlbums[item.id], { onAlbum(item) }, { onSetOffline(item, it) }, onRetryOffline, compact = columns >= 3) { cover(item) }
                }
            }
        }
    }
}

@Composable
private fun PartnerAlbumCard(
    album: PartnerAlbumDto,
    offline: de.photosync.data.offline.OfflineAlbumEntity?,
    click: () -> Unit,
    setOffline: (String) -> Unit,
    retryOffline: (String) -> Unit,
    compact: Boolean,
    cover: @Composable () -> Unit,
) {
    var offlineMenu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = click),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        if (album.cover != null) cover() else Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("Noch kein Titelbild", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.padding(if (compact) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
            Text(album.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text("${album.assetCount} Fotos und Videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (!compact) {
                Box {
                    TextButton(onClick = { offlineMenu = true }) { Text(offlineLabel(offline?.desiredMode)) }
                    DropdownMenu(expanded = offlineMenu, onDismissRequest = { offlineMenu = false }) {
                        DropdownMenuItem(text = { Text("Nur online") }, onClick = { offlineMenu = false; setOffline(OfflineMode.NONE) })
                        DropdownMenuItem(text = { Text("Offline speichern") }, onClick = { offlineMenu = false; setOffline(OfflineMode.OPTIMIZED) })
                        DropdownMenuItem(text = { Text("Originale offline speichern") }, onClick = { offlineMenu = false; setOffline(OfflineMode.ORIGINAL) })
                    }
                }
                offlineStatusText(offline?.takeIf { it.desiredMode != OfflineMode.NONE }?.status)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (offline?.status == OfflineStatus.FAILED || offline?.status == OfflineStatus.RETRY) {
                    TextButton(onClick = { retryOffline(album.id) }) { Text("Erneut versuchen") }
                }
            } else if (offline?.desiredMode != null && offline.desiredMode != OfflineMode.NONE) {
                Text("Offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Cover(id: String, version: String, sha256: String?, gallery: PartnerGalleryViewModel) {
    var file by remember(id, version, sha256) { mutableStateOf<File?>(null) }
    var complete by remember(id, version, sha256) { mutableStateOf(false) }
    LaunchedEffect(id, version, sha256) { file = runCatching { gallery.thumbnail(id, version, sha256) }.getOrNull(); complete = true }
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (file != null) AsyncImage(file, "Cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
        else if (complete) Text("Vorschau nicht verfügbar") else CircularProgressIndicator(Modifier.width(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumGrid(album: PartnerAlbumDto, gallery: PartnerGalleryViewModel, back: () -> Unit, select: (AssetDto) -> Unit, actions: (AssetDto) -> Unit) {
    val assets = remember(album.id) { gallery.assets(album) }.collectAsLazyPagingItems()
    var columns by rememberSaveable(album.id) { mutableIntStateOf(3) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { PartnerAlbumTopBar(album, back, scrollBehavior) },
    ) { padding ->
        when (assets.loadState.refresh) {
            LoadState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is LoadState.Error -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Button(onClick = assets::retry) { Text("Erneut versuchen") } }
            is LoadState.NotLoading -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().pinchToResizeGrid(columns, 2, 7) { columns = it },
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = padding.calculateTopPadding() + 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(count = assets.itemCount, key = { index -> assets.peek(index)?.id ?: index }) { index ->
                    assets[index]?.let { entry -> PartnerAssetTile(entry, { select(entry) }, { actions(entry) }) { AssetThumbnail(entry, gallery) } }
                }
                if (assets.loadState.append is LoadState.Loading) item { Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                if (assets.loadState.append is LoadState.Error) item { TextButton(onClick = assets::retry) { Text("Mehr laden") } }
            }
        }
    }
}

/** Static partner grid for previewing a mixed photo/video album without Paging or network access. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PartnerAlbumGridPreviewContent(album: PartnerAlbumDto, assets: List<AssetDto>, initialColumns: Int = 3) {
    var columns by rememberSaveable { mutableIntStateOf(initialColumns.coerceIn(2, 7)) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { PartnerAlbumTopBar(album, {}, scrollBehavior) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().pinchToResizeGrid(columns, 2, 7) { columns = it },
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = padding.calculateTopPadding() + 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(assets, key = AssetDto::id) { asset -> PartnerAssetTile(asset, {}) { PreviewTile(asset) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartnerAlbumTopBar(album: PartnerAlbumDto, onBack: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) } },
        title = {
            Column {
                Text(album.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("${album.assetCount} Fotos und Videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun PreviewTile(asset: AssetDto) {
    Text(if (asset.mimeType.startsWith("video/")) asset.durationMillis?.let(::duration) ?: "VIDEO" else "Foto", style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun PartnerAssetViewerContent(
    asset: AssetDto, offlineStatus: String?, body: @Composable () -> Unit,
    onBack: () -> Unit = {}, onOptimizedOffline: () -> Unit = {}, onOriginalOffline: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(ComposeColor.Black), contentAlignment = Alignment.Center) {
        body()
        Surface(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = ComposeColor.Black.copy(alpha = 0.62f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹  Zurück", color = ComposeColor.White) }
                Text(asset.originalFileName, modifier = Modifier.weight(1f), maxLines = 1, color = ComposeColor.White, style = MaterialTheme.typography.titleMedium)
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Text("⋮", color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Offline speichern") }, onClick = { menuExpanded = false; onOptimizedOffline() })
                        DropdownMenuItem(text = { Text("Original offline speichern") }, onClick = { menuExpanded = false; onOriginalOffline() })
                    }
                }
            }
        }
        offlineStatus?.let {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), color = ComposeColor.Black.copy(alpha = 0.62f), shape = MaterialTheme.shapes.small) {
                Text(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = ComposeColor.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PartnerAssetTile(asset: AssetDto, click: () -> Unit, longClick: () -> Unit = {}, thumbnail: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceVariant).combinedClickable(onClick = click, onLongClick = { if (asset.mimeType.startsWith("image/")) longClick() }), contentAlignment = Alignment.Center) {
        thumbnail()
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
    val rotation by remember(asset.id) { gallery.rotation(asset.id) }.collectAsStateWithLifecycle(initialValue = 0)
    (durable ?: file)?.let { image -> AsyncImage(image, asset.originalFileName, modifier.fillMaxSize().graphicsLayer(rotationZ = (rotation ?: 0).toFloat()), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low) }
}

@Composable
private fun AssetViewer(asset: AssetDto, gallery: PartnerGalleryViewModel, back: () -> Unit) {
    val offline by remember(asset.id) { gallery.offlineAsset(asset.id) }.collectAsStateWithLifecycle(initialValue = null)
    PartnerAssetViewerContent(
        asset, offlineStatusText(offline?.status),
        body = { if (asset.mimeType.startsWith("video/")) Video(asset, gallery, offline) else Photo(asset, gallery, offline) },
        onBack = back,
        onOptimizedOffline = { gallery.setAssetOffline(asset, OfflineMode.OPTIMIZED) },
        onOriginalOffline = { gallery.setAssetOffline(asset, OfflineMode.ORIGINAL) },
    )
}

@Composable
private fun Photo(asset: AssetDto, gallery: PartnerGalleryViewModel, offline: OfflineAssetEntity?) {
    val rotation by remember(asset.id) { gallery.rotation(asset.id) }.collectAsStateWithLifecycle(initialValue = 0)
    var thumb by remember(asset.id, asset.derivatives) { mutableStateOf<File?>(null) }
    var optimized by remember(asset.id, asset.derivatives) { mutableStateOf<File?>(null) }
    LaunchedEffect(asset.id, asset.derivatives) {
        thumb = runCatching { gallery.thumbnail(asset) }.getOrNull()
        optimized = runCatching { gallery.optimizedImage(asset) }.getOrNull()
    }
    var scale by remember(asset.id) { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 6f) }
    Box(Modifier.fillMaxSize().background(ComposeColor.Black), contentAlignment = Alignment.Center) {
        val durable = offline?.takeIf { it.status == OfflineStatus.READY }?.localPath?.let(::File)?.takeIf(File::isFile)
        AsyncImage(durable ?: optimized ?: thumb, asset.originalFileName, Modifier.fillMaxSize().transformable(transform).graphicsLayer(scaleX = scale, scaleY = scale, rotationZ = (rotation ?: 0).toFloat()), contentScale = ContentScale.Fit)
        if (thumb == null) CircularProgressIndicator()
    }
}

@Composable
private fun Video(asset: AssetDto, gallery: PartnerGalleryViewModel, offline: OfflineAssetEntity?) {
    val localPath = offline?.takeIf { it.status == OfflineStatus.READY }?.localPath?.takeIf { File(it).isFile }
    val player = remember(asset.id, asset.derivatives, localPath) { if (localPath != null) gallery.localVideoPlayer(localPath) else gallery.videoPlayer(asset) }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(ComposeColor.Black)) {
        AssetThumbnail(asset, gallery)
        AndroidView(factory = { context -> PlayerView(context).apply { this.player = player; useController = true; setShutterBackgroundColor(Color.TRANSPARENT) } }, modifier = Modifier.fillMaxSize())
    }
}

private fun duration(value: String): String {
    val seconds = value.toLongOrNull()?.div(1000) ?: return "VIDEO"
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun offlineLabel(mode: String?): String = when (mode) {
    OfflineMode.OPTIMIZED -> "Offline gespeichert"
    OfflineMode.ORIGINAL -> "Originale offline"
    else -> "Offline verfügbar machen"
}

private fun offlineStatusText(status: String?): String? = when (status) {
    OfflineStatus.DOWNLOADING -> "Wird offline gespeichert …"
    OfflineStatus.RETRY -> "Download pausiert"
    OfflineStatus.FAILED -> "Download nicht abgeschlossen"
    OfflineStatus.READY -> "Offline verfügbar"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartnerImageActions(asset: AssetDto, gallery: PartnerGalleryViewModel, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(asset.originalFileName, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            TextButton(onClick = { gallery.setAssetOffline(asset, OfflineMode.ORIGINAL); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Original herunterladen", modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
            TextButton(onClick = { gallery.setAssetOffline(asset, OfflineMode.OPTIMIZED); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Offline speichern", modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
            TextButton(onClick = { gallery.rotate(asset); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Drehen", modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
            TextButton(onClick = { gallery.share(asset); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Teilen", modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
        }
    }
}
