package dev.thunder.injection.custom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class StableApkArchiveTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `repeat rewrite compacts unreachable records and size stabilizes`() {
        val source = temporary.newFile("source.apk")
        val stalePayload = "THUNDER_STALE_PAYLOAD_SENTINEL".toByteArray()
        val replacement = "current replacement".toByteArray()
        val nativePayload = ByteArray(257) { index -> (index * 13).toByte() }
        createArchive(
            source,
            FixtureEntry("assets/retained.txt", "retained".toByteArray()),
            FixtureEntry("replace.bin", stalePayload, stored = true),
            FixtureEntry("META-INF/MANIFEST.MF", "manifest signature metadata".toByteArray(), stored = true),
            FixtureEntry("META-INF/THUNDER.SF", "signature file".toByteArray(), stored = true),
            FixtureEntry("META-INF/THUNDER.RSA", "signature block".toByteArray(), stored = true),
        )

        val first = temporary.newFile("rewrite-1.apk")
        val result = StableApkArchive.rewrite(
            source,
            first,
            setOf("replace.bin"),
            listOf(
                StableApkArchive.AddedEntry("lib/arm64-v8a/libfixture.so", nativePayload, alignment = 4096),
                StableApkArchive.AddedEntry("replace.bin", replacement),
            ),
        )
        assertEquals(
            listOf("META-INF/MANIFEST.MF", "META-INF/THUNDER.RSA", "META-INF/THUNDER.SF"),
            result.removedSignatureEntries,
        )
        assertFalse(first.readBytes().containsSubsequence(stalePayload))
        assertEquals(0L, localEntry(first, "lib/arm64-v8a/libfixture.so").dataOffset % 4096L)

        var previous = first
        repeat(4) { index ->
            val next = temporary.newFile("rewrite-${index + 2}.apk")
            StableApkArchive.rewrite(
                previous,
                next,
                setOf("replace.bin"),
                listOf(StableApkArchive.AddedEntry("replace.bin", replacement)),
            )
            assertEquals("rewrite ${index + 2}", first.length(), next.length())
            assertFalse(next.readBytes().containsSubsequence(stalePayload))
            assertEquals(0L, localEntry(next, "lib/arm64-v8a/libfixture.so").dataOffset % 4096L)
            ZipFile(next).use { zip ->
                assertArrayEquals(nativePayload, zip.getInputStream(zip.getEntry("lib/arm64-v8a/libfixture.so")).readBytes())
                assertArrayEquals(replacement, zip.getInputStream(zip.getEntry("replace.bin")).readBytes())
                assertEquals(null, zip.getEntry("META-INF/THUNDER.RSA"))
            }
            previous = next
        }
    }

    @Test
    fun `retained data descriptor record and entry facts stay byte exact`() {
        val source = temporary.newFile("descriptor-source.apk")
        val retainedPayload = ByteArray(8193) { index -> (index * 31).toByte() }
        createArchive(
            source,
            FixtureEntry(
                name = "assets/descriptor.bin",
                bytes = retainedPayload,
                comment = "central comment",
                extra = byteArrayOf(0x34, 0x12, 0x02, 0x00, 0x55, 0x66),
            ),
            FixtureEntry("replace.bin", "old".toByteArray(), stored = true),
        )
        val beforeLocal = localEntry(source, "assets/descriptor.bin")
        assertTrue("fixture must use a data descriptor", beforeLocal.flags and 0x0008 != 0)
        val beforeRecord = beforeLocal.recordBytes(source)
        val beforeFacts = zipFacts(source, "assets/descriptor.bin")

        val output = temporary.newFile("descriptor-output.apk")
        StableApkArchive.rewrite(
            source,
            output,
            setOf("replace.bin"),
            listOf(StableApkArchive.AddedEntry("replace.bin", "new".toByteArray())),
        )

        val afterLocal = localEntry(output, "assets/descriptor.bin")
        assertTrue(afterLocal.flags and 0x0008 != 0)
        assertArrayEquals(beforeRecord, afterLocal.recordBytes(output))
        assertEquals(beforeFacts, zipFacts(output, "assets/descriptor.bin"))
        ZipFile(output).use { zip ->
            assertArrayEquals(retainedPayload, zip.getInputStream(zip.getEntry("assets/descriptor.bin")).readBytes())
        }
    }

    @Test
    fun `compaction anchors a local header at offset zero without losing retained alignment`() {
        val seed = temporary.newFile("offset-zero-seed.apk")
        createArchive(seed, FixtureEntry("replace.bin", "stale prefix".toByteArray(), stored = true))
        val nativePayload = ByteArray(4096) { index -> (index * 19).toByte() }
        val manifest = "retained manifest".toByteArray()
        val source = temporary.newFile("offset-zero-source.apk")
        StableApkArchive.rewrite(
            seed,
            source,
            setOf("replace.bin"),
            listOf(
                StableApkArchive.AddedEntry("replace.bin", "old replacement".toByteArray()),
                StableApkArchive.AddedEntry("lib/arm64-v8a/libfixture.so", nativePayload, alignment = 16_384),
                StableApkArchive.AddedEntry("AndroidManifest.xml", manifest),
            ),
        )
        assertEquals(0x04034b50L, firstSignature(source))
        assertEquals(0L, localEntry(source, "lib/arm64-v8a/libfixture.so").dataOffset % 16_384L)

        val output = temporary.newFile("offset-zero-output.apk")
        StableApkArchive.rewrite(source, output, setOf("replace.bin"), emptyList())

        assertEquals("Android requires an LFH at absolute byte zero", 0x04034b50L, firstSignature(output))
        assertEquals(0L, localEntry(output, "lib/arm64-v8a/libfixture.so").dataOffset % 16_384L)
        ZipFile(output).use { zip ->
            assertArrayEquals(nativePayload, zip.getInputStream(zip.getEntry("lib/arm64-v8a/libfixture.so")).readBytes())
            assertArrayEquals(manifest, zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
            assertEquals(null, zip.getEntry("replace.bin"))
        }

        val compactedAgain = temporary.newFile("offset-zero-output-2.apk")
        StableApkArchive.rewrite(output, compactedAgain, emptySet(), emptyList())
        assertEquals(output.length(), compactedAgain.length())
        assertEquals(0x04034b50L, firstSignature(compactedAgain))
        assertEquals(0L, localEntry(compactedAgain, "lib/arm64-v8a/libfixture.so").dataOffset % 16_384L)
    }

    @Test
    fun `compaction reframes a retained header when unchanged split has no other anchor`() {
        val seed = temporary.newFile("reframed-anchor-seed.apk")
        createArchive(seed, FixtureEntry("replace.bin", "seed".toByteArray(), stored = true))
        val retainedPayload = ByteArray(257) { index -> (index * 23).toByte() }
        val stalePrefix = "THUNDER_ORPHAN_PREFIX_SENTINEL".toByteArray()
        val source = temporary.newFile("reframed-anchor-source.apk")
        StableApkArchive.rewrite(
            seed,
            source,
            setOf("replace.bin"),
            listOf(
                StableApkArchive.AddedEntry("replace.bin", stalePrefix),
                StableApkArchive.AddedEntry(
                    "lib/arm64-v8a/libfixture.so",
                    retainedPayload,
                    alignment = 16_384,
                ),
            ),
        )
        val before = localEntry(source, "lib/arm64-v8a/libfixture.so")
        val beforeFacts = zipFacts(source, "lib/arm64-v8a/libfixture.so")
        assertTrue(before.localOffset > 0L)
        assertEquals(0L, before.dataOffset % 16_384L)
        assertTrue((before.dataOffset - before.localOffset) % 16_384L != 0L)

        val output = temporary.newFile("reframed-anchor-output.apk")
        StableApkArchive.rewrite(source, output, setOf("replace.bin"), emptyList())

        assertEquals("Android requires an LFH at absolute byte zero", 0x04034b50L, firstSignature(output))
        val after = localEntry(output, "lib/arm64-v8a/libfixture.so")
        assertEquals(0L, after.localOffset)
        assertEquals(0L, after.dataOffset % 16_384L)
        assertEquals(beforeFacts, zipFacts(output, "lib/arm64-v8a/libfixture.so"))
        assertFalse(output.readBytes().containsSubsequence(stalePrefix))
        ZipFile(output).use { zip ->
            assertArrayEquals(retainedPayload, zip.getInputStream(zip.getEntry("lib/arm64-v8a/libfixture.so")).readBytes())
            assertEquals(null, zip.getEntry("replace.bin"))
        }

        val compactedAgain = temporary.newFile("reframed-anchor-output-2.apk")
        StableApkArchive.rewrite(output, compactedAgain, emptySet(), emptyList())
        assertEquals(output.length(), compactedAgain.length())
        assertArrayEquals(
            after.recordBytes(output),
            localEntry(compactedAgain, "lib/arm64-v8a/libfixture.so").recordBytes(compactedAgain),
        )
    }

    @Test
    fun `malformed local bounds and data descriptors fail closed`() {
        val source = temporary.newFile("malformed-source.apk")
        createArchive(
            source,
            FixtureEntry("assets/descriptor.bin", ByteArray(2048) { it.toByte() }),
            FixtureEntry("replace.bin", "old".toByteArray(), stored = true),
        )

        val truncatedHeader = temporary.newFile("truncated-header.apk")
        val truncatedBytes = source.readBytes()
        val descriptor = localEntry(source, "assets/descriptor.bin")
        putU16(truncatedBytes, descriptor.localOffset.toInt() + 28, 0xffff)
        truncatedHeader.writeBytes(truncatedBytes)
        assertRewriteFails(truncatedHeader, temporary.newFile("truncated-output.apk"))

        val badDescriptor = temporary.newFile("bad-descriptor.apk")
        val descriptorBytes = source.readBytes()
        putU32(descriptorBytes, descriptor.descriptorOffset.toInt() + descriptor.descriptorValuesOffset, 0x11223344)
        badDescriptor.writeBytes(descriptorBytes)
        assertRewriteFails(badDescriptor, temporary.newFile("descriptor-output.apk"))

        val zip64Size = temporary.newFile("zip64-size.apk")
        val zip64Bytes = source.readBytes()
        putU32(zip64Bytes, descriptor.centralOffset + 20, 0xffffffffL)
        zip64Size.writeBytes(zip64Bytes)
        val failure = assertThrows(IOException::class.java) {
            StableApkArchive.rewrite(
                zip64Size,
                temporary.newFile("zip64-output.apk"),
                setOf("replace.bin"),
                listOf(StableApkArchive.AddedEntry("replace.bin", "new".toByteArray())),
            )
        }
        assertEquals("ZIP64 APKs are not supported", failure.message)
    }

    private fun assertRewriteFails(input: File, output: File) {
        assertTrue("temporary output fixture must be removable", output.delete())
        assertThrows(IOException::class.java) {
            StableApkArchive.rewrite(
                input,
                output,
                setOf("replace.bin"),
                listOf(StableApkArchive.AddedEntry("replace.bin", "new".toByteArray())),
            )
        }
        assertFalse("partial output must be removed", output.exists())
    }

    private data class FixtureEntry(
        val name: String,
        val bytes: ByteArray,
        val stored: Boolean = false,
        val comment: String? = null,
        val extra: ByteArray? = null,
    )

    private fun createArchive(file: File, vararg entries: FixtureEntry) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for (fixture in entries) {
                val entry = ZipEntry(fixture.name).apply {
                    time = 1_700_000_000_000L
                    comment = fixture.comment
                    extra = fixture.extra
                    if (fixture.stored) {
                        method = ZipEntry.STORED
                        size = fixture.bytes.size.toLong()
                        compressedSize = fixture.bytes.size.toLong()
                        crc = CRC32().apply { update(fixture.bytes) }.value
                    }
                }
                zip.putNextEntry(entry)
                zip.write(fixture.bytes)
                zip.closeEntry()
            }
        }
    }

    private data class ZipFacts(
        val method: Int,
        val crc: Long,
        val size: Long,
        val compressedSize: Long,
        val time: Long,
        val comment: String?,
        val extra: List<Byte>?,
    )

    private fun zipFacts(file: File, name: String): ZipFacts = ZipFile(file).use { zip ->
        val entry = zip.getEntry(name)
        ZipFacts(
            method = entry.method,
            crc = entry.crc,
            size = entry.size,
            compressedSize = entry.compressedSize,
            time = entry.time,
            comment = entry.comment,
            extra = entry.extra?.toList(),
        )
    }

    private data class LocalEntry(
        val centralOffset: Int,
        val localOffset: Long,
        val dataOffset: Long,
        val endOffset: Long,
        val flags: Int,
        val descriptorOffset: Long,
        val descriptorValuesOffset: Int,
    ) {
        fun recordBytes(file: File): ByteArray = file.readBytes().copyOfRange(localOffset.toInt(), endOffset.toInt())
    }

    private fun localEntry(file: File, wantedName: String): LocalEntry {
        val bytes = file.readBytes()
        val eocd = findSignatureBackwards(bytes, 0x06054b50)
        var cursor = u32(bytes, eocd + 16).toInt()
        val count = u16(bytes, eocd + 10)
        repeat(count) {
            assertEquals(0x02014b50L, u32(bytes, cursor))
            val flags = u16(bytes, cursor + 8)
            val compressedSize = u32(bytes, cursor + 20)
            val nameLength = u16(bytes, cursor + 28)
            val extraLength = u16(bytes, cursor + 30)
            val commentLength = u16(bytes, cursor + 32)
            val name = String(bytes, cursor + 46, nameLength, StandardCharsets.UTF_8)
            val localOffset = u32(bytes, cursor + 42)
            if (name == wantedName) {
                val localNameLength = u16(bytes, localOffset.toInt() + 26)
                val localExtraLength = u16(bytes, localOffset.toInt() + 28)
                val dataOffset = localOffset + 30 + localNameLength + localExtraLength
                val descriptorOffset = dataOffset + compressedSize
                val signedDescriptor = flags and 0x0008 != 0 &&
                    u32(bytes, descriptorOffset.toInt()) == 0x08074b50L
                val descriptorSize = if (flags and 0x0008 == 0) 0 else if (signedDescriptor) 16 else 12
                return LocalEntry(
                    centralOffset = cursor,
                    localOffset = localOffset,
                    dataOffset = dataOffset,
                    endOffset = descriptorOffset + descriptorSize,
                    flags = flags,
                    descriptorOffset = descriptorOffset,
                    descriptorValuesOffset = if (signedDescriptor) 4 else 0,
                )
            }
            cursor += 46 + nameLength + extraLength + commentLength
        }
        throw AssertionError("Missing fixture entry $wantedName")
    }

    private fun findSignatureBackwards(bytes: ByteArray, signature: Int): Int =
        (bytes.size - 4 downTo 0).first { u32(bytes, it) == signature.toLong() }

    private fun firstSignature(file: File): Long {
        val bytes = DataInputStream(file.inputStream()).use { input -> ByteArray(4).also(input::readFully) }
        return u32(bytes, 0)
    }

    private fun ByteArray.containsSubsequence(value: ByteArray): Boolean {
        if (value.isEmpty()) return true
        return (0..size - value.size).any { offset ->
            value.indices.all { index -> this[offset + index] == value[index] }
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(offset).toInt() and 0xffff

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset).toLong() and 0xffffffffL

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort())
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value.toInt())
    }
}
