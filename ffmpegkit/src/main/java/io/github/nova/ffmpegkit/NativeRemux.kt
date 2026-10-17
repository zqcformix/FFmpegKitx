package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File

/** Built by remux.cpp for trim and split; the constructor signature is part of the JNI contract. */
internal class NativeRemuxStatus(
    @JvmField val code: Int,
    @JvmField val outputSide: Boolean,
    @JvmField val reason: Int,
    @JvmField val detail: String?,
    /** [start, end) pairs in microseconds from the input's start time; empty on failure. */
    @JvmField val timesUs: LongArray,
) {
    val failed: Boolean
        get() = code != 0 || reason != REASON_FROM_CODE

    /**
     * The exception for a failed status. [output] names the output side in messages (the final
     * target, not the temporary file the native code wrote).
     */
    fun toException(input: File, output: File): Exception {
        val role = if (outputSide) MediaRole.OUTPUT else MediaRole.INPUT
        val subject = if (outputSide) output else input
        val nativeCode = code.takeIf { it != 0 }
        return when (reason) {
            REASON_RANGE_OUTSIDE_MEDIA -> FFmpegException(Reason.RANGE_OUTSIDE_MEDIA, "$detail: $subject", nativeCode)
            REASON_UNSUPPORTED_MEDIA -> FFmpegException(Reason.UNSUPPORTED_MEDIA, "$detail: $subject", nativeCode)
            REASON_TOO_MANY_SEGMENTS -> IllegalArgumentException("segment duration too short for $input: $detail")
            else -> nativeFailure(code, role, "$detail $subject", NativeErrors.describe(code))
        }
    }

    companion object {
        // Must match remux::kReason* in remux.h.
        const val REASON_FROM_CODE = 0
        const val REASON_RANGE_OUTSIDE_MEDIA = 1
        const val REASON_UNSUPPORTED_MEDIA = 2
        const val REASON_TOO_MANY_SEGMENTS = 3
    }
}
