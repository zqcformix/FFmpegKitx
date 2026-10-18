package io.github.nova.ffmpegkit

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roadmap 5.3: a read stuck in its provider can be cancelled or times out, and a call that ends in
 * a cancellation leaves no outputs, even when the cancellation arrives after delivery.
 */
@RunWith(AndroidJUnit4::class)
class InterruptibleIoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var dir: File
    private val threads = mutableListOf<HandlerThread>()
    private val releases = mutableListOf<CountDownLatch>()

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "io-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        FFmpeg.readTimeout = null
        scope.cancel()
        releases.forEach { it.countDown() } // let stuck provider reads finish
        threads.forEach { it.quitSafely() }
        dir.deleteRecursively()
    }

    @Test
    fun aStuckReadDuringATrimIsCancelledPromptly() {
        val output = File(dir, "clip.mp4")
        assertCancelsPromptly({ input -> FFmpeg.trim(TrimRequest(input, MediaOutput.of(output), TimeRange.from(0.seconds))) }) {
            dir.list()!!.toSet() - AV
        }
    }

    @Test
    fun aStuckReadDuringASplitIsCancelledPromptly() {
        val out = File(dir, "segments").apply { mkdirs() }
        assertCancelsPromptly({ input -> FFmpeg.split(SplitRequest.byDuration(input, out, 1.seconds, "clip", "mp4")) }) {
            out.list()!!.toSet()
        }
    }

    @Test
    fun aStuckReadWhileOpeningIsCancelledPromptly() {
        // The very first read hangs: FFmpeg is still probing the format.
        assertCancelsPromptly({ input -> FFmpeg.probe(input) }, hangAfterReads = 0) { emptySet() }
    }

    @Test
    fun aStuckReadTimesOut() {
        FFmpeg.readTimeout = 300.milliseconds
        val (descriptor, _) = stuckInput(hangAfterReads = 2)
        val started = System.nanoTime()
        val e = assertThrows(FFmpegException::class.java) {
            runBlocking { FFmpeg.trim(TrimRequest(MediaInput.of(descriptor), MediaOutput.of(File(dir, "clip.mp4")), TimeRange.from(0.seconds))) }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        Log.i("FFmpegKitTest", "read timeout after $elapsedMs ms: $e")
        assertEquals(Reason.IO, e.reason)
        assertEquals(AvError.ETIMEDOUT, e.nativeErrorCode)
        assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
        assertEquals(setOf(AV), dir.list()!!.toSet())
    }

    @Test
    fun withoutATimeoutASlowReadStillSucceeds() {
        // Each read takes 400 ms, longer than the timeout used above; the default has no limit.
        val (descriptor, release) = stuckInput(hangAfterReads = Int.MAX_VALUE, delayMs = 400)
        release.countDown()
        val result = runBlocking { FFmpeg.trim(TrimRequest(MediaInput.of(descriptor), MediaOutput.of(File(dir, "clip.mp4")), TimeRange(2.seconds, 4.seconds))) }
        assertEquals(TimeRange(2.seconds, 4.seconds), result.requestedRange)
    }

    @Test
    fun aVeryLongTimeoutIsNoShorterThanItsLength() {
        // 1,000 years in microseconds overflowed once converted to a nanosecond deadline, which made every
        // read slower than the 20 ms poll interval time out.
        FFmpeg.readTimeout = (1_000 * 365).days
        val (descriptor, release) = stuckInput(hangAfterReads = Int.MAX_VALUE, delayMs = 100)
        release.countDown()
        val result = runBlocking { FFmpeg.trim(TrimRequest(MediaInput.of(descriptor), MediaOutput.of(File(dir, "clip.mp4")), TimeRange(2.seconds, 4.seconds))) }
        assertEquals(TimeRange(2.seconds, 4.seconds), result.requestedRange)
    }

    @Test
    fun aCancellationAfterDeliveryRemovesTheTrimOutput() {
        val output = File(dir, "clip.mp4")
        assertLateCancellationLeavesNothing({ onProgress -> FFmpeg.trim(TrimRequest(MediaInput.of(sample(AV)), MediaOutput.of(output), TimeRange(2.seconds, 4.seconds)), onProgress) }) {
            dir.list()!!.toSet() - AV
        }
    }

    @Test
    fun aCancellationAfterDeliveryRemovesTheSegments() {
        val out = File(dir, "segments").apply { mkdirs() }
        assertLateCancellationLeavesNothing({ onProgress -> FFmpeg.split(SplitRequest.byDuration(MediaInput.of(sample(AV)), out, 3.seconds, "clip", "mp4"), onProgress) }) {
            out.list()!!.toSet()
        }
    }

    /**
     * Starts [operation] on an input whose reads hang after [hangAfterReads] reads, waits until one
     * hangs, cancels, and checks that the call ends within a second as a plain cancellation with no
     * [leftover] files, although the provider's read is still blocked.
     */
    private fun assertCancelsPromptly(operation: suspend (MediaInput) -> Any, hangAfterReads: Int = 2, leftover: () -> Set<String>) {
        val (descriptor, _, hanging) = stuckInputWithSignal(hangAfterReads)
        var failure: Throwable? = null
        val job = scope.launch {
            try {
                operation(MediaInput.of(descriptor))
            } catch (t: Throwable) {
                failure = t
                throw t
            }
        }
        assertTrue("no read hung", hanging.await(10, TimeUnit.SECONDS))
        val started = System.nanoTime()
        runBlocking { job.cancelAndJoin() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        Log.i("FFmpegKitTest", "cancelled a stuck read in $elapsedMs ms")
        assertTrue("took $elapsedMs ms", elapsedMs < 1_000)
        assertTrue("expected a CancellationException, got $failure", failure is CancellationException)
        assertTrue(failure !is FFmpegException)
        assertEquals(emptySet<String>(), leftover())
    }

    /**
     * Runs [operation]; its final progress report (1.0, outputs delivered) blocks until the test has
     * cancelled the call. The call must then end as a cancellation with nothing left in [leftover].
     */
    private fun assertLateCancellationLeavesNothing(operation: suspend ((MediaProgress) -> Unit) -> Any, leftover: () -> Set<String>) {
        val delivered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var failure: Throwable? = null
        var leftAfterDelivery = emptySet<String>()
        val job = scope.launch {
            try {
                operation { progress ->
                    if (progress.fraction == 1.0) {
                        leftAfterDelivery = leftover()
                        delivered.countDown()
                        release.await()
                    }
                }
            } catch (t: Throwable) {
                failure = t
                throw t
            }
        }
        assertTrue(delivered.await(10, TimeUnit.SECONDS))
        assertTrue("nothing was delivered", leftAfterDelivery.isNotEmpty())
        job.cancel()
        release.countDown()
        runBlocking { job.join() }
        assertTrue("expected a CancellationException, got $failure", failure is CancellationException)
        assertEquals(emptySet<String>(), leftover())
    }

    private fun stuckInput(hangAfterReads: Int, delayMs: Long = 0): Pair<ParcelFileDescriptor, CountDownLatch> =
        stuckInputWithSignal(hangAfterReads, delayMs).let { (descriptor, release, _) -> descriptor to release }

    /**
     * The A/V sample through a seekable descriptor whose reads after the first [hangAfterReads] block
     * until the returned release latch opens (at the latest in tearDown). The third value opens when
     * a read starts hanging.
     */
    private fun stuckInputWithSignal(hangAfterReads: Int, delayMs: Long = 0): Triple<ParcelFileDescriptor, CountDownLatch, CountDownLatch> {
        val bytes = sample(AV).readBytes()
        val release = CountDownLatch(1).also { releases += it }
        val hanging = CountDownLatch(1)
        val reads = AtomicInteger()
        val thread = HandlerThread("stuck-provider").apply { start() }.also { threads += it }
        val descriptor = context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = bytes.size.toLong()

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (reads.getAndIncrement() >= hangAfterReads) {
                        hanging.countDown()
                        release.await()
                    }
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val count = minOf(size.toLong(), bytes.size - offset).toInt().coerceAtLeast(0)
                    System.arraycopy(bytes, offset.toInt(), data, 0, count)
                    return count
                }

                override fun onRelease() {}
            },
            Handler(thread.looper),
        )
        return Triple(descriptor, release, hanging)
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
