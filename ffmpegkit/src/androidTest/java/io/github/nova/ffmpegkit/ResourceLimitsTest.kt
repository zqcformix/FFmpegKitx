package io.github.nova.ffmpegkit

import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.Session.State
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Concurrency limit, buffer budget and resource use over many operations (roadmap 5.4). */
@RunWith(AndroidJUnit4::class)
class ResourceLimitsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "limits-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        FFmpeg.limits = OperationLimits()
        FFmpeg.sessionListener = null
        dir.deleteRecursively()
    }

    @Test
    fun noMoreThanTheLimitRunAtOnce() {
        FFmpeg.limits = OperationLimits(maxConcurrentOperations = 2)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val queuedFirst = AtomicInteger()
        FFmpeg.sessionListener = { _, state ->
            when (state) {
                State.RUNNING -> peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                State.SUCCEEDED, State.FAILED, State.CANCELLED -> running.decrementAndGet()
                State.QUEUED -> queuedFirst.incrementAndGet()
            }
        }
        val input = sample(AV)
        val results = runBlocking {
            (0 until 12).map { i ->
                async(Dispatchers.Default) { input.trimTo(File(dir, "clip$i.mp4"), 2.seconds, 4.seconds) }
            }.awaitAll()
        }
        assertEquals(12, results.size)
        assertEquals(12, queuedFirst.get())
        assertTrue("peak ${peak.get()}", peak.get() in 1..2)
        assertEquals(0, FFmpeg.queue.runningCount)
    }

    @Test
    fun aLongGopTrimWithinASmallBudgetWritesTheSamePackets() {
        // Keyframes at 0, 4 and 8 s: a trim from 3.5 s holds 3.5 s of packets before it reaches the start.
        val input = sample(LONG_GOP)
        val range = TimeRange(3_500.milliseconds, 6.seconds)
        val unlimited = runBlocking { input.trimTo(File(dir, "unlimited.mp4"), range.start, range.endExclusive) }
        FFmpeg.limits = OperationLimits(maxConcurrentOperations = 64, maxBufferedBytes = 1024 * 1024) // 16 KB each
        val bounded = runBlocking { input.trimTo(File(dir, "bounded.mp4"), range.start, range.endExclusive) }
        assertEquals(unlimited.actualRange, bounded.actualRange)
        assertEquals(MediaInspector.packets(File(dir, "unlimited.mp4")), MediaInspector.packets(File(dir, "bounded.mp4")))
    }

    @Test
    fun anIndexlessTrimWithinASmallBudgetStillDecodes() {
        // MPEG-TS has no index: the buffer may have to give up on finding a later keyframe before the start.
        val input = sample(TS)
        val range = TimeRange(5_500.milliseconds, 7.seconds)
        val unlimited = runBlocking { input.trimTo(File(dir, "unlimited.mp4"), range.start, range.endExclusive) }
        FFmpeg.limits = OperationLimits(maxConcurrentOperations = 64, maxBufferedBytes = 1024 * 1024)
        val bounded = runBlocking { input.trimTo(File(dir, "bounded.mp4"), range.start, range.endExclusive) }
        Log.i("FFmpegKitTest", "TS trim: unlimited ${unlimited.actualRange}, bounded ${bounded.actualRange}")
        assertTrue(bounded.actualRange.start <= unlimited.actualRange.start)
        assertEquals(unlimited.actualRange.endExclusive, bounded.actualRange.endExclusive)
        assertEquals(0, MediaInspector.inspect(File(dir, "bounded.mp4")).streams.sumOf { it.decodeErrors })
    }

    @Test
    fun consecutiveOperationsDoNotGrowThreadsOrNativeMemory() {
        val input = sample(AV)
        val truncated = File(dir, "truncated.mp4").apply { writeBytes(input.readBytes().copyOf(100_000)) }
        val segments = File(dir, "segments").apply { mkdirs() }
        var n = 0
        fun round() = runBlocking {
            n++
            // Descriptor inputs exercise the reader thread; failures and probes take the other paths.
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                FFmpeg.trim(TrimRequest(MediaInput.of(descriptor), MediaOutput.of(File(dir, "clip$n.mp4")), TimeRange(2.seconds, 4.seconds))) { }
            }
            File(dir, "clip$n.mp4").delete()
            FFmpeg.split(SplitRequest.byDuration(MediaInput.of(input), segments, 3.seconds, "s$n", "mp4"))
            segments.listFiles()!!.forEach { it.delete() }
            input.probe()
            runCatching { truncated.trimTo(File(dir, "bad$n.mp4"), 0.seconds) }
        }
        repeat(10) { round() } // warm up thread pools and allocator
        val threadsBefore = threadCount()
        val heapBefore = nativeHeap()
        repeat(100) { round() }
        val threadsAfter = threadCount()
        val heapAfter = nativeHeap()
        Log.i("FFmpegKitTest", "100 rounds: threads $threadsBefore -> $threadsAfter, native heap $heapBefore -> $heapAfter bytes")
        assertTrue("threads grew from $threadsBefore to $threadsAfter", threadsAfter <= threadsBefore + 2)
        assertTrue("native heap grew by ${heapAfter - heapBefore} bytes", heapAfter - heapBefore < 8L * 1024 * 1024)
    }

    /** Threads of this process, including native ones (FdReader helpers). */
    private fun threadCount(): Int {
        Thread.sleep(200) // let finished helper threads exit
        return File("/proc/self/task").list()!!.size
    }

    private fun nativeHeap(): Long {
        Runtime.getRuntime().gc()
        return Debug.getNativeHeapAllocatedSize()
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
        const val LONG_GOP = "av_long_gop.mp4"
        const val TS = "av_offset.ts"
    }
}
