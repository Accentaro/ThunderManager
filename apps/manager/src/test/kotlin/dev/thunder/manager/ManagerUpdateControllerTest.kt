package dev.thunder.manager

import dev.thunder.updateclient.ManagerUpdateCandidate
import dev.thunder.updateclient.ManagerUpdateDownloadState
import dev.thunder.updateclient.ReleaseArtifact
import dev.thunder.updateclient.ReleaseCheckSource
import dev.thunder.updateclient.SemanticVersion
import dev.thunder.updateclient.ThunderManagerReleaseManifest
import dev.thunder.updateclient.ThunderReleaseManifest
import dev.thunder.updateclient.VerifiedRuntimeArtifact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant

class ManagerUpdateControllerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `Thunder and Manager availability are compared independently`() = runBlocking {
        val gateway = FakeGateway(temporary.root).apply {
            thunderCheck = ProductReleaseCheck(thunderManifest("1.1.0"), ReleaseCheckSource.NETWORK)
            managerCheck = ProductReleaseCheck(managerManifest("2.0.0"), ReleaseCheckSource.CACHE)
        }
        val controller = controller(gateway)

        controller.performThunderCheck(
            forceRefresh = false,
            installedRuntimeVersion = SemanticVersion.parseStableRelease("1.0.0"),
            manuallyRequested = false,
        )
        controller.performManagerCheck(forceRefresh = false, manuallyRequested = false)

        assertTrue(controller.state.value.thunder is ReleaseAvailability.Available)
        assertTrue(controller.state.value.manager is ReleaseAvailability.Available)
        assertFalse(gateway.lastThunderForceRefresh)
        assertFalse(gateway.lastManagerForceRefresh)

