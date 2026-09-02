package dev.thunder.manager

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.thunder.manager.ui.ManagerPrimaryAction
import dev.thunder.manager.ui.ManagerInventoryUiState
import dev.thunder.manager.ui.ManagerScreen
import dev.thunder.manager.ui.ManagerSecondaryAction
import dev.thunder.manager.ui.ThunderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val managerController: RootlessManagerController
        get() = (application as ThunderApplication).managerController

    private val signingIdentityRecoveryController: SigningIdentityRecoveryController
        get() = (application as ThunderApplication).signingIdentityRecoveryController

    private val updateController: ManagerUpdateController
        get() = (application as ThunderApplication).updateController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThunderTheme {
                ManagerRoute(managerController, signingIdentityRecoveryController, updateController)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        managerController.refresh()
        signingIdentityRecoveryController.refresh()
    }
}

@Composable
private fun ManagerRoute(
    controller: RootlessManagerController,
    signingIdentityController: SigningIdentityRecoveryController,
    updateController: ManagerUpdateController,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val signingIdentityState by signingIdentityController.state.collectAsState()
    val updates by updateController.state.collectAsState()
    val managerDownloadState by updateController.managerDownloadState.collectAsState()
    val readyInventory = state.inventory as? ManagerInventoryUiState.Ready
    val installedRuntimeVersion = readyInventory?.clone?.runtimeVersion
    var pendingPermissionAction by rememberSaveable { mutableStateOf<PendingPermissionAction?>(null) }
    var pendingApkUri by rememberSaveable { mutableStateOf<String?>(null) }
    var backupDialogOpen by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var backupPasswordConfirmation by remember { mutableStateOf("") }
    var pendingBackupPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var restoreDialogOpen by remember { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    var restoreReplacementConfirmed by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<String?>(null) }
    var restoreCandidate by remember { mutableStateOf<SigningIdentityRestoreCandidate?>(null) }
    var managerUpdateError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(readyInventory != null, installedRuntimeVersion) {
        if (readyInventory != null) updateController.checkAutomatically(installedRuntimeVersion)
    }

    val backupDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SIGNING_BACKUP_MIME_TYPE),
    ) { uri ->
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = null
        if (uri != null && passphrase != null) {
            signingIdentityController.export(uri, passphrase)
        } else {
            passphrase?.fill('\u0000')
        }
    }

    val backupSource = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        signingIdentityController.inspectRestore(uri) { candidate ->
            pendingRestoreUri = uri.toString()
            restoreCandidate = candidate
            restorePassword = ""
            restoreReplacementConfirmed = false
            restoreDialogOpen = true
        }
    }

    val officialInstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        pendingApkUri = null
        pendingPermissionAction = null
        controller.refresh()
    }

    val managerInstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updateController.clearManagerDownload()
        controller.refresh()
        updateController.checkAutomatically(installedRuntimeVersion)
    }

    @Suppress("DEPRECATION")
    fun launchOfficialInstaller(uri: Uri) {
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newUri(context.contentResolver, "Official Discord APK", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        runCatching { officialInstallLauncher.launch(intent) }
            .onFailure { controller.reportError("Android could not open the Discord APK installer.") }
    }

    @Suppress("DEPRECATION")
    fun launchManagerInstaller(update: VerifiedManagerUpdate) {
        val uri = update.contentUri
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newUri(context.contentResolver, "ThunderManager update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        runCatching { managerInstallLauncher.launch(intent) }
            .onFailure {
                managerUpdateError = "Android could not open the ThunderManager update installer."
                updateController.clearManagerDownload()
            }
    }

    fun downloadThunderUpdate() {
        val manifest = (updates.thunder as? ReleaseAvailability.Available)?.manifest
        if (manifest == null) {
            controller.reportError("The Thunder release is no longer available. Check for updates again.")
            return
        }
        updateController.downloadThunder(manifest, installedRuntimeVersion) {
            controller.installSelected()
        }
    }

    fun downloadManagerUpdate() {
        val manifest = (updates.manager as? ReleaseAvailability.Available)?.manifest
        if (manifest == null) {
            managerUpdateError = "The Manager release is no longer available. Check for updates again."
            return
        }
        managerUpdateError = null
        updateController.downloadManager(manifest) { candidate ->
            coroutineScope.launch {
                runCatching { ManagerApkUpdateVerifier.verify(context, candidate) }
                    .onSuccess(::launchManagerInstaller)
                    .onFailure { error ->
                        managerUpdateError = error.message
                            ?.takeIf { it.isNotBlank() && it.length <= 220 }
                            ?: "The downloaded Manager APK failed identity verification."
                        updateController.clearManagerDownload()
                    }
            }
        }
    }

    fun continuePendingAction() {
        val action = pendingPermissionAction
        pendingPermissionAction = null
        when (action) {
            PendingPermissionAction.OFFICIAL_APK -> {
                val uri = pendingApkUri?.let(Uri::parse)
                if (uri == null) {
                    controller.reportError("The selected Discord APK is no longer available.")
                } else {
                    launchOfficialInstaller(uri)
                }
            }
            PendingPermissionAction.THUNDER_CLONE -> controller.installSelected()
            PendingPermissionAction.THUNDER_UPDATE -> downloadThunderUpdate()
            PendingPermissionAction.MANAGER_UPDATE -> downloadManagerUpdate()
            null -> Unit
        }
    }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (canInstallPackages(context.packageManager)) {
            continuePendingAction()
        } else {
            val deniedAction = pendingPermissionAction
            pendingPermissionAction = null
            if (deniedAction == PendingPermissionAction.MANAGER_UPDATE) {
                managerUpdateError = "Android's app-install permission is required for a Manager update."
            } else {
                controller.reportInstallPermissionDenied()
            }
        }
    }

    fun requestInstallPermission(action: PendingPermissionAction) {
        pendingPermissionAction = action
        if (canInstallPackages(context.packageManager)) {
            continuePendingAction()
            return
        }
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { unknownSourcesLauncher.launch(settingsIntent) }
            .onFailure {
                pendingPermissionAction = null
                if (action == PendingPermissionAction.MANAGER_UPDATE) {
                    managerUpdateError = "Android could not open the app-install permission."
                } else {
                    controller.reportError("Android could not open the app-install permission.")
                }
            }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        pendingApkUri = uri.toString()
        requestInstallPermission(PendingPermissionAction.OFFICIAL_APK)
    }

    fun chooseApk() {
        apkPicker.launch(arrayOf(APK_MIME_TYPE))
    }

    ManagerScreen(
        inventoryState = state.inventory,
        selectedPackageName = state.selectedPackageName,
        operation = state.operation,
        signingIdentity = signingIdentityState,
        updates = updates,
        managerDownloadState = managerDownloadState,
        managerUpdateError = managerUpdateError,
        primaryAction = state.primaryAction,
        secondaryAction = state.secondaryAction,
        onSelect = controller::select,
        onRefresh = {
            controller.refresh()
            signingIdentityController.refresh()
            updateController.checkManually(installedRuntimeVersion)
        },
        onChooseApk = ::chooseApk,
        onPrimaryAction = {
            when (state.primaryAction) {
                ManagerPrimaryAction.CHOOSE_APK -> chooseApk()
                ManagerPrimaryAction.INJECT,
                ManagerPrimaryAction.UPDATE,
                -> requestInstallPermission(PendingPermissionAction.THUNDER_CLONE)

                ManagerPrimaryAction.OPEN -> {
                    controller.verifyCloneForOpen {
                        val launchIntent = context.packageManager
                            .getLaunchIntentForPackage(THUNDER_PACKAGE_NAME)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (launchIntent == null) {
                            controller.reportError("Android could not find the Thunder app to open.")
                        } else {
                            runCatching { context.startActivity(launchIntent) }
                                .onFailure { controller.reportError("Android could not open Thunder.") }
                        }
                    }
                }
                ManagerPrimaryAction.RETRY -> {
                    if (canInstallPackages(context.packageManager)) {
                        controller.retry()
                    } else {
                        requestInstallPermission(PendingPermissionAction.THUNDER_CLONE)
                    }
                }
                ManagerPrimaryAction.BLOCKED,
                ManagerPrimaryAction.NONE -> Unit
            }
        },
        onSecondaryAction = {
            when (state.secondaryAction) {
                ManagerSecondaryAction.REFRESH_THUNDER ->
                    requestInstallPermission(PendingPermissionAction.THUNDER_CLONE)
                ManagerSecondaryAction.NONE -> Unit
            }
        },
        onBackupSigningIdentity = {
            backupPassword = ""
            backupPasswordConfirmation = ""
            backupDialogOpen = true
        },
        onRestoreSigningIdentity = {
            backupSource.launch(arrayOf(SIGNING_BACKUP_MIME_TYPE, "application/json", "*/*"))
        },
        onUpdateThunder = {
            requestInstallPermission(PendingPermissionAction.THUNDER_UPDATE)
        },
        onUpdateManager = {
            requestInstallPermission(PendingPermissionAction.MANAGER_UPDATE)
        },
    )

    if (backupDialogOpen) {
        SigningPasswordDialog(
            title = "Back up signing identity",
            explanation = "Choose a password you can recover later. Thunder cannot reset it, and both this file and password are required after a Manager reinstall.",
            password = backupPassword,
            onPasswordChange = { backupPassword = it.take(MAX_BACKUP_PASSWORD_CHARACTERS) },
            confirmation = backupPasswordConfirmation,
            onConfirmationChange = { backupPasswordConfirmation = it.take(MAX_BACKUP_PASSWORD_CHARACTERS) },
            confirmEnabled = backupPassword.length >= MIN_BACKUP_PASSWORD_CHARACTERS &&
                backupPassword == backupPasswordConfirmation,
            onDismiss = {
                backupPassword = ""
                backupPasswordConfirmation = ""
                backupDialogOpen = false
            },
            onConfirm = {
                pendingBackupPassphrase?.fill('\u0000')
                pendingBackupPassphrase = backupPassword.toCharArray()
                backupPassword = ""
                backupPasswordConfirmation = ""
                backupDialogOpen = false
                backupDestination.launch(DEFAULT_SIGNING_BACKUP_NAME)
            },
        )
    }

    if (restoreDialogOpen) {
        val candidate = restoreCandidate
        RestoreSigningIdentityDialog(
            password = restorePassword,
            onPasswordChange = { restorePassword = it.take(MAX_BACKUP_PASSWORD_CHARACTERS) },
            candidate = candidate,
            replacementConfirmed = restoreReplacementConfirmed,
            onReplacementConfirmedChange = { restoreReplacementConfirmed = it },
            onDismiss = {
                restorePassword = ""
                pendingRestoreUri = null
                restoreCandidate = null
                restoreReplacementConfirmed = false
                restoreDialogOpen = false
            },
            onConfirm = {
                val uri = pendingRestoreUri?.let(Uri::parse)
                if (uri != null && candidate != null) {
                    val passphrase = restorePassword.toCharArray()
                    restorePassword = ""
                    pendingRestoreUri = null
                    restoreCandidate = null
                    restoreDialogOpen = false
                    signingIdentityController.restore(
                        uri = uri,
                        passphrase = passphrase,
                        replacementConfirmed = restoreReplacementConfirmed,
                    )
                    restoreReplacementConfirmed = false
                }
            },
        )
    }
}

