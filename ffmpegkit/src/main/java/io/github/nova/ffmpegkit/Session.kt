package io.github.nova.ffmpegkit

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One operation's cancellation flag and progress counters, as the blocking work sees them. The work
 * passes [nativeHandle] to native calls; [NativeTask] is the real one, host tests use fakes.
 */
internal interface Task : Closeable {
    /** The remux::Task handle to pass to native functions; 0 for none. */
    val nativeHandle: Long

    val isCancelled: Boolean

    fun cancel()

    /** Bytes of the outputs delivered so far; written by [OutputDelivery] and [UriDelivery]. */
    val delivery: DeliveryMeter

    /** The progress counters so far, or null once closed. Callable from any thread. */
    fun progress(): TaskProgress?

    /** Sets the 1-based segment being written, for work that makes one native call per segment. */
    fun setSegment(index: Int)

    /** Throws [CancellationException] if [cancel] was called; checked before publishing outputs. */
    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("cancelled")
    }
}

/** A snapshot of remux::Task's counters (see remux.h) and of the task's [DeliveryMeter]. */
internal class TaskProgress(
    /** Media time written on the operation's target timeline, summed over its native calls. */
    val processedUs: Long,
    /** The input's duration; null while unknown or when FFmpeg only estimated it from the bit rate. */
    val durationUs: Long?,
    val bytesWritten: Long,
    val segment: Int,
    /** Bytes of written outputs waiting to be delivered or delivered; 0 until the writing is done. */
    val deliveryBytes: Long = 0,
    val deliveredBytes: Long = 0,
)

/**
 * A remux::Task shared with native code. Native work polls the cancellation flag through FFmpeg's
 * interrupt callback and between packets, then stops and cleans up; it writes the progress counters
 * as it goes.
 */
internal class NativeTask : Task {
    private val lock = Any()
    private var handle: Long = create()

    @Volatile
    override var isCancelled: Boolean = false
        private set

    override val nativeHandle: Long
        get() = synchronized(lock) { handle }

    override val delivery = DeliveryMeter()

    override fun cancel() {
        synchronized(lock) {
            isCancelled = true
            if (handle != 0L) cancel(handle)
        }
    }

    override fun progress(): TaskProgress? = synchronized(lock) {
        if (handle == 0L) return null
        val values = LongArray(4)
        progress(handle, values)
        TaskProgress(
            values[0],
            values[1].takeIf { it >= 0 },
            values[2],
            values[3].toInt(),
            delivery.expectedBytes,
            delivery.deliveredBytes,
        )
    }

    override fun setSegment(index: Int) {
        synchronized(lock) {
            if (handle != 0L) setSegment(handle, index)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) destroy(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("ffmpegkit")
        }

        @JvmStatic
        private external fun create(): Long

        @JvmStatic
        private external fun cancel(handle: Long)

        /** Fills [out] with processed_us, duration_us (-1: unknown), bytes_written, segment. */
        @JvmStatic
        private external fun progress(handle: Long, out: LongArray)

        @JvmStatic
        private external fun setSegment(handle: Long, segment: Int)

        @JvmStatic
        private external fun destroy(handle: Long)
    }
}

/**
 * The lifecycle of one editing call (roadmap 5.1): [State.QUEUED] until its work starts on a worker
 * thread, [State.RUNNING], then exactly one terminal state, which is how the call ended: it returned
 * ([State.SUCCEEDED]), threw a `CancellationException` ([State.CANCELLED]) or threw anything else
 * ([State.FAILED]). A session cancelled while queued goes straight to [State.CANCELLED].
 *
 * The terminal state is set only after the call's native resources and temporary files are released.
 * Which exception a call ends with is decided by [runNative]: work stopped by a cancellation request
 * ends as a cancellation even if the interruption made it fail.
 */
