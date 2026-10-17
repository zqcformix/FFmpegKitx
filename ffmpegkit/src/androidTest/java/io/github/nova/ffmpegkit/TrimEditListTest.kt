package io.github.nova.ffmpegkit

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TrimMode.EDIT_LIST] (roadmap 2.4) on av_h264_aac.mp4 (keyframe every 1 s, 30 fps, B-frames).
 * Trimming [2.5 s, 4.5 s) keeps the data from the keyframe at 2 s in both modes; with an edit list,
 * players that honour it start showing at 2.5 s. The playback tests read back the first frame each
 * player renders (through OpenGL, see [GlFrameGrabber]) and compare it with the input's frames at 2.5 s and 2 s.
 */
@RunWith(AndroidJUnit4::class)
class TrimEditListTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var input: File
    private lateinit var playbackThread: HandlerThread

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "edit-list-test").apply { deleteRecursively(); mkdirs() }
        input = File(dir, AV).apply {
            InstrumentationRegistry.getInstrumentation().context.assets.open("media/$AV").use { s -> outputStream().use { s.copyTo(it) } }
        }
        playbackThread = HandlerThread("playback").apply { start() }
    }

    @After
    fun tearDown() {
        playbackThread.quitSafely()
        if (InstrumentationRegistry.getArguments().getString("keepOutputs") != "true") dir.deleteRecursively()
    }

    @Test
    fun sameDataAsKeyframeModeButFFmpegStartsShowingAtTheRequestedStart() {
        val keyframe = Trimmer.trim(input, File(dir, "keyframe.mp4"), RANGE)
        val editList = Trimmer.trim(input, File(dir, "edit-list.mp4"), RANGE, TrimMode.EDIT_LIST)

        assertEquals(keyframe.actualRange, editList.actualRange)
        assertEquals(TimeRange(2.seconds, 4_633_333.microseconds), editList.actualRange)
        assertEquals(MediaInspector.packets(File(dir, "keyframe.mp4")), MediaInspector.packets(File(dir, "edit-list.mp4")))

        // FFmpeg's MP4 demuxer honours the edit list: the 15 frames from 2 s to 2.5 s are decoded but not output.
        val shown = MediaInspector.inspect(File(dir, "keyframe.mp4")).streams.first { it.isVideo }
        val hidden = MediaInspector.inspect(File(dir, "edit-list.mp4")).streams.first { it.isVideo }
        assertEquals(shown.packets, hidden.packets)
        assertEquals(15, shown.frames - hidden.frames)
        assertEquals(0, hidden.decodeErrors)
    }

    @Test
    fun startOnAKeyframeNeedsNoEditList() {
        val keyframe = Trimmer.trim(input, File(dir, "keyframe.mp4"), TimeRange(3.seconds, 5.seconds))
        val editList = Trimmer.trim(input, File(dir, "edit-list.mp4"), TimeRange(3.seconds, 5.seconds), TrimMode.EDIT_LIST)
        assertEquals(keyframe.actualRange, editList.actualRange)
        assertEquals(
            MediaInspector.inspect(File(dir, "keyframe.mp4")).streams.map { it.frames },
            MediaInspector.inspect(File(dir, "edit-list.mp4")).streams.map { it.frames },
        )
    }

    @Test
    fun onlyMp4AndMovOutputs() {
        assertThrows(IllegalArgumentException::class.java) {
            TrimRequest(MediaInput.of(input), MediaOutput.of(File(dir, "clip.mkv")), RANGE, TrimMode.EDIT_LIST)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { input.trimTo(File(dir, "clip.ts"), 2.seconds, 4.seconds, TrimMode.EDIT_LIST) }
        }
        val mov = runBlocking { input.trimTo(File(dir, "clip.mov"), 2_500.milliseconds, 4_500.milliseconds, TrimMode.EDIT_LIST) }
        assertEquals(2.seconds, mov.actualRange.start)
        assertEquals(setOf(AV, "clip.mov"), dir.list()!!.toSet())
    }

    @Test
    fun exoPlayerStartsShowingAtTheRequestedStart() {
        Trimmer.trim(input, File(dir, "keyframe.mp4"), RANGE)
        Trimmer.trim(input, File(dir, "edit-list.mp4"), RANGE, TrimMode.EDIT_LIST)
        val keyframe = play(File(dir, "keyframe.mp4"), ::exoPlayer)
        val editList = play(File(dir, "edit-list.mp4"), ::exoPlayer)
        Log.i(TAG, "ExoPlayer: keyframe mode $keyframe; edit list mode $editList")
        assertEquals(Shown.KEYFRAME, keyframe.firstFrame)
        assertEquals(Shown.REQUESTED_START, editList.firstFrame)
        assertTrue("$editList", abs(editList.durationMs - 2_133) <= 40) // 4.633 s - 2.5 s
        assertTrue("$keyframe", abs(keyframe.durationMs - 2_633) <= 40)
    }

    @Test
    fun mediaPlayerBehaviourIsRecorded() {
        // Recorded, not required: whether the platform player honours edit lists depends on the Android version.
        Trimmer.trim(input, File(dir, "keyframe.mp4"), RANGE)
        Trimmer.trim(input, File(dir, "edit-list.mp4"), RANGE, TrimMode.EDIT_LIST)
        val keyframe = play(File(dir, "keyframe.mp4"), ::mediaPlayer)
        val editList = play(File(dir, "edit-list.mp4"), ::mediaPlayer)
        Log.i(TAG, "MediaPlayer (API ${Build.VERSION.SDK_INT}): keyframe mode $keyframe; edit list mode $editList")
        assertEquals(Shown.KEYFRAME, keyframe.firstFrame)
        assertTrue("$editList", editList.firstFrame != Shown.NOTHING)
    }

    private enum class Shown { REQUESTED_START, KEYFRAME, NOTHING }

    private data class Playback(val firstFrame: Shown, val durationMs: Long)

    /** Plays [file] with [start] into a [GlFrameGrabber] and reports which input frame came out first. */
    private fun play(file: File, start: (File, Surface, Handler, (Long) -> Unit, () -> Unit) -> () -> Unit): Playback {
        val handler = Handler(playbackThread.looper)
        var first: IntArray? = null
        val gotFrame = CountDownLatch(1)
        var durationMs = -1L
        val ready = CountDownLatch(1)
        var release: () -> Unit = {}
        handler.post {
            val grabber = GlFrameGrabber(WIDTH, HEIGHT, handler) { first = it; gotFrame.countDown() }
            val stop = start(file, grabber.surface, handler, { durationMs = it; ready.countDown() }, { ready.countDown(); gotFrame.countDown() })
            release = { stop(); grabber.release() }
        }
        try {
            ready.await(10, TimeUnit.SECONDS)
            gotFrame.await(10, TimeUnit.SECONDS)
        } finally {
            val released = CountDownLatch(1)
            handler.post { release(); released.countDown() }
            released.await(5, TimeUnit.SECONDS)
        }
        val frame = first ?: return Playback(Shown.NOTHING, durationMs)
        val atStart = difference(frame, inputFrame(2_500_000))
        val atKeyframe = difference(frame, inputFrame(2_000_000))
        Log.i(TAG, "${file.name}: mean luma difference to the input at 2.5 s $atStart, at 2 s $atKeyframe")
        return Playback(if (atStart < atKeyframe) Shown.REQUESTED_START else Shown.KEYFRAME, durationMs)
    }

    private fun exoPlayer(file: File, surface: Surface, handler: Handler, onDuration: (Long) -> Unit, onError: () -> Unit): () -> Unit {
        check(Looper.myLooper() == handler.looper)
        val player = ExoPlayer.Builder(context).setLooper(handler.looper).build()
        player.setVideoSurface(surface)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) onDuration(player.duration)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer failed on ${file.name}", error)
                onError()
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.play()
        return { player.release() }
    }

    private fun mediaPlayer(file: File, surface: Surface, handler: Handler, onDuration: (Long) -> Unit, onError: () -> Unit): () -> Unit {
        val player = MediaPlayer()
        player.setSurface(surface)
        player.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer failed on ${file.name}: $what/$extra")
            onError()
            true
        }
        player.setOnPreparedListener {
            onDuration(it.duration.toLong())
            it.start()
        }
        player.setDataSource(file.path)
        player.prepareAsync()
        return { player.release() }
    }

    private val inputFrames = mutableMapOf<Long, IntArray>()

    /** Luma of the input's frame at [timeUs], decoded by the platform. */
    private fun inputFrame(timeUs: Long): IntArray = inputFrames.getOrPut(timeUs) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(input.path)
            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
            luma(Bitmap.createScaledBitmap(bitmap, WIDTH, HEIGHT, true))
        } finally {
            retriever.release()
        }
    }

    private fun luma(bitmap: Bitmap): IntArray {
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        return IntArray(pixels.size) {
            val p = pixels[it]
            (0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)).toInt()
        }
    }

    /** Mean absolute luma difference, taking whichever of [frame]'s two vertical orientations fits better. */
    private fun difference(frame: IntArray, reference: IntArray): Double {
        fun mean(rowOf: (Int) -> Int) = reference.indices.sumOf {
            abs(frame[rowOf(it / WIDTH) * WIDTH + it % WIDTH] - reference[it]).toLong()
        }.toDouble() / reference.size
        return minOf(mean { it }, mean { HEIGHT - 1 - it })
    }

    private companion object {
        const val TAG = "FFmpegKitTest"
        const val AV = "av_h264_aac.mp4"
        const val WIDTH = 320
        const val HEIGHT = 240
        val RANGE = TimeRange(2_500.milliseconds, 4_500.milliseconds)
    }
}
