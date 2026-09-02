package dev.thunder.manager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.thunder.manager.R
import dev.thunder.manager.ManagerUpdatesUiState
import dev.thunder.manager.ReleaseAvailability
import dev.thunder.manager.SigningIdentityRecoveryUiState
import dev.thunder.manager.SigningIdentityStatus
import dev.thunder.manager.ThunderRuntimeDownloadState
import dev.thunder.packageinspector.InstalledDiscordTarget
import dev.thunder.packageinspector.PatchMarker
import dev.thunder.updateclient.ManagerUpdateDownloadState

@Composable
internal fun ManagerScreen(
    inventoryState: ManagerInventoryUiState,
    selectedPackageName: String?,
    operation: ManagerOperationUiState,
    signingIdentity: SigningIdentityRecoveryUiState,
    updates: ManagerUpdatesUiState,
    managerDownloadState: ManagerUpdateDownloadState,
    managerUpdateError: String?,
    primaryAction: ManagerPrimaryAction,
    secondaryAction: ManagerSecondaryAction,
    onSelect: (InstalledDiscordTarget) -> Unit,
    onRefresh: () -> Unit,
    onChooseApk: () -> Unit,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    onBackupSigningIdentity: () -> Unit,
    onRestoreSigningIdentity: () -> Unit,
    onUpdateThunder: () -> Unit,
    onUpdateManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var signingIdentityOpen by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        ),
                    ),
                ),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    top = 64.dp,
                    end = 24.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { ThunderTopBar(onRefresh) }
                item { Intro() }
                item {
                    SigningIdentityButton(
                        state = signingIdentity,
                        onClick = { signingIdentityOpen = true },
                    )
                }
                item {
                    ReleaseUpdates(
                        state = updates,
                        managerDownloadState = managerDownloadState,
                        managerUpdateError = managerUpdateError,
                        onUpdateThunder = onUpdateThunder,
                        onUpdateManager = onUpdateManager,
                    )
                }

                when (inventoryState) {
                    ManagerInventoryUiState.Loading -> item { InventoryLoading() }
                    ManagerInventoryUiState.Failed -> item { InventoryFailure(onRefresh) }
                    is ManagerInventoryUiState.Ready -> {
                        if (inventoryState.targets.isEmpty()) {
                            item {
                                NoDiscordState(
                                    clone = inventoryState.clone,
                                    operation = operation,
                                    primaryAction = primaryAction,
                                    onChooseApk = onChooseApk,
                                    onPrimaryAction = onPrimaryAction,
                                )
                            }
                        } else {
                            item { SectionHeading("Discord source", "The Play app stays untouched.") }
                            items(inventoryState.targets, key = { it.packageName }) { target ->
                                DiscordSourceRow(
                                    target = target,
                                    selected = target.packageName == selectedPackageName,
                                    onClick = { onSelect(target) },
                                )
                            }
                            item {
                                val selected = inventoryState.targets.firstOrNull {
                                    it.packageName == selectedPackageName
                                } ?: inventoryState.targets.first()
                                CloneAction(
                                    source = selected,
                                    clone = inventoryState.clone,
                                    operation = operation,
                                    primaryAction = primaryAction,
                                    secondaryAction = secondaryAction,
                                    onPrimaryAction = onPrimaryAction,
                                    onSecondaryAction = onSecondaryAction,
                                    onChooseApk = onChooseApk,
                                )
                            }
                        }
                    }
                }
            }
            Image(
                painter = painterResource(R.drawable.thunder_cat_console),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(225.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (signingIdentityOpen) {
            SigningIdentityDialog(
                state = signingIdentity,
                onBackup = onBackupSigningIdentity,
                onRestore = onRestoreSigningIdentity,
                onDismiss = { signingIdentityOpen = false },
            )
        }
    }
}

