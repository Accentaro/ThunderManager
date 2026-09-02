package dev.thunder.updateclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun `semantic precedence follows the SemVer specification`() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
            "1.0.1",
            "1.1.0",
            "2.0.0",
        ).map(SemanticVersion::parse)

        ordered.zipWithNext().forEach { (older, newer) -> assertTrue(older < newer) }
        assertEquals(0, SemanticVersion.parse("1.2.3+build.1").compareTo(SemanticVersion.parse("1.2.3+build.2")))
    }

    @Test
    fun `stable releases reject prerelease build metadata and leading zeroes`() {
        listOf("1.0.0-rc.1", "1.0.0+build", "01.0.0", "1.00.0", "1.0.00", "1.0.0-01").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                SemanticVersion.parseStableRelease(value)
            }
        }
    }

    @Test
    fun `arbitrarily large core versions compare without overflow`() {
        val older = SemanticVersion.parse("999999999999999999999999999999.0.0")
        val newer = SemanticVersion.parse("1000000000000000000000000000000.0.0")
        assertTrue(older < newer)
    }
}
