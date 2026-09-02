package dev.thunder.packageinspector

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

interface PackageInventory {
    fun scan(): InventorySnapshot
    fun inspectClone(): CloneInstallState
}

class AndroidPackageInventory(context: Context) : PackageInventory {
    private val packageManager = context.applicationContext.packageManager

    override fun scan(): InventorySnapshot {
        val targets = mutableListOf<InstalledDiscordTarget>()
        val failures = mutableListOf<InventoryFailure>()

        DiscordTargetCatalog.targets.forEach { spec ->
            try {
                targets += inspect(spec)
            } catch (_: PackageManager.NameNotFoundException) {
                failures += InventoryFailure(spec.packageName, spec.channel, InventoryFailureReason.NOT_VISIBLE)
            } catch (_: SecurityException) {
                failures += InventoryFailure(spec.packageName, spec.channel, InventoryFailureReason.SECURITY_RESTRICTED)
            } catch (_: IllegalArgumentException) {
                failures += InventoryFailure(spec.packageName, spec.channel, InventoryFailureReason.INVALID_PACKAGE_METADATA)
            }
        }

        return InventorySnapshot(
            targets = targets.sortedBy { it.channel.ordinal },
            failures = failures,
            clone = inspectClone(),
        )
    }

    override fun inspectClone(): CloneInstallState = try {
        CloneInstallState.Installed(inspectClonePackage(ThunderCloneCatalog.OUTPUT_PACKAGE_NAME))
    } catch (_: PackageManager.NameNotFoundException) {
        CloneInstallState.NotInstalled
    } catch (_: SecurityException) {
        CloneInstallState.Unavailable(
            ThunderCloneCatalog.OUTPUT_PACKAGE_NAME,
            CloneInventoryFailureReason.SECURITY_RESTRICTED,
        )
    } catch (_: IllegalArgumentException) {
        CloneInstallState.Unavailable(
            ThunderCloneCatalog.OUTPUT_PACKAGE_NAME,
            CloneInventoryFailureReason.INVALID_PACKAGE_METADATA,
        )
    }

    private fun inspect(spec: DiscordTargetSpec): InstalledDiscordTarget {
        val packageInfo = getPackageInfo(spec.packageName)
        val applicationInfo = requireNotNull(packageInfo.applicationInfo) {
            "PackageManager returned no ApplicationInfo"
        }
        val splitNames = packageInfo.splitNames ?: emptyArray()
        val splitPaths = applicationInfo.splitSourceDirs ?: emptyArray()
        val artifacts = PackageFacts.artifactSet(applicationInfo.sourceDir, splitNames, splitPaths)
        val signatures = requireNotNull(packageInfo.signingInfo) {
            "PackageManager returned no SigningInfo"
        }.apkContentsSigners
        require(signatures.isNotEmpty()) { "PackageManager returned no current signer" }

        return InstalledDiscordTarget(
            label = packageManager.getApplicationLabel(applicationInfo).toString(),
            packageName = packageInfo.packageName,
            channel = spec.channel,
            versionName = packageInfo.versionName.orEmpty().ifBlank { "Unknown" },
            versionCode = packageInfo.longVersionCode,
            artifacts = artifacts,
            currentSignerSha256 = signatures.map { PackageFacts.sha256(it.toByteArray()) }.sorted(),
            patchMarker = PatchMarkerReader.read(applicationInfo.sourceDir, packageInfo.packageName),
        )
    }

    private fun inspectClonePackage(packageName: String): InstalledThunderClone {
        val packageInfo = getPackageInfo(packageName)
        val applicationInfo = requireNotNull(packageInfo.applicationInfo) {
            "PackageManager returned no ApplicationInfo"
        }
        val artifacts = PackageFacts.artifactSet(
            applicationInfo.sourceDir,
            packageInfo.splitNames ?: emptyArray(),
            applicationInfo.splitSourceDirs ?: emptyArray(),
        )
        val signatures = requireNotNull(packageInfo.signingInfo) {
            "PackageManager returned no SigningInfo"
        }.apkContentsSigners
        require(signatures.isNotEmpty()) { "PackageManager returned no current signer" }

        return InstalledThunderClone(
            label = packageManager.getApplicationLabel(applicationInfo).toString(),
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName.orEmpty().ifBlank { "Unknown" },
            versionCode = packageInfo.longVersionCode,
            artifacts = artifacts,
            currentSignerSha256 = signatures.map { PackageFacts.sha256(it.toByteArray()) }.sorted(),
            patchMarker = PatchMarkerReader.read(applicationInfo.sourceDir, packageInfo.packageName),
        )
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfo(packageName: String): PackageInfo {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
        } else {
            packageManager.getPackageInfo(packageName, flags.toInt())
        }
    }
}
