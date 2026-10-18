package io.github.nova.ffmpegkit

import android.os.Build
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong

internal enum class DeliveryMethod { RENAME, HARD_LINK, COPY }

internal class Delivered<T>(val value: T, val method: DeliveryMethod)

/**
 * How many of an operation's output bytes are delivered, for progress (roadmap 5.2). Outputs are
 * [expect]ed once written; a rename or link delivers a whole file at once, a copy counts as it goes.
 */
internal class DeliveryMeter {
    private val expected = AtomicLong()
    private val delivered = AtomicLong()

    val expectedBytes: Long get() = expected.get()
    val deliveredBytes: Long get() = delivered.get()

    fun expect(bytes: Long) {
        expected.addAndGet(bytes)
    }

    fun delivered(bytes: Long) {
        delivered.addAndGet(bytes)
    }
}

/**
 * Writes an output through a temporary file in the target's directory and publishes it without ever
 * replacing an existing file (roadmap 2.5).
 *
 * Publishing is atomic where the platform allows it: `renameat2(RENAME_NOREPLACE)` (Android 11+), then
 * a hard link (Android's SELinux policy denies hard links to app files, but other file systems and the
 * JVM allow them). Otherwise, e.g. on FUSE storage, the target is created exclusively and the data
 * copied; during that copy the target path exists with incomplete content, so callers must only read
 * it after [deliver] returns.
 */
internal object OutputDelivery {

    /**
     * Runs [write] on a fresh temporary file and publishes it at [target]. [input], when given, only
     * improves the error message if it is the same file as [target]. On any failure, including
     * cancellation, the temporary file is removed and the exception propagates unchanged.
     *
     * [publish] must move or link `temp` to `target` atomically, throw [FileAlreadyExistsException]
     * if `target` exists, and throw [IOException] or [UnsupportedOperationException] to fall back to copying.
     */
    fun <T> deliver(
        input: File?,
        target: File,
        meter: DeliveryMeter? = null,
        publish: (target: Path, temp: Path) -> DeliveryMethod = ::publishAtomically,
        copy: (source: Path, target: FileChannel) -> Unit = { source, channel -> copyAll(source, channel, meter) },
        identity: (Path) -> Any? = ::fileKey,
        write: (temp: File) -> T,
    ): Delivered<T> {
        checkTarget(input, target)
        val temp = mapIoFailures(MediaRole.OUTPUT, "create temporary file next to $target") {
            createTemp(target)
        }
        var failure: Throwable? = null
        try {
            val value = write(temp)
            meter?.expect(temp.length())
            val method = publishOrCopy(temp.toPath(), target.toPath(), meter, publish, copy, identity)
            return Delivered(value, method)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            // After a successful publish a leftover hidden .part file is harmless, so only report it on failure.
            if (!temp.delete() && temp.exists()) {
                failure?.addSuppressed(FFmpegException(Reason.IO, "could not delete temporary file $temp"))
            }
        }
    }

    /**
     * Publishes an already written [temp] at [target] the same way [deliver] does. [temp] must be on
     * the same file system as [target] (for example in a temporary directory next to it). The caller
     * removes [temp] afterwards if it still exists. [temp] must already be [DeliveryMeter.expect]ed.
     */
    fun publishFile(
        temp: File,
        target: File,
        meter: DeliveryMeter? = null,
        publish: (target: Path, temp: Path) -> DeliveryMethod = ::publishAtomically,
        copy: (source: Path, target: FileChannel) -> Unit = { source, channel -> copyAll(source, channel, meter) },
        identity: (Path) -> Any? = ::fileKey,
    ): DeliveryMethod = publishOrCopy(temp.toPath(), target.toPath(), meter, publish, copy, identity)

    /**
     * Whether delivering a local file is expected to copy it: before Android 11 there is no
     * `renameat2`, and Android's SELinux policy denies the hard link. Only used to weigh progress.
     */
    val expectsCopy: Boolean
        get() = !NativeFiles.available || Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /** Identity (device and inode) of [file], or null where the platform has none or it is missing. */
    fun identityOf(file: File): Any? = fileKey(file.toPath())

