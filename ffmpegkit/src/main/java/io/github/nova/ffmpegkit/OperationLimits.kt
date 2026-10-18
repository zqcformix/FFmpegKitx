package io.github.nova.ffmpegkit

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Bounds on what editing operations may use at once (roadmap 5.4); set with [FFmpeg.limits].
 *
 * At most [maxConcurrentOperations] trims, splits and probes run at the same time; later calls wait
 * in call order and can be cancelled while waiting. Each running operation may buffer at most
 * `maxBufferedBytes / maxConcurrentOperations` bytes of packets: a trim holds packets from the
 * keyframe before its start until it reaches the start, which for an input with very long GOPs could
 * otherwise be most of the file. A trim that reaches its share starts at the keyframe it has found
 * so far; it never fails because of the limit.
 *
 * An operation whose read is stuck in its provider holds its slot until it is cancelled or
 * [FFmpeg.readTimeout] (no limit by default) expires; if every slot is held that way, later calls
 * wait. Call operations from a scope that gets cancelled, such as `viewModelScope`, or set a timeout.
 *
 * Nothing is kept after an operation ends: there is no session history, and an operation's
 * [MediaLog] is bounded by [MediaLog.Options.maxEntries].
 *
 * @throws IllegalArgumentException if [maxConcurrentOperations] is not in 1..64 or [maxBufferedBytes]
 *   is below 1 MiB.
 */
class OperationLimits(
    val maxConcurrentOperations: Int = 4,
    val maxBufferedBytes: Long = 128L * 1024 * 1024,
) {
    init {
        require(maxConcurrentOperations in 1..MAX_OPERATIONS) {
            "maxConcurrentOperations must be in 1..$MAX_OPERATIONS: $maxConcurrentOperations"
        }
        require(maxBufferedBytes >= MIN_BUFFERED_BYTES) { "maxBufferedBytes must be at least 1 MiB: $maxBufferedBytes" }
    }

    /** What one running operation may buffer. */
    internal val bufferedBytesPerOperation: Long
        get() = maxBufferedBytes / maxConcurrentOperations

    override fun toString(): String =
        "OperationLimits(maxConcurrentOperations=$maxConcurrentOperations, maxBufferedBytes=$maxBufferedBytes)"

    private companion object {
        // Dispatchers.IO's default parallelism; more could not run at once anyway.
        const val MAX_OPERATIONS = 64
        const val MIN_BUFFERED_BYTES = 1024L * 1024
    }
}

/**
 * First-come, first-served slots for running operations; [limit] is read on every change, so a new
 * [FFmpeg.limits] applies to waiting calls as well. Waiting is cancellable, and a slot handed to a
 * call that is cancelled at that moment passes on to the next one.
 */
internal class OperationQueue(private val limit: () -> Int) {
    private val lock = Any()
    private var running = 0
    private val waiting = ArrayDeque<CompletableDeferred<Unit>>()

    /** Operations holding a slot; for tests. */
    val runningCount: Int get() = synchronized(lock) { running }

    /** Operations waiting for a slot; for tests. */
    val waitingCount: Int get() = synchronized(lock) { waiting.size }

    suspend fun <T> withSlot(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /** Lets waiting calls start if the limit grew. */
    fun limitChanged() = synchronized(lock) { grantWaiting() }

    private suspend fun acquire() {
        val ticket = synchronized(lock) {
            if (waiting.isEmpty() && running < limit()) {
                running++
                return
            }
            CompletableDeferred<Unit>().also { waiting.addLast(it) }
        }
        try {
            ticket.await()
        } catch (e: CancellationException) {
            val granted = synchronized(lock) { ticket.isCompleted || !waiting.remove(ticket) }
            if (granted) release()
            throw e
        }
    }

    private fun release() = synchronized(lock) {
        running--
        grantWaiting()
    }

    private fun grantWaiting() {
        while (running < limit() && waiting.isNotEmpty()) {
            running++
            waiting.removeFirst().complete(Unit)
        }
    }
}
