package dev.thunder.injection.custom

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal compiled-resource table containing one conventional Android app package. */
internal object BinaryResourceTableFixture {
    const val SOURCE_PACKAGE = "com.discord"
    const val OUTPUT_PACKAGE = "dev.thunder.app"

    const val TABLE_HEADER_BYTES = 12
    const val PACKAGE_HEADER_BYTES = 288
    const val PACKAGE_CHUNK_OFFSET = TABLE_HEADER_BYTES
    const val PACKAGE_ID_OFFSET = PACKAGE_CHUNK_OFFSET + 8
    const val PACKAGE_NAME_OFFSET = PACKAGE_CHUNK_OFFSET + 12
    const val PACKAGE_NAME_UNITS = 128
    const val PACKAGE_NAME_BYTES = PACKAGE_NAME_UNITS * 2
    const val TABLE_BYTES = TABLE_HEADER_BYTES + PACKAGE_HEADER_BYTES

    fun create(packageName: String = SOURCE_PACKAGE, packageId: Int = 0x7f): ByteArray {
        require(packageName.isNotEmpty())
        require(packageName.length < PACKAGE_NAME_UNITS)
        require('\u0000' !in packageName)

        return ByteArray(TABLE_BYTES).also { bytes ->
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(0, 0x0002)
            buffer.putShort(2, TABLE_HEADER_BYTES.toShort())
            buffer.putInt(4, TABLE_BYTES)
            buffer.putInt(8, 1)

            buffer.putShort(PACKAGE_CHUNK_OFFSET, 0x0200)
            buffer.putShort(PACKAGE_CHUNK_OFFSET + 2, PACKAGE_HEADER_BYTES.toShort())
            buffer.putInt(PACKAGE_CHUNK_OFFSET + 4, PACKAGE_HEADER_BYTES)
            buffer.putInt(PACKAGE_ID_OFFSET, packageId)
            packageName.forEachIndexed { index, unit ->
                buffer.putShort(PACKAGE_NAME_OFFSET + index * 2, unit.code.toShort())
            }
        }
    }
}
