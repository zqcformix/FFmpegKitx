package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.NativeProbeResult.Companion.UNKNOWN
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the probe result layout (jni/operations.cpp); device tests check the values against ffprobe. */
class NativeProbeResultTest {

    @Test
    fun parsesContainerVideoAndAudio() {
        val info = result(
            header(startUs = 0, durationUs = 10_000_000, bitRate = 267_559),
            listOf(
                video(durationUs = 10_000_000, bitRate = 193_932, width = 320, height = 240, rate = 30L to 1L),
                audio(durationUs = 10_000_000, bitRate = 64_216, sampleRate = 44_100, channels = 2),
            ),
            listOf("mov,mp4,m4a,3gp,3g2,mj2", "h264", null, "aac", "stereo"),
        ).toMediaInfo()

        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", info.formatName)
        assertEquals(0.seconds, info.startTime)
        assertEquals(10.seconds, info.duration)
        assertEquals(267_559L, info.bitRate)
        val video = info.streams[0] as StreamInfo.Video
        assertEquals(0, video.index)
        assertEquals("h264", video.codecName)
        assertEquals(320, video.width)
        assertEquals(240, video.height)
        assertEquals(30.0, video.frameRate!!, 0.0)
        assertEquals(0, video.rotation)
        assertFalse(video.isAttachedPicture)
        val audio = info.streams[1] as StreamInfo.Audio
        assertEquals(1, audio.index)
        assertEquals("aac", audio.codecName)
        assertEquals(44_100, audio.sampleRate)
        assertEquals(2, audio.channels)
        assertEquals("stereo", audio.channelLayout)
        assertEquals(10.seconds, audio.duration)
        assertEquals(64_216L, audio.bitRate)
    }

    @Test
    fun unknownValuesStayNullInsteadOfZero() {
        val info = result(
            header(startUs = UNKNOWN, durationUs = UNKNOWN, bitRate = UNKNOWN),
            listOf(
                video(durationUs = UNKNOWN, bitRate = UNKNOWN, width = UNKNOWN, height = UNKNOWN, rate = UNKNOWN to UNKNOWN, rotation = UNKNOWN),
                audio(durationUs = UNKNOWN, bitRate = UNKNOWN, sampleRate = UNKNOWN, channels = UNKNOWN),
            ),
            listOf("h264", "h264", null, "aac", null),
        ).toMediaInfo()

        assertNull(info.startTime)
        assertNull(info.duration)
        assertNull(info.bitRate)
        val video = info.streams[0] as StreamInfo.Video
        listOf(video.duration, video.bitRate, video.width, video.height, video.frameRate, video.rotation).forEach(::assertNull)
        val audio = info.streams[1] as StreamInfo.Audio
        listOf(audio.duration, audio.bitRate, audio.sampleRate, audio.channels, audio.channelLayout).forEach(::assertNull)
    }

    @Test
    fun rotationIsPassedThrough() {
        // The core already converts FFmpeg's counterclockwise angle to clockwise degrees in 0..359.
        val rotations = listOf(0L, 90L, 180L, 270L, 315L).map { rotation ->
            val info = result(header(), listOf(video(rotation = rotation)), listOf("mov", "h264", null)).toMediaInfo()
            (info.streams.single() as StreamInfo.Video).rotation
        }
        assertEquals(listOf(0, 90, 180, 270, 315), rotations)
    }

    @Test
    fun otherStreamsAndAttachedPictures() {
        val info = result(
            header(),
            listOf(video(attachedPicture = true), other(type = 3), other(type = 4), other(type = 5), other(type = 0)),
            listOf("matroska,webm", "mjpeg", null, "subrip", null, "bin_data", null, "ttf", null, "none", null),
        ).toMediaInfo()

        assertTrue((info.streams[0] as StreamInfo.Video).isAttachedPicture)
        assertEquals(
            listOf("subtitle" to "subrip", "data" to "bin_data", "attachment" to "ttf", "unknown" to "none"),
            info.streams.drop(1).map { (it as StreamInfo.Other).mediaType to it.codecName },
        )
        assertEquals(listOf(1, 2, 3, 4), info.streams.drop(1).map { it.index })
    }

    @Test
    fun nonZeroStartTimeAndFractionalFrameRate() {
        val info = result(
            header(startUs = 1_400_000),
            listOf(video(rate = 30_000L to 1_001L)),
            listOf("mpegts", "h264", null),
        ).toMediaInfo()
        assertEquals(1_400_000.microseconds, info.startTime)
        assertEquals(29.97, (info.streams.single() as StreamInfo.Video).frameRate!!, 0.001)
    }

    private fun result(header: List<Long>, streams: List<List<Long>>, strings: List<String?>) =
        NativeProbeResult(
            NativeRemuxStatus(NativeStatus.OK, 0, NativeStatus.SIDE_NONE, ByteArray(0), LongArray(0)),
            (header + streams.flatten()).toLongArray(),
            strings.toTypedArray(),
        )

    private fun header(startUs: Long = 0, durationUs: Long = UNKNOWN, bitRate: Long = UNKNOWN) = listOf(startUs, durationUs, bitRate)

    // Per-stream layout from jni/operations.cpp: media_type (FFKX_MEDIA_TYPE_*), flags (FFKX_STREAM_*),
    // duration_us, bit_rate, width, height, frame_rate_num, frame_rate_den, rotation, sample_rate, channels.
    private fun video(
        durationUs: Long = UNKNOWN,
        bitRate: Long = UNKNOWN,
        width: Long = 320,
        height: Long = 240,
        rate: Pair<Long, Long> = 30L to 1L,
        rotation: Long = 0,
        attachedPicture: Boolean = false,
    ) = listOf(1, if (attachedPicture) 1 else 0, durationUs, bitRate, width, height, rate.first, rate.second, rotation, UNKNOWN, UNKNOWN)

    private fun audio(durationUs: Long = UNKNOWN, bitRate: Long = UNKNOWN, sampleRate: Long = 48_000, channels: Long = 2) =
        listOf(2, 0, durationUs, bitRate, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, sampleRate, channels)

    private fun other(type: Long) = listOf(type, 0, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN)
}
