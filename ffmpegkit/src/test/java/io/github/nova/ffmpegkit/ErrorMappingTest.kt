package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import io.github.nova.ffmpegkit.MediaRole.INPUT
import io.github.nova.ffmpegkit.MediaRole.OUTPUT
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ErrorMappingTest {

    @Test
    fun tagsMatchPublishedFfmpegValues() {
        // Values as printed by FFmpeg (e.g. "Invalid data found when processing input (-1094995529)").
        assertEquals(-1094995529, AvError.INVALIDDATA)
        assertEquals(-541478725, AvError.EOF)
        assertEquals(-1296385272, AvError.DEMUXER_NOT_FOUND)
        assertEquals(-1481985528, AvError.MUXER_NOT_FOUND)
        assertEquals(-1163346256, AvError.PATCHWELCOME)
        assertEquals(21, AvError.all.size)
        assertEquals(AvError.all.size, AvError.all.toSet().size)
    }

    @Test
    fun missingFileDependsOnRole() {
        assertEquals(Reason.INPUT_NOT_FOUND, AvError.reasonOf(AvError.ENOENT, INPUT))
        // A missing output path means its directory is missing.
        assertEquals(Reason.IO, AvError.reasonOf(AvError.ENOENT, OUTPUT))
    }

    @Test
    fun permissionErrors() {
        for (code in listOf(AvError.EACCES, AvError.EPERM, AvError.EROFS)) {
            assertEquals(Reason.ACCESS_DENIED, AvError.reasonOf(code, INPUT))
            assertEquals(Reason.ACCESS_DENIED, AvError.reasonOf(code, OUTPUT))
        }
    }

    @Test
    fun existingOutputAndFullStorage() {
        assertEquals(Reason.OUTPUT_EXISTS, AvError.reasonOf(AvError.EEXIST, OUTPUT))
        assertEquals(Reason.INSUFFICIENT_SPACE, AvError.reasonOf(AvError.ENOSPC, OUTPUT))
        assertEquals(Reason.INSUFFICIENT_SPACE, AvError.reasonOf(AvError.EDQUOT, OUTPUT))
    }

    @Test
    fun corruptMedia() {
        assertEquals(Reason.INVALID_MEDIA, AvError.reasonOf(AvError.INVALIDDATA, INPUT))
        assertEquals(Reason.INVALID_MEDIA, AvError.reasonOf(AvError.EOF, INPUT))
    }

    @Test
    fun unsupportedMedia() {
        listOf(
            AvError.ENOSYS, AvError.PATCHWELCOME,
            AvError.DEMUXER_NOT_FOUND, AvError.MUXER_NOT_FOUND,
            AvError.DECODER_NOT_FOUND, AvError.ENCODER_NOT_FOUND,
            AvError.STREAM_NOT_FOUND, AvError.PROTOCOL_NOT_FOUND,
            AvError.BSF_NOT_FOUND, AvError.FILTER_NOT_FOUND,
        ).forEach { assertEquals("code $it", Reason.UNSUPPORTED_MEDIA, AvError.reasonOf(it, INPUT)) }
    }

    @Test
    fun ioAndOtherNativeErrors() {
        assertEquals(Reason.IO, AvError.reasonOf(AvError.EIO, INPUT))
        assertEquals(Reason.NATIVE, AvError.reasonOf(AvError.EINVAL, INPUT))
        assertEquals(Reason.NATIVE, AvError.reasonOf(-12 /* ENOMEM */, OUTPUT))
        assertEquals(Reason.NATIVE, AvError.reasonOf(-123456, INPUT))
    }

    @Test
    fun nativeFailureKeepsCodeAndDescription() {
        val e = nativeFailure(AvError.INVALIDDATA, INPUT, "open input", "Invalid data found when processing input")
        assertEquals(Reason.INVALID_MEDIA, e.reason)
        assertEquals(AvError.INVALIDDATA, e.nativeErrorCode)
        assertEquals("open input failed: Invalid data found when processing input (-1094995529)", e.message)
    }

    @Test
    fun typedFileSystemExceptions() {
        assertEquals(Reason.INPUT_NOT_FOUND, mapped(INPUT, NoSuchFileException("in.mp4")).reason)
        assertEquals(Reason.IO, mapped(OUTPUT, NoSuchFileException("dir/out.mp4")).reason)
        assertEquals(Reason.OUTPUT_EXISTS, mapped(OUTPUT, FileAlreadyExistsException("out.mp4")).reason)
        assertEquals(Reason.ACCESS_DENIED, mapped(INPUT, AccessDeniedException("in.mp4")).reason)
        // kotlin.io variants, thrown by File.copyTo() and friends.
        val file = File("out.mp4")
        assertEquals(Reason.OUTPUT_EXISTS, mapped(OUTPUT, kotlin.io.FileAlreadyExistsException(file)).reason)
        assertEquals(Reason.INPUT_NOT_FOUND, mapped(INPUT, kotlin.io.NoSuchFileException(file)).reason)
        assertEquals(Reason.ACCESS_DENIED, mapped(INPUT, kotlin.io.AccessDeniedException(file)).reason)
    }

    @Test
    fun otherIoFailuresBecomeIo() {
        val cause = IOException("broken pipe")
        val e = mapped(OUTPUT, cause)
        assertEquals(Reason.IO, e.reason)
        assertNull(e.nativeErrorCode)
        assertSame(cause, e.cause)
        assertEquals("write output failed: broken pipe", e.message)
    }

    @Test
    fun securityExceptionIsAccessDenied() {
        val e = mapped(INPUT, SecurityException("no grant"))
        assertEquals(Reason.ACCESS_DENIED, e.reason)
        assertEquals("write output failed: no grant", e.message)
    }

    @Test
    fun existingFfmpegExceptionPassesThroughUnchanged() {
        val original = FFmpegException(Reason.RANGE_OUTSIDE_MEDIA, "start beyond end")
        assertSame(original, mapped(INPUT, original))
    }

    @Test
    fun cancellationAndOtherExceptionsAreNotWrapped() {
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            mapIoFailures(INPUT, "read input") { throw cancellation }
        })
        val illegal = IllegalArgumentException("bad range")
        assertSame(illegal, assertThrows(IllegalArgumentException::class.java) {
            mapIoFailures(INPUT, "read input") { throw illegal }
        })
    }

    @Test
    fun returnsBlockResultWhenNothingFails() {
        assertEquals(42, mapIoFailures(INPUT, "read input") { 42 })
    }

    private fun mapped(role: MediaRole, failure: Exception): FFmpegException =
        assertThrows(FFmpegException::class.java) {
            mapIoFailures(role, "write output") { throw failure }
        }
}
