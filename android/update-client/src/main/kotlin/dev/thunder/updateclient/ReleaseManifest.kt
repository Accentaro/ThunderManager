package dev.thunder.updateclient

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

enum class ReleaseProduct(
    val manifestUrl: URI,
    internal val artifactKey: String,
) {
    THUNDER(
        URI.create("https://github.com/Accentaro/Thunder/releases/latest/download/release.json"),
        "runtime",
    ),
    THUNDER_MANAGER(
        URI.create("https://github.com/Accentaro/ThunderManager/releases/latest/download/release.json"),
        "apk",
    ),
}

data class ReleaseArtifact(
    val url: URI,
    val size: Long,
    val sha256: String,
)

sealed interface StableReleaseManifest {
    val version: SemanticVersion
    val publishedAt: Instant
    val artifact: ReleaseArtifact
    val notesUrl: URI
}

data class ThunderReleaseManifest(
    override val version: SemanticVersion,
    override val publishedAt: Instant,
    val runtime: ReleaseArtifact,
    override val notesUrl: URI,
) : StableReleaseManifest {
    override val artifact: ReleaseArtifact
        get() = runtime
}

data class ThunderManagerReleaseManifest(
    override val version: SemanticVersion,
    override val publishedAt: Instant,
    val apk: ReleaseArtifact,
    override val notesUrl: URI,
) : StableReleaseManifest {
    override val artifact: ReleaseArtifact
        get() = apk
}

object ReleaseManifestParser {
    fun parse(product: ReleaseProduct, bytes: ByteArray): StableReleaseManifest {
        if (bytes.size !in 1..UpdateLimits.MAX_MANIFEST_BYTES) {
            throw ReleaseManifestException("Release manifest size is invalid")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw ReleaseManifestException("Release manifest is not UTF-8", error)
        }

        try {
            val tokener = JSONTokener(text)
            val root = tokener.nextValue() as? JSONObject
                ?: throw ReleaseManifestException("Release manifest root must be an object")
            if (tokener.nextClean().code != 0) {
                throw ReleaseManifestException("Release manifest contains trailing data")
            }
            root.requireExactKeys(setOf("schema", "version", "publishedAt", product.artifactKey, "notesUrl"))
            if (root.strictLong("schema") != 1L) {
                throw ReleaseManifestException("Release manifest schema is unsupported")
            }
            val version = try {
                SemanticVersion.parseStableRelease(root.strictString("version"))
            } catch (error: IllegalArgumentException) {
                throw ReleaseManifestException("Release version is invalid", error)
            }
            val publishedAtText = root.strictString("publishedAt")
            if (publishedAtText.length !in 20..40 || !publishedAtText.endsWith('Z')) {
                throw ReleaseManifestException("Release publication time is invalid")
            }
            val publishedAt = try {
                Instant.parse(publishedAtText)
            } catch (error: Exception) {
                throw ReleaseManifestException("Release publication time is invalid", error)
            }
            val artifactObject = root.strictObject(product.artifactKey).also {
                it.requireExactKeys(setOf("url", "size", "sha256"))
            }
            val expectedArtifactUrl = expectedArtifactUrl(product, version)
            val artifact = ReleaseArtifact(
                url = artifactObject.strictUri("url", expectedArtifactUrl),
                size = artifactObject.strictLong("size").also {
                    if (it <= 0L) throw ReleaseManifestException("Release artifact size is invalid")
                },
                sha256 = artifactObject.strictString("sha256").also {
                    if (!SHA_256.matches(it)) throw ReleaseManifestException("Release artifact digest is invalid")
                },
            )
            val notesUrl = root.strictUri("notesUrl", expectedNotesUrl(product, version))
            return when (product) {
                ReleaseProduct.THUNDER -> ThunderReleaseManifest(version, publishedAt, artifact, notesUrl)
                ReleaseProduct.THUNDER_MANAGER -> ThunderManagerReleaseManifest(version, publishedAt, artifact, notesUrl)
            }
        } catch (error: ReleaseManifestException) {
            throw error
        } catch (error: JSONException) {
            throw ReleaseManifestException("Release manifest JSON is invalid", error)
        } catch (error: RuntimeException) {
            throw ReleaseManifestException("Release manifest is invalid", error)
        }
    }

    private fun expectedArtifactUrl(product: ReleaseProduct, version: SemanticVersion): String = when (product) {
        ReleaseProduct.THUNDER ->
            "https://github.com/Accentaro/Thunder/releases/download/v$version/runtime.js"
        ReleaseProduct.THUNDER_MANAGER ->
            "https://github.com/Accentaro/ThunderManager/releases/download/v$version/ThunderManager-$version.apk"
    }

    private fun expectedNotesUrl(product: ReleaseProduct, version: SemanticVersion): String = when (product) {
        ReleaseProduct.THUNDER -> "https://github.com/Accentaro/Thunder/releases/tag/v$version"
        ReleaseProduct.THUNDER_MANAGER -> "https://github.com/Accentaro/ThunderManager/releases/tag/v$version"
    }

    private fun JSONObject.requireExactKeys(expected: Set<String>) {
        val actual = keys().asSequence().toSet()
        if (actual != expected) throw ReleaseManifestException("Release manifest fields are invalid")
    }

    private fun JSONObject.strictString(name: String): String {
        val value = get(name)
        if (value !is String || value.isBlank() || value.length > MAX_STRING_LENGTH) {
            throw ReleaseManifestException("Release manifest field $name is invalid")
        }
        return value
    }

    private fun JSONObject.strictLong(name: String): Long {
        val value = get(name)
        if (value !is Number || !INTEGER.matches(value.toString())) {
            throw ReleaseManifestException("Release manifest field $name is invalid")
        }
        return value.toString().toLongOrNull()
            ?: throw ReleaseManifestException("Release manifest field $name is out of range")
    }

    private fun JSONObject.strictObject(name: String): JSONObject = get(name) as? JSONObject
        ?: throw ReleaseManifestException("Release manifest field $name must be an object")

    private fun JSONObject.strictUri(name: String, expected: String): URI {
        val value = strictString(name)
        if (value != expected) throw ReleaseManifestException("Release URL does not match the immutable release")
        return HttpsUrlPolicy.requireHttps(value)
    }

    private const val MAX_STRING_LENGTH = 2_048
    private val INTEGER = Regex("^(?:0|[1-9][0-9]*)$")
    private val SHA_256 = Regex("^[0-9a-f]{64}$")
}

class ReleaseManifestException(message: String, cause: Throwable? = null) : Exception(message, cause)

object UpdateLimits {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_RUNTIME_BYTES = 576L * 1024L
    const val MAX_MANAGER_APK_BYTES = 256L * 1024L * 1024L
}
