package dev.thunder.updateclient

import java.net.URI
import java.security.MessageDigest
import java.time.Instant

internal object UpdateTestFixtures {
    val runtimeBytes: ByteArray = "globalThis.__THUNDER_UPDATE_TEST__=true;".repeat(4).toByteArray()
    val managerBytes: ByteArray = ByteArray(256) { index -> index.toByte() }

    fun thunderManifestBytes(
        version: String = "0.0.2",
        bytes: ByteArray = runtimeBytes,
        size: Long = bytes.size.toLong(),
        sha256: String = sha256(bytes),
        artifactUrl: String = "https://github.com/Accentaro/Thunder/releases/download/v$version/runtime.js",
        notesUrl: String = "https://github.com/Accentaro/Thunder/releases/tag/v$version",
        extraRootField: String = "",
    ): ByteArray = """
        {
          "schema": 1,
          "version": "$version",
          "publishedAt": "2026-09-02T00:00:00Z",
          "runtime": {
            "url": "$artifactUrl",
            "size": $size,
            "sha256": "$sha256"
          },
          "notesUrl": "$notesUrl"$extraRootField
        }
    """.trimIndent().toByteArray()

    fun managerManifestBytes(
        version: String = "0.0.2",
        bytes: ByteArray = managerBytes,
        size: Long = bytes.size.toLong(),
        sha256: String = sha256(bytes),
        artifactUrl: String =
            "https://github.com/Accentaro/ThunderManager/releases/download/v$version/ThunderManager-$version.apk",
        notesUrl: String = "https://github.com/Accentaro/ThunderManager/releases/tag/v$version",
    ): ByteArray = """
        {
          "schema": 1,
          "version": "$version",
          "publishedAt": "2026-09-02T00:00:00Z",
          "apk": {
            "url": "$artifactUrl",
            "size": $size,
            "sha256": "$sha256"
          },
          "notesUrl": "$notesUrl"
        }
    """.trimIndent().toByteArray()

    fun thunderManifest(
        version: String = "0.0.2",
        bytes: ByteArray = runtimeBytes,
    ): ThunderReleaseManifest = ThunderReleaseManifest(
        version = SemanticVersion.parseStableRelease(version),
        publishedAt = Instant.parse("2026-09-02T00:00:00Z"),
        runtime = ReleaseArtifact(
            URI("https://github.com/Accentaro/Thunder/releases/download/v$version/runtime.js"),
            bytes.size.toLong(),
            sha256(bytes),
        ),
        notesUrl = URI("https://github.com/Accentaro/Thunder/releases/tag/v$version"),
    )

    fun managerManifest(
        version: String = "0.0.2",
        bytes: ByteArray = managerBytes,
    ): ThunderManagerReleaseManifest = ThunderManagerReleaseManifest(
        version = SemanticVersion.parseStableRelease(version),
        publishedAt = Instant.parse("2026-09-02T00:00:00Z"),
        apk = ReleaseArtifact(
            URI("https://github.com/Accentaro/ThunderManager/releases/download/v$version/ThunderManager-$version.apk"),
            bytes.size.toLong(),
            sha256(bytes),
        ),
        notesUrl = URI("https://github.com/Accentaro/ThunderManager/releases/tag/v$version"),
    )

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