@Composable
private fun ReleaseUpdates(
    state: ManagerUpdatesUiState,
    managerDownloadState: ManagerUpdateDownloadState,
    managerUpdateError: String?,
    onUpdateThunder: () -> Unit,
    onUpdateManager: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val availability = state.thunder) {
            is ReleaseAvailability.Available -> UpdateNoticeCard(
                title = "Thunder update available",
                detail = "Stable Thunder ${availability.manifest.version} is ready to download and verify.",
                buttonLabel = when (state.thunderDownload) {
                    is ThunderRuntimeDownloadState.Downloading -> "Downloading Thunder…"
                    is ThunderRuntimeDownloadState.VerifiedReady -> "Runtime verified"
                    is ThunderRuntimeDownloadState.Failed -> "Retry Thunder update"
                    ThunderRuntimeDownloadState.Idle -> "Update Thunder"
                },
                enabled = state.thunderDownload is ThunderRuntimeDownloadState.Idle ||
                    state.thunderDownload is ThunderRuntimeDownloadState.Failed,
                error = if (state.thunderDownload is ThunderRuntimeDownloadState.Failed) {
                    "The runtime update failed verification or download. The installed Thunder app was not changed."
                } else {
                    null
                },
                onClick = onUpdateThunder,
            )
            else -> Unit
        }

        when (val availability = state.manager) {
            is ReleaseAvailability.Available -> UpdateNoticeCard(
                title = "ThunderManager update available",
                detail = "Stable ThunderManager ${availability.manifest.version} will be verified before Android asks you to update it.",
                buttonLabel = when (managerDownloadState) {
                    is ManagerUpdateDownloadState.Downloading -> "Downloading Manager…"
                    is ManagerUpdateDownloadState.Ready -> "Opening Android installer…"
                    is ManagerUpdateDownloadState.Failed -> "Retry Manager update"
                    ManagerUpdateDownloadState.Idle -> "Update ThunderManager"
                },
                enabled = managerDownloadState is ManagerUpdateDownloadState.Idle ||
                    managerDownloadState is ManagerUpdateDownloadState.Failed,
                error = managerUpdateError ?: if (managerDownloadState is ManagerUpdateDownloadState.Failed) {
                    "The Manager update could not be downloaded or verified. This installation was not changed."
                } else {
                    null
                },
                onClick = onUpdateManager,
            )
            else -> Unit
        }

        val manualCheckActive = listOf(state.thunder, state.manager).any {
            it is ReleaseAvailability.Checking && it.manuallyRequested
        }
        val manualCheckFailed = listOf(state.thunder, state.manager).any {
            it is ReleaseAvailability.Failed && it.manuallyRequested
        }
        if (manualCheckActive) {
            UpdateCheckMessage("Checking stable Thunder and ThunderManager releases…", error = false)
        } else if (manualCheckFailed) {
            UpdateCheckMessage("Could not check every release right now. Existing installations were not changed.", error = true)
        }
    }
}

