package io.github.nova.ffmpegkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Error mapping against the real FFmpeg build and Android's file APIs. */
@RunWith(AndroidJUnit4::class)
class PackagedErrorsTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "errors-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.walkBottomUp().forEach { it.setReadable(true); it.setWritable(true) }
        dir.deleteRecursively()
    }

    @Test
    fun kotlinTableMatchesFfmpegHeaders() {
        assertArrayEquals(AvError.all.toIntArray(), NativeErrors.constants())
    }

    @Test
    fun kotlinStatusesMatchTheCAbiHeader() {
        assertArrayEquals(NativeStatus.all.toIntArray(), NativeErrors.statuses())
    }

    @Test
    fun coreClassifiesFfmpegErrorsAsKotlinDoes() {
        // Native failures arrive classified by the core; Java I/O failures by AvError. Both must agree.
        for (code in AvError.all) {
            for (role in MediaRole.entries) {
                val status = NativeErrors.statusOf(code, role == MediaRole.OUTPUT)
                assertEquals("$code on $role", AvError.reasonOf(code, role), NativeStatus.reasonOf(status))
            }
        }
    }

    @Test
    fun describesErrorsWithFfmpegText() {
        assertEquals("Invalid data found when processing input", NativeErrors.describe(AvError.INVALIDDATA))
        assertEquals("No such file or directory", NativeErrors.describe(AvError.ENOENT))
        val e = nativeFailure(AvError.DEMUXER_NOT_FOUND, MediaRole.INPUT, "open input", NativeErrors.describe(AvError.DEMUXER_NOT_FOUND))
        assertEquals(Reason.UNSUPPORTED_MEDIA, e.reason)
        assertEquals("open input failed: Demuxer not found (-1296385272)", e.message)
    }

    @Test
    fun missingInputFile() {
        val e = failure(MediaRole.INPUT) { FileInputStream(File(dir, "missing.mp4")).close() }
        assertEquals(Reason.INPUT_NOT_FOUND, e.reason)
    }

    @Test
    fun unreadableInputFile() {
        val file = File(dir, "locked.mp4").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(file.setReadable(false, false))
        val e = failure(MediaRole.INPUT) { FileInputStream(file).close() }
        assertEquals(Reason.ACCESS_DENIED, e.reason)
    }

    @Test
    fun existingOutputFile() {
        val file = File(dir, "out.mp4").apply { writeBytes(byteArrayOf(1)) }
        val e = failure(MediaRole.OUTPUT) { Files.createFile(file.toPath()) }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
    }

    @Test
    fun outputInMissingDirectory() {
        val e = failure(MediaRole.OUTPUT) { FileOutputStream(File(dir, "missing/out.mp4")).close() }
        assertEquals(Reason.IO, e.reason)
    }

    @Test
    fun outputInReadOnlyDirectory() {
        val readOnly = File(dir, "read-only").apply { mkdirs() }
        assertTrue(readOnly.setWritable(false, false))
        val e = failure(MediaRole.OUTPUT) { FileOutputStream(File(readOnly, "out.mp4")).close() }
        assertEquals(Reason.ACCESS_DENIED, e.reason)
    }

    private fun failure(role: MediaRole, block: () -> Unit): FFmpegException =
        assertThrows(FFmpegException::class.java) { mapIoFailures(role, "test", block) }
}
