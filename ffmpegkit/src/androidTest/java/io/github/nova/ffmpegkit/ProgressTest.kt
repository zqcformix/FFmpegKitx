package io.github.nova.ffmpegkit

import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.Session.State
import java.io.File
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Progress callbacks and sessions on real media (roadmap 5.1, 5.2). Inputs are read through a
 * descriptor whose reads are slowed down, so that the 10 s samples take long enough for several
 * progress reports.
 */
@RunWith(AndroidJUnit4::class)
class ProgressTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var proxyThread: HandlerThread
    private val descriptors = mutableListOf<ParcelFileDescriptor>()

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "progress-test").apply { deleteRecursively(); mkdirs() }
        proxyThread = HandlerThread("proxy-fd").apply { start() }
    }

    @After
    fun tearDown() {
        FFmpeg.sessionListener = null
        descriptors.forEach { it.close() }
        proxyThread.quitSafely()
        dir.deleteRecursively()
    }

    @Test
    fun trimReportsProgressOnTheRequestedRange() {
        val output = File(dir, "clip.mp4")
        val reports = Reports()
        val result = runBlocking(Dispatchers.Main) {
            FFmpeg.trim(TrimRequest(slowInput(AV), MediaOutput.of(output), TimeRange(2_500.milliseconds, 8_500.milliseconds)), reports)
        }
        reports.log("trim")
        // MP4 has an index: after the seek the whole range arrives in one read and is written at once,
        // so only "header written" can be seen in between. The split tests show the counters moving.
        reports.assertWellFormed(midway = false)
        assertTrue(reports.toList().dropLast(1).all { it.progress.bytesWritten > 0 })
        assertTrue(reports.none { it.thread == Looper.getMainLooper().thread })
        assertTrue(reports.all { it.progress.total == 6.seconds && it.progress.segmentIndex == 1 })
        val last = reports.last().progress
        // The trim stops at the first packet whose decoding time reaches the end; writes end within a frame of it.
        assertTrue("processed ${last.processed}", last.processed > 5_900.milliseconds && last.processed <= 6.seconds)
        assertTrue("${last.bytesWritten} bytes for a ${output.length()} byte file", last.bytesWritten >= output.length())
        assertEquals(TimeRange(2_500.milliseconds, 8_500.milliseconds), result.requestedRange)
    }

    @Test
    fun splitByDurationReportsTheSegmentBeingWritten() {
        val out = File(dir, "segments").apply { mkdirs() }
        val reports = Reports()
        val result = runBlocking {
            FFmpeg.split(SplitRequest.byDuration(slowInput(AV), out, 3.seconds, "clip", "mp4"), reports)
        }
        reports.log("split")
        reports.assertWellFormed()
        val duration = runBlocking { File(dir, AV).probe() }.duration!!
        assertTrue(reports.all { it.progress.total == duration })
        val segments = reports.map { it.progress.segmentIndex }
        assertEquals(segments.sorted(), segments)
        assertEquals(result.segments.size, segments.last())
        assertTrue("segments seen: ${segments.toSet()}", segments.toSet().size > 1)
        assertTrue(reports.last().progress.bytesWritten >= result.segments.sumOf { it.file.length() })
    }

    @Test
    fun splitByRangesSumsTheRanges() {
        val out = File(dir, "segments").apply { mkdirs() }
        val ranges = listOf(TimeRange(6.seconds, 9.seconds), TimeRange(0.seconds, 2.seconds), TimeRange.from(8.seconds))
        val reports = Reports()
        runBlocking { FFmpeg.split(SplitRequest.byRanges(slowInput(AV), out, ranges, "clip", "mp4"), reports) }
        reports.log("split by ranges")
        reports.assertWellFormed()
        val duration = runBlocking { File(dir, AV).probe() }.duration!!
        assertTrue(reports.all { it.progress.total == 3.seconds + 2.seconds + (duration - 8.seconds) })
        val segments = reports.map { it.progress.segmentIndex }
        assertEquals(segments.sorted(), segments)
        assertEquals(3, segments.last())
    }

    @Test
    fun aCopiedDeliveryGetsTheSecondHalf() {
        // A Uri output is written to a cache file, then copied in: writing goes up to 0.5.
        val target = File(dir, "target.mp4").apply { createNewFile() }
        val reports = Reports()
        runBlocking {
            FFmpeg.trim(
                TrimRequest(slowInput(AV), MediaOutput.of(context.contentResolver, Uri.fromFile(target), "mp4"), TimeRange.from(0.seconds)),
                reports,
            )
        }
        reports.log("trim to Uri")
        reports.assertWellFormed()
        val writing = reports.toList().dropLast(1).map { it.progress.fraction!! }.filter { it < 0.99 }
        assertTrue("fractions while writing: $writing", writing.isNotEmpty() && writing.all { it <= 0.5 })
        assertTrue(reports.last().progress.bytesWritten >= target.length())
    }

    @Test
    fun unknownDurationGivesIndeterminateProgress() {
        // ADTS has no index; FFmpeg only estimates its duration from the bit rate, which counts as unknown.
        assertNull(runBlocking { sample(ADTS).probe() }.duration)
        val reports = Reports()
        runBlocking { FFmpeg.trim(TrimRequest(slowInput(ADTS), MediaOutput.of(File(dir, "clip.m4a")), TimeRange.from(2.seconds)), reports) }
        reports.log("trim ADTS")
        // The 83 KB input is read almost entirely while opening, so writing may finish between two polls.
        reports.assertWellFormed(midway = false)
        assertTrue(reports.all { it.progress.total == null })
        assertTrue(reports.toList().dropLast(1).all { it.progress.fraction == null })
        assertTrue(reports.last().progress.processed > 7.seconds)
    }

    @Test
    fun aThrowingCallbackStopsTheTrimAndPublishesNothing() {
        val output = File(dir, "clip.mp4")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                FFmpeg.trim(TrimRequest(slowInput(AV), MediaOutput.of(output), TimeRange.from(0.seconds))) {
                    throw IllegalStateException("stop")
                }
            }
        }
        assertEquals("stop", thrown.message)
        assertFalse(output.exists())
        assertEquals(setOf(AV), dir.list()!!.toSet()) // no temporary file left
    }

    @Test
    fun parallelTrimsKeepTheirOwnSessionsAndProgress() {
        val transitions = Collections.synchronizedList(mutableListOf<Pair<Session, State>>())
        FFmpeg.sessionListener = { session, state -> transitions += session to state }
        val ranges = listOf(
            TimeRange(0.seconds, 2.seconds),
            TimeRange(2.seconds, 5.seconds),
            TimeRange(5.seconds, 9.seconds),
            TimeRange.from(1.seconds),
        )
        val reports = ranges.map { Reports() }
        val inputs = ranges.map { slowInput(AV) }
        val results = runBlocking {
            ranges.indices.map { i ->
                async(Dispatchers.Default) {
                    FFmpeg.trim(TrimRequest(inputs[i], MediaOutput.of(File(dir, "clip$i.mp4")), ranges[i]), reports[i])
                }
            }.awaitAll()
        }
        val duration = runBlocking { File(dir, AV).probe() }.duration!!
        for (i in ranges.indices) {
            assertEquals(ranges[i], results[i].requestedRange)
            reports[i].assertWellFormed(midway = false) // the four inputs share one slow proxy thread
            val total = ranges[i].duration ?: (duration - ranges[i].start)
            assertTrue("trim $i saw another trim's progress", reports[i].all { it.progress.total == total })
        }
        val bySession = transitions.filter { it.first.operation == "trim" }.groupBy({ it.first }, { it.second })
        assertEquals(ranges.size, bySession.size)
        for ((session, states) in bySession) {
            assertEquals(listOf(State.QUEUED, State.RUNNING, State.SUCCEEDED), states)
            assertTrue(session.value in results)
            assertTrue(session.elapsed!! > Duration.ZERO)
        }
    }

    private class Report(val thread: Thread, val progress: MediaProgress)

    /** Collects reports; checks the guarantees every operation gives. */
    private class Reports : (MediaProgress) -> Unit, Iterable<Report> {
        private val reports = Collections.synchronizedList(mutableListOf<Report>())
        @Volatile private var inCallback = false

        override fun invoke(progress: MediaProgress) {
            check(!inCallback) { "callback ran concurrently with itself" }
            inCallback = true
            reports += Report(Thread.currentThread(), progress)
            inCallback = false
        }

        override fun iterator() = reports.toList().iterator()

        fun last() = reports.last()

        fun log(name: String) = Log.i("FFmpegKitTest", "$name: ${reports.size} reports, last ${reports.last().progress}")

        /**
         * [midway]: there must be a report from the middle of the writing, which shows that the native
         * counters move while the work runs. Every sample is read in about four reads, so only a few
         * distinct states exist; the reporter's own rules are covered by ProgressReporterTest.
         */
        fun assertWellFormed(midway: Boolean = true) {
            val all = reports.toList()
            if (midway) {
                assertTrue(
                    "no report during writing: ${all.map { it.progress }}",
                    all.dropLast(1).any { it.progress.processed > Duration.ZERO && it.progress.fraction?.let { f -> f < 0.99 } != false },
                )
            }
            assertEquals(1.0, all.last().progress.fraction)
            assertTrue(all.dropLast(1).all { (it.progress.fraction ?: 0.0) <= 0.99 })
            val fractions = all.mapNotNull { it.progress.fraction }
            assertEquals(fractions.sorted(), fractions)
            val processed = all.map { it.progress.processed }
            assertEquals(processed.sorted(), processed)
            val bytes = all.map { it.progress.bytesWritten }
            assertEquals(bytes.sorted(), bytes)
            val total = all.last().progress.total
            if (total != null) assertTrue(processed.all { it <= total })
        }
    }

    /**
     * [name] read through a descriptor whose reads each take 200 ms. A full read of a sample takes
     * about four reads, so progress changes a few times. (Short reads would end the file early.)
     * Closed after the test.
     */
    private fun slowInput(name: String): MediaInput {
        val bytes = sample(name).readBytes()
        val storage = context.getSystemService(StorageManager::class.java)
        val descriptor = storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = bytes.size.toLong()

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    Thread.sleep(200)
                    val count = minOf(size.toLong(), bytes.size - offset).toInt().coerceAtLeast(0)
                    System.arraycopy(bytes, offset.toInt(), data, 0, count)
                    return count
                }

                override fun onRelease() {}
            },
            Handler(proxyThread.looper),
        )
        descriptors += descriptor
        return MediaInput.of(descriptor)
    }

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
        const val AV = "av_h264_aac.mp4"
        const val ADTS = "audio_aac.aac"
    }
}
