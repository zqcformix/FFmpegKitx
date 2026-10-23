package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import kotlin.coroutines.cancellation.CancellationException

/** Built by jni/operations.cpp from an `ffkx_error`; the constructor signature is part of the JNI contract. */
internal class NativeRemuxStatus(
    /** An `FFKX_*` status, see [NativeStatus]. */
    @JvmField val status: Int,
    /** The FFmpeg error (`AVERROR`) behind the failure, 0 if none. */
    @JvmField val ffmpegError: Int,
    /** `FFKX_SIDE_*`: which side the failure concerns. */
    @JvmField val side: Int,
    /** What failed, without paths (UTF-8); empty on success. */
    @JvmField val message: ByteArray,
    /** [start, end) pairs in microseconds from the input's start time; empty on failure. */
    @JvmField val timesUs: LongArray,
) {
    val failed: Boolean
        get() = status != NativeStatus.OK

    /**
     * The exception for a failed status. [input] and [output] name the two sides in messages; the
     * output is the final target, not the temporary file the native code wrote.
     */
    fun toException(input: String, output: String): Exception {
        val text = String(message, Charsets.UTF_8)
        val subject = if (side == NativeStatus.SIDE_OUTPUT) output else input
        return when (status) {
            NativeStatus.TOO_MANY_SEGMENTS -> IllegalArgumentException("segment duration too short for $input: $text")
            NativeStatus.INVALID_ARGUMENT -> IllegalArgumentException("$text: $subject")
            NativeStatus.CANCELLED -> CancellationException(text)
            else -> FFmpegException(NativeStatus.reasonOf(status), "$text: $subject", ffmpegError.takeIf { it != 0 })
        }
    }

    /** The exception for a failed command ([FFmpeg.execute]): its message already says what failed. */
    fun toCommandException(): Exception {
        val text = String(message, Charsets.UTF_8)
        return when (status) {
            NativeStatus.INVALID_ARGUMENT -> IllegalArgumentException(text)
            NativeStatus.CANCELLED -> CancellationException(text)
            else -> FFmpegException(NativeStatus.reasonOf(status), text, ffmpegError.takeIf { it != 0 })
        }
    }
}

/**
 * The `FFKX_*` status and side values of the C ABI (include/ffmpegkitx.h). They never change;
 * `PackagedErrorsTest` checks them against the header.
 */
internal object NativeStatus {
    const val OK = 0
    const val INVALID_ARGUMENT = 1
    const val UNSUPPORTED_VERSION = 2
    const val OUT_OF_MEMORY = 3
    const val INTERNAL = 4
    const val CANCELLED = 5
    const val INPUT_NOT_FOUND = 6
    const val ACCESS_DENIED = 7
    const val OUTPUT_EXISTS = 8
    const val INSUFFICIENT_SPACE = 9
    const val RANGE_OUTSIDE_MEDIA = 10
    const val TOO_MANY_SEGMENTS = 11
    const val UNSUPPORTED_MEDIA = 12
    const val INVALID_MEDIA = 13
    const val IO = 14
    const val FFMPEG = 15

    /** Every value above, in the order `NativeErrors.statuses()` returns them. */
    val all: List<Int> = (OK..FFMPEG).toList()

    const val SIDE_NONE = 0
    const val SIDE_INPUT = 1
    const val SIDE_OUTPUT = 2

    /**
     * The [Reason] of a failure status. Failures inside the library itself (out of memory, internal,
     * unsupported version) and statuses added after this binding are [Reason.NATIVE].
     */
    fun reasonOf(status: Int): Reason = when (status) {
        INPUT_NOT_FOUND -> Reason.INPUT_NOT_FOUND
        ACCESS_DENIED -> Reason.ACCESS_DENIED
        OUTPUT_EXISTS -> Reason.OUTPUT_EXISTS
        INSUFFICIENT_SPACE -> Reason.INSUFFICIENT_SPACE
        RANGE_OUTSIDE_MEDIA -> Reason.RANGE_OUTSIDE_MEDIA
        UNSUPPORTED_MEDIA -> Reason.UNSUPPORTED_MEDIA
        INVALID_MEDIA -> Reason.INVALID_MEDIA
        IO -> Reason.IO
        else -> Reason.NATIVE
    }
}
