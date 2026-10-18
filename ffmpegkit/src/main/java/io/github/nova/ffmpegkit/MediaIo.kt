package io.github.nova.ffmpegkit

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Where an operation reads from: a local file, a `content://` or `file://` Uri, or a
 * [ParcelFileDescriptor]. `content://` Uris are read through their file descriptor, never treated as
 * paths. Inputs must be seekable; pipes and streaming providers fail with [Reason.UNSUPPORTED_MEDIA].
 */
sealed class MediaInput {
    /** Names the input in error messages. */
    internal abstract val description: String

    /** The local file behind this input, if there is one; used to detect output == input. */
    internal open val file: File? get() = null

    internal abstract fun open(): OpenedInput

    override fun toString(): String = description

    internal class FromFile(override val file: File) : MediaInput() {
        override val description: String get() = file.path
        override fun open() = OpenedInput(file.path, -1, null)
    }

    internal class FromUri(val resolver: ContentResolver, val uri: Uri) : MediaInput() {
        override val description: String get() = uri.toString()
        override val file: File? get() = if (uri.scheme == ContentResolver.SCHEME_FILE) uri.path?.let(::File) else null

        override fun open(): OpenedInput {
            file?.let { return OpenedInput(it.path, -1, null) }
            val descriptor = mapIoFailures(MediaRole.INPUT, "open input $uri") {
                try {
                    resolver.openFileDescriptor(uri, "r")
                } catch (e: FileNotFoundException) {
                    // Providers report a missing item this way, usually without an errno to go by.
                    val mapped = ioFailure(e, MediaRole.INPUT, "open input $uri")
                    throw if (mapped.reason == Reason.IO) FFmpegException(Reason.INPUT_NOT_FOUND, mapped.message!!, cause = e) else mapped
                }
            } ?: throw FFmpegException(Reason.INPUT_NOT_FOUND, "open input $uri failed: the provider returned no data")
            return OpenedInput(null, descriptor.fd, descriptor)
        }
    }

    /** Reads [descriptor] from offset 0. The caller keeps ownership and closes it. */
    internal class FromDescriptor(val descriptor: ParcelFileDescriptor) : MediaInput() {
        override val description: String get() = "file descriptor ${descriptor.fd}"
        override fun open() = OpenedInput(null, descriptor.fd, null)
    }

    companion object {
        fun of(file: File): MediaInput = FromFile(file)

        /** Opened with [ContentResolver.openFileDescriptor] in read mode for the duration of the operation. */
        fun of(resolver: ContentResolver, uri: Uri): MediaInput = FromUri(resolver, uri)

        /** Read from offset 0; the caller keeps ownership and closes [descriptor] afterwards. */
        fun of(descriptor: ParcelFileDescriptor): MediaInput = FromDescriptor(descriptor)
    }
}

/** An input ready for native code: a path, or (fd >= 0) a descriptor. Closes what [open] opened. */
internal class OpenedInput(val path: String?, val fd: Int, private val owned: Closeable?) : Closeable {
    override fun close() {
        owned?.close()
    }
}

/**
 * Where a single-file operation writes to: a local file, which is never overwritten, or a Uri the
 * caller created and owns, whose content is replaced once the output is complete.
 */
sealed class MediaOutput {
    internal abstract val description: String

    override fun toString(): String = description

    /** Never overwritten; see [OutputDelivery]. */
    internal class ToFile(val file: File) : MediaOutput() {
        override val description: String get() = file.path
    }

    /**
     * A Uri the caller created and owns (e.g. a MediaStore item or a SAF document). Its content is
     * replaced, but only after the whole output was written to a temporary file; see [UriDelivery].
     */
    internal class ToUri(val resolver: ContentResolver, val uri: Uri, val containerExtension: String) : MediaOutput() {
        override val description: String get() = uri.toString()
    }

    companion object {
        fun of(file: File): MediaOutput = ToFile(file)

        /** [containerExtension] (e.g. "mp4") selects the container; by default it follows the Uri's MIME type. */
        fun of(resolver: ContentResolver, uri: Uri, containerExtension: String? = null): MediaOutput {
            val extension = containerExtension
                ?: resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            require(!extension.isNullOrEmpty() && extension.all { it.isLetterOrDigit() }) {
                "cannot tell the output container of $uri; pass containerExtension"
            }
            return ToUri(resolver, uri, extension)
        }
    }
}

/**
 * Delivery to a [MediaOutput.ToUri]: the output is written to a temporary file in the app's cache
 * directory (`java.io.tmpdir` on Android) and copied into the Uri in one pass afterwards. The Uri is
 * untouched if writing fails, and native code never needs a seekable output descriptor. If the copy
 * itself fails, the Uri holds incomplete data; callers delete or retry it (MediaStore callers should
 * keep the item pending until the operation returns).
 */
internal object UriDelivery {

    fun <T> deliver(output: MediaOutput.ToUri, meter: DeliveryMeter? = null, write: (temp: File) -> T): T {
        val temp = mapIoFailures(MediaRole.OUTPUT, "create temporary file for ${output.uri}") {
            File.createTempFile("ffmpegkit-", ".part." + output.containerExtension)
        }
        var failure: Throwable? = null
        try {
            val value = write(temp)
            meter?.expect(temp.length())
            copyInto(temp, output, meter)
            return value
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (!temp.delete() && temp.exists()) {
                failure?.addSuppressed(FFmpegException(Reason.IO, "could not delete temporary file $temp"))
            }
        }
    }

    private fun copyInto(temp: File, output: MediaOutput.ToUri, meter: DeliveryMeter?) = mapIoFailures(MediaRole.OUTPUT, "write ${output.uri}") {
        val descriptor = output.resolver.openFileDescriptor(output.uri, "wt")
            ?: throw FFmpegException(Reason.IO, "write ${output.uri} failed: the provider returned no file")
        descriptor.use {
            FileOutputStream(it.fileDescriptor).channel.use { target ->
                FileInputStream(temp).channel.use { source ->
                    val size = source.size()
                    var position = 0L
                    while (position < size) {
                        val written = source.transferTo(position, minOf(OutputDelivery.COPY_CHUNK, size - position), target)
                        if (written <= 0) throw IOException("copy stopped at $position of $size bytes")
                        position += written
                        meter?.delivered(written)
                    }
                    // Some providers ignore the "t" in "wt"; cut any old tail where the descriptor allows it.
                    try {
                        target.truncate(size)
                    } catch (notSeekable: IOException) {
                        // A pipe or socket: nothing old can follow the data.
                    }
                }
            }
        }
    }
}