@Composable
private fun UpdateNoticeCard(
    title: String,
    detail: String,
    buttonLabel: String,
    enabled: Boolean,
    error: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f), RoundedCornerShape(17.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(buttonLabel, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UpdateCheckMessage(message: String, error: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!error) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SigningIdentityButton(
    state: SigningIdentityRecoveryUiState,
    onClick: () -> Unit,
) {
    val statusColor = when (state.status) {
        SigningIdentityStatus.CHECKING -> MaterialTheme.colorScheme.primary
        SigningIdentityStatus.PROTECTED -> MaterialTheme.colorScheme.tertiary
        SigningIdentityStatus.NOT_CREATED,
        SigningIdentityStatus.RECOVERY_REQUIRED,
        -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(statusColor),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Signing identity",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SigningIdentityDialog(
    state: SigningIdentityRecoveryUiState,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val protected = state.status == SigningIdentityStatus.PROTECTED
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Signing identity",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    StatusLabel(
                        label = when (state.status) {
                            SigningIdentityStatus.CHECKING -> "Checking"
                            SigningIdentityStatus.NOT_CREATED -> "Not created"
                            SigningIdentityStatus.PROTECTED -> "Protected"
                            SigningIdentityStatus.RECOVERY_REQUIRED -> "Restore required"
                        },
                        positive = protected,
                    )
                }
                Text(
                    text = when (state.status) {
                        SigningIdentityStatus.CHECKING -> "Checking the key used for in-place Thunder updates…"
                        SigningIdentityStatus.NOT_CREATED ->
                            "Thunder creates this key during the first injection. Restore an existing backup first if this Manager was reinstalled."
                        SigningIdentityStatus.PROTECTED ->
                            "Back this up once. The same identity is required to update the installed Thunder app without losing its data."
                        SigningIdentityStatus.RECOVERY_REQUIRED ->
                            "Restore the matching backup before updating the existing Thunder app."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.certificateSha256?.let { digest ->
                    Text(
                        text = "Signer ${digest.take(12)}…${digest.takeLast(12)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBackup, enabled = protected && !state.busy) {
                        Text("Back up")
                    }
                    TextButton(onClick = onRestore, enabled = !state.busy) {
                        Text("Restore")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun ThunderTopBar(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Thunder",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        TextButton(onClick = onRefresh, shape = RoundedCornerShape(14.dp)) {
            Text(
                "⟳",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "Check for updates",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Intro() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                text = "A little storm\nbeside Discord.",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.fillMaxWidth(0.79f),
            )
            Image(
                painter = painterResource(R.drawable.thunder_pixel_cloud),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.CenterEnd).size(88.dp),
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text(
            text = detail,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InventoryLoading() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Text("Looking for Discord and Thunder…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InventoryFailure(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("The package scan got rained out.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Nothing was changed. Try the scan again.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun NoDiscordState(
    clone: ThunderCloneUi?,
    operation: ManagerOperationUiState,
    primaryAction: ManagerPrimaryAction,
    onChooseApk: () -> Unit,
    onPrimaryAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (clone == null) "Discord comes first" else "Thunder is still here",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (clone == null) {
                "Pick an official Discord APK. Android installs it normally; then Thunder can build its separate app."
            } else {
                "You can open Thunder now. Install official Discord again whenever you want a newer source build."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val error = when (operation) {
            is ManagerOperationUiState.Failed -> operation.message
            is ManagerOperationUiState.Blocked -> operation.message
            else -> null
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = if (clone == null) onChooseApk else onPrimaryAction,
            enabled = primaryAction != ManagerPrimaryAction.BLOCKED,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(vertical = 15.dp),
            colors = if (clone != null) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(if (clone == null) "Choose Discord APK" else "Open Thunder", fontWeight = FontWeight.SemiBold)
        }
        if (clone != null) {
            TextButton(onClick = onChooseApk, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Install official Discord APK")
            }
        }
    }
}

@Composable
private fun DiscordSourceRow(
    target: InstalledDiscordTarget,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val icon = remember(target.packageName) {
        runCatching { context.packageManager.getApplicationIcon(target.packageName).toBoundedImageBitmap() }.getOrNull()
    }
    val shape = RoundedCornerShape(17.dp)
    val background = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val outline = if (selected) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f))
            .clip(shape)
            .background(background)
            .border(1.dp, outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = icon?.let(::BitmapPainter) ?: painterResource(R.drawable.ic_thunder),
            contentDescription = target.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = target.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "${target.channel.displayName} · ${target.versionName}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusLabel(
            label = if (target.patchMarker is PatchMarker.Absent) "Source" else "Check",
            positive = target.patchMarker is PatchMarker.Absent,
        )
    }
}

@Composable
private fun StatusLabel(label: String, positive: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (positive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error),
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CloneAction(
    source: InstalledDiscordTarget,
    clone: ThunderCloneUi?,
    operation: ManagerOperationUiState,
    primaryAction: ManagerPrimaryAction,
    secondaryAction: ManagerSecondaryAction,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    onChooseApk: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (clone != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Thunder app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusLabel(
                    label = clone.versionName,
                    positive = clone.versionCode >= source.versionCode,
                )
            }
        }

        Text(
            text = operationMessage(source, clone, operation),
            style = MaterialTheme.typography.bodyMedium,
            color = if (operation is ManagerOperationUiState.Failed || operation is ManagerOperationUiState.Blocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        if (source.patchMarker !is PatchMarker.Absent) {
            Text(
                text = "This source already contains modification metadata. Restore an official Discord build before continuing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when (operation) {
            is ManagerOperationUiState.Building -> {
                val progress = operation.progress
                if (progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            ManagerOperationUiState.AwaitingAndroid -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Unit
        }

        Button(
            onClick = onPrimaryAction,
            enabled = primaryAction != ManagerPrimaryAction.NONE &&
                primaryAction != ManagerPrimaryAction.BLOCKED &&
                source.patchMarker is PatchMarker.Absent,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("⚡  ${primaryAction.label()}", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (secondaryAction == ManagerSecondaryAction.REFRESH_THUNDER) {
                TextButton(onClick = onSecondaryAction, shape = RoundedCornerShape(13.dp)) {
                    Text("↻  Refresh Thunder", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            TextButton(onClick = onChooseApk, shape = RoundedCornerShape(13.dp)) {
                Text("▣  Different Discord APK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun operationMessage(
    source: InstalledDiscordTarget,
    clone: ThunderCloneUi?,
    operation: ManagerOperationUiState,
): String = when (operation) {
    ManagerOperationUiState.Idle -> when {
        clone == null -> "Ready to make a separate Thunder app. Discord and its data stay where they are."
        source.versionCode > clone.versionCode -> "Discord has a newer build. Thunder can update in place and keep its own data."
        else -> "Thunder is ready. It lives beside official Discord with its own app data."
    }
    is ManagerOperationUiState.Building -> operation.label
    ManagerOperationUiState.AwaitingAndroid -> "Thunder is built. Confirm the install in Android’s installer."
    is ManagerOperationUiState.Failed -> operation.message
    is ManagerOperationUiState.Blocked -> operation.message
}

private fun ManagerPrimaryAction.label(): String = when (this) {
    ManagerPrimaryAction.CHOOSE_APK -> "Choose Discord APK"
    ManagerPrimaryAction.INJECT -> "Inject Thunder"
    ManagerPrimaryAction.UPDATE -> "Update Thunder"
    ManagerPrimaryAction.OPEN -> "Open Thunder"
    ManagerPrimaryAction.RETRY -> "Try again"
    ManagerPrimaryAction.BLOCKED -> "Action unavailable"
    ManagerPrimaryAction.NONE -> "Working…"
}

private fun Drawable.toBoundedImageBitmap() = Bitmap
    .createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    .also { bitmap ->
        val previousBounds = copyBounds()
        setBounds(0, 0, bitmap.width, bitmap.height)
        draw(Canvas(bitmap))
        bounds = previousBounds
    }
    .asImageBitmap()
