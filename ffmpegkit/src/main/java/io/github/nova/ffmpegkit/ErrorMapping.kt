package io.github.nova.ffmpegkit

import android.system.ErrnoException
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.IOException
import java.nio.file.AccessDeniedException as NioAccessDeniedException
import java.nio.file.FileAlreadyExistsException as NioFileAlreadyExistsException
import java.nio.file.NoSuchFileException as NioNoSuchFileException
import kotlin.io.AccessDeniedException as KotlinAccessDeniedException
import kotlin.io.FileAlreadyExistsException as KotlinFileAlreadyExistsException
import kotlin.io.NoSuchFileException as KotlinNoSuchFileException

/** Which side of an operation failed; the same error code can mean different things for each. */
internal enum class MediaRole { INPUT, OUTPUT }

/**
 * FFmpeg `AVERROR` values and their [Reason].
 *
 * `AVERROR(e)` is `-e`; the errno values are Linux's, shared by every Android ABI. `PackagedErrorsTest`
 * checks this table against the values compiled from the FFmpeg headers on a device.
 */
internal object AvError {
    const val EPERM = -1
    const val ENOENT = -2
    const val EIO = -5
    const val EACCES = -13
    const val EEXIST = -17
    const val EINVAL = -22
    const val ENOSPC = -28
    const val EROFS = -30
    const val ENOSYS = -38
    const val EDQUOT = -122

    val INVALIDDATA = tag('I', 'N', 'D', 'A')
    val EOF = tag('E', 'O', 'F', ' ')
    val PATCHWELCOME = tag('P', 'A', 'W', 'E')
    val DEMUXER_NOT_FOUND = tag(0xF8, 'D', 'E', 'M')
    val MUXER_NOT_FOUND = tag(0xF8, 'M', 'U', 'X')
    val DECODER_NOT_FOUND = tag(0xF8, 'D', 'E', 'C')
    val ENCODER_NOT_FOUND = tag(0xF8, 'E', 'N', 'C')
    val STREAM_NOT_FOUND = tag(0xF8, 'S', 'T', 'R')
    val PROTOCOL_NOT_FOUND = tag(0xF8, 'P', 'R', 'O')
    val BSF_NOT_FOUND = tag(0xF8, 'B', 'S', 'F')
    val FILTER_NOT_FOUND = tag(0xF8, 'F', 'I', 'L')

    /** Every value above, in the order `NativeErrors.constants()` returns them. */
    val all: List<Int> = listOf(
        EPERM, ENOENT, EIO, EACCES, EEXIST, EINVAL, ENOSPC, EROFS, ENOSYS, EDQUOT,
        INVALIDDATA, EOF, PATCHWELCOME,
        DEMUXER_NOT_FOUND, MUXER_NOT_FOUND, DECODER_NOT_FOUND, ENCODER_NOT_FOUND,
        STREAM_NOT_FOUND, PROTOCOL_NOT_FOUND, BSF_NOT_FOUND, FILTER_NOT_FOUND,
    )

    private val unsupported = setOf(
        ENOSYS, PATCHWELCOME,
        DEMUXER_NOT_FOUND, MUXER_NOT_FOUND, DECODER_NOT_FOUND, ENCODER_NOT_FOUND,
        STREAM_NOT_FOUND, PROTOCOL_NOT_FOUND, BSF_NOT_FOUND, FILTER_NOT_FOUND,
    )

    /**
     * Generic meaning of [code]. Callers that know more (for example, that an `EINVAL` came from
     * choosing an output container) should pick the reason themselves.
     */
    fun reasonOf(code: Int, role: MediaRole): Reason = when (code) {
        ENOENT -> if (role == MediaRole.INPUT) Reason.INPUT_NOT_FOUND else Reason.IO
        EACCES, EPERM, EROFS -> Reason.ACCESS_DENIED
        EEXIST -> Reason.OUTPUT_EXISTS
        ENOSPC, EDQUOT -> Reason.INSUFFICIENT_SPACE
        EIO -> Reason.IO
        // EOF only reaches here when the media ended before the data a caller needed.
        INVALIDDATA, EOF -> Reason.INVALID_MEDIA
        in unsupported -> Reason.UNSUPPORTED_MEDIA
        else -> Reason.NATIVE
    }

    // FFERRTAG(a, b, c, d) == -MKTAG(a, b, c, d)
    private fun tag(a: Int, b: Char, c: Char, d: Char) =
        -(a or (b.code shl 8) or (c.code shl 16) or (d.code shl 24))

    private fun tag(a: Char, b: Char, c: Char, d: Char) = tag(a.code, b, c, d)
}

/** FFmpeg error helpers implemented in native code. */
internal object NativeErrors {
    init {
        System.loadLibrary("ffmpegkit")
    }

    /** `av_strerror()` text for [code]. */
    @JvmStatic
    external fun describe(code: Int): String

    /** The `AVERROR` values behind [AvError.all], compiled from the FFmpeg headers. */
    @JvmStatic
    external fun constants(): IntArray
}

/**
 * Builds the exception for an FFmpeg error [code] returned while doing [action] on the [role] side.
 * [description] is the `av_strerror()` text, see [NativeErrors.describe].
 */
internal fun nativeFailure(code: Int, role: MediaRole, action: String, description: String): FFmpegException =
    FFmpegException(AvError.reasonOf(code, role), "$action failed: $description ($code)", nativeErrorCode = code)

/**
 * Runs [block] and converts I/O and permission failures into [FFmpegException]. Other exceptions,
 * including `CancellationException` and existing [FFmpegException]s, propagate unchanged.
 */
internal inline fun <T> mapIoFailures(role: MediaRole, action: String, block: () -> T): T =
    try {
        block()
    } catch (e: FFmpegException) {
        throw e
    } catch (e: IOException) {
        throw ioFailure(e, role, action)
    } catch (e: SecurityException) {
        throw FFmpegException(Reason.ACCESS_DENIED, "$action failed: ${e.message}", cause = e)
    }

internal fun ioFailure(e: IOException, role: MediaRole, action: String): FFmpegException {
    if (e is FFmpegException) return e
    val reason = when (e) {
        is NioNoSuchFileException, is KotlinNoSuchFileException ->
            if (role == MediaRole.INPUT) Reason.INPUT_NOT_FOUND else Reason.IO
        is NioFileAlreadyExistsException, is KotlinFileAlreadyExistsException -> Reason.OUTPUT_EXISTS
        is NioAccessDeniedException, is KotlinAccessDeniedException -> Reason.ACCESS_DENIED
        // Android's java.io reports the errno through an ErrnoException cause.
        else -> e.errno()?.let { AvError.reasonOf(-it, role) }?.takeUnless { it == Reason.NATIVE } ?: Reason.IO
    }
    return FFmpegException(reason, "$action failed: ${e.message}", cause = e)
}

private fun Throwable.errno(): Int? =
    generateSequence(this) { it.cause }.take(8).filterIsInstance<ErrnoException>().firstOrNull()?.errno
