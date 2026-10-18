package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.Session.State
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session state machine (roadmap 5.1). */
class SessionTest {

    private val transitions = mutableListOf<State>()
    private var now = 0L

    private fun session() = Session("trim", clock = { now }, listener = { _, state -> synchronized(transitions) { transitions += state } })

    @Test
    fun succeedsThroughRunning() {
        val session = session()
        now = 5_000_000
        assertTrue(session.start())
        now = 12_000_000
        assertTrue(session.succeed("result"))
        assertEquals(listOf(State.QUEUED, State.RUNNING, State.SUCCEEDED), transitions)
        assertEquals(State.SUCCEEDED, session.state)
        assertEquals("result", session.value)
        assertNull(session.failure)
        assertEquals(7.milliseconds, session.elapsed)
    }

    @Test
    fun cancellationExceptionEndsAsCancelledAnythingElseAsFailed() {
        val cancelled = session().apply { start() }
        val cancellation = CancellationException("cancelled")
        cancelled.fail(cancellation)
        assertEquals(State.CANCELLED, cancelled.state)
        assertSame(cancellation, cancelled.failure)

        val failed = session().apply { start() }
        val error = FFmpegException(FFmpegException.Reason.IO, "disk full")
        failed.fail(error)
        assertEquals(State.FAILED, failed.state)
        assertSame(error, failed.failure)
        assertNull(failed.value)
    }

    @Test
    fun cancelledWhileQueuedNeverRuns() {
        val session = session()
        session.fail(CancellationException())
        assertFalse(session.start())
        assertEquals(listOf(State.QUEUED, State.CANCELLED), transitions)
        assertNull(session.elapsed)
    }

    @Test
    fun theTerminalStateIsReportedOnce() {
        val session = session().apply { start() }
        assertTrue(session.fail(IOException("first")))
        assertFalse(session.succeed("late"))
        assertFalse(session.fail(CancellationException()))
        assertFalse(session.start())
        assertEquals(listOf(State.QUEUED, State.RUNNING, State.FAILED), transitions)
        assertEquals("first", session.failure!!.message)
    }

    @Test
    fun racingEndsHaveExactlyOneWinner() {
        repeat(200) {
            transitions.clear()
            val session = session().apply { start() }
            val threads = 4
            val barrier = CyclicBarrier(threads)
            val wins = AtomicInteger()
            val done = CountDownLatch(threads)
            repeat(threads) { i ->
                thread {
                    barrier.await()
                    val won = when (i % 3) {
                        0 -> session.succeed(i)
                        1 -> session.fail(CancellationException())
                        else -> session.fail(IOException())
                    }
                    if (won) wins.incrementAndGet()
                    done.countDown()
                }
            }
            done.await()
            assertEquals(1, wins.get())
            assertEquals(1, transitions.count { it.isTerminal })
            assertTrue(session.state.isTerminal)
        }
    }

    @Test
    fun idsAreUnique() {
        val ids = (1..1_000).map { Session("probe", listener = null).id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
