package de.photosync.data.sync

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.FileNotFoundException

internal class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val totalLength: Long,
    private val offset: Long = 0,
    private val chunkLength: Long = totalLength - offset,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    init {
        require(offset >= 0 && chunkLength > 0 && offset + chunkLength <= totalLength)
    }

    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = chunkLength

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.use {
            var skipped = 0L
            while (skipped < offset) {
                val count = it.skip(offset - skipped)
                if (count > 0) {
                    skipped += count
                } else {
                    if (it.read() < 0) throw IllegalStateException("Media changed while upload was paused")
                    skipped += 1
                }
            }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            while (written < chunkLength) {
                val count = it.read(buffer, 0, minOf(buffer.size.toLong(), chunkLength - written).toInt())
                if (count < 0) throw IllegalStateException("Media changed while it was being uploaded")
                sink.write(buffer, 0, count)
                written += count
                onProgress(offset + written)
            }
            check(written == chunkLength) { "Media changed while it was being uploaded" }
        }
    }
}
