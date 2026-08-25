package io.jmix.cli.env

import io.jmix.cli.util.PlatformVersions

/**
 * JDK compatibility table ported from Studio's JdkVersions.java. Ranges are
 * inclusive on both ends, checked top-down; no match falls back to the first
 * (newest) entry.
 */
object JdkVersions {

    private data class Range(val from: String?, val to: String?, val jdks: Set<Int>)

    private val ranges = listOf(
        Range("3.0.0", null, setOf(21, 25)),
        Range("2.2.0", "2.99-SNAPSHOT", setOf(17, 21)),
        Range("2.0.0", "2.1-SNAPSHOT", setOf(17)),
        Range("1.3.0", "1.99-SNAPSHOT", setOf(11, 17)),
        Range(null, "1.2-SNAPSHOT", setOf(8, 11, 17)),
    )

    fun supportedJdks(jmixVersion: String): Set<Int> =
        ranges.firstOrNull { range ->
            (range.from == null || PlatformVersions.compare(jmixVersion, range.from) >= 0) &&
                (range.to == null || PlatformVersions.compare(jmixVersion, range.to) <= 0)
        }?.jdks ?: ranges.first().jdks
}
