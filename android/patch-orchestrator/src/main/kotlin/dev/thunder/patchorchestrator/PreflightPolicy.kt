package dev.thunder.patchorchestrator

import dev.thunder.injection.BackendAssessment
import dev.thunder.injection.BackendCompatibility
import dev.thunder.packageinspector.CloneInstallState
import dev.thunder.packageinspector.DiscordTargetCatalog
import dev.thunder.packageinspector.InstalledDiscordTarget
import dev.thunder.packageinspector.PatchMarker
import dev.thunder.packageinspector.ThunderCloneCatalog
import java.util.Locale

enum class InstallDisposition {
    FRESH_CLONE_INSTALL,
    IN_PLACE_CLONE_UPDATE,
    CURRENT_CLONE_REFRESH,
}

enum class PreflightBlockReason {
    BACKEND_INCOMPATIBLE,
    DEGRADED_REQUIRES_OVERRIDE,
    SOURCE_IS_PATCHED,
    INVALID_SOURCE_PATCH_MARKER,
    UNTRUSTED_SOURCE_SIGNER,
    INVALID_SOURCE_PROVENANCE,
    CLONE_UNAVAILABLE,
    FOREIGN_CLONE,
    INVALID_CLONE_MARKER,
    SIGNING_IDENTITY_MISSING,
    DOWNGRADE_NOT_ALLOWED,
}

sealed interface PreflightDecision {
    data class Allowed(
        val disposition: InstallDisposition,
        val outputPackageName: String,
        val currentSignerMatchesThunder: Boolean,
    ) : PreflightDecision

    data class Blocked(val reason: PreflightBlockReason) : PreflightDecision
}

object PreflightPolicy {
    fun evaluate(
        sourceTarget: InstalledDiscordTarget,
        cloneState: CloneInstallState,
        assessment: BackendAssessment,
        sourceSetSha256: String,
        thunderCertificateSha256: String?,
        allowDegraded: Boolean,
    ): PreflightDecision {
        val provenance = evaluateProvenance(
            sourceTarget = sourceTarget,
            cloneState = cloneState,
            sourceSetSha256 = sourceSetSha256,
            thunderCertificateSha256 = thunderCertificateSha256,
        )
        if (provenance is PreflightDecision.Blocked) return provenance

        if (assessment.compatibility == BackendCompatibility.INCOMPATIBLE || assessment.blockingReasons.isNotEmpty()) {
            return PreflightDecision.Blocked(PreflightBlockReason.BACKEND_INCOMPATIBLE)
        }
        if (assessment.compatibility == BackendCompatibility.DEGRADED && !allowDegraded) {
            return PreflightDecision.Blocked(PreflightBlockReason.DEGRADED_REQUIRES_OVERRIDE)
        }
        return provenance
    }

    /**
     * Authenticates the official source and installed-clone relationship without
     * inspecting either APK with an injection backend. The coordinator uses this
     * decision to choose the immutable APK set that the backend must inspect.
     */
    fun evaluateProvenance(
        sourceTarget: InstalledDiscordTarget,
        cloneState: CloneInstallState,
        sourceSetSha256: String,
        thunderCertificateSha256: String?,
    ): PreflightDecision {
        when (sourceTarget.patchMarker) {
            PatchMarker.Absent -> Unit
            is PatchMarker.Valid -> return PreflightDecision.Blocked(PreflightBlockReason.SOURCE_IS_PATCHED)
            is PatchMarker.Invalid -> {
                return PreflightDecision.Blocked(PreflightBlockReason.INVALID_SOURCE_PATCH_MARKER)
            }
        }

        val targetSpec = DiscordTargetCatalog.forPackage(sourceTarget.packageName)
        val sourceSigners = sourceTarget.currentSignerSha256
            .map { it.lowercase(Locale.ROOT) }
            .distinct()
            .sorted()
        if (sourceSigners.size != 1 || sourceSigners.any { it !in targetSpec.trustedSignerSha256 }) {
            return PreflightDecision.Blocked(PreflightBlockReason.UNTRUSTED_SOURCE_SIGNER)
        }
        if (!SHA_256.matches(sourceSetSha256)) {
            return PreflightDecision.Blocked(PreflightBlockReason.INVALID_SOURCE_PROVENANCE)
        }

        val outputPackageName = ThunderCloneCatalog.forSource(sourceTarget.packageName).outputPackageName
        return when (cloneState) {
            CloneInstallState.NotInstalled -> PreflightDecision.Allowed(
                disposition = InstallDisposition.FRESH_CLONE_INSTALL,
                outputPackageName = outputPackageName,
                currentSignerMatchesThunder = false,
            )
            is CloneInstallState.Unavailable -> {
                PreflightDecision.Blocked(PreflightBlockReason.CLONE_UNAVAILABLE)
            }
            is CloneInstallState.Installed -> evaluateInstalledClone(
                sourceTarget = sourceTarget,
                cloneState = cloneState,
                outputPackageName = outputPackageName,
                sourceSigners = sourceSigners,
                sourceSetSha256 = sourceSetSha256,
                trustedSourceSigners = targetSpec.trustedSignerSha256,
                thunderCertificateSha256 = thunderCertificateSha256,
            )
        }
    }

