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
    val bucketName: String?,
    val uri: String,
    val kind: LocalMediaKind,
)

class AlbumAccumulator {
    private data class MutableAlbum(
        val volumeName: String,
        val bucketId: String,
        var name: String,
        var coverUri: String,
        var coverKind: LocalMediaKind,
        var imageCount: Int = 0,
        var videoCount: Int = 0,
    )

    private val albums = linkedMapOf<String, MutableAlbum>()

    fun add(media: MediaMetadata) {
        val key = "${media.volumeName}:${media.bucketId}"
        val album = albums.getOrPut(key) {
            MutableAlbum(
                volumeName = media.volumeName,
                bucketId = media.bucketId,
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
            name = album.name,
            coverUri = album.coverUri,
            imageCount = album.imageCount,
            videoCount = album.videoCount,
        )
    }
}
