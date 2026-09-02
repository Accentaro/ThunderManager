package dev.thunder.updateclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

interface ReleaseBytesFetcher {
    suspend fun fetch(url: URI, maximumBytes: Int): ByteArray
}

interface VerifiedArtifactDownloader {
    suspend fun download(
        artifact: ReleaseArtifact,
        destinationDirectory: File,
        maximumBytes: Long,
    ): VerifiedDownload
}

data class VerifiedDownload(
    val file: File,
    val size: Long,
    val sha256: String,
)

enum class UpdateDownloadFailureCode {
    INVALID_URL,
    HTTP_STATUS,
    REDIRECT_LIMIT,
    CONTENT_ENCODING,
    TOO_LARGE,
    SIZE_MISMATCH,
    DIGEST_MISMATCH,
    TLS,
    IO,
}

class UpdateDownloadException(
    val code: UpdateDownloadFailureCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)

object HttpsUrlPolicy {
    fun requireHttps(value: String): URI = try {
        requireHttps(URI(value))
    } catch (error: UpdateDownloadException) {
        throw error
    } catch (error: Exception) {
        throw UpdateDownloadException(UpdateDownloadFailureCode.INVALID_URL, error)
    }

    fun requireHttps(value: URI): URI {
        if (!value.isAbsolute || !value.scheme.equals("https", ignoreCase = true) ||
            value.host.isNullOrBlank() || value.userInfo != null || value.fragment != null ||
            value.port !in setOf(-1, 443)
        ) {
            throw UpdateDownloadException(UpdateDownloadFailureCode.INVALID_URL)
        }
        return value
    }
}