    /** Throws [FFmpegException] unless [target]'s directory exists and [target] does not. */
    fun checkTarget(input: File?, target: File) {
        val directory = target.absoluteFile.parentFile
        if (directory == null || !directory.isDirectory) {
            throw FFmpegException(Reason.IO, "output directory does not exist: $directory")
        }
        // NOFOLLOW_LINKS: a dangling symlink also counts as an existing output.
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val isInput = input != null && runCatching { Files.isSameFile(input.toPath(), target.toPath()) }.getOrDefault(false)
            throw FFmpegException(
                Reason.OUTPUT_EXISTS,
                if (isInput) "output is the input file: $target" else "output already exists: $target",
            )
        }
    }

    /** Hidden, unique, and ending in the target's extension, which selects the container. */
    private fun createTemp(target: File): File {
        val extension = target.extension
        val suffix = if (extension.isEmpty()) ".part" else ".part.$extension"
        val prefix = "." + target.nameWithoutExtension.take(MAX_PREFIX_CHARS) + "."
        return Files.createTempFile(target.absoluteFile.parentFile!!.toPath(), prefix, suffix).toFile()
    }

    private fun publishOrCopy(
        temp: Path,
        target: Path,
        meter: DeliveryMeter?,
        atomic: (Path, Path) -> DeliveryMethod,
        copy: (Path, FileChannel) -> Unit,
        identity: (Path) -> Any?,
    ): DeliveryMethod {
        try {
            val size = if (meter != null) Files.size(temp) else 0L
            return atomic(target, temp).also { meter?.delivered(size) }
        } catch (e: FileAlreadyExistsException) {
            throw FFmpegException(Reason.OUTPUT_EXISTS, "output already exists: $target", cause = e)
        } catch (unsupported: UnsupportedOperationException) {
            // No atomic publish on this file system; copy instead.
        } catch (refused: IOException) {
            // EPERM/EACCES/ENOSYS from FUSE, FAT or SELinux. Real problems (no space, no permission) resurface in the copy.
        }
        copyExclusive(temp, target, copy, identity)
        return DeliveryMethod.COPY
    }

    private fun publishAtomically(target: Path, temp: Path): DeliveryMethod {
        if (NativeFiles.available) {
            when (NativeFiles.renameNoReplace(temp.toString().toByteArray(), target.toString().toByteArray())) {
                0 -> return DeliveryMethod.RENAME
                AvError.EEXIST -> throw FileAlreadyExistsException(target.toString())
                // ENOSYS before Android 11, EINVAL where the file system lacks RENAME_NOREPLACE: try a hard link.
            }
        }
        Files.createLink(target, temp)
        return DeliveryMethod.HARD_LINK
    }

    private fun copyExclusive(temp: Path, target: Path, copy: (Path, FileChannel) -> Unit, identity: (Path) -> Any?) {
        val channel = mapIoFailures(MediaRole.OUTPUT, "create output $target") {
            try {
                FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (e: FileAlreadyExistsException) {
                throw FFmpegException(Reason.OUTPUT_EXISTS, "output already exists: $target", cause = e)
            }
        }
        val created = identity(target)
        try {
            mapIoFailures(MediaRole.OUTPUT, "copy output to $target") {
                channel.use { copy(temp, it) }
            }
        } catch (e: Throwable) {
            removeIfCreated(target, created, identity, e)
            throw e
        }
    }

    /** Deletes [target] only while it is still the file this call created; otherwise reports why it stays. */
    private fun removeIfCreated(target: Path, created: Any?, identity: (Path) -> Any?, failure: Throwable) {
        if (created == null || identity(target) != created) {
            failure.addSuppressed(
                FFmpegException(Reason.IO, "left incomplete output $target: cannot confirm it is the file this call created"),
            )
            return
        }
        try {
            Files.deleteIfExists(target)
        } catch (e: IOException) {
            failure.addSuppressed(FFmpegException(Reason.IO, "could not delete incomplete output $target", cause = e))
        }
    }

    private fun copyAll(source: Path, target: FileChannel, meter: DeliveryMeter?) {
        FileChannel.open(source, StandardOpenOption.READ).use { input ->
            val size = input.size()
            var position = 0L
            while (position < size) {
                val copied = target.transferFrom(input, position, minOf(COPY_CHUNK, size - position))
                if (copied <= 0) throw IOException("copy stopped at $position of $size bytes")
                position += copied
                meter?.delivered(copied)
            }
        }
    }

    /** Device and inode on Unix (Android); null where the platform has no stable key, e.g. Windows. */
    private fun fileKey(path: Path): Any? =
        runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() }
            .getOrNull()

    // Keeps temporary names well below the 255-byte limit even for long multi-byte names.
    private const val MAX_PREFIX_CHARS = 40

    /** Copies go in chunks of this many bytes so that progress moves during long copies. */
    const val COPY_CHUNK = 8L * 1024 * 1024
}

internal object NativeFiles {
    /** False off Android (host unit tests), where the native library does not exist. */
    val available: Boolean = runCatching { System.loadLibrary("ffmpegkit") }.isSuccess

    /** `renameat2(from, to, RENAME_NOREPLACE)` on UTF-8 paths: 0 or -errno; -ENOSYS before Android 11. */
    @JvmStatic
    external fun renameNoReplace(from: ByteArray, to: ByteArray): Int
}
