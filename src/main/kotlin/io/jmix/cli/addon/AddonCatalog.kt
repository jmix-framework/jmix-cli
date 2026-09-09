package io.jmix.cli.addon

import com.google.gson.JsonParser
import com.vdurmont.semver4j.Semver
import io.jmix.cli.util.PlatformVersions
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.apache.maven.artifact.versioning.VersionRange
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class AddonDependency(
    val group: String,
    val name: String,
    val configuration: String = "implementation",
    val versionRange: String? = null,
) {
    val coordinates: String get() = "$group:$name"

    fun supports(version: String, profile: AddonProjectProfile): Boolean {
        if (name.endsWith("-flowui-starter") && !profile.flowUi) return false
        if (name != "swagger-ui-starter" &&
            (name.endsWith("-ui-starter") || Regex(".+-widgets(-compiled)?").matches(name)) && !profile.classicUi
        ) return false
        return versionRange == null || VersionRange.createFromVersionSpec(versionRange)
            .containsVersion(DefaultArtifactVersion(version))
    }
}

data class AddonCompatibility(val platformRequirement: String, val artifactVersions: List<String>)

data class Addon(
    val id: String,
    val name: String,
    val about: String,
    val description: String,
    val commercial: Boolean,
    val dependencies: List<AddonDependency>,
    val compatibility: List<AddonCompatibility>,
    val weight: Int = Int.MAX_VALUE,
    val category: String = "Add-on",
    val tags: List<String> = emptyList(),
    val vendor: String = "",
)

data class ResolvedAddon(val addon: Addon, val version: String, val dependencies: List<AddonDependency>, val included: Boolean) {
    val id: String get() = addon.id
}

/** Match translation artifacts from the compatible catalog, preferring an exact locale over its language. */
internal fun translationAddonIds(available: List<ResolvedAddon>, localeCodes: List<String>): Set<String> {
    val translations = available.filter { it.addon.category == "Translation" }.flatMap { addon ->
        addon.dependencies.filter { it.group == "io.jmix.translations" && it.name.startsWith("jmix-translations-") }
            .map { it.name.removePrefix("jmix-translations-").lowercase() to addon.id }
    }.toMap()
    return localeCodes.mapNotNullTo(linkedSetOf()) {
        val code = it.trim().replace('_', '-').lowercase()
        translations[code] ?: translations[code.substringBefore('-')]
    }
}

/** The actual template dependencies determine its UI and included add-ons. */
data class AddonProjectProfile(val buildFile: String, val coordinates: Set<String>) {
    val flowUi: Boolean get() = coordinates.any { it.startsWith("io.jmix.flowui:") }
    val classicUi: Boolean get() = coordinates.any { it.startsWith("io.jmix.ui:") }

    companion object {
        fun from(templateRoot: Path): AddonProjectProfile? = Files.walk(templateRoot).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".gradle") }
                .sorted()
                .map { file ->
                    val coordinates = dependencyCoordinates(Files.readString(file))
                    AddonProjectProfile(templateRoot.relativize(file).joinToString("/"), coordinates)
                }
                .filter { "io.jmix.core:jmix-core-starter" in it.coordinates }
                .findFirst().orElse(null)
        }

        fun dependencyCoordinates(script: String): Set<String> =
            Regex("[\"']([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)(?::[^\"']*)?[\"']")
                .findAll(script).mapTo(linkedSetOf()) { it.groupValues[1] }
    }
}

