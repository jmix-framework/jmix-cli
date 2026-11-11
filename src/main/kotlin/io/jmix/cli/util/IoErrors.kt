package io.jmix.cli.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException

/** Formats a short cause for CLI errors, using [server] to name the destination of a failed connection. */
internal fun describeIoError(e: Throwable, server: String = "the release server"): String = when (e) {
    is UnknownHostException, is ConnectException, is HttpTimeoutException, is SocketException ->
        "cannot reach $server"
    is AccessDeniedException -> "no permission to write ${e.file}"
    is FileSystemException -> "${e.reason ?: "file system error"}: ${e.file}"
    else -> e.message?.takeIf { it.isNotBlank() }?.let {
        if (e is IOException) it else "${e::class.simpleName}: $it"
    } ?: (e::class.simpleName ?: "unknown error")
}
