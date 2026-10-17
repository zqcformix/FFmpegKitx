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
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The public suspend API (roadmap 4.2): threading, extensions, errors and cancellation. */
@RunWith(AndroidJUnit4::class)
class FFmpegApiTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var dir: File
    private lateinit var proxyThread: HandlerThread

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "api-test").apply { deleteRecursively(); mkdirs() }
        proxyThread = HandlerThread("proxy-fd").apply { start() }
    }

    @After
    fun tearDown() {
        FFmpeg.workerThreadObserver = null
        scope.cancel()
        proxyThread.quitSafely()
        dir.deleteRecursively()
    }

    @Test
    fun trimFromTheMainThreadRunsTheWorkElsewhere() {
        val sample = sample(AV)
        var worker: Thread? = null
        FFmpeg.workerThreadObserver = { worker = it }
        val result = runBlocking(Dispatchers.Main) {
            sample.trimTo(File(dir, "clip.mp4"), 2_500.milliseconds, 4_500.milliseconds)
        }
        assertNotSame(Looper.getMainLooper().thread, worker)
        assertEquals(TimeRange(2_500.milliseconds, 4_500.milliseconds), result.requestedRange)
        assertEquals(TimeRange(2.seconds, 4_633_333.microseconds), result.actualRange)
        assertEquals(0, MediaInspector.inspect(File(dir, "clip.mp4")).streams.sumOf { it.decodeErrors })
    }

    @Test
    fun openEndedTrimAndUriExtension() {
        val sample = sample(AV)
        val result = runBlocking { Uri.fromFile(sample).trimTo(context.contentResolver, File(dir, "clip.mp4"), 7_500.milliseconds) }
        assertEquals(TimeRange(7.seconds, 10.seconds), result.actualRange)
    }

    @Test
    fun splitExtension() {
        val out = File(dir, "segments").apply { mkdirs() }
        val result = runBlocking { sample(AV).splitTo(out, 3.seconds, "clip", "mp4") }
        assertEquals(listOf("clip-0001.mp4", "clip-0002.mp4", "clip-0003.mp4", "clip-0004.mp4"), result.segments.map { it.file.name })
        assertEquals(TimeRange(9.seconds, 10.seconds), result.segments.last().actualRange)
    }

    @Test
    fun invalidArgumentsThrowBeforeAnyWork() {
        val sample = sample(AV)
        assertThrows(IllegalArgumentException::class.java) {
            SplitRequest.byDuration(MediaInput.of(sample), dir, 3.seconds, "", "mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { sample.trimTo(File(dir, "clip.mp4"), 5.seconds, 4.seconds) }
        }
        assertEquals(setOf(AV), dir.list()!!.toSet())
    }

    @Test
    fun failuresArriveAsFFmpegException() {
        val e = assertThrows(FFmpegException::class.java) {
            runBlocking { File(dir, "missing.mp4").trimTo(File(dir, "clip.mp4"), 0.seconds, 1.seconds) }
        }
        assertEquals(Reason.INPUT_NOT_FOUND, e.reason)
    }

    @Test
    fun cancellingATrimStopsNativeWorkAndCleansUpFirst() {
        val output = File(dir, "clip.mp4")
        assertCancellationStopsWork(
            operation = { input -> FFmpeg.trim(TrimRequest(input, MediaOutput.of(output), TimeRange.from(0.seconds))) },
            leftover = { dir.list()!!.toSet() - AV },
        )
    }

    @Test
    fun cancellingASplitStopsNativeWorkAndPublishesNothing() {
        val out = File(dir, "segments").apply { mkdirs() }
        assertCancellationStopsWork(
            operation = { input -> FFmpeg.split(SplitRequest.byDuration(input, out, 1.seconds, "clip", "mp4")) },
            leftover = { out.list()!!.toSet() },
        )
    }

    @Test
    fun cancellingASplitByRangesStopsNativeWorkAndPublishesNothing() {
        val out = File(dir, "segments").apply { mkdirs() }
        val ranges = listOf(TimeRange(0.seconds, 3.seconds), TimeRange(3.seconds, 6.seconds), TimeRange.from(6.seconds))
        assertCancellationStopsWork(
            operation = { input -> FFmpeg.split(SplitRequest.byRanges(input, out, ranges, "clip", "mp4")) },
            leftover = { out.list()!!.toSet() },
        )
    }

    /**
     * Runs [operation] on a slow, seekable descriptor (each read takes 150 ms), cancels it after the
     * second read and checks that: the coroutine ends with a plain CancellationException; no reads
     * happen after it ended (native work had stopped); far fewer reads happened than a full run
     * needs; and [leftover] is empty when the coroutine has ended.
     */
    private fun assertCancellationStopsWork(operation: suspend (MediaInput) -> Any, leftover: () -> Set<String>) {
        val bytes = sample(AV).readBytes()
        val fullRunReads = AtomicInteger()
        proxy(bytes, fullRunReads, delayMs = 0).use { runBlocking { runCatching { operation(MediaInput.of(it)) } } }
        leftover().forEach { File(dir, it).deleteRecursively() }
        File(dir, "segments").listFiles()?.forEach { it.deleteRecursively() }

        val reads = AtomicInteger()
        var failure: Throwable? = null
        proxy(bytes, reads, delayMs = 150).use { descriptor ->
            val job = scope.launch {
                try {
                    operation(MediaInput.of(descriptor))
                } catch (t: Throwable) {
                    failure = t
                    throw t
                }
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (reads.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("operation never started reading", reads.get() >= 2)
            runBlocking { job.cancelAndJoin() }
            val readsWhenEnded = reads.get()
            Log.i("FFmpegKitTest", "cancellation: $readsWhenEnded reads when the coroutine ended, ${fullRunReads.get()} for a full run")
            Thread.sleep(500)
            assertEquals("reads continued after the coroutine ended", readsWhenEnded, reads.get())
            assertTrue("$readsWhenEnded reads after cancelling vs $fullRunReads for a full run", readsWhenEnded < fullRunReads.get())
        }
        assertTrue("expected a CancellationException, got $failure", failure is CancellationException)
        assertTrue(failure !is FFmpegException)
        assertEquals(emptySet<String>(), leftover())
    }

    /** A seekable descriptor over [bytes] whose reads are counted and delayed. */
    private fun proxy(bytes: ByteArray, reads: AtomicInteger, delayMs: Long): ParcelFileDescriptor {
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = bytes.size.toLong()

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    reads.incrementAndGet()
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val count = minOf(size.toLong(), bytes.size - offset).toInt().coerceAtLeast(0)
                    System.arraycopy(bytes, offset.toInt(), data, 0, count)
                    return count
                }

                override fun onRelease() {}
            },
            Handler(proxyThread.looper),
        )
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
    }
}
