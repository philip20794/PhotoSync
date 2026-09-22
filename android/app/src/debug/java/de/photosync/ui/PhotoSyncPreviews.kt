@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.photosync.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import de.photosync.data.local.AlbumSyncProgress
import de.photosync.data.local.SyncSettingsEntity
import de.photosync.data.offline.OfflineAlbumEntity
import de.photosync.data.offline.OfflineMode
import de.photosync.data.offline.OfflineStatus
import de.photosync.data.remote.AssetDto
import de.photosync.data.remote.PartnerAlbumDto
import de.photosync.data.remote.TrashAlbumDto
import de.photosync.data.remote.TrashAssetDto
import de.photosync.data.remote.UserDto
import de.photosync.domain.model.LocalAlbum
import de.photosync.domain.model.LocalMedia
import de.photosync.domain.model.LocalMediaKind
import de.photosync.ui.gallery.GalleryUiState
import de.photosync.ui.gallery.LocalAlbumMediaGridPreviewContent
import de.photosync.ui.gallery.LocalAlbumOverview
import de.photosync.ui.gallery.LocalMediaViewerContent
import de.photosync.ui.partner.PartnerAlbumGridPreviewContent
import de.photosync.ui.partner.PartnerAlbumOverviewContent
import de.photosync.ui.partner.PartnerGalleryState
import de.photosync.ui.partner.PartnerAssetViewerContent
import de.photosync.ui.settings.SettingsContent
import de.photosync.ui.settings.SettingsUiState
import de.photosync.ui.theme.PhotoSyncTheme
import de.photosync.ui.trash.TrashContent
import de.photosync.ui.trash.TrashState

/** Debug-only fixtures: no server, MediaStore, credentials, or image downloads are used by previews. */
private object PreviewFixtures {
    private const val timestamp = "2026-09-21T10:30:00Z"
    private val owner = UserDto("partner-1", "Mara", "mara")
    val localAlbums = listOf(
        LocalAlbum("camera", "external_primary", "42", "Kamera", "content://preview/camera", 842, 61),
        LocalAlbum("holiday", "external_primary", "7", "Sommerurlaub an der Atlantikküste 2026 – unbearbeitet", "content://preview/holiday", 126, 18),
        LocalAlbum("empty", "external_primary", "8", "Leeres Album", "content://preview/empty", 0, 0),
    )
    val localProgress = AlbumSyncProgress("camera", "camera", true, true, 1L, null, 903, 811, 2, 7_900_000_000, 9_600_000_000)
    val media = List(18) { index ->
        LocalMedia((index + 1).toLong(), "content://preview/media/$index", if (index % 5 == 0) "VID_${index + 1}.mp4" else "IMG_${index + 1}.jpg", if (index % 5 == 0) LocalMediaKind.VIDEO else LocalMediaKind.IMAGE, 4032, 3024, if (index % 5 == 0) 83_000L else null, 0)
    }
    val partnerAlbums = listOf(
        PartnerAlbumDto("summer", owner, "Sommerurlaub 2026 – Atlantikküste und kleine Ausflüge", false, true, 1_284, "4294967296", "17179869184", null, timestamp, timestamp),
        PartnerAlbumDto("weekend", owner, "Wochenende", false, true, 42, "245366784", "1073741824", null, timestamp, timestamp),
        PartnerAlbumDto("empty-partner", owner, "Noch leer", false, true, 0, "0", "0", null, timestamp, timestamp),
    )
    val partnerAssets = List(21) { index -> asset("asset-$index", if (index % 4 == 0) "clip_${index + 1}.mp4" else "urlaub_${index + 1}.jpg", if (index % 4 == 0) "video/mp4" else "image/jpeg") }
    fun asset(id: String = "hero", name: String = "Sonnenuntergang-am-Meer.jpg", mime: String = "image/jpeg") = AssetDto(id, owner.id, "summer", name, mime, timestamp, "5242880", 4032, 3024, if (mime.startsWith("video/")) "83000" else null, "abc", status = "ready", createdAt = timestamp, updatedAt = timestamp)
    val trash = listOf(
        TrashAssetDto("trash-1", owner.id, "summer", "Familienfoto.jpg", "image/jpeg", timestamp, "3242880", 3024, 4032, null, "abc", "trashed", deletedAt = timestamp, purgeAfter = "2026-12-20T10:30:00Z", remainingRetentionSeconds = 7_776_000, originalAlbum = TrashAlbumDto("summer", "Sommerurlaub 2026"), createdAt = timestamp, updatedAt = timestamp),
        TrashAssetDto("trash-2", owner.id, "weekend", "Wanderung.mp4", "video/mp4", timestamp, "8242880", 1920, 1080, "126000", "def", "trashed", deletedAt = timestamp, purgeAfter = "2026-10-02T10:30:00Z", remainingRetentionSeconds = 950_000, originalAlbum = TrashAlbumDto("weekend", "Wochenende"), createdAt = timestamp, updatedAt = timestamp),
    )
}

