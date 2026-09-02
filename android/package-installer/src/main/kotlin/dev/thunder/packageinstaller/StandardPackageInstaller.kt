package dev.thunder.packageinstaller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

class StandardPackageInstaller(context: Context) {
    private val applicationContext = context.applicationContext
    private val packageInstaller = applicationContext.packageManager.packageInstaller
    private val store = InstallSessionStore(applicationContext)

    suspend fun stage(packageName: String, artifacts: List<InstallArtifact>): StagedInstall =
        withContext(Dispatchers.IO) {
            InstallInputRules.requirePackageName(packageName)
            val totalBytes = InstallInputRules.requireArtifacts(artifacts)
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setSize(totalBytes)
                setOriginatingUid(Process.myUid())
                if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)
                if (Build.VERSION.SDK_INT >= 31) {
                    setInstallScenario(PackageManager.INSTALL_SCENARIO_FAST)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
                }
            }

            val sessionId = try {
                packageInstaller.createSession(params)
            } catch (error: Exception) {
                throw InstallException(InstallFailureCode.SESSION_CREATE_FAILED, error)
            }
            val staged = StagedInstall(
                sessionId = sessionId,
                packageName = packageName,
                artifactCount = artifacts.size,
                totalBytes = totalBytes,
                createdAtEpochMillis = System.currentTimeMillis(),
            )

            try {
                store.create(staged, InstallSessionState.STAGING)
                packageInstaller.openSession(sessionId).use { session ->
                    for (artifact in artifacts) {
                        session.openWrite(artifact.sessionName, 0, artifact.file.length()).use { output ->
                            artifact.file.inputStream().buffered().use { input ->
                                input.copyTo(output, BUFFER_SIZE)
                            }
                            session.fsync(output)
                        }
                    }
                }
                checkNotNull(
                    store.transition(
                        sessionId,
                        setOf(InstallSessionState.STAGING),
                        InstallSessionState.STAGED,
                    ),
                )
                staged
            } catch (error: Exception) {
                runCatching { packageInstaller.abandonSession(sessionId) }
                if (error is InstallException) throw error
                throw InstallException(InstallFailureCode.SESSION_WRITE_FAILED, error)
            }
        }

    fun commit(stagedInstall: StagedInstall) {
        dispatchCommit(stagedInstall, setOf(InstallSessionState.STAGED), recovering = false)
    }

    fun resumeCommit(stagedInstall: StagedInstall) {
        dispatchCommit(stagedInstall, RECOVERABLE_COMMIT_STATES, recovering = true)
    }

    private fun dispatchCommit(
        stagedInstall: StagedInstall,
        expectedStates: Set<InstallSessionState>,
        recovering: Boolean,
    ) {
        val record = store.read(stagedInstall.sessionId)
            ?: throw InstallException(InstallFailureCode.SESSION_NOT_OWNED)
        if (record.stagedInstall != stagedInstall || record.state !in expectedStates) {
            throw InstallException(InstallFailureCode.SESSION_NOT_OWNED)
        }
        val sessionInfo = try {
            packageInstaller.getSessionInfo(stagedInstall.sessionId)
        } catch (error: Exception) {
            if (recovering) markRecoveryFailed(stagedInstall, expectedStates, error.message)
            throw InstallException(InstallFailureCode.SESSION_NOT_OWNED, error)
        }
        if (sessionInfo == null || sessionInfo.appPackageName != stagedInstall.packageName) {
            if (recovering) markRecoveryFailed(
                stagedInstall,
                expectedStates,
                "The pending Android install session is no longer available.",
            )
            throw InstallException(InstallFailureCode.SESSION_NOT_OWNED)
        }

        val callback = Intent(applicationContext, PackageInstallerStatusReceiver::class.java).apply {
            action = PackageInstallerStatusReceiver.ACTION_STATUS
            data = PackageInstallerStatusReceiver.callbackUri(stagedInstall.sessionId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            stagedInstall.sessionId,
            callback,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        try {
            val transitioned = store.transition(
                stagedInstall.sessionId,
                expectedStates,
                InstallSessionState.COMMIT_REQUESTED,
            )
            if (transitioned == null) {
                val latest = store.read(stagedInstall.sessionId)
                if (latest?.state in TERMINAL_STATES) return
                throw InstallException(InstallFailureCode.SESSION_NOT_OWNED)
            }
            packageInstaller.openSession(stagedInstall.sessionId).use { session ->
                session.commit(pendingIntent.intentSender)
            }
        } catch (error: Exception) {
            runCatching {
                store.transition(
                    stagedInstall.sessionId,
                    RECOVERABLE_COMMIT_STATES,
                    InstallSessionState.FAILED,
                    statusMessage = error.message,
                )
            }
            throw InstallException(InstallFailureCode.SESSION_COMMIT_FAILED, error)
        }
    }

    private fun markRecoveryFailed(
        stagedInstall: StagedInstall,
        expectedStates: Set<InstallSessionState>,
        message: String?,
    ) {
        runCatching {
            store.transition(
                stagedInstall.sessionId,
                expectedStates,
                InstallSessionState.FAILED,
                statusMessage = message,
            )
        }
    }

    /**
     * Waits for the outcome Android reports back through [PackageInstallerStatusReceiver]. The
     * journal is the single source of truth, so the wait survives the confirmation dialog taking
     * the manager off screen. Returns null only when the deadline passes with no terminal state.
     */
    suspend fun awaitOutcome(
        stagedInstall: StagedInstall,
        timeoutMillis: Long = DEFAULT_OUTCOME_TIMEOUT_MILLIS,
    ): InstallSessionRecord? = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var record = store.read(stagedInstall.sessionId)
        while ((record == null || record.state !in TERMINAL_STATES) && SystemClock.elapsedRealtime() < deadline) {
            delay(OUTCOME_POLL_INTERVAL_MILLIS)
            record = store.read(stagedInstall.sessionId)
        }
        record?.takeIf { it.state in TERMINAL_STATES }
    }

    fun abandon(stagedInstall: StagedInstall) {
        val record = store.read(stagedInstall.sessionId)
            ?: throw InstallException(InstallFailureCode.SESSION_NOT_OWNED)
        if (record.stagedInstall != stagedInstall) {
            throw InstallException(InstallFailureCode.SESSION_NOT_OWNED)
        }
        try {
            packageInstaller.getSessionInfo(stagedInstall.sessionId)?.let {
                packageInstaller.abandonSession(stagedInstall.sessionId)
            }
            store.transition(
                stagedInstall.sessionId,
                ABANDONABLE_STATES,
                InstallSessionState.ABANDONED,
            )
        } catch (error: Exception) {
            throw InstallException(InstallFailureCode.SESSION_ABANDON_FAILED, error)
        }
    }

    fun records(): List<InstallSessionRecord> = store.records()

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val OUTCOME_POLL_INTERVAL_MILLIS = 350L
        const val DEFAULT_OUTCOME_TIMEOUT_MILLIS = 10L * 60L * 1000L
        val TERMINAL_STATES = setOf(
            InstallSessionState.SUCCEEDED,
            InstallSessionState.FAILED,
            InstallSessionState.ABANDONED,
        )
        val RECOVERABLE_COMMIT_STATES = setOf(
            InstallSessionState.COMMIT_REQUESTED,
            InstallSessionState.USER_ACTION_REQUIRED,
        )
        val ABANDONABLE_STATES = setOf(
            InstallSessionState.STAGING,
            InstallSessionState.STAGED,
            InstallSessionState.COMMIT_REQUESTED,
            InstallSessionState.USER_ACTION_REQUIRED,
        )
    }
}
