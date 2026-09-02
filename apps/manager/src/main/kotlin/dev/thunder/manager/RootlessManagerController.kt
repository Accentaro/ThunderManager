package dev.thunder.manager

import android.content.Context
import dev.thunder.manager.ui.ManagerInventoryUiState
import dev.thunder.manager.ui.ManagerOperationUiState
import dev.thunder.manager.ui.ManagerPrimaryAction
import dev.thunder.manager.ui.ManagerSecondaryAction
import dev.thunder.manager.ui.ThunderCloneUi
import dev.thunder.manager.ui.resolvePrimaryAction
import dev.thunder.manager.ui.resolveSecondaryAction
import dev.thunder.packageinspector.AndroidPackageInventory
import dev.thunder.packageinspector.CloneInstallState
import dev.thunder.packageinspector.InstalledDiscordTarget
import dev.thunder.packageinspector.ThunderCloneCatalog
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
import kotlinx.coroutines.withContext

internal data class ManagerRouteUiState(
    val inventory: ManagerInventoryUiState = ManagerInventoryUiState.Loading,
    val selectedPackageName: String? = null,
    val operation: ManagerOperationUiState = ManagerOperationUiState.Idle,
) {
    val primaryAction: ManagerPrimaryAction
        get() {
            val ready = inventory as? ManagerInventoryUiState.Ready
            val source = ready?.targets?.firstOrNull { it.packageName == selectedPackageName }
                ?: ready?.targets?.firstOrNull()
            val clone = ready?.clone
            if (source != null && clone != null && clone.sourcePackageName != source.packageName) {
                return ManagerPrimaryAction.BLOCKED
            }
            return resolvePrimaryAction(source?.versionCode, clone?.versionCode, operation)
        }

    val secondaryAction: ManagerSecondaryAction
        get() {
            val ready = inventory as? ManagerInventoryUiState.Ready
                ?: return ManagerSecondaryAction.NONE
            val source = ready.targets.firstOrNull { it.packageName == selectedPackageName }
                ?: ready.targets.firstOrNull()
                ?: return ManagerSecondaryAction.NONE
            val clone = ready.clone ?: return ManagerSecondaryAction.NONE
            if (clone.sourcePackageName != source.packageName) return ManagerSecondaryAction.NONE
            return resolveSecondaryAction(source.versionCode, clone.versionCode, operation)
        }
}

internal sealed interface ClonePipelineEvent {
    data class Progress(val label: String, val fraction: Float? = null) : ClonePipelineEvent
    data object AwaitingAndroid : ClonePipelineEvent
}

internal sealed interface CloneUiInspection {
    data object Absent : CloneUiInspection
    data class Trusted(val clone: ThunderCloneUi) : CloneUiInspection
    data class Blocked(val message: String) : CloneUiInspection
}

internal sealed interface VerifiedOpenDecision {
    data class Allow(val clone: ThunderCloneUi) : VerifiedOpenDecision
    data class Block(val message: String) : VerifiedOpenDecision
}

internal fun resolveVerifiedOpen(
    inspection: CloneUiInspection,
    selectedSourcePackageName: String?,
): VerifiedOpenDecision = when (inspection) {
    CloneUiInspection.Absent -> VerifiedOpenDecision.Block(
        "Thunder is no longer installed. Check again before rebuilding it.",
    )
    is CloneUiInspection.Blocked -> VerifiedOpenDecision.Block(inspection.message)
    is CloneUiInspection.Trusted -> if (
        selectedSourcePackageName != null &&
        inspection.clone.sourcePackageName != selectedSourcePackageName
    ) {
        VerifiedOpenDecision.Block(
            "The installed Thunder app no longer matches the selected Discord source. Check again.",
        )
    } else {
        VerifiedOpenDecision.Allow(inspection.clone)
    }
}

internal interface RootlessClonePipeline {
    suspend fun inspectClone(cloneState: CloneInstallState): CloneUiInspection

    suspend fun install(
        source: InstalledDiscordTarget,
        onEvent: suspend (ClonePipelineEvent) -> Unit,
    )

    suspend fun recoverPendingInstall(onEvent: suspend (ClonePipelineEvent) -> Unit): Boolean
}

