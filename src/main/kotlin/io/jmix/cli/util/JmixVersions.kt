package io.jmix.cli.util

/**
 * Version comparison ported verbatim from Studio's JmixVersionComparator.
 *
 * Quirks preserved intentionally: a "SNAPSHOT" segment compares as newest,
 * and when one version is a prefix of the other the SHORTER one is newer
 * ("2.1" > "2.1.0"). The JDK table and version filtering depend on both.
 */
object JmixVersionComparator : Comparator<String> {

    private val VERSION_SPLIT_REGEX = Regex("[.-]")

    override fun compare(v1: String, v2: String): Int {
        if (v1 == v2) return 0
        val parts1 = VERSION_SPLIT_REGEX.split(v1)
        val parts2 = VERSION_SPLIT_REGEX.split(v2)
        for (i in parts1.indices) {
            if (parts2.size <= i) return -1
            val s1 = parts1[i]
            val s2 = parts2[i]
            if (s1 == s2) continue
            if (s1 == "SNAPSHOT") return 1
            if (s2 == "SNAPSHOT") return -1
            val n1 = s1.toIntOrNull()
            val n2 = s2.toIntOrNull()
            return if (n1 != null && n2 != null) n1 - n2 else s1.compareTo(s2)
        }
        return if (parts1.size < parts2.size) 1 else 0
    }
}

/** Ported from Studio's PlatformVersion. */
object PlatformVersions {

    /** Newest Jmix minor this CLI build knows about; newer versions are filtered as "future". */
    const val LAST_KNOWN_MINOR_VERSION = "3.0"

    private val UNSTABLE_VERSION_PATTERN = Regex("(-\\w*$)|(\\.[a-zA-Z]+\\d*$)")

    fun compare(v1: String, v2: String): Int = JmixVersionComparator.compare(v1, v2)

    fun isJmix2Plus(version: String): Boolean = compare(version, "1.99.999-SNAPSHOT") > 0

    fun isJmix3Plus(version: String): Boolean = compare(version, "2.99.999-SNAPSHOT") > 0

    fun isFutureUnknownVersion(version: String): Boolean =
        compare(version, LAST_KNOWN_MINOR_VERSION) > 0

    /** Studio's SupportedVersion.JMIX_1_0.check: everything above 0.9.999-SNAPSHOT. */
    fun isSupportedPlatformVersion(version: String): Boolean =
        compare(version, "0.9.999-SNAPSHOT") > 0

    fun isUnstable(version: String): Boolean = UNSTABLE_VERSION_PATTERN.containsMatchIn(version)

    /** "3.0.1" -> "3.0"; throws on versions with fewer than two segments. */
    fun majorMinor(version: String): String {
        val parts = version.split('.', '-')
        require(parts.size >= 2) { "Invalid version: $version" }
        return "${parts[0]}.${parts[1]}"
    }

    /**
     * The newest patch of each major.minor line, newest line first:
     * [2.7.6, 2.8.2, 2.8.3, 3.0.0, 3.0.1] -> [3.0.1, 2.8.3, 2.7.6].
     * Input must be ascending (as returned by TemplateRepository.fetchVersions).
     */
    fun latestPatchPerMinor(ascendingVersions: List<String>): List<String> =
        ascendingVersions
            .groupBy { majorMinor(it) }
            .map { (_, patches) -> patches.last() }
            .asReversed()
}
