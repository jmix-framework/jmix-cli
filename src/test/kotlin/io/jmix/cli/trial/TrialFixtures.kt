package io.jmix.cli.trial

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val TRIAL_ROOT = "jmix-commercial-addons-demo-3.1.0"

/** Same shape as the store's archive: one top folder, composite root, trial_repository, demos. */
internal fun writeTrialZip(zip: Path, vararg extra: Pair<String, String>): Path {
    val root = TRIAL_ROOT
    val entries = listOf(
        "$root/settings.gradle" to "includeBuild 'demo/bpm'\nincludeBuild 'demo/webdav'",
        "$root/build.gradle" to "ext.jmixCompositeProjectRoot = true",
        "$root/gradlew" to "#!/bin/sh",
        "$root/trial_repository/io/jmix/bpm/jmix-bpm/3.1.0/jmix-bpm-3.1.0.jar" to "jar",
        "$root/demo/bpm/gradlew" to "#!/bin/sh",
        "$root/demo/bpm/build.gradle" to "jmix {\n    bomVersion = '3.1.0'\n}",
        "$root/demo/bpm/src/main/resources/application.properties" to "server.port=8101",
        "$root/demo/webdav/gradlew" to "#!/bin/sh",
        "$root/demo/webdav/build.gradle" to "jmix { bomVersion = '3.1.0' }",
    ) + extra
    ZipOutputStream(Files.newOutputStream(zip)).use { out ->
        entries.forEach { (name, text) -> out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() }
    }
    return zip
}
