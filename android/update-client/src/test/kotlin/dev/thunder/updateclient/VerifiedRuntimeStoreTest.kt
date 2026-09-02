package dev.thunder.updateclient

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VerifiedRuntimeStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `valid newer runtime is promoted immutably and read back with verification`() = runBlocking {
        val root = temporary.newFolder("store")
        val downloadDirectory = temporary.newFolder("downloads")
        val store = VerifiedRuntimeStore(root)
        val downloader = FakeDownloader(UpdateTestFixtures.runtimeBytes)
        val updater = ThunderRuntimeUpdateDownloader(downloader, downloadDirectory, store)

        val artifact = updater.downloadAndStore(
            UpdateTestFixtures.thunderManifest("0.0.2"),
            SemanticVersion.parseStableRelease("0.0.1"),
        )

        assertEquals("0.0.2", artifact.version.toString())
        assertArrayEquals(UpdateTestFixtures.runtimeBytes, artifact.readBytes())
        assertEquals(artifact, store.latest())
        assertEquals(artifact, store.get(SemanticVersion.parseStableRelease("0.0.2")))
        assertTrue(downloadDirectory.listFiles().orEmpty().isEmpty())

        val reused = updater.downloadAndStore(
            UpdateTestFixtures.thunderManifest("0.0.2"),
            SemanticVersion.parseStableRelease("0.0.1"),
        )
        assertEquals(artifact, reused)
        assertEquals(1, downloader.calls)
    }

    @Test
    fun `equal and older runtime attempts are rejected before download`() {
        val downloader = FakeDownloader(UpdateTestFixtures.runtimeBytes)
        val updater = ThunderRuntimeUpdateDownloader(
            downloader,
            temporary.newFolder("downloads"),
            VerifiedRuntimeStore(temporary.newFolder("store")),
        )
        listOf("1.2.3", "1.2.2").forEach { version ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    updater.downloadAndStore(
                        UpdateTestFixtures.thunderManifest(version),
                        SemanticVersion.parseStableRelease("1.2.3"),
                    )
                }
            }
        }
        assertEquals(0, downloader.calls)
    }

    @Test
    fun `SHA mismatch never creates a stored release`() {
        val root = temporary.newFolder("store")
        val store = VerifiedRuntimeStore(root)
        val wrongBytes = UpdateTestFixtures.runtimeBytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val file = temporary.newFile("wrong.part").apply { writeBytes(wrongBytes) }

        val error = assertThrows(UpdateDownloadException::class.java) {
            runBlocking {
                store.promote(
                    UpdateTestFixtures.thunderManifest(),
                    VerifiedDownload(file, file.length(), UpdateTestFixtures.sha256(wrongBytes)),
                )
            }
        }
        assertEquals(UpdateDownloadFailureCode.DIGEST_MISMATCH, error.code)
        assertNull(runBlocking { store.latest() })
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `corrupted stored runtime is rejected instead of injected`() = runBlocking {
        val root = temporary.newFolder("store")
        val store = VerifiedRuntimeStore(root)
        val manifest = UpdateTestFixtures.thunderManifest()
        val file = temporary.newFile("runtime.part").apply { writeBytes(UpdateTestFixtures.runtimeBytes) }
        val artifact = store.promote(
            manifest,
            VerifiedDownload(file, file.length(), manifest.runtime.sha256),
        )
        artifact.file.writeText("corrupt")

        val error = assertThrows(UpdateDownloadException::class.java) {
            runBlocking { store.latest() }
        }
        assertEquals(UpdateDownloadFailureCode.SIZE_MISMATCH, error.code)
    }

    @Test
    fun `same version cannot be replaced with changed release bytes`() = runBlocking {
        val root = temporary.newFolder("store")
        val store = VerifiedRuntimeStore(root)
        val first = UpdateTestFixtures.runtimeBytes
        val second = "globalThis.__THUNDER_CHANGED_RELEASE__=true;".repeat(4).toByteArray()
        val firstManifest = UpdateTestFixtures.thunderManifest(bytes = first)
        val firstFile = temporary.newFile("first.part").apply { writeBytes(first) }
        store.promote(firstManifest, VerifiedDownload(firstFile, firstFile.length(), firstManifest.runtime.sha256))

        val secondManifest = UpdateTestFixtures.thunderManifest(bytes = second)
        val secondFile = temporary.newFile("second.part").apply { writeBytes(second) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.promote(
                    secondManifest,
                    VerifiedDownload(secondFile, secondFile.length(), secondManifest.runtime.sha256),
                )
            }
        }
        assertArrayEquals(first, store.latest()?.readBytes())
    }

    private class FakeDownloader(private val bytes: ByteArray) : VerifiedArtifactDownloader {
        var calls = 0

        override suspend fun download(
            artifact: ReleaseArtifact,
            destinationDirectory: File,
            maximumBytes: Long,
        ): VerifiedDownload {
            calls++
            destinationDirectory.mkdirs()
            val file = File(destinationDirectory, "fake-$calls.part").apply { writeBytes(bytes) }
            return VerifiedDownload(file, file.length(), UpdateTestFixtures.sha256(bytes))
        }
    }
}
