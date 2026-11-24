package io.github.d3tal.luxin.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal data class DiagnosticLogDestination(val uri: Uri, val label: String)
internal data class ExportedDiagnosticLog(val filename: String, val records: Int, val limited: Boolean)

internal object DiagnosticLogExporter {
    private const val PREFERENCES = "diagnostic_log_export"
    private const val DIRECTORY = "directory_uri"
    private val exporting = Mutex()
    private val _destination = MutableStateFlow<DiagnosticLogDestination?>(null)
    val destination: StateFlow<DiagnosticLogDestination?> = _destination.asStateFlow()

    suspend fun loadDestination(context: Context): DiagnosticLogDestination? = withContext(Dispatchers.IO) {
        val text = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(DIRECTORY, null)
        if (text == null) {
            _destination.value = null
            return@withContext null
        }
        val uri = Uri.parse(text)
        if (!DocumentsContract.isTreeUri(uri) || context.contentResolver.persistedUriPermissions.none {
                it.uri == uri && it.isWritePermission
            }) {
            _destination.value = null
            return@withContext null
        }
        destination(context, uri).also { _destination.value = it }
    }

    suspend fun saveDestination(context: Context, uri: Uri): DiagnosticLogDestination = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val selected = destination(context, uri)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(DIRECTORY, uri.toString()).apply()
        _destination.value = selected
        selected
    }

    private fun destination(context: Context, uri: Uri): DiagnosticLogDestination {
        val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val name = context.contentResolver.query(document,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        return DiagnosticLogDestination(uri, name?.takeIf { it.isNotBlank() } ?: "Выбранная папка")
    }

    suspend fun export(context: Context, target: DiagnosticLogDestination): ExportedDiagnosticLog {
        val parent = DocumentsContract.buildDocumentUriUsingTree(target.uri,
            DocumentsContract.getTreeDocumentId(target.uri))
        return exportToDirectory(context, parent)
    }

    suspend fun exportToDirectory(context: Context, parentDocumentUri: Uri): ExportedDiagnosticLog =
        withContext(Dispatchers.IO) {
            check(exporting.tryLock()) { "Log export is already running" }
            var temporary: File? = null
            var created: Uri? = null
            var complete = false
            try {
                val logFile = File.createTempFile("tracking-diagnostics-", ".log", context.cacheDir)
                temporary = logFile
                val processId = Process.myPid()
                val now = Date()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(now)
                val filename = "luxin-tracking-$stamp-${UUID.randomUUID().toString().take(8)}.log"
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val version = packageInfo.versionName.orEmpty().replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(100)
                val summary = runInterruptible {
                    logFile.bufferedWriter(Charsets.UTF_8).use { output ->
                        output.write("# Luxin tracking diagnostics v1\n")
                        output.write("# appVersion=$version versionCode=${packageInfo.longVersionCode} androidSdk=${Build.VERSION.SDK_INT}\n")
                        output.write("# exportedAtUtc=$stamp processId=$processId clock=logcat_monotonic\n")
                        output.write("# Recent Android main-buffer snapshot of this process; not a complete session recording.\n")
                        output.write("# Only allowlisted scalar tracking fields; no screenshots, paths, player/world coordinates or general system log.\n")
                        LogcatSnapshot().write(processId, output).also {
                            output.write("# exportedRecords=${it.records} limited=${it.limited}\n")
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val document = DocumentsContract.createDocument(context.contentResolver, parentDocumentUri, "text/plain", filename)
                    ?: throw IOException("Cannot create diagnostic log")
                created = document
                context.contentResolver.openOutputStream(document, "w")?.use { output ->
                    logFile.inputStream().use { input ->
                        val bytes = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            output.write(bytes, 0, count)
                        }
                    }
                    output.flush()
                } ?: throw IOException("Cannot open diagnostic log")
                currentCoroutineContext().ensureActive()
                complete = true
                ExportedDiagnosticLog(filename, summary.records, summary.limited)
            } finally {
                // Only files created by this invocation are removed; existing exports and app data stay intact.
                val partialDocument = created
                if (!complete && partialDocument != null) withContext(NonCancellable) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, partialDocument) }
                }
                runCatching { temporary?.delete() }
                exporting.unlock()
            }
        }
}
