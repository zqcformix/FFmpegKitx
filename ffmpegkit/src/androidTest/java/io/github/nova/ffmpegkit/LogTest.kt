package io.github.nova.ffmpegkit

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.FFmpegException.Reason
import io.github.nova.ffmpegkit.MediaLog.Level
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** FFmpeg log capture per operation (roadmap 5.2) with real FFmpeg messages. */
@RunWith(AndroidJUnit4::class)
class LogTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "log-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        FFmpeg.logOptions = MediaLog.Options()
        dir.deleteRecursively()
    }

    @Test
    fun aFailureCarriesWhatFFmpegLogged() {
        val e = assertThrows(FFmpegException::class.java) { runBlocking { truncated().trimTo(File(dir, "clip.mp4"), 0.seconds) } }
        Log.i("FFmpegKitTest", "truncated MP4: $e\n${e.log}")
        assertEquals(Reason.INVALID_MEDIA, e.reason)
        val moov = e.log.entries.single { "moov atom not found" in it.message }
        assertEquals(Level.ERROR, moov.level)
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", moov.component)
    }

    @Test
    fun aResultCarriesWarnings() {
        val result = runBlocking { sample(ADTS).trimTo(File(dir, "clip.m4a"), 2.seconds, 4.seconds) }
        Log.i("FFmpegKitTest", "ADTS trim:\n${result.log}")
        val estimate = result.log.entries.single { "Estimating duration from bitrate" in it.message }
        assertEquals(Level.WARNING, estimate.level)
        assertEquals(0, result.log.droppedCount)
    }

    @Test
    fun aCleanTrimLogsNothingAtTheDefaultLevel() {
        val result = runBlocking { sample(AV).trimTo(File(dir, "clip.mp4"), 2.seconds, 4.seconds) }
        assertEquals(emptyList<MediaLog.Entry>(), result.log.entries)
        val split = runBlocking { sample(AV).splitTo(File(dir, "segments").apply { mkdirs() }, 3.seconds, "clip", "mp4") }
        assertEquals(emptyList<MediaLog.Entry>(), split.log.entries)
    }

    @Test
    fun theBufferKeepsOnlyTheLatestEntries() {
        // At DEBUG the MP4 demuxer logs every box it parses, far more than 5 lines.
        FFmpeg.logOptions = MediaLog.Options(level = Level.DEBUG, maxEntries = 5)
        val result = runBlocking { sample(AV).trimTo(File(dir, "clip.mp4"), 2.seconds, 4.seconds) }
        Log.i("FFmpegKitTest", "DEBUG trim, ${result.log.droppedCount} dropped:\n${result.log}")
        assertEquals(5, result.log.entries.size)
        assertTrue(result.log.droppedCount > 0)
        assertTrue(result.log.entries.any { it.level == Level.DEBUG })

        FFmpeg.logOptions = MediaLog.Options(level = Level.DEBUG, maxEntries = 0)
        val none = runBlocking { sample(AV).trimTo(File(dir, "clip2.mp4"), 2.seconds, 4.seconds) }
        assertEquals(0, none.log.entries.size)
        assertTrue(none.log.droppedCount > 0)
    }

    @Test
    fun pathsAreRedacted() {
        // libavformat logs the path it opens at DEBUG. A space and non-ASCII name also checks the UTF-8 round trip.
        val input = File(dir, "视频 input.mp4").apply { writeBytes(sample(AV).readBytes()) }
        FFmpeg.logOptions = MediaLog.Options(level = Level.DEBUG, maxEntries = 10_000)
        val redacted = runBlocking { input.trimTo(File(dir, "clip.mp4"), 2.seconds, 4.seconds) }.log.entries.map { it.message }
        assertTrue(redacted.any { it == "Opening '<input>' for reading" })
        assertTrue(redacted.none { dir.path in it })

        FFmpeg.logOptions = MediaLog.Options(level = Level.DEBUG, maxEntries = 10_000, redactPaths = false)
        val raw = runBlocking { input.trimTo(File(dir, "clip2.mp4"), 2.seconds, 4.seconds) }.log.entries.map { it.message }
        assertTrue(raw.any { it == "Opening '${input.path}' for reading" })
    }

    @Test
    fun theLevelFiltersEntries() {
        FFmpeg.logOptions = MediaLog.Options(level = Level.ERROR)
        val result = runBlocking { sample(ADTS).trimTo(File(dir, "clip.m4a"), 2.seconds, 4.seconds) }
        assertTrue(result.log.entries.none { it.level == Level.WARNING })
    }

    @Test
    fun parallelOperationsNeverGetEachOthersLines() {
        val truncated = truncated()
        val adts = sample(ADTS)
        repeat(3) { round ->
            val logs = runBlocking {
                (0 until 8).map { i ->
                    async(Dispatchers.Default) {
                        if (i % 2 == 0) {
                            "adts" to adts.trimTo(File(dir, "r$round-$i.m4a"), 2.seconds, 4.seconds).log
                        } else {
                            "truncated" to runCatching { truncated.probe() }.exceptionOrNull().let { (it as FFmpegException).log }
                        }
                    }
                }.awaitAll()
            }
            for ((kind, log) in logs) {
                val messages = log.entries.map { it.message }
                if (kind == "adts") {
                    assertTrue(messages.any { "Estimating duration" in it })
                    assertTrue("$kind got $messages", messages.none { "moov" in it })
                } else {
                    assertTrue(messages.any { "moov atom not found" in it })
                    assertTrue("$kind got $messages", messages.none { "Estimating" in it })
                }
            }
        }
    }

    /** The first 100 KB of the A/V sample: its moov box is at the end, so FFmpeg cannot find it. */
    private fun truncated(): File = File(dir, "truncated.mp4").apply {
        if (!exists()) writeBytes(sample(AV).readBytes().copyOf(100_000))
    }

    private fun sample(name: String): File {
        val file = File(dir, name)
        if (!file.exists()) {
            InstrumentationRegistry.getInstrumentation().context.assets.open("media/$name").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return file
    }

    private companion object {
        const val AV = "av_h264_aac.mp4"
        const val ADTS = "audio_aac.aac"
    }
}
