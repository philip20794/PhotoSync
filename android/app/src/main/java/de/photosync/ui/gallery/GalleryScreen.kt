package de.photosync.ui.gallery

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        Dialog(
            onDismissRequest = { selectedAlbum = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AlbumMediaGrid(
                    album = album,
                    gallery = gallery,
                    progress = state.syncProgress[album.id],
                    columns = state.mediaGridColumns[album.id] ?: 3,
                    onSharing = { gallery.setSharing(album, it) },
                    onColumnsChanged = { gallery.setMediaGridColumns(album.id, it) },
                    onBack = { selectedAlbum = null },
                )
            }
        }
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
            columns = state.albumGridColumns,
            onColumnsChanged = gallery::setAlbumGridColumns,
        )
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Deine Fotos, an einem Ort", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Erlaube den Zugriff auf die Alben, die du in PhotoSync sehen und teilen möchtest.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRequest) { Text("Fotos auswählen") }
            }
        }
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
    columns: Int,
    onColumnsChanged: (Int) -> Unit,
) {
    LocalAlbumOverview(
        state = state,
        partialAccess = partialAccess,
        showTopBar = false,
        onManagePermission = onManagePermission,
        onRefresh = onRefresh,
        onAlbum = onAlbum,
        onSharing = onSharing,
        gridColumns = columns,
        onGridColumnsChanged = onColumnsChanged,
    )
}