internal class Session(
    /** Names the operation, e.g. "trim". */
    val operation: String,
    private val clock: () -> Long = System::nanoTime,
    private val listener: ((Session, State) -> Unit)? = FFmpeg.sessionListener,
) {
    enum class State {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

        val isTerminal: Boolean get() = this >= SUCCEEDED
    }

    private class Snapshot(
        val state: State,
        val startedNanos: Long?,
        val endedNanos: Long?,
        val value: Any?,
        val failure: Throwable?,
    )

    /** Unique within the process. */
    val id: Long = nextId.incrementAndGet()

    private val snapshot = AtomicReference(Snapshot(State.QUEUED, null, null, null, null))

    init {
        listener?.invoke(this, State.QUEUED)
    }

    val state: State get() = snapshot.get().state

    /** The call's result once [State.SUCCEEDED]. */
    val value: Any? get() = snapshot.get().value

    /** What the call threw once [State.FAILED] or [State.CANCELLED]. */
    val failure: Throwable? get() = snapshot.get().failure

    /** Time from the work starting to the session ending; null until it ended, or if it never ran. */
    val elapsed: Duration?
        get() {
            val s = snapshot.get()
            return if (s.startedNanos != null && s.endedNanos != null) (s.endedNanos - s.startedNanos).nanoseconds else null
        }

    /** [State.QUEUED] → [State.RUNNING]; false if the session is no longer queued. */
    fun start(): Boolean = transition { s ->
        if (s.state != State.QUEUED) null else Snapshot(State.RUNNING, clock(), null, null, null)
    }

    /** Ends the session with [value]; false if it had already ended. */
    fun succeed(value: Any?): Boolean = end(State.SUCCEEDED, value, null)

    /** Ends the session with [failure]: cancelled for a `CancellationException`, else failed. False if it had already ended. */
    fun fail(failure: Throwable): Boolean =
        end(if (failure is CancellationException) State.CANCELLED else State.FAILED, null, failure)

    private fun end(state: State, value: Any?, failure: Throwable?): Boolean = transition { s ->
        if (s.state.isTerminal) null else Snapshot(state, s.startedNanos, clock(), value, failure)
    }

    /** Applies [next] atomically (null: not allowed from this state) and notifies the listener once. */
    private inline fun transition(next: (current: Snapshot) -> Snapshot?): Boolean {
        while (true) {
            val current = snapshot.get()
            val updated = next(current) ?: return false
            if (snapshot.compareAndSet(current, updated)) {
                listener?.invoke(this, updated.state)
                return true
            }
        }
    }

    override fun toString(): String = "Session($id $operation $state)"

    private companion object {
        val nextId = AtomicLong()
    }
}

/** How often [runNative] reads the native progress counters. */
internal val PROGRESS_INTERVAL = 100.milliseconds

/**
 * Runs blocking native [work] on [Dispatchers.IO] as one [Session]. If the calling coroutine is
 * cancelled, the native side is told to stop, and this only returns (by rethrowing the cancellation)
 * once [work] has finished cleaning up. Cancellation is never turned into an [FFmpegException].
 *
 * With a [progress] reporter, the task's counters are read every [PROGRESS_INTERVAL] while the work
 * runs, and [ProgressReporter.finish] reports completion after [work] returned (its outputs are
 * delivered by then). Callbacks run on IO threads, one at a time, and never after this returns. An
 * exception thrown by a callback stops the work and is rethrown as it is.
 */
internal suspend fun <T> runNative(
    operation: String,
    progress: ProgressReporter? = null,
    newTask: () -> Task = ::NativeTask,
    work: (Task) -> T,
): T {
    val session = Session(operation)
    val result = runCatching { newTask().use { task -> run(session, task, progress, work) } }
    result.fold({ session.succeed(it) }, { session.fail(it) })
    return result.getOrThrow()
}

private suspend fun <T> run(session: Session, task: Task, progress: ProgressReporter?, work: (Task) -> T): T = coroutineScope {
    val job = async(Dispatchers.IO) {
        session.start()
        progress?.start()
        try {
            work(task).also { task.progress()?.let { progress?.finish(it) } }
        } catch (e: Exception) {
            // Work stopped by cancel() must end as a cancellation, not as the error it caused.
            task.throwIfCancelled()
            throw e
        }
    }
    val poller = progress?.let { reporter ->
        launch(Dispatchers.IO) {
            while (true) {
                delay(PROGRESS_INTERVAL)
                val counters = task.progress() ?: break
                try {
                    reporter.report(counters)
                } catch (e: Throwable) {
                    task.cancel() // the callback failed: stop the work; coroutineScope rethrows `e`
                    throw e
                }
            }
        }
    }
    try {
        job.await().also { poller?.cancel() }
    } catch (e: CancellationException) {
        task.cancel()
        throw e // coroutineScope still waits for `job` to finish its cleanup
    }
}
