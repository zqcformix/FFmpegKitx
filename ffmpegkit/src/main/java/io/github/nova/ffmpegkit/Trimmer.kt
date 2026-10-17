package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import kotlin.time.Duration.Companion.microseconds

/**
 * Result of a fast trim. [actualRange] is what was written, measured on the input's timeline from
 * its start: it starts at the keyframe at or before [requestedRange]'s start and may run a little
 * past the requested end because of B-frame reordering.
 */
internal class TrimResult(
    val requestedRange: TimeRange,
    val actualRange: TimeRange,
    val output: File,
)

/**
 * Fast (stream copy) trimming of local files. Blocking; the public suspend entry point is roadmap
 * 4.2. The output is written to a temporary file and published by [OutputDelivery], so an existing
 * file is never replaced and [output] only appears once complete (or, on storage without hard links,
 * is complete when this returns).
 */
internal object Trimmer {

    fun trim(input: File, output: File, range: TimeRange): TrimResult {
        val result = OutputDelivery.deliver(input, output) { temp ->
            val result = NativeTrim.trim(
                input.path.toByteArray(),
                temp.path.toByteArray(),
                range.startMicros,
                range.endExclusiveMicros ?: -1,
            )
            if (result.failed) throw result.toException(input, output)
            result
        }.value
        return TrimResult(
            requestedRange = range,
            actualRange = TimeRange(result.actualStartUs.microseconds, result.actualEndUs.microseconds),
            output = output,
        )
    }

    private fun NativeTrimResult.toException(input: File, output: File): FFmpegException {
        val role = if (outputSide) MediaRole.OUTPUT else MediaRole.INPUT
        val subject = if (outputSide) output else input
        val code = this.code.takeIf { it != 0 }
        return when (reason) {
            NativeTrimResult.REASON_RANGE_OUTSIDE_MEDIA ->
                FFmpegException(Reason.RANGE_OUTSIDE_MEDIA, "$detail: $subject", code)
            NativeTrimResult.REASON_UNSUPPORTED_MEDIA ->
                FFmpegException(Reason.UNSUPPORTED_MEDIA, "$detail: $subject", code)
            else -> nativeFailure(this.code, role, "$detail $subject", NativeErrors.describe(this.code))
        }
    }
}

/** Built by trim.cpp; the constructor signature is part of the JNI contract. */
internal class NativeTrimResult(
    @JvmField val code: Int,
    @JvmField val outputSide: Boolean,
    @JvmField val reason: Int,
    @JvmField val detail: String?,
    @JvmField val actualStartUs: Long,
    @JvmField val actualEndUs: Long,
) {
    val failed: Boolean
        get() = code != 0 || reason != REASON_FROM_CODE

    companion object {
        // Must match kReason* in trim.cpp.
        const val REASON_FROM_CODE = 0
        const val REASON_RANGE_OUTSIDE_MEDIA = 1
        const val REASON_UNSUPPORTED_MEDIA = 2
    }
}

internal object NativeTrim {
    init {
        System.loadLibrary("ffmpegkit")
    }

    /** Paths are UTF-8 bytes; [endUs] is -1 to trim to the end of the media. */
    @JvmStatic
    external fun trim(input: ByteArray, output: ByteArray, startUs: Long, endUs: Long): NativeTrimResult
}
