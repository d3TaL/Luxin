package io.github.d3tal.luxin.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LogcatSnapshotTest {
    private val record = "123.456 1234 1240 I LuxinTrackerJNI: BigMap stage=return phase=moving tolerated=1\n"

    private open class FakeProcess(text: String, private val code: Int = 0) : Process() {
        private val input = ByteArrayInputStream(text.toByteArray())
        var destroyed = false
        var outputClosed = false
        private val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        override fun getOutputStream() = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = code
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = code
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test
    fun usesScopedDumpCommandAndCleansProcessWithoutBufferClearing() {
        var command = emptyList<String>()
        val process = FakeProcess(record)
        val snapshot = LogcatSnapshot(launch = { command = it; process })
        val output = StringWriter()
        assertEquals(1, snapshot.write(1234, output).records)
        assertTrue(command.containsAll(listOf("-d", "--pid=1234", "LuxinTrackerJNI:I", "LuxinCapture:D", "*:S")))
        assertTrue(command.contains("monotonic"))
        assertFalse(command.contains("-c") || command.contains("su") || command.contains("adb"))
        assertTrue(process.destroyed && process.outputClosed)
    }

    @Test
    fun emptyBufferOrPermissionErrorsDoNotBecomeSuccessfulEmptyExports() {
        for (process in listOf(FakeProcess(""), FakeProcess("Permission denied: /private/secret", 1))) {
            try {
                LogcatSnapshot(launch = { process }).write(1234, StringWriter())
                fail("Expected unavailable diagnostics")
            } catch (e: IOException) {
                assertFalse(e.message.orEmpty().contains("secret"))
            }
            assertTrue(process.destroyed)
        }
    }

    @Test
    fun failedExitAfterValidRowsStillFailsTheExport() {
        val process = FakeProcess(record, 1)
        try {
            LogcatSnapshot(launch = { process }).write(1234, StringWriter())
            fail("Expected logcat failure")
        } catch (_: IOException) {
            assertTrue(process.destroyed)
        }
    }

    @Test
    fun writerFailureAlwaysStopsLogcat() {
        val process = FakeProcess(record)
        val output = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, count: Int) { throw IOException("write failure") }
            override fun flush() = Unit
            override fun close() = Unit
        }
        try {
            LogcatSnapshot(launch = { process }).write(1234, output)
            fail("Expected writer failure")
        } catch (_: IOException) {
            assertTrue(process.destroyed)
        }
    }

    @Test
    fun interruptedExportStopsLogcatAndDoesNotReportSuccess() {
        val process = FakeProcess(record)
        Thread.currentThread().interrupt()
        try {
            LogcatSnapshot(launch = { process }).write(1234, StringWriter())
            fail("Expected interrupted export")
        } catch (_: InterruptedException) {
            assertTrue(process.destroyed)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun timeoutKillsBlockedLogcatWithoutSleep() {
        val destroyed = CountDownLatch(1)
        val process = object : FakeProcess("") {
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int {
                    check(destroyed.await(5, TimeUnit.SECONDS))
                    return -1
                }
            }
            override fun destroy() { super.destroy(); destroyed.countDown() }
        }
        try {
            LogcatSnapshot(launch = { process }, timeoutMs = 25).write(1234, StringWriter())
            fail("Expected timeout")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("timed out"))
            assertTrue(process.destroyed)
        }
    }
}
