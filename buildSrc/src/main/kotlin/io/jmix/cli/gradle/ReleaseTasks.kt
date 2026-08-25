package io.jmix.cli.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.security.MessageDigest
import javax.inject.Inject

@DisableCachingByDefault(because = "jlink output is tied to the local JDK installation")
abstract class JlinkRuntimeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jlinkExecutable: RegularFileProperty

    @get:Input
    abstract val modules: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun createRuntime() {
        fileSystemOperations.delete { delete(outputDirectory) }
        execOperations.exec {
            executable(jlinkExecutable.get().asFile)
            args(
                "--add-modules", modules.get().joinToString(","),
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--output", outputDirectory.get().asFile,
            )
        }
    }
}

@DisableCachingByDefault(because = "jpackage output is platform-specific")
abstract class JpackageAppImageTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jpackageExecutable: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeDirectory: DirectoryProperty

    @get:Input
    abstract val applicationName: Property<String>

    @get:Input
    abstract val applicationVersion: Property<String>

    @get:Input
    abstract val mainJar: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val javaOptions: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun createImage() {
        fileSystemOperations.delete { delete(outputDirectory) }
        execOperations.exec {
            executable(jpackageExecutable.get().asFile)
            args(
                "--type", "app-image",
                "--dest", outputDirectory.get().asFile,
                "--name", applicationName.get(),
                "--description", "Jmix project generator",
                "--vendor", "Jmix",
                "--app-version", applicationVersion.get(),
                "--input", inputDirectory.get().asFile,
                "--main-jar", mainJar.get(),
                "--main-class", mainClass.get(),
                "--runtime-image", runtimeDirectory.get().asFile,
            )
            javaOptions.get().forEach { option ->
                args("--java-options", option)
            }
        }
    }
}

@DisableCachingByDefault(because = "computing the checksum is inexpensive")
abstract class Sha256Task : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFile: RegularFileProperty

    @get:Input
    abstract val inputFileName: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeChecksum() {
        val digest = MessageDigest.getInstance("SHA-256")
        inputFile.get().asFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val checksum = outputFile.get().asFile
        checksum.parentFile.mkdirs()
        checksum.writeText("$hash  ${inputFileName.get()}\n")
    }
}
