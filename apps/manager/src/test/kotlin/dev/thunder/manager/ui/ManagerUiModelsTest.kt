package dev.thunder.manager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagerUiModelsTest {
    @Test
    fun noDiscordOffersApkPicker() {
        assertEquals(
            ManagerPrimaryAction.CHOOSE_APK,
            resolvePrimaryAction(null, null, ManagerOperationUiState.Idle),
        )
    }

    @Test
    fun officialDiscordCanCreateFreshClone() {
        assertEquals(
            ManagerPrimaryAction.INJECT,
            resolvePrimaryAction(344205, null, ManagerOperationUiState.Idle),
        )
    }

    @Test
    fun newerOfficialDiscordOffersCloneUpdate() {
        assertEquals(
            ManagerPrimaryAction.UPDATE,
            resolvePrimaryAction(344205, 344105, ManagerOperationUiState.Idle),
        )
    }

    @Test
    fun currentOrNewerCloneOpensWithoutReinstall() {
        assertEquals(
            ManagerPrimaryAction.OPEN,
            resolvePrimaryAction(344205, 344205, ManagerOperationUiState.Idle),
        )
        assertEquals(
            ManagerPrimaryAction.OPEN,
            resolvePrimaryAction(344205, 344305, ManagerOperationUiState.Idle),
        )
    }

    @Test
    fun sameVersionCloneOffersRuntimeRefreshBesideOpen() {
        assertEquals(
            ManagerPrimaryAction.OPEN,
            resolvePrimaryAction(344205, 344205, ManagerOperationUiState.Idle),
        )
        assertEquals(
            ManagerSecondaryAction.REFRESH_THUNDER,
            resolveSecondaryAction(344205, 344205, ManagerOperationUiState.Idle),
        )
    }

    @Test
    fun differentVersionsDoNotOfferRuntimeRefresh() {
        assertEquals(
            ManagerSecondaryAction.NONE,
            resolveSecondaryAction(344205, 344105, ManagerOperationUiState.Idle),
        )
        assertEquals(
            ManagerSecondaryAction.NONE,
            resolveSecondaryAction(344205, 344305, ManagerOperationUiState.Idle),
        )
    }

    @Test
    fun verifiedCloneCanOpenWithoutItsSourceInstalled() {
        assertEquals(
            ManagerPrimaryAction.OPEN,
            resolvePrimaryAction(null, 344205, ManagerOperationUiState.Idle),
        )
    }

    @Test
    fun unsafeCloneStateHasNoRetryAction() {
        assertEquals(
            ManagerPrimaryAction.BLOCKED,
            resolvePrimaryAction(null, null, ManagerOperationUiState.Blocked("Unverified clone")),
        )
    }

    @Test
    fun installWorkDisablesThePrimaryAction() {
        assertEquals(
            ManagerPrimaryAction.NONE,
            resolvePrimaryAction(344205, null, ManagerOperationUiState.Building("Signing…", null)),
        )
        assertEquals(
            ManagerPrimaryAction.NONE,
            resolvePrimaryAction(344205, null, ManagerOperationUiState.AwaitingAndroid),
        )
        assertEquals(
            ManagerSecondaryAction.NONE,
            resolveSecondaryAction(
                344205,
                344205,
                ManagerOperationUiState.Building("Signing…", null),
            ),
        )
    }
}
