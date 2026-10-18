package io.github.nova.ffmpegkit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Progress of [FFmpeg.trim] or [FFmpeg.split], passed to their `onProgress` callback up to ten times
 * a second once the input is open and writing has started, and once more when the outputs are
 * delivered. A short operation may only report that last one.
 *
 * Times are on the operation's target timeline: from the requested start for a trim, from the
 * input's start for a split by duration, and summed over the ranges for a split by ranges.
 */
class MediaProgress internal constructor(
    /**
     * 0 to 1, never decreasing; null while [total] is unknown. It covers writing the outputs and
     * delivering them, and 1.0 is reported once they are delivered. Where delivery is known to copy
     * the outputs (a `Uri` output; file outputs before Android 11), writing goes up to 0.5 and the copy
     * up to 0.99; otherwise delivery is a rename and writing goes up to 0.99. Whether the operation
     * succeeded is decided by the call returning or throwing, not by this value.
     */
    val fraction: Double?,
    /** Media time written so far; never decreasing, and at most [total] when that is known. */
    val processed: Duration,
    /**
     * The media time the operation writes in all: the requested ranges, clamped to the input's
     * duration. Null when that is unknown, i.e. the input's duration is unknown and a range runs to
     * the end of the media (or, for a split by duration, always with an unknown input duration).
     */
    val total: Duration?,
    /** Bytes written to the outputs so far, including container headers. */
    val bytesWritten: Long,
    /**
     * 1-based segment being written: always 1 for a trim. A keyframe-aligned split only knows how
     * many segments there are once it has read the whole input, so there is no segment count.
     */
    val segmentIndex: Int,
    /** [processed] media time per second of elapsed time since the work started; null until measurable. */
    val speed: Double?,
) {
    override fun toString(): String =
        "MediaProgress(fraction=$fraction, processed=$processed, total=$total, bytes=$bytesWritten, segment=$segmentIndex, speed=$speed)"
}

/** How long the target timeline of an operation is, given the input's duration (roadmap 5.2). */
internal fun interface ProgressTimeline {
    /** The total in microseconds, or null when it cannot be known; [durationUs] is null while unknown. */
    fun totalUs(durationUs: Long?): Long?

    companion object {
        /** Split by duration: the whole input. */
        val WHOLE_INPUT = ProgressTimeline { it }

        /** Trim (one range) and split by ranges: the ranges one after another, each clamped to the input. */
        fun ranges(ranges: List<TimeRange>) = ProgressTimeline { durationUs ->
            var total = 0L
            for (range in ranges) {
                val end = range.endExclusiveMicros
                total += when {
                    durationUs != null -> (minOf(end ?: durationUs, durationUs) - range.startMicros).coerceAtLeast(0)
                    end != null -> end - range.startMicros
                    else -> return@ProgressTimeline null
                }
            }
            total
        }
    }
}

/**
 * Turns task counters into [MediaProgress] for one operation's [callback]: keeps [MediaProgress.fraction]
 * and [MediaProgress.processed] from decreasing, skips unchanged counters, and reports nothing after
 * [finish]. Calls are serialized, so the callback never runs concurrently with itself.
 *
 * [copies]: delivery is expected to copy the outputs, so it gets as large a share of the fraction as
 * writing. A delivery that copies unexpectedly stays at 0.99 until it is done.
 */
internal class ProgressReporter(
    private val timeline: ProgressTimeline,
    private val copies: Boolean,
    private val callback: (MediaProgress) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) {
    private val writeShare = if (copies) COPYING_WRITE_SHARE else DELIVERED_AT

    private var startedNanos = 0L
    private var last: MediaProgress? = null
    private var lastCounters: TaskProgress? = null
    private var finished = false

    /** Marks the start of the work, for [MediaProgress.speed]. */
    @Synchronized
    fun start() {
        startedNanos = clock()
    }

    /** Reports [counters] if writing has started and anything changed since the last call. */
    @Synchronized
    fun report(counters: TaskProgress) {
        // Before the first write the input may not be open yet, and with it the total not known.
        if (counters.processedUs <= 0 && counters.bytesWritten <= 0) return
        if (finished || lastCounters?.let { it.sameAs(counters) } == true) return
        lastCounters = counters
        emit(counters, done = false)
    }

    /** Reports completion (fraction 1.0); later calls do nothing. */
    @Synchronized
    fun finish(counters: TaskProgress) {
        if (finished) return
        finished = true
        emit(counters, done = true)
    }

    private fun emit(counters: TaskProgress, done: Boolean) {
        val totalUs = timeline.totalUs(counters.durationUs)
        val previous = last
        var processedUs = counters.processedUs.coerceAtLeast(0)
        if (totalUs != null) processedUs = processedUs.coerceAtMost(totalUs)
        previous?.let { processedUs = maxOf(processedUs, it.processed.inWholeMicroseconds) }
        val fraction = when {
            done -> 1.0
            totalUs == null -> previous?.fraction
            else -> {
                // Delivery starts once everything is written, however close processed came to the total.
                val delivering = counters.deliveryBytes > 0
                val written = if (delivering || totalUs <= 0) 1.0 else processedUs.toDouble() / totalUs
                val delivered = if (delivering) counters.deliveredBytes.toDouble() / counters.deliveryBytes else 0.0
                val current = writeShare * written + (DELIVERED_AT - writeShare) * delivered.coerceAtMost(1.0)
                maxOf(current, previous?.fraction ?: 0.0)
            }
        }
        val elapsedNanos = clock() - startedNanos
        val speed = if (elapsedNanos >= MIN_SPEED_NANOS) processedUs * 1_000.0 / elapsedNanos else null
        val progress = MediaProgress(
            fraction,
            processedUs.microseconds,
            totalUs?.microseconds,
            counters.bytesWritten,
            counters.segment,
            speed,
        )
        last = progress
        callback(progress)
    }

    private fun TaskProgress.sameAs(other: TaskProgress) =
        processedUs == other.processedUs && durationUs == other.durationUs && bytesWritten == other.bytesWritten &&
            segment == other.segment && deliveryBytes == other.deliveryBytes && deliveredBytes == other.deliveredBytes

    private companion object {
        /** [MediaProgress.fraction] once the outputs are written and copied; 1.0 is kept for the delivered result. */
        const val DELIVERED_AT = 0.99

        /** The part of [MediaProgress.fraction] for writing when delivery copies: copying takes about as long. */
        const val COPYING_WRITE_SHARE = 0.5

        const val MIN_SPEED_NANOS = 1_000_000L
    }
}
