package de.photosync.domain.model

enum class LocalMediaKind { IMAGE, VIDEO }

data class LocalAlbum(
    val id: String,
    val volumeName: String,
    val bucketId: String,
    val name: String,
    val coverUri: String,
    val imageCount: Int,
    val videoCount: Int,
    val relativePath: String = "",
) {
    val totalCount: Int get() = imageCount + videoCount
}

data class LocalMedia(
    val id: Long,
    val uri: String,
    val displayName: String,
    val kind: LocalMediaKind,
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    val takenAtMillis: Long,
)

data class MediaMetadata(
    val volumeName: String,
    val bucketId: String,
    val relativePath: String,
    val bucketName: String?,
    val uri: String,
    val kind: LocalMediaKind,
)

class AlbumAccumulator {
    private data class MutableAlbum(
        val volumeName: String,
        val bucketId: String,
        val relativePath: String,
        var name: String,
        var coverUri: String,
        var coverKind: LocalMediaKind,
        var imageCount: Int = 0,
        var videoCount: Int = 0,
    )

    private val albums = linkedMapOf<String, MutableAlbum>()

    fun add(media: MediaMetadata) {
        val relativePath = normalizeAlbumRelativePath(media.relativePath)
        val key = albumSourceKey(media.volumeName, relativePath)
        val album = albums.getOrPut(key) {
            MutableAlbum(
                volumeName = media.volumeName,
                bucketId = media.bucketId,
                relativePath = relativePath,
                name = media.bucketName?.takeIf(String::isNotBlank) ?: "Ohne Album",
                coverUri = media.uri,
                coverKind = media.kind,
            )
        }
        if (album.name == "Ohne Album" && !media.bucketName.isNullOrBlank()) album.name = media.bucketName
        if (album.coverKind == LocalMediaKind.VIDEO && media.kind == LocalMediaKind.IMAGE) {
            album.coverUri = media.uri
            album.coverKind = media.kind
        }
        when (media.kind) {
            LocalMediaKind.IMAGE -> album.imageCount++
            LocalMediaKind.VIDEO -> album.videoCount++
        }
    }

    fun result(): List<LocalAlbum> = albums.map { (id, album) ->
        LocalAlbum(
            id = id,
            volumeName = album.volumeName,
            bucketId = album.bucketId,
            relativePath = album.relativePath,
            name = album.name,
            coverUri = album.coverUri,
            imageCount = album.imageCount,
            videoCount = album.videoCount,
        )
    }
}

internal fun normalizeAlbumRelativePath(value: String): String {
    val parts = java.text.Normalizer.normalize(
        value.replace('\\', '/'),
        java.text.Normalizer.Form.NFKC,
    ).split('/').map(String::trim).filter(String::isNotEmpty)
    val relative = when {
        parts.size >= 3 && parts.take(3).map(String::lowercase) == listOf("storage", "emulated", "0") ->
            parts.drop(3)
        parts.size >= 3 && parts.take(3).map(String::lowercase) == listOf("storage", "self", "primary") ->
            parts.drop(3)
        parts.firstOrNull()?.equals("sdcard", ignoreCase = true) == true -> parts.drop(1)
        else -> parts
    }
    return relative.joinToString("/").lowercase()
}

internal fun normalizeAlbumVolume(value: String): String {
    val normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).trim().lowercase()
    return if (normalized == "external") "external_primary" else normalized
}

internal fun albumSourceKey(volumeName: String, relativePath: String): String =
    normalizeAlbumVolume(volumeName) + "|" + normalizeAlbumRelativePath(relativePath)
