package dev.thunder.updateclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseManifestParserTest {
    @Test
    fun `valid Thunder and Manager manifests are parsed as separate products`() {
        val thunder = ReleaseManifestParser.parse(
            ReleaseProduct.THUNDER,
            UpdateTestFixtures.thunderManifestBytes(),
        ) as ThunderReleaseManifest
        val manager = ReleaseManifestParser.parse(
            ReleaseProduct.THUNDER_MANAGER,
            UpdateTestFixtures.managerManifestBytes("3.4.5"),
        ) as ThunderManagerReleaseManifest

        assertEquals("0.0.2", thunder.version.toString())
        assertEquals(UpdateTestFixtures.runtimeBytes.size.toLong(), thunder.runtime.size)
        assertEquals("3.4.5", manager.version.toString())
        assertTrue(manager.apk.url.toASCIIString().endsWith("ThunderManager-3.4.5.apk"))
    }

    @Test
    fun `malformed invalid and trailing JSON are rejected`() {
        listOf(
            "{".toByteArray(),
            "[]".toByteArray(),
            "{} trailing".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28),
        ).forEach { bytes ->
            assertThrows(ReleaseManifestException::class.java) {
                ReleaseManifestParser.parse(ReleaseProduct.THUNDER, bytes)
            }
        }
    }

    @Test
    fun `missing unknown and wrong-typed fields are rejected`() {
        val valid = String(UpdateTestFixtures.thunderManifestBytes())
        val variants = listOf(
            valid.replace(Regex("\\s*\"publishedAt\": \"[^\"]+\","), ""),
            String(UpdateTestFixtures.thunderManifestBytes(extraRootField = ",\n  \"channel\": \"stable\"")),
            valid.replace("\"schema\": 1", "\"schema\": \"1\""),
            valid.replace("\"size\": ${UpdateTestFixtures.runtimeBytes.size}", "\"size\": 1.0"),
            valid.replace("\"sha256\":", "\"digest\":"),
        )
        variants.forEach { json ->
            assertThrows(ReleaseManifestException::class.java) {
                ReleaseManifestParser.parse(ReleaseProduct.THUNDER, json.toByteArray())
            }
        }
    }

    @Test
    fun `prerelease downgrade-shaped and mutable URLs are rejected`() {
        val variants = listOf(
            UpdateTestFixtures.thunderManifestBytes(version = "0.0.2-rc.1"),
            UpdateTestFixtures.thunderManifestBytes(
                artifactUrl = "http://github.com/Accentaro/Thunder/releases/download/v0.0.2/runtime.js",
            ),
            UpdateTestFixtures.thunderManifestBytes(
                artifactUrl = "https://raw.githubusercontent.com/Accentaro/Thunder/main/runtime.js",
            ),
            UpdateTestFixtures.thunderManifestBytes(
                notesUrl = "https://github.com/Accentaro/Thunder/releases/latest",
            ),
        )
        variants.forEach { bytes ->
            assertThrows(ReleaseManifestException::class.java) {
                ReleaseManifestParser.parse(ReleaseProduct.THUNDER, bytes)
            }
        }
    }

    @Test
    fun `invalid digest size and oversized manifest are rejected`() {
        listOf(
            UpdateTestFixtures.thunderManifestBytes(size = 0),
            UpdateTestFixtures.thunderManifestBytes(sha256 = "A".repeat(64)),
            UpdateTestFixtures.thunderManifestBytes(sha256 = "0".repeat(63)),
        ).forEach { bytes ->
            assertThrows(ReleaseManifestException::class.java) {
                ReleaseManifestParser.parse(ReleaseProduct.THUNDER, bytes)
            }
        }
        assertThrows(ReleaseManifestException::class.java) {
            ReleaseManifestParser.parse(
                ReleaseProduct.THUNDER,
                ByteArray(UpdateLimits.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() },
            )
        }
    }
}
