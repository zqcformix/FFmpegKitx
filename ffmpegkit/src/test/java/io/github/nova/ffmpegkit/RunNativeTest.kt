package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.Session.State
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** runNative with a fake task: session states, progress delivery, cancellation and failure priority (roadmap 5.1, 5.2). */
class RunNativeTest {

    private val transitions = Collections.synchronizedList(mutableListOf<Pair<Session, State>>())

    @Before
    fun setUp() {
        FFmpeg.sessionListener = { session, state -> transitions += session to state }
    }

    @After
    fun tearDown() {
        FFmpeg.sessionListener = null
    }

    @Test
    fun progressArrivesOnWorkerThreadsAndEndsWithCompletionBeforeTheCallReturns() {
        val task = FakeTask()
        val reported = Collections.synchronizedList(mutableListOf<Pair<Thread, MediaProgress>>())
        val caller = Thread.currentThread()
        val result = runBlocking {
            runNative("trim", reporter { reported += Thread.currentThread() to it }, { task }) { t -> (t as FakeTask).work(steps = 10) }
        }
        val countWhenReturned = reported.size
        Thread.sleep(3 * PROGRESS_INTERVAL.inWholeMilliseconds)

        assertEquals("done", result)
        assertEquals("callbacks after the call returned", countWhenReturned, reported.size)
        assertTrue("only ${reported.size} reports", reported.size >= 3)
        assertTrue(reported.none { it.first == caller })
        val fractions = reported.map { it.second.fraction!! }
        assertEquals(fractions.sorted(), fractions)
        assertEquals(1.0, fractions.last(), 0.0)
        assertTrue(fractions.dropLast(1).all { it <= 0.99 })
        assertTrue(task.closed)
        assertEquals(listOf(State.QUEUED, State.RUNNING, State.SUCCEEDED), transitions.map { it.second })
        assertEquals("done", transitions.last().first.value)
    }

    @Test
    fun aFailingCallbackStopsTheWorkAndItsExceptionPropagates() {
        val task = FakeTask()
        val failure = IllegalStateException("callback failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { runNative("trim", reporter { throw failure }, { task }) { t -> (t as FakeTask).work(steps = 50) } }
        }
        assertSameOrRecovered(failure, thrown)
        assertTrue(task.isCancelled)
        assertTrue("work ran ${task.steps} of 50 steps", task.steps < 50)
        assertTrue(task.cleanedUp)
        val session = transitions.last().first
        assertEquals(State.FAILED, session.state)
        assertSameOrRecovered(failure, session.failure!!)
    }

    @Test
    fun cancellationWaitsForCleanupAndWinsOverTheFailureItCaused() {
        val task = FakeTask()
        var outcome: Throwable? = null
        val reported = Collections.synchronizedList(mutableListOf<MediaProgress>())
        runBlocking {
            val job = launch(Dispatchers.Default) {
                try {
                    runNative("split", reporter { reported += it }, { task }) { t -> (t as FakeTask).work(steps = 50) }
                } catch (t: Throwable) {
                    outcome = t
                    throw t
                }
            }
            while (task.steps < 3) Thread.sleep(5)
            job.cancelAndJoin()
            assertTrue("returned before the work cleaned up", task.cleanedUp)
        }
        val countWhenEnded = reported.size
        Thread.sleep(3 * PROGRESS_INTERVAL.inWholeMilliseconds)
        assertEquals(countWhenEnded, reported.size)
        // The work failed with an FFmpegException because it was interrupted; the call ends as a cancellation.
        assertTrue("got $outcome", outcome is CancellationException)
        assertFalse(outcome is FFmpegException)
        assertTrue(task.steps < 50)
        assertTrue(reported.none { it.fraction == 1.0 })
        assertEquals(listOf(State.QUEUED, State.RUNNING, State.CANCELLED), transitions.map { it.second })
    }

    @Test
    fun aFailureWithoutCancellationEndsAsFailed() {
        val error = FFmpegException(FFmpegException.Reason.INVALID_MEDIA, "broken")
        val thrown = assertThrows(FFmpegException::class.java) {
            runBlocking { runNative("probe", newTask = { FakeTask() }) { throw error } }
        }
        assertSameOrRecovered(error, thrown)
        assertEquals(listOf(State.QUEUED, State.RUNNING, State.FAILED), transitions.map { it.second })
        assertSameOrRecovered(error, transitions.last().first.failure!!)
    }

