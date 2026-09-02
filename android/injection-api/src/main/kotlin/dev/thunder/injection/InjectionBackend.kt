package dev.thunder.injection

import java.io.File

data class ApkSetInput(
    val packageName: String,
    val versionCode: Long,
    val artifacts: List<ApkArtifactInput>,
    val sourceSignerSha256: List<String>,
    /** The host's own version label, which a backend may hand to the runtime it embeds. */
    val versionName: String = "",
)

data class ApkArtifactInput(
    val splitName: String?,
    val file: File,
    val sha256: String,
) {
    val isBase: Boolean get() = splitName == null
}

enum class BackendCompatibility {
    COMPATIBLE,
    DEGRADED,
    INCOMPATIBLE,
}

data class BackendAssessment(
    val backendId: String,
    val backendVersion: String,
    val compatibility: BackendCompatibility,
    val evidence: List<AssessmentEvidence>,
    val blockingReasons: List<String>,
)

data class AssessmentEvidence(
    val id: String,
    val passed: Boolean,
    val detail: String,
)

data class InjectionPlan(
    val transactionId: String,
    val input: ApkSetInput,
    val outputPackageName: String,
    val outputDirectory: File,
    val bootstrapVersion: String,
    val runtimeContractVersion: Int,
)

data class PreparedInjection(
    val plan: InjectionPlan,
    val privateState: Map<String, String>,
)

data class MutatedApkSet(
    val packageName: String,
    val versionCode: Long,
    val artifacts: List<MutatedApkArtifact>,
)

data class MutatedApkArtifact(
    val splitName: String?,
    val file: File,
)

data class MutationReport(
    val backendId: String,
    val backendVersion: String,
    val changedEntries: List<ChangedArchiveEntry>,
    val changedManifestFields: List<String>,
    val inputSetSha256: String,
    val outputSetSha256: String,
)

data class ChangedArchiveEntry(
    val artifactSplitName: String?,
    val entryName: String,
    val change: ArchiveEntryChange,
)

enum class ArchiveEntryChange {
    ADDED,
    REPLACED,
    REMOVED,
}

interface InjectionBackend {
    val id: String
    val version: String

    suspend fun analyse(input: ApkSetInput): BackendAssessment
    suspend fun prepare(plan: InjectionPlan): PreparedInjection
    suspend fun apply(prepared: PreparedInjection): MutatedApkSet
    suspend fun verify(input: ApkSetInput, output: MutatedApkSet): MutationReport
}
