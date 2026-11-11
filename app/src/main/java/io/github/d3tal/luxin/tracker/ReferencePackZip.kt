package io.github.d3tal.luxin.tracker

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** No Android APIs: archive policy and failed-import cleanup can be tested on a plain JVM. */
internal object ReferencePackZip {
    // Also bound the SAF copy itself, including ZIP metadata/compression overhead.
    private const val MAX_ARCHIVE_BYTES = 72L * 1024 * 1024
    private val versionName = Regex("version[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun safeDirectoryName(name: String): Boolean = versionName.matches(name)

    /** Publishes a fresh directory, never modifies an existing pack or its active pointer. */
    fun install(
        root: File,
        input: InputStream,
        dimensions: (File) -> Pair<Int, Int>
    ): Pair<File, ReferencePackManifest> {
        Files.createDirectories(root.toPath())
        val temporary = Files.createTempDirectory(root.toPath(), ".import-").toFile()
        try {
            val archive = File(temporary, "source.zip")
            archive.outputStream().use { copyBounded(input, it, MAX_ARCHIVE_BYTES) }
            val payload = File(temporary, "payload")
            check(payload.mkdir()) { "Cannot create pack staging directory" }
            extract(archive, payload)
            val manifest = validateInstalled(payload, dimensions)
            val destination = File(root, "version${UUID.randomUUID()}")
            Files.move(payload.toPath(), destination.toPath())
            return destination to manifest
        } finally {
            // The only recursively deleted directory is the one this invocation created.
            temporary.deleteRecursively()
        }
    }

    fun validateInstalled(directory: File, dimensions: (File) -> Pair<Int, Int>): ReferencePackManifest {
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) { "Not a pack directory" }
        val files = requireNotNull(directory.listFiles()) { "Cannot list pack" }
        require(files.size in 1..ReferencePackRules.MAX_ENTRIES) { "Too many pack files" }
        var bytes = 0L
        for (file in files) {
            require(ReferencePackRules.safeFilename(file.name) &&
                Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Invalid pack file" }
            bytes += file.length()
            require(bytes <= ReferencePackRules.MAX_BYTES) { "Pack byte limit exceeded" }
        }
        val manifestFile = File(directory, ReferencePackRules.MANIFEST)
        val manifestBytes = ByteArrayOutputStream()
        manifestFile.inputStream().use {
            copyBounded(it, manifestBytes, ReferencePackRules.MAX_MANIFEST_BYTES.toLong())
        }
        val manifest = ReferencePackRules.decode(manifestBytes.toByteArray())
        require(files.map { it.name }.toSet() == ReferencePackRules.filenames(manifest)) {
            "Missing or unreferenced pack files"
        }
        ReferencePackRules.validateDimensions(manifest) { dimensions(File(directory, it)) }
        return manifest
    }

    private data class EntryInfo(val name: String, val size: Long, val method: Int)

    private fun extract(archive: File, directory: File) {
        // java.util.zip does not expose Unix file type bits. Inspect the central directory
        // before extracting anything so symlinks/special files cannot masquerade as PNGs.
        val central = inspectCentralDirectory(archive)
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.map { EntryInfo(it.name, it.size, it.method) } == central) { "Inconsistent ZIP directory" }
            val byName = entries.associateBy { it.name }
            val manifestEntry = requireNotNull(byName[ReferencePackRules.MANIFEST]) { "Missing manifest.json" }
            var actualBytes = 0L
            fun read(entry: ZipEntry, output: OutputStream, limit: Long) {
                val crc = CRC32()
                val count = zip.getInputStream(entry).use { input ->
                    copyBounded(input, output, minOf(limit, ReferencePackRules.MAX_BYTES - actualBytes), crc)
                }
                actualBytes += count
                require(count == entry.size && crc.value == entry.crc) { "Invalid ZIP size or CRC" }
            }
            val manifestOutput = ByteArrayOutputStream()
            read(manifestEntry, manifestOutput, ReferencePackRules.MAX_MANIFEST_BYTES.toLong())
            val manifest = ReferencePackRules.decode(manifestOutput.toByteArray())
            require(byName.keys == ReferencePackRules.filenames(manifest)) { "Missing or unreferenced ZIP entries" }
            File(directory, ReferencePackRules.MANIFEST).writeBytes(ReferencePackRules.encode(manifest))
            for (entry in entries) {
                if (entry.name == ReferencePackRules.MANIFEST) continue
                File(directory, entry.name).outputStream().use { read(entry, it, ReferencePackRules.MAX_BYTES) }
            }
        }
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        crc: CRC32? = null
    ): Long {
        require(limit >= 0) { "Byte limit exceeded" }
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            // Read at most one byte beyond the budget; never write that excess byte.
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - count + 1).toInt())
            if (read < 0) return count
            if (read == 0) continue
            count += read
            require(count <= limit) { "Decompressed/archive byte limit exceeded" }
            crc?.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private fun inspectCentralDirectory(archive: File): List<EntryInfo> = RandomAccessFile(archive, "r").use { file ->
        require(file.length() in 22..MAX_ARCHIVE_BYTES) { "Invalid ZIP length" }
        val tailSize = minOf(file.length(), 65557L).toInt()
        val tail = ByteArray(tailSize)
        file.seek(file.length() - tailSize)
        file.readFully(tail)
        val end = (tail.size - 22 downTo 0).firstOrNull {
            u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size
        } ?: throw IllegalArgumentException("Missing ZIP end record")
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "Multi-disk ZIP is unsupported" }
        val count = u16(tail, end + 10)
        require(count in 1..ReferencePackRules.MAX_ENTRIES && u16(tail, end + 8) == count) {
            "ZIP entry limit exceeded"
        }
        val centralSize = u32(tail, end + 12)
        val centralOffset = u32(tail, end + 16)
        val endOffset = file.length() - tailSize + end
        // Deliberately reject ZIP64 and split archives; neither is needed for these limits.
        require(centralOffset + centralSize == endOffset) { "Invalid/ZIP64 central directory" }
        file.seek(centralOffset)
        val names = mutableSetOf<String>()
        val result = mutableListOf<EntryInfo>()
        var total = 0L
        repeat(count) {
            require(file.filePointer + 46 <= endOffset) { "Truncated ZIP directory" }
            val header = ByteArray(46).also(file::readFully)
            require(u32(header, 0) == 0x02014b50L) { "Invalid ZIP directory record" }
            require(u16(header, 8) and 0x41 == 0) { "Encrypted ZIP is unsupported" }
            val method = u16(header, 10)
            require(method == ZipEntry.STORED || method == ZipEntry.DEFLATED) { "Unsupported ZIP method" }
            require(u16(header, 34) == 0) { "Multi-disk entry" }
            val attributes = u32(header, 38)
            val type = ((attributes ushr 16) and 0xf000).toInt()
            require((type == 0 || type == 0x8000) && attributes and 0x10L == 0L) {
                "ZIP links/directories/special files are forbidden"
            }
            val size = u32(header, 24)
            total += size
            require(total <= ReferencePackRules.MAX_BYTES) { "Declared ZIP byte limit exceeded" }
            val nameLength = u16(header, 28)
            val extraLength = u16(header, 30)
            val commentLength = u16(header, 32)
            require(nameLength in 1..128 && file.filePointer + nameLength + extraLength + commentLength <= endOffset) {
                "Invalid ZIP entry lengths"
            }
            val nameBytes = ByteArray(nameLength).also(file::readFully)
            require(nameBytes.all { it.toInt() in 0..127 }) { "Non-ASCII ZIP filename" }
            val name = nameBytes.toString(Charsets.US_ASCII)
            require(ReferencePackRules.safeFilename(name) && names.add(name)) { "Unsafe or duplicate ZIP filename" }
            if (name == ReferencePackRules.MANIFEST) {
                require(size <= ReferencePackRules.MAX_MANIFEST_BYTES) { "Manifest too large" }
            }
            val extra = ByteArray(extraLength).also(file::readFully)
            var offset = 0
            while (offset < extra.size) {
                require(offset + 4 <= extra.size) { "Invalid ZIP extra field" }
                val tag = u16(extra, offset)
                val length = u16(extra, offset + 2)
                // ZIP64 and legacy Unix fields that can encode link targets are not accepted.
                require(tag != 0x0001 && tag != 0x000d && tag != 0x756e) { "Unsupported ZIP extra field" }
                offset += 4 + length
                require(offset <= extra.size) { "Invalid ZIP extra field length" }
            }
            file.seek(file.filePointer + commentLength)
            result += EntryInfo(name, size, method)
        }
        require(file.filePointer == endOffset) { "Unexpected ZIP directory data" }
        result
    }
}
