package dev.thunder.manager

import android.content.Context
import dev.thunder.updateclient.ManagerUpdateDownloadState
import dev.thunder.updateclient.ManagerUpdateCandidate
import dev.thunder.updateclient.SemanticVersion
import dev.thunder.updateclient.ThunderManagerReleaseManifest
import dev.thunder.updateclient.ThunderReleaseManifest
import dev.thunder.updateclient.VerifiedRuntimeArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface ReleaseAvailability<out T> {
    data object NotChecked : ReleaseAvailability<Nothing>
    data class Checking(val manuallyRequested: Boolean) : ReleaseAvailability<Nothing>
    data object Current : ReleaseAvailability<Nothing>
    data class Available<T>(val manifest: T) : ReleaseAvailability<T>
    data class Failed(val manuallyRequested: Boolean) : ReleaseAvailability<Nothing>
}

internal sealed interface ThunderRuntimeDownloadState {
    data object Idle : ThunderRuntimeDownloadState
    data class Downloading(val version: SemanticVersion) : ThunderRuntimeDownloadState
    data class VerifiedReady(val artifact: VerifiedRuntimeArtifact) : ThunderRuntimeDownloadState
    data class Failed(val version: SemanticVersion) : ThunderRuntimeDownloadState
}

internal data class ManagerUpdatesUiState(
    val thunder: ReleaseAvailability<ThunderReleaseManifest> = ReleaseAvailability.NotChecked,
    val manager: ReleaseAvailability<ThunderManagerReleaseManifest> = ReleaseAvailability.NotChecked,
    val thunderDownload: ThunderRuntimeDownloadState = ThunderRuntimeDownloadState.Idle,
)

/** Non-blocking lifecycle/state wrapper around [ManagerUpdateInfrastructure]. */
internal class ManagerUpdateController internal constructor(
    private val gateway: ManagerUpdateGateway,
    private val scope: CoroutineScope,
) {
    constructor(context: Context) : this(
        ManagerUpdateInfrastructure(context.applicationContext),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val mutableState = MutableStateFlow(ManagerUpdatesUiState())
    val state: StateFlow<ManagerUpdatesUiState> = mutableState.asStateFlow()
    val managerDownloadState: StateFlow<ManagerUpdateDownloadState> = gateway.managerDownloadState

    private var thunderCheckJob: Job? = null
    private var managerCheckJob: Job? = null
    private var thunderDownloadJob: Job? = null
    private var managerDownloadJob: Job? = null

    fun checkAutomatically(installedRuntimeVersion: String?) {
        val installed = installedRuntimeVersion.parseStableOrNull()
        if (thunderCheckJob?.isActive != true) {
            thunderCheckJob = scope.launch {
                try {
                    performThunderCheck(forceRefresh = false, installed, manuallyRequested = false)
                } finally {
                    thunderCheckJob = null
                }
            }
        }
        if (managerCheckJob?.isActive != true) {
            managerCheckJob = scope.launch {
                try {
                    performManagerCheck(forceRefresh = false, manuallyRequested = false)
                } finally {
                    managerCheckJob = null
                }
            }
        }
    }

    fun checkManually(installedRuntimeVersion: String?) {
        val installed = installedRuntimeVersion.parseStableOrNull()
        thunderCheckJob?.cancel()
        managerCheckJob?.cancel()
        thunderCheckJob = scope.launch {
            try {
                performThunderCheck(forceRefresh = true, installed, manuallyRequested = true)
            } finally {
                thunderCheckJob = null
            }
        }
        managerCheckJob = scope.launch {
            try {
                performManagerCheck(forceRefresh = true, manuallyRequested = true)
            } finally {
                managerCheckJob = null
            }
        }
    }

    fun downloadThunder(
        manifest: ThunderReleaseManifest,
        installedRuntimeVersion: String?,
        onVerifiedReady: (VerifiedRuntimeArtifact) -> Unit,
    ) {
        if (thunderDownloadJob?.isActive == true) return
        val installed = installedRuntimeVersion.parseStableOrNull()
        thunderDownloadJob = scope.launch {
            try {
                val artifact = performThunderDownload(manifest, installed)
                onVerifiedReady(artifact)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // performThunderDownload already published a non-destructive failure state.
            } finally {
                thunderDownloadJob = null
            }
        }
    }

    fun downloadManager(
        manifest: ThunderManagerReleaseManifest,
        onVerifiedReady: (ManagerUpdateCandidate) -> Unit,
    ) {
        if (managerDownloadJob?.isActive == true) return
        managerDownloadJob = scope.launch {
            try {
                val candidate = gateway.downloadManager(manifest)
                onVerifiedReady(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The gateway's separately exposed download state carries the safe failure reason.
            } finally {
                managerDownloadJob = null
            }
        }
    }

    fun clearManagerDownload() {
        if (managerDownloadJob?.isActive == true) return
        scope.launch { gateway.clearManagerDownload() }
    }

    fun clearThunderDownloadState() {
        if (thunderDownloadJob?.isActive != true) {
            mutableState.update { it.copy(thunderDownload = ThunderRuntimeDownloadState.Idle) }
        }
    }

    internal suspend fun performThunderCheck(
        forceRefresh: Boolean,
        installedRuntimeVersion: SemanticVersion?,
        manuallyRequested: Boolean,
    ) {
        mutableState.update {
            it.copy(thunder = ReleaseAvailability.Checking(manuallyRequested))
        }
        try {
            val result = gateway.checkThunder(forceRefresh)
            val current = installedRuntimeVersion ?: gateway.bundledRuntimeVersion
            mutableState.update {
                it.copy(
                    thunder = if (result.isNewerThan(current)) {
                        ReleaseAvailability.Available(result.manifest)
                    } else {
                        ReleaseAvailability.Current
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update {
                it.copy(thunder = ReleaseAvailability.Failed(manuallyRequested))
            }
        }
    }

    internal suspend fun performManagerCheck(forceRefresh: Boolean, manuallyRequested: Boolean) {
        mutableState.update {
            it.copy(manager = ReleaseAvailability.Checking(manuallyRequested))
        }
        try {
            val result = gateway.checkManager(forceRefresh)
            mutableState.update {
                it.copy(
                    manager = if (result.isNewerThan(gateway.managerVersion)) {
                        ReleaseAvailability.Available(result.manifest)
                    } else {
                        ReleaseAvailability.Current
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update {
                it.copy(manager = ReleaseAvailability.Failed(manuallyRequested))
            }
        }
    }

    internal suspend fun performThunderDownload(
        manifest: ThunderReleaseManifest,
        installedRuntimeVersion: SemanticVersion?,
    ): VerifiedRuntimeArtifact {
        mutableState.update {
            it.copy(thunderDownload = ThunderRuntimeDownloadState.Downloading(manifest.version))
        }
        return try {
            gateway.downloadThunder(manifest, installedRuntimeVersion).also { artifact ->
                mutableState.update {
                    it.copy(thunderDownload = ThunderRuntimeDownloadState.VerifiedReady(artifact))
                }
            }
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(thunderDownload = ThunderRuntimeDownloadState.Idle) }
            throw cancelled
        } catch (error: Exception) {
            mutableState.update {
                it.copy(thunderDownload = ThunderRuntimeDownloadState.Failed(manifest.version))
            }
            throw error
        }
    }

    private fun String?.parseStableOrNull(): SemanticVersion? = this?.let { value ->
        runCatching { SemanticVersion.parseStableRelease(value) }.getOrNull()
    }
}
