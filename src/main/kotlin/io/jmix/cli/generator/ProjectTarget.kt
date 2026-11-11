package io.jmix.cli.generator

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Validates the chosen output directory and checks whether it already contains entries. */
internal fun projectTargetHasFiles(targetDir: Path): Boolean {
    val attributes = readPathAttributes(targetDir) ?: return false
    if (attributes.isSymbolicLink) throw IOException("Project directory $targetDir is a symbolic link. Choose another folder.")
    if (!attributes.isDirectory) throw IOException("$targetDir already exists and is not a directory.")
    return Files.list(targetDir).use { it.findAny().isPresent }
}

internal fun checkedProjectOutput(targetDir: Path, path: Path): Path {
    val root = targetDir.toAbsolutePath().normalize()
    val output = path.toAbsolutePath().normalize()
    if (!output.startsWith(root)) throw IOException("Output path $output is outside the project destination $root.")

    // shortcut: checks precede writes, use directory-relative file handles if concurrent path replacement must be excluded.
    var entry = root
    for (part in root.relativize(output)) {
        rejectLinkedOutput(entry)
        entry = entry.resolve(part)
    }
    rejectLinkedOutput(entry)
    return output
}

private fun rejectLinkedOutput(path: Path) {
    val attributes = readPathAttributes(path) ?: return
    if (attributes.isSymbolicLink || attributes.isOther) {
        throw IOException("Cannot write project output through a linked or special path: $path.")
    }
}

private fun readPathAttributes(path: Path): BasicFileAttributes? = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
} catch (_: NoSuchFileException) {
    null
}
