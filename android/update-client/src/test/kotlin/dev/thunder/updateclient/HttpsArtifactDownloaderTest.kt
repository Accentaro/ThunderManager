package dev.thunder.updateclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

class HttpsArtifactDownloaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `valid HTTPS artifact is streamed and verified`() = runBlocking {
        val bytes = UpdateTestFixtures.runtimeBytes
        val connection = FakeHttpsConnection(
            body = ByteArrayInputStream(bytes),
            declaredLength = bytes.size.toLong(),
        )
        val downloader = HttpsArtifactDownloader(connectionFactory = { connection })
        val artifact = artifact(bytes)

        val result = downloader.download(artifact, temporary.newFolder(), UpdateLimits.MAX_RUNTIME_BYTES)

        assertArrayEquals(bytes, result.file.readBytes())
        assertEquals(bytes.size.toLong(), result.size)
        assertEquals(UpdateTestFixtures.sha256(bytes), result.sha256)
    }

    @Test
    fun `oversized and digest-mismatched downloads delete partial files`() {
        val oversized = ByteArray(513) { 7 }
        val oversizedDirectory = temporary.newFolder()
        val oversizedFailure = downloadFailure(
            HttpsArtifactDownloader(connectionFactory = {
                FakeHttpsConnection(body = ByteArrayInputStream(oversized), declaredLength = -1)
            }),
            artifact = ReleaseArtifact(URI("https://example.test/runtime.js"), 512, "0".repeat(64)),
            directory = oversizedDirectory,
            maximumBytes = 512,
        )
        assertEquals(UpdateDownloadFailureCode.TOO_LARGE, oversizedFailure.code)
        assertTrue(oversizedDirectory.listFiles().orEmpty().isEmpty())

        val bytes = UpdateTestFixtures.runtimeBytes
        val digestDirectory = temporary.newFolder()
        val digestFailure = downloadFailure(
            HttpsArtifactDownloader(connectionFactory = {
                FakeHttpsConnection(ByteArrayInputStream(bytes), bytes.size.toLong())
            }),
            ReleaseArtifact(URI("https://example.test/runtime.js"), bytes.size.toLong(), "0".repeat(64)),
            digestDirectory,
            UpdateLimits.MAX_RUNTIME_BYTES,
        )
        assertEquals(UpdateDownloadFailureCode.DIGEST_MISMATCH, digestFailure.code)
        assertTrue(digestDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation interrupts streaming and deletes the partial file`() {
        val directory = temporary.newFolder()
        val input = object : InputStream() {
            private var first = true

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw CancellationException("cancelled")
                first = false
                repeat(minOf(32, length)) { index -> buffer[offset + index] = index.toByte() }
                return minOf(32, length)
            }
        }
        val downloader = HttpsArtifactDownloader(connectionFactory = {
            FakeHttpsConnection(input, declaredLength = -1)
        })

        assertThrows(CancellationException::class.java) {
            runBlocking {
                downloader.download(
                    ReleaseArtifact(URI("https://example.test/runtime.js"), 64, "0".repeat(64)),
                    directory,
                    64,
                )
            }
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `network TLS cleartext redirects and encoded responses fail closed`() {
        val tls = HttpsArtifactDownloader(connectionFactory = {
            FakeHttpsConnection(responseFailure = SSLHandshakeException("untrusted"))
        })
        assertEquals(
            UpdateDownloadFailureCode.TLS,
            fetchFailure(tls).code,
        )

        val offline = HttpsArtifactDownloader(connectionFactory = { throw IOException("offline") })
        assertEquals(UpdateDownloadFailureCode.IO, fetchFailure(offline).code)

        val cleartextRedirect = HttpsArtifactDownloader(connectionFactory = {
            FakeHttpsConnection(status = 302, headers = mapOf("Location" to "http://example.test/runtime.js"))
        })
        assertEquals(UpdateDownloadFailureCode.INVALID_URL, fetchFailure(cleartextRedirect).code)

        val encoded = HttpsArtifactDownloader(connectionFactory = {
            FakeHttpsConnection(
                body = ByteArrayInputStream(byteArrayOf(1)),
                declaredLength = 1,
                encoding = "gzip",
            )
        })
        assertEquals(UpdateDownloadFailureCode.CONTENT_ENCODING, fetchFailure(encoded).code)
    }

    @Test
    fun `URL policy accepts only absolute HTTPS without credentials fragments or custom ports`() {
        assertEquals("https", HttpsUrlPolicy.requireHttps("https://example.test/file").scheme)
        listOf(
            "http://example.test/file",
            "//example.test/file",
            "https://user@example.test/file",
            "https://example.test:8443/file",
            "https://example.test/file#fragment",
        ).forEach { value ->
            val error = assertThrows(UpdateDownloadException::class.java) {
                HttpsUrlPolicy.requireHttps(value)
            }
            assertEquals(UpdateDownloadFailureCode.INVALID_URL, error.code)
        }
    }

    private fun artifact(bytes: ByteArray): ReleaseArtifact = ReleaseArtifact(
        URI("https://example.test/runtime.js"),
        bytes.size.toLong(),
        UpdateTestFixtures.sha256(bytes),
    )

    private fun downloadFailure(
        downloader: HttpsArtifactDownloader,
        artifact: ReleaseArtifact,
        directory: java.io.File,
        maximumBytes: Long,
    ): UpdateDownloadException = assertThrows(UpdateDownloadException::class.java) {
        runBlocking { downloader.download(artifact, directory, maximumBytes) }
    }

    private fun fetchFailure(downloader: HttpsArtifactDownloader): UpdateDownloadException =
        assertThrows(UpdateDownloadException::class.java) {
            runBlocking { downloader.fetch(URI("https://example.test/release.json"), 1024) }
        }

    private class FakeHttpsConnection(
        private val body: InputStream = ByteArrayInputStream(byteArrayOf()),
        private val declaredLength: Long = -1,
        private val status: Int = 200,
        private val encoding: String? = null,
        private val headers: Map<String, String> = emptyMap(),
        private val responseFailure: IOException? = null,
    ) : HttpsURLConnection(URL("https://example.test/resource")) {
        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return status
        }

        override fun getInputStream(): InputStream = body
        override fun getContentLengthLong(): Long = declaredLength
        override fun getContentEncoding(): String? = encoding
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getCipherSuite(): String = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
