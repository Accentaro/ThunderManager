package dev.thunder.updateclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class ManagerUpdateCandidate(
    val manifest: ThunderManagerReleaseManifest,
    val apk: File,
) {
    fun verifyIntegrity() {
        ArtifactIntegrity.requireMatches(apk, manifest.apk, UpdateLimits.MAX_MANAGER_APK_BYTES)
    }
}

sealed interface ManagerUpdateDownloadState {
    data object Idle : ManagerUpdateDownloadState
    data class Downloading(val version: SemanticVersion) : ManagerUpdateDownloadState
    data class Ready(val candidate: ManagerUpdateCandidate) : ManagerUpdateDownloadState
    data class Failed(
        val version: SemanticVersion,
        val reason: ManagerUpdateFailure,
    ) : ManagerUpdateDownloadState
}

enum class ManagerUpdateFailure {
    NOT_NEWER,
    DOWNLOAD_FAILED,
    INTEGRITY_FAILED,
}

class ManagerSelfUpdateDownloader(
    private val downloader: VerifiedArtifactDownloader,
    private val downloadDirectory: File,
    private val currentVersion: SemanticVersion,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<ManagerUpdateDownloadState>(ManagerUpdateDownloadState.Idle)
    val state: StateFlow<ManagerUpdateDownloadState> = mutableState.asStateFlow()

    suspend fun download(manifest: ThunderManagerReleaseManifest): ManagerUpdateCandidate = mutex.withLock {
        if (manifest.version <= currentVersion) {
            mutableState.value = ManagerUpdateDownloadState.Failed(
                manifest.version,
                ManagerUpdateFailure.NOT_NEWER,
            )
            throw IllegalArgumentException("Manager update is not newer than the installed version")
        }

        (mutableState.value as? ManagerUpdateDownloadState.Ready)?.candidate?.apk?.delete()
        mutableState.value = ManagerUpdateDownloadState.Downloading(manifest.version)
        try {
            val verified = downloader.download(
                manifest.apk,
                downloadDirectory,
                UpdateLimits.MAX_MANAGER_APK_BYTES,
            )
            val candidate = ManagerUpdateCandidate(manifest, verified.file)
            try {
                candidate.verifyIntegrity()
            } catch (error: Exception) {
                candidate.apk.delete()
                mutableState.value = ManagerUpdateDownloadState.Failed(
                    manifest.version,
                    ManagerUpdateFailure.INTEGRITY_FAILED,
                )
                throw error
            }
            mutableState.value = ManagerUpdateDownloadState.Ready(candidate)
            candidate
        } catch (cancelled: CancellationException) {
            mutableState.value = ManagerUpdateDownloadState.Idle
            throw cancelled
        } catch (error: Exception) {
            if (mutableState.value !is ManagerUpdateDownloadState.Failed) {
                mutableState.value = ManagerUpdateDownloadState.Failed(
                    manifest.version,
                    ManagerUpdateFailure.DOWNLOAD_FAILED,
                )
            }
            throw error
        }
    }

    suspend fun clear() = mutex.withLock {
        (mutableState.value as? ManagerUpdateDownloadState.Ready)?.candidate?.apk?.delete()
        mutableState.value = ManagerUpdateDownloadState.Idle
    }
}
