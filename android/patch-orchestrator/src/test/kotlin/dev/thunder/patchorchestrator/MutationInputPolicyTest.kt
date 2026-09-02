package dev.thunder.patchorchestrator

import dev.thunder.packageinspector.CloneInstallState
import dev.thunder.packageinspector.DiscordChannel
import dev.thunder.packageinspector.DiscordTargetCatalog
import dev.thunder.packageinspector.InstalledDiscordTarget
import dev.thunder.packageinspector.InstalledThunderClone
import dev.thunder.packageinspector.PackageArtifact
import dev.thunder.packageinspector.PatchMarker
import dev.thunder.packageinspector.ThunderCloneCatalog
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MutationInputPolicyTest {
    @Test
    fun schema3SteadyRefreshSelectsAuthenticatedInstalledClone() {
        val source = source(versionCode = 2)
        val clone = clone(versionCode = 2, sourceVersionCode = 2, sourceSetSha256 = SOURCE_SET)
        val allowed = provenance(source, clone, SOURCE_SET)

        val selected = MutationInputPolicy.select(
            source,
            CloneInstallState.Installed(clone),
            allowed,
        )

        assertSame(clone, (selected as MutationInputTarget.InstalledClone).clone)
    }

    @Test
    fun newerOfficialSourceSelectsStockInputForInPlaceUpdate() {
        val source = source(versionCode = 2)
        val clone = clone(versionCode = 1, sourceVersionCode = 1, sourceSetSha256 = OLD_SOURCE_SET)
        val allowed = provenance(source, clone, SOURCE_SET)

        val selected = MutationInputPolicy.select(
            source,
            CloneInstallState.Installed(clone),
            allowed,
        )

        assertSame(source, (selected as MutationInputTarget.OfficialSource).target)
    }

    @Test
    fun sameVersionOfficialBitChangeStillSelectsStockInput() {
        val source = source(versionCode = 2)
        val clone = clone(versionCode = 2, sourceVersionCode = 2, sourceSetSha256 = OLD_SOURCE_SET)
        val allowed = provenance(source, clone, SOURCE_SET)

        val selected = MutationInputPolicy.select(
            source,
            CloneInstallState.Installed(clone),
            allowed,
        )

        assertSame(source, (selected as MutationInputTarget.OfficialSource).target)
    }

    @Test
    fun freshInstallSelectsStockInput() {
        val source = source(versionCode = 2)
        val decision = PreflightPolicy.evaluateProvenance(
            sourceTarget = source,
            cloneState = CloneInstallState.NotInstalled,
            sourceSetSha256 = SOURCE_SET,
            thunderCertificateSha256 = null,
        ) as PreflightDecision.Allowed

        val selected = MutationInputPolicy.select(source, CloneInstallState.NotInstalled, decision)

        assertSame(source, (selected as MutationInputTarget.OfficialSource).target)
    }

    @Test
    fun refreshRejectsACloneWithoutTheOfficialFullSplitClosure() {
        val source = source(versionCode = 2)
        val clone = clone(
            versionCode = 2,
            sourceVersionCode = 2,
            sourceSetSha256 = SOURCE_SET,
            artifacts = ARTIFACTS.dropLast(1),
        )
        val allowed = provenance(source, clone, SOURCE_SET)

        assertThrows(IllegalArgumentException::class.java) {
            MutationInputPolicy.select(source, CloneInstallState.Installed(clone), allowed)
        }
    }

    private fun provenance(
        source: InstalledDiscordTarget,
        clone: InstalledThunderClone,
        sourceSetSha256: String,
    ): PreflightDecision.Allowed = PreflightPolicy.evaluateProvenance(
        sourceTarget = source,
        cloneState = CloneInstallState.Installed(clone),
        sourceSetSha256 = sourceSetSha256,
        thunderCertificateSha256 = THUNDER_SIGNER,
    ) as PreflightDecision.Allowed

    private fun source(versionCode: Long) = InstalledDiscordTarget(
        label = "Discord",
        packageName = SOURCE_PACKAGE,
        channel = DiscordChannel.STABLE,
        versionName = versionCode.toString(),
        versionCode = versionCode,
        artifacts = ARTIFACTS,
        currentSignerSha256 = listOf(OFFICIAL_SIGNER),
        patchMarker = PatchMarker.Absent,
    )

    private fun clone(
        versionCode: Long,
        sourceVersionCode: Long,
        sourceSetSha256: String,
        artifacts: List<PackageArtifact> = ARTIFACTS.map { artifact ->
            artifact.copy(sourcePath = "clone-" + artifact.sourcePath)
        },
    ) = InstalledThunderClone(
        label = "Thunder",
        packageName = ThunderCloneCatalog.OUTPUT_PACKAGE_NAME,
        versionName = versionCode.toString(),
        versionCode = versionCode,
        artifacts = artifacts,
        currentSignerSha256 = listOf(THUNDER_SIGNER),
        patchMarker = PatchMarker.Valid(
            schemaVersion = 3,
            bootstrapVersion = "0.6.0",
            sourcePackageName = SOURCE_PACKAGE,
            sourceVersionCode = sourceVersionCode,
            sourceSignerSha256 = listOf(OFFICIAL_SIGNER),
            sourceSetSha256 = sourceSetSha256,
            outputPackageName = ThunderCloneCatalog.OUTPUT_PACKAGE_NAME,
            hostDexSha256 = HOST_DEX,
        ),
    )

    private companion object {
        const val SOURCE_PACKAGE = "com.discord"
        val OFFICIAL_SIGNER = DiscordTargetCatalog.forPackage(SOURCE_PACKAGE).trustedSignerSha256.single()
        const val THUNDER_SIGNER = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val SOURCE_SET = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OLD_SOURCE_SET = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val HOST_DEX = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        val ARTIFACTS = listOf(
            PackageArtifact(null, "official-base.apk"),
            PackageArtifact("config.arm64_v8a", "official-arm64.apk"),
            PackageArtifact("config.en", "official-en.apk"),
            PackageArtifact("config.xxhdpi", "official-xxhdpi.apk"),
        )
    }
}