@Composable private fun PreviewTheme(content: @Composable () -> Unit) = PhotoSyncTheme(content)

@Preview(name = "Meine Alben", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun MyAlbumsPreview() = PreviewTheme { LocalAlbumOverview(GalleryUiState(PreviewFixtures.localAlbums, mapOf("camera" to PreviewFixtures.localProgress)), partialAccess = true) }

@Preview(name = "Albumübersicht – dicht", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun MyAlbumsDensePreview() = PreviewTheme { LocalAlbumOverview(GalleryUiState(PreviewFixtures.localAlbums, mapOf("camera" to PreviewFixtures.localProgress)), partialAccess = false, initialColumns = 4) }

@Preview(name = "Hauptnavigation", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun MainNavigationPreview() = PreviewTheme {
    Scaffold(topBar = { PhotoSyncHomeTopBar("PhotoSync", "Phil", secondary = false) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PhotoSyncHomeTabs(selectedTab = 0)
            LocalAlbumOverview(GalleryUiState(PreviewFixtures.localAlbums, mapOf("camera" to PreviewFixtures.localProgress)), partialAccess = false)
        }
    }
}

@Preview(name = "Geöffnetes Album", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun OpenAlbumPreview() = PreviewTheme { LocalAlbumMediaGridPreviewContent(PreviewFixtures.localAlbums.first(), PreviewFixtures.media, PreviewFixtures.localProgress) }

@Preview(name = "Geöffnetes Album – groß", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun OpenAlbumLargePreview() = PreviewTheme { LocalAlbumMediaGridPreviewContent(PreviewFixtures.localAlbums.first(), PreviewFixtures.media, PreviewFixtures.localProgress, initialColumns = 2) }

@Preview(name = "Geöffnetes Album – dicht", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun OpenAlbumDensePreview() = PreviewTheme { LocalAlbumMediaGridPreviewContent(PreviewFixtures.localAlbums.first(), PreviewFixtures.media, PreviewFixtures.localProgress, initialColumns = 6) }

@Preview(name = "Partner-Alben und Offline", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun PartnerAlbumsPreview() = PreviewTheme {
    val offline = mapOf(
        "summer" to OfflineAlbumEntity("preview", "summer", OfflineMode.ORIGINAL, 4_294_967_296, 17_179_869_184, OfflineStatus.READY),
        "weekend" to OfflineAlbumEntity("preview", "weekend", OfflineMode.OPTIMIZED, 245_366_784, 1_073_741_824, OfflineStatus.RETRY, "WLAN-Verbindung unterbrochen"),
    )
    PartnerAlbumOverviewContent(PartnerGalleryState(PreviewFixtures.partnerAlbums, cacheBytes = 786_432_000, cacheMaxBytes = 2_147_483_648, offlineAlbums = offline))
}

@Preview(name = "Partner-Album mit Fotos und Videos", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun PartnerAlbumPreview() = PreviewTheme { PartnerAlbumGridPreviewContent(PreviewFixtures.partnerAlbums.first(), PreviewFixtures.partnerAssets) }

@Preview(name = "Bildansicht", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun ImageViewerPreview() = PreviewTheme {
    PartnerAssetViewerContent(
        asset = PreviewFixtures.asset(),
        offlineStatus = "Offline verfügbar",
        body = { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { androidx.compose.material3.Text("Bildvorschau") } },
    )
}

@Preview(name = "Lokale Bildansicht", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun LocalImageViewerPreview() = PreviewTheme { LocalMediaViewerContent(PreviewFixtures.media[1]) }

@Preview(name = "Papierkorb", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun TrashPreview() = PreviewTheme { TrashContent(TrashState(PreviewFixtures.trash, selected = setOf("trash-1"), loading = false)) }

@Preview(name = "Einstellungen", showBackground = true, widthDp = 420, heightDp = 840)
@Composable fun SettingsPreview() = PreviewTheme {
    SettingsContent(
        state = SettingsUiState(
            preferences = SyncSettingsEntity("preview", autoBackupEnabled = true, wifiOnly = true, notifySyncErrors = true, lastSuccessfulSyncAt = 1_790_000_000_000, serverReachable = true),
            displayName = "Phil", deviceName = "Pixel 9", partnerName = "Mara",
            backups = PreviewFixtures.partnerAlbums, offlineBytes = 8_456_000_000,
            cacheBytes = 786_432_000, cacheMaxBytes = 2_147_483_648,
        ),
        displayName = "Phil", deviceName = "Pixel 9",
    )
}