    @Test
    fun anFFmpegExceptionCarriesTheTaskLog() {
        val log = NativeLog(intArrayOf(16), arrayOf(ByteArray(0)), arrayOf("moov atom not found".toByteArray()), 0).toMediaLog(null)
        val task = FakeTask().apply { logged = log }
        val thrown = assertThrows(FFmpegException::class.java) {
            runBlocking { runNative("trim", newTask = { task }) { throw FFmpegException(FFmpegException.Reason.INVALID_MEDIA, "broken") } }
        }
        assertSame(log, (thrown.cause as? FFmpegException ?: thrown).log)
    }

    @Test
    fun cancelledWhileQueuedNeverRuns() {
        var ran = false
        runBlocking {
            launch(start = CoroutineStart.UNDISPATCHED) {
                cancel()
                runNative("trim", newTask = { FakeTask() }) { ran = true }
            }.join()
        }
        assertFalse(ran)
        assertEquals(listOf(State.QUEUED, State.CANCELLED), transitions.map { it.second })
    }

    @Test
    fun parallelSessionsKeepTheirOwnProgressAndResults() {
        val count = 8
        val reports = List(count) { Collections.synchronizedList(mutableListOf<MediaProgress>()) }
        val results = runBlocking {
            (0 until count).map { i ->
                async(Dispatchers.Default) {
                    val task = FakeTask(durationUs = (i + 1) * 1_000_000L)
                    runNative("trim#$i", reporter { reports[i] += it }, { task }) { t -> (t as FakeTask).work(steps = 6, result = i) }
                }
            }.awaitAll()
        }
        assertEquals((0 until count).toList(), results)
        for (i in 0 until count) {
            assertTrue(reports[i].isNotEmpty())
            assertTrue("session $i saw another task's progress", reports[i].all { it.total!!.inWholeMicroseconds == (i + 1) * 1_000_000L })
            assertEquals(1.0, reports[i].last().fraction!!, 0.0)
        }
        val bySession = transitions.groupBy({ it.first }, { it.second })
        assertEquals(count, bySession.size)
        assertEquals(count, bySession.keys.map { it.id }.toSet().size)
        for ((session, states) in bySession) {
            assertEquals(listOf(State.QUEUED, State.RUNNING, State.SUCCEEDED), states)
            assertEquals("trim#${session.value}", session.operation)
        }
    }

    /**
     * With assertions enabled (as in tests), kotlinx-coroutines' stack trace recovery may rethrow a copy
     * of an exception with the original as its cause; without them the original arrives.
     */
    private fun assertSameOrRecovered(expected: Throwable, actual: Throwable) {
        assertTrue("got $actual", actual === expected || (actual.javaClass == expected.javaClass && actual.cause === expected))
    }

    private fun reporter(callback: (MediaProgress) -> Unit) = ProgressReporter(ProgressTimeline.WHOLE_INPUT, copies = false, callback)

    /** Stands in for native work: each step takes 30 ms and writes 1/10 of the duration's worth of media. */
    private class FakeTask(private val durationUs: Long? = 1_000_000) : Task {
        override val nativeHandle = 0L
        override val delivery = DeliveryMeter()

        @Volatile
        override var isCancelled = false
            private set

        @Volatile
        var steps = 0

        @Volatile
        var processedUs = 0L

        @Volatile
        var cleanedUp = false

        @Volatile
        var closed = false

        override fun cancel() {
            isCancelled = true
        }

        override fun progress(): TaskProgress? =
            if (closed) null else TaskProgress(processedUs, durationUs, processedUs / 100, 1, delivery.expectedBytes, delivery.deliveredBytes)

        override fun setSegment(index: Int) {}

        override fun redact(path: String?, placeholder: String) {}

        var logged: MediaLog = MediaLog.EMPTY

        override fun log(): MediaLog = logged

        override fun close() {
            closed = true
        }

        fun <T> work(steps: Int, result: T): T {
            try {
                repeat(steps) {
                    // Like native code: interruption surfaces as an ordinary failure.
                    if (isCancelled) throw FFmpegException(FFmpegException.Reason.NATIVE, "interrupted")
                    Thread.sleep(30)
                    this.steps++
                    processedUs = minOf((durationUs ?: Long.MAX_VALUE), processedUs + (durationUs ?: 1_000_000) / 10)
                }
                delivery.expect(1_000)
                delivery.delivered(1_000)
                return result
            } finally {
                Thread.sleep(50) // cleanup takes a while
                cleanedUp = true
            }
        }

        fun work(steps: Int): String = work(steps, "done")
    }
}
