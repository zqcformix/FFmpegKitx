package io.github.nova.ffmpegkit

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
        val status = OutputDelivery.deliver(input, output) { temp ->
            val status = NativeTrim.trim(
                input.path.toByteArray(),
                temp.path.toByteArray(),
                range.startMicros,
                range.endExclusiveMicros ?: -1,
            )
            if (status.failed) throw status.toException(input, output)
            status
        }.value
        return TrimResult(
            requestedRange = range,
            actualRange = TimeRange(status.timesUs[0].microseconds, status.timesUs[1].microseconds),
            output = output,
        )
    }
}

internal object NativeTrim {
    init {
        System.loadLibrary("ffmpegkit")
    }

    /** Paths are UTF-8 bytes; [endUs] is -1 to trim to the end of the media. Times: [actual start, end). */
    @JvmStatic
    external fun trim(input: ByteArray, output: ByteArray, startUs: Long, endUs: Long): NativeRemuxStatus
}
