package dev.thunder.patchdomain

import java.io.File

data class SnapshotProgress(
    val bytesCopied: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes == 0L) 1f else {
            (bytesCopied.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        }
}

data class SnapshotHandle(
    val transactionId: String,
    val packageName: String,
    val artifactCount: Int,
    val totalBytes: Long,
)

data class VerifiedSnapshot(
    val handle: SnapshotHandle,
    val inputFiles: List<File>,
    val signingOutputDirectory: File,
)

data class SnapshotCleanupResult(
    val removedTransactionCount: Int,
    val retainedActiveTransactionCount: Int,
    val failedTransactionCount: Int,
)

enum class SnapshotFailureCode {
    SOURCE_UNREADABLE,
    SOURCE_CHANGED,
    INSUFFICIENT_SPACE,
    WORKSPACE_UNAVAILABLE,
    COPY_FAILED,
    JOURNAL_FAILED,
    CLEANUP_FAILED,
}

class SnapshotException(
    val code: SnapshotFailureCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)

internal enum class SnapshotJournalState {
    PREPARING,
    READY,
    FAILED,
}

internal data class SnapshotArtifactRecord(
    val splitName: String?,
    val outputName: String,
    val size: Long,
    val sha256: String,
)

internal object SnapshotFileNames {
    fun forArtifact(index: Int, isBase: Boolean): String {
        require(index >= 0) { "Artifact index must not be negative" }
        require(isBase == (index == 0)) { "Only artifact zero may be the base APK" }
        return if (isBase) "base.apk" else "split-${index.toString().padStart(3, '0')}.apk"
    }
}
