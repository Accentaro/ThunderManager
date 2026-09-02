package dev.thunder.packageinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri

class PackageInstallerStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS || intent.data?.scheme != CALLBACK_SCHEME) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val uriSessionId = intent.data?.lastPathSegment?.toIntOrNull()
        if (sessionId < 0 || uriSessionId != sessionId) return

        val store = InstallSessionStore(context.applicationContext)
        if (store.read(sessionId) == null) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val transitioned = store.transition(
                    sessionId,
                    CALLBACK_STATES,
                    InstallSessionState.USER_ACTION_REQUIRED,
                    status,
                    message,
                ) ?: return
                val confirmation = intent.intentExtra()
                if (confirmation == null) {
                    store.transition(
                        sessionId,
                        setOf(transitioned.state),
                        InstallSessionState.FAILED,
                        PackageInstaller.STATUS_FAILURE_INVALID,
                        "Package installer did not provide a confirmation intent",
                    )
                } else {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmation) }
                        .onFailure { error ->
                            store.transition(
                                sessionId,
                                setOf(InstallSessionState.USER_ACTION_REQUIRED),
                                InstallSessionState.FAILED,
                                PackageInstaller.STATUS_FAILURE_INVALID,
                                error.message ?: "Android could not open install confirmation.",
                            )
                        }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                store.transition(sessionId, CALLBACK_STATES, InstallSessionState.SUCCEEDED, status, message)
            }

            else -> {
                store.transition(sessionId, CALLBACK_STATES, InstallSessionState.FAILED, status, message)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.intentExtra(): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        internal const val ACTION_STATUS = "dev.thunder.packageinstaller.action.STATUS"
        private const val CALLBACK_SCHEME = "thunder-install"
        private val CALLBACK_STATES = setOf(
            InstallSessionState.COMMIT_REQUESTED,
            InstallSessionState.USER_ACTION_REQUIRED,
        )

        internal fun callbackUri(sessionId: Int): Uri =
            Uri.Builder().scheme(CALLBACK_SCHEME).authority("session").appendPath(sessionId.toString()).build()
    }
}