/** Stateless local-album overview used by the app and debug previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalAlbumOverview(
    state: GalleryUiState,
    partialAccess: Boolean,
    initialColumns: Int = 2,
    showTopBar: Boolean = true,
    onManagePermission: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onAlbum: (LocalAlbum) -> Unit = {},
    onSharing: (LocalAlbum, Boolean) -> Unit = { _, _ -> },
    gridColumns: Int? = null,
    onGridColumnsChanged: (Int) -> Unit = {},
) {
    var previewColumns by rememberSaveable { mutableIntStateOf(initialColumns.coerceIn(1, 4)) }
    val columns = gridColumns ?: previewColumns
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
                            Text("Meine Alben", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (state.albums.size == 1) "1 Album" else "${state.albums.size} Alben",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { if (partialAccess) TextButton(onClick = onManagePermission) { Text("Auswahl") } },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        when {
            state.loading && state.albums.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Medien konnten nicht gelesen werden.")
                Button(onClick = onRefresh) { Text("Erneut versuchen") }
            }
            state.albums.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Keine zugänglichen lokalen Alben gefunden.") }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().pinchToResizeGrid(columns, 1, 4) {
                    if (gridColumns == null) previewColumns = it else onGridColumnsChanged(it)
                },
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(if (columns >= 3) 4.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (columns >= 3) 8.dp else 16.dp),
            ) {
                items(state.albums, key = LocalAlbum::id) { album ->
                    AlbumCard(album, state.syncProgress[album.id], onAlbum, { onSharing(album, it) }, compact = columns >= 3)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(
    album: LocalAlbum,
    progress: AlbumSyncProgress?,
    onAlbum: (LocalAlbum) -> Unit,
    onSharing: (Boolean) -> Unit,
    compact: Boolean,
) {
    var actionMenuVisible by remember(album.id) { mutableStateOf(false) }
    Box {
        Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { onAlbum(album) },
            onLongClick = { actionMenuVisible = true },
        ),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(album.coverUri).build(),
                contentDescription = "Cover von ${album.name}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
            )
            if (progress?.shareRequested == true) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                ) { Text("Geteilt", modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall) }
            }
        }
        Column(Modifier.padding(if (compact) 8.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text("${album.imageCount} Fotos · ${album.videoCount} Videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mit Partner teilen", style = MaterialTheme.typography.bodyMedium)
                        syncProgressText(progress)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                    Switch(checked = progress?.shareRequested == true, onCheckedChange = onSharing)
                }
            }
        }
    }
        DropdownMenu(expanded = actionMenuVisible, onDismissRequest = { actionMenuVisible = false }) {
            val shared = progress?.shareRequested == true
            DropdownMenuItem(
                text = { Text(if (shared) "Freigabe beenden" else "Mit Partner teilen") },
                onClick = { actionMenuVisible = false; onSharing(!shared) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumMediaGrid(
    album: LocalAlbum,
    gallery: GalleryViewModel,
    progress: AlbumSyncProgress?,
    columns: Int,
    onSharing: (Boolean) -> Unit,
    onColumnsChanged: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val flow = remember(album.id) { gallery.media(album) }
    val media = flow.collectAsLazyPagingItems()
    var selectedMedia by remember(album.id) { mutableStateOf<LocalMedia?>(null) }
    selectedMedia?.let { selected ->
        BackHandler { selectedMedia = null }
        LocalMediaViewerContent(selected, onBack = { selectedMedia = null })
        return
    }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { AlbumMediaTopBar(album, progress, onBack, onSharing, scrollBehavior) },
    ) { padding ->
        when (val refresh = media.loadState.refresh) {
            LoadState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is LoadState.Error -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Album konnte nicht geladen werden.")
                Button(onClick = media::retry) { Text("Erneut versuchen") }
            }
            is LoadState.NotLoading -> LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().pinchToResizeGrid(columns, 2, 7, onColumnsChanged),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = padding.calculateTopPadding() + 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(count = media.itemCount, key = { index -> media.peek(index)?.uri ?: index }) { index ->
                    media[index]?.let { item -> MediaTile(item) { selectedMedia = item } }
                }
                if (media.loadState.append is LoadState.Loading) {
                    item { Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            }
        }
    }
}

/** Static counterpart of the paged album grid for previewing representative media. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalAlbumMediaGridPreviewContent(
    album: LocalAlbum,
    media: List<LocalMedia>,
    progress: AlbumSyncProgress? = null,
    initialColumns: Int = 3,
) {
    var columns by rememberSaveable { mutableIntStateOf(initialColumns.coerceIn(2, 7)) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { AlbumMediaTopBar(album, progress, {}, {}, scrollBehavior) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().pinchToResizeGrid(columns, 2, 7) { columns = it },
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = padding.calculateTopPadding() + 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
        ) { items(media, key = LocalMedia::id) { item -> MediaTile(item) {} } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumMediaTopBar(
    album: LocalAlbum,
    progress: AlbumSyncProgress?,
    onBack: () -> Unit,
    onSharing: (Boolean) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val shared = progress?.shareRequested == true
    TopAppBar(
        navigationIcon = { IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) } },
        title = {
            Column {
                Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("${album.imageCount} Fotos · ${album.videoCount} Videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            IconButton(onClick = { menuExpanded = true }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (shared) "Freigabe beenden" else "Mit Partner teilen") },
                    onClick = { menuExpanded = false; onSharing(!shared) },
                )
                syncProgressText(progress)?.let { status -> DropdownMenuItem(text = { Text(status) }, enabled = false, onClick = {}) }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun MediaTile(media: LocalMedia, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
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

@Composable
internal fun LocalMediaViewerContent(media: LocalMedia, onBack: () -> Unit = {}) {
    var chromeVisible by remember(media.id) { mutableStateOf(true) }
    var scale by remember(media.id) { mutableFloatStateOf(1f) }
    val transform = rememberTransformableState { zoom, _, _ -> scale = (scale * zoom).coerceIn(1f, 6f) }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(media.uri).build(),
            contentDescription = media.displayName,
            modifier = Modifier.fillMaxSize().clickable { chromeVisible = !chromeVisible }.transformable(transform).graphicsLayer(scaleX = scale, scaleY = scale),
            contentScale = ContentScale.Fit,
        )
        if (chromeVisible) {
            Surface(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Color.Black.copy(alpha = 0.62f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹  Zurück", color = Color.White) }
                    Text(media.displayName, modifier = Modifier.weight(1f), maxLines = 1, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    if (media.kind == LocalMediaKind.VIDEO) Text("VIDEO", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
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
    if (!progress.remoteShared) return if (progress.lastError == null) "Wird vorbereitet …" else "Synchronisierung pausiert"
    progress.lastError?.let { return "Synchronisierung pausiert" }
    if (progress.lastScanAt == null) return "Fotos werden vorbereitet …"
    if (progress.totalCount == 0L) return "Geteilt"
    return when {
        progress.failedCount > 0 -> "Einige Fotos brauchen Aufmerksamkeit"
        progress.completedCount == progress.totalCount -> "Aktuell geteilt"
        else -> "Wird synchronisiert …"
    }
}