internal class RootlessManagerController(
    context: Context,
    private val pipeline: RootlessClonePipeline,
) {
    private val applicationContext = context.applicationContext
    private val inventory = AndroidPackageInventory(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ManagerRouteUiState())
    val state: StateFlow<ManagerRouteUiState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null
    private var installJob: Job? = null
    private var openVerificationJob: Job? = null

    init {
        installJob = scope.launch {
            runCatching { pipeline.recoverPendingInstall(::publishEvent) }
            installJob = null
            refresh()
        }
    }

    fun refresh() {
        if (installJob?.isActive == true || openVerificationJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val previous = mutableState.value
            mutableState.value = previous.copy(inventory = ManagerInventoryUiState.Loading)
            runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = inventory.scan()
                    snapshot to pipeline.inspectClone(snapshot.clone)
                }
            }.onSuccess { (snapshot, cloneInspection) ->
                val targets = snapshot.targets
                val clone = (cloneInspection as? CloneUiInspection.Trusted)?.clone
                val selected = clone?.sourcePackageName
                    ?.takeIf { sourceName -> targets.any { it.packageName == sourceName } }
                    ?: previous.selectedPackageName
                    ?.takeIf { selectedName -> targets.any { it.packageName == selectedName } }
                    ?: targets.firstOrNull()?.packageName
                mutableState.value = previous.copy(
                    inventory = ManagerInventoryUiState.Ready(targets, clone),
                    selectedPackageName = selected,
                    operation = when (cloneInspection) {
                        CloneUiInspection.Absent,
                        is CloneUiInspection.Trusted,
                        -> ManagerOperationUiState.Idle
                        is CloneUiInspection.Blocked -> ManagerOperationUiState.Blocked(cloneInspection.message)
                    },
                )
            }.onFailure {
                mutableState.value = previous.copy(inventory = ManagerInventoryUiState.Failed)
            }
        }
    }

    fun select(target: InstalledDiscordTarget) {
        if (installJob?.isActive == true || openVerificationJob?.isActive == true) return
        val clone = (mutableState.value.inventory as? ManagerInventoryUiState.Ready)?.clone
        if (clone != null && clone.sourcePackageName != target.packageName) {
            mutableState.update {
                it.copy(
                    operation = ManagerOperationUiState.Blocked(
                        "This Thunder app follows ${clone.sourcePackageName}. Select that Discord source to update it safely.",
                    ),
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                selectedPackageName = target.packageName,
                operation = ManagerOperationUiState.Idle,
            )
        }
    }

    fun installSelected() {
        if (installJob?.isActive == true || openVerificationJob?.isActive == true) return
        val current = mutableState.value
        val ready = current.inventory as? ManagerInventoryUiState.Ready ?: return
        val source = ready.targets.firstOrNull { it.packageName == current.selectedPackageName }
            ?: ready.targets.firstOrNull()
            ?: return

        installJob = scope.launch {
            mutableState.update {
                it.copy(operation = ManagerOperationUiState.Building("Copying Discord safely…", 0f))
            }
            runCatching { pipeline.install(source, ::publishEvent) }
                .onSuccess {
                    mutableState.update { it.copy(operation = ManagerOperationUiState.Idle) }
                    installJob = null
                    refresh()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(operation = ManagerOperationUiState.Failed(error.userMessage()))
                    }
                    installJob = null
                }
        }
    }

    fun verifyCloneForOpen(onVerified: () -> Unit) {
        if (installJob?.isActive == true || openVerificationJob?.isActive == true) return
        refreshJob?.cancel()

        val current = mutableState.value
        val ready = current.inventory as? ManagerInventoryUiState.Ready
        val selectedSourcePackageName = ready?.targets
            ?.firstOrNull { it.packageName == current.selectedPackageName }
            ?.packageName
            ?: ready?.targets?.firstOrNull()?.packageName

        openVerificationJob = scope.launch {
            try {
                mutableState.update {
                    it.copy(
                        operation = ManagerOperationUiState.Building(
                            "Checking Thunder before opening…",
                            null,
                        ),
                    )
                }
                val inspection = try {
                    withContext(Dispatchers.IO) {
                        pipeline.inspectClone(inventory.inspectClone())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    CloneUiInspection.Blocked(
                        "Thunder could not be verified just now. Check again before opening it.",
                    )
                }

                when (val decision = resolveVerifiedOpen(inspection, selectedSourcePackageName)) {
                    is VerifiedOpenDecision.Allow -> {
                        mutableState.update { state ->
                            val currentReady = state.inventory as? ManagerInventoryUiState.Ready
                            state.copy(
                                inventory = currentReady?.copy(clone = decision.clone) ?: state.inventory,
                                operation = ManagerOperationUiState.Idle,
                            )
                        }
                        onVerified()
                    }
                    is VerifiedOpenDecision.Block -> {
                        mutableState.update { state ->
                            val currentReady = state.inventory as? ManagerInventoryUiState.Ready
                            state.copy(
                                inventory = currentReady?.copy(clone = null) ?: state.inventory,
                                operation = ManagerOperationUiState.Blocked(decision.message),
                            )
                        }
                    }
                }
            } finally {
                openVerificationJob = null
            }
        }
    }

    fun retry() {
        mutableState.update { it.copy(operation = ManagerOperationUiState.Idle) }
        installSelected()
    }

    fun reportInstallPermissionDenied() {
        mutableState.update {
            it.copy(
                operation = ManagerOperationUiState.Failed(
                    "Allow Thunder to ask Android to install the separate Thunder app, then try again.",
                ),
            )
        }
    }

    fun reportError(message: String) {
        mutableState.update { it.copy(operation = ManagerOperationUiState.Failed(message)) }
    }

    private suspend fun publishEvent(event: ClonePipelineEvent) {
        mutableState.update {
            it.copy(
                operation = when (event) {
                    is ClonePipelineEvent.Progress -> ManagerOperationUiState.Building(event.label, event.fraction)
                    ClonePipelineEvent.AwaitingAndroid -> ManagerOperationUiState.AwaitingAndroid
                },
            )
        }
    }

    private fun Throwable.userMessage(): String = message
        ?.takeIf { it.isNotBlank() && it.length <= 220 }
        ?: "Thunder could not finish this build. Official Discord was not changed."
}

internal const val THUNDER_PACKAGE_NAME = ThunderCloneCatalog.OUTPUT_PACKAGE_NAME
