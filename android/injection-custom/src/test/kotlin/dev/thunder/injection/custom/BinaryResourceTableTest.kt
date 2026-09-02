package dev.thunder.injection.custom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryResourceTableTest {
    @Test
    fun `rewrite changes only the fixed package-name field`() {
        val original = BinaryResourceTableFixture.create()
        val rewritten = original.copyOf()

        val returned = BinaryResourceTable.rewritePackageName(
            rewritten,
            BinaryResourceTableFixture.SOURCE_PACKAGE,
            BinaryResourceTableFixture.OUTPUT_PACKAGE,
        )

        assertSame(rewritten, returned)
        assertEquals(BinaryResourceTableFixture.OUTPUT_PACKAGE, BinaryResourceTable.readPackageName(rewritten))
        assertArrayEquals(BinaryResourceTableFixture.create(BinaryResourceTableFixture.OUTPUT_PACKAGE), rewritten)
        rewritten.indices
            .filterNot { it in packageNameRange }
            .forEach { index -> assertEquals("byte $index", original[index], rewritten[index]) }
    }

    @Test
    fun `read rejects malformed roots and root bounds`() {
        assertReadFailure(
            "Resource table root is invalid",
            BinaryResourceTableFixture.create().copyOf(11),
        )
        assertReadFailure(
            "Resource table root is invalid",
            BinaryResourceTableFixture.create().mutateU16(0, 0x0003),
        )
        assertReadFailure(
            "Resource table bounds are invalid",
            BinaryResourceTableFixture.create().mutateU16(2, 11),
        )
        assertReadFailure(
            "Resource table bounds are invalid",
            BinaryResourceTableFixture.create().mutateU32(4, BinaryResourceTableFixture.TABLE_BYTES - 1),
        )
        assertReadFailure(
            "Resource table size is invalid",
            BinaryResourceTableFixture.create().mutateU32(4, BinaryResourceTableFixture.TABLE_BYTES + 1),
        )
    }

    @Test
    fun `read rejects malformed child bounds`() {
        assertReadFailure(
            "Resource table child bounds are invalid",
            BinaryResourceTableFixture.create().mutateU16(BinaryResourceTableFixture.PACKAGE_CHUNK_OFFSET + 2, 7),
        )
        assertReadFailure(
            "Resource table child bounds are invalid",
            BinaryResourceTableFixture.create().mutateU32(BinaryResourceTableFixture.PACKAGE_CHUNK_OFFSET + 4, 7),
        )
        assertReadFailure(
            "Resource table child size is invalid",
            BinaryResourceTableFixture.create().mutateU32(
                BinaryResourceTableFixture.PACKAGE_CHUNK_OFFSET + 4,
                BinaryResourceTableFixture.PACKAGE_HEADER_BYTES + 1,
            ),
        )
        assertReadFailure(
            "Resource table package header is truncated",
            BinaryResourceTableFixture.create().mutateU16(
                BinaryResourceTableFixture.PACKAGE_CHUNK_OFFSET + 2,
                BinaryResourceTableFixture.PACKAGE_NAME_BYTES + 11,
            ),
        )

        val trailingPartialHeader = BinaryResourceTableFixture.create().copyOf(BinaryResourceTableFixture.TABLE_BYTES + 4)
            .mutateU32(4, BinaryResourceTableFixture.TABLE_BYTES + 4)
        assertReadFailure("Resource table child header is truncated", trailingPartialHeader)
    }

    @Test
    fun `read rejects inconsistent absent and multiple package chunks`() {
        assertReadFailure(
            "Resource table package count is invalid",
            BinaryResourceTableFixture.create().mutateU32(8, 0),
        )

        val absent = BinaryResourceTableFixture.create()
            .mutateU16(BinaryResourceTableFixture.PACKAGE_CHUNK_OFFSET, 0x0201)
            .mutateU32(8, 0)
        assertReadFailure("Resource table must contain one app package", absent)

        val single = BinaryResourceTableFixture.create()
        val packageChunk = single.copyOfRange(BinaryResourceTableFixture.PACKAGE_CHUNK_OFFSET, single.size)
        val multiple = ByteArray(BinaryResourceTableFixture.TABLE_HEADER_BYTES + packageChunk.size * 2)
        single.copyInto(multiple, endIndex = BinaryResourceTableFixture.TABLE_HEADER_BYTES)
        packageChunk.copyInto(multiple, BinaryResourceTableFixture.TABLE_HEADER_BYTES)
        packageChunk.copyInto(multiple, BinaryResourceTableFixture.TABLE_HEADER_BYTES + packageChunk.size)
        multiple.mutateU32(4, multiple.size).mutateU32(8, 2)
        assertReadFailure("Resource table must contain one app package", multiple)
    }

    @Test
    fun `read and rewrite reject a non-app package id`() {
        val resourceTable = BinaryResourceTableFixture.create(packageId = 0x80)

        assertReadFailure("Resource table app package id is invalid", resourceTable)
        assertRewriteFailure(
            "Resource table app package id is invalid",
            resourceTable,
            BinaryResourceTableFixture.SOURCE_PACKAGE,
            BinaryResourceTableFixture.OUTPUT_PACKAGE,
        )
    }

    @Test
    fun `rewrite rejects a source mismatch without mutating input`() {
        val resourceTable = BinaryResourceTableFixture.create()
        val original = resourceTable.copyOf()

        assertRewriteFailure(
            "Resource table package identity differs from the selected source",
            resourceTable,
            "com.example.not.discord",
            BinaryResourceTableFixture.OUTPUT_PACKAGE,
        )

        assertArrayEquals(original, resourceTable)
    }

    @Test
    fun `read rejects empty unterminated and non-zero-padded package names`() {
        val empty = BinaryResourceTableFixture.create().also { bytes ->
            bytes.fill(0, BinaryResourceTableFixture.PACKAGE_NAME_OFFSET, packageNameRange.last + 1)
        }
        assertReadFailure("Resource table package name is invalid", empty)

        val unterminated = BinaryResourceTableFixture.create().also { bytes ->
            repeat(BinaryResourceTableFixture.PACKAGE_NAME_UNITS) { index ->
                bytes.mutateU16(BinaryResourceTableFixture.PACKAGE_NAME_OFFSET + index * 2, 'a'.code)
            }
        }
        assertReadFailure("Resource table package name is invalid", unterminated)

        val nonZeroPadded = BinaryResourceTableFixture.create().mutateU16(
            BinaryResourceTableFixture.PACKAGE_NAME_OFFSET +
                (BinaryResourceTableFixture.SOURCE_PACKAGE.length + 1) * 2,
            'x'.code,
        )
        assertReadFailure("Resource table package name is not zero padded", nonZeroPadded)
    }

    @Test
    fun `rewrite enforces the fixed output-name capacity without mutation`() {
        val maximum = "a".repeat(BinaryResourceTableFixture.PACKAGE_NAME_UNITS - 1)
        val boundary = BinaryResourceTableFixture.create()
        BinaryResourceTable.rewritePackageName(boundary, BinaryResourceTableFixture.SOURCE_PACKAGE, maximum)
        assertEquals(maximum, BinaryResourceTable.readPackageName(boundary))

        listOf("", "bad\u0000name", "a".repeat(BinaryResourceTableFixture.PACKAGE_NAME_UNITS)).forEach { invalid ->
            val resourceTable = BinaryResourceTableFixture.create()
            val original = resourceTable.copyOf()
            assertRewriteFailure(
                "Output resource package name is invalid",
                resourceTable,
                BinaryResourceTableFixture.SOURCE_PACKAGE,
                invalid,
            )
            assertArrayEquals(original, resourceTable)
        }
    }

    private fun assertReadFailure(message: String, bytes: ByteArray) {
        val failure = assertThrows(IOException::class.java) { BinaryResourceTable.readPackageName(bytes) }
        assertEquals(message, failure.message)
    }

    private fun assertRewriteFailure(message: String, bytes: ByteArray, source: String, output: String) {
        val failure = assertThrows(IOException::class.java) {
            BinaryResourceTable.rewritePackageName(bytes, source, output)
        }
        assertEquals(message, failure.message)
    }

    private fun ByteArray.mutateU16(offset: Int, value: Int): ByteArray = apply {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort())
    }

    private fun ByteArray.mutateU32(offset: Int, value: Int): ByteArray = apply {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
    }

    private companion object {
        val packageNameRange = BinaryResourceTableFixture.PACKAGE_NAME_OFFSET until
            BinaryResourceTableFixture.PACKAGE_NAME_OFFSET + BinaryResourceTableFixture.PACKAGE_NAME_BYTES
    }
}
