package dev.thunder.patchorchestrator

import dev.thunder.injection.BackendAssessment
import dev.thunder.injection.BackendCompatibility
import dev.thunder.packageinspector.CloneInstallState
import dev.thunder.packageinspector.DiscordChannel
import dev.thunder.packageinspector.DiscordTargetCatalog
import dev.thunder.packageinspector.InstalledDiscordTarget
import dev.thunder.packageinspector.InstalledThunderClone
import dev.thunder.packageinspector.InvalidPatchMarkerReason
import dev.thunder.packageinspector.PatchMarker
import dev.thunder.packageinspector.ThunderCloneCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class PreflightPolicyTest {
    @Test
    fun absentCloneIsASeparateFreshInstall() {
        val result = evaluate(cloneState = CloneInstallState.NotInstalled)

        val allowed = result as PreflightDecision.Allowed
        assertEquals(InstallDisposition.FRESH_CLONE_INSTALL, allowed.disposition)
        assertEquals(ThunderCloneCatalog.OUTPUT_PACKAGE_NAME, allowed.outputPackageName)
    }

    @Test
    fun matchingCloneAtTheSameSourceBitsIsCurrent() {
        val result = evaluate(cloneState = installedClone())

        assertEquals(
            InstallDisposition.CURRENT_CLONE_REFRESH,
            (result as PreflightDecision.Allowed).disposition,
        )
    }

    @Test
    fun schema3CloneWithHostDexIdentityIsCurrent() {
        val result = evaluate(
            cloneState = installedClone(marker = validMarker(schemaVersion = 3)),
        )

        assertEquals(
            InstallDisposition.CURRENT_CLONE_REFRESH,
            (result as PreflightDecision.Allowed).disposition,
        )
    }

    @Test
    fun schema3CloneWithoutHostDexIdentityFailsClosed() {
        val result = evaluate(
            cloneState = installedClone(marker = validMarker(schemaVersion = 3, hostDexSha256 = null)),
        )

        assertBlocked(PreflightBlockReason.INVALID_CLONE_MARKER, result)
    }

    @Test
    fun newerOfficialSourceCanUpdateCloneInPlace() {
        val result = evaluate(
            sourceTarget = source(versionCode = 2),
            cloneState = installedClone(versionCode = 1),
        )

        assertEquals(
            InstallDisposition.IN_PLACE_CLONE_UPDATE,
            (result as PreflightDecision.Allowed).disposition,
        )
    }

    @Test
    fun sameVersionWithDifferentSourceBitsRefreshesInPlace() {
        val result = evaluate(
            cloneState = installedClone(sourceSetSha256 = OLD_SOURCE_SET),
        )

        assertEquals(
            InstallDisposition.IN_PLACE_CLONE_UPDATE,
            (result as PreflightDecision.Allowed).disposition,
        )
    }

    @Test
    fun cloneWithoutThunderMarkerIsForeign() {
        val result = evaluate(
            cloneState = installedClone(marker = PatchMarker.Absent),
        )

        assertBlocked(PreflightBlockReason.FOREIGN_CLONE, result)
    }

    @Test
    fun malformedCloneMarkerFailsClosed() {
        val result = evaluate(
            cloneState = installedClone(
                marker = PatchMarker.Invalid(InvalidPatchMarkerReason.MALFORMED),
            ),
        )

        assertBlocked(PreflightBlockReason.INVALID_CLONE_MARKER, result)
    }

    @Test
    fun legacyMarkerCannotAuthorizeACloneUpdate() {
        val result = evaluate(
            cloneState = installedClone(
                marker = validMarker(schemaVersion = 1, sourcePackageName = null),
            ),
        )

        assertBlocked(PreflightBlockReason.INVALID_CLONE_MARKER, result)
    }

    @Test
    fun existingCloneNeverAcceptsANewSigningIdentity() {
        val result = evaluate(
            cloneState = installedClone(),
            thunderCertificateSha256 = null,
        )

        assertBlocked(PreflightBlockReason.SIGNING_IDENTITY_MISSING, result)
    }

    @Test
    fun mismatchedCloneSignerIsForeign() {
        val result = evaluate(
            cloneState = installedClone(signer = "c".repeat(64)),
        )

        assertBlocked(PreflightBlockReason.FOREIGN_CLONE, result)
    }

    @Test
    fun downgradeIsBlockedEvenForAnAuthenticClone() {
        val result = evaluate(
            sourceTarget = source(versionCode = 1),
            cloneState = installedClone(versionCode = 2),
        )

        assertBlocked(PreflightBlockReason.DOWNGRADE_NOT_ALLOWED, result)
    }

    @Test
    fun packageNameLookalikeWithUnknownSignerIsRejected() {
        val result = evaluate(
            sourceTarget = source(signer = "d".repeat(64)),
            cloneState = CloneInstallState.NotInstalled,
        )

        assertBlocked(PreflightBlockReason.UNTRUSTED_SOURCE_SIGNER, result)
    }

    @Test
    fun degradedBackendRequiresAnExplicitOverride() {
        val result = evaluate(
            cloneState = CloneInstallState.NotInstalled,
            assessment = assessment(BackendCompatibility.DEGRADED),
        )

        assertBlocked(PreflightBlockReason.DEGRADED_REQUIRES_OVERRIDE, result)
    }

    private fun evaluate(
        sourceTarget: InstalledDiscordTarget = source(),
        cloneState: CloneInstallState,
        assessment: BackendAssessment = assessment(),
        sourceSetSha256: String = SOURCE_SET,
        thunderCertificateSha256: String? = THUNDER_SIGNER,
        allowDegraded: Boolean = false,
    ): PreflightDecision = PreflightPolicy.evaluate(
        sourceTarget = sourceTarget,
        cloneState = cloneState,
        assessment = assessment,
        sourceSetSha256 = sourceSetSha256,
        thunderCertificateSha256 = thunderCertificateSha256,
        allowDegraded = allowDegraded,
    )

    private fun source(
        versionCode: Long = 1,
        signer: String = OFFICIAL_SIGNER,
    ) = InstalledDiscordTarget(
        label = "Discord",
        packageName = "com.discord",
        channel = DiscordChannel.STABLE,
        versionName = "1.0",
        versionCode = versionCode,
        artifacts = emptyList(),
        currentSignerSha256 = listOf(signer),
        patchMarker = PatchMarker.Absent,
    )

    private fun installedClone(
        versionCode: Long = 1,
        signer: String = THUNDER_SIGNER,
        sourceSetSha256: String = SOURCE_SET,
        marker: PatchMarker = validMarker(
            sourceVersionCode = versionCode,
            sourceSetSha256 = sourceSetSha256,
        ),
    ) = CloneInstallState.Installed(
        InstalledThunderClone(
            label = "Thunder",
            packageName = ThunderCloneCatalog.OUTPUT_PACKAGE_NAME,
            versionName = "1.0",
            versionCode = versionCode,
            artifacts = emptyList(),
            currentSignerSha256 = listOf(signer),
            patchMarker = marker,
        ),
    )

    private fun validMarker(
        schemaVersion: Int = 2,
        sourcePackageName: String? = "com.discord",
        sourceVersionCode: Long? = 1,
        sourceSetSha256: String? = SOURCE_SET,
        hostDexSha256: String? = if (schemaVersion == 3) "c".repeat(64) else null,
    ) = PatchMarker.Valid(
        schemaVersion = schemaVersion,
        bootstrapVersion = "1.0.0",
        sourcePackageName = sourcePackageName,
        sourceVersionCode = sourceVersionCode,
        sourceSignerSha256 = listOf(OFFICIAL_SIGNER),
        sourceSetSha256 = sourceSetSha256,
        outputPackageName = ThunderCloneCatalog.OUTPUT_PACKAGE_NAME,
        hostDexSha256 = hostDexSha256,
    )

    private fun assessment(compatibility: BackendCompatibility = BackendCompatibility.COMPATIBLE) = BackendAssessment(
        backendId = "test",
        backendVersion = "1.0.0",
        compatibility = compatibility,
        evidence = emptyList(),
        blockingReasons = emptyList(),
    )

    private fun assertBlocked(expected: PreflightBlockReason, actual: PreflightDecision) {
        assertEquals(expected, (actual as PreflightDecision.Blocked).reason)
    }

    private companion object {
        val OFFICIAL_SIGNER = DiscordTargetCatalog.forPackage("com.discord").trustedSignerSha256.single()
        const val SOURCE_SET = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OLD_SOURCE_SET = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val THUNDER_SIGNER = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
