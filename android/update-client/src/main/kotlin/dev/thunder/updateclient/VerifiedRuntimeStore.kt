package dev.thunder.updateclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class VerifiedRuntimeArtifact(
    val version: SemanticVersion,
    val file: File,
    val size: Long,
    val sha256: String,
    val sourceUrl: URI,
    val notesUrl: URI,
) {
    fun readBytes(): ByteArray {
        ArtifactIntegrity.requireMatches(
            file,
            ReleaseArtifact(sourceUrl, size, sha256),
            UpdateLimits.MAX_RUNTIME_BYTES,
        )
        return file.readBytes()
    }
}

class VerifiedRuntimeStore(private val root: File) {
    suspend fun promote(
        manifest: ThunderReleaseManifest,
        download: VerifiedDownload,
    ): VerifiedRuntimeArtifact = withContext(Dispatchers.IO) {
        ArtifactIntegrity.requireMatches(download.file, manifest.runtime, UpdateLimits.MAX_RUNTIME_BYTES)
        if (!root.isDirectory && !root.mkdirs()) throw IllegalStateException("Could not create runtime store")
        val destination = File(root, manifest.version.toString())

        synchronized(PROCESS_LOCK) {
            if (destination.exists()) {
                return@synchronized requireSameRelease(loadDirectory(destination), manifest)
            }
            val temporary = File(root, ".${manifest.version}.${UUID.randomUUID()}.tmp")
            if (!temporary.mkdirs()) throw IllegalStateException("Could not stage verified runtime")
            try {
                val runtime = File(temporary, RUNTIME_FILE)
                copyAndSync(download.file, runtime)
                ArtifactIntegrity.requireMatches(runtime, manifest.runtime, UpdateLimits.MAX_RUNTIME_BYTES)
                writeMetadata(File(temporary, METADATA_FILE), manifest)
                moveDirectory(temporary, destination)
                requireSameRelease(loadDirectory(destination), manifest)
            } finally {
                if (temporary.exists()) temporary.deleteRecursively()
            }
        }
    }

    suspend fun latest(): VerifiedRuntimeArtifact? = withContext(Dispatchers.IO) {
        synchronized(PROCESS_LOCK) {
            if (!root.isDirectory) return@synchronized null
            val latestDirectory = root.listFiles()
                .orEmpty()
                .filter(File::isDirectory)
                .mapNotNull { directory ->
                    runCatching { SemanticVersion.parseStableRelease(directory.name) }
                        .getOrNull()
                        ?.let { it to directory }
                }
                .maxByOrNull { it.first }
                ?.second
                ?: return@synchronized null
            loadDirectory(latestDirectory)
        }
    }

    suspend fun get(version: SemanticVersion): VerifiedRuntimeArtifact? = withContext(Dispatchers.IO) {
        synchronized(PROCESS_LOCK) {
            val directory = File(root, version.toString())
            if (directory.isDirectory) loadDirectory(directory) else null
        }
    }

    private fun loadDirectory(directory: File): VerifiedRuntimeArtifact {
        val canonicalRoot = root.canonicalFile
        val canonicalDirectory = directory.canonicalFile
        if (canonicalDirectory.parentFile != canonicalRoot || canonicalDirectory.name.startsWith('.')) {
            throw IllegalStateException("Runtime store path is invalid")
        }
        val metadata = File(canonicalDirectory, METADATA_FILE)
        if (!metadata.isFile || metadata.length() !in 1..MAX_METADATA_BYTES) {
            throw IllegalStateException("Runtime metadata is missing")
        }
        val artifact = DataInputStream(metadata.inputStream().buffered()).use { input ->
            if (input.readInt() != METADATA_MAGIC || input.readInt() != METADATA_SCHEMA) {
                throw IllegalStateException("Runtime metadata schema is invalid")
            }
            val version = SemanticVersion.parseStableRelease(input.readUTF())
            val size = input.readLong()
            val sha256 = input.readUTF()
            val sourceUrl = HttpsUrlPolicy.requireHttps(input.readUTF())
            val notesUrl = HttpsUrlPolicy.requireHttps(input.readUTF())
            if (input.read() != -1 || version.toString() != canonicalDirectory.name) {
                throw IllegalStateException("Runtime metadata is invalid")
            }
            VerifiedRuntimeArtifact(
                version = version,
                file = File(canonicalDirectory, RUNTIME_FILE),
                size = size,
                sha256 = sha256,
                sourceUrl = sourceUrl,
                notesUrl = notesUrl,
            )
        }
        ArtifactIntegrity.requireMatches(
            artifact.file,
            ReleaseArtifact(artifact.sourceUrl, artifact.size, artifact.sha256),
            UpdateLimits.MAX_RUNTIME_BYTES,
        )
        return artifact
    }

