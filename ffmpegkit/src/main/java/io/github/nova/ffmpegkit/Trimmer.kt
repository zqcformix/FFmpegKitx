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
    val output: MediaOutput,
)

/**
 * Fast (stream copy) trimming. Blocking; the public suspend entry point is roadmap 4.2.
 *
 * File outputs go through [OutputDelivery] (temporary file, never overwrites, complete once this
 * returns); Uri outputs through [UriDelivery].
 */
internal object Trimmer {

    fun trim(input: File, output: File, range: TimeRange): TrimResult =
        trim(MediaInput.of(input), MediaOutput.of(output), range)

    fun trim(input: MediaInput, output: MediaOutput, range: TimeRange): TrimResult {
        val times = when (output) {
            is MediaOutput.ToFile -> OutputDelivery.deliver(input.file, output.file) { temp -> run(input, temp, output, range) }.value
            is MediaOutput.ToUri -> UriDelivery.deliver(output) { temp -> run(input, temp, output, range) }
        }
        return TrimResult(range, TimeRange(times[0].microseconds, times[1].microseconds), output)
    }

    private fun run(input: MediaInput, temp: File, output: MediaOutput, range: TimeRange): LongArray =
        input.open().use { opened ->
            val status = NativeTrim.trim(
                opened.path?.toByteArray(),
                opened.fd,
                temp.path.toByteArray(),
                range.startMicros,
                range.endExclusiveMicros ?: -1,
            )
            if (status.failed) throw status.toException(input.description, output.description)
            status.timesUs
        }
}

internal object NativeTrim {
    init {
        System.loadLibrary("ffmpegkit")
    }

    /**
     * Reads [inputPath] (UTF-8 bytes) or, when [inputFd] >= 0, that descriptor. [endUs] is -1 to trim
     * to the end of the media. Times: [actual start, end).
     */
    @JvmStatic
    external fun trim(inputPath: ByteArray?, inputFd: Int, output: ByteArray, startUs: Long, endUs: Long): NativeRemuxStatus
}
