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
 * 4.2. Writes [output] directly: the temp-file delivery of roadmap 2.5 is not in place yet.
 */
internal object Trimmer {

    fun trim(input: File, output: File, range: TimeRange): TrimResult {
        mapIoFailures(MediaRole.OUTPUT, "create output $output") {
            if (!output.createNewFile()) {
                throw FFmpegException(Reason.OUTPUT_EXISTS, "output already exists: $output")
            }
        }
        val result = try {
            NativeTrim.trim(
                input.path.toByteArray(),
                output.path.toByteArray(),
                range.startMicros,
                range.endExclusiveMicros ?: -1,
            )
        } catch (e: Throwable) {
            deleteCreatedOutput(output, e)
            throw e
        }
        if (result.failed) {
            val failure = result.toException(input, output)
            deleteCreatedOutput(output, failure)
            throw failure
        }
        return TrimResult(
            requestedRange = range,
            actualRange = TimeRange(result.actualStartUs.microseconds, result.actualEndUs.microseconds),
            output = output,
        )
    }

    private fun deleteCreatedOutput(output: File, failure: Throwable) {
        if (output.exists() && !output.delete()) {
            failure.addSuppressed(FFmpegException(Reason.IO, "could not delete incomplete output $output"))
        }
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
