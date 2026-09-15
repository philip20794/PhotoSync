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
    private val length: Long,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.use {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
                written += count
                onProgress(written)
            }
            check(written == length) { "Media changed while it was being uploaded" }
        }
    }
}
