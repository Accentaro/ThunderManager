package dev.thunder.patchorchestrator

import android.content.Context
import dev.thunder.injection.ApkArtifactInput
import dev.thunder.injection.ApkSetInput
import dev.thunder.injection.BackendAssessment
import dev.thunder.injection.InjectionBackend
import dev.thunder.injection.InjectionPlan
import dev.thunder.injection.MutatedApkSet
import dev.thunder.injection.MutationReport
import dev.thunder.packageinspector.AndroidPackageInventory
import dev.thunder.packageinspector.CloneInstallState
import dev.thunder.packageinspector.InstalledDiscordTarget
import dev.thunder.packageinspector.InstalledThunderClone
import dev.thunder.packageinspector.PackageArtifact
import dev.thunder.packageinspector.ThunderCloneCatalog
import dev.thunder.patchdomain.ApkSnapshotStore
import dev.thunder.patchdomain.SnapshotCleanupResult
import dev.thunder.patchdomain.SnapshotHandle
import dev.thunder.patchdomain.SnapshotProgress
import dev.thunder.signing.ApkSetSigner
import dev.thunder.signing.SignedApkSet
import dev.thunder.signing.SigningIdentityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

enum class PatchPipelineStep {
    SNAPSHOT,
    VERIFY_INPUT,
    ANALYSE_COMPATIBILITY,
    APPLY_BOOTSTRAP,
    VERIFY_MUTATION,
    SIGN_OUTPUT,
    READY_FOR_INSTALL,
}

sealed interface PatchPipelineProgress {
    data class Step(val step: PatchPipelineStep) : PatchPipelineProgress
    data class Snapshot(val progress: SnapshotProgress) : PatchPipelineProgress
}

data class PreparedPatchTransaction(
    val sourceTarget: InstalledDiscordTarget,
    val installedClone: InstalledThunderClone?,
    val outputPackageName: String,
    val snapshotHandles: List<SnapshotHandle>,
    val assessment: BackendAssessment,
    val mutationReport: MutationReport,
    val signedApkSet: SignedApkSet,
    val preflight: PreflightDecision.Allowed,
)

enum class PatchTransactionFailureCode {
    PREFLIGHT_BLOCKED,
    BACKEND_OUTPUT_INVALID,
}

class PatchTransactionException(
    val code: PatchTransactionFailureCode,
    val blockReason: PreflightBlockReason? = null,
    cause: Throwable? = null,
) : Exception(code.name, cause)

