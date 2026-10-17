package io.github.nova.ffmpegkit

import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Structured probe (roadmap 3.2). Expected values are what ffprobe (FFmpeg N-123317-gc1be2107c9,
 * 2026-03-10) prints for the samples with `-show_entries format=format_name,start_time,duration,bit_rate:
 * stream=index,codec_type,codec_name,width,height,avg_frame_rate,sample_rate,channels,channel_layout,
 * duration,bit_rate:stream_side_data=rotation`; an "N/A" there is null here.
 */
@RunWith(AndroidJUnit4::class)
class ProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var dir: File
    private lateinit var proxyThread: HandlerThread

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "probe-test").apply { deleteRecursively(); mkdirs() }
        proxyThread = HandlerThread("proxy-fd").apply { start() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        proxyThread.quitSafely()
        dir.deleteRecursively()
    }

    @Test
    fun audioAndVideoMp4() {
        val info = probe("av_h264_aac.mp4")
        assertEquals(MP4, info.formatName)
        assertEquals(0.seconds, info.startTime)
        assertEquals(10.seconds, info.duration)
        assertEquals(267_559L, info.bitRate)
        assertEquals(2, info.streams.size)
        assertVideo(info.streams[0], rotation = 0)
        assertAudio(info.streams[1], index = 1, duration = 10.seconds, bitRate = 64_216)
    }

    @Test
    fun videoOnlyAndAudioOnly() {
        val video = probe("video_h264.mp4")
        assertEquals(196_943L, video.bitRate)
        assertVideo(video.streams.single(), rotation = 0)

        val audio = probe("audio_aac.m4a")
        assertEquals(MP4, audio.formatName)
        assertEquals(10.seconds, audio.duration)
        assertEquals(66_364L, audio.bitRate)
        assertAudio(audio.streams.single(), index = 0, duration = 10.seconds, bitRate = 64_216)
    }

    @Test
    fun displayMatrixRotation() {
        // ffprobe: rotation=-90 (counterclockwise), so the picture is shown turned 90° clockwise.
        val info = probe("video_h264_rotated.mp4")
        assertVideo(info.streams.single(), rotation = 90)
    }

    @Test
    fun rawVideoStreamHasNoStartTimeDurationOrBitRate() {
        val info = probe("video_h264.h264")
        assertEquals("h264", info.formatName)
        assertNull(info.startTime)
        assertNull(info.duration)
        assertNull(info.bitRate)
        val video = info.streams.single() as StreamInfo.Video
        assertEquals("h264", video.codecName)
        assertEquals(320, video.width)
        assertEquals(240, video.height)
        assertNull(video.duration)
        assertNull(video.bitRate)
        // ffprobe's avg_frame_rate=25/1: the raw H.264 demuxer's default, documented on frameRate.
        assertEquals(25.0, video.frameRate!!, 0.0)
    }

    @Test
    fun durationEstimatedFromBitRateIsUnknown() {
        // ffprobe estimates 10.099628 s from the bit rate ("this may be inaccurate"); the real length is 10 s.
        val info = probe("audio_aac.aac")
        assertEquals("aac", info.formatName)
        assertNull(info.startTime)
        assertNull(info.duration)
        assertAudio(info.streams.single(), index = 0, duration = null, bitRate = null)
    }

    @Test
    fun descriptorAndUriInputs() {
        val sample = sample("av_h264_aac.mp4")
        val fromDescriptor = ParcelFileDescriptor.open(sample, ParcelFileDescriptor.MODE_READ_ONLY).use {
            runBlocking { FFmpeg.probe(MediaInput.of(it)) }
        }
        val fromUri = runBlocking { Uri.fromFile(sample).probe(context.contentResolver) }
        listOf(fromDescriptor, fromUri).forEach {
            assertEquals(MP4, it.formatName)
            assertEquals(10.seconds, it.duration)
            assertVideo(it.streams[0], rotation = 0)
            assertAudio(it.streams[1], index = 1, duration = 10.seconds, bitRate = 64_216)
        }
    }

    @Test
    fun failures() {
        val missing = assertThrows(FFmpegException::class.java) { runBlocking { File(dir, "missing.mp4").probe() } }
        assertEquals(Reason.INPUT_NOT_FOUND, missing.reason)

        val text = File(dir, "text.mp4").apply { writeText("not media") }
        val invalid = assertThrows(FFmpegException::class.java) { runBlocking { text.probe() } }
        assertEquals(Reason.INVALID_MEDIA, invalid.reason)
    }

    @Test
    fun cancellingStopsReadingAndEndsAsCancellation() {
        val bytes = sample("av_h264_aac.mp4").readBytes()
        val reads = AtomicInteger()
        var failure: Throwable? = null
        proxy(bytes, reads).use { descriptor ->
            val job = scope.launch {
                try {
                    FFmpeg.probe(MediaInput.of(descriptor))
                } catch (t: Throwable) {
                    failure = t
                    throw t
                }
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (reads.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("probe never started reading", reads.get() >= 1)
            runBlocking { job.cancelAndJoin() }
            val readsWhenEnded = reads.get()
            Thread.sleep(700)
            assertEquals("reads continued after the coroutine ended", readsWhenEnded, reads.get())
        }
        assertTrue("expected a CancellationException, got $failure", failure is CancellationException)
        assertFalse(failure is FFmpegException)
    }

    private fun assertVideo(stream: StreamInfo, rotation: Int) {
        val video = stream as StreamInfo.Video
        assertEquals(0, video.index)
        assertEquals("h264", video.codecName)
        assertEquals(320, video.width)
        assertEquals(240, video.height)
        assertEquals(30.0, video.frameRate!!, 0.0)
        assertEquals(rotation, video.rotation)
        assertEquals(10.seconds, video.duration)
        assertEquals(193_932L, video.bitRate)
        assertFalse(video.isAttachedPicture)
    }

    private fun assertAudio(stream: StreamInfo, index: Int, duration: kotlin.time.Duration?, bitRate: Long?) {
        val audio = stream as StreamInfo.Audio
        assertEquals(index, audio.index)
        assertEquals("aac", audio.codecName)
        assertEquals(44_100, audio.sampleRate)
        assertEquals(2, audio.channels)
        assertEquals("stereo", audio.channelLayout)
        assertEquals(duration, audio.duration)
        if (bitRate != null) assertEquals(bitRate, audio.bitRate)
    }

    private fun probe(name: String): MediaInfo = runBlocking { sample(name).probe() }

    /** A seekable descriptor over [bytes] whose reads are counted and take 300 ms each. */
    private fun proxy(bytes: ByteArray, reads: AtomicInteger): ParcelFileDescriptor =
        context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = bytes.size.toLong()

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    reads.incrementAndGet()
                    Thread.sleep(300)
                    val count = minOf(size.toLong(), bytes.size - offset).toInt().coerceAtLeast(0)
                    System.arraycopy(bytes, offset.toInt(), data, 0, count)
                    return count
                }

                override fun onRelease() {}
            },
            Handler(proxyThread.looper),
        )

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
        const val MP4 = "mov,mp4,m4a,3gp,3g2,mj2"
    }
}
