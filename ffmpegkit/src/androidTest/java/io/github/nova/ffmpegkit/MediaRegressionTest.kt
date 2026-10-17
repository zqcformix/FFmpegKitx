package io.github.nova.ffmpegkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Real-media regression (roadmap 2.6) for the media shapes the basic samples lack: a long GOP, a
 * variable frame rate and a non-zero start time (MPEG-TS), plus Unicode and spaces in paths. The
 * samples come from scripts/generate-test-media.ps1; expected keyframes and timestamps are from
 * `ffprobe -show_packets` (FFmpeg N-123317-gc1be2107c9):
 *   - av_long_gop.mp4: 30 fps, B-frames, keyframes at 0, 4 and 8 s
 *   - av_vfr.mp4: 30 fps until 5 s, then 15 fps; a keyframe every 1 s; video ends at 10 s
 *   - av_offset.ts: av_h264_aac.mp4 in MPEG-TS; start time 1.443444 s (first audio packet),
 *     video keyframes at 1.466667 s + n, so 0.023223 s + n on the timeline that results use
 * MPEG-TS and ADTS have no index, so seeking lands on a byte position, often after the keyframe or
 * packet a trim needs; trim.cpp then seeks further back.
 */
@RunWith(AndroidJUnit4::class)
class MediaRegressionTest {

    @get:Rule
    val testName = TestName()

    private lateinit var dir: File
    private lateinit var out: File

