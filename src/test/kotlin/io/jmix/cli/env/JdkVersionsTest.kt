package io.jmix.cli.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JdkVersionsTest {

    @Test
    fun `jdk table ported from Studio`() {
        assertEquals(setOf(21, 25), JdkVersions.supportedJdks("3.0.0"))
        assertEquals(setOf(21, 25), JdkVersions.supportedJdks("3.0.1"))
        assertEquals(setOf(17, 21), JdkVersions.supportedJdks("2.2.0"))
        assertEquals(setOf(17, 21), JdkVersions.supportedJdks("2.8.3"))
        assertEquals(setOf(17), JdkVersions.supportedJdks("2.0.0"))
        assertEquals(setOf(17), JdkVersions.supportedJdks("2.1.0"))
        assertEquals(setOf(11, 17), JdkVersions.supportedJdks("1.3.0"))
        assertEquals(setOf(11, 17), JdkVersions.supportedJdks("1.5.2"))
        assertEquals(setOf(8, 11, 17), JdkVersions.supportedJdks("1.0.0"))
    }

    @Test
    fun `unmatched version falls back to newest range`() {
        assertEquals(setOf(21, 25), JdkVersions.supportedJdks("4.0.0"))
    }

    @Test
    fun `java version string parsing`() {
        assertEquals(21, JdkDetector.parseMajorVersion("21.0.1"))
        assertEquals(8, JdkDetector.parseMajorVersion("1.8.0_392"))
        assertEquals(17, JdkDetector.parseMajorVersion("17"))
        assertEquals(25, JdkDetector.parseMajorVersion("25.0.3+9"))
        assertEquals(null, JdkDetector.parseMajorVersion("not-a-version"))
    }
}
