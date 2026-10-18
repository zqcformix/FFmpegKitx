package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.Session.State
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Concurrency slots and limits (roadmap 5.4). */
class OperationQueueTest {

    @After
    fun tearDown() {
        FFmpeg.sessionListener = null
    }

    @Test
    fun noMoreThanTheLimitRun() = runBlocking {
        val queue = OperationQueue { 2 }
        val running = AtomicInteger()
        var peak = 0
        (0 until 8).map {
            launch(Dispatchers.Default) {
                queue.withSlot {
                    val now = running.incrementAndGet()
                    synchronized(this@OperationQueueTest) { peak = maxOf(peak, now) }
                    delay(20)
                    running.decrementAndGet()
                }
            }
        }.forEach { it.join() }
        assertEquals(2, peak)
        assertEquals(0, queue.runningCount)
        assertEquals(0, queue.waitingCount)
    }

    @Test
    fun waitingCallsStartInCallOrder() = runBlocking {
        // With one slot each grant follows the previous operation's end, so the order is observable.
        val queue = OperationQueue { 1 }
        val started = Collections.synchronizedList(mutableListOf<Int>())
        (0 until 8).map { i ->
            launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                queue.withSlot {
                    started += i
                    delay(5)
                }
            }
        }.forEach { it.join() }
        assertEquals((0 until 8).toList(), started)
    }

    @Test
    fun aCancelledWaiterNeverRunsAndFreesItsPlace() = runBlocking {
        val queue = OperationQueue { 1 }
        val hold = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) { queue.withSlot { hold.await() } }
        while (queue.runningCount == 0) yield()
        var ran = false
        val waiter = launch(Dispatchers.Default) { queue.withSlot { ran = true } }
        while (queue.waitingCount == 0) yield()
        waiter.cancelAndJoin()
        assertEquals(0, queue.waitingCount)
        hold.complete(Unit)
        first.join()
        assertFalse(ran)
        assertEquals(0, queue.runningCount)
        assertEquals("next", queue.withSlot { "next" })
    }

    @Test
    fun aSlotHandedToACancelledWaiterPassesOn() = runBlocking {
        // Releases and cancellations race; afterwards every slot must be free again.
        repeat(200) {
            val queue = OperationQueue { 1 }
            val hold = CompletableDeferred<Unit>()
            val first = launch(Dispatchers.Default) { queue.withSlot { hold.await() } }
            while (queue.runningCount == 0) yield()
            val waiters = (0 until 3).map { launch(Dispatchers.Default) { queue.withSlot { } } }
            while (queue.waitingCount < 3) yield()
            hold.complete(Unit)
            waiters[0].cancel()
            first.join()
            waiters.forEach { it.join() }
            assertEquals(0, queue.runningCount)
            assertEquals(0, queue.waitingCount)
        }
    }

    @Test
    fun aFailingOperationFreesItsSlot() = runBlocking {
        val queue = OperationQueue { 1 }
        assertThrows(IllegalStateException::class.java) { runBlocking { queue.withSlot { error("boom") } } }
        assertEquals(0, queue.runningCount)
        assertEquals(1, queue.withSlot { 1 })
    }

    @Test
    fun aLargerLimitStartsWaitingCallsAtOnce() = runBlocking {
        var limit = 1
        val queue = OperationQueue { limit }
        val hold = CompletableDeferred<Unit>()
        val jobs = (0 until 3).map { launch(Dispatchers.Default) { queue.withSlot { hold.await() } } }
        while (queue.runningCount + queue.waitingCount < 3) yield()
        assertEquals(1, queue.runningCount)
        limit = 3
        queue.limitChanged()
        assertEquals(3, queue.runningCount)
        hold.complete(Unit)
        jobs.forEach { it.join() }
    }

    @Test
    fun aSessionCancelledInTheQueueEndsCancelledWithoutRunning() = runBlocking {
        val transitions = Collections.synchronizedList(mutableListOf<Pair<Session, State>>())
        FFmpeg.sessionListener = { session, state -> transitions += session to state }
        val queue = OperationQueue { 1 }
        val hold = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) { queue.withSlot { hold.await() } }
        while (queue.runningCount == 0) yield()
        var outcome: Throwable? = null
        val queued = launch(Dispatchers.Default) {
            try {
                runNative("trim", newTask = { error("no task is created while queued") }, queue = queue) { }
            } catch (t: Throwable) {
                outcome = t
            }
        }
        while (queue.waitingCount == 0) yield()
        queued.cancelAndJoin()
        hold.complete(Unit)
        first.join()
        assertTrue("got $outcome", outcome is CancellationException)
        assertEquals(listOf(State.QUEUED, State.CANCELLED), transitions.map { it.second })
    }

    @Test
    fun parallelCallsThroughTheSharedQueueAllFinish() = runBlocking {
        FFmpeg.limits = OperationLimits(maxConcurrentOperations = 2)
        try {
            val results = (0 until 6).map { i ->
                async(Dispatchers.Default) { runNative("probe", newTask = { NoopTask() }) { i } }
            }.awaitAll()
            assertEquals((0 until 6).toList(), results)
            assertEquals(0, FFmpeg.queue.runningCount)
        } finally {
            FFmpeg.limits = OperationLimits()
        }
    }

    @Test
    fun limitsAreValidatedAndShareTheBufferBudget() {
        val defaults = OperationLimits()
        assertEquals(4, defaults.maxConcurrentOperations)
        assertEquals(128L * 1024 * 1024, defaults.maxBufferedBytes)
        assertEquals(32L * 1024 * 1024, defaults.bufferedBytesPerOperation)
        assertThrows(IllegalArgumentException::class.java) { OperationLimits(maxConcurrentOperations = 0) }
        assertThrows(IllegalArgumentException::class.java) { OperationLimits(maxConcurrentOperations = 65) }
        assertThrows(IllegalArgumentException::class.java) { OperationLimits(maxBufferedBytes = 1024) }
    }

    private class NoopTask : Task {
        override val nativeHandle = 0L
        override val isCancelled = false
        override val delivery = DeliveryMeter()
        override val rollbacks = Rollbacks()
        override fun cancel() {}
        override fun onCancel(action: () -> Unit) = java.io.Closeable {}
        override fun progress(): TaskProgress? = null
        override fun setSegment(index: Int) {}
        override fun redact(path: String?, placeholder: String) {}
        override fun log() = MediaLog.EMPTY
        override fun close() {}
    }
}