    @Before
    fun setUp() {
        // One directory per test, so `-e keepOutputs true` keeps every test's outputs.
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "regression-test/${testName.methodName}").apply { deleteRecursively(); mkdirs() }
        out = File(dir, "out").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        // `-e keepOutputs true` keeps the outputs for scripts/verify-outputs.ps1.
        if (InstrumentationRegistry.getArguments().getString("keepOutputs") != "true") dir.deleteRecursively()
    }

    @Test
    fun longGopTrimFallsBackToTheKeyframeUpToFourSecondsEarlier() {
        val input = sample(LONG_GOP)
        val result = Trimmer.trim(input, File(out, "long-gop-trim.mp4"), TimeRange(5.seconds, 7.seconds))
        assertEquals(4.seconds, result.actualRange.start)
        assertEndNear(result.actualRange, 7.seconds)
        assertEquals(120, assertContiguousRun(input, File(out, "long-gop-trim.mp4")).getValue(VIDEO))
    }

    @Test
    fun longGopSplitCutsOnlyWhereKeyframesAre() {
        val input = sample(LONG_GOP)
        val result = Splitter.split(input, out, 1.seconds, "long-gop", "mp4")
        assertEquals(listOf(TimeRange(0.seconds, 4.seconds), TimeRange(4.seconds, 8.seconds), TimeRange(8.seconds, 10.seconds)), result.segments.map { it.actualRange })
        assertLossless(input, result)
    }

    @Test
    fun longGopRangesCanOverlapByAWholeGop() {
        // Both ranges fall back to the keyframe at 4 s, so the second segment contains the first.
        val input = sample(LONG_GOP)
        val result = Splitter.splitByRanges(
            MediaInput.of(input), out, listOf(TimeRange(5.seconds, 7.seconds), TimeRange(7.seconds, 8.seconds)), "long-gop-ranges", "mp4",
        )
        assertEquals(listOf(4.seconds, 4.seconds), result.segments.map { it.actualRange.start })
        result.segments.forEach { assertEquals(120, assertContiguousRun(input, it.file).getValue(VIDEO)) }
    }

    @Test
    fun variableFrameRateTrimAndSplit() {
        val input = sample(VFR)
        val video = runBlocking { input.probe() }.streams.first() as StreamInfo.Video
        assertEquals(250.0 / 11, video.frameRate!!, 1e-9) // 225 frames in 9.9 s

        val trim = Trimmer.trim(input, File(out, "vfr-trim.mp4"), TimeRange(5_500.milliseconds, 7_500.milliseconds))
        assertEquals(5.seconds, trim.actualRange.start)
        assertEndNear(trim.actualRange, 7_500.milliseconds)
        assertEquals(150, assertContiguousRun(input, File(out, "vfr-trim.mp4")).getValue(VIDEO))

        val split = Splitter.split(input, out, 2.seconds, "vfr", "mp4")
        assertEquals(listOf(0, 2, 4, 6, 8).map { it.seconds }, split.segments.map { it.actualRange.start })
        assertEquals(10.seconds, split.segments.last().actualRange.endExclusive)
        assertLossless(input, split)
    }

    @Test
    fun nonZeroStartTimeIsTheZeroOfReportedRanges() {
        val input = sample(OFFSET)
        val info = runBlocking { input.probe() }
        assertEquals("mpegts", info.formatName)
        assertEquals(1_443_444.microseconds, info.startTime)

        // 2 s after the start time is 3.443444 s; the keyframe before it is at 2.466667 s, i.e. 1.023223 s.
        val ts = Trimmer.trim(input, File(out, "offset-trim.ts"), TimeRange(2.seconds, 4.seconds))
        assertEquals(1_023_223.microseconds, ts.actualRange.start)
        assertEndNear(ts.actualRange, 4.seconds)
        assertEquals(30, assertContiguousRun(input, File(out, "offset-trim.ts")).getValue(VIDEO))
        assertDecodes(File(out, "offset-trim.ts"))

        // The same range into MP4: the muxer converts Annex B and ADTS, so compare counts and timing.
        val mp4 = Trimmer.trim(input, File(out, "offset-trim.mp4"), TimeRange(2.seconds, 4.seconds))
        assertEquals(ts.actualRange, mp4.actualRange)
        assertEquals(packetCounts(File(out, "offset-trim.ts")), packetCounts(File(out, "offset-trim.mp4")))
        val output = MediaInspector.inspect(File(out, "offset-trim.mp4"))
        assertEquals(0L, output.startTimeUs)
        assertDecodes(File(out, "offset-trim.mp4"))
    }

    @Test
    fun indexlessAudioTrimStartsAtThePacketHoldingTheStart() {
        // ADTS has no index either; a seek can land after the packet that holds the start.
        val input = sample(ADTS)
        val result = Trimmer.trim(input, File(out, "adts-trim.m4a"), TimeRange(5.seconds, 6.seconds))
        assertTrue("${result.actualRange}", result.actualRange.start <= 5.seconds)
        assertTrue("${result.actualRange}", 5.seconds - result.actualRange.start < AAC_FRAME)
        assertEndNear(result.actualRange, 6.seconds)
        assertDecodes(File(out, "adts-trim.m4a"))
    }

    @Test
    fun nonZeroStartTimeSplit() {
        val input = sample(OFFSET)
        val result = Splitter.split(input, out, 3.seconds, "offset", "ts")
        assertEquals(listOf(0, 3_023_223, 6_023_223, 9_023_223).map { it.microseconds }, result.segments.map { it.actualRange.start })
        assertLossless(input, result)
    }

    @Test
    fun unicodeAndSpacesInDirectoriesAndPrefixes() {
        val input = File(File(dir, "输入 dir é").apply { mkdirs() }, "长 GOP 样本.mp4").apply { sample(LONG_GOP).copyTo(this) }
        val target = File(out, "输出 目录 ü").apply { mkdirs() }
        assertEquals(10.seconds, runBlocking { input.probe() }.duration)

        val split = runBlocking { input.splitTo(target, 1.seconds, "片段 clip", "mp4") }
        assertEquals(listOf("片段 clip-0001.mp4", "片段 clip-0002.mp4", "片段 clip-0003.mp4"), split.segments.map { it.file.name })
        assertLossless(input, split)

        val ranges = runBlocking { input.splitTo(target, listOf(TimeRange(8.seconds, 9.seconds)), "范围 range", "mkv") }
        assertEquals("范围 range-0001.mkv", ranges.segments.single().file.name)
        assertDecodes(ranges.segments.single().file)
        assertEquals(4, target.list()!!.size) // 3 + 1 segments, nothing else left behind
    }

    /** Per stream, the output's packets are one contiguous run of the input's; returns where each run starts. */
    private fun assertContiguousRun(input: File, output: File): Map<Int, Int> {
        val source = MediaInspector.packets(input).groupBy { it.stream }
        return MediaInspector.packets(output).groupBy { it.stream }.mapValues { (stream, packets) ->
            val all = source.getValue(stream)
            val start = (0..all.size - packets.size).firstOrNull { all.subList(it, it + packets.size) == packets }
            assertTrue("${output.name} stream $stream is not a contiguous run of ${input.name}", start != null)
            start!!
        }
    }

    /** Concatenated per stream, the segments hold exactly the input's packets; each starts with a keyframe and decodes. */
    private fun assertLossless(input: File, result: SplitResult) {
        val original = MediaInspector.packets(input).groupBy { it.stream }
        val joined = result.segments.flatMap { MediaInspector.packets(it.file) }.groupBy { it.stream }
        assertEquals(original.keys, joined.keys)
        original.keys.forEach { assertEquals("stream $it", original.getValue(it), joined.getValue(it)) }
        result.segments.forEach {
            assertTrue(it.file.name, MediaInspector.packets(it.file).first { p -> p.stream == VIDEO }.keyframe)
            assertDecodes(it.file)
        }
    }

    private fun assertDecodes(file: File) {
        MediaInspector.inspect(file).streams.forEach {
            assertEquals("${file.name} $it", 0, it.decodeErrors)
            assertTrue("${file.name} $it", it.frames > 0)
        }
    }

    /** The end can pass the requested one by what B-frame reordering and the last frame's duration add. */
    private fun assertEndNear(range: TimeRange, requestedEnd: Duration) {
        val end = range.endExclusive!!
        assertTrue("$range should end at or after $requestedEnd", end >= requestedEnd)
        assertTrue("$range should end within 200 ms of $requestedEnd", end - requestedEnd < 200.milliseconds)
    }

    private fun packetCounts(file: File) = MediaInspector.packets(file).groupingBy { it.stream }.eachCount()

    private fun sample(name: String): File {
        val file = File(dir, name)
        if (!file.exists()) {
            InstrumentationRegistry.getInstrumentation().context.assets.open("media/$name").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return file
    }

    private companion object {
        const val LONG_GOP = "av_long_gop.mp4"
        const val VFR = "av_vfr.mp4"
        const val OFFSET = "av_offset.ts"
        const val ADTS = "audio_aac.aac"
        const val VIDEO = 0

        /** 1024 samples at 44.1 kHz, rounded up. */
        val AAC_FRAME = 23_220.microseconds
    }
}
