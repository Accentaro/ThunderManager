package dev.thunder.updateclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class ReleaseCheckSource {
    CACHE,
    NETWORK,
}

data class CheckedRelease(
    val manifest: StableReleaseManifest,
    val source: ReleaseCheckSource,
) {
    fun isNewerThan(installedVersion: SemanticVersion): Boolean = manifest.version > installedVersion
}

class ReleaseCheckCache(private val directory: File) {
    fun readFresh(product: ReleaseProduct, nowEpochMillis: Long): ByteArray? = synchronized(PROCESS_LOCK) {
        val file = file(product)
        if (!file.isFile || file.length() !in 1..MAX_CACHE_FILE_BYTES) return@synchronized null
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_MAGIC || input.readInt() != CACHE_SCHEMA) return@synchronized null
                val checkedAt = input.readLong()
                val age = nowEpochMillis - checkedAt
                if (checkedAt < 0L || age !in 0 until CACHE_TTL_MILLIS) return@synchronized null
                val size = input.readInt()
                if (size !in 1..UpdateLimits.MAX_MANIFEST_BYTES) return@synchronized null
                val payload = ByteArray(size)
                input.readFully(payload)
                if (input.read() != -1) return@synchronized null
                payload
            }
        } catch (_: Exception) {
            null
        }
    }

    fun writeSuccessful(product: ReleaseProduct, checkedAtEpochMillis: Long, manifestBytes: ByteArray) =
        synchronized(PROCESS_LOCK) {
            require(checkedAtEpochMillis >= 0L) { "Cache timestamp is invalid" }
            require(manifestBytes.size in 1..UpdateLimits.MAX_MANIFEST_BYTES) { "Manifest size is invalid" }
            if (!directory.isDirectory && !directory.mkdirs()) throw IllegalStateException("Could not create update cache")
            val payload = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(CACHE_MAGIC)
                    output.writeInt(CACHE_SCHEMA)
                    output.writeLong(checkedAtEpochMillis)
                    output.writeInt(manifestBytes.size)
                    output.write(manifestBytes)
                }
                bytes.toByteArray()
            }
            atomicWrite(file(product), payload)
        }

    private fun file(product: ReleaseProduct): File = File(
        directory,
        when (product) {
            ReleaseProduct.THUNDER -> "thunder-release.cache"
            ReleaseProduct.THUNDER_MANAGER -> "manager-release.cache"
        },
    )

    private fun atomicWrite(destination: File, payload: ByteArray) {
        val temporary = File(directory, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                ByteArrayInputStream(payload).copyTo(output)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val CACHE_TTL_MILLIS = 6L * 60L * 60L * 1_000L
        private const val CACHE_MAGIC = 0x5448554E
        private const val CACHE_SCHEMA = 1
        private const val MAX_CACHE_FILE_BYTES = UpdateLimits.MAX_MANIFEST_BYTES + 64L
        private val PROCESS_LOCK = Any()
    }
}

class StableReleaseChecker(
    private val fetcher: ReleaseBytesFetcher,
    private val cache: ReleaseCheckCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun check(product: ReleaseProduct, forceRefresh: Boolean = false): CheckedRelease =
        withContext(Dispatchers.IO) {
            val now = clock()
            if (!forceRefresh) {
                val cached = cache.readFresh(product, now)
                if (cached != null) {
                    runCatching { ReleaseManifestParser.parse(product, cached) }
                        .getOrNull()
                        ?.let { return@withContext CheckedRelease(it, ReleaseCheckSource.CACHE) }
                }
            }

            val bytes = fetcher.fetch(product.manifestUrl, UpdateLimits.MAX_MANIFEST_BYTES)
            val manifest = ReleaseManifestParser.parse(product, bytes)
            cache.writeSuccessful(product, clock(), bytes)
            CheckedRelease(manifest, ReleaseCheckSource.NETWORK)
        }
}
