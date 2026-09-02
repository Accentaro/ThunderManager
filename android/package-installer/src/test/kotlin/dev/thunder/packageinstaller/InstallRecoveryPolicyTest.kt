package dev.thunder.packageinstaller

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallRecoveryPolicyTest {
    @Test
    fun recommitsBothInterruptedCommitStates() {
        assertEquals(
            InstallRecoveryAction.RECOMMIT,
            InstallRecoveryPolicy.actionFor(InstallSessionState.COMMIT_REQUESTED),
        )
        assertEquals(
            InstallRecoveryAction.RECOMMIT,
            InstallRecoveryPolicy.actionFor(InstallSessionState.USER_ACTION_REQUIRED),
        )
    }

    @Test
    fun mapsEveryJournalStateToADeterministicRecoveryAction() {
        val actions = InstallSessionState.entries.associateWith(InstallRecoveryPolicy::actionFor)

        assertEquals(InstallRecoveryAction.ABANDON, actions.getValue(InstallSessionState.STAGING))
        assertEquals(InstallRecoveryAction.COMMIT, actions.getValue(InstallSessionState.STAGED))
        assertEquals(InstallRecoveryAction.COMPLETE, actions.getValue(InstallSessionState.SUCCEEDED))
        assertEquals(InstallRecoveryAction.IGNORE, actions.getValue(InstallSessionState.FAILED))
        assertEquals(InstallRecoveryAction.IGNORE, actions.getValue(InstallSessionState.ABANDONED))
    }
}