        gateway.thunderCheck = ProductReleaseCheck(thunderManifest("1.0.0"), ReleaseCheckSource.NETWORK)
        gateway.managerCheck = ProductReleaseCheck(managerManifest("0.9.0"), ReleaseCheckSource.NETWORK)
        controller.performThunderCheck(
            forceRefresh = true,
            installedRuntimeVersion = SemanticVersion.parseStableRelease("1.0.0"),
            manuallyRequested = true,
        )
        controller.performManagerCheck(forceRefresh = true, manuallyRequested = true)
        assertEquals(ReleaseAvailability.Current, controller.state.value.thunder)
        assertEquals(ReleaseAvailability.Current, controller.state.value.manager)
        assertTrue(gateway.lastThunderForceRefresh)
        assertTrue(gateway.lastManagerForceRefresh)
    }

    @Test
    fun `automatic failure is silent-state distinguishable from manual failure`() = runBlocking {
        val gateway = FakeGateway(temporary.root).apply { checkFailure = IOException("offline") }
        val controller = controller(gateway)

        controller.performThunderCheck(false, null, manuallyRequested = false)
        controller.performManagerCheck(true, manuallyRequested = true)

        assertEquals(ReleaseAvailability.Failed(false), controller.state.value.thunder)
        assertEquals(ReleaseAvailability.Failed(true), controller.state.value.manager)
    }

    @Test
    fun `verified Thunder download publishes ready before invoking reinjection callback`() {
        val gateway = FakeGateway(temporary.root)
        val controller = controller(gateway)
        var callbackArtifact: VerifiedRuntimeArtifact? = null
        var callbackState: ThunderRuntimeDownloadState? = null
        val manifest = thunderManifest("1.1.0")

        controller.downloadThunder(manifest, "1.0.0") { artifact ->
            callbackArtifact = artifact
            callbackState = controller.state.value.thunderDownload
        }

        assertEquals(gateway.runtimeArtifact, callbackArtifact)
        assertEquals(ThunderRuntimeDownloadState.VerifiedReady(gateway.runtimeArtifact), callbackState)
        assertEquals(
            ThunderRuntimeDownloadState.VerifiedReady(gateway.runtimeArtifact),
            controller.state.value.thunderDownload,
        )
    }

    @Test
    fun `failed Thunder download never invokes reinjection callback`() {
        val gateway = FakeGateway(temporary.root).apply { downloadFailure = IOException("offline") }
        val controller = controller(gateway)
        var callbackInvoked = false
        val manifest = thunderManifest("1.1.0")

        controller.downloadThunder(manifest, "1.0.0") { callbackInvoked = true }

        assertFalse(callbackInvoked)
        assertEquals(
            ThunderRuntimeDownloadState.Failed(manifest.version),
            controller.state.value.thunderDownload,
        )
    }

    @Test
    fun `verified Manager download publishes ready before invoking install callback`() {
        val gateway = FakeGateway(temporary.root)
        val controller = controller(gateway)
        var callbackCandidate: ManagerUpdateCandidate? = null
        var callbackState: ManagerUpdateDownloadState? = null

        controller.downloadManager(managerManifest("1.1.0")) { candidate ->
            callbackCandidate = candidate
            callbackState = controller.managerDownloadState.value
        }

        assertEquals(gateway.managerCandidate, callbackCandidate)
        assertEquals(ManagerUpdateDownloadState.Ready(gateway.managerCandidate), callbackState)
    }

    private fun controller(gateway: ManagerUpdateGateway) = ManagerUpdateController(
        gateway,
        CoroutineScope(Dispatchers.Unconfined),
    )

    private fun thunderManifest(version: String): ThunderReleaseManifest {
        val artifact = ReleaseArtifact(
            URI("https://github.com/Accentaro/Thunder/releases/download/v$version/runtime.js"),
            128,
            "a".repeat(64),
        )
        return ThunderReleaseManifest(
            SemanticVersion.parseStableRelease(version),
            Instant.parse("2026-09-02T00:00:00Z"),
            artifact,
            URI("https://github.com/Accentaro/Thunder/releases/tag/v$version"),
        )
    }

    private fun managerManifest(version: String): ThunderManagerReleaseManifest {
        val artifact = ReleaseArtifact(
            URI("https://github.com/Accentaro/ThunderManager/releases/download/v$version/ThunderManager-$version.apk"),
            256,
            "b".repeat(64),
        )
        return ThunderManagerReleaseManifest(
            SemanticVersion.parseStableRelease(version),
            Instant.parse("2026-09-02T00:00:00Z"),
            artifact,
            URI("https://github.com/Accentaro/ThunderManager/releases/tag/v$version"),
        )
    }

    private class FakeGateway(root: File) : ManagerUpdateGateway {
        override val bundledRuntimeVersion = SemanticVersion.parseStableRelease("1.0.0")
        override val managerVersion = SemanticVersion.parseStableRelease("1.0.0")
        override val managerDownloadState = MutableStateFlow<ManagerUpdateDownloadState>(
            ManagerUpdateDownloadState.Idle,
        )
        var thunderCheck = ProductReleaseCheck(
            ThunderReleaseManifest(
                SemanticVersion.parseStableRelease("1.0.0"),
                Instant.parse("2026-09-02T00:00:00Z"),
                ReleaseArtifact(URI("https://example.test/runtime.js"), 128, "a".repeat(64)),
                URI("https://example.test/notes"),
            ),
            ReleaseCheckSource.NETWORK,
        )
        var managerCheck = ProductReleaseCheck(
            ThunderManagerReleaseManifest(
                SemanticVersion.parseStableRelease("1.0.0"),
                Instant.parse("2026-09-02T00:00:00Z"),
                ReleaseArtifact(URI("https://example.test/manager.apk"), 256, "b".repeat(64)),
                URI("https://example.test/notes"),
            ),
            ReleaseCheckSource.NETWORK,
        )
        var checkFailure: Exception? = null
        var downloadFailure: Exception? = null
        var lastThunderForceRefresh = false
        var lastManagerForceRefresh = false
        val runtimeArtifact = VerifiedRuntimeArtifact(
            SemanticVersion.parseStableRelease("1.1.0"),
            File(root, "runtime.js"),
            128,
            "a".repeat(64),
            URI("https://example.test/runtime.js"),
            URI("https://example.test/notes"),
        )
        val managerCandidate = ManagerUpdateCandidate(managerManifestForCandidate(), File(root, "manager.apk"))

        override suspend fun checkThunder(forceRefresh: Boolean): ProductReleaseCheck<ThunderReleaseManifest> {
            lastThunderForceRefresh = forceRefresh
            checkFailure?.let { throw it }
            return thunderCheck
        }

        override suspend fun checkManager(forceRefresh: Boolean): ProductReleaseCheck<ThunderManagerReleaseManifest> {
            lastManagerForceRefresh = forceRefresh
            checkFailure?.let { throw it }
            return managerCheck
        }

        override suspend fun downloadThunder(
            manifest: ThunderReleaseManifest,
            installedRuntimeVersion: SemanticVersion?,
        ): VerifiedRuntimeArtifact {
            downloadFailure?.let { throw it }
            return runtimeArtifact
        }

        override suspend fun downloadManager(manifest: ThunderManagerReleaseManifest): ManagerUpdateCandidate {
            managerDownloadState.value = ManagerUpdateDownloadState.Ready(managerCandidate)
            return managerCandidate
        }

        override suspend fun clearManagerDownload() = Unit

        private fun managerManifestForCandidate(): ThunderManagerReleaseManifest {
            val version = "1.1.0"
            return ThunderManagerReleaseManifest(
                SemanticVersion.parseStableRelease(version),
                Instant.parse("2026-09-02T00:00:00Z"),
                ReleaseArtifact(URI("https://example.test/manager.apk"), 256, "b".repeat(64)),
                URI("https://example.test/notes"),
            )
        }
    }
}
