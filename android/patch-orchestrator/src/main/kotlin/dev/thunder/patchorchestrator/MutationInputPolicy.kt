package dev.thunder.patchorchestrator

import dev.thunder.packageinspector.CloneInstallState
import dev.thunder.packageinspector.InstalledDiscordTarget
import dev.thunder.packageinspector.InstalledThunderClone
import dev.thunder.packageinspector.ThunderCloneCatalog

internal sealed interface MutationInputTarget {
    data class OfficialSource(val target: InstalledDiscordTarget) : MutationInputTarget
    data class InstalledClone(val clone: InstalledThunderClone) : MutationInputTarget
}

/**
 * Keeps source upgrades stock-backed while allowing a same-source Refresh to
 * preserve the already-woven host DEX from an authenticated installed clone.
 */
internal object MutationInputPolicy {
    fun select(
        sourceTarget: InstalledDiscordTarget,
        cloneState: CloneInstallState,
        preflight: PreflightDecision.Allowed,
    ): MutationInputTarget {
        val expectedOutputPackage = ThunderCloneCatalog
            .forSource(sourceTarget.packageName)
            .outputPackageName
        require(preflight.outputPackageName == expectedOutputPackage) {
            "Preflight selected an unexpected output package"
        }

        return when (preflight.disposition) {
            InstallDisposition.FRESH_CLONE_INSTALL,
            InstallDisposition.IN_PLACE_CLONE_UPDATE,
            -> MutationInputTarget.OfficialSource(sourceTarget)

            InstallDisposition.CURRENT_CLONE_REFRESH -> {
                val clone = (cloneState as? CloneInstallState.Installed)?.clone
                    ?: error("Current-clone Refresh requires an installed clone")
                require(clone.packageName == preflight.outputPackageName) {
                    "Current-clone Refresh selected a foreign package"
                }
                require(clone.versionCode == sourceTarget.versionCode) {
                    "Current-clone Refresh cannot change the host version"
                }
                val sourceSplits = sourceTarget.artifacts.map { it.splitName }
                val cloneSplits = clone.artifacts.map { it.splitName }
                require(
                    sourceSplits.count { it == null } == 1 &&
                        cloneSplits.count { it == null } == 1 &&
                        sourceSplits.size == sourceSplits.toSet().size &&
                        cloneSplits.size == cloneSplits.toSet().size &&
                        cloneSplits.toSet() == sourceSplits.toSet(),
                ) {
                    "Current-clone Refresh requires the authenticated full split closure"
                }
                MutationInputTarget.InstalledClone(clone)
            }
        }
    }
}
