package io.github.nova.ffmpegkit

import kotlin.time.Duration

/**
 * What [FFmpeg.probe] read from a media file's container and stream headers, without decoding.
 * A value the file does not state is `null`, never a made-up zero. Values match `ffprobe` from the
 * same FFmpeg version, except that durations FFmpeg can only estimate from the bit rate (raw
 * streams such as ADTS AAC) are `null`.
 */
class MediaInfo internal constructor(
    /** FFmpeg's demuxer name, as ffprobe's `format_name` (e.g. `mov,mp4,m4a,3gp,3g2,mj2`). */
    val formatName: String,
    /** The container's start time. Times in [TrimResult] and [SplitResult] count from it. */
    val startTime: Duration?,
    val duration: Duration?,
    /** Overall bit rate in bits per second. */
    val bitRate: Long?,
    /** Every stream in container order, including those trim and split do not copy. */
    val streams: List<StreamInfo>,
) {
    override fun toString(): String =
        "MediaInfo(format=$formatName, startTime=$startTime, duration=$duration, bitRate=$bitRate, streams=$streams)"
}

/** One stream of a [MediaInfo]. */
sealed class StreamInfo {
    /** Index in the container. */
    abstract val index: Int

    /** FFmpeg's codec name (e.g. `h264`, `aac`), as ffprobe's `codec_name`. */
    abstract val codecName: String

    abstract val duration: Duration?

    /** Bits per second. */
    abstract val bitRate: Long?

    class Video internal constructor(
        override val index: Int,
        override val codecName: String,
        override val duration: Duration?,
        override val bitRate: Long?,
        /** Coded size, before [rotation] is applied. */
        val width: Int?,
        val height: Int?,
        /** Average frame rate (ffprobe's `avg_frame_rate`); for raw streams this can be the demuxer's default. */
        val frameRate: Double?,
        /**
         * Clockwise degrees to rotate the picture for display (as Android's `MediaFormat.KEY_ROTATION`),
         * in `0 until 360`; 0 without a display matrix, `null` if the matrix is not a rotation.
         */
        val rotation: Int?,
        /** Cover art stored as a video stream; trim and split skip it. */
        val isAttachedPicture: Boolean,
    ) : StreamInfo() {
        override fun toString(): String =
            "Video(#$index $codecName, ${width}x$height, frameRate=$frameRate, rotation=$rotation, " +
                "duration=$duration, bitRate=$bitRate${if (isAttachedPicture) ", attached picture" else ""})"
    }

    class Audio internal constructor(
        override val index: Int,
        override val codecName: String,
        override val duration: Duration?,
        override val bitRate: Long?,
        val sampleRate: Int?,
        val channels: Int?,
        /** FFmpeg's layout description (e.g. `stereo`, `5.1`), as ffprobe's `channel_layout`. */
        val channelLayout: String?,
    ) : StreamInfo() {
        override fun toString(): String =
            "Audio(#$index $codecName, sampleRate=$sampleRate, channels=$channels, layout=$channelLayout, " +
                "duration=$duration, bitRate=$bitRate)"
    }

    /** Subtitle, data, attachment or unknown streams; described in more detail later (roadmap 9.2). */
    class Other internal constructor(
        override val index: Int,
        override val codecName: String,
        override val duration: Duration?,
        override val bitRate: Long?,
        /** `subtitle`, `data`, `attachment` or `unknown`, as ffprobe's `codec_type`. */
        val mediaType: String,
    ) : StreamInfo() {
        override fun toString(): String = "Other(#$index $mediaType $codecName, duration=$duration, bitRate=$bitRate)"
    }
}
