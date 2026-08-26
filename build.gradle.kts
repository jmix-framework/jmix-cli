import io.jmix.cli.gradle.JlinkRuntimeTask
import io.jmix.cli.gradle.JpackageAppImageTask
import io.jmix.cli.gradle.Sha256Task
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "io.jmix.cli"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()

val mordantFfm = libs.mordant.ffm.get().module

configurations.configureEach {
    // The pinned Mordant FFM backend defines an undersized macOS termios struct,
    // which lets tcgetattr write past its native buffer. Use JNA for raw mode.
    exclude(group = mordantFfm.group, module = mordantFfm.name)
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.jna)
    implementation(libs.mordant.runtime)
    implementation(libs.mordant.jna)
    implementation(libs.groovy.templates)
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("io.jmix.cli.MainKt")
    // Mordant's JNA terminal detection needs native access on JDK 24+.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    inputs.property(
        "integrationTestsEnabled",
        providers.environmentVariable("JMIX_CLI_IT")
            .map { it.equals("true", ignoreCase = true) }
            .orElse(false),
    )
    testLogging {
        // Full failure messages in CI logs; the short form hides them.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<JavaExec>("run") {
    // Let the wizard read answers when started via `gradlew run`.
    standardInput = System.`in`
}

// Self-contained release image: the CLI needs a JVM for Groovy templates, but
// users should not need to install Java before starting the wizard.
val releaseOs = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
    System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
    else -> error("Unsupported release operating system: ${System.getProperty("os.name")}")
}
val releaseArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "amd64", "x86_64", "x64" -> "x64"
    else -> error("Unsupported release architecture: ${System.getProperty("os.arch")}")
}
val releaseExtension = if (releaseOs == "windows") "zip" else "tar.gz"
val releaseArchiveName = "jmix-cli-$releaseOs-$releaseArch.$releaseExtension"
val releaseDirectory = layout.buildDirectory.dir("release")
val releaseArchive = releaseDirectory.map { it.file(releaseArchiveName) }
val releaseChecksum = releaseDirectory.map { it.file("$releaseArchiveName.sha256") }
val licenseFile = layout.projectDirectory.file("LICENSE")
val runtimeImageDirectory = layout.buildDirectory.dir("jpackage/runtime")
val appImageDirectory = layout.buildDirectory.dir("jpackage/image")
val applicationArchiveBaseName = project.name
val projectVersion = project.version.toString()
val distributionLibDirectory = layout.buildDirectory.dir("install/$applicationArchiveBaseName/lib")
// macOS requires the first app-version component to be positive. Release tags
// and archive contents still carry the real pre-1.0 project version.
val jpackageVersion = projectVersion
    .takeIf { it.matches(Regex("[1-9]\\d*(\\.\\d+){0,2}")) }
    ?: "1.0.0"
val packagingJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val executableSuffix = if (releaseOs == "windows") ".exe" else ""
val releaseRuntimeModules = listOf(
    "java.base",
    "java.compiler",
    "java.desktop",
    "java.logging",
    "java.management",
    "java.naming",
    "java.net.http",
    "java.prefs",
    "java.scripting",
    "java.sql",
    "java.xml",
    "jdk.crypto.ec",
    "jdk.unsupported",
    "jdk.zipfs",
)

val jlinkRuntime = tasks.register<JlinkRuntimeTask>("jlinkRuntime") {
    description = "Builds the minimal JVM runtime used by the release image."
    group = "distribution"

    jlinkExecutable.fileProvider(packagingJava.map {
        it.executablePath.asFile.parentFile.resolve("jlink$executableSuffix")
    })
    modules.set(releaseRuntimeModules)
    outputDirectory.set(runtimeImageDirectory)
}

val jpackageAppImage = tasks.register<JpackageAppImageTask>("jpackageAppImage") {
    description = "Builds a self-contained native launcher with the bundled JVM runtime."
    group = "distribution"
    dependsOn(tasks.installDist, jlinkRuntime)

    jpackageExecutable.fileProvider(packagingJava.map {
        it.executablePath.asFile.parentFile.resolve("jpackage$executableSuffix")
    })
    inputDirectory.set(distributionLibDirectory)
    runtimeDirectory.set(runtimeImageDirectory)
    applicationName.set("jmix")
    applicationVersion.set(jpackageVersion)
    mainJar.set("$applicationArchiveBaseName-$projectVersion.jar")
    mainClass.set("io.jmix.cli.MainKt")
    javaOptions.set(listOf(
        "--enable-native-access=ALL-UNNAMED",
        // Match the UTF-8 console code page set by WindowsConsole.enableUtf8:
        // otherwise the JVM encodes std streams in the legacy OEM code page and
        // every non-ASCII wizard glyph degrades to "?".
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dstdin.encoding=UTF-8",
    ))
    outputDirectory.set(appImageDirectory)
}

val archiveAppImage = if (releaseOs == "windows") {
    tasks.register<Zip>("archiveAppImage") {
        description = "Archives the self-contained Jmix CLI image."
        group = "distribution"
        dependsOn(jpackageAppImage)
        destinationDirectory.set(releaseDirectory)
        archiveFileName.set(releaseArchiveName)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(appImageDirectory)
        from(licenseFile)
    }
} else {
    tasks.register<Tar>("archiveAppImage") {
        description = "Archives the self-contained Jmix CLI image."
        group = "distribution"
        dependsOn(jpackageAppImage)

        destinationDirectory.set(releaseDirectory)
        archiveFileName.set(releaseArchiveName)
        compression = Compression.GZIP
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(appImageDirectory)
        from(licenseFile)
        eachFile {
            permissions {
                unix(if (file.canExecute()) "rwxr-xr-x" else "rw-r--r--")
            }
        }
    }
}

val appImageChecksum = tasks.register<Sha256Task>("appImageChecksum") {
    description = "Writes the SHA-256 checksum for the release archive."
    group = "distribution"
    dependsOn(archiveAppImage)

    inputFile.set(releaseArchive)
    inputFileName.set(releaseArchiveName)
    outputFile.set(releaseChecksum)
}

tasks.register("releaseBundle") {
    description = "Builds the platform release archive and checksum."
    group = "distribution"
    dependsOn(appImageChecksum)
}
