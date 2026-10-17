package io.github.nova.ffmpegkit

import java.io.File

/** Per-stream result of decoding a whole file. Times are microseconds on the file's own timeline. */
internal class StreamSummary(
    val type: Int,
    val packets: Long,
    val keyframes: Long,
    val firstPtsUs: Long,
    val endUs: Long,
    val frames: Long,
    val decodeErrors: Long,
) {
    val isVideo: Boolean get() = type == AVMEDIA_TYPE_VIDEO
    val isAudio: Boolean get() = type == AVMEDIA_TYPE_AUDIO

    override fun toString(): String =
        "Stream(type=$type, packets=$packets, keyframes=$keyframes, firstPtsUs=$firstPtsUs, " +
            "endUs=$endUs, frames=$frames, decodeErrors=$decodeErrors)"

    private companion object {
        const val AVMEDIA_TYPE_VIDEO = 0
        const val AVMEDIA_TYPE_AUDIO = 1
    }
}

internal class MediaSummary(
    val startTimeUs: Long,
    val durationUs: Long?,
    val streams: List<StreamSummary>,
) {
    override fun toString(): String = "Media(startTimeUs=$startTimeUs, durationUs=$durationUs, streams=$streams)"
}

/** Demuxes and decodes every packet of a file; used to verify outputs. */
internal object MediaInspector {

    fun inspect(file: File): MediaSummary {
        val values = NativeInspector.inspect(file.path.toByteArray())
        val status = values[0].toInt()
        if (status != 0) {
            throw nativeFailure(status, MediaRole.INPUT, "inspect $file", NativeErrors.describe(status))
        }
        val streams = values.drop(HEADER_FIELDS).chunked(STREAM_FIELDS).map {
            StreamSummary(it[0].toInt(), it[1], it[2], it[3], it[4], it[5], it[6])
        }
        return MediaSummary(values[1], values[2].takeIf { it >= 0 }, streams)
    }

    /** Every packet in read order, without decoding. */
    fun packets(file: File): List<PacketDigest> {
        val values = NativeInspector.packets(file.path.toByteArray())
        val status = values[0].toInt()
        if (status != 0) {
            throw nativeFailure(status, MediaRole.INPUT, "read packets of $file", NativeErrors.describe(status))
        }
        return values.drop(1).chunked(PACKET_FIELDS).map { PacketDigest(it[0].toInt(), it[1].toInt(), it[2] == 1L, it[3]) }
    }

    // Layouts documented in inspect.cpp.
    private const val HEADER_FIELDS = 3
    private const val STREAM_FIELDS = 7
    private const val PACKET_FIELDS = 4
}

/** A packet's identity for comparing packet sequences: stream, size, keyframe flag and data hash. */
internal data class PacketDigest(val stream: Int, val size: Int, val keyframe: Boolean, val hash: Long)

internal object NativeInspector {
    init {
        System.loadLibrary("ffmpegkit")
    }

    @JvmStatic
    external fun inspect(path: ByteArray): LongArray

    @JvmStatic
    external fun packets(path: ByteArray): LongArray
}
