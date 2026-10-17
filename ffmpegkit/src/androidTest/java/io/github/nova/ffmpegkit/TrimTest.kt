package io.github.nova.ffmpegkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fast trim against the generated samples (scripts/generate-test-media.ps1): keyframes every 1 s,
 * B-frames, AAC priming. Expected ranges and packet counts were derived from `ffprobe -show_packets`
 * on the samples: video starts at the last keyframe at or before the start, each stream stops at
 * the first packet whose decode time reaches the end, audio before the keyframe is dropped.
 */
@RunWith(AndroidJUnit4::class)
class TrimTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "trim-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        // `-e keepOutputs true` keeps the files for checking with desktop ffprobe (see RELEASES/ROADMAP evidence).
        if (InstrumentationRegistry.getArguments().getString("keepOutputs") != "true") dir.deleteRecursively()
    }

    @Test
    fun startsAtPreviousKeyframeAndKeepsAudioInSync() {
        val range = TimeRange(2_500.milliseconds, 4_500.milliseconds)
        val result = Trimmer.trim(sample(AV), out("clip.mp4"), range)

        assertSame(range, result.requestedRange)
        assertEquals(TimeRange(2.seconds, 4_633_333.microseconds), result.actualRange)
        assertAudioVideo(result, videoPackets = 77, keyframes = 3, audioPackets = 107)
    }

    @Test
    fun startOnKeyframeIsExact() {
        val result = Trimmer.trim(sample(AV), out("clip.mp4"), TimeRange(3.seconds, 4.seconds))
        assertEquals(TimeRange(3.seconds, 4_133_333.microseconds), result.actualRange)
        assertAudioVideo(result, videoPackets = 32, keyframes = 2, audioPackets = 43)
    }

    @Test
    fun fromZeroDropsAudioPriming() {
        val result = Trimmer.trim(sample(AV), out("clip.mp4"), TimeRange(0.seconds, 3.seconds))
        assertEquals(TimeRange(0.seconds, 3_133_333.microseconds), result.actualRange)
        assertAudioVideo(result, videoPackets = 92, keyframes = 4, audioPackets = 130)
    }

    @Test
    fun openEndRunsToEndOfMedia() {
        val result = Trimmer.trim(sample(AV), out("clip.mp4"), TimeRange.from(7_500.milliseconds))
        assertEquals(TimeRange(7.seconds, 10.seconds), result.actualRange)
        assertAudioVideo(result, videoPackets = 90, keyframes = 3, audioPackets = 129)
    }

    @Test
    fun videoOnly() {
        val result = Trimmer.trim(sample(VIDEO), out("clip.mp4"), TimeRange(1_200.milliseconds, 2_200.milliseconds))
        assertEquals(TimeRange(1.seconds, 2_300_000.microseconds), result.actualRange)
        val video = MediaInspector.inspect(result.output).streams.single()
        assertTrue(video.isVideo)
        assertDecodable(video, packets = 38)
        assertEquals(2, video.keyframes)
    }

    @Test
    fun audioOnlyCutsAtPacketBoundaries() {
        val result = Trimmer.trim(sample(AUDIO), out("clip.m4a"), TimeRange(2_500.milliseconds, 4_500.milliseconds))
        // Packet 107 (pts 109568/44100 s) contains 2.5 s; packet 193 is the last one starting before 4.5 s.
        assertEquals(TimeRange(2_484_535.microseconds, 4_504_671.microseconds), result.actualRange)
        val audio = MediaInspector.inspect(result.output).streams.single()
        assertTrue(audio.isAudio)
        assertDecodable(audio, packets = 87)
    }

    @Test
    fun changesContainerWithoutReencoding() {
        val result = Trimmer.trim(sample(AV), out("clip.mkv"), TimeRange(2_500.milliseconds, 4_500.milliseconds))
        assertEquals(TimeRange(2.seconds, 4_633_333.microseconds), result.actualRange)
        assertAudioVideo(result, videoPackets = 77, keyframes = 3, audioPackets = 107)
    }

    @Test
    fun unicodeAndSpacesInPaths() {
        val input = sample(AV).let { original -> File(dir, "输入 视频 🎬.mp4").also { original.renameTo(it) } }
        val result = Trimmer.trim(input, out("输出 片段 ✂️.mp4"), TimeRange(3.seconds, 4.seconds))
        assertTrue(result.output.length() > 0)
        assertAudioVideo(result, videoPackets = 32, keyframes = 2, audioPackets = 43)
    }

    @Test
    fun startAtEndOfMediaIsOutsideMedia() {
        val output = out("clip.mp4")
        val e = assertThrows(FFmpegException::class.java) {
            Trimmer.trim(sample(AV), output, TimeRange.from(10.seconds))
        }
        assertEquals(Reason.RANGE_OUTSIDE_MEDIA, e.reason)
        assertFalse(output.exists())
    }

    @Test
    fun existingOutputIsNotOverwritten() {
        val output = out("clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val e = assertThrows(FFmpegException::class.java) {
            Trimmer.trim(sample(AV), output, TimeRange(2.seconds, 3.seconds))
        }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.readBytes())
    }

    @Test
    fun missingInput() {
        val output = out("clip.mp4")
        val e = assertThrows(FFmpegException::class.java) {
            Trimmer.trim(File(dir, "missing.mp4"), output, TimeRange(2.seconds, 3.seconds))
        }
        assertEquals(Reason.INPUT_NOT_FOUND, e.reason)
        assertFalse(output.exists())
    }

    @Test
    fun corruptInput() {
        val input = File(dir, "garbage.mp4").apply { writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() }) }
        val output = out("clip.mp4")
        val e = assertThrows(FFmpegException::class.java) {
            Trimmer.trim(input, output, TimeRange(0.seconds, 1.seconds))
        }
        assertEquals(Reason.INVALID_MEDIA, e.reason)
        assertFalse(output.exists())
    }

    @Test
    fun unknownOutputExtension() {
        val output = out("clip.notacontainer")
        val e = assertThrows(FFmpegException::class.java) {
            Trimmer.trim(sample(AV), output, TimeRange(2.seconds, 3.seconds))
        }
        assertEquals(Reason.UNSUPPORTED_MEDIA, e.reason)
        assertFalse(output.exists())
    }

    @Test
    fun containerThatCannotHoldTheStreams() {
        val output = out("clip.mp3")
        val e = assertThrows(FFmpegException::class.java) {
            Trimmer.trim(sample(AV), output, TimeRange(2.seconds, 3.seconds))
        }
        assertEquals(Reason.UNSUPPORTED_MEDIA, e.reason)
        assertFalse(output.exists())
    }

    @Test
    fun leavesNoTemporaryFiles() {
        val input = sample(AV)
        Trimmer.trim(input, out("ok.mp4"), TimeRange(2.seconds, 3.seconds))
        assertThrows(FFmpegException::class.java) {
            Trimmer.trim(input, out("bad.notacontainer"), TimeRange(2.seconds, 3.seconds))
        }
        assertThrows(FFmpegException::class.java) {
            Trimmer.trim(input, out("late.mp4"), TimeRange.from(20.seconds))
        }
        assertEquals(setOf(AV, "out-ok.mp4"), dir.list()!!.toSet())
    }

    @Test
    fun outputThatIsTheInputLeavesTheInputUntouched() {
        val input = sample(AV)
        val before = input.readBytes()
        val e = assertThrows(FFmpegException::class.java) {
            Trimmer.trim(input, File(input.path), TimeRange(2.seconds, 3.seconds))
        }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
        assertArrayEquals(before, input.readBytes())
    }

    @Test
    fun concurrentTrimsToTheSameOutputNeverOverwrite() {
        val input = sample(AV)
        val output = out("race.mp4")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val outcomes = try {
            (1..4).map { second ->
                pool.submit<Result<TrimResult>> {
                    start.await()
                    runCatching { Trimmer.trim(input, output, TimeRange(second.seconds, (second + 1).seconds)) }
                }
            }.also { start.countDown() }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(outcomes.toString(), 1, outcomes.count { it.isSuccess })
        outcomes.filter { it.isFailure }.forEach {
            assertEquals(Reason.OUTPUT_EXISTS, (it.exceptionOrNull() as FFmpegException).reason)
        }
        // Every candidate range is one whole GOP, so the published file must be one complete output.
        val video = MediaInspector.inspect(output).streams.single { it.isVideo }
        assertDecodable(video, packets = 32)
        assertEquals(setOf(AV, "out-race.mp4"), dir.list()!!.toSet())
    }

    @Test
    fun trimsIntoExternalStorage() {
        val external = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null) ?: return
        val externalDir = File(external, "trim-test").apply { deleteRecursively(); mkdirs() }
        try {
            val result = Trimmer.trim(sample(AV), File(externalDir, "clip.mp4"), TimeRange(3.seconds, 4.seconds))
            assertAudioVideo(result, videoPackets = 32, keyframes = 2, audioPackets = 43)
            assertEquals(listOf("clip.mp4"), externalDir.list()!!.toList())
        } finally {
            externalDir.deleteRecursively()
        }
    }

    private fun assertAudioVideo(result: TrimResult, videoPackets: Long, keyframes: Long, audioPackets: Long) {
        val media = MediaInspector.inspect(result.output)
        assertEquals(media.toString(), 2, media.streams.size)
        val video = media.streams.single { it.isVideo }
        val audio = media.streams.single { it.isAudio }
        assertDecodable(video, videoPackets)
        assertEquals(keyframes, video.keyframes)
        assertDecodable(audio, audioPackets)
        // Both streams moved by the same offset: they start together (within one AAC frame) and the
        // output spans what actualRange reports.
        assertTrue("$video / $audio", abs(audio.firstPtsUs - video.firstPtsUs) < AAC_FRAME_US)
        val span = maxOf(video.endUs, audio.endUs) - minOf(video.firstPtsUs, audio.firstPtsUs)
        val actual = result.actualRange.duration!!.inWholeMicroseconds
        assertTrue("span $span vs actual $actual", abs(span - actual) <= 1_000)
    }

    private fun assertDecodable(stream: StreamSummary, packets: Long) {
        assertEquals(stream.toString(), packets, stream.packets)
        assertEquals(stream.toString(), 0, stream.decodeErrors)
        assertEquals(stream.toString(), packets, stream.frames)
    }

    private fun sample(name: String): File {
        val file = File(dir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("media/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun out(name: String) = File(dir, "out-$name")

    private companion object {
        const val AV = "av_h264_aac.mp4"
        const val VIDEO = "video_h264.mp4"
        const val AUDIO = "audio_aac.m4a"
        const val AAC_FRAME_US = 1024 * 1_000_000L / 44_100
    }
}
