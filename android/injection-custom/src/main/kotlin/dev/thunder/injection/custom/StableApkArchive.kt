package dev.thunder.injection.custom

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.zip.CRC32

internal object StableApkArchive {
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val APK_SIGNING_MAGIC = "APK Sig Block 42"
    private const val UTF8_FLAG = 0x0800
    private const val DATA_DESCRIPTOR_FLAG = 0x0008
    private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val MAX_RETAINED_ALIGNMENT = 16_384
    private const val MAX_EOCD_SEARCH = 65_557

    data class AddedEntry(val name: String, val bytes: ByteArray, val alignment: Int = 1)
    data class RewriteResult(val removedSignatureEntries: List<String>)

    private data class LocalRecord(
        val offset: Long,
        val dataOffset: Long,
        val endOffset: Long,
        val alignment: Int,
        val extraLength: Int,
    )

    private data class CentralEntry(
        val name: String,
        val raw: ByteArray,
        val localOffset: Long,
        val local: LocalRecord,
    )

    private data class CentralRecord(
        val name: String,
        val nameBytes: ByteArray,
        val raw: ByteArray,
        val localOffset: Long,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
    )

    private data class Directory(val entries: List<CentralEntry>)

    fun rewrite(
        input: File,
        output: File,
        replacedNames: Set<String>,
        addedEntries: List<AddedEntry>,
    ): RewriteResult {
        require(input.isFile && input.canRead()) { "Input APK is unreadable" }
        require(input.canonicalFile != output.canonicalFile) { "Output APK must not overwrite its input" }
        val directory = parseDirectory(input)
        val existingNames = directory.entries.map { it.name }
        if (existingNames.toSet().size != existingNames.size) throw IOException("APK contains duplicate entry names")
        if (!existingNames.containsAll(replacedNames)) throw IOException("APK replacement entry is missing")
        val newNames = addedEntries.map { it.name }
        if (newNames.toSet().size != newNames.size || newNames.any { it.isBlank() || it.startsWith('/') || it.split('/').contains("..") }) {
            throw IOException("Added APK entry name is invalid")
        }

        output.parentFile?.let { parent -> if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create APK output directory") }
        val removedSignatures = directory.entries.map { it.name }.filter(::isJarSignature)
        val retained = directory.entries.filterNot { it.name in replacedNames || isJarSignature(it.name) }
        val retainedNames = retained.mapTo(HashSet()) { it.name }
        if (newNames.any { it in retainedNames }) throw IOException("Added APK entry collides with retained input")
        if (retained.isEmpty() && addedEntries.isEmpty()) throw IOException("APK rewrite would remove every entry")

        var complete = false
        try {
            RandomAccessFile(input, "r").use { source ->
                BufferedOutputStream(Files.newOutputStream(output.toPath()), 256 * 1024).use { sink ->
                    var offset = 0L
                    val retainedOffsets = HashMap<String, Long>(retained.size)
                    val retainedByLocalOffset = retained.sortedBy { it.localOffset }
                    val firstRetained = retainedByLocalOffset.firstOrNull()
                    val leadingPadding = firstRetained?.let(::paddingAtOffsetZero) ?: 0
                    // Android's ziparchive rejects a valid ZIP if byte zero is not an LFH. A
                    // centrally-unreferenced old record may previously have occupied byte zero;
                    // dropping it can otherwise leave alignment padding before the first live
                    // stored entry. Reorder one byte-exact record that needs no leading padding,
                    // anchor with a newly added LFH, or extend one retained local extra field when
                    // an unchanged split has neither option. Preserve payload bytes, metadata, and
                    // alignment in every case.
                    val retainedAnchor = if (leadingPadding != 0) {
                        retainedByLocalOffset.firstOrNull { entry -> paddingAtOffsetZero(entry) == 0 }
                    } else {
                        null
                    }
                    val needsFallbackAnchor = leadingPadding != 0 && retainedAnchor == null
                    val anchoredAddedEntry = if (needsFallbackAnchor) addedEntries.firstOrNull() else null
                    val reframedRetainedAnchor = if (needsFallbackAnchor && anchoredAddedEntry == null) {
                        retainedByLocalOffset.firstOrNull(::canReframeAtOffsetZero)
                    } else {
                        null
                    }
                    if (needsFallbackAnchor && anchoredAddedEntry == null && reframedRetainedAnchor == null) {
                        throw IOException("APK compaction cannot preserve offset-zero LFH and retained alignment")
                    }
                    val retainedWriteAnchor = retainedAnchor ?: reframedRetainedAnchor
                    val retainedWriteOrder = if (retainedWriteAnchor == null) {
                        retainedByLocalOffset
                    } else {
                        listOf(retainedWriteAnchor) + retainedByLocalOffset.filterNot { it === retainedWriteAnchor }
                    }

                    val newCentral = ArrayList<ByteArray>(addedEntries.size)
                    if (anchoredAddedEntry != null) {
                        val encoded = encodeStoredEntry(anchoredAddedEntry, offset)
                        sink.write(encoded.localHeader)
                        sink.write(anchoredAddedEntry.bytes)
                        newCentral += encoded.central
                        offset += encoded.localHeader.size + anchoredAddedEntry.bytes.size
                    }

                    for (entry in retainedWriteOrder) {
                        if (entry === reframedRetainedAnchor) {
                            if (offset != 0L) throw IOException("Reframed APK anchor must start at offset zero")
                            retainedOffsets[entry.name] = offset
                            offset += writeReframedAnchor(source, sink, entry)
                            continue
                        }
                        val headerSize = entry.local.dataOffset - entry.local.offset
                        val padding = alignmentPadding(offset + headerSize, entry.local.alignment)
                        writeZeros(sink, padding)
                        offset += padding
                        retainedOffsets[entry.name] = offset
                        val recordSize = entry.local.endOffset - entry.local.offset
                        copyExactly(source, sink, entry.local.offset, recordSize)
                        offset += recordSize
                    }

                    for (entry in addedEntries) {
                        if (entry === anchoredAddedEntry) continue
                        val encoded = encodeStoredEntry(entry, offset)
                        sink.write(encoded.localHeader)
                        sink.write(entry.bytes)
                        newCentral += encoded.central
                        offset += encoded.localHeader.size + entry.bytes.size
                    }
                    val centralOffset = offset
                    for (entry in retained) {
                        val patched = entry.raw.copyOf()
                        val retainedOffset = retainedOffsets[entry.name]
                            ?: throw IOException("Retained APK entry offset is missing")
                        if (retainedOffset > 0xffffffffL) throw IOException("ZIP64 APKs are not supported")
                        putU32(patched, 42, retainedOffset)
                        sink.write(patched)
                        offset += patched.size
                    }
                    for (entry in newCentral) {
                        sink.write(entry)
                        offset += entry.size
                    }
                    val centralSize = offset - centralOffset
                    val totalEntries = retained.size + newCentral.size
                    if (totalEntries > 0xffff || centralOffset > 0xffffffffL || centralSize > 0xffffffffL) {
                        throw IOException("ZIP64 APKs are not supported")
                    }
                    sink.write(eocd(totalEntries, centralSize, centralOffset))
                }
            }
            complete = true
        } finally {
            if (!complete) Files.deleteIfExists(output.toPath())
        }
        return RewriteResult(removedSignatures.sorted())
    }

    private data class EncodedEntry(val localHeader: ByteArray, val central: ByteArray)

    private fun encodeStoredEntry(entry: AddedEntry, localOffset: Long): EncodedEntry {
        if (entry.alignment < 1 || entry.alignment > 16_384 || entry.alignment.countOneBits() != 1) throw IOException("APK entry alignment is invalid")
        val name = entry.name.toByteArray(StandardCharsets.UTF_8)
        if (name.size > 0xffff || entry.bytes.size.toLong() > 0xffffffffL || localOffset > 0xffffffffL) throw IOException("APK entry exceeds ZIP32 limits")
        val baseDataOffset = localOffset + 30 + name.size
        val extraSize = if (entry.alignment == 1) 0 else {
            val payload = ((entry.alignment - ((baseDataOffset + 4) % entry.alignment).toInt()) % entry.alignment)
            4 + payload
        }
        val extra = ByteArray(extraSize)
        if (extraSize > 0) {
            putU16(extra, 0, 0xffff)
            putU16(extra, 2, extraSize - 4)
        }
        val crc = CRC32().apply { update(entry.bytes) }.value
        // Keep payload bytes in their one caller-owned array. Copying a 50+ MiB DEX into another
        // local-entry array drove the on-device manager into its 256 MiB heap ceiling.
        val local = ByteArray(30 + name.size + extra.size)
        putU32(local, 0, LOCAL_SIGNATURE.toLong())
        putU16(local, 4, 20)
        putU16(local, 6, UTF8_FLAG)
        putU16(local, 8, 0)
        putU32(local, 14, crc)
        putU32(local, 18, entry.bytes.size.toLong())
        putU32(local, 22, entry.bytes.size.toLong())
        putU16(local, 26, name.size)
        putU16(local, 28, extra.size)
        name.copyInto(local, 30)
        extra.copyInto(local, 30 + name.size)

        val central = ByteArray(46 + name.size + extra.size)
        putU32(central, 0, CENTRAL_SIGNATURE.toLong())
        putU16(central, 4, 0x031e)
        putU16(central, 6, 20)
        putU16(central, 8, UTF8_FLAG)
        putU16(central, 10, 0)
        putU32(central, 16, crc)
        putU32(central, 20, entry.bytes.size.toLong())
        putU32(central, 24, entry.bytes.size.toLong())
        putU16(central, 28, name.size)
        putU16(central, 30, extra.size)
        putU32(central, 42, localOffset)
        name.copyInto(central, 46)
        extra.copyInto(central, 46 + name.size)
        return EncodedEntry(local, central)
    }

    private fun parseDirectory(file: File): Directory = RandomAccessFile(file, "r").use { archive ->
        val length = archive.length()
        if (length < 22) throw IOException("APK is too small")
        val searchSize = minOf(length, MAX_EOCD_SEARCH.toLong()).toInt()
        val tail = ByteArray(searchSize)
        archive.seek(length - searchSize)
        archive.readFully(tail)
        val eocdOffsetInTail = (tail.size - 22 downTo 0).firstOrNull { u32(tail, it) == EOCD_SIGNATURE.toLong() }
            ?: throw IOException("APK end-of-central-directory is missing")
        val commentLength = u16(tail, eocdOffsetInTail + 20)
        if (eocdOffsetInTail + 22 + commentLength != tail.size) throw IOException("APK EOCD bounds are invalid")
        if (u16(tail, eocdOffsetInTail + 4) != 0 || u16(tail, eocdOffsetInTail + 6) != 0) throw IOException("Multi-disk APKs are unsupported")
        val entryCount = u16(tail, eocdOffsetInTail + 10)
        if (entryCount != u16(tail, eocdOffsetInTail + 8) || entryCount == 0xffff) throw IOException("ZIP64 APKs are unsupported")
        val centralSize = u32(tail, eocdOffsetInTail + 12)
        val centralOffset = u32(tail, eocdOffsetInTail + 16)
        val eocdOffset = length - searchSize + eocdOffsetInTail
        if (centralSize == 0xffffffffL || centralOffset == 0xffffffffL ||
            centralOffset > eocdOffset || centralSize > eocdOffset - centralOffset
        ) {
            throw IOException("APK central directory is invalid")
        }
        if (centralOffset + centralSize != eocdOffset) throw IOException("APK central directory bounds are invalid")
        if (eocdOffset >= 20) {
            val locator = ByteArray(4)
            archive.seek(eocdOffset - 20)
            archive.readFully(locator)
            if (u32(locator, 0) == 0x07064b50L) throw IOException("ZIP64 APKs are not supported")
        }
        if (centralSize > Int.MAX_VALUE) throw IOException("APK central directory is too large")
        val central = ByteArray(centralSize.toInt())
        archive.seek(centralOffset)
        archive.readFully(central)
        val centralRecords = ArrayList<CentralRecord>(entryCount)
        var cursor = 0
        repeat(entryCount) {
            if (cursor + 46 > central.size || u32(central, cursor) != CENTRAL_SIGNATURE.toLong()) throw IOException("APK central entry is invalid")
            val flags = u16(central, cursor + 8)
            if (flags and 0x0041 != 0) throw IOException("Encrypted APK entries are unsupported")
            val method = u16(central, cursor + 10)
            val crc = u32(central, cursor + 16)
            val compressedSize = u32(central, cursor + 20)
            val uncompressedSize = u32(central, cursor + 24)
            if (compressedSize == 0xffffffffL || uncompressedSize == 0xffffffffL) {
                throw IOException("ZIP64 APKs are not supported")
            }
            val nameLength = u16(central, cursor + 28)
            val extraLength = u16(central, cursor + 30)
            val commentLengthEntry = u16(central, cursor + 32)
            val total = 46 + nameLength + extraLength + commentLengthEntry
            if (cursor + total > central.size) throw IOException("APK central entry is truncated")
            val nameBytes = central.copyOfRange(cursor + 46, cursor + 46 + nameLength)
            if (nameBytes.isEmpty()) throw IOException("APK entry name is invalid")
            val charset = if (flags and UTF8_FLAG != 0) StandardCharsets.UTF_8 else Charset.forName("CP437")
            val name = String(nameBytes, charset)
            validateExtraFields(central, cursor + 46 + nameLength, extraLength)
            if (u16(central, cursor + 34) != 0) throw IOException("Multi-disk APKs are unsupported")
            val localOffset = u32(central, cursor + 42)
            if (localOffset == 0xffffffffL) throw IOException("ZIP64 APKs are not supported")
            if (localOffset >= centralOffset) throw IOException("APK local entry offset is invalid")
            centralRecords += CentralRecord(
                name = name,
                nameBytes = nameBytes,
                raw = central.copyOfRange(cursor, cursor + total),
                localOffset = localOffset,
                flags = flags,
                method = method,
                crc = crc,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
            )
            cursor += total
        }
        if (cursor != central.size) throw IOException("APK central directory contains trailing data")
        val localDataEnd = signingBlockStart(archive, centralOffset) ?: centralOffset
        val entries = centralRecords.map { record ->
            CentralEntry(
                name = record.name,
                raw = record.raw,
                localOffset = record.localOffset,
                local = parseLocalRecord(archive, record, localDataEnd),
            )
        }
        var previousEnd = 0L
        for (entry in entries.sortedBy { it.localOffset }) {
            if (entry.local.offset < previousEnd) throw IOException("APK local entries overlap")
            previousEnd = entry.local.endOffset
        }
        Directory(entries)
    }

    private fun parseLocalRecord(archive: RandomAccessFile, central: CentralRecord, localDataEnd: Long): LocalRecord {
        val localOffset = central.localOffset
        if (localOffset > localDataEnd || 30L > localDataEnd - localOffset) throw IOException("APK local entry is truncated")
        val header = ByteArray(30)
        archive.seek(localOffset)
        archive.readFully(header)
        if (u32(header, 0) != LOCAL_SIGNATURE.toLong()) throw IOException("APK local entry is invalid")
        val flags = u16(header, 6)
        val method = u16(header, 8)
        if (flags != central.flags || method != central.method) throw IOException("APK local and central metadata disagree")
        if (flags and 0x0041 != 0) throw IOException("Encrypted APK entries are unsupported")
        val localCrc = u32(header, 14)
        val localCompressedSize = u32(header, 18)
        val localUncompressedSize = u32(header, 22)
        if (localCompressedSize == 0xffffffffL || localUncompressedSize == 0xffffffffL) {
            throw IOException("ZIP64 APKs are not supported")
        }
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        val variableSize = nameLength.toLong() + extraLength
        if (variableSize > localDataEnd - localOffset - 30L) throw IOException("APK local entry header is truncated")
        val variable = ByteArray(variableSize.toInt())
        archive.readFully(variable)
        if (!variable.copyOfRange(0, nameLength).contentEquals(central.nameBytes)) {
            throw IOException("APK local and central names disagree")
        }
        validateExtraFields(variable, nameLength, extraLength)
        val dataOffset = localOffset + 30L + variableSize
        if (central.compressedSize > localDataEnd - dataOffset) throw IOException("APK local entry payload is truncated")
        val payloadEnd = dataOffset + central.compressedSize
        val hasDescriptor = flags and DATA_DESCRIPTOR_FLAG != 0
        val endOffset = if (hasDescriptor) {
            validateDescriptor(archive, payloadEnd, localDataEnd, central)
        } else {
            if (localCrc != central.crc ||
                localCompressedSize != central.compressedSize ||
                localUncompressedSize != central.uncompressedSize
            ) {
                throw IOException("APK local and central sizes disagree")
            }
            payloadEnd
        }
        if (hasDescriptor &&
            ((localCrc != 0L && localCrc != central.crc) ||
                (localCompressedSize != 0L && localCompressedSize != central.compressedSize) ||
                (localUncompressedSize != 0L && localUncompressedSize != central.uncompressedSize))
        ) {
            throw IOException("APK local and central sizes disagree")
        }
        if (method == 0 && central.compressedSize != central.uncompressedSize) {
            throw IOException("Stored APK entry sizes disagree")
        }
        return LocalRecord(
            offset = localOffset,
            dataOffset = dataOffset,
            endOffset = endOffset,
            alignment = retainedAlignment(method, dataOffset),
            extraLength = extraLength,
        )
    }

    private fun validateDescriptor(
        archive: RandomAccessFile,
        offset: Long,
        localDataEnd: Long,
        central: CentralRecord,
    ): Long {
        if (offset > localDataEnd || 12L > localDataEnd - offset) throw IOException("APK data descriptor is truncated")
        val first = ByteArray(4)
        archive.seek(offset)
        archive.readFully(first)
        val signed = u32(first, 0) == DATA_DESCRIPTOR_SIGNATURE.toLong()
        val size = if (signed) 16 else 12
        if (size.toLong() > localDataEnd - offset) throw IOException("APK data descriptor is truncated")
        val descriptor = ByteArray(size)
        archive.seek(offset)
        archive.readFully(descriptor)
        val valuesOffset = if (signed) 4 else 0
        if (u32(descriptor, valuesOffset) != central.crc ||
            u32(descriptor, valuesOffset + 4) != central.compressedSize ||
            u32(descriptor, valuesOffset + 8) != central.uncompressedSize
        ) {
            throw IOException("APK data descriptor disagrees with central metadata")
        }
        return offset + size
    }

    private fun validateExtraFields(bytes: ByteArray, offset: Int, length: Int) {
        var cursor = offset
        val end = offset + length
        while (end - cursor >= 4) {
            val id = u16(bytes, cursor)
            val size = u16(bytes, cursor + 2)
            if (size > end - cursor - 4) {
                if (bytes.allZero(cursor, end)) return
                throw IOException("APK extra field is truncated")
            }
            if (id == ZIP64_EXTRA_ID) throw IOException("ZIP64 APKs are not supported")
            cursor += 4 + size
        }
        if (!bytes.allZero(cursor, end)) throw IOException("APK extra field is truncated")
    }

    private fun ByteArray.allZero(start: Int, end: Int): Boolean =
        (start until end).all { index -> this[index] == 0.toByte() }

    private fun retainedAlignment(method: Int, dataOffset: Long): Int {
        if (method != 0) return 1
        var alignment = 1
        while (alignment < MAX_RETAINED_ALIGNMENT && dataOffset % (alignment * 2L) == 0L) alignment *= 2
        return alignment
    }

    private fun alignmentPadding(dataOffsetWithoutPadding: Long, alignment: Int): Int =
        ((alignment - (dataOffsetWithoutPadding % alignment).toInt()) % alignment)

    private fun paddingAtOffsetZero(entry: CentralEntry): Int =
        alignmentPadding(entry.local.dataOffset - entry.local.offset, entry.local.alignment)

    private fun retainedAnchorExtraSize(entry: CentralEntry): Int {
        val headerSize = entry.local.dataOffset - entry.local.offset
        return 4 + alignmentPadding(headerSize + 4L, entry.local.alignment)
    }

    private fun canReframeAtOffsetZero(entry: CentralEntry): Boolean =
        entry.local.extraLength <= 0xffff - retainedAnchorExtraSize(entry)

    private fun writeReframedAnchor(
        source: RandomAccessFile,
        sink: BufferedOutputStream,
        entry: CentralEntry,
    ): Long {
        val headerSize = entry.local.dataOffset - entry.local.offset
        if (headerSize > Int.MAX_VALUE) throw IOException("APK local entry header is too large")
        val header = ByteArray(headerSize.toInt())
        source.seek(entry.local.offset)
        source.readFully(header)

        val alignmentExtra = ByteArray(retainedAnchorExtraSize(entry))
        putU16(alignmentExtra, 0, 0xffff)
        putU16(alignmentExtra, 2, alignmentExtra.size - 4)
        val newExtraLength = entry.local.extraLength + alignmentExtra.size
        if (newExtraLength > 0xffff) throw IOException("APK retained anchor extra field is too large")
        putU16(header, 28, newExtraLength)

        sink.write(header)
        sink.write(alignmentExtra)
        val payloadAndDescriptorSize = entry.local.endOffset - entry.local.dataOffset
        copyExactly(source, sink, entry.local.dataOffset, payloadAndDescriptorSize)
        return headerSize + alignmentExtra.size + payloadAndDescriptorSize
    }

    private fun signingBlockStart(archive: RandomAccessFile, centralOffset: Long): Long? {
        if (centralOffset < 24) return null
        val footer = ByteArray(24)
        archive.seek(centralOffset - footer.size)
        archive.readFully(footer)
        val magic = String(footer, 8, 16, StandardCharsets.US_ASCII)
        if (magic != APK_SIGNING_MAGIC) return null
        val size = littleEndian(footer).getLong(0)
        if (size < 24 || size > centralOffset - 8) throw IOException("APK signing block size is invalid")
        val start = centralOffset - size - 8
        val header = ByteArray(8)
        archive.seek(start)
        archive.readFully(header)
        if (littleEndian(header).getLong(0) != size) throw IOException("APK signing block bounds disagree")
        return start
    }

    private fun eocd(entries: Int, centralSize: Long, centralOffset: Long): ByteArray = ByteArray(22).also {
        putU32(it, 0, EOCD_SIGNATURE.toLong())
        putU16(it, 8, entries)
        putU16(it, 10, entries)
        putU32(it, 12, centralSize)
        putU32(it, 16, centralOffset)
    }

    private fun isJarSignature(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        return upper == "META-INF/MANIFEST.MF" || (upper.startsWith("META-INF/") && (
            upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")
        ))
    }

    private fun copyExactly(source: RandomAccessFile, sink: BufferedOutputStream, offset: Long, bytes: Long) {
        source.seek(offset)
        var remaining = bytes
        val buffer = ByteArray(256 * 1024)
        while (remaining > 0) {
            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("APK ended before its local data boundary")
            sink.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun writeZeros(sink: BufferedOutputStream, count: Int) {
        if (count > 0) sink.write(ByteArray(count))
    }

    private fun littleEndian(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun u16(bytes: ByteArray, offset: Int): Int = littleEndian(bytes).getShort(offset).toInt() and 0xffff
    private fun u32(bytes: ByteArray, offset: Int): Long = littleEndian(bytes).getInt(offset).toLong() and 0xffffffffL
    private fun putU16(bytes: ByteArray, offset: Int, value: Int) { littleEndian(bytes).putShort(offset, value.toShort()) }
    private fun putU32(bytes: ByteArray, offset: Int, value: Long) { littleEndian(bytes).putInt(offset, value.toInt()) }
}
