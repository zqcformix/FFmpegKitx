package io.github.nova.ffmpegkit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeRangeTest {

    @Test
    fun closedRangeKeepsBoundsAndDuration() {
        val range = TimeRange(2.seconds, 8.seconds)
        assertEquals(2.seconds, range.start)
        assertEquals(8.seconds, range.endExclusive)
        assertEquals(6.seconds, range.duration)
        assertEquals(2_000_000L, range.startMicros)
        assertEquals(8_000_000L, range.endExclusiveMicros)
    }

    @Test
    fun fromRunsToEndOfMedia() {
        val range = TimeRange.from(10.seconds)
        assertEquals(10.seconds, range.start)
        assertNull(range.endExclusive)
        assertNull(range.duration)
        assertNull(range.endExclusiveMicros)
        assertEquals(TimeRange(10.seconds, null), range)
    }

    @Test
    fun acceptsZeroStartAndSingleMicrosecond() {
        val range = TimeRange(Duration.ZERO, 1.microseconds)
        assertEquals(0L, range.startMicros)
        assertEquals(1L, range.endExclusiveMicros)
        assertEquals(0L, TimeRange.from(Duration.ZERO).startMicros)
    }

    @Test
    fun acceptsMicrosecondPrecision() {
        val range = TimeRange(1_500.microseconds, 2_000_001.microseconds)
        assertEquals(1_500L, range.startMicros)
        assertEquals(2_000_001L, range.endExclusiveMicros)
    }

    @Test
    fun acceptsLargeValuesThatFitInMicroseconds() {
        // Durations this large are stored in milliseconds, so use a whole number of milliseconds.
        val micros = Long.MAX_VALUE / 2_000 * 1_000
        assertEquals(micros, TimeRange.from(micros.microseconds).startMicros)
    }

    @Test
    fun rejectsNegativeStart() {
        assertInvalid("start must not be negative") { TimeRange.from((-1).microseconds) }
        assertInvalid("start must not be negative") { TimeRange((-1).seconds, 1.seconds) }
    }

    @Test
    fun rejectsEndNotAfterStart() {
        assertInvalid("must be greater than start") { TimeRange(5.seconds, 5.seconds) }
        assertInvalid("must be greater than start") { TimeRange(5.seconds, 4.seconds) }
    }

    @Test
    fun rejectsNegativeEnd() {
        assertInvalid("endExclusive must not be negative") { TimeRange(Duration.ZERO, (-1).seconds) }
    }

    @Test
    fun rejectsInfiniteBounds() {
        assertInvalid("start must be finite") { TimeRange.from(Duration.INFINITE) }
        assertInvalid("start must be finite") { TimeRange(-Duration.INFINITE, 1.seconds) }
        assertInvalid("endExclusive must be finite") { TimeRange(1.seconds, Duration.INFINITE) }
    }

    @Test
    fun rejectsSubMicrosecondValues() {
        assertInvalid("start must be a whole number of microseconds") { TimeRange.from(1.nanoseconds) }
        assertInvalid("endExclusive must be a whole number of microseconds") {
            TimeRange(Duration.ZERO, 1_500.nanoseconds)
        }
    }

    @Test
    fun rejectsValuesThatOverflowMicroseconds() {
        // Finite as a Duration (stored in milliseconds) but beyond Long.MAX_VALUE microseconds.
        val huge = (Long.MAX_VALUE / 4).milliseconds
        assertTrue(huge.isFinite())
        assertInvalid("start overflows 64-bit microseconds") { TimeRange.from(huge) }
        assertInvalid("endExclusive overflows 64-bit microseconds") { TimeRange(Duration.ZERO, huge) }
    }

    @Test
    fun equalityUsesBothBounds() {
        assertEquals(TimeRange(1.seconds, 2.seconds), TimeRange(1_000.milliseconds, 2_000.milliseconds))
        assertEquals(TimeRange(1.seconds, 2.seconds).hashCode(), TimeRange(1.seconds, 2.seconds).hashCode())
        assertNotEquals(TimeRange(1.seconds, 2.seconds), TimeRange(1.seconds, 3.seconds))
        assertNotEquals(TimeRange(1.seconds, 2.seconds), TimeRange.from(1.seconds))
    }

    @Test
    fun toStringShowsHalfOpenRange() {
        assertEquals("TimeRange[2s, 8s)", TimeRange(2.seconds, 8.seconds).toString())
        assertEquals("TimeRange[10s, end)", TimeRange.from(10.seconds).toString())
    }

    private fun assertInvalid(messagePart: String, block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue(
            "expected message containing \"$messagePart\" but was \"${error.message}\"",
            error.message.orEmpty().contains(messagePart),
        )
    }
}
