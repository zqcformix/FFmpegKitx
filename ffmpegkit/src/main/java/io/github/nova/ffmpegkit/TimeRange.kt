package io.github.nova.ffmpegkit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Half-open media time range `[start, endExclusive)`.
 *
 * A `null` [endExclusive] runs to the end of the media. Both bounds must be finite, non-negative
 * and a whole number of microseconds (FFmpeg's `AV_TIME_BASE`); nanoseconds are never truncated
 * silently. [endExclusive] must be greater than [start]. Invalid values throw
 * [IllegalArgumentException] here, before any media work starts.
 *
 * ```kotlin
 * TimeRange(2.seconds, 8.seconds) // [2s, 8s)
 * TimeRange.from(10.seconds)      // from 10s to the end of the media
 * ```
 */
class TimeRange(val start: Duration, val endExclusive: Duration?) {

    init {
        requireWholeMicroseconds("start", start)
        if (endExclusive != null) {
            requireWholeMicroseconds("endExclusive", endExclusive)
            require(endExclusive > start) {
                "endExclusive ($endExclusive) must be greater than start ($start)"
            }
        }
    }

    /** Length of the range, or `null` when it runs to the end of the media. */
    val duration: Duration?
        get() = endExclusive?.minus(start)

    internal val startMicros: Long
        get() = start.inWholeMicroseconds

    internal val endExclusiveMicros: Long?
        get() = endExclusive?.inWholeMicroseconds

    override fun equals(other: Any?): Boolean =
        other is TimeRange && start == other.start && endExclusive == other.endExclusive

    override fun hashCode(): Int = 31 * start.hashCode() + endExclusive.hashCode()

    override fun toString(): String = "TimeRange[$start, ${endExclusive ?: "end"})"

    companion object {
        /** Range from [start] to the end of the media. */
        fun from(start: Duration): TimeRange = TimeRange(start, null)
    }
}

private fun requireWholeMicroseconds(name: String, value: Duration) {
    require(value.isFinite()) { "$name must be finite: $value" }
    require(!value.isNegative()) { "$name must not be negative: $value" }
    // inWholeMicroseconds clamps to Long.MAX_VALUE when the value does not fit.
    val micros = value.inWholeMicroseconds
    require(micros != Long.MAX_VALUE) { "$name overflows 64-bit microseconds: $value" }
    require(micros.microseconds == value) { "$name must be a whole number of microseconds: $value" }
}