class PatchTransactionCoordinator(
    context: Context,
    private val backend: InjectionBackend,
    private val bootstrapVersion: String,
    private val runtimeContractVersion: Int,
) {
    private val snapshots = ApkSnapshotStore(context)
    private val packageInventory = AndroidPackageInventory(context)
    private val identities = SigningIdentityStore(context)
    private val signer = ApkSetSigner()

    suspend fun prepare(
        sourceTarget: InstalledDiscordTarget,
        allowDegraded: Boolean = false,
        onProgress: suspend (PatchPipelineProgress) -> Unit = {},
    ): PreparedPatchTransaction = prepare(
        sourceTarget = sourceTarget,
        cloneState = packageInventory.inspectClone(),
        allowDegraded = allowDegraded,
        onProgress = onProgress,
    )

    suspend fun prepare(
        sourceTarget: InstalledDiscordTarget,
        cloneState: CloneInstallState,
        allowDegraded: Boolean = false,
        onProgress: suspend (PatchPipelineProgress) -> Unit = {},
    ): PreparedPatchTransaction {
        val snapshotHandles = mutableListOf<SnapshotHandle>()
        onProgress(PatchPipelineProgress.Step(PatchPipelineStep.SNAPSHOT))
        val sourceHandle = snapshots.prepare(sourceTarget) { progress ->
            onProgress(PatchPipelineProgress.Snapshot(progress))
        }
        snapshotHandles += sourceHandle

        try {
            onProgress(PatchPipelineProgress.Step(PatchPipelineStep.VERIFY_INPUT))
            val verifiedSource = snapshots.verify(sourceHandle)
            val sourceInput = buildInput(sourceTarget, verifiedSource.inputFiles)
            val outputPackageName = ThunderCloneCatalog.forSource(sourceTarget.packageName).outputPackageName
            val sourceSetSha256 = setDigest(sourceInput.artifacts.map { it.splitName to it.sha256 })
            val existingIdentity = when (cloneState) {
                CloneInstallState.NotInstalled -> null
                is CloneInstallState.Installed -> identities.getExisting(outputPackageName)
                is CloneInstallState.Unavailable -> null
            }
            val provenance = when (val decision = PreflightPolicy.evaluateProvenance(
                sourceTarget = sourceTarget,
                cloneState = cloneState,
                sourceSetSha256 = sourceSetSha256,
                thunderCertificateSha256 = existingIdentity?.certificateSha256,
            )) {
                is PreflightDecision.Allowed -> decision
                is PreflightDecision.Blocked -> throw PatchTransactionException(
                    code = PatchTransactionFailureCode.PREFLIGHT_BLOCKED,
                    blockReason = decision.reason,
                )
            }

            val selectedInput = MutationInputPolicy.select(sourceTarget, cloneState, provenance)
            val (input, verifiedMutationInput) = when (selectedInput) {
                is MutationInputTarget.OfficialSource -> sourceInput to verifiedSource
                is MutationInputTarget.InstalledClone -> {
                    onProgress(PatchPipelineProgress.Step(PatchPipelineStep.SNAPSHOT))
                    val cloneHandle = snapshots.prepare(selectedInput.clone) { progress ->
                        onProgress(PatchPipelineProgress.Snapshot(progress))
                    }
                    snapshotHandles += cloneHandle
                    onProgress(PatchPipelineProgress.Step(PatchPipelineStep.VERIFY_INPUT))
                    val verifiedClone = snapshots.verify(cloneHandle)
                    val cloneInput = buildInput(selectedInput.clone, verifiedClone.inputFiles)
                    // Source provenance is now captured by sourceSetSha256 and the
                    // allowed decision. Release the large official snapshot before
                    // mutating the independently verified clone snapshot.
                    snapshots.discard(sourceHandle)
                    check(snapshotHandles.remove(sourceHandle)) {
                        "Verified official snapshot was not tracked"
                    }
                    cloneInput to verifiedClone
                }
            }

            onProgress(PatchPipelineProgress.Step(PatchPipelineStep.ANALYSE_COMPATIBILITY))
            val assessment = backend.analyse(input)
            val preflight = when (val decision = PreflightPolicy.evaluate(
                sourceTarget = sourceTarget,
                cloneState = cloneState,
                assessment = assessment,
                sourceSetSha256 = sourceSetSha256,
                thunderCertificateSha256 = existingIdentity?.certificateSha256,
                allowDegraded = allowDegraded,
            )) {
                is PreflightDecision.Allowed -> decision
                is PreflightDecision.Blocked -> throw PatchTransactionException(
                    code = PatchTransactionFailureCode.PREFLIGHT_BLOCKED,
                    blockReason = decision.reason,
                )
            }
            if (preflight != provenance) {
                throw PatchTransactionException(PatchTransactionFailureCode.BACKEND_OUTPUT_INVALID)
            }
            val identity = when (cloneState) {
                CloneInstallState.NotInstalled -> identities.getOrCreate(outputPackageName)
                is CloneInstallState.Installed -> existingIdentity
                is CloneInstallState.Unavailable -> null
            }

            val workspace = requireNotNull(verifiedMutationInput.signingOutputDirectory.parentFile)
            val mutationDirectory = File(workspace, "mutated")
            if (mutationDirectory.exists() || !mutationDirectory.mkdirs()) {
                throw PatchTransactionException(PatchTransactionFailureCode.BACKEND_OUTPUT_INVALID)
            }
            val plan = InjectionPlan(
                transactionId = verifiedMutationInput.handle.transactionId,
                input = input,
                outputPackageName = outputPackageName,
                outputDirectory = mutationDirectory,
                bootstrapVersion = bootstrapVersion,
                runtimeContractVersion = runtimeContractVersion,
            )

            onProgress(PatchPipelineProgress.Step(PatchPipelineStep.APPLY_BOOTSTRAP))
            val prepared = backend.prepare(plan)
            val mutated = backend.apply(prepared)
            validateOutput(input, mutated, outputPackageName, mutationDirectory)

            onProgress(PatchPipelineProgress.Step(PatchPipelineStep.VERIFY_MUTATION))
            val report = backend.verify(input, mutated)
            if (report.backendId != backend.id || report.backendVersion != backend.version) {
                throw PatchTransactionException(PatchTransactionFailureCode.BACKEND_OUTPUT_INVALID)
            }

            val orderedOutputs = input.artifacts.map { inputArtifact ->
                mutated.artifacts.singleOrNull { it.splitName == inputArtifact.splitName }?.file
                    ?: throw PatchTransactionException(PatchTransactionFailureCode.BACKEND_OUTPUT_INVALID)
            }
            onProgress(PatchPipelineProgress.Step(PatchPipelineStep.SIGN_OUTPUT))
            val signed = signer.sign(
                orderedOutputs,
                verifiedMutationInput.signingOutputDirectory,
                requireNotNull(identity) { "An allowed patch transaction has no signing identity" },
            )
            onProgress(PatchPipelineProgress.Step(PatchPipelineStep.READY_FOR_INSTALL))
            return PreparedPatchTransaction(
                sourceTarget = sourceTarget,
                installedClone = (cloneState as? CloneInstallState.Installed)?.clone,
                outputPackageName = outputPackageName,
                snapshotHandles = snapshotHandles.toList(),
                assessment = assessment,
                mutationReport = report,
                signedApkSet = signed,
                preflight = preflight,
            )
        } catch (cancelled: CancellationException) {
            discardSnapshots(snapshotHandles)
            throw cancelled
        } catch (error: Exception) {
            runCatching { discardSnapshots(snapshotHandles) }
            throw error
        }
    }

    suspend fun discard(transaction: PreparedPatchTransaction) {
        discardSnapshots(transaction.snapshotHandles)
    }

    suspend fun reapAbandonedTransactions(): SnapshotCleanupResult =
        snapshots.reapAbandonedTransactions()

    private suspend fun buildInput(target: InstalledDiscordTarget, files: List<File>): ApkSetInput =
        buildInput(
            packageName = target.packageName,
            versionCode = target.versionCode,
            artifacts = target.artifacts,
            currentSignerSha256 = target.currentSignerSha256,
            versionName = target.versionName,
            files = files,
        )

    private suspend fun buildInput(target: InstalledThunderClone, files: List<File>): ApkSetInput =
        buildInput(
            packageName = target.packageName,
            versionCode = target.versionCode,
            artifacts = target.artifacts,
            currentSignerSha256 = target.currentSignerSha256,
            versionName = target.versionName,
            files = files,
        )

    private suspend fun buildInput(
        packageName: String,
        versionCode: Long,
        artifacts: List<PackageArtifact>,
        currentSignerSha256: List<String>,
        versionName: String,
        files: List<File>,
    ): ApkSetInput =
        withContext(Dispatchers.IO) {
            if (files.size != artifacts.size) {
                throw PatchTransactionException(PatchTransactionFailureCode.BACKEND_OUTPUT_INVALID)
            }
            ApkSetInput(
                packageName = packageName,
                versionCode = versionCode,
                artifacts = artifacts.zip(files).map { (artifact, file) ->
                    ApkArtifactInput(artifact.splitName, file, sha256(file))
                },
                sourceSignerSha256 = currentSignerSha256,
                versionName = versionName,
            )
        }

    private suspend fun discardSnapshots(handles: List<SnapshotHandle>) {
        var firstFailure: Throwable? = null
        for (handle in handles.asReversed()) {
            try {
                snapshots.discard(handle)
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                } else {
                    firstFailure.addSuppressed(error)
                }
            }
        }
        firstFailure?.let { throw it }
    }

    private fun validateOutput(
        input: ApkSetInput,
        output: MutatedApkSet,
        expectedOutputPackageName: String,
        outputDirectory: File,
    ) {
        if (output.packageName != expectedOutputPackageName
            || output.versionCode != input.versionCode
            || output.artifacts.size != input.artifacts.size
            || output.artifacts.count { it.splitName == null } != 1
            || output.artifacts.map { it.splitName }.toSet() != input.artifacts.map { it.splitName }.toSet()
        ) {
            throw PatchTransactionException(PatchTransactionFailureCode.BACKEND_OUTPUT_INVALID)
        }
        val canonicalDirectory = outputDirectory.canonicalFile
        for (artifact in output.artifacts) {
            if (!artifact.file.isFile
                || !artifact.file.canRead()
                || artifact.file.length() <= 0L
                || artifact.file.canonicalFile.parentFile != canonicalDirectory
            ) {
                throw PatchTransactionException(PatchTransactionFailureCode.BACKEND_OUTPUT_INVALID)
            }
        }
    }

    private fun setDigest(entries: List<Pair<String?, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((splitName, hash) in entries.sortedBy { it.first ?: "" }) {
            digest.update((splitName ?: "base").toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(hash.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
