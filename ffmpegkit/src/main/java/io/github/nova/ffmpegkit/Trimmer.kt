package io.github.nova.ffmpegkit

import java.io.File
import kotlin.time.Duration.Companion.microseconds

/**
 * Fast (stream copy) trimming; blocking. [FFmpeg.trim] is the public suspend entry point.
 *
 * File outputs go through [OutputDelivery] (temporary file, never overwrites, complete once this
 * returns); Uri outputs through [UriDelivery]. A cancelled [cancellation] stops the native work and
 * nothing is published.
 */
internal object Trimmer {

    fun trim(input: File, output: File, range: TimeRange, mode: TrimMode = TrimMode.KEYFRAME): TrimResult =
        trim(MediaInput.of(input), MediaOutput.of(output), range, mode = mode)

    fun trim(
        input: MediaInput,
        output: MediaOutput,
        range: TimeRange,
        cancellation: NativeCancellation? = null,
        mode: TrimMode = TrimMode.KEYFRAME,
    ): TrimResult {
        val editList = mode == TrimMode.EDIT_LIST
        val times = when (output) {
            is MediaOutput.ToFile ->
                OutputDelivery.deliver(input.file, output.file) { temp -> run(input, temp, output, range, editList, cancellation) }.value
            is MediaOutput.ToUri ->
                UriDelivery.deliver(output) { temp -> run(input, temp, output, range, editList, cancellation) }
        }
        return TrimResult(range, TimeRange(times[0].microseconds, times[1].microseconds), output)
    }

    private fun run(
        input: MediaInput,
        temp: File,
        output: MediaOutput,
        range: TimeRange,
        editList: Boolean,
        cancellation: NativeCancellation?,
    ): LongArray = input.open().use { opened ->
        val status = NativeTrim.trim(
            opened.path?.toByteArray(),
            opened.fd,
            temp.path.toByteArray(),
            range.startMicros,
            range.endExclusiveMicros ?: -1,
            editList,
            cancellation?.nativeHandle ?: 0L,
        )
        cancellation?.throwIfCancelled() // before anything is published
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
     * to the end of the media; [editList] selects [TrimMode.EDIT_LIST]; [cancellation] is a
     * [NativeCancellation] handle or 0. Times: [actual start, end).
     */
    @JvmStatic
    external fun trim(
        inputPath: ByteArray?,
        inputFd: Int,
        output: ByteArray,
        startUs: Long,
        endUs: Long,
        editList: Boolean,
        cancellation: Long,
    ): NativeRemuxStatus
}