    private fun evaluateInstalledClone(
        sourceTarget: InstalledDiscordTarget,
        cloneState: CloneInstallState.Installed,
        outputPackageName: String,
        sourceSigners: List<String>,
        sourceSetSha256: String,
        trustedSourceSigners: Set<String>,
        thunderCertificateSha256: String?,
    ): PreflightDecision {
        val clone = cloneState.clone
        if (clone.packageName != outputPackageName) {
            return PreflightDecision.Blocked(PreflightBlockReason.FOREIGN_CLONE)
        }

        val marker = when (val candidate = clone.patchMarker) {
            PatchMarker.Absent -> return PreflightDecision.Blocked(PreflightBlockReason.FOREIGN_CLONE)
            is PatchMarker.Invalid -> {
                return PreflightDecision.Blocked(PreflightBlockReason.INVALID_CLONE_MARKER)
            }
            is PatchMarker.Valid -> candidate
        }
        val markerSigners = marker.sourceSignerSha256
        if (marker.schemaVersion !in setOf(2, 3)
            || (marker.schemaVersion == 3 && marker.hostDexSha256?.let(SHA_256::matches) != true)
            || marker.sourcePackageName != sourceTarget.packageName
            || marker.sourceVersionCode != clone.versionCode
            || marker.outputPackageName != outputPackageName
            || markerSigners == null
            || markerSigners.size != 1
            || markerSigners.any { it !in trustedSourceSigners }
            || marker.sourceSetSha256?.let(SHA_256::matches) != true
        ) {
            return PreflightDecision.Blocked(PreflightBlockReason.INVALID_CLONE_MARKER)
        }
        if (sourceTarget.versionCode < clone.versionCode) {
            return PreflightDecision.Blocked(PreflightBlockReason.DOWNGRADE_NOT_ALLOWED)
        }
        if (thunderCertificateSha256 == null) {
            return PreflightDecision.Blocked(PreflightBlockReason.SIGNING_IDENTITY_MISSING)
        }

        val signerMatches = clone.currentSignerSha256.size == 1
            && clone.currentSignerSha256.single().equals(thunderCertificateSha256, ignoreCase = true)
        if (!signerMatches) {
            return PreflightDecision.Blocked(PreflightBlockReason.FOREIGN_CLONE)
        }

        val sourceBitsAreCurrent = sourceTarget.versionCode == clone.versionCode
            && marker.sourceSetSha256 == sourceSetSha256
            && markerSigners == sourceSigners
        return PreflightDecision.Allowed(
            disposition = if (sourceBitsAreCurrent) {
                InstallDisposition.CURRENT_CLONE_REFRESH
            } else {
                InstallDisposition.IN_PLACE_CLONE_UPDATE
            },
            outputPackageName = outputPackageName,
            currentSignerMatchesThunder = true,
        )
    }

    private val SHA_256 = Regex("^[0-9a-f]{64}$")
}
