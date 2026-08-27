package io.jmix.cli.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class CacheCleanerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `prunes stale cache files and keeps fresh ones`() {
        val now = Instant.now()
        val repoDir = Files.createDirectories(tempDir.resolve("globalrepojmixio"))
        val stale = repoDir.resolve("jmix-studio-templates-2.0.0.jar")
        Files.writeString(stale, "stale")
        Files.setLastModifiedTime(stale, FileTime.from(now.minus(CacheCleaner.STALE_AFTER).minusSeconds(60)))
        val fresh = repoDir.resolve("jmix-studio-templates-2.6.1.jar")
        Files.writeString(fresh, "fresh")

        CacheCleaner.pruneTemplateCache(tempDir, now)

        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(fresh))
    }

    @Test
    fun `tolerates a missing cache directory`() {
        CacheCleaner.pruneTemplateCache(tempDir.resolve("missing"), Instant.now())
    }
}
