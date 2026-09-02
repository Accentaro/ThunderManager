package dev.thunder.injection.custom

import dev.thunder.injection.ApkSetInput
import dev.thunder.injection.ArchiveEntryChange
import dev.thunder.injection.AssessmentEvidence
import dev.thunder.injection.BackendAssessment
import dev.thunder.injection.BackendCompatibility
import dev.thunder.injection.ChangedArchiveEntry
import dev.thunder.injection.InjectionBackend
import dev.thunder.injection.InjectionPlan
import dev.thunder.injection.MutatedApkArtifact
import dev.thunder.injection.MutatedApkSet
import dev.thunder.injection.MutationReport
import dev.thunder.injection.PreparedInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile

fun interface BootstrapDexProvider {
    fun load(): ByteArray
}

data class RuntimeBundle(
    val version: String,
    val bytes: ByteArray,
)

fun interface RuntimeBundleProvider {
    fun load(): RuntimeBundle
}

fun interface BrandIconProvider {
    fun load(): ByteArray
}

class PurposeBuiltInjectionBackend internal constructor(
    private val dexProvider: BootstrapDexProvider,
    private val runtimeProvider: RuntimeBundleProvider,
    private val brandIconProvider: BrandIconProvider?,
    private val reactNativeDexWeaver: ReactNativeDexWeaver,
    private val chatBubblesDexWeaver: ChatBubblesDexWeaver = DexlibChatBubblesDexWeaver(),
) : InjectionBackend {
    constructor(dexProvider: BootstrapDexProvider, runtimeProvider: RuntimeBundleProvider) :
        this(dexProvider, runtimeProvider, null, DexlibReactNativeDexWeaver())

    internal constructor(
        dexProvider: BootstrapDexProvider,
        runtimeProvider: RuntimeBundleProvider,
        reactNativeDexWeaver: ReactNativeDexWeaver,
    ) : this(dexProvider, runtimeProvider, null, reactNativeDexWeaver)

    internal constructor(
        dexProvider: BootstrapDexProvider,
        runtimeProvider: RuntimeBundleProvider,
        reactNativeDexWeaver: ReactNativeDexWeaver,
        chatBubblesDexWeaver: ChatBubblesDexWeaver,
    ) : this(dexProvider, runtimeProvider, null, reactNativeDexWeaver, chatBubblesDexWeaver)

    constructor(
        dexProvider: BootstrapDexProvider,
        runtimeProvider: RuntimeBundleProvider,
        brandIconProvider: BrandIconProvider,
    ) : this(dexProvider, runtimeProvider, brandIconProvider, DexlibReactNativeDexWeaver())

    override val id: String = "thunder.purpose-built"
    override val version: String = "0.6.0"

    // A patch transaction reuses one immutable, content-addressed snapshot for analyse, prepare,
    // and verify. Cache only the small proof result: never retain a host DEX byte array.
    private val inspectionCache = object : LinkedHashMap<InspectionCacheKey, BaseInspection>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InspectionCacheKey, BaseInspection>?): Boolean =
            size > MAX_INSPECTION_CACHE_ENTRIES
    }

    override suspend fun analyse(input: ApkSetInput): BackendAssessment = withContext(Dispatchers.IO) {
        val evidence = mutableListOf<AssessmentEvidence>()
        val blockers = mutableListOf<String>()

        val base = input.artifacts.singleOrNull { it.isBase }
        if (base == null) {
            blockers += "base-apk-cardinality"
            if (!SUPPORTED_PACKAGES.matches(input.packageName)) blockers += "unsupported-package"
            evidence += AssessmentEvidence("supported-package", SUPPORTED_PACKAGES.matches(input.packageName), input.packageName)
            evidence += AssessmentEvidence("single-base-apk", false, "Expected exactly one base APK")
        } else {
            evidence += AssessmentEvidence("single-base-apk", true, base.file.name)
            runCatching { inspectBase(base.file, input.packageName, input.versionCode, base.sha256) }
                .onSuccess { inspection ->
                    val supported = SUPPORTED_PACKAGES.matches(inspection.sourcePackageName)
                    if (!supported) blockers += "unsupported-package"
                    evidence += AssessmentEvidence("supported-package", supported, inspection.sourcePackageName)
                    evidence += AssessmentEvidence("binary-manifest-factory", true, inspection.originalFactory)
                    evidence += AssessmentEvidence(
                        "contiguous-dex-set",
                        true,
                        if (inspection.mode == InputMode.STOCK) "Next entry ${inspection.dexEntry}" else "Existing entry ${inspection.dexEntry}",
                    )
                    evidence += AssessmentEvidence(
                        "react-native-bundle-seam",
                        true,
                        if (inspection.mode == InputMode.STOCK) "Two host calls in ${inspection.hostDexEntry}" else "Two Thunder bridge calls in ${inspection.hostDexEntry}",
                    )
                    evidence += AssessmentEvidence(
                        "thunder-surface",
                        true,
                        if (inspection.mode == InputMode.STOCK) "Clean stock input" else "Authenticated runtime refresh input",
                    )
                    evidence += AssessmentEvidence(
                        "chat-bubbles-native-seam",
                        inspection.chatBubblesDexEntry != null,
                        inspection.chatBubblesDexEntry ?: "Unsupported host shape; core injection remains available",
                    )
                }
                .onFailure { error ->
                    if (!SUPPORTED_PACKAGES.matches(input.packageName)) blockers += "unsupported-package"
                    evidence += AssessmentEvidence("supported-package", SUPPORTED_PACKAGES.matches(input.packageName), input.packageName)
                    blockers += "unsupported-base-layout"
                    evidence += AssessmentEvidence(
                        "binary-manifest-factory",
                        false,
                        "${error.javaClass.simpleName}: ${error.message ?: "no detail"}",
                    )
                }
        }
        BackendAssessment(
            backendId = id,
            backendVersion = version,
            compatibility = if (blockers.isEmpty()) BackendCompatibility.COMPATIBLE else BackendCompatibility.INCOMPATIBLE,
            evidence = evidence,
            blockingReasons = blockers.distinct(),
        )
    }

    override suspend fun prepare(plan: InjectionPlan): PreparedInjection = withContext(Dispatchers.IO) {
        val assessment = analyse(plan.input)
        if (assessment.compatibility == BackendCompatibility.INCOMPATIBLE || assessment.blockingReasons.isNotEmpty()) {
            throw IOException("Purpose-built backend rejected input: ${assessment.blockingReasons.joinToString()}")
        }
        requireSafeVersion(plan.bootstrapVersion)
        if (plan.runtimeContractVersion < 1) throw IOException("Runtime contract version is invalid")
        val base = plan.input.artifacts.single { it.isBase }
        val inspection = inspectBase(base.file, plan.input.packageName, plan.input.versionCode, base.sha256)
        validateOutputIdentity(plan.input.packageName, plan.outputPackageName, inspection)
        for (artifact in plan.input.artifacts) {
            val manifest = readBoundedEntry(artifact.file, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
            if (BinaryAndroidManifest.readPackageName(manifest) != plan.input.packageName) {
                throw IOException("APK split package identity differs from the selected source")
            }
            if (inspection.mode == InputMode.THUNDER_REFRESH && inspection.markerSchemaVersion == 2) {
                BinaryAndroidManifest.migrateSchema2CloneIdentities(
                    manifest,
                    inspection.sourcePackageName,
                    plan.outputPackageName,
                )
            }
            expectedResourceTable(
                artifact.file,
                inspection.mode,
                plan.input.packageName,
                inspection.sourcePackageName,
                plan.outputPackageName,
            )
        }
        val dex = dexProvider.load()
        validateDex(dex)
        emittedRuntime(plan.input, inspection.sourcePackageName, inspection.chatBubblesDexEntry != null)
        val sourceSignerSha256 = inspection.sourceSignerSha256 ?: normalizedSignerDigests(plan.input.sourceSignerSha256)
        val sourceSetSha256 = inspection.sourceSetSha256
            ?: setDigest(plan.input.artifacts.map { it.splitName to it.sha256 })
        val sourceVersionCode = inspection.sourceVersionCode ?: plan.input.versionCode
        PreparedInjection(
            plan = plan,
            privateState = buildMap {
                putAll(mapOf(
                "dexEntry" to inspection.dexEntry,
                "hostDexEntry" to inspection.hostDexEntry,
                "inputMode" to inspection.mode.name,
                "originalFactory" to inspection.originalFactory,
                "sourcePackageName" to inspection.sourcePackageName,
                "sourceSetSha256" to sourceSetSha256,
                "sourceSignerSha256" to sourceSignerSha256.joinToString(","),
                "sourceVersionCode" to sourceVersionCode.toString(),
                ))
                inspection.hostDexSha256?.let { put("hostDexSha256", it) }
                inspection.chatBubblesDexEntry?.let { put("chatBubblesDexEntry", it) }
                inspection.chatBubblesDexSha256?.let { put("chatBubblesDexSha256", it) }
                inspection.markerSchemaVersion?.let { put("inputMarkerSchemaVersion", it.toString()) }
            },
        )
    }

    override suspend fun apply(prepared: PreparedInjection): MutatedApkSet = withContext(Dispatchers.IO) {
        val plan = prepared.plan
        val dexEntry = prepared.privateState.getValue("dexEntry")
        val hostDexEntry = prepared.privateState.getValue("hostDexEntry")
        val chatBubblesDexEntry = prepared.privateState["chatBubblesDexEntry"]
        val chatBubblesDexSha256 = prepared.privateState["chatBubblesDexSha256"]
        val rewriteChatBubblesDex = chatBubblesDexEntry != null && chatBubblesDexSha256 == null
        if (chatBubblesDexEntry == null && chatBubblesDexSha256 != null) {
            throw IOException("Prepared ChatBubbles DEX identity is incomplete")
        }
        if (chatBubblesDexEntry != null && (!DEX_NAME.matches(chatBubblesDexEntry) || chatBubblesDexEntry == dexEntry)) {
            throw IOException("Prepared ChatBubbles DEX entry is invalid")
        }
        if (chatBubblesDexSha256 != null && !SHA_256.matches(chatBubblesDexSha256)) {
            throw IOException("Prepared ChatBubbles DEX identity is invalid")
        }
        val inputMode = InputMode.valueOf(prepared.privateState.getValue("inputMode"))
        val inputMarkerSchemaVersion = prepared.privateState["inputMarkerSchemaVersion"]?.toIntOrNull()
        if (inputMode == InputMode.THUNDER_REFRESH && inputMarkerSchemaVersion !in setOf(2, 3)) {
            throw IOException("Prepared refresh marker schema is invalid")
        }
        val originalFactory = prepared.privateState.getValue("originalFactory")
        val sourcePackageName = prepared.privateState.getValue("sourcePackageName")
        val sourceSetSha256 = prepared.privateState.getValue("sourceSetSha256")
        val sourceSignerSha256 = prepared.privateState.getValue("sourceSignerSha256").split(',')
        val sourceVersionCode = prepared.privateState.getValue("sourceVersionCode").toLong()
        val outputPackageName = plan.outputPackageName
        val dex = dexProvider.load().also(::validateDex)
        val runtime = emittedRuntime(plan.input, sourcePackageName, chatBubblesDexEntry != null)
        val runtimeSha256 = sha256(runtime.bytes)
        val brandIcon = brandIconProvider?.load()?.also(::validateBrandIcon)
        val outputs = ArrayList<MutatedApkArtifact>(plan.input.artifacts.size)
        for ((index, artifact) in plan.input.artifacts.withIndex()) {
            val output = File(plan.outputDirectory, if (artifact.isBase) "base.apk" else "split-$index.apk")
            val resourceTable = expectedResourceTable(
                artifact.file,
                inputMode,
                plan.input.packageName,
                sourcePackageName,
                outputPackageName,
            )
            val brandIconEntries = if (brandIcon == null) emptySet() else brandIconEntries(artifact.file)
            val schema2Manifest = if (
                inputMode == InputMode.THUNDER_REFRESH && inputMarkerSchemaVersion == 2
            ) {
                BinaryAndroidManifest.migrateSchema2CloneIdentities(
                    readBoundedEntry(artifact.file, MANIFEST_ENTRY, MAX_MANIFEST_BYTES),
                    sourcePackageName,
                    outputPackageName,
                )
            } else {
                null
            }
            if (artifact.isBase) {
                val configuration = configuration(
                    originalFactory,
                    plan.runtimeContractVersion,
                    runtimeSha256,
                    runtime.version,
                )
                if (inputMode == InputMode.STOCK) {
                    val manifest = readBoundedEntry(artifact.file, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
                    val identity = BinaryAndroidManifest.rewriteBaseForClone(
                        manifest,
                        plan.input.packageName,
                        outputPackageName,
                        THUNDER_APPLICATION_LABEL,
                    )
                    val rewritten = BinaryAndroidManifest.replaceFactoryAndDeclareRecovery(
                        identity.bytes,
                        THUNDER_FACTORY,
                        THUNDER_RECOVERY_ACTIVITY,
                    )
                    if (rewritten.originalFactory != originalFactory) throw IOException("Host factory changed after backend preparation")
                    val originalHostDex = readBoundedEntry(artifact.file, hostDexEntry, MAX_HOST_DEX_BYTES)
                    val rewrittenDexEntries = linkedMapOf(
                        hostDexEntry to reactNativeDexWeaver.rewrite(originalHostDex),
                    )
                    chatBubblesDexEntry?.let { entry ->
                        val chatSource = rewrittenDexEntries[entry]
                            ?: readBoundedEntry(artifact.file, entry, MAX_HOST_DEX_BYTES)
                        rewrittenDexEntries[entry] = chatBubblesDexWeaver.rewrite(chatSource)
                    }
                    val rewrittenHostDex = rewrittenDexEntries.getValue(hostDexEntry)
                    val rewrittenChatBubblesDexSha256 = chatBubblesDexEntry?.let { entry ->
                        sha256(rewrittenDexEntries.getValue(entry))
                    }
                    val marker = marker(
                        sourcePackageName = sourcePackageName,
                        outputPackageName = outputPackageName,
                        sourceVersionCode = sourceVersionCode,
                        sourceSignerSha256 = sourceSignerSha256,
                        sourceSetSha256 = sourceSetSha256,
                        bootstrapVersion = plan.bootstrapVersion,
                        dexEntry = dexEntry,
                        hostDexEntry = hostDexEntry,
                        hostDexSha256 = sha256(rewrittenHostDex),
                        chatBubblesDexEntry = chatBubblesDexEntry,
                        chatBubblesDexSha256 = rewrittenChatBubblesDexSha256,
                        runtimeSha256 = runtimeSha256,
                        runtimeVersion = runtime.version,
                    )
                    val replacedNames = mutableSetOf(MANIFEST_ENTRY).apply { addAll(rewrittenDexEntries.keys) }
                    val addedEntries = mutableListOf(
                        StableApkArchive.AddedEntry(MANIFEST_ENTRY, rewritten.bytes, alignment = 4),
                    )
                    rewrittenDexEntries.forEach { (entry, bytes) ->
                        addedEntries += StableApkArchive.AddedEntry(entry, bytes, alignment = 4)
                    }
                    addedEntries += listOf(
                        StableApkArchive.AddedEntry(dexEntry, dex, alignment = 4),
                        StableApkArchive.AddedEntry(CONFIG_ENTRY, configuration, alignment = 1),
                        StableApkArchive.AddedEntry(MARKER_ENTRY, marker, alignment = 1),
                        StableApkArchive.AddedEntry(RUNTIME_ENTRY, runtime.bytes, alignment = 1),
                    )
                    if (resourceTable?.changed == true) {
                        replacedNames += RESOURCES_ENTRY
                        addedEntries += StableApkArchive.AddedEntry(RESOURCES_ENTRY, resourceTable.bytes, alignment = 4)
                    }
                    addBrandIconReplacements(replacedNames, addedEntries, brandIconEntries, brandIcon)
                    StableApkArchive.rewrite(
                        input = artifact.file,
                        output = output,
                        replacedNames = replacedNames,
                        addedEntries = addedEntries,
                    )
                } else {
                    val preparedHostDexSha256 = prepared.privateState.getValue("hostDexSha256")
                    if (!SHA_256.matches(preparedHostDexSha256)) throw IOException("Prepared host DEX identity is invalid")
                    val rewrittenChatBubblesDex = if (rewriteChatBubblesDex) {
                        chatBubblesDexWeaver.rewrite(
                            readBoundedEntry(artifact.file, requireNotNull(chatBubblesDexEntry), MAX_HOST_DEX_BYTES),
                        )
                    } else {
                        null
                    }
                    val outputHostDexSha256 = if (
                        chatBubblesDexEntry == hostDexEntry && rewrittenChatBubblesDex != null
                    ) {
                        sha256(rewrittenChatBubblesDex)
                    } else {
                        preparedHostDexSha256
                    }
                    val outputChatBubblesDexSha256 = chatBubblesDexEntry?.let {
                        rewrittenChatBubblesDex?.let(::sha256) ?: requireNotNull(chatBubblesDexSha256)
                    }
                    val marker = marker(
                        sourcePackageName = sourcePackageName,
                        outputPackageName = outputPackageName,
                        sourceVersionCode = sourceVersionCode,
                        sourceSignerSha256 = sourceSignerSha256,
                        sourceSetSha256 = sourceSetSha256,
                        bootstrapVersion = plan.bootstrapVersion,
                        dexEntry = dexEntry,
                        hostDexEntry = hostDexEntry,
                        hostDexSha256 = outputHostDexSha256,
                        chatBubblesDexEntry = chatBubblesDexEntry,
                        chatBubblesDexSha256 = outputChatBubblesDexSha256,
                        runtimeSha256 = runtimeSha256,
                        runtimeVersion = runtime.version,
                    )
                    val replacedNames = mutableSetOf(dexEntry, CONFIG_ENTRY, MARKER_ENTRY, RUNTIME_ENTRY)
                    val addedEntries = mutableListOf(
                        StableApkArchive.AddedEntry(dexEntry, dex, alignment = 4),
                        StableApkArchive.AddedEntry(CONFIG_ENTRY, configuration, alignment = 1),
                        StableApkArchive.AddedEntry(MARKER_ENTRY, marker, alignment = 1),
                        StableApkArchive.AddedEntry(RUNTIME_ENTRY, runtime.bytes, alignment = 1),
                    )
                    if (rewrittenChatBubblesDex != null) {
                        replacedNames += requireNotNull(chatBubblesDexEntry)
                        addedEntries += StableApkArchive.AddedEntry(
                            chatBubblesDexEntry,
                            rewrittenChatBubblesDex,
                            alignment = 4,
                        )
                    }
                    if (schema2Manifest?.changedManifestFields?.isNotEmpty() == true) {
                        replacedNames += MANIFEST_ENTRY
                        addedEntries += StableApkArchive.AddedEntry(
                            MANIFEST_ENTRY,
                            schema2Manifest.bytes,
                            alignment = 4,
                        )
                    }
                    if (resourceTable?.changed == true) {
                        replacedNames += RESOURCES_ENTRY
                        addedEntries += StableApkArchive.AddedEntry(RESOURCES_ENTRY, resourceTable.bytes, alignment = 4)
                    }
                    addBrandIconReplacements(replacedNames, addedEntries, brandIconEntries, brandIcon)
                    StableApkArchive.rewrite(
                        input = artifact.file,
                        output = output,
                        replacedNames = replacedNames,
                        addedEntries = addedEntries,
                    )
                }
            } else {
                if (inputMode == InputMode.STOCK) {
                    val manifest = readBoundedEntry(artifact.file, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
                    val rewrittenManifest = BinaryAndroidManifest.rewritePackageAndCoexistenceNames(
                        manifest,
                        plan.input.packageName,
                        outputPackageName,
                    )
                    val replacedNames = mutableSetOf(MANIFEST_ENTRY)
                    val addedEntries = mutableListOf(
                        StableApkArchive.AddedEntry(MANIFEST_ENTRY, rewrittenManifest.bytes, alignment = 4),
                    )
                    if (resourceTable?.changed == true) {
                        replacedNames += RESOURCES_ENTRY
                        addedEntries += StableApkArchive.AddedEntry(RESOURCES_ENTRY, resourceTable.bytes, alignment = 4)
                    }
                    addBrandIconReplacements(replacedNames, addedEntries, brandIconEntries, brandIcon)
                    StableApkArchive.rewrite(
                        input = artifact.file,
                        output = output,
                        replacedNames = replacedNames,
                        addedEntries = addedEntries,
                    )
                } else {
                    val replacedNames = mutableSetOf<String>()
                    val addedEntries = mutableListOf<StableApkArchive.AddedEntry>()
                    if (schema2Manifest?.changedManifestFields?.isNotEmpty() == true) {
                        replacedNames += MANIFEST_ENTRY
                        addedEntries += StableApkArchive.AddedEntry(
                            MANIFEST_ENTRY,
                            schema2Manifest.bytes,
                            alignment = 4,
                        )
                    }
                    if (resourceTable?.changed == true) {
                        replacedNames += RESOURCES_ENTRY
                        addedEntries += StableApkArchive.AddedEntry(RESOURCES_ENTRY, resourceTable.bytes, alignment = 4)
                    }
                    addBrandIconReplacements(replacedNames, addedEntries, brandIconEntries, brandIcon)
                    StableApkArchive.rewrite(
                        input = artifact.file,
                        output = output,
                        replacedNames = replacedNames,
                        addedEntries = addedEntries,
                    )
                }
            }
            outputs += MutatedApkArtifact(artifact.splitName, output)
        }
        MutatedApkSet(outputPackageName, plan.input.versionCode, outputs)
    }

    override suspend fun verify(input: ApkSetInput, output: MutatedApkSet): MutationReport = withContext(Dispatchers.IO) {
        if (input.versionCode != output.versionCode) throw IOException("Mutated APK version changed")
        val inputBySplit = input.artifacts.associateBy { it.splitName }
        val outputBySplit = output.artifacts.associateBy { it.splitName }
        if (inputBySplit.keys != outputBySplit.keys) throw IOException("Mutated APK split closure changed")
        val inputBaseArtifact = inputBySplit.getValue(null)
        val inputBase = inputBaseArtifact.file
        val outputBase = outputBySplit.getValue(null).file
        val base = inspectBase(inputBase, input.packageName, input.versionCode, inputBaseArtifact.sha256)
        val rewritesChatBubblesDex = base.chatBubblesDexEntry != null && base.chatBubblesDexSha256 == null
        validateOutputIdentity(input.packageName, output.packageName, base)
        val outputManifest = readBoundedEntry(outputBase, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
        if (BinaryAndroidManifest.readPackageName(outputManifest) != output.packageName) {
            throw IOException("Mutated base package identity is invalid")
        }
        if (BinaryAndroidManifest.readFactory(outputManifest) != THUNDER_FACTORY) throw IOException("Bootstrap factory verification failed")
        if (!BinaryAndroidManifest.hasExportedActivity(outputManifest, THUNDER_RECOVERY_ACTIVITY)) throw IOException("Recovery activity verification failed")
        val inputBaseManifest = readBoundedEntry(inputBase, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
        val cloneIdentity = if (base.mode == InputMode.STOCK) {
            BinaryAndroidManifest.rewriteBaseForClone(
                inputBaseManifest,
                input.packageName,
                output.packageName,
                THUNDER_APPLICATION_LABEL,
            )
        } else {
            null
        }
        val schema2BaseIdentity = if (
            base.mode == InputMode.THUNDER_REFRESH && base.markerSchemaVersion == 2
        ) {
            BinaryAndroidManifest.migrateSchema2CloneIdentities(
                inputBaseManifest,
                base.sourcePackageName,
                output.packageName,
            )
        } else {
            null
        }
        val expectedBaseManifest = when {
            cloneIdentity != null -> BinaryAndroidManifest.replaceFactoryAndDeclareRecovery(
                cloneIdentity.bytes,
                THUNDER_FACTORY,
                THUNDER_RECOVERY_ACTIVITY,
            ).bytes
            schema2BaseIdentity != null -> schema2BaseIdentity.bytes
            else -> inputBaseManifest
        }
        if (!outputManifest.contentEquals(expectedBaseManifest)) throw IOException("Mutated base manifest differs from the allowlist")

        val splitManifestChanges = linkedMapOf<String, List<String>>()
        val refreshManifestChanges = mutableListOf<ChangedArchiveEntry>()
        val resourceChanges = mutableListOf<ChangedArchiveEntry>()
        val brandIconChanges = mutableListOf<ChangedArchiveEntry>()
        val expectedBrandIcon = brandIconProvider?.load()?.also(::validateBrandIcon)
        for ((split, inputArtifact) in inputBySplit) {
            val outputArtifact = outputBySplit.getValue(split)
            val resourceTable = expectedResourceTable(
                inputArtifact.file,
                base.mode,
                input.packageName,
                base.sourcePackageName,
                output.packageName,
            )
            val resourceChanged = resourceTable?.changed == true
            val brandedEntries = if (expectedBrandIcon == null) emptySet() else brandIconEntries(inputArtifact.file)
            val inputManifest = readBoundedEntry(inputArtifact.file, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
            val expectedManifest = if (split == null) {
                expectedBaseManifest
            } else {
                val rewrite = when {
                    base.mode == InputMode.STOCK -> BinaryAndroidManifest.rewritePackageAndCoexistenceNames(
                        inputManifest,
                        input.packageName,
                        output.packageName,
                    )
                    base.markerSchemaVersion == 2 -> BinaryAndroidManifest.migrateSchema2CloneIdentities(
                        inputManifest,
                        base.sourcePackageName,
                        output.packageName,
                    )
                    else -> null
                }
                splitManifestChanges[split] = rewrite?.changedManifestFields.orEmpty()
                rewrite?.bytes ?: inputManifest
            }
            val manifestChanged = !expectedManifest.contentEquals(inputManifest)
            if (base.mode == InputMode.THUNDER_REFRESH && manifestChanged) {
                refreshManifestChanges += ChangedArchiveEntry(split, MANIFEST_ENTRY, ArchiveEntryChange.REPLACED)
            }
            val inputFacts = archiveFacts(inputArtifact.file)
            val outputFacts = archiveFacts(outputArtifact.file)
            val allowedAdded = if (split == null && base.mode == InputMode.STOCK) {
                setOf(base.dexEntry, CONFIG_ENTRY, MARKER_ENTRY, RUNTIME_ENTRY)
            } else {
                emptySet()
            }
            val expectedNames = inputFacts.keys.filterNot(::isJarSignature).toSet() + allowedAdded
            if (outputFacts.keys != expectedNames) throw IOException("Mutated APK entry set differs from the allowlist: ${split ?: "base"}")
            for ((name, facts) in inputFacts) {
                val mutable = when {
                    manifestChanged && name == MANIFEST_ENTRY -> true
                    resourceChanged && name == RESOURCES_ENTRY -> true
                    name in brandedEntries -> true
                    base.mode == InputMode.STOCK && split == null ->
                        name == MANIFEST_ENTRY || name == base.hostDexEntry || name == base.chatBubblesDexEntry
                    base.mode == InputMode.STOCK -> name == MANIFEST_ENTRY
                    split == null ->
                        name == base.dexEntry || name == CONFIG_ENTRY || name == MARKER_ENTRY || name == RUNTIME_ENTRY ||
                            rewritesChatBubblesDex && name == base.chatBubblesDexEntry
                    else -> false
                }
                if (mutable || isJarSignature(name)) continue
                if (outputFacts[name] != facts) throw IOException("Unallowlisted APK entry changed: ${split ?: "base"}:$name")
            }

            for (name in brandedEntries) {
                val actualIcon = readBoundedEntry(outputArtifact.file, name, MAX_BRAND_ICON_BYTES)
                if (!actualIcon.contentEquals(expectedBrandIcon)) {
                    throw IOException("Mutated launcher icon differs from the Thunder brand asset: ${split ?: "base"}:$name")
                }
                brandIconChanges += ChangedArchiveEntry(split, name, ArchiveEntryChange.REPLACED)
            }

            if (resourceTable != null) {
                val actualResources = readBoundedEntry(outputArtifact.file, RESOURCES_ENTRY, MAX_RESOURCES_BYTES)
                if (!actualResources.contentEquals(resourceTable.bytes)) {
                    throw IOException("Mutated resource table differs from the package-name allowlist: ${split ?: "base"}")
                }
                if (BinaryResourceTable.readPackageName(actualResources) != output.packageName) {
                    throw IOException("Mutated resource package identity is invalid: ${split ?: "base"}")
                }
                if (resourceChanged) {
                    resourceChanges += ChangedArchiveEntry(split, RESOURCES_ENTRY, ArchiveEntryChange.REPLACED)
                }
            }

            val actualManifest = readBoundedEntry(outputArtifact.file, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
            if (BinaryAndroidManifest.readPackageName(actualManifest) != output.packageName) {
                throw IOException("Mutated APK split package identity is invalid: ${split ?: "base"}")
            }
            if (!actualManifest.contentEquals(expectedManifest)) {
                throw IOException("Mutated manifest differs from the identity allowlist: ${split ?: "base"}")
            }
        }
        if (base.mode == InputMode.STOCK) {
            val originalHostDex = readBoundedEntry(inputBase, base.hostDexEntry, MAX_HOST_DEX_BYTES)
            val outputHostDex = readBoundedEntry(outputBase, base.hostDexEntry, MAX_HOST_DEX_BYTES)
            if (base.chatBubblesDexEntry == base.hostDexEntry) {
                val reactNativeOnly = reactNativeDexWeaver.rewrite(originalHostDex)
                reactNativeDexWeaver.verify(originalHostDex, reactNativeOnly)
                chatBubblesDexWeaver.verify(reactNativeOnly, outputHostDex)
            } else {
                reactNativeDexWeaver.verify(originalHostDex, outputHostDex)
                base.chatBubblesDexEntry?.let { entry ->
                    chatBubblesDexWeaver.verify(
                        readBoundedEntry(inputBase, entry, MAX_HOST_DEX_BYTES),
                        readBoundedEntry(outputBase, entry, MAX_HOST_DEX_BYTES),
                    )
                }
            }
        } else if (rewritesChatBubblesDex) {
            val entry = requireNotNull(base.chatBubblesDexEntry)
            chatBubblesDexWeaver.verify(
                readBoundedEntry(inputBase, entry, MAX_HOST_DEX_BYTES),
                readBoundedEntry(outputBase, entry, MAX_HOST_DEX_BYTES),
            )
        }
        val expectedRuntime = emittedRuntime(input, base.sourcePackageName, base.chatBubblesDexEntry != null)
        val expectedRuntimeSha256 = sha256(expectedRuntime.bytes)
        validateConfiguration(
            outputBase,
            base.originalFactory,
            expectedRuntimeSha256,
            expectedRuntime.version,
        )
        if (!readBoundedEntry(outputBase, RUNTIME_ENTRY, MAX_RUNTIME_BYTES).contentEquals(expectedRuntime.bytes)) {
            throw IOException("Embedded runtime verification failed")
        }
        val expectedDex = dexProvider.load().also(::validateDex)
        if (!readBoundedEntry(outputBase, base.dexEntry, MAX_DEX_BYTES).contentEquals(expectedDex)) {
            throw IOException("Bootstrap DEX verification failed")
        }
        val outputHostDexSha256 = sha256BoundedEntry(outputBase, base.hostDexEntry, MAX_HOST_DEX_BYTES)
        if (base.mode == InputMode.THUNDER_REFRESH &&
            !(rewritesChatBubblesDex && base.chatBubblesDexEntry == base.hostDexEntry) &&
            outputHostDexSha256 != base.hostDexSha256
        ) {
            throw IOException("Preserved host DEX verification failed")
        }
        val outputChatBubblesDexSha256 = base.chatBubblesDexEntry?.let { entry ->
            sha256BoundedEntry(outputBase, entry, MAX_HOST_DEX_BYTES)
        }
        if (base.mode == InputMode.THUNDER_REFRESH && !rewritesChatBubblesDex &&
            outputChatBubblesDexSha256 != base.chatBubblesDexSha256
        ) {
            throw IOException("Preserved ChatBubbles DEX verification failed")
        }
        validateMarker(
            file = outputBase,
            sourcePackageName = base.sourcePackageName,
            outputPackageName = output.packageName,
            sourceVersionCode = base.sourceVersionCode ?: input.versionCode,
            sourceSignerSha256 = base.sourceSignerSha256 ?: normalizedSignerDigests(input.sourceSignerSha256),
            sourceSetSha256 = base.sourceSetSha256
                ?: setDigest(input.artifacts.map { it.splitName to it.sha256 }),
            dexEntry = base.dexEntry,
            hostDexEntry = base.hostDexEntry,
            hostDexSha256 = outputHostDexSha256,
            chatBubblesDexEntry = base.chatBubblesDexEntry,
            chatBubblesDexSha256 = outputChatBubblesDexSha256,
            runtimeSha256 = expectedRuntimeSha256,
            runtimeVersion = expectedRuntime.version,
        )

        val removed = input.artifacts.flatMap { artifact ->
            archiveFacts(artifact.file).keys.filter(::isJarSignature).sorted().map { name ->
                ChangedArchiveEntry(artifact.splitName, name, ArchiveEntryChange.REMOVED)
            }
        }
        val changes = if (base.mode == InputMode.STOCK) {
            buildList {
                addAll(listOf(
                ChangedArchiveEntry(null, MANIFEST_ENTRY, ArchiveEntryChange.REPLACED),
                ChangedArchiveEntry(null, base.hostDexEntry, ArchiveEntryChange.REPLACED),
                ChangedArchiveEntry(null, base.dexEntry, ArchiveEntryChange.ADDED),
                ChangedArchiveEntry(null, CONFIG_ENTRY, ArchiveEntryChange.ADDED),
                ChangedArchiveEntry(null, MARKER_ENTRY, ArchiveEntryChange.ADDED),
                ChangedArchiveEntry(null, RUNTIME_ENTRY, ArchiveEntryChange.ADDED),
                ))
                if (base.chatBubblesDexEntry != null && base.chatBubblesDexEntry != base.hostDexEntry) {
                    add(ChangedArchiveEntry(null, base.chatBubblesDexEntry, ArchiveEntryChange.REPLACED))
                }
                input.artifacts.filterNot { it.isBase }.forEach { artifact ->
                    add(ChangedArchiveEntry(artifact.splitName, MANIFEST_ENTRY, ArchiveEntryChange.REPLACED))
                }
                addAll(resourceChanges)
                addAll(brandIconChanges)
            }
        } else {
            buildList {
                addAll(listOf(
                ChangedArchiveEntry(null, base.dexEntry, ArchiveEntryChange.REPLACED),
                ChangedArchiveEntry(null, CONFIG_ENTRY, ArchiveEntryChange.REPLACED),
                ChangedArchiveEntry(null, MARKER_ENTRY, ArchiveEntryChange.REPLACED),
                ChangedArchiveEntry(null, RUNTIME_ENTRY, ArchiveEntryChange.REPLACED),
                ))
                if (rewritesChatBubblesDex && base.chatBubblesDexEntry != base.dexEntry) {
                    add(ChangedArchiveEntry(null, requireNotNull(base.chatBubblesDexEntry), ArchiveEntryChange.REPLACED))
                }
                addAll(refreshManifestChanges)
                addAll(resourceChanges)
                addAll(brandIconChanges)
            }
        }
        MutationReport(
            backendId = id,
            backendVersion = version,
            changedEntries = changes + removed,
            changedManifestFields = if (base.mode == InputMode.STOCK) {
                buildList {
                    addAll(requireNotNull(cloneIdentity).changedManifestFields)
                    add("application.appComponentFactory")
                    add("application.activity[ThunderRecoveryActivity]")
                    input.artifacts.filterNot { it.isBase }.forEach { artifact ->
                        splitManifestChanges.getValue(requireNotNull(artifact.splitName)).forEach { field ->
                            add("split[${artifact.splitName}].$field")
                        }
                    }
                }
            } else buildList {
                addAll(schema2BaseIdentity?.changedManifestFields.orEmpty())
                input.artifacts.filterNot { it.isBase }.forEach { artifact ->
                    splitManifestChanges[requireNotNull(artifact.splitName)].orEmpty().forEach { field ->
                        add("split[${artifact.splitName}].$field")
                    }
                }
            },
            inputSetSha256 = setDigest(input.artifacts.map { it.splitName to it.sha256 }),
            outputSetSha256 = setDigest(output.artifacts.map { it.splitName to sha256(it.file) }),
        )
    }

    private enum class InputMode { STOCK, THUNDER_REFRESH }
    private data class BaseInspection(
        val dexEntry: String,
        val hostDexEntry: String,
        val originalFactory: String,
        val mode: InputMode,
        val sourcePackageName: String,
        val sourceVersionCode: Long? = null,
        val sourceSignerSha256: List<String>? = null,
        val sourceSetSha256: String? = null,
        val hostDexSha256: String? = null,
        val chatBubblesDexEntry: String? = null,
        val chatBubblesDexSha256: String? = null,
        val markerSchemaVersion: Int? = null,
    )
    private data class InspectionCacheKey(
        val canonicalPath: String,
        val packageName: String,
        val versionCode: Long,
        val apkSha256: String,
        val length: Long,
        val lastModified: Long,
    )
    private data class ArchiveFact(val crc: Long, val method: Int, val size: Long)
    private data class ResourceTablePlan(val bytes: ByteArray, val changed: Boolean)
    private data class ChatBubblesDexInspection(val entry: String, val sha256: String?)
    private data class VerificationInput(val facts: Map<String, ArchiveFact>, val names: List<String>, val originalFactory: String)
    private data class ParsedMarker(
        val backendId: String,
        val backendVersion: String,
        val bootstrapDexEntry: String,
        val bootstrapVersion: String,
        val schemaVersion: Int,
        val hostDexSha256: String?,
        val chatBubblesDexEntry: String?,
        val chatBubblesDexSha256: String?,
        val outputPackageName: String,
        val platform: String,
        val reactNativeDexEntry: String,
        val runtimeSha256: String,
        val runtimeVersion: String,
        val sourcePackageName: String,
        val sourceSetSha256: String,
        val sourceSignerSha256: List<String>,
        val sourceVersionCode: Long,
    )

    private fun inspectBase(
        file: File,
        packageName: String,
        versionCode: Long,
        apkSha256: String,
    ): BaseInspection {
        if (!file.isFile || !file.canRead() || !SHA_256.matches(apkSha256)) {
            throw IOException("Base APK snapshot identity is invalid")
        }
        val key = InspectionCacheKey(
            file.canonicalPath,
            packageName,
            versionCode,
            apkSha256,
            file.length(),
            file.lastModified(),
        )
        synchronized(inspectionCache) { inspectionCache[key] }?.let { return it }
        val inspection = inspectBaseUncached(file, packageName, versionCode)
        synchronized(inspectionCache) { inspectionCache[key] = inspection }
        return inspection
    }

    private fun inspectBaseUncached(file: File, packageName: String, versionCode: Long): BaseInspection {
        val manifest = readBoundedEntry(file, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
        if (BinaryAndroidManifest.readPackageName(manifest) != packageName) {
            throw IOException("Base APK package identity differs from the selected package")
        }
        val verification = inspectInputForVerification(file)
        if (verification.originalFactory != THUNDER_FACTORY) {
            if (verification.names.any { it.startsWith("assets/thunder/") || it.startsWith("dev/thunder/") }) {
                throw IOException("Stock input contains an unexpected Thunder surface")
            }
            val chatBubblesDex = findChatBubblesDex(file, verification.names)
            return BaseInspection(
                dexEntry = nextDexEntry(verification.names),
                hostDexEntry = findHostDex(file, verification.names),
                originalFactory = verification.originalFactory,
                mode = InputMode.STOCK,
                sourcePackageName = packageName,
                chatBubblesDexEntry = chatBubblesDex?.entry,
                chatBubblesDexSha256 = chatBubblesDex?.sha256,
            )
        }

        if (!BinaryAndroidManifest.hasExportedActivity(manifest, THUNDER_RECOVERY_ACTIVITY)) {
            throw IOException("Thunder refresh input is missing recovery")
        }
        val thunderEntries = verification.names.filter { it.startsWith("assets/thunder/") }.toSet()
        if (thunderEntries != setOf(CONFIG_ENTRY, MARKER_ENTRY, RUNTIME_ENTRY)) {
            throw IOException("Thunder refresh surface differs from the allowlist")
        }
        val properties = readConfiguration(file)
        val originalFactory = properties.getProperty("originalFactory") ?: throw IOException("Thunder refresh original factory is missing")
        val runtimeSha256 = properties.getProperty("runtimeSha256") ?: throw IOException("Thunder refresh runtime hash is missing")
        validateConfiguration(file, originalFactory, runtimeSha256, expectedRuntimeVersion = null)
        val parsed = parseMarker(file)
        if (parsed.backendId != id || !SAFE_VERSION.matches(parsed.backendVersion) ||
            parsed.outputPackageName != packageName || parsed.platform != "thunder" ||
            parsed.runtimeSha256 != runtimeSha256 || !SAFE_VERSION.matches(parsed.bootstrapVersion) ||
            !SAFE_VERSION.matches(parsed.runtimeVersion) || !SUPPORTED_PACKAGES.matches(parsed.sourcePackageName) ||
            parsed.sourceVersionCode <= 0L || parsed.sourceVersionCode != versionCode ||
            !SHA_256.matches(parsed.sourceSetSha256) ||
            parsed.sourceSignerSha256.isEmpty() || parsed.sourceSignerSha256.any { !SHA_256.matches(it) }
        ) throw IOException("Thunder refresh marker is incompatible")
        if (parsed.bootstrapDexEntry !in verification.names || !DEX_NAME.matches(parsed.bootstrapDexEntry)) {
            throw IOException("Thunder refresh bootstrap DEX is missing")
        }
        if (parsed.reactNativeDexEntry !in verification.names || !DEX_NAME.matches(parsed.reactNativeDexEntry)) {
            throw IOException("Thunder refresh host DEX is missing")
        }
        validateDex(readBoundedEntry(file, parsed.bootstrapDexEntry, MAX_DEX_BYTES))
        val hostDexSha256 = if (parsed.schemaVersion == 3) {
            val expected = parsed.hostDexSha256
            if (expected == null || !SHA_256.matches(expected)) throw IOException("Thunder refresh host DEX identity is missing")
            val actual = sha256BoundedEntry(file, parsed.reactNativeDexEntry, MAX_HOST_DEX_BYTES)
            if (actual != expected) throw IOException("Thunder refresh host DEX identity is invalid")
            actual
        } else {
            // Schema 2 predates the host-Dex digest. Parse it once, then emit schema 3 so every
            // later Refresh can authenticate this immutable entry with a streaming hash.
            val hostDex = readBoundedEntry(file, parsed.reactNativeDexEntry, MAX_HOST_DEX_BYTES)
            val calls = reactNativeDexWeaver.callCounts(hostDex)
            if (calls.original != 0 || calls.patched != 2) {
                throw IOException("Thunder refresh React Native seam is incompatible")
            }
            sha256(hostDex)
        }
        val runtime = readBoundedEntry(file, RUNTIME_ENTRY, MAX_RUNTIME_BYTES).also(::validateRuntime)
        if (sha256(runtime) != runtimeSha256) throw IOException("Thunder refresh runtime hash is invalid")
        val chatBubblesDex = if (parsed.chatBubblesDexEntry != null) {
            val entry = parsed.chatBubblesDexEntry
            val expected = parsed.chatBubblesDexSha256
            if (entry !in verification.names || !DEX_NAME.matches(entry) || entry == parsed.bootstrapDexEntry ||
                expected == null || !SHA_256.matches(expected)
            ) {
                throw IOException("Thunder refresh ChatBubbles DEX identity is invalid")
            }
            val actual = sha256BoundedEntry(file, entry, MAX_HOST_DEX_BYTES)
            if (actual != expected) throw IOException("Thunder refresh ChatBubbles DEX identity is invalid")
            ChatBubblesDexInspection(entry, actual)
        } else {
            findChatBubblesDex(file, verification.names, allowPatched = true)
        }
        return BaseInspection(
            dexEntry = parsed.bootstrapDexEntry,
            hostDexEntry = parsed.reactNativeDexEntry,
            originalFactory = originalFactory,
            mode = InputMode.THUNDER_REFRESH,
            sourcePackageName = parsed.sourcePackageName,
            sourceVersionCode = parsed.sourceVersionCode,
            sourceSignerSha256 = parsed.sourceSignerSha256,
            sourceSetSha256 = parsed.sourceSetSha256,
            hostDexSha256 = hostDexSha256,
            chatBubblesDexEntry = chatBubblesDex?.entry,
            chatBubblesDexSha256 = chatBubblesDex?.sha256,
            markerSchemaVersion = parsed.schemaVersion,
        )
    }

    private fun readConfiguration(file: File): Properties = Properties().apply {
        readBoundedEntry(file, CONFIG_ENTRY, 16 * 1024).inputStream().use { input -> load(input) }
    }

    private fun validateConfiguration(
        file: File,
        originalFactory: String,
        runtimeSha256: String,
        expectedRuntimeVersion: String?,
    ) {
        val properties = readConfiguration(file)
        if (properties.stringPropertyNames() != CONFIG_KEYS ||
            properties.getProperty("originalFactory") != originalFactory ||
            properties.getProperty("platform") != "thunder" ||
            properties.getProperty("runtimeAsset") != "assets://thunder/runtime.js" ||
            properties.getProperty("runtimeContractVersion")?.toIntOrNull()?.let { it >= 1 } != true ||
            properties.getProperty("runtimeEntrypoint") != THUNDER_RUNTIME_ENTRYPOINT ||
            properties.getProperty("runtimeSha256") != runtimeSha256 ||
            !SHA_256.matches(runtimeSha256) ||
            properties.getProperty("schemaVersion") != "1" ||
            (expectedRuntimeVersion != null && properties.getProperty("runtimeVersion") != expectedRuntimeVersion) ||
            !SAFE_VERSION.matches(properties.getProperty("runtimeVersion").orEmpty())
        ) throw IOException("Bootstrap configuration verification failed")
    }

    private fun parseMarker(file: File): ParsedMarker {
        val text = String(readBoundedEntry(file, MARKER_ENTRY, 16 * 1024), Charsets.UTF_8).trimEnd('\n')
        MARKER_V3_PATTERN.matchEntire(text)?.groupValues?.let { values ->
            return ParsedMarker(
                backendId = values[1],
                backendVersion = values[2],
                bootstrapDexEntry = values[3],
                bootstrapVersion = values[4],
                schemaVersion = 3,
                hostDexSha256 = values[7],
                chatBubblesDexEntry = values[5].ifEmpty { null },
                chatBubblesDexSha256 = values[6].ifEmpty { null },
                outputPackageName = values[8],
                platform = values[9],
                reactNativeDexEntry = values[10],
                runtimeSha256 = values[11],
                runtimeVersion = values[12],
                sourcePackageName = values[13],
                sourceSetSha256 = values[14],
                sourceSignerSha256 = markerSigners(values[15]),
                sourceVersionCode = values[16].toLongOrNull()
                    ?: throw IOException("Patch marker source version is invalid"),
            )
        }
        MARKER_V2_PATTERN.matchEntire(text)?.groupValues?.let { values ->
            return ParsedMarker(
                backendId = values[1],
                backendVersion = values[2],
                bootstrapDexEntry = values[3],
                bootstrapVersion = values[4],
                schemaVersion = 2,
                hostDexSha256 = null,
                chatBubblesDexEntry = null,
                chatBubblesDexSha256 = null,
                outputPackageName = values[5],
                platform = values[6],
                reactNativeDexEntry = values[7],
                runtimeSha256 = values[8],
                runtimeVersion = values[9],
                sourcePackageName = values[10],
                sourceSetSha256 = values[11],
                sourceSignerSha256 = markerSigners(values[12]),
                sourceVersionCode = values[13].toLongOrNull()
                    ?: throw IOException("Patch marker source version is invalid"),
            )
        }
        throw IOException("Patch marker is malformed")
    }

    private fun markerSigners(value: String): List<String> =
        MARKER_SIGNER_PATTERN.findAll(value).map { match -> match.groupValues[1] }.toList()

    private fun validateOutputIdentity(
        inputPackageName: String,
        outputPackageName: String,
        inspection: BaseInspection,
    ) {
        if (!PACKAGE_NAME.matches(outputPackageName) || !OUTPUT_PACKAGES.matches(outputPackageName)) {
            throw IOException("Output package name is not a Thunder derived identity")
        }
        when (inspection.mode) {
            InputMode.STOCK -> if (inputPackageName == outputPackageName) {
                throw IOException("Purpose-built rootless output must use a derived package identity")
            }
            InputMode.THUNDER_REFRESH -> if (inputPackageName != outputPackageName) {
                throw IOException("Thunder refresh cannot change its installed package identity")
            }
        }
    }

    private fun validateMarker(
        file: File,
        sourcePackageName: String,
        outputPackageName: String,
        sourceVersionCode: Long,
        sourceSignerSha256: List<String>,
        sourceSetSha256: String,
        dexEntry: String,
        hostDexEntry: String,
        hostDexSha256: String,
        chatBubblesDexEntry: String?,
        chatBubblesDexSha256: String?,
        runtimeSha256: String,
        runtimeVersion: String,
    ) {
        val marker = parseMarker(file)
        if (marker.schemaVersion != 3 || marker.hostDexSha256 != hostDexSha256 || !SHA_256.matches(hostDexSha256) ||
            marker.chatBubblesDexEntry != chatBubblesDexEntry ||
            marker.chatBubblesDexSha256 != chatBubblesDexSha256 ||
            (chatBubblesDexEntry == null) != (chatBubblesDexSha256 == null) ||
            chatBubblesDexEntry != null && (
                !DEX_NAME.matches(chatBubblesDexEntry) || chatBubblesDexEntry == dexEntry ||
                    !SHA_256.matches(requireNotNull(chatBubblesDexSha256))
                ) ||
            marker.backendId != id || marker.backendVersion != version || marker.bootstrapDexEntry != dexEntry ||
            marker.outputPackageName != outputPackageName || marker.platform != "thunder" ||
            marker.reactNativeDexEntry != hostDexEntry || marker.runtimeSha256 != runtimeSha256 ||
            marker.runtimeVersion != runtimeVersion || !SAFE_VERSION.matches(runtimeVersion) ||
            !SAFE_VERSION.matches(marker.bootstrapVersion) ||
            marker.sourcePackageName != sourcePackageName || marker.sourceVersionCode != sourceVersionCode ||
            marker.sourceSignerSha256 != normalizedSignerDigests(sourceSignerSha256) ||
            marker.sourceSetSha256 != sourceSetSha256 || !SHA_256.matches(sourceSetSha256)
        ) throw IOException("Patch marker verification failed")
    }

    private fun inspectInputForVerification(file: File): VerificationInput {
        val facts = archiveFacts(file)
        val manifest = readBoundedEntry(file, MANIFEST_ENTRY, MAX_MANIFEST_BYTES)
        return VerificationInput(facts, facts.keys.toList(), BinaryAndroidManifest.readFactory(manifest))
    }

    private fun archiveFacts(file: File): Map<String, ArchiveFact> = ZipFile(file).use { archive ->
        val facts = linkedMapOf<String, ArchiveFact>()
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (facts.put(entry.name, ArchiveFact(entry.crc, entry.method, entry.size)) != null) throw IOException("APK contains duplicate entries")
        }
        facts
    }

    private fun brandIconEntries(file: File): Set<String> = ZipFile(file).use { archive ->
        val matches = linkedSetOf<String>()
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && BRAND_ICON_ENTRY.matches(entry.name)) matches += entry.name
        }
        matches
    }

    private fun addBrandIconReplacements(
        replacedNames: MutableSet<String>,
        addedEntries: MutableList<StableApkArchive.AddedEntry>,
        iconEntries: Set<String>,
        brandIcon: ByteArray?,
    ) {
        if (iconEntries.isEmpty()) return
        val bytes = brandIcon ?: throw IOException("Thunder brand icon is unavailable")
        replacedNames += iconEntries
        iconEntries.forEach { name ->
            addedEntries += StableApkArchive.AddedEntry(name, bytes, alignment = 4)
        }
    }

    private fun validateBrandIcon(bytes: ByteArray) {
        if (bytes.size !in 64..MAX_BRAND_ICON_BYTES || bytes.size < PNG_SIGNATURE.size ||
            !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            throw IOException("Packaged Thunder brand icon is not a bounded PNG")
        }
    }

    private fun nextDexEntry(names: Collection<String>): String {
        val indices = names.mapNotNull { name -> DEX_NAME.matchEntire(name)?.groupValues?.get(1)?.let { if (it.isEmpty()) 1 else it.toIntOrNull() } }.sorted()
        if (indices.isEmpty() || indices != (1..indices.size).toList()) throw IOException("Host DEX entries are non-contiguous")
        return "classes${indices.size + 1}.dex"
    }

    private fun findHostDex(apk: File, names: Collection<String>): String {
        val matches = names.asSequence()
            .filter { DEX_NAME.matches(it) }
            .map { name -> name to reactNativeDexWeaver.originalCallCount(readBoundedEntry(apk, name, MAX_HOST_DEX_BYTES)) }
            .filter { it.second != 0 }
            .toList()
        if (matches.size != 1 || matches.single().second != 2) throw IOException("Host React Native bundle seam is incompatible")
        return matches.single().first
    }

    private fun findChatBubblesDex(
        apk: File,
        names: Collection<String>,
        allowPatched: Boolean = false,
    ): ChatBubblesDexInspection? {
        val matches = mutableListOf<Pair<String, Pair<ChatBubblesDexStatus, ByteArray>>>()
        for (name in names) {
            if (!DEX_NAME.matches(name)) continue
            val bytes = try {
                readBoundedEntry(apk, name, MAX_HOST_DEX_BYTES)
            } catch (_: IOException) {
                continue
            }
            val status = try {
                chatBubblesDexWeaver.inspect(bytes)
            } catch (_: RuntimeException) {
                ChatBubblesDexStatus.UNSUPPORTED
            }
            if (status != ChatBubblesDexStatus.UNSUPPORTED) matches += name to (status to bytes)
        }
        val (entry, match) = matches.singleOrNull() ?: return null
        val (status, bytes) = match
        return when (status) {
            ChatBubblesDexStatus.UNPATCHED -> ChatBubblesDexInspection(entry, null)
            ChatBubblesDexStatus.PATCHED -> if (allowPatched) ChatBubblesDexInspection(entry, sha256(bytes)) else null
            ChatBubblesDexStatus.UNSUPPORTED -> null
        }
    }

    private fun configuration(
        originalFactory: String,
        runtimeContractVersion: Int,
        runtimeSha256: String,
        runtimeVersion: String,
    ): ByteArray {
        if (!SAFE_VERSION.matches(runtimeVersion)) throw IOException("Runtime version is invalid")
        return (
        "originalFactory=$originalFactory\n" +
            "platform=thunder\n" +
            "runtimeAsset=assets://thunder/runtime.js\n" +
            "runtimeContractVersion=$runtimeContractVersion\n" +
            "runtimeEntrypoint=$THUNDER_RUNTIME_ENTRYPOINT\n" +
            "runtimeSha256=$runtimeSha256\n" +
            "runtimeVersion=$runtimeVersion\n" +
            "schemaVersion=1\n"
        ).toByteArray(Charsets.ISO_8859_1)
    }

    private fun marker(
        sourcePackageName: String,
        outputPackageName: String,
        sourceVersionCode: Long,
        sourceSignerSha256: List<String>,
        sourceSetSha256: String,
        bootstrapVersion: String,
        dexEntry: String,
        hostDexEntry: String,
        hostDexSha256: String,
        chatBubblesDexEntry: String?,
        chatBubblesDexSha256: String?,
        runtimeSha256: String,
        runtimeVersion: String,
    ): ByteArray {
        val signerDigests = normalizedSignerDigests(sourceSignerSha256)
        if (sourceVersionCode <= 0L || !SHA_256.matches(sourceSetSha256) ||
            !SHA_256.matches(hostDexSha256) || !SAFE_VERSION.matches(runtimeVersion)
        ) {
            throw IOException("Source provenance is invalid")
        }
        if ((chatBubblesDexEntry == null) != (chatBubblesDexSha256 == null) ||
            chatBubblesDexEntry != null && (
                !DEX_NAME.matches(chatBubblesDexEntry) || chatBubblesDexEntry == dexEntry ||
                    !SHA_256.matches(requireNotNull(chatBubblesDexSha256))
                )
        ) {
            throw IOException("ChatBubbles DEX provenance is invalid")
        }
        val signers = signerDigests.joinToString(",") { digest -> "\"$digest\"" }
        val chatBubblesIdentity = if (chatBubblesDexEntry == null) {
            ""
        } else {
            "\"chatBubblesDexEntry\":\"$chatBubblesDexEntry\",\"chatBubblesDexSha256\":\"$chatBubblesDexSha256\","
        }
        return (
        "{\"backendId\":\"$id\",\"backendVersion\":\"$version\",\"bootstrapDexEntry\":\"$dexEntry\"," +
            "\"bootstrapVersion\":\"$bootstrapVersion\",$chatBubblesIdentity\"hostDexSha256\":\"$hostDexSha256\"," +
            "\"outputPackageName\":\"$outputPackageName\",\"platform\":\"thunder\",\"reactNativeDexEntry\":\"$hostDexEntry\"," +
            "\"runtimeSha256\":\"$runtimeSha256\",\"runtimeVersion\":\"$runtimeVersion\",\"schemaVersion\":3," +
            "\"sourcePackageName\":\"$sourcePackageName\",\"sourceSetSha256\":\"$sourceSetSha256\"," +
            "\"sourceSignerSha256\":[$signers],\"sourceVersionCode\":$sourceVersionCode}\n"
        ).toByteArray(Charsets.UTF_8)
    }

    private fun validateDex(bytes: ByteArray) {
        if (bytes.size !in 112..MAX_DEX_BYTES || bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() || bytes[2] != 'x'.code.toByte() || bytes[3] != '\n'.code.toByte() || bytes[7] != 0.toByte()) {
            throw IOException("Bootstrap payload is not a bounded DEX file")
        }
    }

    /**
     * The embedded runtime carries the host's identity as a preamble, so the mod can report the
     * Discord it is patched into without having to recognise a host internal. The bytes hashed into
     * the configuration and marker are these emitted bytes, so verification stays exact.
     */
    private fun emittedRuntime(
        input: ApkSetInput,
        sourcePackageName: String,
        chatBubblesCapability: Boolean,
    ): EmittedRuntime {
        val bundle = runtimeProvider.load()
        if (!SAFE_VERSION.matches(bundle.version)) throw IOException("Runtime version is invalid")
        val bytes = hostPreamble(input, sourcePackageName, chatBubblesCapability) + bundle.bytes
        validateRuntime(bytes)
        return EmittedRuntime(bundle.version, bytes)
    }

    private data class EmittedRuntime(val version: String, val bytes: ByteArray)

    private fun hostPreamble(
        input: ApkSetInput,
        sourcePackageName: String,
        chatBubblesCapability: Boolean,
    ): ByteArray {
        if (input.versionName.length > MAX_VERSION_NAME_LENGTH) throw IOException("Host version name is unbounded")
        return (
            "globalThis.__THUNDER_HOST__={" +
                "\"packageName\":${jsonString(sourcePackageName)}," +
                "\"nativeCapabilities\":{\"chatBubbles\":$chatBubblesCapability}," +
                "\"versionCode\":${jsonString(input.versionCode.toString())}," +
                "\"versionName\":${jsonString(input.versionName)}};\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    /** Emits ASCII-only JSON so the preamble can never introduce a NUL or a non-Latin-1 byte. */
    private fun jsonString(value: String): String {
        val builder = StringBuilder(value.length + 2).append('"')
        for (character in value) {
            when {
                character == '"' -> builder.append("\\\"")
                character == '\\' -> builder.append("\\\\")
                character.code in 0x20..0x7e -> builder.append(character)
                else -> builder.append("\\u%04x".format(character.code))
            }
        }
        return builder.append('"').toString()
    }

    private fun validateRuntime(bytes: ByteArray) {
        if (bytes.size !in 128..MAX_RUNTIME_BYTES) throw IOException("Embedded runtime is outside its size bound")
        if (bytes.any { it == 0.toByte() }) throw IOException("Embedded runtime is not a JavaScript source bundle")
    }

    private fun expectedResourceTable(
        apk: File,
        mode: InputMode,
        inputPackageName: String,
        sourcePackageName: String,
        outputPackageName: String,
    ): ResourceTablePlan? {
        val bytes = readOptionalBoundedEntry(apk, RESOURCES_ENTRY, MAX_RESOURCES_BYTES) ?: return null
        val currentPackageName = BinaryResourceTable.readPackageName(bytes)
        return when (mode) {
            InputMode.STOCK -> {
                if (inputPackageName != sourcePackageName || currentPackageName != inputPackageName) {
                    throw IOException("Resource table package identity differs from the selected source")
                }
                ResourceTablePlan(
                    BinaryResourceTable.rewritePackageName(bytes, currentPackageName, outputPackageName),
                    changed = true,
                )
            }
            InputMode.THUNDER_REFRESH -> when (currentPackageName) {
                outputPackageName -> ResourceTablePlan(bytes, changed = false)
                sourcePackageName -> ResourceTablePlan(
                    BinaryResourceTable.rewritePackageName(bytes, currentPackageName, outputPackageName),
                    changed = true,
                )
                else -> throw IOException("Thunder refresh resource package identity is incompatible")
            }
        }
    }

    private fun readBoundedEntry(apk: File, name: String, maximum: Int): ByteArray =
        readOptionalBoundedEntry(apk, name, maximum) ?: throw IOException("APK entry is missing: $name")

    private fun sha256BoundedEntry(apk: File, name: String, maximum: Int): String = ZipFile(apk).use { archive ->
        val entry = archive.getEntry(name) ?: throw IOException("APK entry is missing: $name")
        if (entry.isDirectory || entry.size < 0 || entry.size > maximum) throw IOException("APK entry size is invalid: $name")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        archive.getInputStream(entry).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > entry.size || total > maximum) throw IOException("APK entry exceeds its declared size: $name")
                digest.update(buffer, 0, count)
            }
        }
        if (total != entry.size) throw IOException("APK entry ended before its declared size: $name")
        digest.digest().toHex()
    }

    private fun readOptionalBoundedEntry(apk: File, name: String, maximum: Int): ByteArray? = ZipFile(apk).use { archive ->
        val entry = archive.getEntry(name) ?: return@use null
        if (entry.isDirectory || entry.size < 0 || entry.size > maximum) throw IOException("APK entry size is invalid: $name")
        val bytes = ByteArray(entry.size.toInt())
        archive.getInputStream(entry).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw IOException("APK entry ended before its declared size: $name")
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) throw IOException("APK entry ended before its declared size: $name")
                    bytes[offset++] = value.toByte()
                } else {
                    offset += count
                }
            }
            if (input.read() >= 0) throw IOException("APK entry exceeds its declared size: $name")
        }
        bytes
    }

    private fun setDigest(entries: List<Pair<String?, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((split, hash) in entries.sortedBy { it.first ?: "" }) {
            digest.update((split ?: "base").toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(hash.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().toHex()
    }

    private fun normalizedSignerDigests(values: List<String>): List<String> {
        val normalized = values.map { it.lowercase(Locale.ROOT) }.distinct().sorted()
        if (normalized.isEmpty() || normalized.any { !SHA_256.matches(it) }) {
            throw IOException("Source signing identity is invalid")
        }
        return normalized
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun requireSafeVersion(value: String) {
        if (!Regex("^[0-9A-Za-z][0-9A-Za-z._+-]{0,99}$").matches(value)) throw IOException("Bootstrap version is invalid")
    }

    private fun isJarSignature(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        return upper == "META-INF/MANIFEST.MF" || (upper.startsWith("META-INF/") && (
            upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")
        ))
    }

    companion object {
        private const val THUNDER_FACTORY = "dev.thunder.bootstrap.ThunderAppComponentFactory"
        private const val THUNDER_RECOVERY_ACTIVITY = "dev.thunder.bootstrap.ThunderRecoveryActivity"
        private const val THUNDER_RUNTIME_ENTRYPOINT = "dev.thunder.bootstrap.ThunderRuntimeEntrypoint"
        private const val THUNDER_APPLICATION_LABEL = "Thunder"
        private const val MANIFEST_ENTRY = "AndroidManifest.xml"
        private const val RESOURCES_ENTRY = "resources.arsc"
        private const val CONFIG_ENTRY = "assets/thunder/bootstrap.properties"
        private const val MARKER_ENTRY = "assets/thunder/patch-manifest.json"
        private const val RUNTIME_ENTRY = "assets/thunder/runtime.js"
        private const val MAX_VERSION_NAME_LENGTH = 128
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private const val MAX_DEX_BYTES = 12 * 1024 * 1024
        private const val MAX_HOST_DEX_BYTES = 64 * 1024 * 1024
        private const val MAX_RESOURCES_BYTES = 64 * 1024 * 1024
        private const val MAX_BRAND_ICON_BYTES = 4 * 1024 * 1024
        private const val MAX_RUNTIME_BYTES = 576 * 1024
        private const val HASH_BUFFER_BYTES = 256 * 1024
        private const val MAX_INSPECTION_CACHE_ENTRIES = 4
        private val SUPPORTED_PACKAGES = Regex("^com\\.discord(?:\\.(?:beta|canary))?$")
        private val DEX_NAME = Regex("^classes([2-9][0-9]*)?\\.dex$")
        private val SAFE_VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z._+-]{0,99}$")
        private val SHA_256 = Regex("^[0-9a-f]{64}$")
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val OUTPUT_PACKAGES = Regex("^dev\\.thunder(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val BRAND_ICON_ENTRY = Regex(
            "^res/mipmap-(?:mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)-v4/ic_logo_(?:foreground|round(?:_[a-z0-9]+)?|square(?:_[a-z0-9]+)?)\\.png$",
        )
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        private val CONFIG_KEYS = setOf(
            "originalFactory",
            "platform",
            "runtimeAsset",
            "runtimeContractVersion",
            "runtimeEntrypoint",
            "runtimeSha256",
            "runtimeVersion",
            "schemaVersion",
        )
        private val MARKER_V2_PATTERN = Regex(
            """\{"backendId":"([^"]+)","backendVersion":"([^"]+)","bootstrapDexEntry":"([^"]+)","bootstrapVersion":"([^"]+)","outputPackageName":"([^"]+)","platform":"([^"]+)","reactNativeDexEntry":"([^"]+)","runtimeSha256":"([^"]+)","runtimeVersion":"([^"]+)","schemaVersion":2,"sourcePackageName":"([^"]+)","sourceSetSha256":"([0-9a-f]{64})","sourceSignerSha256":\[((?:"[0-9a-f]{64}"(?:,"[0-9a-f]{64}")*)?)\],"sourceVersionCode":([0-9]+)\}""",
        )
        private val MARKER_V3_PATTERN = Regex(
            """\{"backendId":"([^"]+)","backendVersion":"([^"]+)","bootstrapDexEntry":"([^"]+)","bootstrapVersion":"([^"]+)",(?:"chatBubblesDexEntry":"([^"]+)","chatBubblesDexSha256":"([0-9a-f]{64})",)?"hostDexSha256":"([0-9a-f]{64})","outputPackageName":"([^"]+)","platform":"([^"]+)","reactNativeDexEntry":"([^"]+)","runtimeSha256":"([^"]+)","runtimeVersion":"([^"]+)","schemaVersion":3,"sourcePackageName":"([^"]+)","sourceSetSha256":"([0-9a-f]{64})","sourceSignerSha256":\[((?:"[0-9a-f]{64}"(?:,"[0-9a-f]{64}")*)?)\],"sourceVersionCode":([0-9]+)\}""",
        )
        private val MARKER_SIGNER_PATTERN = Regex("\"([0-9a-f]{64})\"")
    }
}
