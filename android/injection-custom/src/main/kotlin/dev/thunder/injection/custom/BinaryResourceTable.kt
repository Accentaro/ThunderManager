package dev.thunder.injection.custom

import java.io.IOException

/** Narrow parser for the fixed package identity in Android's compiled resource table. */
internal object BinaryResourceTable {
    private const val TABLE_TYPE = 0x0002
    private const val PACKAGE_TYPE = 0x0200
    private const val APP_PACKAGE_ID = 0x7fL
    private const val CHUNK_HEADER_BYTES = 8
    private const val TABLE_HEADER_BYTES = 12
    private const val PACKAGE_ID_OFFSET = 8
    private const val PACKAGE_NAME_OFFSET = 12
    private const val PACKAGE_NAME_UNITS = 128
    private const val PACKAGE_NAME_BYTES = PACKAGE_NAME_UNITS * 2
    private const val PACKAGE_NAME_END = PACKAGE_NAME_OFFSET + PACKAGE_NAME_BYTES

    /**
     * Rewrites only ResTable_package.name. The caller owns [bytes], which is validated completely
     * before it is mutated so malformed input can never leave a partially rewritten table.
     */
    fun rewritePackageName(bytes: ByteArray, sourcePackageName: String, outputPackageName: String): ByteArray {
        val packageChunk = singlePackageChunk(bytes)
        val packageId = u32(bytes, packageChunk + PACKAGE_ID_OFFSET)
        if (packageId != APP_PACKAGE_ID) throw IOException("Resource table app package id is invalid")
        val current = readFixedName(bytes, packageChunk + PACKAGE_NAME_OFFSET)
        if (current != sourcePackageName) throw IOException("Resource table package identity differs from the selected source")
        val encoded = outputPackageName.toCharArray()
        if (encoded.isEmpty() || encoded.size >= PACKAGE_NAME_UNITS || encoded.any { it == '\u0000' }) {
            throw IOException("Output resource package name is invalid")
        }

        val start = packageChunk + PACKAGE_NAME_OFFSET
        bytes.fill(0, start, start + PACKAGE_NAME_BYTES)
        encoded.forEachIndexed { index, value -> putU16(bytes, start + index * 2, value.code) }
        if (readPackageName(bytes) != outputPackageName) throw IOException("Resource table package rewrite verification failed")
        return bytes
    }

    fun readPackageName(bytes: ByteArray): String {
        val packageChunk = singlePackageChunk(bytes)
        if (u32(bytes, packageChunk + PACKAGE_ID_OFFSET) != APP_PACKAGE_ID) {
            throw IOException("Resource table app package id is invalid")
        }
        return readFixedName(bytes, packageChunk + PACKAGE_NAME_OFFSET)
    }

    private fun singlePackageChunk(bytes: ByteArray): Int {
        if (bytes.size < TABLE_HEADER_BYTES || u16(bytes, 0) != TABLE_TYPE) {
            throw IOException("Resource table root is invalid")
        }
        val headerSize = u16(bytes, 2)
        val declaredSize = boundedSize(u32(bytes, 4), bytes.size, "Resource table size is invalid")
        if (headerSize < TABLE_HEADER_BYTES || headerSize > declaredSize || declaredSize != bytes.size) {
            throw IOException("Resource table bounds are invalid")
        }
        val declaredPackages = u32(bytes, 8)
        val packages = ArrayList<Int>()
        var cursor = headerSize
        while (cursor < declaredSize) {
            if (declaredSize - cursor < CHUNK_HEADER_BYTES) throw IOException("Resource table child header is truncated")
            val childHeaderSize = u16(bytes, cursor + 2)
            val childSize = boundedSize(u32(bytes, cursor + 4), declaredSize - cursor, "Resource table child size is invalid")
            if (childHeaderSize < CHUNK_HEADER_BYTES || childHeaderSize > childSize) {
                throw IOException("Resource table child bounds are invalid")
            }
            if (u16(bytes, cursor) == PACKAGE_TYPE) {
                if (childHeaderSize < PACKAGE_NAME_END) throw IOException("Resource table package header is truncated")
                packages += cursor
            }
            cursor += childSize
        }
        if (cursor != declaredSize || declaredPackages != packages.size.toLong()) {
            throw IOException("Resource table package count is invalid")
        }
        if (packages.size != 1) throw IOException("Resource table must contain one app package")
        return packages.single()
    }

    private fun readFixedName(bytes: ByteArray, offset: Int): String {
        val value = StringBuilder()
        var terminated = false
        repeat(PACKAGE_NAME_UNITS) { index ->
            val unit = u16(bytes, offset + index * 2)
            if (unit == 0) {
                terminated = true
            } else {
                if (terminated) throw IOException("Resource table package name is not zero padded")
                value.append(unit.toChar())
            }
        }
        if (!terminated || value.isEmpty()) throw IOException("Resource table package name is invalid")
        return value.toString()
    }

    private fun boundedSize(value: Long, maximum: Int, message: String): Int {
        if (value <= 0L || value > maximum.toLong()) throw IOException(message)
        return value.toInt()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) throw IOException("Resource table read is out of bounds")
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) throw IOException("Resource table read is out of bounds")
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }
}
