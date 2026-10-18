package io.github.nova.ffmpegkit

import kotlin.time.Duration.Companion.microseconds

/** Structured probe over probe.cpp; blocking. [FFmpeg.probe] is the public suspend entry point. */
internal object Prober {

    fun probe(input: MediaInput, task: Task? = null): MediaInfo = input.open(task).use { opened ->
        task?.redact(opened.path, Redactions.INPUT)
        val result = NativeProbe.probe(opened.path?.toByteArray(), opened.fd, task?.nativeHandle ?: 0L)
        task?.throwIfCancelled()
        if (result.status.failed) throw result.status.toException(input.description, input.description)
        result.toMediaInfo()
    }
}

/** Built by probe.cpp; the constructor signature is part of the JNI contract. */
internal class NativeProbeResult(
    @JvmField val status: NativeRemuxStatus,
    /** Layout documented in probe.cpp; [UNKNOWN] marks a value the file does not state. */
    @JvmField val numbers: LongArray,
    @JvmField val strings: Array<String?>,
) {
    fun toMediaInfo(): MediaInfo {
        val streams = numbers.drop(HEADER_NUMBERS).chunked(STREAM_NUMBERS).mapIndexed { index, n ->
            val codecName = strings[HEADER_STRINGS + index * STREAM_STRINGS] ?: "none"
            val duration = n[1].known()?.microseconds
            val bitRate = n[2].known()
            when (n[0].toInt()) {
                AVMEDIA_TYPE_VIDEO -> StreamInfo.Video(
                    index,
                    codecName,
                    duration,
                    bitRate,
                    width = n[3].known()?.toInt(),
                    height = n[4].known()?.toInt(),
                    frameRate = n[5].known()?.let { num -> n[6].known()?.let { num.toDouble() / it } },
                    rotation = n[7].known()?.let { Math.floorMod(-it, 360L).toInt() },
                    isAttachedPicture = n[8] == 1L,
                )
                AVMEDIA_TYPE_AUDIO -> StreamInfo.Audio(
                    index,
                    codecName,
                    duration,
                    bitRate,
                    sampleRate = n[9].known()?.toInt(),
                    channels = n[10].known()?.toInt(),
                    channelLayout = strings[HEADER_STRINGS + index * STREAM_STRINGS + 1],
                )
                else -> StreamInfo.Other(index, codecName, duration, bitRate, mediaType(n[0].toInt()))
            }
        }
        return MediaInfo(
            formatName = strings[0] ?: "unknown",
            startTime = numbers[0].known()?.microseconds,
            duration = numbers[1].known()?.microseconds,
            bitRate = numbers[2].known(),
            streams = streams,
        )
    }

    companion object {
        const val UNKNOWN = Long.MIN_VALUE

        private const val HEADER_NUMBERS = 3
        private const val STREAM_NUMBERS = 11
        private const val HEADER_STRINGS = 1
        private const val STREAM_STRINGS = 2

        // AVMediaType values from libavutil/avutil.h.
        private const val AVMEDIA_TYPE_VIDEO = 0
        private const val AVMEDIA_TYPE_AUDIO = 1

        private fun Long.known(): Long? = takeIf { it != UNKNOWN }

        private fun mediaType(type: Int): String = when (type) {
            2 -> "data"
            3 -> "subtitle"
            4 -> "attachment"
            else -> "unknown"
        }
    }
}

internal object NativeProbe {
    init {
        System.loadLibrary("ffmpegkit")
    }

    /** Reads [inputPath] (UTF-8 bytes) or, when [inputFd] >= 0, that descriptor; [task] is a [NativeTask] handle or 0. */
    @JvmStatic
    external fun probe(inputPath: ByteArray?, inputFd: Int, task: Long): NativeProbeResult
}
