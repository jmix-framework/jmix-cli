package io.jmix.cli.util

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val BYTES_PER_MIB = 1L shl 20

/** Host of [url] for labels such as "from global.repo.jmix.io"; the location itself when it has none. */
internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

/** Content-Length of a response, or -1 when the server did not send one. */
internal fun HttpResponse<*>.contentLength(): Long =
    headers().firstValueAsLong("content-length").orElse(-1L)

/** True when [file] starts with the zip magic number (also true for jars). Says nothing about the rest of the file. */
internal fun isZip(file: Path): Boolean = try {
    Files.newInputStream(file).use { input ->
        val magic = input.readNBytes(4)
        magic.size == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
    }
} catch (e: IOException) {
    false
}

/** Copies an HTTP body with a byte limit and an idle timeout that resets while data arrives. */
internal fun copyHttpBody(
    input: InputStream,
    target: Path,
    total: Long,
    timeout: Duration,
    onProgress: (Long, Long) -> Unit,
    maxBytes: Long = 1L shl 30,
) {
    val timeoutNanos = timeout.toNanos()
    val expired = AtomicBoolean()
    val lastProgress = AtomicLong(System.nanoTime())
    // The request future completes at the headers for ofInputStream bodies.
    Executors.newSingleThreadScheduledExecutor().use { timer ->
        val stop = timer.scheduleWithFixedDelay({
            if (System.nanoTime() - lastProgress.get() >= timeoutNanos) {
                expired.set(true)
                runCatching { input.close() }
            }
        }, timeoutNanos, minOf(timeoutNanos, SECONDS.toNanos(1)), NANOSECONDS)
        try {
            copyWithProgress(input, target, total, { done, size ->
                lastProgress.set(System.nanoTime())
                onProgress(done, size)
            }, maxBytes)
        } catch (e: IOException) {
            if (expired.get()) throw HttpTimeoutException("The HTTP response timed out").apply { initCause(e) }
            throw e
        } finally {
            stop.cancel(false)
        }
        if (expired.get()) throw HttpTimeoutException("The HTTP response timed out")
    }
}

/**
 * Copies [input] into [target], replacing it, and reports progress after every
 * chunk as (bytes so far, [total]) so downloads can show real numbers.
 * [total] is -1 when the size is unknown. The copy fails as soon as it has
 * read more than [maxBytes], whatever the server announced; what was written
 * so far stays in [target].
 */
internal fun copyWithProgress(
    input: InputStream,
    target: Path,
    total: Long,
    onProgress: (Long, Long) -> Unit,
    maxBytes: Long = Long.MAX_VALUE,
) {
    var done = 0L
    input.use { source ->
        Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                done += read
                if (done > maxBytes) throw IOException("download exceeds ${maxBytes / BYTES_PER_MIB} MiB")
                output.write(buffer, 0, read)
                onProgress(done, total)
            }
        }
    }
}

private const val COPY_BUFFER_SIZE = 64 * 1024
