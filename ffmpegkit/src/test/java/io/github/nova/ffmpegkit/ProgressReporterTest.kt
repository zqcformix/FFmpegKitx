package io.github.nova.ffmpegkit

import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native counters → MediaProgress (roadmap 5.2). */
class ProgressReporterTest {

    private val reported = mutableListOf<MediaProgress>()
    private var now = 0L

    private fun reporter(timeline: ProgressTimeline, copies: Boolean = false) =
        ProgressReporter(timeline, copies, { reported += it }, clock = { now }).apply { start() }

    /** Rounded to 6 decimals, so that expectations need not spell out floating-point noise. */
    private fun fractions() = reported.map { p -> p.fraction?.let { Math.round(it * 1e6) / 1e6 } }

    private fun counters(processedUs: Long, durationUs: Long? = 10_000_000, bytes: Long = 0, segment: Int = 1, delivery: Long = 0, delivered: Long = 0) =
        TaskProgress(processedUs, durationUs, bytes, segment, delivery, delivered)

    @Test
    fun trimTimelineIsTheRangeClampedToTheInput() {
        val timeline = ProgressTimeline.ranges(listOf(TimeRange(2.seconds, 8.seconds)))
        assertEquals(6_000_000L, timeline.totalUs(10_000_000))
        assertEquals(6_000_000L, timeline.totalUs(null)) // the end is known
        assertEquals(3_000_000L, timeline.totalUs(5_000_000)) // clamped to the input
        val open = ProgressTimeline.ranges(listOf(TimeRange.from(2.seconds)))
        assertEquals(8_000_000L, open.totalUs(10_000_000))
        assertNull(open.totalUs(null))
    }

    @Test
    fun rangesTimelineSumsTheRanges() {
        val timeline = ProgressTimeline.ranges(
            listOf(TimeRange(0.seconds, 3.seconds), TimeRange(1.seconds, 2.seconds), TimeRange.from(6.seconds)),
        )
        assertEquals((3 + 1 + 4) * 1_000_000L, timeline.totalUs(10_000_000))
        assertNull(timeline.totalUs(null))
        assertEquals(10_000_000L, ProgressTimeline.WHOLE_INPUT.totalUs(10_000_000))
        assertNull(ProgressTimeline.WHOLE_INPUT.totalUs(null))
    }

    @Test
    fun writingGoesUpTo099AndDeliveryTo1() {
        val reporter = reporter(ProgressTimeline.WHOLE_INPUT)
        reporter.report(counters(0, bytes = 48)) // the header
        reporter.report(counters(5_000_000, bytes = 1_000))
        reporter.report(counters(10_000_000, bytes = 2_000))
        reporter.finish(counters(10_000_000, bytes = 2_100))
        assertEquals(listOf(0.0, 0.495, 0.99, 1.0), fractions())
        assertEquals(listOf(48L, 1_000L, 2_000L, 2_100L), reported.map { it.bytesWritten })
        assertEquals(5.seconds, reported[1].processed)
        assertEquals(10.seconds, reported[1].total)
    }

    @Test
    fun copyingDeliveryGetsHalfTheFraction() {
        val reporter = reporter(ProgressTimeline.WHOLE_INPUT, copies = true)
        reporter.report(counters(5_000_000))
        reporter.report(counters(9_900_000)) // writing ends a little short of the total
        reporter.report(counters(9_900_000, delivery = 4_000, delivered = 0))
        reporter.report(counters(9_900_000, delivery = 4_000, delivered = 2_000))
        reporter.report(counters(9_900_000, delivery = 4_000, delivered = 4_000))
        reporter.finish(counters(9_900_000, delivery = 4_000, delivered = 4_000))
        assertEquals(listOf(0.25, 0.495, 0.5, 0.745, 0.99, 1.0), fractions())
    }

    @Test
    fun aRenameDeliveryJumpsTo099() {
        val reporter = reporter(ProgressTimeline.WHOLE_INPUT)
        reporter.report(counters(9_000_000))
        reporter.report(counters(9_000_000, delivery = 4_000, delivered = 4_000))
        assertEquals(listOf(0.891, 0.99), fractions())
    }

    @Test
    fun nothingIsReportedBeforeWritingStarts() {
        val reporter = reporter(ProgressTimeline.WHOLE_INPUT)
        reporter.report(counters(0, durationUs = null)) // still opening the input
        reporter.report(counters(0, bytes = 48))
        assertEquals(listOf(0.0), fractions())
        assertEquals(10.seconds, reported.single().total)
    }

    @Test
    fun unknownTotalGivesIndeterminateProgressThen1() {
        val reporter = reporter(ProgressTimeline.WHOLE_INPUT)
        reporter.report(counters(1_000_000, durationUs = null))
        reporter.report(counters(2_000_000, durationUs = null))
        reporter.finish(counters(3_000_000, durationUs = null))
        assertEquals(listOf(null, null, 1.0), fractions())
        assertEquals(listOf(1.seconds, 2.seconds, 3.seconds), reported.map { it.processed })
        assertTrue(reported.all { it.total == null })
    }

    @Test
    fun neverDecreasesAndProcessedStaysWithinTheTotal() {
        val reporter = reporter(ProgressTimeline.ranges(listOf(TimeRange(0.seconds, 4.seconds))))
        reporter.report(counters(3_000_000))
        reporter.report(counters(2_000_000, bytes = 1)) // cannot happen natively; still never goes back
        reporter.report(counters(4_200_000, bytes = 2)) // a B-frame tail past the end
        assertEquals(listOf(3.seconds, 3.seconds, 4.seconds), reported.map { it.processed })
        assertEquals(listOf(0.7425, 0.7425, 0.99), fractions())
    }

    @Test
    fun unchangedCountersAreSkippedAndNothingFollowsFinish() {
        val reporter = reporter(ProgressTimeline.WHOLE_INPUT)
        reporter.report(counters(1_000_000))
        reporter.report(counters(1_000_000))
        reporter.finish(counters(1_000_000))
        reporter.report(counters(2_000_000))
        reporter.finish(counters(2_000_000))
        assertEquals(listOf(0.099, 1.0), fractions())
    }

    @Test
    fun speedIsMediaTimePerElapsedTime() {
        val reporter = reporter(ProgressTimeline.WHOLE_INPUT)
        reporter.report(counters(1_000_000)) // no time has passed
        now = 500_000_000
        reporter.report(counters(2_000_000, segment = 2))
        assertNull(reported[0].speed)
        assertEquals(4.0, reported[1].speed!!, 1e-9)
        assertEquals(2, reported[1].segmentIndex)
        assertEquals(2_000_000.microseconds, reported[1].processed)
    }
}