@Composable
private fun SigningPasswordDialog(
    title: String,
    explanation: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmation: String,
    onConfirmationChange: (String) -> Unit,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(explanation)
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = onConfirmationChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    supportingText = { Text("At least $MIN_BACKUP_PASSWORD_CHARACTERS characters") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text("Choose backup location") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RestoreSigningIdentityDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    candidate: SigningIdentityRestoreCandidate?,
    replacementConfirmed: Boolean,
    onReplacementConfirmedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val replacementRequired = candidate?.activeIdentityExists == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore signing identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This backup will be validated before any active identity changes.")
                candidate?.metadata?.certificateSha256?.let { digest ->
                    Text("Backup signer ${digest.take(12)}…${digest.takeLast(12)}")
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (replacementRequired) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Checkbox(
                            checked = replacementConfirmed,
                            onCheckedChange = onReplacementConfirmedChange,
                        )
                        Text(
                            "Replace the active identity only after confirming this backup matches the installed Thunder app.",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = password.isNotEmpty() && (!replacementRequired || replacementConfirmed),
            ) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class PendingPermissionAction {
    OFFICIAL_APK,
    THUNDER_CLONE,
    THUNDER_UPDATE,
    MANAGER_UPDATE,
}

private fun canInstallPackages(packageManager: android.content.pm.PackageManager): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val SIGNING_BACKUP_MIME_TYPE = "application/vnd.thunder.signing-backup"
private const val DEFAULT_SIGNING_BACKUP_NAME = "Thunder-signing-identity.thunderkey"
private const val MIN_BACKUP_PASSWORD_CHARACTERS = 8
private const val MAX_BACKUP_PASSWORD_CHARACTERS = 256
