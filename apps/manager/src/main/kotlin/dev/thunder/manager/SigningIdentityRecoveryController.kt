package dev.thunder.manager

import android.content.Context
import android.net.Uri
import dev.thunder.packageinspector.AndroidPackageInventory
import dev.thunder.packageinspector.CloneInstallState
import dev.thunder.signing.SigningException
import dev.thunder.signing.SigningFailureCode
import dev.thunder.signing.SigningIdentityBackupMetadata
import dev.thunder.signing.SigningIdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

internal enum class SigningIdentityStatus {
    CHECKING,
    NOT_CREATED,
    PROTECTED,
    RECOVERY_REQUIRED,
}

internal data class SigningIdentityRecoveryUiState(
    val status: SigningIdentityStatus = SigningIdentityStatus.CHECKING,
    val certificateSha256: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

internal data class SigningIdentityRestoreCandidate(
    val metadata: SigningIdentityBackupMetadata,
    val activeIdentityExists: Boolean,
)

internal class SigningIdentityRecoveryController(context: Context) {
    private val applicationContext = context.applicationContext
    private val identities = SigningIdentityStore(applicationContext)
    private val inventory = AndroidPackageInventory(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(SigningIdentityRecoveryUiState())
    val state: StateFlow<SigningIdentityRecoveryUiState> = mutableState.asStateFlow()

    fun refresh() {
        if (mutableState.value.busy) return
        scope.launch {
            mutableState.value = mutableState.value.copy(
                status = SigningIdentityStatus.CHECKING,
                message = null,
                isError = false,
            )
            runCatching { identities.getExisting(THUNDER_PACKAGE_NAME) }
                .onSuccess { identity ->
                    mutableState.value = if (identity == null) {
                        SigningIdentityRecoveryUiState(status = SigningIdentityStatus.NOT_CREATED)
                    } else {
                        SigningIdentityRecoveryUiState(
                            status = SigningIdentityStatus.PROTECTED,
                            certificateSha256 = identity.certificateSha256,
                        )
                    }
                }
                .onFailure {
                    mutableState.value = SigningIdentityRecoveryUiState(
                        status = SigningIdentityStatus.RECOVERY_REQUIRED,
                        message = "The local update key cannot be opened. Restore a signing identity backup before updating Thunder.",
                        isError = true,
                    )
                }
        }
    }

    fun inspectRestore(
        uri: Uri,
        onReady: (SigningIdentityRestoreCandidate) -> Unit,
    ) {
        if (mutableState.value.busy) return
        scope.launch {
            mutableState.value = mutableState.value.copy(busy = true, message = "Checking the backup…", isError = false)
            runCatching {
                val backup = readBackup(uri)
                try {
                    val metadata = identities.inspectPortable(backup)
                    if (metadata.targetPackageName != THUNDER_PACKAGE_NAME) {
                        throw IOException("This backup belongs to a different app.")
                    }
                    verifyCandidateAgainstInstalledClone(metadata)
                    val active = runCatching { identities.getExisting(THUNDER_PACKAGE_NAME) }.getOrNull() != null ||
                        mutableState.value.status == SigningIdentityStatus.RECOVERY_REQUIRED
                    SigningIdentityRestoreCandidate(metadata, active)
                } finally {
                    backup.fill(0)
                }
            }.onSuccess { candidate ->
                mutableState.value = mutableState.value.copy(busy = false, message = null)
                onReady(candidate)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    message = error.userMessage("Thunder could not read that signing identity backup."),
                    isError = true,
                )
            }
        }
    }

    fun export(uri: Uri, passphrase: CharArray) {
        if (mutableState.value.busy) {
            passphrase.fill('\u0000')
            return
        }
        scope.launch {
            mutableState.value = mutableState.value.copy(busy = true, message = "Encrypting the signing identity…", isError = false)
            runCatching {
                val backup = identities.exportPortable(THUNDER_PACKAGE_NAME, passphrase)
                try {
                    withContext(Dispatchers.IO) {
                        applicationContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            output.write(backup)
                            output.flush()
                        } ?: throw IOException("The selected backup file could not be opened.")
                    }
                } finally {
                    backup.fill(0)
                }
            }.onSuccess {
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    message = "Signing identity backup saved. Keep the file and password somewhere safe.",
                    isError = false,
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    message = error.userMessage("Thunder could not save the signing identity backup."),
                    isError = true,
                )
            }
            passphrase.fill('\u0000')
        }
    }

    fun restore(uri: Uri, passphrase: CharArray, replacementConfirmed: Boolean) {
        if (mutableState.value.busy) {
            passphrase.fill('\u0000')
            return
        }
        scope.launch {
            mutableState.value = mutableState.value.copy(busy = true, message = "Restoring the signing identity…", isError = false)
            runCatching {
                val backup = readBackup(uri)
                try {
                    val metadata = identities.inspectPortable(backup)
                    verifyCandidateAgainstInstalledClone(metadata)
                    identities.restorePortable(
                        targetPackageName = THUNDER_PACKAGE_NAME,
                        backup = backup,
                        passphrase = passphrase,
                        replacementConfirmed = replacementConfirmed,
                    )
                } finally {
                    backup.fill(0)
                }
            }.onSuccess { identity ->
                mutableState.value = SigningIdentityRecoveryUiState(
                    status = SigningIdentityStatus.PROTECTED,
                    certificateSha256 = identity.certificateSha256,
                    message = "Signing identity restored. Thunder updates can keep using the same Android signer.",
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    message = error.userMessage("Thunder could not restore that signing identity backup."),
                    isError = true,
                )
            }
            passphrase.fill('\u0000')
        }
    }

    private suspend fun verifyCandidateAgainstInstalledClone(metadata: SigningIdentityBackupMetadata) {
        when (val cloneState = withContext(Dispatchers.IO) { inventory.inspectClone() }) {
            CloneInstallState.NotInstalled -> Unit
            is CloneInstallState.Unavailable -> throw IOException(
                "Android would not let Thunder verify the installed clone, so the active identity was not changed.",
            )
            is CloneInstallState.Installed -> {
                val signer = cloneState.clone.currentSignerSha256.singleOrNull()
                    ?: throw IOException("The installed Thunder app has an unexpected signer set.")
                if (!signer.equals(metadata.certificateSha256, ignoreCase = true)) {
                    throw IOException(
                        "This backup does not match the installed Thunder app. The active identity was not changed.",
                    )
                }
            }
        }
    }

    private suspend fun readBackup(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BACKUP_BYTES) throw IOException("The backup is too large.")
                output.write(buffer, 0, count)
            }
            output.toByteArray().also {
                if (it.isEmpty()) throw IOException("The backup is empty.")
            }
        } ?: throw IOException("The selected backup file could not be opened.")
    }

    private fun Throwable.userMessage(fallback: String): String = when ((this as? SigningException)?.code) {
        SigningFailureCode.BACKUP_INVALID_PASSWORD -> "The password is incorrect or the backup is damaged. Nothing was changed."
        SigningFailureCode.BACKUP_INVALID_FORMAT -> "That file is not a valid Thunder signing identity backup."
        SigningFailureCode.BACKUP_TARGET_MISMATCH -> "That backup belongs to a different app."
        SigningFailureCode.BACKUP_ACTIVE_IDENTITY -> "Replacing an active signing identity requires explicit confirmation."
        else -> message?.takeIf { it.isNotBlank() && it.length <= 220 } ?: fallback
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 128 * 1024
    }
}