class HttpsArtifactDownloader private constructor(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val connectionFactory: (URI) -> HttpsURLConnection,
) : ReleaseBytesFetcher, VerifiedArtifactDownloader {
    constructor(
        connectTimeoutMillis: Int = 15_000,
        readTimeoutMillis: Int = 30_000,
    ) : this(connectTimeoutMillis, readTimeoutMillis, { uri ->
        uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw UpdateDownloadException(UpdateDownloadFailureCode.INVALID_URL)
    })

    internal constructor(connectionFactory: (URI) -> HttpsURLConnection) : this(
        15_000,
        30_000,
        connectionFactory,
    )

    override suspend fun fetch(url: URI, maximumBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        if (maximumBytes <= 0) throw UpdateDownloadException(UpdateDownloadFailureCode.TOO_LARGE)
        withResponse(url) { connection ->
            requireIdentityEncoding(connection)
            val declared = connection.contentLengthLong
            if (declared > maximumBytes) throw UpdateDownloadException(UpdateDownloadFailureCode.TOO_LARGE)
            connection.inputStream.use { input -> input.readBounded(maximumBytes) }
        }
    }

    override suspend fun download(
        artifact: ReleaseArtifact,
        destinationDirectory: File,
        maximumBytes: Long,
    ): VerifiedDownload = withContext(Dispatchers.IO) {
        if (artifact.size <= 0L || artifact.size > maximumBytes || maximumBytes <= 0L) {
            throw UpdateDownloadException(UpdateDownloadFailureCode.TOO_LARGE)
        }
        if (!SHA_256.matches(artifact.sha256)) {
            throw UpdateDownloadException(UpdateDownloadFailureCode.DIGEST_MISMATCH)
        }
        if (!destinationDirectory.isDirectory && !destinationDirectory.mkdirs()) {
            throw UpdateDownloadException(UpdateDownloadFailureCode.IO)
        }
        val canonicalDirectory = try {
            destinationDirectory.canonicalFile
        } catch (error: IOException) {
            throw UpdateDownloadException(UpdateDownloadFailureCode.IO, error)
        }
        val output = File(canonicalDirectory, "artifact-${UUID.randomUUID()}.part")
        if (output.canonicalFile.parentFile != canonicalDirectory) {
            throw UpdateDownloadException(UpdateDownloadFailureCode.IO)
        }

        try {
            val result = withResponse(artifact.url) { connection ->
                requireIdentityEncoding(connection)
                val declared = connection.contentLengthLong
                if (declared > maximumBytes || (declared >= 0L && declared != artifact.size)) {
                    throw UpdateDownloadException(
                        if (declared > maximumBytes) {
                            UpdateDownloadFailureCode.TOO_LARGE
                        } else {
                            UpdateDownloadFailureCode.SIZE_MISMATCH
                        },
                    )
                }
                copyVerified(connection.inputStream, output, artifact, maximumBytes)
            }
            result
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        } catch (error: UpdateDownloadException) {
            output.delete()
            throw error
        } catch (error: Exception) {
            output.delete()
            throw UpdateDownloadException(UpdateDownloadFailureCode.IO, error)
        }
    }

    private fun copyVerified(
        source: InputStream,
        output: File,
        artifact: ReleaseArtifact,
        maximumBytes: Long,
    ): VerifiedDownload {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        source.use { input ->
            FileOutputStream(output).use { fileOutput ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    total = try {
                        Math.addExact(total, count.toLong())
                    } catch (error: ArithmeticException) {
                        throw UpdateDownloadException(UpdateDownloadFailureCode.TOO_LARGE, error)
                    }
                    if (total > maximumBytes || total > artifact.size) {
                        throw UpdateDownloadException(UpdateDownloadFailureCode.TOO_LARGE)
                    }
                    digest.update(buffer, 0, count)
                    fileOutput.write(buffer, 0, count)
                }
                fileOutput.fd.sync()
            }
        }
        if (total != artifact.size) throw UpdateDownloadException(UpdateDownloadFailureCode.SIZE_MISMATCH)
        val actual = digest.digest().toHex()
        if (actual != artifact.sha256) throw UpdateDownloadException(UpdateDownloadFailureCode.DIGEST_MISMATCH)
        return VerifiedDownload(output, total, actual)
    }

    private fun InputStream.readBounded(maximumBytes: Int): ByteArray = use { input ->
        val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (output.size() > maximumBytes - count) {
                throw UpdateDownloadException(UpdateDownloadFailureCode.TOO_LARGE)
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun requireIdentityEncoding(connection: HttpsURLConnection) {
        val encoding = connection.contentEncoding
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            throw UpdateDownloadException(UpdateDownloadFailureCode.CONTENT_ENCODING)
        }
    }

    private fun <T> withResponse(initialUrl: URI, block: (HttpsURLConnection) -> T): T {
        var current = HttpsUrlPolicy.requireHttps(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = try {
                connectionFactory(current)
            } catch (error: UpdateDownloadException) {
                throw error
            } catch (error: SSLException) {
                throw UpdateDownloadException(UpdateDownloadFailureCode.TLS, error)
            } catch (error: Exception) {
                throw UpdateDownloadException(UpdateDownloadFailureCode.IO, error)
            }
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val status = connection.responseCode
                if (status in REDIRECT_STATUSES) {
                    if (redirectCount == MAX_REDIRECTS) {
                        throw UpdateDownloadException(UpdateDownloadFailureCode.REDIRECT_LIMIT)
                    }
                    val location = connection.getHeaderField("Location")
                        ?.takeIf { it.length <= MAX_REDIRECT_LOCATION_LENGTH }
                        ?: throw UpdateDownloadException(UpdateDownloadFailureCode.INVALID_URL)
                    current = HttpsUrlPolicy.requireHttps(current.resolve(location))
                } else {
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw UpdateDownloadException(UpdateDownloadFailureCode.HTTP_STATUS)
                    }
                    return block(connection)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UpdateDownloadException) {
                throw error
            } catch (error: SSLException) {
                throw UpdateDownloadException(UpdateDownloadFailureCode.TLS, error)
            } catch (error: Exception) {
                throw UpdateDownloadException(UpdateDownloadFailureCode.IO, error)
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateDownloadException(UpdateDownloadFailureCode.REDIRECT_LIMIT)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
        const val MAX_REDIRECTS = 5
        const val MAX_REDIRECT_LOCATION_LENGTH = 4_096
        const val USER_AGENT = "ThunderManager-update-client"
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

object ArtifactIntegrity {
    fun requireMatches(file: File, artifact: ReleaseArtifact, maximumBytes: Long) {
        if (!file.isFile || !file.canRead() || artifact.size !in 1..maximumBytes || file.length() != artifact.size) {
            throw UpdateDownloadException(UpdateDownloadFailureCode.SIZE_MISMATCH)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                if (total > maximumBytes) throw UpdateDownloadException(UpdateDownloadFailureCode.TOO_LARGE)
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        if (actual != artifact.sha256) throw UpdateDownloadException(UpdateDownloadFailureCode.DIGEST_MISMATCH)
    }
}
