package dev.thunder.updateclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ManagerSelfUpdateDownloaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `valid newer Manager APK reaches ready state and clear removes it`() = runBlocking {
        val downloader = FakeDownloader(UpdateTestFixtures.managerBytes)
        val updater = updater(downloader)

        val candidate = updater.download(UpdateTestFixtures.managerManifest("1.2.4"))

        candidate.verifyIntegrity()
        assertEquals(ManagerUpdateDownloadState.Ready(candidate), updater.state.value)
        assertTrue(candidate.apk.isFile)
        updater.clear()
        assertEquals(ManagerUpdateDownloadState.Idle, updater.state.value)
        assertFalse(candidate.apk.exists())
    }

    @Test
    fun `equal and older Manager versions are rejected before download`() {
        listOf("1.2.3", "1.2.2").forEach { version ->
            val downloader = FakeDownloader(UpdateTestFixtures.managerBytes)
            val updater = updater(downloader)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { updater.download(UpdateTestFixtures.managerManifest(version)) }
            }
            assertEquals(
                ManagerUpdateDownloadState.Failed(
                    SemanticVersion.parseStableRelease(version),
                    ManagerUpdateFailure.NOT_NEWER,
                ),
                updater.state.value,
            )
            assertEquals(0, downloader.calls)
        }
    }

    @Test
    fun `download and integrity failures have distinct terminal state`() {
        val networkUpdater = updater(FakeDownloader(UpdateTestFixtures.managerBytes, IOException("offline")))
        assertThrows(IOException::class.java) {
            runBlocking { networkUpdater.download(UpdateTestFixtures.managerManifest("1.2.4")) }
        }
        assertEquals(
            ManagerUpdateFailure.DOWNLOAD_FAILED,
            (networkUpdater.state.value as ManagerUpdateDownloadState.Failed).reason,
        )

        val corruptBytes = UpdateTestFixtures.managerBytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val corruptUpdater = updater(FakeDownloader(corruptBytes))
        assertThrows(UpdateDownloadException::class.java) {
            runBlocking { corruptUpdater.download(UpdateTestFixtures.managerManifest("1.2.4")) }
        }
        assertEquals(
            ManagerUpdateFailure.INTEGRITY_FAILED,
            (corruptUpdater.state.value as ManagerUpdateDownloadState.Failed).reason,
        )
        assertTrue(temporary.root.walkTopDown().none { it.isFile && it.name.startsWith("fake-") })
    }

    @Test
    fun `cancellation returns state to idle`() {
        val updater = updater(FakeDownloader(UpdateTestFixtures.managerBytes, CancellationException("cancelled")))
        assertThrows(CancellationException::class.java) {
            runBlocking { updater.download(UpdateTestFixtures.managerManifest("1.2.4")) }
        }
        assertEquals(ManagerUpdateDownloadState.Idle, updater.state.value)
    }

    private fun updater(downloader: VerifiedArtifactDownloader) = ManagerSelfUpdateDownloader(
        downloader,
        temporary.newFolder(),
        SemanticVersion.parse("1.2.3"),
    )

    private class FakeDownloader(
        private val bytes: ByteArray,
        private val failure: Exception? = null,
    ) : VerifiedArtifactDownloader {
        var calls = 0

        override suspend fun download(
            artifact: ReleaseArtifact,
            destinationDirectory: File,
            maximumBytes: Long,
        ): VerifiedDownload {
            calls++
            failure?.let { throw it }
            val file = File(destinationDirectory, "fake-$calls.apk").apply { writeBytes(bytes) }
            return VerifiedDownload(file, file.length(), UpdateTestFixtures.sha256(bytes))
        }
    }
}
