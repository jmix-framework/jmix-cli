package io.jmix.cli.util

import java.io.InputStream
import java.net.URI
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/** Host of [url] for labels such as "from global.repo.jmix.io"; the location itself when it has none. */
internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

/** Content-Length of a response, or -1 when the server did not send one. */
internal fun HttpResponse<*>.contentLength(): Long =
    headers().firstValueAsLong("content-length").orElse(-1L)

/**
 * Copies [input] into [target], replacing it, and reports progress after every
 * chunk as (bytes so far, [total]) so downloads can show real numbers.
 * [total] is -1 when the size is unknown.
 */
internal fun copyWithProgress(input: InputStream, target: Path, total: Long, onProgress: (Long, Long) -> Unit) {
    var done = 0L
    input.use { source ->
        Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                done += read
                onProgress(done, total)
            }
        }
    }
}

private const val COPY_BUFFER_SIZE = 64 * 1024
