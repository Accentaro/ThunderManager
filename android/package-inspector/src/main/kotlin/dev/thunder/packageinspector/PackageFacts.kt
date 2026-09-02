package dev.thunder.packageinspector

import java.security.MessageDigest

data class PackageArtifact(
    val splitName: String?,
    val sourcePath: String,
) {
    val isBase: Boolean get() = splitName == null
}

data class InstalledDiscordTarget(
    val label: String,
    val packageName: String,
    val channel: DiscordChannel,
    val versionName: String,
    val versionCode: Long,
    val artifacts: List<PackageArtifact>,
    val currentSignerSha256: List<String>,
    val patchMarker: PatchMarker,
)

data class InstalledThunderClone(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val artifacts: List<PackageArtifact>,
    val currentSignerSha256: List<String>,
    val patchMarker: PatchMarker,
)

sealed interface PatchMarker {
    data object Absent : PatchMarker

    data class Valid(
        val schemaVersion: Int,
        val bootstrapVersion: String,
        val sourcePackageName: String?,
        val sourceVersionCode: Long?,
        val sourceSignerSha256: List<String>?,
        val sourceSetSha256: String?,
        val outputPackageName: String,
        val hostDexSha256: String? = null,
        val runtimeVersion: String? = null,
    ) : PatchMarker

    data class Invalid(val reason: InvalidPatchMarkerReason) : PatchMarker
}

enum class InvalidPatchMarkerReason {
    TOO_LARGE,
    MALFORMED,
    UNSUPPORTED_SCHEMA,
    WRONG_PLATFORM,
    PACKAGE_MISMATCH,
    SOURCE_PACKAGE_MISMATCH,
    UNREADABLE,
}

enum class CloneInventoryFailureReason {
    SECURITY_RESTRICTED,
    INVALID_PACKAGE_METADATA,
}

sealed interface CloneInstallState {
    data object NotInstalled : CloneInstallState

    data class Installed(val clone: InstalledThunderClone) : CloneInstallState

    data class Unavailable(
        val outputPackageName: String,
        val reason: CloneInventoryFailureReason,
    ) : CloneInstallState
}

enum class InventoryFailureReason {
    NOT_VISIBLE,
    SECURITY_RESTRICTED,
    INVALID_PACKAGE_METADATA,
}

data class InventoryFailure(
    val packageName: String,
    val channel: DiscordChannel,
    val reason: InventoryFailureReason,
)

data class InventorySnapshot(
    val targets: List<InstalledDiscordTarget>,
    val failures: List<InventoryFailure>,
    val clone: CloneInstallState = CloneInstallState.NotInstalled,
)

internal object PackageFacts {
    fun artifactSet(
        basePath: String,
        splitNames: Array<String>,
        splitPaths: Array<String>,
    ): List<PackageArtifact> {
        require(basePath.isNotBlank()) { "The base APK path is missing" }
        require(splitNames.size == splitPaths.size) { "Split names and paths differ in length" }

        val artifacts = buildList {
            add(PackageArtifact(splitName = null, sourcePath = basePath))
            splitNames.indices.forEach { index ->
                val name = splitNames[index]
                val path = splitPaths[index]
                require(name.isNotBlank()) { "A split name is blank" }
                require(path.isNotBlank()) { "A split APK path is blank" }
                add(PackageArtifact(splitName = name, sourcePath = path))
            }
        }

        require(artifacts.map { it.sourcePath }.toSet().size == artifacts.size) {
            "The installed APK set contains duplicate paths"
        }
        require(splitNames.toSet().size == splitNames.size) {
            "The installed APK set contains duplicate split names"
        }

        return artifacts
    }

    fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
