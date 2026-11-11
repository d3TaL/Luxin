package io.github.d3tal.luxin.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ReferencePackZipTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): Map<String, ByteArray> = linkedMapOf(
        "manifest.json" to ReferencePackRules.encode(referenceManifest()),
        "image.png" to byteArrayOf(1, 2, 3),
        "active.png" to byteArrayOf(4, 5, 6),
        "context.png" to byteArrayOf(7, 8, 9)
    )

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun install(root: File, bytes: ByteArray, dimensions: (File) -> Pair<Int, Int> = { 10 to 10 }) =
        bytes.inputStream().use { ReferencePackZip.install(root, it, dimensions) }

    private fun rejects(bytes: ByteArray) {
        val root = temporary.newFolder()
        assertThrows(Exception::class.java) { install(root, bytes) }
        assertTrue("Failed import left staging/published files", root.listFiles()!!.isEmpty())
    }

    private fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, i ->
        value or ((bytes[offset + i].toLong() and 255) shl (i * 8))
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }

    private fun centralHeaders(bytes: ByteArray): List<Int> =
        (0..bytes.size - 46).filter { u32(bytes, it) == 0x02014b50L }

    @Test fun validPackPublishesNewDirectoryAndRetainsPriorInstallations() {
        val root = temporary.newFolder()
        val bytes = zip(fixture())
        val (first, manifest) = install(root, bytes)
        val (second, secondManifest) = install(root, bytes)
        assertTrue(ReferencePackZip.safeDirectoryName(first.name))
        assertTrue(ReferencePackZip.safeDirectoryName(second.name))
        assertFalse(first == second)
        assertEquals(referenceManifest(), manifest)
        assertEquals(manifest, secondManifest)
        assertEquals(setOf(first.name, second.name), root.list()!!.toSet())
        assertEquals(manifest, ReferencePackZip.validateInstalled(first) { 10 to 10 })
        assertTrue(File(first, "image.png").readBytes().contentEquals(fixture().getValue("image.png")))
    }

    @Test fun unsafeStoredDirectoryNamesAreRejected() {
        for (name in listOf("", ".", "..", "../version123", "/version123", "version123", "version" + "a".repeat(300))) {
            assertFalse(ReferencePackZip.safeDirectoryName(name))
        }
    }

    @Test fun traversalDirectoriesAndDuplicateEntriesAreRejected() {
        for (name in listOf("../escape.png", "/escape.png", "sub/image.png", "sub\\image.png", "dir/", "a..png")) {
            rejects(zip(fixture() + (name to byteArrayOf(1))))
        }
        // ZipOutputStream refuses duplicates; mutate equal-length names in both headers.
        val duplicate = zip(fixture() + ("other.png" to byteArrayOf(1)))
        val from = "other.png".toByteArray()
        val to = "image.png".toByteArray()
        for (offset in 0..duplicate.size - from.size) {
            if (from.indices.all { duplicate[offset + it] == from[it] }) to.copyInto(duplicate, offset)
        }
        rejects(duplicate)
    }

    @Test fun centralDirectoryLinksAndSpecialFilesAreRejected() {
        for (mode in listOf(0xa000, 0x4000, 0x1000, 0x6000)) {
            val bytes = zip(fixture())
            val header = centralHeaders(bytes)[1]
            bytes[header + 5] = 3 // Unix host
            put32(bytes, header + 38, mode.toLong() shl 16)
            rejects(bytes)
        }
        val directory = zip(fixture())
        put32(directory, centralHeaders(directory)[1] + 38, 0x10)
        rejects(directory)
    }

    @Test fun entryAndDeclaredByteLimitsAreCheckedBeforePayloadValidation() {
        rejects(zip((0 until 65).associate { "file$it.png" to byteArrayOf(1) }))
        val bytes = zip(fixture())
        put32(bytes, centralHeaders(bytes)[1] + 24, ReferencePackRules.MAX_BYTES + 1)
        rejects(bytes)
        val manifestTooLarge = zip(fixture())
        put32(manifestTooLarge, centralHeaders(manifestTooLarge)[0] + 24, ReferencePackRules.MAX_MANIFEST_BYTES + 1L)
        rejects(manifestTooLarge)
    }

    @Test fun unknownSizesAndDishonestDeclaredSizesCannotBypassStreamBudget() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, data) in fixture()) {
                    zip.putNextEntry(ZipEntry(name)) // data descriptor: local size is unknown
                    if (name == "image.png") {
                        val zeros = ByteArray(8192)
                        repeat((ReferencePackRules.MAX_BYTES / zeros.size).toInt()) { zip.write(zeros) }
                        zip.write(0)
                    } else zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        ZipInputStream(bytes.inputStream()).use { assertEquals(-1L, it.nextEntry.size) }
        // Under-report the expanded image in the central directory. The streaming counter,
        // not the declared size, must stop the decompression bomb.
        put32(bytes, centralHeaders(bytes)[1] + 24, 1)
        rejects(bytes)
    }

    @Test fun missingUnknownAndMalformedManifestFilesAreRejected() {
        rejects(zip(fixture() - "manifest.json"))
        rejects(zip(fixture() - "active.png"))
        rejects(zip(fixture() + ("users.json" to "[]".toByteArray())))
        rejects(zip(fixture() + ("manifest.json" to "{}".toByteArray())))
        rejects(byteArrayOf(1, 2, 3))
        val truncated = zip(fixture())
        rejects(truncated.copyOf(truncated.size - 10))
    }

    @Test fun crcMismatchIsRejected() {
        val bytes = zip(fixture())
        put32(bytes, centralHeaders(bytes)[1] + 16, 0)
        rejects(bytes)
    }

    @Test fun partialReadOrValidationFailureCleansOnlyOwnTemporaryDirectory() {
        val root = temporary.newFolder()
        val (prior, _) = install(root, zip(fixture()))
        val priorManifest = File(prior, "manifest.json").readBytes()
        val partial = object : InputStream() {
            var reads = 0
            override fun read(): Int {
                if (++reads > 10) throw IOException("Provider disconnected")
                return 1
            }
        }
        assertThrows(IOException::class.java) { ReferencePackZip.install(root, partial) { 10 to 10 } }
        assertThrows(IllegalArgumentException::class.java) {
            install(root, zip(fixture())) { if (it.name == "active.png") 11 to 10 else 10 to 10 }
        }
        assertEquals(listOf(prior.name), root.list()!!.toList())
        assertTrue(priorManifest.contentEquals(File(prior, "manifest.json").readBytes()))
    }

    @Test fun loadingRevalidatesManifestFilesAndRejectsLinks() {
        val root = temporary.newFolder()
        val (directory, _) = install(root, zip(fixture()))
        File(directory, "users.json").writeText("[]")
        assertThrows(IllegalArgumentException::class.java) { ReferencePackZip.validateInstalled(directory) { 10 to 10 } }
        File(directory, "users.json").delete()
        val image = File(directory, "image.png")
        image.delete()
        assertThrows(IllegalArgumentException::class.java) { ReferencePackZip.validateInstalled(directory) { 10 to 10 } }
        val target = temporary.newFile().apply { writeBytes(byteArrayOf(1)) }
        Files.createSymbolicLink(image.toPath(), target.toPath())
        assertThrows(IllegalArgumentException::class.java) { ReferencePackZip.validateInstalled(directory) { 10 to 10 } }
    }
}