    private fun requireSameRelease(
        artifact: VerifiedRuntimeArtifact,
        manifest: ThunderReleaseManifest,
    ): VerifiedRuntimeArtifact {
        if (artifact.version != manifest.version || artifact.size != manifest.runtime.size ||
            artifact.sha256 != manifest.runtime.sha256 || artifact.sourceUrl != manifest.runtime.url ||
            artifact.notesUrl != manifest.notesUrl
        ) {
            throw IllegalStateException("An immutable runtime release changed after verification")
        }
        return artifact
    }

    private fun writeMetadata(file: File, manifest: ThunderReleaseManifest) {
        FileOutputStream(file).use { fileOutput ->
            val output = DataOutputStream(fileOutput.buffered())
            output.writeInt(METADATA_MAGIC)
            output.writeInt(METADATA_SCHEMA)
            output.writeUTF(manifest.version.toString())
            output.writeLong(manifest.runtime.size)
            output.writeUTF(manifest.runtime.sha256)
            output.writeUTF(manifest.runtime.url.toASCIIString())
            output.writeUTF(manifest.notesUrl.toASCIIString())
            output.flush()
            fileOutput.fd.sync()
        }
    }

    private fun copyAndSync(source: File, destination: File) {
        source.inputStream().buffered().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, 256 * 1024)
                output.fd.sync()
            }
        }
    }

    private fun moveDirectory(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private companion object {
        const val RUNTIME_FILE = "runtime.js"
        const val METADATA_FILE = "release.bin"
        const val METADATA_MAGIC = 0x5452554E
        const val METADATA_SCHEMA = 1
        const val MAX_METADATA_BYTES = 16L * 1024L
        val PROCESS_LOCK = Any()
    }
}

class ThunderRuntimeUpdateDownloader(
    private val downloader: VerifiedArtifactDownloader,
    private val downloadDirectory: File,
    private val store: VerifiedRuntimeStore,
) {
    suspend fun downloadAndStore(
        manifest: ThunderReleaseManifest,
        currentVersion: SemanticVersion,
    ): VerifiedRuntimeArtifact {
        require(manifest.version > currentVersion) {
            "Thunder runtime update is not newer than the installed version"
        }
        val stored = store.latest()
        if (stored != null && stored.version >= manifest.version) {
            require(stored.version == manifest.version && stored.matches(manifest)) {
                "Thunder runtime update would replace or downgrade a verified release"
            }
            return stored
        }
        val download = downloader.download(
            manifest.runtime,
            downloadDirectory,
            UpdateLimits.MAX_RUNTIME_BYTES,
        )
        return try {
            store.promote(manifest, download)
        } finally {
            download.file.delete()
        }
    }

    private fun VerifiedRuntimeArtifact.matches(manifest: ThunderReleaseManifest): Boolean =
        version == manifest.version && size == manifest.runtime.size && sha256 == manifest.runtime.sha256 &&
            sourceUrl == manifest.runtime.url && notesUrl == manifest.notesUrl
}
