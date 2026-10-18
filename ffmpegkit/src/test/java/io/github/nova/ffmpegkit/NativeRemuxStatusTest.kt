package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the C ABI's `ffkx_error` becomes an exception; PackagedErrorsTest checks the constants against the header. */
class NativeRemuxStatusTest {

    @Test
    fun okIsNotAFailure() {
        assertFalse(status(NativeStatus.OK, side = NativeStatus.SIDE_NONE, message = "").failed)
        assertTrue(status(NativeStatus.IO).failed)
    }

    @Test
    fun mediaFailuresBecomeFFmpegExceptionsNamingTheirSide() {
        val input = status(NativeStatus.INPUT_NOT_FOUND, code = -2, message = "open input failed: No such file or directory (-2)")
            .toException("in.mp4", "out.mp4") as FFmpegException
        assertEquals(Reason.INPUT_NOT_FOUND, input.reason)
        assertEquals("open input failed: No such file or directory (-2): in.mp4", input.message)
        assertEquals(-2, input.nativeErrorCode)

        val output = status(NativeStatus.UNSUPPORTED_MEDIA, side = NativeStatus.SIDE_OUTPUT, message = "edit list mode needs an MP4 or MOV output")
            .toException("in.mp4", "out.mkv") as FFmpegException
        assertEquals(Reason.UNSUPPORTED_MEDIA, output.reason)
        assertEquals("edit list mode needs an MP4 or MOV output: out.mkv", output.message)
        assertNull(output.nativeErrorCode)
    }

    @Test
    fun everyMediaStatusHasItsReason() {
        val expected = mapOf(
            NativeStatus.INPUT_NOT_FOUND to Reason.INPUT_NOT_FOUND,
            NativeStatus.ACCESS_DENIED to Reason.ACCESS_DENIED,
            NativeStatus.OUTPUT_EXISTS to Reason.OUTPUT_EXISTS,
            NativeStatus.INSUFFICIENT_SPACE to Reason.INSUFFICIENT_SPACE,
            NativeStatus.RANGE_OUTSIDE_MEDIA to Reason.RANGE_OUTSIDE_MEDIA,
            NativeStatus.UNSUPPORTED_MEDIA to Reason.UNSUPPORTED_MEDIA,
            NativeStatus.INVALID_MEDIA to Reason.INVALID_MEDIA,
            NativeStatus.IO to Reason.IO,
            NativeStatus.FFMPEG to Reason.NATIVE,
            // Failures inside the library, and statuses from a newer core, are still FFmpegExceptions.
            NativeStatus.OUT_OF_MEMORY to Reason.NATIVE,
            NativeStatus.INTERNAL to Reason.NATIVE,
            NativeStatus.UNSUPPORTED_VERSION to Reason.NATIVE,
            99 to Reason.NATIVE,
        )
        for ((value, reason) in expected) {
            val e = status(value, code = -12).toException("in.mp4", "out.mp4")
            assertEquals("status $value", reason, (e as FFmpegException).reason)
            assertEquals(-12, e.nativeErrorCode)
        }
    }

    @Test
    fun argumentErrorsAndCancellationAreNotWrapped() {
        val segments = status(NativeStatus.TOO_MANY_SEGMENTS, message = "more than 9999 segments of 1000 us").toException("in.mp4", "dir")
        assertTrue(segments is IllegalArgumentException)
        assertEquals("segment duration too short for in.mp4: more than 9999 segments of 1000 us", segments.message)
        assertTrue(status(NativeStatus.INVALID_ARGUMENT).toException("in.mp4", "out.mp4") is IllegalArgumentException)
        assertTrue(status(NativeStatus.CANCELLED, side = NativeStatus.SIDE_NONE).toException("in.mp4", "out.mp4") is CancellationException)
    }

    @Test
    fun messagesAreUtf8() {
        val e = NativeRemuxStatus(NativeStatus.IO, 0, NativeStatus.SIDE_INPUT, "读取失败".toByteArray(), LongArray(0))
            .toException("in.mp4", "out.mp4")
        assertEquals("读取失败: in.mp4", e.message)
    }

    private fun status(value: Int, code: Int = 0, side: Int = NativeStatus.SIDE_INPUT, message: String = "failed") =
        NativeRemuxStatus(value, code, side, message.toByteArray(), LongArray(0))
}
