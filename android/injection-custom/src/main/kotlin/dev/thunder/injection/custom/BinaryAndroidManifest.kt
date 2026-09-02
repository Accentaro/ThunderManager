package dev.thunder.injection.custom

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal object BinaryAndroidManifest {
    private const val XML_TYPE = 0x0003
    private const val STRING_POOL_TYPE = 0x0001
    private const val RESOURCE_MAP_TYPE = 0x0180
    private const val START_ELEMENT_TYPE = 0x0102
    private const val END_ELEMENT_TYPE = 0x0103
    private const val UTF8_FLAG = 0x00000100
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val NO_INDEX = -1
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val MANIFEST = "manifest"
    private const val APPLICATION = "application"
    private const val ACTIVITY = "activity"
    private const val ACTIVITY_ALIAS = "activity-alias"
    private const val PROVIDER = "provider"
    private const val ACTION = "action"
    private const val CATEGORY = "category"
    private const val PERMISSION = "permission"
    private const val USES_PERMISSION = "uses-permission"
    private const val USES_PERMISSION_SDK_23 = "uses-permission-sdk-23"
    private const val PACKAGE_ATTRIBUTE = "package"
    private const val NAME_ATTRIBUTE = "name"
    private const val EXPORTED_ATTRIBUTE = "exported"
    private const val FACTORY_ATTRIBUTE = "appComponentFactory"
    private const val LABEL_ATTRIBUTE = "label"
    private const val AUTHORITIES_ATTRIBUTE = "authorities"
    private const val TASK_AFFINITY_ATTRIBUTE = "taskAffinity"
    private const val ANDROID_NAME_RESOURCE = 0x01010003
    private const val ANDROID_EXPORTED_RESOURCE = 0x01010010

    data class RewriteResult(val bytes: ByteArray, val originalFactory: String)

    data class CloneIdentityRewriteResult(
        val bytes: ByteArray,
        val changedManifestFields: List<String>,
    )

    private data class CloneScopedRewrites(
        val attributes: List<StringAttribute>,
        val replacements: LinkedHashMap<Int, String>,
        val changedManifestFields: List<String>,
    )

    private data class StringAttribute(
        val elementName: String,
        val namespace: String?,
        val name: String,
        val value: String?,
        val offset: Int,
    )

    fun readPackageName(bytes: ByteArray): String {
        val attributes = stringAttributes(bytes).filter {
            it.elementName == MANIFEST && it.namespace == null && it.name == PACKAGE_ATTRIBUTE
        }
        if (attributes.size != 1) throw IOException("Android manifest package attribute is missing or ambiguous")
        return attributes.single().value ?: throw IOException("Android manifest package attribute is not a string")
    }

    fun rewritePackageName(bytes: ByteArray, sourcePackageName: String, outputPackageName: String): ByteArray {
        requirePackageName(sourcePackageName)
        requirePackageName(outputPackageName)
        val attributes = stringAttributes(bytes)
        val packages = attributes.filter {
            it.elementName == MANIFEST && it.namespace == null && it.name == PACKAGE_ATTRIBUTE
        }
        if (packages.size != 1 || packages.single().value != sourcePackageName) {
            throw IOException("Android manifest package identity does not match the selected source")
        }
        val rewritten = rewriteStringAttributes(bytes, mapOf(packages.single().offset to outputPackageName))
        if (readPackageName(rewritten) != outputPackageName) throw IOException("Android manifest package rewrite failed")
        return rewritten
    }

    fun rewritePackageAndCoexistenceNames(
        bytes: ByteArray,
        sourcePackageName: String,
        outputPackageName: String,
    ): CloneIdentityRewriteResult {
        requirePackageName(sourcePackageName)
        requirePackageName(outputPackageName)
        if (sourcePackageName == outputPackageName) throw IOException("Clone package must differ from its source")
        val scoped = cloneScopedRewrites(stringAttributes(bytes), sourcePackageName, outputPackageName)
        val rewritten = rewriteStringAttributes(bytes, scoped.replacements)
        verifyCloneScopedNames(rewritten, sourcePackageName, outputPackageName)
        return CloneIdentityRewriteResult(rewritten, scoped.changedManifestFields)
    }

    /**
     * Schema 2 briefly derived manifest routing strings without rewriting their DEX callsites.
     * While upgrading that authenticated format, restore those host protocol names and normalize
     * every install-scoped identity that older split handling may have left source-owned.
     */
    fun migrateSchema2CloneIdentities(
        bytes: ByteArray,
        sourcePackageName: String,
        outputPackageName: String,
    ): CloneIdentityRewriteResult {
        requirePackageName(sourcePackageName)
        requirePackageName(outputPackageName)
        if (sourcePackageName == outputPackageName) throw IOException("Clone package must differ from its source")
        val attributes = stringAttributes(bytes)
        val packages = attributes.filter {
            it.elementName == MANIFEST && it.namespace == null && it.name == PACKAGE_ATTRIBUTE
        }
        if (packages.size != 1 || packages.single().value != outputPackageName) {
            throw IOException("Legacy clone manifest identity does not match its installed package")
        }

        val replacements = linkedMapOf<Int, String>()
        var affinityChanged = false
        var authorityChanged = false
        var permissionChanged = false
        var actionChanged = false
        var categoryChanged = false
        for (attribute in attributes) {
            val value = attribute.value ?: continue
            val replacement = when {
                attribute.namespace == ANDROID_NAMESPACE && attribute.name == TASK_AFFINITY_ATTRIBUTE &&
                    isPackageOwned(value, sourcePackageName) -> {
                    affinityChanged = true
                    outputPackageName + value.removePrefix(sourcePackageName)
                }
                attribute.elementName == PROVIDER && attribute.namespace == ANDROID_NAMESPACE &&
                    attribute.name == AUTHORITIES_ATTRIBUTE -> {
                    rewriteAuthorities(value, sourcePackageName, outputPackageName).takeIf { rewritten ->
                        rewritten != value
                    }?.also {
                        authorityChanged = true
                    }
                }
                isPermissionIdentityAttribute(attribute) && isPackageOwned(value, sourcePackageName) -> {
                    permissionChanged = true
                    outputPackageName + value.removePrefix(sourcePackageName)
                }
                attribute.namespace == ANDROID_NAMESPACE && attribute.name == NAME_ATTRIBUTE &&
                    attribute.elementName in setOf(ACTION, CATEGORY) &&
                    isPackageOwned(value, outputPackageName) -> {
                    if (attribute.elementName == ACTION) actionChanged = true else categoryChanged = true
                    sourcePackageName + value.removePrefix(outputPackageName)
                }
                else -> null
            }
            if (replacement != null) replacements[attribute.offset] = replacement
        }

        val rewritten = rewriteStringAttributes(bytes, replacements)
        verifyCloneScopedNames(rewritten, sourcePackageName, outputPackageName)
        if (stringAttributes(rewritten).any { attribute ->
                attribute.namespace == ANDROID_NAMESPACE && attribute.name == NAME_ATTRIBUTE &&
                    attribute.elementName in setOf(ACTION, CATEGORY) &&
                    attribute.value?.let { value -> isPackageOwned(value, outputPackageName) } == true
            }
        ) throw IOException("Schema 2 clone retains a derived host routing name")
        return CloneIdentityRewriteResult(
            bytes = rewritten,
            changedManifestFields = buildList {
                if (affinityChanged) add("manifest.package-owned-task-affinities")
                if (authorityChanged) add("application.provider.authorities")
                if (permissionChanged) add("manifest.package-owned-permissions")
                if (actionChanged) add("manifest.restored-host-intent-actions")
                if (categoryChanged) add("manifest.restored-host-intent-categories")
            },
        )
    }

    fun rewriteBaseForClone(
        bytes: ByteArray,
        sourcePackageName: String,
        outputPackageName: String,
        applicationLabel: String,
    ): CloneIdentityRewriteResult {
        requirePackageName(sourcePackageName)
        requirePackageName(outputPackageName)
        if (sourcePackageName == outputPackageName) throw IOException("Clone package must differ from its source")
        if (applicationLabel.isBlank() || applicationLabel.length > 80) throw IOException("Clone application label is invalid")

        val scoped = cloneScopedRewrites(stringAttributes(bytes), sourcePackageName, outputPackageName)
        val attributes = scoped.attributes
        val applicationLabels = attributes.filter {
            it.elementName == APPLICATION && it.namespace == ANDROID_NAMESPACE && it.name == LABEL_ATTRIBUTE
        }
        if (applicationLabels.size != 1) throw IOException("Host application label is missing or ambiguous")
        val activityLabels = attributes.filter {
            it.elementName in setOf(ACTIVITY, ACTIVITY_ALIAS) &&
                it.namespace == ANDROID_NAMESPACE && it.name == LABEL_ATTRIBUTE
        }

        val rewrites = LinkedHashMap(scoped.replacements)
        rewrites[applicationLabels.single().offset] = applicationLabel
        activityLabels.forEach { attribute -> rewrites[attribute.offset] = applicationLabel }
        val rewritten = rewriteStringAttributes(bytes, rewrites)
        val outputAttributes = stringAttributes(rewritten)
        verifyCloneScopedNames(rewritten, sourcePackageName, outputPackageName)
        val outputLabels = outputAttributes.filter {
            it.elementName in setOf(APPLICATION, ACTIVITY, ACTIVITY_ALIAS) &&
                it.namespace == ANDROID_NAMESPACE && it.name == LABEL_ATTRIBUTE
        }
        if (outputLabels.size != 1 + activityLabels.size || outputLabels.any { it.value != applicationLabel }) {
            throw IOException("Clone label verification failed")
        }
        return CloneIdentityRewriteResult(
            bytes = rewritten,
            changedManifestFields = buildList {
                addAll(scoped.changedManifestFields)
                add("application.label")
                if (activityLabels.any { it.elementName == ACTIVITY }) add("application.activity.label")
                if (activityLabels.any { it.elementName == ACTIVITY_ALIAS }) add("application.activity-alias.label")
            },
        )
    }

    private fun cloneScopedRewrites(
        attributes: List<StringAttribute>,
        sourcePackageName: String,
        outputPackageName: String,
    ): CloneScopedRewrites {
        val packageAttributes = attributes.filter {
            it.elementName == MANIFEST && it.namespace == null && it.name == PACKAGE_ATTRIBUTE
        }
        if (packageAttributes.size != 1 || packageAttributes.single().value != sourcePackageName) {
            throw IOException("Android manifest package identity does not match the selected source")
        }
        val replacements = linkedMapOf(packageAttributes.single().offset to outputPackageName)
        var affinityChanged = false
        var authorityChanged = false
        var permissionChanged = false
        // Intent action/category values are bytecode-facing protocol strings, not install identities.
        // Keep them unchanged unless the corresponding host callsites are rewritten too.
        for (attribute in attributes) {
            val value = attribute.value ?: continue
            val replacement = when {
                attribute.namespace == ANDROID_NAMESPACE && attribute.name == TASK_AFFINITY_ATTRIBUTE -> {
                    value.takeIf { isPackageOwned(it, sourcePackageName) }?.let {
                        affinityChanged = true
                        outputPackageName + it.removePrefix(sourcePackageName)
                    }
                }
                attribute.elementName == PROVIDER && attribute.namespace == ANDROID_NAMESPACE &&
                    attribute.name == AUTHORITIES_ATTRIBUTE -> {
                    rewriteAuthorities(value, sourcePackageName, outputPackageName).takeIf { rewritten ->
                        rewritten != value
                    }?.also {
                        authorityChanged = true
                    }
                }
                isPermissionIdentityAttribute(attribute) && isPackageOwned(value, sourcePackageName) -> {
                    permissionChanged = true
                    outputPackageName + value.removePrefix(sourcePackageName)
                }
                else -> null
            }
            if (replacement != null) replacements[attribute.offset] = replacement
        }
        return CloneScopedRewrites(
            attributes = attributes,
            replacements = replacements,
            changedManifestFields = buildList {
                add("manifest.package")
                if (affinityChanged) add("manifest.package-owned-task-affinities")
                if (authorityChanged) add("application.provider.authorities")
                if (permissionChanged) add("manifest.package-owned-permissions")
            },
        )
    }

    private fun verifyCloneScopedNames(
        bytes: ByteArray,
        sourcePackageName: String,
        outputPackageName: String,
    ) {
        if (readPackageName(bytes) != outputPackageName) throw IOException("Clone package verification failed")
        if (stringAttributes(bytes).any { attribute ->
                val retainsSourceIdentity = when {
                    attribute.namespace == ANDROID_NAMESPACE && attribute.name == TASK_AFFINITY_ATTRIBUTE ->
                        attribute.value?.let { value -> isPackageOwned(value, sourcePackageName) } == true
                    attribute.elementName == PROVIDER && attribute.namespace == ANDROID_NAMESPACE &&
                        attribute.name == AUTHORITIES_ATTRIBUTE ->
                        attribute.value?.split(';')?.any { authority -> isPackageOwned(authority, sourcePackageName) } == true
                    isPermissionIdentityAttribute(attribute) ->
                        attribute.value?.let { value -> isPackageOwned(value, sourcePackageName) } == true
                    else -> false
                }
                retainsSourceIdentity
            }
        ) throw IOException("Clone retains a source-owned coexistence identity")
    }

    internal fun readStringAttributeValues(
        bytes: ByteArray,
        elementName: String,
        attributeName: String,
    ): List<String> = stringAttributes(bytes)
        .filter { it.elementName == elementName && it.name == attributeName }
        .mapNotNull(StringAttribute::value)

    fun readFactory(bytes: ByteArray): String = locateFactory(bytes).factory

    fun hasExportedActivity(bytes: ByteArray, className: String): Boolean {
        requireClassName(className)
        val document = littleEndian(bytes)
        val stringPoolOffset = firstStringPool(document)
        val pool = StringPool.parse(document, stringPoolOffset)
        return findActivity(document, pool, stringPoolOffset + pool.chunkSize, className) == true
    }

    fun replaceFactoryAndDeclareRecovery(bytes: ByteArray, replacement: String, recoveryActivity: String): RewriteResult {
        val replaced = replaceFactory(bytes, replacement)
        return RewriteResult(declareExportedActivity(replaced.bytes, recoveryActivity), replaced.originalFactory)
    }

    fun replaceFactory(bytes: ByteArray, replacement: String): RewriteResult {
        requireClassName(replacement)
        val original = locateFactory(bytes).factory
        if (original == replacement) return RewriteResult(bytes.copyOf(), original)

        val document = littleEndian(bytes)
        val stringPoolOffset = firstStringPool(document)
        val pool = StringPool.parse(document, stringPoolOffset)
        val rebuiltPool = pool.append(replacement)
        val delta = rebuiltPool.size - pool.chunkSize
        val output = ByteArray(bytes.size + delta)
        bytes.copyInto(output, 0, 0, stringPoolOffset)
        rebuiltPool.copyInto(output, stringPoolOffset)
        bytes.copyInto(output, stringPoolOffset + rebuiltPool.size, stringPoolOffset + pool.chunkSize)
        putU32(output, 4, output.size)

        val updated = littleEndian(output)
        val updatedPool = StringPool.parse(updated, stringPoolOffset)
        val replacementIndex = updatedPool.stringCount - 1
        val location = findFactoryAttribute(updated, updatedPool, stringPoolOffset + updatedPool.chunkSize)
        putU32(output, location.attributeOffset + 8, replacementIndex)
        putU8(output, location.attributeOffset + 15, TYPE_STRING)
        putU32(output, location.attributeOffset + 16, replacementIndex)
        return RewriteResult(output, original)
    }

    private fun declareExportedActivity(bytes: ByteArray, className: String): ByteArray {
        requireClassName(className)
        val source = littleEndian(bytes)
        val stringPoolOffset = firstStringPool(source)
        val pool = StringPool.parse(source, stringPoolOffset)
        when (findActivity(source, pool, stringPoolOffset + pool.chunkSize, className)) {
            true -> return bytes.copyOf()
            false -> throw IOException("Recovery activity exists but is not exported")
            null -> Unit
        }

        val androidNamespace = pool.indexOf(ANDROID_NAMESPACE)
        val activityName = pool.indexOf(ACTIVITY)
        val nameAttribute = pool.indexOf(NAME_ATTRIBUTE)
        val exportedAttribute = pool.indexOf(EXPORTED_ATTRIBUTE)
        if (androidNamespace == NO_INDEX || activityName == NO_INDEX || nameAttribute == NO_INDEX || exportedAttribute == NO_INDEX) {
            throw IOException("Host manifest lacks required activity declaration symbols")
        }
        requireAttributeResource(source, stringPoolOffset + pool.chunkSize, nameAttribute, ANDROID_NAME_RESOURCE)
        requireAttributeResource(source, stringPoolOffset + pool.chunkSize, exportedAttribute, ANDROID_EXPORTED_RESOURCE)

        val rebuiltPool = pool.append(className)
        val withString = replaceChunk(bytes, stringPoolOffset, pool.chunkSize, rebuiltPool)
        val updated = littleEndian(withString)
        val updatedPool = StringPool.parse(updated, stringPoolOffset)
        val classNameIndex = updatedPool.stringCount - 1
        val applicationEnd = findApplicationEnd(updated, updatedPool, stringPoolOffset + updatedPool.chunkSize)
        val declaration = activityDeclaration(
            lineNumber = u32(updated, applicationEnd + 8),
            androidNamespace = androidNamespace,
            activityName = activityName,
            nameAttribute = nameAttribute,
            exportedAttribute = exportedAttribute,
            className = classNameIndex,
        )
        val output = ByteArray(withString.size + declaration.size)
        withString.copyInto(output, 0, 0, applicationEnd)
        declaration.copyInto(output, applicationEnd)
        withString.copyInto(output, applicationEnd + declaration.size, applicationEnd)
        putU32(output, 4, output.size)
        if (!hasExportedActivity(output, className)) throw IOException("Recovery activity verification failed")
        return output
    }

    private fun replaceChunk(source: ByteArray, offset: Int, size: Int, replacement: ByteArray): ByteArray {
        val output = ByteArray(source.size - size + replacement.size)
        source.copyInto(output, 0, 0, offset)
        replacement.copyInto(output, offset)
        source.copyInto(output, offset + replacement.size, offset + size)
        putU32(output, 4, output.size)
        return output
    }

    private fun activityDeclaration(
        lineNumber: Int,
        androidNamespace: Int,
        activityName: Int,
        nameAttribute: Int,
        exportedAttribute: Int,
        className: Int,
    ): ByteArray {
        val startSize = 36 + 2 * 20
        val output = ByteArray(startSize + 24)
        putU16(output, 0, START_ELEMENT_TYPE)
        putU16(output, 2, 16)
        putU32(output, 4, startSize)
        putU32(output, 8, lineNumber)
        putU32(output, 12, NO_INDEX)
        putU32(output, 16, NO_INDEX)
        putU32(output, 20, activityName)
        putU16(output, 24, 20)
        putU16(output, 26, 20)
        putU16(output, 28, 2)

        val name = 36
        putU32(output, name, androidNamespace)
        putU32(output, name + 4, nameAttribute)
        putU32(output, name + 8, className)
        putU16(output, name + 12, 8)
        putU8(output, name + 15, TYPE_STRING)
        putU32(output, name + 16, className)

        val exported = name + 20
        putU32(output, exported, androidNamespace)
        putU32(output, exported + 4, exportedAttribute)
        putU32(output, exported + 8, NO_INDEX)
        putU16(output, exported + 12, 8)
        putU8(output, exported + 15, TYPE_INT_BOOLEAN)
        putU32(output, exported + 16, 1)

        val end = startSize
        putU16(output, end, END_ELEMENT_TYPE)
        putU16(output, end + 2, 16)
        putU32(output, end + 4, 24)
        putU32(output, end + 8, lineNumber)
        putU32(output, end + 12, NO_INDEX)
        putU32(output, end + 16, NO_INDEX)
        putU32(output, end + 20, activityName)
        return output
    }

    private fun findApplicationEnd(document: ByteBuffer, pool: StringPool, initialOffset: Int): Int {
        var offset = initialOffset
        var applicationDepth = 0
        while (offset + 8 <= document.limit()) {
            val type = u16(document, offset)
            val size = chunkSize(document, offset)
            if (type == START_ELEMENT_TYPE) {
                val name = pool.string(u32(document, offset + 20))
                if (applicationDepth > 0) applicationDepth++ else if (name == APPLICATION) applicationDepth = 1
            } else if (type == END_ELEMENT_TYPE && applicationDepth > 0) {
                if (applicationDepth == 1 && pool.string(u32(document, offset + 20)) == APPLICATION) return offset
                applicationDepth--
            }
            offset += size
        }
        throw IOException("Android manifest application end element is missing")
    }

    private fun findActivity(document: ByteBuffer, pool: StringPool, initialOffset: Int, className: String): Boolean? {
        var offset = initialOffset
        while (offset + 8 <= document.limit()) {
            val type = u16(document, offset)
            val size = chunkSize(document, offset)
            if (type == START_ELEMENT_TYPE && size >= 36 && pool.string(u32(document, offset + 20)) == ACTIVITY) {
                val attributeStart = u16(document, offset + 24)
                val attributeSize = u16(document, offset + 26)
                val attributeCount = u16(document, offset + 28)
                if (attributeSize < 20 || attributeCount > 1024) throw IOException("Activity attributes are invalid")
                var matches = false
                var exported = false
                val attributesOffset = offset + 16 + attributeStart
                for (index in 0 until attributeCount) {
                    val attributeOffset = attributesOffset + index * attributeSize
                    if (attributeOffset < offset || attributeOffset + 20 > offset + size) throw IOException("Activity attribute exceeds its chunk")
                    if (pool.optionalString(u32(document, attributeOffset)) != ANDROID_NAMESPACE) continue
                    when (pool.string(u32(document, attributeOffset + 4))) {
                        NAME_ATTRIBUTE -> {
                            val raw = u32(document, attributeOffset + 8)
                            val typed = u32(document, attributeOffset + 16)
                            matches = pool.string(if (raw == NO_INDEX) typed else raw) == className
                        }
                        EXPORTED_ATTRIBUTE -> exported = u8(document, attributeOffset + 15) == TYPE_INT_BOOLEAN && u32(document, attributeOffset + 16) != 0
                    }
                }
                if (matches) return exported
            }
            offset += size
        }
        return null
    }

    private fun stringAttributes(bytes: ByteArray): List<StringAttribute> {
        val document = littleEndian(bytes)
        val stringPoolOffset = firstStringPool(document)
        val pool = StringPool.parse(document, stringPoolOffset)
        val attributes = mutableListOf<StringAttribute>()
        var offset = stringPoolOffset + pool.chunkSize
        while (offset + 8 <= document.limit()) {
            val type = u16(document, offset)
            val size = chunkSize(document, offset)
            if (type == START_ELEMENT_TYPE) {
                if (size < 36) throw IOException("Android manifest start element is truncated")
                val elementName = pool.string(u32(document, offset + 20))
                val attributeStart = u16(document, offset + 24)
                val attributeSize = u16(document, offset + 26)
                val attributeCount = u16(document, offset + 28)
                if (attributeSize < 20 || attributeCount > 4096) throw IOException("Android manifest attributes are invalid")
                val attributesOffset = offset + 16 + attributeStart
                for (index in 0 until attributeCount) {
                    val attributeOffset = attributesOffset + index * attributeSize
                    if (attributeOffset < offset || attributeOffset + 20 > offset + size) {
                        throw IOException("Android manifest attribute exceeds its chunk")
                    }
                    val rawIndex = u32(document, attributeOffset + 8)
                    val valueType = u8(document, attributeOffset + 15)
                    val typedIndex = u32(document, attributeOffset + 16)
                    val value = when {
                        rawIndex != NO_INDEX -> pool.string(rawIndex)
                        valueType == TYPE_STRING -> pool.string(typedIndex)
                        else -> null
                    }
                    attributes += StringAttribute(
                        elementName = elementName,
                        namespace = pool.optionalString(u32(document, attributeOffset)),
                        name = pool.string(u32(document, attributeOffset + 4)),
                        value = value,
                        offset = attributeOffset,
                    )
                }
            }
            offset += size
        }
        if (offset != document.limit()) throw IOException("Android manifest contains trailing data")
        return attributes
    }

    private fun rewriteStringAttributes(bytes: ByteArray, rewrites: Map<Int, String>): ByteArray {
        if (rewrites.isEmpty()) return bytes.copyOf()
        var output = bytes.copyOf()
        var offsetDelta = 0
        for (replacement in rewrites.values.distinct()) {
            val document = littleEndian(output)
            val stringPoolOffset = firstStringPool(document)
            val pool = StringPool.parse(document, stringPoolOffset)
            if (pool.indexOf(replacement) != NO_INDEX) continue
            val rebuilt = pool.append(replacement)
            offsetDelta += rebuilt.size - pool.chunkSize
            output = replaceChunk(output, stringPoolOffset, pool.chunkSize, rebuilt)
        }

        val document = littleEndian(output)
        val stringPoolOffset = firstStringPool(document)
        val pool = StringPool.parse(document, stringPoolOffset)
        for ((originalOffset, replacement) in rewrites) {
            val replacementIndex = pool.indexOf(replacement)
            if (replacementIndex == NO_INDEX) throw IOException("Android manifest replacement string is missing")
            val attributeOffset = originalOffset + offsetDelta
            if (attributeOffset < 0 || attributeOffset + 20 > output.size) {
                throw IOException("Android manifest replacement attribute moved outside the document")
            }
            putU32(output, attributeOffset + 8, replacementIndex)
            putU8(output, attributeOffset + 15, TYPE_STRING)
            putU32(output, attributeOffset + 16, replacementIndex)
        }
        return output
    }

    private fun rewriteAuthorities(value: String, sourcePackageName: String, outputPackageName: String): String =
        value.split(';').joinToString(";") { authority ->
            if (isPackageOwned(authority, sourcePackageName)) {
                outputPackageName + authority.removePrefix(sourcePackageName)
            } else {
                authority
            }
        }

    private fun isPackageOwned(value: String, packageName: String): Boolean =
        value == packageName || value.startsWith("$packageName.")

    private fun isPermissionIdentityAttribute(attribute: StringAttribute): Boolean =
        attribute.namespace == ANDROID_NAMESPACE && when {
            attribute.elementName == PERMISSION && attribute.name == NAME_ATTRIBUTE -> true
            attribute.elementName in USES_PERMISSION_ELEMENTS && attribute.name == NAME_ATTRIBUTE -> true
            attribute.name in PERMISSION_REFERENCE_ATTRIBUTES -> true
            else -> false
        }

    private fun requireAttributeResource(document: ByteBuffer, initialOffset: Int, stringIndex: Int, expectedResource: Int) {
        var offset = initialOffset
        while (offset + 8 <= document.limit()) {
            val type = u16(document, offset)
            val size = chunkSize(document, offset)
            if (type == RESOURCE_MAP_TYPE) {
                if (u16(document, offset + 2) != 8 || (size - 8) % 4 != 0) throw IOException("Android manifest resource map is invalid")
                val count = (size - 8) / 4
                if (stringIndex >= count || u32(document, offset + 8 + stringIndex * 4) != expectedResource) {
                    throw IOException("Android manifest attribute resource mapping is incompatible")
                }
                return
            }
            if (type == START_ELEMENT_TYPE) break
            offset += size
        }
        throw IOException("Android manifest resource map is missing")
    }

    private data class FactoryLocation(val attributeOffset: Int, val factory: String)

    private fun locateFactory(bytes: ByteArray): FactoryLocation {
        val document = littleEndian(bytes)
        val stringPoolOffset = firstStringPool(document)
        val pool = StringPool.parse(document, stringPoolOffset)
        return findFactoryAttribute(document, pool, stringPoolOffset + pool.chunkSize)
    }

    private fun firstStringPool(document: ByteBuffer): Int {
        if (u16(document, 0) != XML_TYPE || u16(document, 2) < 8 || u32(document, 4) != document.limit()) {
            throw IOException("Android manifest is not a bounded binary XML document")
        }
        var offset = u16(document, 2)
        while (offset + 8 <= document.limit()) {
            val type = u16(document, offset)
            val size = chunkSize(document, offset)
            if (type == STRING_POOL_TYPE) return offset
            offset += size
        }
        throw IOException("Android manifest string pool is missing")
    }

    private fun findFactoryAttribute(document: ByteBuffer, pool: StringPool, initialOffset: Int): FactoryLocation {
        var offset = initialOffset
        while (offset + 8 <= document.limit()) {
            val type = u16(document, offset)
            val size = chunkSize(document, offset)
            if (type == START_ELEMENT_TYPE) {
                if (size < 36) throw IOException("Android manifest start element is truncated")
                val elementName = pool.string(u32(document, offset + 20))
                if (elementName == APPLICATION) {
                    val attributeStart = u16(document, offset + 24)
                    val attributeSize = u16(document, offset + 26)
                    val attributeCount = u16(document, offset + 28)
                    if (attributeSize < 20 || attributeCount > 1024) throw IOException("Application attributes are invalid")
                    val attributesOffset = offset + 16 + attributeStart
                    for (index in 0 until attributeCount) {
                        val attributeOffset = attributesOffset + index * attributeSize
                        if (attributeOffset < offset || attributeOffset + 20 > offset + size) {
                            throw IOException("Application attribute exceeds its chunk")
                        }
                        val namespace = pool.optionalString(u32(document, attributeOffset))
                        val name = pool.string(u32(document, attributeOffset + 4))
                        if (namespace == ANDROID_NAMESPACE && name == FACTORY_ATTRIBUTE) {
                            val rawIndex = u32(document, attributeOffset + 8)
                            val typedIndex = u32(document, attributeOffset + 16)
                            val factory = pool.string(if (rawIndex == NO_INDEX) typedIndex else rawIndex)
                            requireClassName(factory)
                            return FactoryLocation(attributeOffset, factory)
                        }
                    }
                    throw IOException("Host application has no android:appComponentFactory attribute")
                }
            }
            offset += size
        }
        throw IOException("Android manifest application element is missing")
    }

    private class StringPool(
        private val document: ByteBuffer,
        private val offset: Int,
        val chunkSize: Int,
        private val headerSize: Int,
        val stringCount: Int,
        private val styleCount: Int,
        private val flags: Int,
        private val stringsStart: Int,
        private val stylesStart: Int,
        private val stringOffsets: IntArray,
    ) {
        fun optionalString(index: Int): String? = if (index == NO_INDEX) null else string(index)

        fun indexOf(value: String): Int {
            for (index in 0 until stringCount) if (string(index) == value) return index
            return NO_INDEX
        }

        fun string(index: Int): String {
            if (index !in 0 until stringCount) throw IOException("Android manifest string index is invalid")
            val position = offset + stringsStart + stringOffsets[index]
            val chunkEnd = offset + if (stylesStart == 0) chunkSize else stylesStart
            if (position !in offset until chunkEnd) throw IOException("Android manifest string offset is invalid")
            return if (flags and UTF8_FLAG != 0) decodeUtf8(document, position, chunkEnd) else decodeUtf16(document, position, chunkEnd)
        }

        fun append(value: String): ByteArray {
            if (stringCount >= 1_000_000) throw IOException("Android manifest string pool is too large")
            val encoded = if (flags and UTF8_FLAG != 0) encodeUtf8(value) else encodeUtf16(value)
            val stringsEnd = if (stylesStart == 0) chunkSize else stylesStart
            val existingStringBytes = stringsEnd - stringsStart
            val unalignedDelta = 4 + encoded.size
            val delta = (unalignedDelta + 3) and -4
            val rebuilt = ByteArray(chunkSize + delta)

            copyRange(document, offset, offset + headerSize, rebuilt, 0)
            putU32(rebuilt, 8, stringCount + 1)
            putU32(rebuilt, 20, stringsStart + 4)
            putU32(rebuilt, 24, if (stylesStart == 0) 0 else stylesStart + delta)
            putU32(rebuilt, 4, rebuilt.size)

            var outputOffset = headerSize
            for (entry in stringOffsets) {
                putU32(rebuilt, outputOffset, entry)
                outputOffset += 4
            }
            putU32(rebuilt, outputOffset, existingStringBytes)
            outputOffset += 4
            val styleOffsetsBytes = styleCount * 4
            copyRange(document, offset + headerSize + stringCount * 4, offset + headerSize + stringCount * 4 + styleOffsetsBytes, rebuilt, outputOffset)
            outputOffset += styleOffsetsBytes
            val oldTableEnd = headerSize + stringCount * 4 + styleOffsetsBytes
            copyRange(document, offset + oldTableEnd, offset + stringsStart, rebuilt, outputOffset)
            outputOffset = stringsStart + 4
            copyRange(document, offset + stringsStart, offset + stringsEnd, rebuilt, outputOffset)
            outputOffset += existingStringBytes
            encoded.copyInto(rebuilt, outputOffset)
            if (stylesStart != 0) {
                copyRange(document, offset + stylesStart, offset + chunkSize, rebuilt, stylesStart + delta)
            }
            return rebuilt
        }

        companion object {
            fun parse(document: ByteBuffer, offset: Int): StringPool {
                if (u16(document, offset) != STRING_POOL_TYPE) throw IOException("Android manifest string pool type is invalid")
                val headerSize = u16(document, offset + 2)
                val chunkSize = chunkSize(document, offset)
                if (headerSize < 28 || offset + chunkSize > document.limit()) throw IOException("Android manifest string pool is truncated")
                val stringCount = u32(document, offset + 8)
                val styleCount = u32(document, offset + 12)
                val flags = u32(document, offset + 16)
                val stringsStart = u32(document, offset + 20)
                val stylesStart = u32(document, offset + 24)
                if (stringCount < 0 || styleCount < 0 || stringCount > 1_000_000 || styleCount > 1_000_000) throw IOException("Android manifest string counts are invalid")
                val offsetsEnd = headerSize.toLong() + (stringCount.toLong() + styleCount) * 4
                if (offsetsEnd > stringsStart || stringsStart >= chunkSize || (stylesStart != 0 && stylesStart !in stringsStart until chunkSize)) {
                    throw IOException("Android manifest string pool layout is invalid")
                }
                val stringOffsets = IntArray(stringCount) { index -> u32(document, offset + headerSize + index * 4) }
                return StringPool(document, offset, chunkSize, headerSize, stringCount, styleCount, flags, stringsStart, stylesStart, stringOffsets)
            }
        }
    }

    private fun decodeUtf8(buffer: ByteBuffer, start: Int, end: Int): String {
        var cursor = start
        val first = readLength8(buffer, cursor, end)
        cursor += first.second
        val second = readLength8(buffer, cursor, end)
        cursor += second.second
        if (second.first < 0 || cursor + second.first >= end || buffer.get(cursor + second.first).toInt() != 0) throw IOException("UTF-8 manifest string is truncated")
        val bytes = ByteArray(second.first)
        for (index in bytes.indices) bytes[index] = buffer.get(cursor + index)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun decodeUtf16(buffer: ByteBuffer, start: Int, end: Int): String {
        val length = readLength16(buffer, start, end)
        val cursor = start + length.second
        if (length.first < 0 || cursor + length.first * 2 + 2 > end || u16(buffer, cursor + length.first * 2) != 0) throw IOException("UTF-16 manifest string is truncated")
        val bytes = ByteArray(length.first * 2)
        for (index in bytes.indices) bytes[index] = buffer.get(cursor + index)
        return String(bytes, StandardCharsets.UTF_16LE)
    }

    private fun encodeUtf8(value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return encodeLength8(value.length) + encodeLength8(bytes.size) + bytes + byteArrayOf(0)
    }

    private fun encodeUtf16(value: String): ByteArray = encodeLength16(value.length) + value.toByteArray(StandardCharsets.UTF_16LE) + byteArrayOf(0, 0)

    private fun encodeLength8(length: Int): ByteArray {
        if (length > 0x7fff) throw IOException("Manifest string is too long")
        return if (length <= 0x7f) byteArrayOf(length.toByte()) else byteArrayOf(((length ushr 8) or 0x80).toByte(), length.toByte())
    }

    private fun encodeLength16(length: Int): ByteArray {
        if (length > 0x7fffffff) throw IOException("Manifest string is too long")
        return if (length <= 0x7fff) byteArrayOf(length.toByte(), (length ushr 8).toByte())
        else byteArrayOf(((length ushr 16) or 0x8000).toByte(), ((length ushr 24) and 0x7f).toByte(), length.toByte(), (length ushr 8).toByte())
    }

    private fun readLength8(buffer: ByteBuffer, offset: Int, end: Int): Pair<Int, Int> {
        if (offset >= end) throw IOException("Manifest string length is truncated")
        val first = buffer.get(offset).toInt() and 0xff
        if (first and 0x80 == 0) return first to 1
        if (offset + 1 >= end) throw IOException("Manifest string length is truncated")
        return (((first and 0x7f) shl 8) or (buffer.get(offset + 1).toInt() and 0xff)) to 2
    }

    private fun readLength16(buffer: ByteBuffer, offset: Int, end: Int): Pair<Int, Int> {
        if (offset + 2 > end) throw IOException("Manifest string length is truncated")
        val first = u16(buffer, offset)
        if (first and 0x8000 == 0) return first to 2
        if (offset + 4 > end) throw IOException("Manifest string length is truncated")
        return (((first and 0x7fff) shl 16) or u16(buffer, offset + 2)) to 4
    }

    private fun chunkSize(buffer: ByteBuffer, offset: Int): Int {
        val size = u32(buffer, offset + 4)
        if (size < 8 || offset.toLong() + size > buffer.limit()) throw IOException("Binary XML chunk exceeds its document")
        return size
    }

    private fun requireClassName(value: String) {
        if (!Regex("^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+$").matches(value) || value.length > 255) {
            throw IOException("AppComponentFactory class name is invalid")
        }
    }

    private fun requirePackageName(value: String) {
        if (!Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(value) || value.length > 223) {
            throw IOException("Android package name is invalid")
        }
    }

    private fun littleEndian(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun u16(buffer: ByteBuffer, offset: Int): Int = buffer.getShort(offset).toInt() and 0xffff
    private fun u8(buffer: ByteBuffer, offset: Int): Int = buffer.get(offset).toInt() and 0xff
    private fun u32(buffer: ByteBuffer, offset: Int): Int = buffer.getInt(offset)
    private fun putU8(bytes: ByteArray, offset: Int, value: Int) { bytes[offset] = value.toByte() }
    private fun putU16(bytes: ByteArray, offset: Int, value: Int) { littleEndian(bytes).putShort(offset, value.toShort()) }
    private fun putU32(bytes: ByteArray, offset: Int, value: Int) { littleEndian(bytes).putInt(offset, value) }
    private fun copyRange(buffer: ByteBuffer, start: Int, end: Int, output: ByteArray, outputOffset: Int) {
        if (start > end) throw IOException("Binary XML range is invalid")
        for (index in start until end) output[outputOffset + index - start] = buffer.get(index)
    }

    private val PERMISSION_REFERENCE_ATTRIBUTES = setOf("permission", "readPermission", "writePermission")
    private val USES_PERMISSION_ELEMENTS = setOf(USES_PERMISSION, USES_PERMISSION_SDK_23)
}
