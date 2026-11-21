package io.github.d3tal.luxin.diagnostics

import java.io.IOException
import java.io.Writer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded dump of this process only. No root, READ_LOGS, buffer clearing or persistent recorder. */
internal class LogcatSnapshot(
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    private val timeoutMs: Long = 10_000
) {
    fun write(processId: Int, output: Writer): TrackingLogSummary {
        require(processId > 0 && timeoutMs > 0)
        val process = launch(listOf("logcat", "-b", "main", "-d", "-t", TrackingLogFilter.MAX_RECORDS.toString(),
            "-v", "threadtime", "-v", "monotonic", "-v", "usec", "--pid=$processId",
            "LuxinTrackerJNI:I", "LuxinCapture:D", "*:S"))
        val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LuxinLogTimeout").apply { isDaemon = true }
        }
        val timedOut = AtomicBoolean(false)
        val deadline = timer.schedule({
            timedOut.set(true)
            process.destroyForcibly()
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val summary = process.inputStream.bufferedReader(Charsets.UTF_8).use {
                TrackingLogFilter.copy(it, output, processId)
            }
            if (timedOut.get()) throw IOException("Diagnostic log read timed out")
            if (!summary.limited) {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) || timedOut.get()) {
                    throw IOException("Diagnostic log read timed out")
                }
                if (process.exitValue() != 0) throw IOException("Diagnostic log is unavailable")
            }
            if (summary.records == 0) throw IOException("No tracking diagnostics in the log buffer")
            return summary
        } finally {
            deadline.cancel(false)
            timer.shutdownNow()
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
