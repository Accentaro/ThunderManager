package dev.thunder.patchdomain

import android.content.Context
import android.util.AtomicFile
import dev.thunder.packageinspector.InstalledDiscordTarget
import dev.thunder.packageinspector.InstalledThunderClone
import dev.thunder.packageinspector.PackageArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class ApkSnapshotStore internal constructor(
    private val root: File,
    private val ownerToken: String,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, "transactions"),
        PROCESS_OWNER_TOKEN,
    )

    suspend fun prepare(
        target: InstalledDiscordTarget,
        onProgress: suspend (SnapshotProgress) -> Unit = {},
    ): SnapshotHandle = prepare(target.asSnapshotTarget(), onProgress)

    suspend fun prepare(
        target: InstalledThunderClone,
        onProgress: suspend (SnapshotProgress) -> Unit = {},
    ): SnapshotHandle = prepare(target.asSnapshotTarget(), onProgress)

    private suspend fun prepare(
        target: SnapshotTarget,
        onProgress: suspend (SnapshotProgress) -> Unit,
    ): SnapshotHandle = withContext(Dispatchers.IO) {
        val transactionId = UUID.randomUUID().toString()
        val workspace = transactionDirectory(transactionId)
        val inputDirectory = File(workspace, "input")
        val sources = validateSources(target)
        val totalBytes = sources.sumOf(File::length)
        val requiredFreeBytes = totalBytes + maxOf(totalBytes / 10, MINIMUM_FREE_MARGIN_BYTES)

        if (root.parentFile?.usableSpace?.let { it < requiredFreeBytes } != false) {
            throw SnapshotException(SnapshotFailureCode.INSUFFICIENT_SPACE)
        }
        if (!inputDirectory.mkdirs()) {
            throw SnapshotException(SnapshotFailureCode.WORKSPACE_UNAVAILABLE)
        }

        val records = mutableListOf<SnapshotArtifactRecord>()
        var bytesCopied = 0L
        var nextProgressReport = PROGRESS_INTERVAL_BYTES

        try {
            writeJournal(
                workspace = workspace,
                target = target,
                state = SnapshotJournalState.PREPARING,
                totalBytes = totalBytes,
                records = records,
            )

            target.artifacts.forEachIndexed { index, artifact ->
                currentCoroutineContext().ensureActive()
                val source = sources[index]
                val outputName = SnapshotFileNames.forArtifact(index, artifact.isBase)
                val destination = File(inputDirectory, outputName)
                val digest = MessageDigest.getInstance("SHA-256")
                val expectedLength = source.length()

                try {
                    FileInputStream(source).use { input ->
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                bytesCopied += count
                                if (bytesCopied >= nextProgressReport || bytesCopied == totalBytes) {
                                    onProgress(SnapshotProgress(bytesCopied, totalBytes))
                                    nextProgressReport = bytesCopied + PROGRESS_INTERVAL_BYTES
                                }
                            }
                            output.fd.sync()
                        }
                    }
                } catch (error: IOException) {
                    throw SnapshotException(SnapshotFailureCode.COPY_FAILED, error)
                }

                if (source.length() != expectedLength || destination.length() != expectedLength) {
                    throw SnapshotException(SnapshotFailureCode.SOURCE_CHANGED)
                }

                records += SnapshotArtifactRecord(
                    splitName = artifact.splitName,
                    outputName = outputName,
                    size = expectedLength,
                    sha256 = digest.digest().toHex(),
                )
                writeJournal(
                    workspace = workspace,
                    target = target,
                    state = SnapshotJournalState.PREPARING,
                    totalBytes = totalBytes,
                    records = records,
                )
            }

            writeJournal(
                workspace = workspace,
                target = target,
                state = SnapshotJournalState.READY,
                totalBytes = totalBytes,
                records = records,
            )

            SnapshotHandle(
                transactionId = transactionId,
                packageName = target.packageName,
                artifactCount = records.size,
                totalBytes = totalBytes,
            )
        } catch (cancelled: CancellationException) {
            deleteWorkspace(workspace)
            throw cancelled
        } catch (error: SnapshotException) {
            inputDirectory.deleteRecursively()
            runCatching {
                writeJournal(
                    workspace = workspace,
                    target = target,
                    state = SnapshotJournalState.FAILED,
                    totalBytes = totalBytes,
                    records = records,
                    failureCode = error.code,
                )
            }
            throw error
        } catch (error: IOException) {
            inputDirectory.deleteRecursively()
            throw SnapshotException(SnapshotFailureCode.JOURNAL_FAILED, error)
        }
    }

    suspend fun discard(handle: SnapshotHandle) = withContext(Dispatchers.IO) {
        val workspace = transactionDirectory(handle.transactionId)
        if (workspace.exists() && !deleteWorkspace(workspace)) {
            throw SnapshotException(SnapshotFailureCode.CLEANUP_FAILED)
        }
    }

    suspend fun reapAbandonedTransactions(): SnapshotCleanupResult = withContext(Dispatchers.IO) {
        if (!root.exists()) {
            return@withContext SnapshotCleanupResult(0, 0, 0)
        }

        val canonicalRoot = try {
            root.canonicalFile
        } catch (error: IOException) {
            throw SnapshotException(SnapshotFailureCode.CLEANUP_FAILED, error)
        }
        var removed = 0
        var retained = 0
        var failed = 0
        root.listFiles().orEmpty().forEach { candidate ->
            if (!candidate.isDirectory || normalizedTransactionId(candidate.name) == null) return@forEach
            val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrElse {
                failed++
                return@forEach
            }
            if (canonicalCandidate.parentFile != canonicalRoot) {
                failed++
                return@forEach
            }
            if (isOwnedByCurrentProcess(candidate)) {
                retained++
                return@forEach
            }
            if (deleteWorkspace(candidate)) removed++ else failed++
        }
        SnapshotCleanupResult(removed, retained, failed)
    }

    suspend fun verify(handle: SnapshotHandle): VerifiedSnapshot = withContext(Dispatchers.IO) {
        val workspace = transactionDirectory(handle.transactionId)
        val journalFile = File(workspace, JOURNAL_FILE_NAME)
        if (!journalFile.isFile || journalFile.length() !in 1..MAX_JOURNAL_BYTES) {
            throw SnapshotException(SnapshotFailureCode.SOURCE_UNREADABLE)
        }

        try {
            val journal = JSONObject(journalFile.readText(Charsets.UTF_8))
            if (journal.getInt("schemaVersion") != JOURNAL_SCHEMA_VERSION
                || journal.getString("transactionId") != handle.transactionId
                || journal.getString("packageName") != handle.packageName
                || journal.getString("state") != SnapshotJournalState.READY.name.lowercase()
                || journal.getInt("artifactCount") != handle.artifactCount
                || journal.getInt("completedArtifactCount") != handle.artifactCount
                || journal.getLong("totalBytes") != handle.totalBytes
            ) {
                throw SnapshotException(SnapshotFailureCode.SOURCE_CHANGED)
            }

            val inputDirectory = File(workspace, "input")
            val records = journal.getJSONArray("artifacts")
            if (records.length() != handle.artifactCount) {
                throw SnapshotException(SnapshotFailureCode.SOURCE_CHANGED)
            }
            val inputFiles = buildList {
                repeat(records.length()) { index ->
                    val record = records.getJSONObject(index)
                    val expectedName = SnapshotFileNames.forArtifact(index, isBase = index == 0)
                    if (record.getString("outputName") != expectedName) {
                        throw SnapshotException(SnapshotFailureCode.SOURCE_CHANGED)
                    }
                    val input = File(inputDirectory, expectedName)
                    if (input.parentFile?.canonicalFile != inputDirectory.canonicalFile
                        || !input.isFile
                        || input.length() != record.getLong("size")
                        || sha256(input) != record.getString("sha256")
                    ) {
                        throw SnapshotException(SnapshotFailureCode.SOURCE_CHANGED)
                    }
                    add(input)
                }
            }
            if (inputDirectory.listFiles()?.filter(File::isFile)?.size != inputFiles.size) {
                throw SnapshotException(SnapshotFailureCode.SOURCE_CHANGED)
            }

            VerifiedSnapshot(
                handle = handle,
                inputFiles = inputFiles,
                signingOutputDirectory = File(workspace, "signed"),
            )
        } catch (error: SnapshotException) {
            throw error
        } catch (error: JSONException) {
            throw SnapshotException(SnapshotFailureCode.SOURCE_CHANGED, error)
        } catch (error: IOException) {
            throw SnapshotException(SnapshotFailureCode.SOURCE_UNREADABLE, error)
        }
    }

    private fun validateSources(target: SnapshotTarget): List<File> = target.artifacts.map { artifact ->
        val source = File(artifact.sourcePath)
        if (!source.isFile || !source.canRead() || source.length() <= 0L) {
            throw SnapshotException(SnapshotFailureCode.SOURCE_UNREADABLE)
        }
        source
    }

    private fun transactionDirectory(transactionId: String): File {
        val normalizedId = normalizedTransactionId(transactionId)
            ?: throw SnapshotException(SnapshotFailureCode.WORKSPACE_UNAVAILABLE)
        return File(root, normalizedId)
    }

    private fun normalizedTransactionId(value: String): String? = try {
        UUID.fromString(value).toString().takeIf { it == value }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isOwnedByCurrentProcess(workspace: File): Boolean {
        val journal = File(workspace, JOURNAL_FILE_NAME)
        if (!journal.isFile || journal.length() !in 1..MAX_JOURNAL_BYTES) return false
        return try {
            val payload = journal.readText(Charsets.UTF_8)
            OWNER_TOKEN_PATTERN.find(payload)?.groupValues?.get(1) == ownerToken
        } catch (_: IOException) {
            false
        }
    }

    private fun writeJournal(
        workspace: File,
        target: SnapshotTarget,
        state: SnapshotJournalState,
        totalBytes: Long,
        records: List<SnapshotArtifactRecord>,
        failureCode: SnapshotFailureCode? = null,
    ) {
        if (!workspace.isDirectory && !workspace.mkdirs()) {
            throw IOException("Unable to create transaction workspace")
        }

        val artifacts = JSONArray().apply {
            records.forEach { record ->
                put(JSONObject().apply {
                    put("splitName", record.splitName ?: JSONObject.NULL)
                    put("outputName", record.outputName)
                    put("size", record.size)
                    put("sha256", record.sha256)
                })
            }
        }
        val payload = JSONObject().apply {
            put("schemaVersion", JOURNAL_SCHEMA_VERSION)
            put("transactionId", workspace.name)
            put("ownerToken", ownerToken)
            put("state", state.name.lowercase())
            put("packageName", target.packageName)
            put("versionCode", target.versionCode)
            put("artifactCount", target.artifacts.size)
            put("completedArtifactCount", records.size)
            put("totalBytes", totalBytes)
            put("currentSignerSha256", JSONArray(target.currentSignerSha256))
            put("artifacts", artifacts)
            if (failureCode != null) put("failureCode", failureCode.name)
        }.toString()

        val journal = File(workspace, JOURNAL_FILE_NAME)
        val atomicFile = AtomicFile(journal)
        val output = atomicFile.startWrite()
        try {
            output.write(payload.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun deleteWorkspace(workspace: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val workspacePath = workspace.canonicalFile.toPath()
        if (workspacePath.parent != rootPath) return false
        return workspace.deleteRecursively()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private companion object {
        const val JOURNAL_SCHEMA_VERSION = 1
        const val JOURNAL_FILE_NAME = "journal.json"
        const val MAX_JOURNAL_BYTES = 64L * 1024
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val PROGRESS_INTERVAL_BYTES = 4L * 1024 * 1024
        const val MINIMUM_FREE_MARGIN_BYTES = 64L * 1024 * 1024
        val PROCESS_OWNER_TOKEN: String = UUID.randomUUID().toString()
        val OWNER_TOKEN_PATTERN = Regex("\\\"ownerToken\\\":\\\"([0-9a-f-]{36})\\\"")
    }

    private data class SnapshotTarget(
        val packageName: String,
        val versionCode: Long,
        val artifacts: List<PackageArtifact>,
        val currentSignerSha256: List<String>,
    )

    private fun InstalledDiscordTarget.asSnapshotTarget() = SnapshotTarget(
        packageName = packageName,
        versionCode = versionCode,
        artifacts = artifacts,
        currentSignerSha256 = currentSignerSha256,
    )

    private fun InstalledThunderClone.asSnapshotTarget() = SnapshotTarget(
        packageName = packageName,
        versionCode = versionCode,
        artifacts = artifacts,
        currentSignerSha256 = currentSignerSha256,
    )
}
