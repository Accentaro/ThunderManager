package dev.thunder.packageinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageFactsTest {
    @Test
    fun artifactSetPreservesBaseAndSplitClosure() {
        val artifacts = PackageFacts.artifactSet(
            basePath = "/installed/base.apk",
            splitNames = arrayOf("config.arm64_v8a", "config.xxhdpi"),
            splitPaths = arrayOf("/installed/split_arm64.apk", "/installed/split_xxhdpi.apk"),
        )

        assertEquals(3, artifacts.size)
        assertEquals(null, artifacts.first().splitName)
        assertEquals("config.xxhdpi", artifacts.last().splitName)
    }

    @Test
    fun artifactSetRejectsIncompleteSplitMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageFacts.artifactSet(
                basePath = "/installed/base.apk",
                splitNames = arrayOf("config.arm64_v8a"),
                splitPaths = emptyArray(),
            )
        }
    }

    @Test
    fun artifactSetRejectsDuplicatePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageFacts.artifactSet(
                basePath = "/installed/base.apk",
                splitNames = arrayOf("config.xxhdpi"),
                splitPaths = arrayOf("/installed/base.apk"),
            )
        }
    }

    @Test
    fun signerDigestIsStableLowercaseSha256() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            PackageFacts.sha256("hello".toByteArray()),
        )
    }

    @Test
    fun everySupportedDiscordChannelMapsToTheSingleCloneIdentity() {
        DiscordTargetCatalog.targets.forEach { source ->
            assertEquals(
                ThunderCloneCatalog.OUTPUT_PACKAGE_NAME,
                ThunderCloneCatalog.forSource(source.packageName).outputPackageName,
            )
        }
    }

    @Test
    fun unknownSourcePackagesCannotClaimTheCloneIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            ThunderCloneCatalog.forSource("example.lookalike")
        }
    }
}
