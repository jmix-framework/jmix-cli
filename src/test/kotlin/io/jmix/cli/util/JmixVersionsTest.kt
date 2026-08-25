package io.jmix.cli.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JmixVersionComparatorTest {

    private fun compare(v1: String, v2: String) = JmixVersionComparator.compare(v1, v2)

    @Test
    fun `plain numeric comparison`() {
        assertTrue(compare("2.8.3", "2.8.2") > 0)
        assertTrue(compare("2.8.3", "3.0.0") < 0)
        assertEquals(0, compare("3.0.1", "3.0.1"))
        assertTrue(compare("2.10.0", "2.9.0") > 0)
    }

    @Test
    fun `SNAPSHOT segment compares as newest`() {
        assertTrue(compare("2.99-SNAPSHOT", "2.8.3") > 0)
        assertTrue(compare("2.8.3", "2.99-SNAPSHOT") < 0)
        assertTrue(compare("2.2-SNAPSHOT", "2.2-RC1") > 0)
        // Prefix rule beats the SNAPSHOT rule: extra segments make a version older.
        assertTrue(compare("3.0.0-SNAPSHOT", "3.0.0") < 0)
    }

    @Test
    fun `prefix quirk - shorter version is newer`() {
        // Studio's comparator: "3.0" > "3.0.1".
        assertTrue(compare("3.0", "3.0.1") > 0)
        assertTrue(compare("3.0.1", "3.0") < 0)
    }

    @Test
    fun `pre-release suffixes compare lexicographically`() {
        assertTrue(compare("3.0.0-RC1", "3.0.0-RC2") < 0)
    }
}

class PlatformVersionsTest {

    @Test
    fun `future unknown version detection`() {
        assertFalse(PlatformVersions.isFutureUnknownVersion("3.0.1"))
        assertFalse(PlatformVersions.isFutureUnknownVersion("2.8.3"))
        assertTrue(PlatformVersions.isFutureUnknownVersion("3.1.0"))
        assertTrue(PlatformVersions.isFutureUnknownVersion("4.0.0"))
    }

    @Test
    fun `supported platform version - above 1_0 only`() {
        assertTrue(PlatformVersions.isSupportedPlatformVersion("1.0.0"))
        assertTrue(PlatformVersions.isSupportedPlatformVersion("3.0.1"))
        assertFalse(PlatformVersions.isSupportedPlatformVersion("0.9.1"))
    }

    @Test
    fun `unstable version detection`() {
        assertTrue(PlatformVersions.isUnstable("3.0.0-RC1"))
        assertTrue(PlatformVersions.isUnstable("2.0.0-SNAPSHOT"))
        assertFalse(PlatformVersions.isUnstable("3.0.1"))
        assertFalse(PlatformVersions.isUnstable("2.8.3"))
    }

    @Test
    fun `version gates for theme visibility`() {
        assertTrue(PlatformVersions.isJmix2Plus("2.0.0"))
        assertFalse(PlatformVersions.isJmix2Plus("1.5.0"))
        assertTrue(PlatformVersions.isJmix3Plus("3.0.0"))
        assertTrue(PlatformVersions.isJmix3Plus("3.0.0-RC1"))
        assertFalse(PlatformVersions.isJmix3Plus("2.8.3"))
    }

    @Test
    fun `major minor extraction`() {
        assertEquals("3.0", PlatformVersions.majorMinor("3.0.1"))
        assertEquals("2.99", PlatformVersions.majorMinor("2.99-SNAPSHOT"))
    }

    @Test
    fun `latest patch per minor, newest line first`() {
        val ascending = listOf("2.6.1", "2.6.2", "2.7.0", "2.7.6", "2.8.0", "2.8.3", "3.0.0", "3.0.1")
        assertEquals(
            listOf("3.0.1", "2.8.3", "2.7.6", "2.6.2"),
            PlatformVersions.latestPatchPerMinor(ascending),
        )
    }
}
