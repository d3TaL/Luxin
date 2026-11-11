package io.github.d3tal.luxin.tracker

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.coroutines.coroutineContext

data class InstalledReferencePack(val directory: File, val manifest: ReferencePackManifest)

object ReferencePackStore {
    private const val PREFERENCES = "tracker_reference_packs"
    private const val ACTIVE_DIRECTORY = "active_directory"
    private val lock = Any()
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    /** Reads the SAF URI immediately; no persistent URI grant or network access is needed. */
    suspend fun importPack(context: Context, uri: Uri): InstalledReferencePack = withContext(Dispatchers.IO) {
        val jobContext = coroutineContext
        synchronized(lock) {
            jobContext.ensureActive()
            val root = File(context.filesDir, "tracker_packs")
            require(!Files.exists(root.toPath(), NOFOLLOW_LINKS) ||
                Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) { "Invalid tracker pack root" }
            val (directory, manifest) = (context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open reference pack URI")).use { input ->
                ReferencePackZip.install(root, input, ::pngDimensions)
            }
            // Published packs are immutable from here on, even if activation fails or is cancelled.
            // Never delete a prior installation: a native tracker may still be using it.
            jobContext.ensureActive()
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val previous = preferences.getString(ACTIVE_DIRECTORY, null)
            if (!preferences.edit().putString(ACTIVE_DIRECTORY, directory.name).commit()) {
                // commit() may update the in-memory map even when the disk write fails.
                val restore = preferences.edit()
                if (previous == null) restore.remove(ACTIVE_DIRECTORY) else restore.putString(ACTIVE_DIRECTORY, previous)
                restore.commit()
                throw IOException("Cannot activate reference pack")
            }
            InstalledReferencePack(directory, manifest)
        }
    }

    /** Returns null for absent, unsafe, incomplete or corrupt installations; never repairs/deletes them. */
    fun loadInstalled(context: Context): InstalledReferencePack? = synchronized(lock) {
        try {
            val name = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(ACTIVE_DIRECTORY, null) ?: return@synchronized null
            if (!ReferencePackZip.safeDirectoryName(name)) return@synchronized null
            val root = File(context.filesDir, "tracker_packs")
            if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) return@synchronized null
            val directory = File(root, name)
            val manifest = ReferencePackZip.validateInstalled(directory, ::pngDimensions)
            InstalledReferencePack(directory, manifest)
        } catch (_: Exception) {
            null
        }
    }

    private fun pngDimensions(file: File): Pair<Int, Int> {
        val signature = ByteArray(pngSignature.size)
        DataInputStream(file.inputStream()).use { it.readFully(signature) }
        require(signature.contentEquals(pngSignature)) { "Expected PNG image/mask" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outMimeType == "image/png") { "Invalid PNG image/mask" }
        // Width/height and aggregate pixel budgets are checked by the pure shared rules.
        return options.outWidth to options.outHeight
    }
}
