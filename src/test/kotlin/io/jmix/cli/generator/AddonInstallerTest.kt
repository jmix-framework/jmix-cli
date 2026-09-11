package io.jmix.cli.generator

import groovyjarjarasm.asm.ClassWriter
import groovyjarjarasm.asm.Opcodes
import groovyjarjarasm.asm.Type
import io.jmix.cli.addon.AddonCatalog
import io.jmix.cli.addon.AddonCatalogTest
import io.jmix.cli.addon.AddonProjectProfile
import io.jmix.cli.template.TemplateMetadata
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AddonInstallerTest {
    @TempDir lateinit var tempDir: Path

    private fun info(addon: Boolean = false) = ProjectCreationInfo(
        name = "sample", targetDir = tempDir, rootPackage = "com.example", jmixVersion = "3.0.1",
        templateMetadata = TemplateMetadata(addon = addon), createGitRepository = false,
        addons = AddonCatalog.parse(AddonCatalogTest.CATALOG_JSON)
            .select(listOf("sample"), "3.0.1", AddonProjectProfile("build.gradle", emptySet())),
    )

    private fun artifact(
        changelog: String = "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"/>",
        extraResources: Map<String, String> = emptyMap(),
    ): AddonInstaller.Artifact {
        val jar = tempDir.resolve("sample.jar")
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/SampleConfiguration", null, "java/lang/Object", null)
        val annotation = writer.visitAnnotation("Lio/jmix/core/annotation/JmixModule;", true)
        annotation.visit("id", "custom.sample")
        annotation.visitArray("dependsOn").also { it.visit(null, Type.getObjectType("demo/BaseConfiguration")); it.visitEnd() }
        annotation.visitEnd()
        writer.visitEnd()
        JarOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(JarEntry("demo/SampleConfiguration.class")); zip.write(writer.toByteArray()); zip.closeEntry()
            zip.putNextEntry(JarEntry("custom/sample/liquibase/changelog.xml"))
            zip.write(changelog.toByteArray())
            zip.closeEntry()
            for ((path, content) in extraResources) {
                zip.putNextEntry(JarEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
        return AddonInstaller.Artifact("demo", "sample", "3.0.1", jar.toString())
    }

    @Test
    fun `artifact metadata supplies custom module ID and changelog without class loading`() {
        val module = AddonInstaller.readModules(listOf(artifact())).single()
        assertEquals("custom.sample", module.id)
        assertEquals("custom/sample/liquibase/changelog.xml", module.changelog)
        assertEquals(listOf("demo.BaseConfiguration"), module.dependsOn)
    }

    @Test
    fun `module order honors dependencies and rejects cycles`() {
        val first = AddonInstaller.Module("A", "a", listOf("Z"), "g:a", null)
        val second = AddonInstaller.Module("Z", "z", emptyList(), "g:z", null)
        assertEquals(listOf("Z", "A"), AddonInstaller.sortModules(listOf(first, second)).map { it.className })
        assertThrows(IOException::class.java) { AddonInstaller.sortModules(listOf(first, second.copy(dependsOn = listOf("A")))) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n"])
    fun `dependencies start the main block and do not duplicate selections`(newline: String) {
        val build = tempDir.resolve("build.gradle")
        val buildscript = """
            buildscript {
                dependencies { classpath 'demo:plugin' }
            }

        """.trimIndent().replace("\n", newline)
        Files.writeString(build, buildscript + "dependencies {${newline}    implementation 'demo:existing'${newline}}$newline")
        AddonInstaller.appendDependencies(info(), build)
        AddonInstaller.appendDependencies(info(), build)
        val text = Files.readString(build)
        assertTrue(text.startsWith(buildscript))
        assertTrue(text.contains("dependencies {$newline    // Selected Jmix add-ons$newline    implementation 'demo:sample-starter:3.0.1'"))
        assertEquals(1, Regex("(?m)^dependencies").findAll(text).count())
        assertTrue(text.contains("demo:existing"))
        assertTrue(text.indexOf("demo:sample-starter") < text.indexOf("demo:existing"))
        assertEquals(1, Regex("demo:sample-starter:3.0.1").findAll(text).count())
        if (newline == "\r\n") assertFalse(text.replace(newline, "").contains('\n'))
    }

    @Test
    fun `missing main dependencies block leaves the build file unchanged`() {
        val build = tempDir.resolve("build.gradle")
        val text = "buildscript {\n    dependencies { classpath 'demo:plugin' }\n}\n"
        Files.writeString(build, text)

        assertThrows(IOException::class.java) { AddonInstaller.appendDependencies(info(), build) }
        assertEquals(text, Files.readString(build))
    }

    @Test
    fun `Java and Kotlin addon configuration preserve existing module declarations`() {
        val modules = AddonInstaller.readModules(listOf(artifact()))
        for ((extension, before, expected) in listOf(
            Triple("java", "@JmixModule(dependsOn = {Existing.class})\nclass Config {}", "demo.SampleConfiguration.class"),
            Triple("kt", "@JmixModule(dependsOn = [Existing::class])\nclass Config", "demo.SampleConfiguration::class"),
        )) {
            val dir = Files.createDirectories(tempDir.resolve(extension).resolve("src/main"))
            val file = dir.resolve("Config.$extension")
            Files.writeString(file, before)
            AddonInstaller.configureModule(info(true), tempDir.resolve(extension), modules)
            AddonInstaller.configureModule(info(true), tempDir.resolve(extension), modules)
            val text = Files.readString(file)
            assertTrue(text.contains("Existing"))
            assertEquals(1, Regex(Regex.escape(expected)).findAll(text).count())
        }
    }

    @Test
    fun `addon test changelog is active and dependency includes precede project migrations`() {
        val artifact = artifact()
        val file = tempDir.resolve("src/test/resources/com/example/liquibase/changelog.xml")
        Files.createDirectories(file.parent)
        Files.writeString(file, """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <includeAll path="com/example/liquibase/changelog"/>
            </databaseChangeLog>
        """.trimIndent())
        val modules = AddonInstaller.readModules(listOf(artifact))
        AddonInstaller.configureChangelogs(info(true), tempDir, modules, listOf(artifact))
        AddonInstaller.configureChangelogs(info(true), tempDir, modules, listOf(artifact))
        val text = Files.readString(file)
        assertEquals(1, Regex("custom/sample/liquibase/changelog.xml").findAll(text).count())
        assertTrue(text.indexOf("custom/sample") < text.indexOf("includeAll"))
        assertFalse(text.contains("contextFilter"))
        assertFalse(Files.exists(tempDir.resolve("src/main/resources/com/example/liquibase/changelog.xml")))
    }

    @Test
    fun `Studio addon context covers transitive roots only in addon main changelogs`() {
        val baseRoot = "io/jmix/data/liquibase/changelog.xml"
        val artifact = artifact("""
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <include file="intermediate.xml" relativeToChangelogFile="true" contextFilter="@jmix-addon"/>
            </databaseChangeLog>
        """.trimIndent(), mapOf("custom/sample/liquibase/intermediate.xml" to """
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <include file="/$baseRoot"/>
            </databaseChangeLog>
        """.trimIndent()))
        val modules = listOf(AddonInstaller.Module("Base", "io.jmix.data", emptyList(), "io.jmix.data:jmix-data", baseRoot)) +
            AddonInstaller.readModules(listOf(artifact))
        for (scope in listOf("main", "test")) {
            val file = tempDir.resolve("src/$scope/resources/com/example/liquibase/changelog.xml")
            Files.createDirectories(file.parent)
            Files.writeString(file, "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\">\n</databaseChangeLog>")
        }
        AddonInstaller.configureChangelogs(info(true), tempDir, modules, listOf(artifact))
        val main = Files.readString(tempDir.resolve("src/main/resources/com/example/liquibase/changelog.xml"))
        val test = Files.readString(tempDir.resolve("src/test/resources/com/example/liquibase/changelog.xml"))
        assertTrue(main.contains("contextFilter=\"@jmix-addon\""))
        assertFalse(main.contains(baseRoot))
        assertTrue(test.contains(baseRoot))
        assertFalse(test.contains("contextFilter"))
    }

    @Test
    fun `non XML changesets remain untouched and malformed roots report their resource path`() {
        val artifact = artifact("""
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                <include file="custom/sample/schema.sql"/>
            </databaseChangeLog>
        """.trimIndent(), mapOf("custom/sample/schema.sql" to "--liquibase formatted sql\n--changeset test:1\nselect 1;"))
        val file = tempDir.resolve("src/main/resources/com/example/liquibase/changelog.xml")
        Files.createDirectories(file.parent)
        Files.writeString(file, "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\">\n</databaseChangeLog>")
        val modules = AddonInstaller.readModules(listOf(artifact))
        AddonInstaller.configureChangelogs(info(), tempDir, modules, listOf(artifact))
        assertTrue(Files.readString(file).contains("custom/sample/liquibase/changelog.xml"))
        artifact("<broken>")
        val failure = assertThrows(IOException::class.java) {
            AddonInstaller.configureChangelogs(info(), tempDir, modules, listOf(artifact))
        }
        assertTrue(failure.message!!.contains("custom/sample/liquibase/changelog.xml"))
    }

    @Test
    fun `security prerequisites are added only to addon test scope and preserve existing users`() {
        val security = AddonInstaller.Module("io.jmix.security.SecurityConfiguration", "io.jmix.security",
            emptyList(), "io.jmix.security:jmix-security", null)
        for ((extension, repository) in listOf("java" to "", "kt" to "", "java" to "@Bean UserRepository userRepository() { return null; }")) {
            val module = Files.createTempDirectory(tempDir, "test-security-")
            val source = Files.createDirectories(module.resolve("src/test"))
            val config = source.resolve("TestConfiguration.$extension")
            Files.writeString(config, "@SpringBootConfiguration\nclass TestConfiguration {\n$repository\n}\n")
            val build = module.resolve("module.gradle")
            Files.writeString(build, "dependencies {}\n")
            AddonInstaller.configureTestSecurity(build, emptyList())
            assertFalse(Files.readString(build).contains("security-starter"))
            repeat(2) { AddonInstaller.configureTestSecurity(build, listOf(security)) }
            assertEquals(1, Regex("testImplementation 'io.jmix.security:jmix-security-starter'").findAll(Files.readString(build)).count())
            assertEquals(1, Regex("(?m)^dependencies").findAll(Files.readString(build)).count())
            assertEquals(if (repository.isEmpty()) 1 else 0,
                Regex("addonTestUserRepository").findAll(Files.readString(config)).count())
            if (extension == "kt") assertTrue(Files.readString(config).contains("open fun addonTestUserRepository"))
        }
    }
}
