package dev.thunder.packageinstaller

import android.content.pm.PackageInstaller
import java.io.File

data class InstallArtifact(
    val file: File,
    val sessionName: String,
)

data class StagedInstall(
    val sessionId: Int,
    val packageName: String,
    val artifactCount: Int,
    val totalBytes: Long,
    val createdAtEpochMillis: Long,
)

enum class InstallSessionState {
    STAGING,
    STAGED,
    COMMIT_REQUESTED,
    USER_ACTION_REQUIRED,
    SUCCEEDED,
    FAILED,
    ABANDONED,
}

enum class InstallRecoveryAction {
    ABANDON,
    COMMIT,
    RECOMMIT,
    COMPLETE,
    IGNORE,
}

object InstallRecoveryPolicy {
    fun actionFor(state: InstallSessionState): InstallRecoveryAction = when (state) {
        InstallSessionState.STAGING -> InstallRecoveryAction.ABANDON
        InstallSessionState.STAGED -> InstallRecoveryAction.COMMIT
        InstallSessionState.COMMIT_REQUESTED,
        InstallSessionState.USER_ACTION_REQUIRED,
        -> InstallRecoveryAction.RECOMMIT
        InstallSessionState.SUCCEEDED -> InstallRecoveryAction.COMPLETE
        InstallSessionState.FAILED,
        InstallSessionState.ABANDONED,
        -> InstallRecoveryAction.IGNORE
    }
}

data class InstallSessionRecord(
    val stagedInstall: StagedInstall,
    val state: InstallSessionState,
    val statusCode: Int? = null,
    val statusMessage: String? = null,
    val updatedAtEpochMillis: Long,
) {
    /** Turns Android's install status into something a person can act on. */
    fun describeOutcome(): String {
        val reason = when (statusCode) {
            PackageInstaller.STATUS_SUCCESS -> return "Installed."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "The Android confirmation was dismissed."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "It conflicts with another installed package. Thunder left the installed apps unchanged."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Android rejected the package as incompatible."
            PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the package as invalid."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "There is not enough free storage."
            else -> "Android reported an unspecified install failure."
        }
        return statusMessage?.takeIf { it.isNotBlank() }?.let { "$reason ($it)" } ?: reason
    }
}

enum class InstallFailureCode {
    INVALID_PACKAGE_NAME,
    INVALID_ARTIFACT_SET,
    ARTIFACT_UNREADABLE,
    SESSION_CREATE_FAILED,
    SESSION_WRITE_FAILED,
    SESSION_NOT_OWNED,
    SESSION_COMMIT_FAILED,
    SESSION_ABANDON_FAILED,
    JOURNAL_FAILED,
}

class InstallException(
    val code: InstallFailureCode,
    cause: Throwable? = null,
) : Exception(code.name, cause)

internal object InstallInputRules {
    private val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val artifactNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,126}\\.apk$")

    fun requirePackageName(packageName: String) {
        if (!packageNamePattern.matches(packageName)) {
            throw InstallException(InstallFailureCode.INVALID_PACKAGE_NAME)
        }
    }

    fun requireArtifacts(artifacts: List<InstallArtifact>): Long {
        if (artifacts.isEmpty() || artifacts.size > 256) {
            throw InstallException(InstallFailureCode.INVALID_ARTIFACT_SET)
        }

        val names = HashSet<String>(artifacts.size)
        for (artifact in artifacts) {
            if (!artifactNamePattern.matches(artifact.sessionName) || !names.add(artifact.sessionName)) {
                throw InstallException(InstallFailureCode.INVALID_ARTIFACT_SET)
            }
        }

        var totalBytes = 0L
        for (artifact in artifacts) {
            if (!artifact.file.isFile || !artifact.file.canRead() || artifact.file.length() <= 0L) {
                throw InstallException(InstallFailureCode.ARTIFACT_UNREADABLE)
            }
            try {
                totalBytes = Math.addExact(totalBytes, artifact.file.length())
            } catch (error: ArithmeticException) {
                throw InstallException(InstallFailureCode.INVALID_ARTIFACT_SET, error)
            }
        }
        return totalBytes
    }
}
