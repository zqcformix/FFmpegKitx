package io.github.nova.ffmpegkit.editor

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import io.github.nova.ffmpegkit.MediaOutput
import io.github.nova.ffmpegkit.SplitResult
import io.github.nova.ffmpegkit.TimeRange
import io.github.nova.ffmpegkit.TrimResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A video this app put in the gallery. */
class SavedVideo(val uri: Uri, val displayName: String, val sizeBytes: Long, val actualRange: TimeRange)

/**
 * Saves results under `Movies/FFmpegKit`: through MediaStore on Android 10+, where an item stays
 * pending (hidden) until the whole run succeeded and is deleted otherwise, and as public files
 * picked up by the media scanner before that (needs [Manifest.permission.WRITE_EXTERNAL_STORAGE]).
 */
class Gallery(context: Context) {
    private val context = context.applicationContext
    private val resolver = context.contentResolver

    val needsWritePermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Runs [trim] with an output in the gallery named [displayName]. */
    suspend fun saveTrim(
        displayName: String,
        mimeType: String,
        extension: String,
        trim: suspend (MediaOutput) -> TrimResult,
    ): SavedVideo = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = insertPending(displayName, mimeType)
            try {
                // A Uri output: the library writes a cache temp file and copies it in when complete.
                val result = trim(MediaOutput.of(resolver, uri, extension))
                publish(uri)
                SavedVideo(uri, displayName, querySize(uri), result.actualRange)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val file = File(legacyDirectory(), displayName)
            val result = trim(MediaOutput.of(file))
            SavedVideo(scan(file, mimeType), displayName, file.length(), result.actualRange)
        }
    }

    /** Runs [split] with an output directory, then puts every segment in the gallery, or none of them. */
    suspend fun saveSplit(mimeType: String, split: suspend (File) -> SplitResult): List<SavedVideo> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Split writes files, so segments go to the cache first and are copied into MediaStore.
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "split-").toFile()
            val inserted = mutableListOf<Uri>()
            try {
                val saved = split(directory).segments.map { segment ->
                    ensureActive()
                    val uri = insertPending(segment.file.name, mimeType).also(inserted::add)
                    copy(segment.file, uri)
                    SavedVideo(uri, segment.file.name, segment.file.length(), segment.actualRange)
                }
                saved.forEach { publish(it.uri) }
                saved
            } catch (e: Throwable) {
                inserted.forEach { resolver.delete(it, null, null) }
                throw e
            } finally {
                directory.deleteRecursively()
            }
        } else {
            split(legacyDirectory()).segments.map {
                SavedVideo(scan(it.file, mimeType), it.file.name, it.file.length(), it.actualRange)
            }
        }
    }

    private fun insertPending(displayName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.insert(collection, values) ?: throw IOException("MediaStore did not create $displayName")
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    private fun copy(file: File, uri: Uri) {
        val output = resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException("cannot write $uri")
        output.use { target -> file.inputStream().use { it.copyTo(target) } }
    }

    private fun querySize(uri: Uri): Long =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1
        } ?: -1

    @Suppress("DEPRECATION")
    private fun legacyDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM).apply { mkdirs() }

    private suspend fun scan(file: File, mimeType: String): Uri = suspendCancellableCoroutine { continuation ->
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mimeType)) { _, uri ->
            if (uri != null) continuation.resume(uri) else continuation.resumeWithException(IOException("media scanner rejected $file"))
        }
    }

    private companion object {
        const val ALBUM = "FFmpegKit"
    }
}
