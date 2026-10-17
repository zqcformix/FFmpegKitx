package io.github.nova.ffmpegkit

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Split by explicit ranges (roadmap 2.3) against the generated samples (keyframe every 1 s, 30 fps,
 * B-frames, AAC with priming). Every segment must be exactly what a trim of its range writes, so the
 * keyframe fallback makes neighbouring segments overlap; each must decode on its own.
 */
@RunWith(AndroidJUnit4::class)
class SplitRangesTest {

    private lateinit var dir: File
    private lateinit var out: File
    private lateinit var reference: File

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "split-ranges-test").apply { deleteRecursively(); mkdirs() }
        out = File(dir, "out").apply { mkdirs() }
        reference = File(dir, "reference").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        // `-e keepOutputs true` keeps the segments for checking with desktop ffmpeg.
        if (InstrumentationRegistry.getArguments().getString("keepOutputs") != "true") dir.deleteRecursively()
    }

    @Test
    fun adjacentRangesOverlapWhereStartsFallBackToKeyframes() {
        val input = sample(AV)
        val ranges = listOf(TimeRange(0.seconds, 2_500.milliseconds), TimeRange(2_500.milliseconds, 5.seconds), TimeRange.from(5.seconds))
        val result = runBlocking { input.splitTo(out, ranges, "clip", "mp4") }

        assertEquals(listOf("clip-0001.mp4", "clip-0002.mp4", "clip-0003.mp4"), result.segments.map { it.file.name })
        assertEquals(ranges, result.segments.map { it.requestedRange })
        // 2.5 s falls back to the keyframe at 2 s; 5 s is a keyframe.
        assertEquals(listOf(0.seconds, 2.seconds, 5.seconds), result.segments.map { it.actualRange.start })
        assertEquals(10.seconds, result.segments.last().actualRange.endExclusive)
        // The first segment runs to 2.5 s or slightly past it, the second starts at 2 s: they overlap.
        val first = result.segments[0].actualRange
        val second = result.segments[1].actualRange
        assertTrue("$first should end at or after 2.5 s", first.endExclusive!! >= 2_500.milliseconds)
        assertTrue("$second should start before $first ends", second.start < first.endExclusive!!)
        assertSameAsTrims(input, result)
        assertEquals(result.segments.map { it.file.name }.toSet(), out.list()!!.toSet()) // nothing else left behind
    }

    @Test
    fun segmentsFollowTheOrderOfTheRangesWhichMayRepeat() {
        val input = sample(AV)
        val ranges = listOf(TimeRange(6.seconds, 8.seconds), TimeRange(1.seconds, 3.seconds), TimeRange(1.seconds, 3.seconds))
        val result = Splitter.splitByRanges(MediaInput.of(input), out, ranges, "clip", "mp4")

        assertEquals(listOf(6.seconds, 1.seconds, 1.seconds), result.segments.map { it.actualRange.start })
        assertEquals(MediaInspector.packets(result.segments[1].file), MediaInspector.packets(result.segments[2].file))
        assertSameAsTrims(input, result)
    }

    @Test
    fun audioOnlyRangesStartAtThePacketContainingTheStart() {
        val input = sample(AUDIO)
        val ranges = listOf(TimeRange(500.milliseconds, 1_500.milliseconds), TimeRange(1_500.milliseconds, 3.seconds))
        val result = Splitter.splitByRanges(MediaInput.of(input), out, ranges, "clip", "m4a")

        result.segments.forEachIndexed { i, segment ->
            assertTrue("$segment", segment.actualRange.start <= ranges[i].start)
            assertTrue("$segment", ranges[i].start - segment.actualRange.start < AAC_FRAME)
        }
        assertSameAsTrims(input, result)
    }

    @Test
    fun descriptorInputIsReadFromTheStartForEveryRange() {
        val input = sample(AV)
        val ranges = listOf(TimeRange(3.seconds, 4.seconds), TimeRange(0.seconds, 1.seconds))
        val fromFile = Splitter.splitByRanges(MediaInput.of(input), out, ranges, "file", "mp4")
        val fromDescriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            runBlocking { FFmpeg.split(SplitRequest.byRanges(MediaInput.of(it), out, ranges, "fd", "mp4")) }
        }
        assertEquals(fromFile.segments.map { it.actualRange }, fromDescriptor.segments.map { it.actualRange })
        fromFile.segments.zip(fromDescriptor.segments).forEach { (a, b) ->
            assertEquals(MediaInspector.packets(a.file), MediaInspector.packets(b.file))
        }
    }

    @Test
    fun aFailingRangePublishesNothing() {
        val input = sample(AV)
        val ranges = listOf(TimeRange(0.seconds, 2.seconds), TimeRange.from(20.seconds), TimeRange(4.seconds, 6.seconds))
        val e = assertThrows(FFmpegException::class.java) {
            Splitter.splitByRanges(MediaInput.of(input), out, ranges, "clip", "mp4")
        }
        assertEquals(Reason.RANGE_OUTSIDE_MEDIA, e.reason)
        assertTrue(e.message, e.message!!.contains("range 2"))
        assertEquals(emptyList<String>(), out.list()!!.toList()) // no segment, no temporary directory
    }

    @Test
    fun unsupportedContainerPublishesNothing() {
        val e = assertThrows(FFmpegException::class.java) {
            Splitter.splitByRanges(MediaInput.of(sample(AV)), out, listOf(TimeRange(0.seconds, 2.seconds)), "clip", "mp3")
        }
        assertEquals(Reason.UNSUPPORTED_MEDIA, e.reason)
        assertEquals(emptyList<String>(), out.list()!!.toList())
    }

    /**
     * Each segment has the packets and actual range of a separate trim of its range; it starts with a
     * keyframe (when there is video) and decodes without errors on its own.
     */
    private fun assertSameAsTrims(input: File, result: SplitResult) {
        val hasVideo = MediaInspector.inspect(input).streams.any { it.isVideo }
        result.segments.forEachIndexed { i, segment ->
            val range = segment.requestedRange!!
            val trimmed = File(reference, "trim-$i.${segment.file.extension}")
            val trim = Trimmer.trim(input, trimmed, range)
            assertEquals("segment ${i + 1}", trim.actualRange, segment.actualRange)
            assertEquals("segment ${i + 1}", MediaInspector.packets(trimmed), MediaInspector.packets(segment.file))
            if (hasVideo) assertTrue(segment.file.name, MediaInspector.packets(segment.file).first { it.stream == 0 }.keyframe)
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
        const val AUDIO = "audio_aac.m4a"

        /** 1024 samples at 44.1 kHz, rounded up. */
        val AAC_FRAME = 23_220.microseconds
    }
}