class AddonCatalog(val addons: List<Addon>) {
    fun available(version: String, profile: AddonProjectProfile): List<ResolvedAddon> = addons
        .filter { !it.commercial && installable(it) }
        .mapNotNull { resolve(it, version, profile) }
        .sortedWith(compareBy<ResolvedAddon> { if (it.included) 0 else if (it.addon.category == "Translation") 2 else 1 }
            .thenByDescending { it.addon.weight }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.addon.name }
            .thenBy { it.id })

    fun select(ids: List<String>, version: String, profile: AddonProjectProfile): List<ResolvedAddon> {
        val available = available(version, profile).associateBy { it.id }
        return ids.distinct().map { id ->
            val addon = addons.find { it.id == id }
                ?: throw IOException("Unknown add-on '$id'. Use an ID from the add-on selection list.")
            if (addon.commercial) throw IOException("Add-on '$id' is commercial. This CLI currently installs free add-ons only.")
            if (!installable(addon)) throw IOException("Add-on '$id' has no supported runtime dependency metadata in the Studio catalog. " +
                "Follow the vendor's installation instructions.")
            available[id] ?: throw IOException("Add-on '$id' is not compatible with Jmix $version and this template.")
        }
    }

    private fun installable(addon: Addon): Boolean = addon.id != "sneferu" &&
        addon.dependencies.none { it.coordinates == "io.jmix.gradle:jmix-gradle-plugin" }

    private fun resolve(addon: Addon, version: String, profile: AddonProjectProfile): ResolvedAddon? {
        val cleanVersion = Regex("^\\d+\\.\\d+(?:\\.\\d+)?").find(version)?.value ?: return null
        val semver = Semver(cleanVersion, Semver.SemverType.NPM)
        val versions = addon.compatibility.firstOrNull { semver.satisfies(it.platformRequirement) }
            ?.artifactVersions ?: return null
        val selectedVersion = if (version in versions) version else {
            val preferred = if (!PlatformVersions.isUnstable(version)) {
                versions.filterNot(PlatformVersions::isUnstable).ifEmpty { versions }
            } else versions
            preferred.lastOrNull() ?: return null
        }
        val dependencies = addon.dependencies.filter { it.supports(version, profile) }
        if (dependencies.isEmpty()) return null
        return ResolvedAddon(addon, selectedVersion, dependencies, dependencies.all { it.coordinates in profile.coordinates })
    }

    companion object {
        fun parse(json: String): AddonCatalog = try {
            val nodes = JsonParser.parseString(json).asJsonObject.getAsJsonArray("appComponents")
                ?: throw IllegalArgumentException("Missing appComponents array")
            val addons = nodes.map { node ->
                val obj = node.asJsonObject
                fun string(name: String) = obj.get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val id = string("id").also { require(IDENTIFIER.matches(it)) { "Invalid add-on ID" } }
                val commercial = obj.get("commercial")
                require(commercial != null && commercial.isJsonPrimitive && commercial.asJsonPrimitive.isBoolean) {
                    "Missing commercial flag for $id"
                }
                val dependencies = obj.getAsJsonArray("dependencies").map { dependency ->
                    val d = dependency.asJsonObject
                    val group = d.get("group").asString
                    val name = d.get("name").asString
                    val configuration = d.get("configuration")?.takeUnless { it.isJsonNull }?.asString ?: "implementation"
                    require(IDENTIFIER.matches(group) && IDENTIFIER.matches(name)) { "Invalid dependency for $id" }
                    require(Regex("[A-Za-z][A-Za-z0-9]*").matches(configuration)) { "Invalid configuration for $id" }
                    val range = d.get("versionRange")?.takeUnless { it.isJsonNull }?.asString
                    range?.let { VersionRange.createFromVersionSpec(it) }
                    AddonDependency(group, name, configuration, range)
                }
                val compatibility = obj.getAsJsonArray("compatibilityList").map { compatibility ->
                    val c = compatibility.asJsonObject
                    val requirement = c.get("platformRequirement").asString.replace("=>", ">=")
                    Semver("3.0.0", Semver.SemverType.NPM).satisfies(requirement)
                    val versions = c.getAsJsonArray("artifactVersions").map { it.asString }
                    require(versions.all { VERSION.matches(it) }) { "Invalid artifact version for $id" }
                    AddonCompatibility(requirement, versions)
                }
                val name = plainText(string("name"))
                require(name.isNotBlank()) { "Missing name for $id" }
                Addon(id, name, plainText(string("about")), plainText(string("description")), commercial.asBoolean,
                    dependencies, compatibility,
                    weight = obj.get("weight")?.takeUnless { it.isJsonNull }?.asInt ?: Int.MAX_VALUE,
                    category = plainText(string("category")),
                    tags = obj.get("tags")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { plainText(it.asString) }.orEmpty(),
                    vendor = plainText(string("vendor")))
            }
            require(addons.isNotEmpty() && addons.map { it.id }.distinct().size == addons.size) { "Empty or duplicate add-on catalog" }
            AddonCatalog(addons)
        } catch (e: Exception) {
            throw IOException("Invalid add-on catalog: ${e.message}", e)
        }

        fun plainText(value: String): String = value.replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        private val IDENTIFIER = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")
        private val VERSION = Regex("[0-9][0-9A-Za-z._+-]*")
    }
}
