package io.jmix.cli.update

import io.jmix.cli.repo.TemplateRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Prunes template cache files untouched for [STALE_AFTER]. Pruned files are
 * re-downloaded on demand, so this only trims disk usage; recently used caches
 * stay available for offline fallback.
 */
object CacheCleaner {
    val STALE_AFTER: Duration = Duration.ofDays(120)

    fun pruneTemplateCache(
        cacheDir: Path = TemplateRepository.defaultCacheDir(),
        now: Instant = Instant.now(),
    ) {
        if (!Files.isDirectory(cacheDir)) return
        val cutoff = now.minus(STALE_AFTER)
        val stale = Files.walk(cacheDir).use { paths ->
            paths.toList().filter { path ->
                Files.isRegularFile(path) && runCatching {
                    Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)
                }.getOrDefault(false)
            }
        }
        stale.forEach { runCatching { Files.deleteIfExists(it) } }
    }
}
