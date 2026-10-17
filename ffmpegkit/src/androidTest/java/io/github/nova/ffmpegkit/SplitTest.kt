package io.github.nova.ffmpegkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keyframe-aligned split against the generated samples (keyframe every 1 s, 30 fps, B-frames, AAC
 * with priming). Each case checks that the segments concatenated hold exactly the input's packets.
 */
@RunWith(AndroidJUnit4::class)
class SplitTest {

    private lateinit var dir: File
    private lateinit var out: File

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "split-test").apply { deleteRecursively(); mkdirs() }
        out = File(dir, "out").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        // `-e keepOutputs true` keeps the segments for checking with desktop ffmpeg.
        if (InstrumentationRegistry.getArguments().getString("keepOutputs") != "true") dir.deleteRecursively()
    }

    @Test
    fun cutsAtFirstKeyframeAfterEachSegmentLength() {
        val input = sample(AV)
        val result = Splitter.split(input, out, 3.seconds, "clip", "mp4")
        assertRanges(result, 0.seconds to 3.seconds, 3.seconds to 6.seconds, 6.seconds to 9.seconds, 9.seconds to 10.seconds)
        assertEquals(listOf(90, 90, 90, 30), videoPackets(result))
        assertLossless(input, result)
    }

    @Test
    fun segmentsAreNeverShorterThanRequested() {
        // 2.5 s boundaries would be 2.5/5/7.5 s; cutting at the next keyframe after start + 2.5 s gives 3/6/9 s.
        val input = sample(AV)
        val result = Splitter.split(input, out, 2_500.milliseconds, "clip", "mp4")
        assertRanges(result, 0.seconds to 3.seconds, 3.seconds to 6.seconds, 6.seconds to 9.seconds, 9.seconds to 10.seconds)
        result.segments.dropLast(1).forEach { assertTrue(it.actualRange.duration!! >= 2_500.milliseconds) }
        assertLossless(input, result)
    }

    @Test
    fun exactDivisionMakesNoEmptySegment() {
        val input = sample(AV)
        val result = Splitter.split(input, out, 5.seconds, "clip", "mp4")
        assertRanges(result, 0.seconds to 5.seconds, 5.seconds to 10.seconds)
        assertLossless(input, result)
    }

    @Test
    fun mediaShorterThanOneSegment() {
        val input = sample(AV)
        val result = Splitter.split(input, out, 20.seconds, "clip", "mp4")
        assertRanges(result, 0.seconds to 10.seconds)
        assertLossless(input, result)
    }

    @Test
    fun oneSegmentPerKeyframe() {
        val input = sample(AV)
        val result = Splitter.split(input, out, 1.seconds, "clip", "mp4")
        assertEquals((1..10).map { "clip-%04d.mp4".format(it) }, result.segments.map { it.file.name })
        result.segments.forEachIndexed { i, segment -> assertEquals(TimeRange(i.seconds, (i + 1).seconds), segment.actualRange) }
        assertLossless(input, result)
    }

    @Test
    fun videoOnly() {
        val input = sample(VIDEO)
        val result = Splitter.split(input, out, 3.seconds, "clip", "mp4")
        assertRanges(result, 0.seconds to 3.seconds, 3.seconds to 6.seconds, 6.seconds to 9.seconds, 9.seconds to 10.seconds)
        assertLossless(input, result)
    }

    @Test
    fun audioOnlyCutsAtPacketBoundaries() {
        val input = sample(AUDIO)
        val result = Splitter.split(input, out, 3.seconds, "clip", "m4a")
        // First packet starting at or after 3 s is 133120/44100 s; then 6.0186 s → 266240, 9.0372 s → 399360.
        assertRanges(
            result,
            0.seconds to 3_018_594.microseconds,
            3_018_594.microseconds to 6_037_188.microseconds,
            6_037_188.microseconds to 9_055_782.microseconds,
            9_055_782.microseconds to 10.seconds,
        )
        assertEquals(listOf(131, 130, 130, 41), result.segments.map { MediaInspector.packets(it.file).size })
        assertLossless(input, result)
    }

    @Test
    fun otherContainerAndUnicodePrefix() {
        val input = sample(AV)
        val result = Splitter.split(input, out, 5.seconds, "假期 🎬", "mkv")
        assertEquals(listOf("假期 🎬-0001.mkv", "假期 🎬-0002.mkv"), result.segments.map { it.file.name })
        result.segments.forEach { segment ->
            MediaInspector.inspect(segment.file).streams.forEach { assertEquals(it.toString(), 0, it.decodeErrors) }
        }
    }

    @Test
    fun tooManySegmentsIsRejectedBeforeWriting() {
        // 10 s / 1 ms could mean 10 001 segments, above the 9 999 limit, even though keyframes would allow only 10.
        val input = sample(AV)
        assertThrows(IllegalArgumentException::class.java) {
            Splitter.split(input, out, 1.milliseconds, "clip", "mp4")
        }
        assertEquals(emptyList<String>(), out.list()!!.toList())
    }

    @Test
    fun segmentLengthJustInsideTheLimit() {
        // 10 s / 1.001 ms → at most 9 991 segments: allowed; the keyframes give 10.
        val result = Splitter.split(sample(AV), out, 1_001.microseconds, "clip", "mp4")
        assertEquals(10, result.segments.size)
        assertEquals("clip-0010.mp4", result.segments.last().file.name)
    }

    @Test
    fun existingSegmentNameStopsBeforeWork() {
        val existing = File(out, "clip-0003.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val e = assertThrows(FFmpegException::class.java) {
            Splitter.split(sample(AV), out, 3.seconds, "clip", "mp4")
        }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
        assertArrayEquals(byteArrayOf(1, 2, 3), existing.readBytes())
        assertEquals(listOf("clip-0003.mp4"), out.list()!!.toList())
    }

    @Test
    fun corruptInputLeavesNothingBehind() {
        val input = File(dir, "garbage.mp4").apply { writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() }) }
        val e = assertThrows(FFmpegException::class.java) {
            Splitter.split(input, out, 3.seconds, "clip", "mp4")
        }
        assertEquals(Reason.INVALID_MEDIA, e.reason)
        assertEquals(emptyList<String>(), out.list()!!.toList())
    }

    @Test
    fun unsupportedContainerLeavesNothingBehind() {
        val e = assertThrows(FFmpegException::class.java) {
            Splitter.split(sample(AV), out, 3.seconds, "clip", "mp3")
        }
        assertEquals(Reason.UNSUPPORTED_MEDIA, e.reason)
        assertEquals(emptyList<String>(), out.list()!!.toList())
    }

    private fun assertRanges(result: SplitResult, vararg expected: Pair<Duration, Duration>) {
        assertEquals(expected.map { (start, end) -> TimeRange(start, end) }, result.segments.map { it.actualRange })
        assertEquals((1..expected.size).map { "clip-%04d".format(it) }, result.segments.map { it.file.nameWithoutExtension })
        assertEquals(result.segments.map { it.file.name }.toSet(), out.list()!!.toSet()) // nothing else left behind
    }

    private fun videoPackets(result: SplitResult) = result.segments.map { segment ->
        MediaInspector.packets(segment.file).count { it.stream == 0 }
    }

    /** Concatenated per stream, the segments' packets equal the input's: same data, same order, none lost or repeated. */
    private fun assertLossless(input: File, result: SplitResult) {
        val original = MediaInspector.packets(input).groupBy { it.stream }
        val joined = result.segments.flatMap { MediaInspector.packets(it.file) }.groupBy { it.stream }
        assertEquals(original.keys, joined.keys)
        for (stream in original.keys) {
            assertEquals("stream $stream", original.getValue(stream), joined.getValue(stream))
        }
        // With video, every segment starts its video with a keyframe; all segments decode cleanly.
        val videoStream = MediaInspector.inspect(input).streams.indexOfFirst { it.isVideo }
        result.segments.forEach { segment ->
            if (videoStream >= 0) {
                val firstVideo = MediaInspector.packets(segment.file).first { it.stream == videoStream }
                assertTrue(segment.file.name, firstVideo.keyframe)
            }
            MediaInspector.inspect(segment.file).streams.forEach { assertEquals("${segment.file.name} $it", 0, it.decodeErrors) }
        }
    }

    private fun sample(name: String): File {
        val file = File(dir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("media/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private companion object {
        const val AV = "av_h264_aac.mp4"
        const val VIDEO = "video_h264.mp4"
        const val AUDIO = "audio_aac.m4a"
    }
}
