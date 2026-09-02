package dev.thunder.manager

import android.content.Context
import dev.thunder.injection.custom.AssetBootstrapDexProvider
import dev.thunder.injection.custom.AssetBrandIconProvider
import dev.thunder.injection.custom.AssetRuntimeBundleProvider
import dev.thunder.injection.custom.PurposeBuiltInjectionBackend
import dev.thunder.injection.custom.RuntimeBundle
import dev.thunder.injection.custom.RuntimeBundleProvider
import dev.thunder.packageinspector.AndroidPackageInventory
import dev.thunder.packageinspector.CloneInstallState
import dev.thunder.packageinspector.DiscordTargetCatalog
import dev.thunder.packageinspector.PatchMarker
import dev.thunder.packageinstaller.InstallArtifact
import dev.thunder.packageinstaller.InstallRecoveryAction
import dev.thunder.packageinstaller.InstallRecoveryPolicy
import dev.thunder.packageinstaller.InstallSessionRecord
import dev.thunder.packageinstaller.InstallSessionState
import dev.thunder.packageinstaller.StandardPackageInstaller
import dev.thunder.patchorchestrator.PatchPipelineProgress
import dev.thunder.patchorchestrator.PatchPipelineStep
import dev.thunder.patchorchestrator.PatchTransactionCoordinator
import dev.thunder.patchorchestrator.PatchTransactionException
import dev.thunder.patchorchestrator.PreflightBlockReason
import dev.thunder.patchorchestrator.PreparedPatchTransaction
import dev.thunder.signing.SigningIdentityStore
import dev.thunder.updateclient.SemanticVersion
import dev.thunder.updateclient.VerifiedRuntimeArtifact
import dev.thunder.updateclient.VerifiedRuntimeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidRootlessClonePipeline(context: Context) : RootlessClonePipeline {
    private val applicationContext = context.applicationContext
    private val inventory = AndroidPackageInventory(applicationContext)
    private val bundledRuntimeVersion = SemanticVersion.parseStableRelease(BuildConfig.BUNDLED_THUNDER_RUNTIME_VERSION)
    private val bundledRuntimeProvider = AssetRuntimeBundleProvider(
        applicationContext,
        bundledRuntimeVersion.toString(),
    )
    private val runtimeStore = VerifiedRuntimeStore(
        applicationContext.filesDir.resolve(ManagerUpdateStorage.RUNTIME_STORE_DIRECTORY),
    )
    private val installer = StandardPackageInstaller(applicationContext)
    private val identities = SigningIdentityStore(applicationContext)

    override suspend fun inspectClone(cloneState: CloneInstallState): CloneUiInspection = when (cloneState) {
        CloneInstallState.NotInstalled -> CloneUiInspection.Absent
        is CloneInstallState.Unavailable -> CloneUiInspection.Blocked(
            "Android would not let Thunder verify the installed dev.thunder.app. Nothing will be replaced.",
        )
        is CloneInstallState.Installed -> inspectInstalledClone(cloneState)
    }

    override suspend fun install(
        source: dev.thunder.packageinspector.InstalledDiscordTarget,
        onEvent: suspend (ClonePipelineEvent) -> Unit,
    ) {
        val currentInventory = withContext(Dispatchers.IO) { inventory.scan() }
        val coordinator = coordinator(selectedRuntimeProvider(currentInventory.clone))
        var prepared: PreparedPatchTransaction? = null
        try {
            val currentSource = currentInventory.targets.singleOrNull {
                it.packageName == source.packageName && it.channel == source.channel
            } ?: throw IllegalStateException(
                "Thunder could not re-check the selected official Discord installation. Official Discord was not changed.",
            )
            prepared = try {
                coordinator.prepare(
                    sourceTarget = currentSource,
                    cloneState = currentInventory.clone,
                    onProgress = { progress -> onEvent(progress.toUiEvent()) },
                )
            } catch (error: PatchTransactionException) {
                throw IllegalStateException(error.blockReason.userMessage(), error)
            }

            check(prepared.outputPackageName == THUNDER_PACKAGE_NAME) {
                "The patch transaction selected an unexpected output package."
            }
            val artifacts = prepared.signedApkSet.artifacts.map { artifact ->
                InstallArtifact(
                    file = artifact.outputFile,
                    sessionName = artifact.outputFile.name,
                )
            }
            onEvent(ClonePipelineEvent.Progress("Staging the complete Thunder app…"))
            val staged = installer.stage(prepared.outputPackageName, artifacts)

            try {
                coordinator.discard(prepared)
                prepared = null
            } catch (error: Throwable) {
                // Never leave a recoverable install session behind when the private
                // patch workspace could not be cleaned up successfully.
                runCatching { installer.abandon(staged) }
                throw error
            }

            onEvent(ClonePipelineEvent.AwaitingAndroid)
            installer.commit(staged)
            requireSuccessful(installer.awaitOutcome(staged))
        } finally {
            prepared?.let { transaction -> runCatching { coordinator.discard(transaction) } }
        }
    }

    override suspend fun recoverPendingInstall(onEvent: suspend (ClonePipelineEvent) -> Unit): Boolean {
        val coordinator = coordinator(bundledRuntimeProvider)
        runCatching { coordinator.reapAbandonedTransactions() }
        val record = withContext(Dispatchers.IO) {
            installer.records().firstOrNull { it.stagedInstall.packageName == THUNDER_PACKAGE_NAME }
        } ?: return false

        return when (InstallRecoveryPolicy.actionFor(record.state)) {
            InstallRecoveryAction.ABANDON -> {
                runCatching { installer.abandon(record.stagedInstall) }
                false
            }
            InstallRecoveryAction.COMMIT -> {
                onEvent(ClonePipelineEvent.AwaitingAndroid)
                installer.commit(record.stagedInstall)
                requireSuccessful(installer.awaitOutcome(record.stagedInstall))
                true
            }
            InstallRecoveryAction.RECOMMIT -> {
                onEvent(ClonePipelineEvent.AwaitingAndroid)
                installer.resumeCommit(record.stagedInstall)
                requireSuccessful(installer.awaitOutcome(record.stagedInstall))
                true
            }
            InstallRecoveryAction.COMPLETE -> true
            InstallRecoveryAction.IGNORE -> false
        }
    }

    private fun requireSuccessful(outcome: InstallSessionRecord?) {
        if (outcome?.state == InstallSessionState.SUCCEEDED) return
        throw IllegalStateException(
            outcome?.describeOutcome() ?: "Android did not report whether Thunder was installed.",
        )
    }

    private suspend fun selectedRuntimeProvider(cloneState: CloneInstallState): RuntimeBundleProvider {
        val verified = runtimeStore.latest()
        val installedVersion = (cloneState as? CloneInstallState.Installed)
            ?.clone
            ?.patchMarker
            ?.let { it as? PatchMarker.Valid }
            ?.runtimeVersion
            ?.let(SemanticVersion::parseStableRelease)
        return when (
            runtimeSourceFor(
                bundledVersion = bundledRuntimeVersion,
                verifiedVersion = verified?.version,
                installedVersion = installedVersion,
            )
        ) {
            RuntimeSource.BUNDLED -> bundledRuntimeProvider
            RuntimeSource.VERIFIED -> VerifiedRuntimeBundleProvider(requireNotNull(verified))
        }
    }

    private fun coordinator(runtimeProvider: RuntimeBundleProvider): PatchTransactionCoordinator {
        val backend = PurposeBuiltInjectionBackend(
            AssetBootstrapDexProvider(applicationContext),
            runtimeProvider,
            AssetBrandIconProvider(applicationContext),
        )
        return PatchTransactionCoordinator(
            context = applicationContext,
            backend = backend,
            bootstrapVersion = backend.version,
            runtimeContractVersion = RUNTIME_CONTRACT_VERSION,
        )
    }

    private suspend fun inspectInstalledClone(state: CloneInstallState.Installed): CloneUiInspection {
        val clone = state.clone
        val marker = clone.patchMarker as? PatchMarker.Valid
            ?: return CloneUiInspection.Blocked(
                "The installed dev.thunder.app has no valid Thunder marker, so Manager will not open or replace it.",
            )
        val sourcePackageName = marker.sourcePackageName
            ?: return CloneUiInspection.Blocked("The installed Thunder app has no verified Discord source.")
        val trustedSourceSigners = runCatching {
            DiscordTargetCatalog.forPackage(sourcePackageName).trustedSignerSha256
        }.getOrNull() ?: return CloneUiInspection.Blocked("The installed Thunder app names an unsupported Discord source.")
        val identity = identities.getExisting(THUNDER_PACKAGE_NAME)
            ?: return CloneUiInspection.Blocked(
                "Thunder’s update key is missing. The installed Thunder app was left untouched.",
            )
        val markerSigners = marker.sourceSignerSha256
        val trusted = marker.schemaVersion in setOf(2, 3) &&
            (marker.schemaVersion != 3 || marker.hostDexSha256?.matches(SHA_256) == true) &&
            marker.outputPackageName == THUNDER_PACKAGE_NAME &&
            marker.sourceVersionCode == clone.versionCode &&
            marker.sourceSetSha256?.matches(SHA_256) == true &&
            markerSigners?.size == 1 &&
            markerSigners.all { it in trustedSourceSigners } &&
            clone.currentSignerSha256.size == 1 &&
            clone.currentSignerSha256.single().equals(identity.certificateSha256, ignoreCase = true)
        if (!trusted) {
            return CloneUiInspection.Blocked(
                "The installed dev.thunder.app does not match this Manager’s marker and update key. Nothing will be replaced.",
            )
        }
        return CloneUiInspection.Trusted(
            dev.thunder.manager.ui.ThunderCloneUi(
                versionName = clone.versionName,
                versionCode = clone.versionCode,
                sourcePackageName = sourcePackageName,
                runtimeVersion = marker.runtimeVersion,
            ),
        )
    }

    private fun PatchPipelineProgress.toUiEvent(): ClonePipelineEvent.Progress = when (this) {
        is PatchPipelineProgress.Snapshot -> ClonePipelineEvent.Progress(
            label = "Copying app files safely…",
            fraction = progress.fraction,
        )
        is PatchPipelineProgress.Step -> ClonePipelineEvent.Progress(
            label = when (step) {
                PatchPipelineStep.SNAPSHOT -> "Preparing a private workspace…"
                PatchPipelineStep.VERIFY_INPUT -> "Checking the copied APKs…"
                PatchPipelineStep.ANALYSE_COMPATIBILITY -> "Checking this Discord build…"
                PatchPipelineStep.APPLY_BOOTSTRAP -> "Adding Thunder…"
                PatchPipelineStep.VERIFY_MUTATION -> "Checking the Thunder build…"
                PatchPipelineStep.SIGN_OUTPUT -> "Signing your Thunder app…"
                PatchPipelineStep.READY_FOR_INSTALL -> "Thunder is ready for Android…"
            },
        )
    }

    private fun PreflightBlockReason?.userMessage(): String = when (this) {
        PreflightBlockReason.BACKEND_INCOMPATIBLE ->
            "This Discord build is not compatible with Thunder yet. Official Discord was not changed."
        PreflightBlockReason.DEGRADED_REQUIRES_OVERRIDE ->
            "Thunder could not prove this Discord build is safe to patch."
        PreflightBlockReason.SOURCE_IS_PATCHED,
        PreflightBlockReason.INVALID_SOURCE_PATCH_MARKER,
        -> "Use an official, unmodified Discord installation as Thunder’s source."
        PreflightBlockReason.UNTRUSTED_SOURCE_SIGNER,
        PreflightBlockReason.INVALID_SOURCE_PROVENANCE,
        -> "Thunder could not verify that this Discord installation is official and unchanged."
        PreflightBlockReason.CLONE_UNAVAILABLE ->
            "Android would not let Thunder inspect the existing Thunder app."
        PreflightBlockReason.FOREIGN_CLONE,
        PreflightBlockReason.INVALID_CLONE_MARKER,
        -> "The installed dev.thunder.app was not created by this Thunder Manager, so it will not be replaced."
        PreflightBlockReason.SIGNING_IDENTITY_MISSING ->
            "Thunder’s update key is missing. The installed Thunder app was left untouched."
        PreflightBlockReason.DOWNGRADE_NOT_ALLOWED ->
            "This Discord source is older than the installed Thunder app. Install a newer official Discord first."
        null -> "Thunder could not safely prepare this app. Official Discord was not changed."
    }

    private companion object {
        const val RUNTIME_CONTRACT_VERSION = 1
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

internal enum class RuntimeSource {
    BUNDLED,
    VERIFIED,
}

internal fun runtimeSourceFor(
    bundledVersion: SemanticVersion,
    verifiedVersion: SemanticVersion?,
    installedVersion: SemanticVersion?,
): RuntimeSource {
    val source = if (verifiedVersion != null && verifiedVersion > bundledVersion) {
        RuntimeSource.VERIFIED
    } else {
        RuntimeSource.BUNDLED
    }
    val availableVersion = if (source == RuntimeSource.VERIFIED) {
        requireNotNull(verifiedVersion)
    } else {
        bundledVersion
    }
    require(installedVersion == null || availableVersion >= installedVersion) {
        "No verified Thunder runtime is new enough to update the installed Thunder app"
    }
    return source
}

private class VerifiedRuntimeBundleProvider(
    private val artifact: VerifiedRuntimeArtifact,
) : RuntimeBundleProvider {
    override fun load(): RuntimeBundle = RuntimeBundle(
        version = artifact.version.toString(),
        bytes = artifact.readBytes(),
    )
}
