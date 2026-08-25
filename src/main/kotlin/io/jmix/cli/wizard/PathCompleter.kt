package io.jmix.cli.wizard

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A completion step: the new input text and, when ambiguous, the candidates to show. */
data class PathCompletion(val text: String, val candidates: List<String>)

/**
 * Tab-completion for directory paths typed into the wizard. Completes the last
 * path segment against existing directories: a single match completes fully
 * (with a trailing separator), several matches extend to their longest common
 * prefix, and a repeated Tab with no progress lists the candidates.
 */
object PathCompleter {

    fun complete(
        input: String,
        cwd: Path = Path.of("").toAbsolutePath(),
        home: Path = Path.of(System.getProperty("user.home")),
    ): PathCompletion {
        if (input == "~") return PathCompletion("~" + File.separator, emptyList())
        val separatorIndex = input.lastIndexOfAny(charArrayOf('/', '\\'))
        val dirText = if (separatorIndex >= 0) input.substring(0, separatorIndex + 1) else ""
        val segment = input.substring(separatorIndex + 1)

        val dir = resolveDir(dirText, cwd, home) ?: return PathCompletion(input, emptyList())
        val names = directoriesIn(dir)
            .filter { it.startsWith(segment) }
            .filter { segment.startsWith(".") || !it.startsWith(".") }
            .sorted()
        if (names.isEmpty()) return PathCompletion(input, emptyList())

        if (names.size == 1) {
            return PathCompletion(dirText + names.single() + File.separator, emptyList())
        }
        val prefix = names.reduce(::commonPrefix)
        return if (prefix.length > segment.length) {
            PathCompletion(dirText + prefix, emptyList())
        } else {
            PathCompletion(input, names)
        }
    }

    private fun resolveDir(dirText: String, cwd: Path, home: Path): Path? {
        val path = when {
            dirText.isEmpty() -> cwd
            dirText.startsWith("~/") || dirText.startsWith("~\\") ->
                home.resolve(dirText.removePrefix("~").trimStart('/', '\\'))
            else -> runCatching { Path.of(dirText) }.getOrNull()?.let {
                if (it.isAbsolute) it else cwd.resolve(it)
            }
        }
        return path?.takeIf(Files::isDirectory)
    }

    private fun directoriesIn(dir: Path): List<String> = try {
        Files.list(dir).use { entries ->
            entries.filter(Files::isDirectory).map { it.fileName.toString() }.toList()
        }
    } catch (e: IOException) {
        emptyList()
    }

    private fun commonPrefix(left: String, right: String): String =
        left.commonPrefixWith(right)
}
