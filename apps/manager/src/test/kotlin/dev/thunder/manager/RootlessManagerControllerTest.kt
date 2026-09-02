package dev.thunder.manager

import dev.thunder.manager.ui.ThunderCloneUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootlessManagerControllerTest {
    private val clone = ThunderCloneUi(
        versionName = "205.15",
        versionCode = 344205,
        sourcePackageName = "com.discord",
    )

    @Test
    fun freshlyTrustedMatchingCloneCanOpen() {
        assertEquals(
            VerifiedOpenDecision.Allow(clone),
            resolveVerifiedOpen(CloneUiInspection.Trusted(clone), "com.discord"),
        )
    }

    @Test
    fun missingCloneCannotOpenFromStaleUiState() {
        assertTrue(
            resolveVerifiedOpen(CloneUiInspection.Absent, "com.discord") is
                VerifiedOpenDecision.Block,
        )
    }

    @Test
    fun newlyUntrustedCloneCannotOpenFromStaleUiState() {
        val decision = resolveVerifiedOpen(
            CloneUiInspection.Blocked("Signer changed"),
            "com.discord",
        )
        assertEquals(VerifiedOpenDecision.Block("Signer changed"), decision)
    }

    @Test
    fun cloneWhoseSourceChangedCannotOpen() {
        assertTrue(
            resolveVerifiedOpen(CloneUiInspection.Trusted(clone), "com.discord.beta") is
                VerifiedOpenDecision.Block,
        )
    }
}
