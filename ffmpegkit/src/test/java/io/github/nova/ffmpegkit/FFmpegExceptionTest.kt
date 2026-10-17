package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FFmpegExceptionTest {

    @Test
    fun reasonsAreFrozenAndOnlyAppended() {
        // The error model is frozen before the first editing release: new values go at the end,
        // existing ones are never renamed, removed or reordered.
        assertEquals(
            listOf(
                "INPUT_NOT_FOUND", "ACCESS_DENIED", "OUTPUT_EXISTS", "INSUFFICIENT_SPACE",
                "RANGE_OUTSIDE_MEDIA", "UNSUPPORTED_MEDIA", "INVALID_MEDIA", "IO", "NATIVE",
            ),
            Reason.entries.map { it.name },
        )
    }

    @Test
    fun isAnIOExceptionCarryingReasonCodeAndCause() {
        val cause = IOException("disk")
        val e = FFmpegException(Reason.IO, "write failed", nativeErrorCode = -5, cause = cause)
        assertTrue(e is IOException)
        assertEquals(Reason.IO, e.reason)
        assertEquals("write failed", e.message)
        assertEquals(-5, e.nativeErrorCode)
        assertSame(cause, e.cause)
    }

    @Test
    fun nativeErrorCodeAndCauseAreOptional() {
        val e = FFmpegException(Reason.OUTPUT_EXISTS, "exists")
        assertNull(e.nativeErrorCode)
        assertNull(e.cause)
    }

    @Test
    fun toStringNamesReasonAndCode() {
        assertEquals(
            "FFmpegException(INVALID_MEDIA, nativeErrorCode=-1094995529): bad data",
            FFmpegException(Reason.INVALID_MEDIA, "bad data", -1094995529).toString(),
        )
        assertEquals(
            "FFmpegException(OUTPUT_EXISTS): exists",
            FFmpegException(Reason.OUTPUT_EXISTS, "exists").toString(),
        )
    }
}
