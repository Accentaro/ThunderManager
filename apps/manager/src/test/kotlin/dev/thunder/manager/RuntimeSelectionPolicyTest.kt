package dev.thunder.manager

import dev.thunder.updateclient.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeSelectionPolicyTest {
    private val bundled = SemanticVersion.parseStableRelease("1.0.0")

    @Test
    fun `newest verified runtime is preferred over bundled runtime`() {
        assertEquals(
            RuntimeSource.VERIFIED,
            runtimeSourceFor(
                bundled,
                SemanticVersion.parseStableRelease("1.1.0"),
                SemanticVersion.parseStableRelease("1.0.0"),
            ),
        )
    }

    @Test
    fun `bundled runtime remains source when store is absent older or equal`() {
        listOf(null, "0.9.0", "1.0.0").forEach { stored ->
            assertEquals(
                RuntimeSource.BUNDLED,
                runtimeSourceFor(
                    bundled,
                    stored?.let(SemanticVersion::parseStableRelease),
                    installedVersion = null,
                ),
            )
        }
    }

    @Test
    fun `refresh refuses to downgrade authenticated installed runtime`() {
        assertThrows(IllegalArgumentException::class.java) {
            runtimeSourceFor(
                bundled,
                SemanticVersion.parseStableRelease("1.1.0"),
                SemanticVersion.parseStableRelease("1.2.0"),
            )
        }
    }
}
