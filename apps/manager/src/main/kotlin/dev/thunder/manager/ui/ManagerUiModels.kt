package dev.thunder.manager.ui

import dev.thunder.packageinspector.InstalledDiscordTarget

internal sealed interface ManagerInventoryUiState {
    data object Loading : ManagerInventoryUiState
    data object Failed : ManagerInventoryUiState
    data class Ready(
        val targets: List<InstalledDiscordTarget>,
        val clone: ThunderCloneUi?,
    ) : ManagerInventoryUiState
}

internal data class ThunderCloneUi(
    val versionName: String,
    val versionCode: Long,
    val sourcePackageName: String,
    val runtimeVersion: String? = null,
)

internal sealed interface ManagerOperationUiState {
    data object Idle : ManagerOperationUiState
    data class Building(
        val label: String,
        val progress: Float?,
    ) : ManagerOperationUiState

    data object AwaitingAndroid : ManagerOperationUiState
    data class Failed(val message: String) : ManagerOperationUiState
    data class Blocked(val message: String) : ManagerOperationUiState
}

internal enum class ManagerPrimaryAction {
    CHOOSE_APK,
    INJECT,
    UPDATE,
    OPEN,
    RETRY,
    BLOCKED,
    NONE,
}

internal enum class ManagerSecondaryAction {
    REFRESH_THUNDER,
    NONE,
}

internal fun resolvePrimaryAction(
    sourceVersionCode: Long?,
    cloneVersionCode: Long?,
    operation: ManagerOperationUiState,
): ManagerPrimaryAction = when (operation) {
    is ManagerOperationUiState.Building,
    ManagerOperationUiState.AwaitingAndroid,
    -> ManagerPrimaryAction.NONE

    is ManagerOperationUiState.Failed -> ManagerPrimaryAction.RETRY
    is ManagerOperationUiState.Blocked -> ManagerPrimaryAction.BLOCKED
    ManagerOperationUiState.Idle -> when {
        sourceVersionCode == null && cloneVersionCode != null -> ManagerPrimaryAction.OPEN
        sourceVersionCode == null -> ManagerPrimaryAction.CHOOSE_APK
        cloneVersionCode == null -> ManagerPrimaryAction.INJECT
        sourceVersionCode > cloneVersionCode -> ManagerPrimaryAction.UPDATE
        else -> ManagerPrimaryAction.OPEN
    }
}

internal fun resolveSecondaryAction(
    sourceVersionCode: Long?,
    cloneVersionCode: Long?,
    operation: ManagerOperationUiState,
): ManagerSecondaryAction = if (
    operation == ManagerOperationUiState.Idle &&
    sourceVersionCode != null &&
    sourceVersionCode == cloneVersionCode
) {
    ManagerSecondaryAction.REFRESH_THUNDER
} else {
    ManagerSecondaryAction.NONE
}
