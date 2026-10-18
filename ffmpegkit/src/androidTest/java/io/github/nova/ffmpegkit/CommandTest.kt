package io.github.nova.ffmpegkit

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [FFmpeg.execute] (roadmap 6.3): FFmpeg 8.0.1's ffmpeg command line in libffmpegkitx_cli. */
@RunWith(AndroidJUnit4::class)
class CommandTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val inserted = mutableListOf<Uri>()
    private lateinit var dir: File
    private lateinit var input: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "command-test").apply { deleteRecursively(); mkdirs() }
        input = sample(AV)
    }

    @After
    fun tearDown() {
        FFmpeg.logOptions = MediaLog.Options()
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
        dir.deleteRecursively()
    }

    @Test
    fun copiesWithProgressAndALogOfTheWorkerThreads() {
        FFmpeg.logOptions = MediaLog.Options(level = MediaLog.Level.VERBOSE, maxEntries = 10_000)
        val output = File(dir, "copy.mp4")
        val reports = Collections.synchronizedList(mutableListOf<MediaProgress>())
        val result = runBlocking {
            FFmpeg.execute(listOf("-i", input.path, "-t", "2", "-c", "copy", output.path)) { reports += it }
        }

        val info = runBlocking { FFmpeg.probe(MediaInput.of(output)) }
        assertEquals(2, info.streams.size)
        assertTrue("${info.duration}", info.duration!! in 1_900.milliseconds..2_200.milliseconds)
        // Lines of ffmpeg's worker threads ("Terminating thread...") belong to the call; paths are redacted.
        val messages = result.log.entries.map { it.message }
        assertTrue(messages.any { "Terminating thread with return code 0" in it })
        assertTrue(messages.any { "<path>" in it })
        // The banner's configuration line is longer than an entry may be (1,024 bytes): it is cut, and the
        // lines after it are still there.
        assertTrue(messages.any { it.trimStart().startsWith("configuration:") && it.toByteArray().size <= 1_024 })
        assertTrue(messages.any { it.startsWith("Stream mapping:") })
        assertFalse(messages.any { input.path in it || output.path in it })
        // The total is unknown until the end.
        val last = reports.last()
        assertEquals(1.0, last.fraction!!, 0.0)
        assertNull(last.total)
        assertTrue("${last.processed}", last.processed >= 1_900.milliseconds)
        assertTrue(last.bytesWritten > 0)
        assertTrue(reports.dropLast(1).all { it.fraction == null })
    }

    @Test
    fun transcodes() {
        val output = File(dir, "small.mp4")
        runBlocking {
            FFmpeg.execute("-i", input.path, "-t", "1", "-vf", "scale=160:120", "-c:v", "mpeg4", "-c:a", "aac", output.path)
        }
        val video = runBlocking { FFmpeg.probe(MediaInput.of(output)) }.streams[0] as StreamInfo.Video
        assertEquals("mpeg4", video.codecName)
        assertEquals(160, video.width)
        assertEquals(120, video.height)
    }

    @Test
    fun anExistingOutputNeedsMinusY() {
        val output = File(dir, "exists.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val e = assertThrows(FFmpegException::class.java) {
            runBlocking { FFmpeg.execute("-i", input.path, "-t", "1", "-c", "copy", output.path) }
        }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
        assertEquals(3L, output.length())
        runBlocking { FFmpeg.execute("-y", "-i", input.path, "-t", "1", "-c", "copy", output.path) }
        assertTrue(output.length() > 3)
    }

    @Test
    fun failuresCarryReasonAndLog() {
        val e = assertThrows(FFmpegException::class.java) {
            runBlocking { FFmpeg.execute("-i", File(dir, "missing.mp4").path, File(dir, "out.mp4").path) }
        }
        assertEquals(Reason.INPUT_NOT_FOUND, e.reason)
        assertEquals("ffmpeg failed: No such file or directory (-2)", e.message)
        assertTrue(e.log.entries.any { "No such file or directory" in it.message })

        val usage = assertThrows(FFmpegException::class.java) { runBlocking { FFmpeg.execute() } }
        assertEquals(Reason.NATIVE, usage.reason)
        assertEquals("ffmpeg exited with code 1", usage.message)
    }

    @Test
    fun processWideOptionsAreRefused() {
        val output = File(dir, "refused.mp4")
        for (option in listOf(listOf("-report"), listOf("-cpuflags", "0"), listOf("-timelimit", "60"), listOf("-stdin"), listOf("-version"))) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { FFmpeg.execute(option + listOf("-i", input.path, output.path)) }
            }
            assertTrue(e.message!!, e.message!!.startsWith("option ${option[0]} is not available"))
        }
        assertFalse(output.exists())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { FFmpeg.execute("-i", "a\u0000b", output.path) } }
    }

    @Test
    fun cancellingStopsAnEndlessCommandAndKeepsItsOutput() {
        val output = File(dir, "endless.mkv")
        val progressed = CompletableDeferred<Unit>()
        var cancelledAfterMs = 0L
        runBlocking {
            val job = async(Dispatchers.Default) {
                FFmpeg.execute(listOf("-stream_loop", "-1", "-i", input.path, "-c", "copy", output.path)) {
                    if (it.processed >= 5.seconds) progressed.complete(Unit)
                }
            }
            withTimeout(20_000) { progressed.await() }
            val started = System.nanoTime()
            job.cancel()
            val thrown = runCatching { job.await() }.exceptionOrNull()
            cancelledAfterMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("$thrown", thrown is CancellationException)
        }
        assertTrue("took $cancelledAfterMs ms", cancelledAfterMs < 3_000)
        // ffmpeg finished what it had written, as on 'q'.
        val info = runBlocking { FFmpeg.probe(MediaInput.of(output)) }
        assertTrue("${info.duration}", info.duration!! >= 5.seconds)
        runBlocking { FFmpeg.execute("-i", input.path, "-t", "1", "-c", "copy", File(dir, "after.mp4").path) }
    }

    @Test
    fun commandsRunOneAtATimeWhileOperationsDoNot() {
        val results = runBlocking {
            coroutineScope {
                (1..3).map { i ->
                    async(Dispatchers.IO) {
                        FFmpeg.execute("-i", input.path, "-t", "1", "-c", "copy", File(dir, "parallel-$i.mp4").path)
                    }
                } + async(Dispatchers.IO) { input.trimTo(File(dir, "trim.mp4"), 1.seconds, 2.seconds) }
            }.awaitAll()
        }
        assertEquals(4, results.size)
        (1..3).forEach { assertTrue(File(dir, "parallel-$it.mp4").length() > 0) }
    }

    @Test
    fun aContentUriIsReadThroughItsDescriptor() {
        assumeTrue("MediaStore pending items need Android 10", Build.VERSION.SDK_INT >= 29)
        val uri = insertVideo(input)
        val output = File(dir, "from-uri.mp4")
        resolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            runBlocking { FFmpeg.execute("-fd", descriptor.fd.toString(), "-i", "fd:", "-t", "1", "-c", "copy", output.path) }
        }
        assertEquals(2, runBlocking { FFmpeg.probe(MediaInput.of(output)) }.streams.size)

        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            runBlocking { FFmpeg.execute("-fd", descriptor.fd.toString(), "-i", "fd:", "-t", "1", "-c", "copy", File(dir, "from-fd.mp4").path) }
        }
    }

    private fun insertVideo(content: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ffmpegkit-command-${System.nanoTime()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/FFmpegKitTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        inserted += uri
        resolver.openOutputStream(uri, "w")!!.use { out -> content.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun sample(name: String): File {
        val file = File(dir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("media/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private companion object {
        const val AV = "av_h264_aac.mp4"
    }
}
