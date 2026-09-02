package dev.thunder.injection.custom

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object BinaryManifestFixture {
    const val SOURCE_PACKAGE = "com.discord"
    const val OUTPUT_PACKAGE = "dev.thunder.app"
    const val ORIGINAL_FACTORY = "com.example.OriginalFactory"
    const val SOURCE_PERMISSION = "$SOURCE_PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    const val SOURCE_AUTHORITY = "$SOURCE_PACKAGE.file-provider"
    const val COMPONENT_NAME = "$SOURCE_PACKAGE.main.MainActivity"
    const val ACTION_NAME = "$SOURCE_PACKAGE.intent.action.TEST"
    const val CATEGORY_NAME = "$SOURCE_PACKAGE.intent.category.TEST"
    const val SYSTEM_ACTION_NAME = "android.intent.action.VIEW"
    const val SYSTEM_CATEGORY_NAME = "android.intent.category.BROWSABLE"
    const val TASK_AFFINITY = "$SOURCE_PACKAGE.share"
    const val SPLIT_COMPONENT_NAME = "$SOURCE_PACKAGE.splits.CustomTabActivity"
    const val SPLIT_AUTHORITY = "$SOURCE_PACKAGE.split-provider;com.example.shared-provider"
    const val SPLIT_ACTION_NAME = "$SOURCE_PACKAGE.intent.action.CUSTOM_TAB"
    const val SPLIT_CATEGORY_NAME = "$SOURCE_PACKAGE.intent.category.CUSTOM_TAB"
    const val SPLIT_TASK_AFFINITY = "$SOURCE_PACKAGE.custom_tab"

    fun create(
        packageName: String = SOURCE_PACKAGE,
        coexistencePackageName: String = SOURCE_PACKAGE,
        routingPackageName: String = SOURCE_PACKAGE,
    ): ByteArray = encode(
        Element(
            "manifest",
            listOf(Attribute(null, "package", packageName)),
            listOf(
                Element(
                    "permission",
                    listOf(Attribute(ANDROID_NAMESPACE, "name", "$coexistencePackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")),
                ),
                Element(
                    "uses-permission",
                    listOf(Attribute(ANDROID_NAMESPACE, "name", "$coexistencePackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")),
                ),
                Element(
                    "application",
                    listOf(
                        Attribute(ANDROID_NAMESPACE, "name", "$SOURCE_PACKAGE.MainApplication"),
                        Attribute(ANDROID_NAMESPACE, "appComponentFactory", ORIGINAL_FACTORY),
                        Attribute(ANDROID_NAMESPACE, "label", "Discord"),
                        Attribute(
                            ANDROID_NAMESPACE,
                            "permission",
                            "$coexistencePackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                        ),
                    ),
                    listOf(
                        Element(
                            "provider",
                            listOf(
                                Attribute(ANDROID_NAMESPACE, "name", "$SOURCE_PACKAGE.provider.FileProvider"),
                                Attribute(ANDROID_NAMESPACE, "authorities", "$coexistencePackageName.file-provider"),
                                Attribute(
                                    ANDROID_NAMESPACE,
                                    "readPermission",
                                    "$coexistencePackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                                ),
                            ),
                        ),
                        Element(
                            "activity",
                            listOf(
                                Attribute(ANDROID_NAMESPACE, "name", COMPONENT_NAME),
                                Attribute(ANDROID_NAMESPACE, "taskAffinity", "$coexistencePackageName.share"),
                                Attribute(ANDROID_NAMESPACE, "label", "Discord activity"),
                            ),
                        ),
                        Element(
                            "activity-alias",
                            listOf(
                                Attribute(ANDROID_NAMESPACE, "name", "$SOURCE_PACKAGE.main.MainAlias"),
                                Attribute(ANDROID_NAMESPACE, "targetActivity", COMPONENT_NAME),
                                Attribute(ANDROID_NAMESPACE, "label", "Discord alias"),
                            ),
                        ),
                        Element(
                            "receiver",
                            listOf(Attribute(ANDROID_NAMESPACE, "name", "$SOURCE_PACKAGE.notifications.Receiver")),
                            listOf(
                                Element(
                                    "intent-filter",
                                    children = listOf(
                                        Element(
                                            "action",
                                            listOf(Attribute(ANDROID_NAMESPACE, "name", "$routingPackageName.intent.action.TEST")),
                                        ),
                                        Element("action", listOf(Attribute(ANDROID_NAMESPACE, "name", SYSTEM_ACTION_NAME))),
                                        Element(
                                            "category",
                                            listOf(Attribute(ANDROID_NAMESPACE, "name", "$routingPackageName.intent.category.TEST")),
                                        ),
                                        Element("category", listOf(Attribute(ANDROID_NAMESPACE, "name", SYSTEM_CATEGORY_NAME))),
                                    ),
                                ),
                            ),
                        ),
                        Element(
                            "meta-data",
                            listOf(
                                Attribute(ANDROID_NAMESPACE, "name", "$SOURCE_PACKAGE.features.FLAG"),
                                Attribute(ANDROID_NAMESPACE, "value", SOURCE_PACKAGE),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    fun createSplit(
        packageName: String = SOURCE_PACKAGE,
        taskAffinityPackageName: String = SOURCE_PACKAGE,
        providerPermissionPackageName: String = SOURCE_PACKAGE,
        routingPackageName: String = SOURCE_PACKAGE,
    ): ByteArray = encode(
        Element(
            "manifest",
            listOf(Attribute(null, "package", packageName)),
            listOf(
                Element(
                    "uses-permission",
                    listOf(
                        Attribute(
                            ANDROID_NAMESPACE,
                            "name",
                            "$providerPermissionPackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                        ),
                    ),
                ),
                Element(
                    "application",
                    children = listOf(
                        Element(
                            "provider",
                            listOf(
                                Attribute(ANDROID_NAMESPACE, "name", "$SOURCE_PACKAGE.splits.SplitProvider"),
                                Attribute(
                                    ANDROID_NAMESPACE,
                                    "authorities",
                                    "$providerPermissionPackageName.split-provider;com.example.shared-provider",
                                ),
                                Attribute(
                                    ANDROID_NAMESPACE,
                                    "writePermission",
                                    "$providerPermissionPackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                                ),
                            ),
                        ),
                        Element(
                            "activity",
                            listOf(
                                Attribute(ANDROID_NAMESPACE, "name", SPLIT_COMPONENT_NAME),
                                Attribute(ANDROID_NAMESPACE, "taskAffinity", "$taskAffinityPackageName.custom_tab"),
                            ),
                            listOf(
                                Element(
                                    "intent-filter",
                                    children = listOf(
                                        Element(
                                            "action",
                                            listOf(Attribute(ANDROID_NAMESPACE, "name", "$routingPackageName.intent.action.CUSTOM_TAB")),
                                        ),
                                        Element("action", listOf(Attribute(ANDROID_NAMESPACE, "name", SYSTEM_ACTION_NAME))),
                                        Element(
                                            "category",
                                            listOf(Attribute(ANDROID_NAMESPACE, "name", "$routingPackageName.intent.category.CUSTOM_TAB")),
                                        ),
                                        Element("category", listOf(Attribute(ANDROID_NAMESPACE, "name", SYSTEM_CATEGORY_NAME))),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private data class Attribute(val namespace: String?, val name: String, val value: String)
    private data class Element(
        val name: String,
        val attributes: List<Attribute> = emptyList(),
        val children: List<Element> = emptyList(),
    )

    private fun encode(root: Element): ByteArray {
        // Recovery activity insertion requires the framework "exported" attribute to
        // already exist in the binary XML string/resource pools.
        val strings = linkedSetOf(ANDROID_NAMESPACE, "exported")
        fun collect(element: Element) {
            strings += element.name
            for (attribute in element.attributes) {
                attribute.namespace?.let(strings::add)
                strings += attribute.name
                strings += attribute.value
            }
            element.children.forEach(::collect)
        }
        collect(root)
        val values = strings.toList()
        val indices = values.withIndex().associate { (index, value) -> value to index }
        val encodedStrings = values.map { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            byteArrayOf(value.length.toByte(), bytes.size.toByte()) + bytes + byteArrayOf(0)
        }
        val stringOffsets = IntArray(values.size)
        var stringBytesSize = 0
        for (index in encodedStrings.indices) {
            stringOffsets[index] = stringBytesSize
            stringBytesSize += encodedStrings[index].size
        }
        val paddedStringBytes = (stringBytesSize + 3) and -4
        val poolSize = 28 + values.size * 4 + paddedStringBytes
        val resourceMapSize = 8 + values.size * 4
        val elementBytes = encodedElementSize(root)
        val result = ByteArray(8 + poolSize + resourceMapSize + elementBytes)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, 0x0003)
        buffer.putShort(2, 8)
        buffer.putInt(4, result.size)

        val pool = 8
        buffer.putShort(pool, 0x0001)
        buffer.putShort(pool + 2, 28)
        buffer.putInt(pool + 4, poolSize)
        buffer.putInt(pool + 8, values.size)
        buffer.putInt(pool + 16, 0x100)
        buffer.putInt(pool + 20, 28 + values.size * 4)
        for (index in stringOffsets.indices) buffer.putInt(pool + 28 + index * 4, stringOffsets[index])
        var cursor = pool + 28 + values.size * 4
        for (value in encodedStrings) {
            value.copyInto(result, cursor)
            cursor += value.size
        }

        val resourceMap = 8 + poolSize
        buffer.putShort(resourceMap, 0x0180)
        buffer.putShort(resourceMap + 2, 8)
        buffer.putInt(resourceMap + 4, resourceMapSize)
        indices["name"]?.let { buffer.putInt(resourceMap + 8 + it * 4, 0x01010003) }
        indices["exported"]?.let { buffer.putInt(resourceMap + 8 + it * 4, 0x01010010) }
        writeElement(result, buffer, resourceMap + resourceMapSize, root, indices)
        return result
    }

    private fun encodedElementSize(element: Element): Int =
        36 + element.attributes.size * 20 + element.children.sumOf(::encodedElementSize) + 24

    private fun writeElement(
        output: ByteArray,
        buffer: ByteBuffer,
        offset: Int,
        element: Element,
        indices: Map<String, Int>,
    ): Int {
        val startSize = 36 + element.attributes.size * 20
        buffer.putShort(offset, 0x0102)
        buffer.putShort(offset + 2, 16)
        buffer.putInt(offset + 4, startSize)
        buffer.putInt(offset + 8, 1)
        buffer.putInt(offset + 12, -1)
        buffer.putInt(offset + 16, -1)
        buffer.putInt(offset + 20, indices.getValue(element.name))
        buffer.putShort(offset + 24, 20)
        buffer.putShort(offset + 26, 20)
        buffer.putShort(offset + 28, element.attributes.size.toShort())
        for ((index, attribute) in element.attributes.withIndex()) {
            val attributeOffset = offset + 36 + index * 20
            buffer.putInt(attributeOffset, attribute.namespace?.let(indices::getValue) ?: -1)
            buffer.putInt(attributeOffset + 4, indices.getValue(attribute.name))
            buffer.putInt(attributeOffset + 8, indices.getValue(attribute.value))
            buffer.putShort(attributeOffset + 12, 8)
            output[attributeOffset + 15] = 3
            buffer.putInt(attributeOffset + 16, indices.getValue(attribute.value))
        }

        var cursor = offset + startSize
        for (child in element.children) cursor = writeElement(output, buffer, cursor, child, indices)
        buffer.putShort(cursor, 0x0103)
        buffer.putShort(cursor + 2, 16)
        buffer.putInt(cursor + 4, 24)
        buffer.putInt(cursor + 8, 1)
        buffer.putInt(cursor + 12, -1)
        buffer.putInt(cursor + 16, -1)
        buffer.putInt(cursor + 20, indices.getValue(element.name))
        return cursor + 24
    }

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
}
