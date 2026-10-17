package io.github.nova.ffmpegkit

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Content Uri, file Uri and file descriptor inputs, and Uri outputs, against MediaStore (roadmap 3.1). */
@RunWith(AndroidJUnit4::class)
class MediaUriTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val inserted = mutableListOf<Uri>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "uri-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
        dir.deleteRecursively()
    }

    @Test
    fun trimsFromMediaStoreUriLikeFromAFile() {
        val sample = sample(AV)
        val uri = insertVideo(sample)
        val range = TimeRange(2_500.milliseconds, 4_500.milliseconds)
        val fromUri = Trimmer.trim(MediaInput.of(resolver, uri), MediaOutput.of(File(dir, "from-uri.mp4")), range)
        val fromFile = Trimmer.trim(sample, File(dir, "from-file.mp4"), range)

        assertEquals(TimeRange(2.seconds, 4_633_333.microseconds), fromUri.actualRange)
        assertEquals(packets(File(dir, "from-file.mp4")), packets(File(dir, "from-uri.mp4")))
        assertEquals(fromFile.actualRange, fromUri.actualRange)
    }

    @Test
    fun splitsFromMediaStoreUri() {
        val sample = sample(AV)
        val out = File(dir, "segments").apply { mkdirs() }
        val result = Splitter.split(MediaInput.of(resolver, insertVideo(sample)), out, 3.seconds, "clip", "mp4")
        assertEquals(4, result.segments.size)
        val joined = result.segments.flatMap { packets(it.file) }.groupBy { it.stream }
        assertEquals(packets(sample).groupBy { it.stream }, joined)
    }

    @Test
    fun trimsIntoMediaStoreUri() {
        val sample = sample(AV)
        val target = insertVideo(content = null) // pending, empty; container follows its MIME type
        val range = TimeRange(3.seconds, 4.seconds)
        val result = Trimmer.trim(MediaInput.of(sample), MediaOutput.of(resolver, target), range)
        Trimmer.trim(sample, File(dir, "reference.mp4"), range)

        assertEquals(TimeRange(3.seconds, 4_133_333.microseconds), result.actualRange)
        assertEquals(packets(File(dir, "reference.mp4")), packets(copyOut(target)))
    }

    @Test
    fun failedTrimLeavesTheOutputUriUntouched() {
        val target = insertVideo(content = null)
        resolver.openOutputStream(target, "w")!!.use { it.write(byteArrayOf(1, 2, 3)) }
        val corrupt = File(dir, "garbage.mp4").apply { writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() }) }
        val e = assertThrows(FFmpegException::class.java) {
            Trimmer.trim(MediaInput.of(corrupt), MediaOutput.of(resolver, target), TimeRange(0.seconds, 1.seconds))
        }
        assertEquals(Reason.INVALID_MEDIA, e.reason)
        assertArrayEquals(byteArrayOf(1, 2, 3), resolver.openInputStream(target)!!.use { it.readBytes() })
    }

    @Test
    fun uriOutputLeavesNoTemporaryFiles() {
        val before = temporaryFiles()
        val sample = sample(AV)
        Trimmer.trim(MediaInput.of(sample), MediaOutput.of(resolver, insertVideo(content = null)), TimeRange(2.seconds, 3.seconds))
        assertThrows(FFmpegException::class.java) {
            Trimmer.trim(MediaInput.of(sample), MediaOutput.of(resolver, insertVideo(content = null)), TimeRange.from(20.seconds))
        }
        assertEquals(before, temporaryFiles())
    }

    @Test
    fun fileUriIsReadAsAFile() {
        val result = Trimmer.trim(MediaInput.of(resolver, Uri.fromFile(sample(AV))), MediaOutput.of(File(dir, "clip.mp4")), TimeRange(3.seconds, 4.seconds))
        assertEquals(TimeRange(3.seconds, 4_133_333.microseconds), result.actualRange)
    }

    @Test
    fun descriptorIsReadFromTheStartAndStaysOpen() {
        val sample = sample(AV)
        ParcelFileDescriptor.open(sample, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).skip(100_000) // the caller already read part of it
            val result = Trimmer.trim(MediaInput.of(descriptor), MediaOutput.of(File(dir, "clip.mp4")), TimeRange(3.seconds, 4.seconds))
            assertEquals(TimeRange(3.seconds, 4_133_333.microseconds), result.actualRange)
            assertTrue(descriptor.fileDescriptor.valid())
        }
    }

    @Test
    fun pipeInputIsUnsupported() {
        val sample = sample(AV)
        val (read, write) = ParcelFileDescriptor.createPipe()
        val writer = Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out -> sample.inputStream().use { it.copyTo(out) } }
            } catch (closedByReader: IOException) {
                // The reader gave up; expected.
            }
        }.apply { start() }
        val output = File(dir, "clip.mp4")
        try {
            val e = assertThrows(FFmpegException::class.java) {
                Trimmer.trim(MediaInput.of(read), MediaOutput.of(output), TimeRange(0.seconds, 1.seconds))
            }
            assertEquals(Reason.UNSUPPORTED_MEDIA, e.reason)
            assertFalse(output.exists())
        } finally {
            read.close()
            writer.join(5_000)
        }
    }

    @Test
    fun missingMediaStoreItem() {
        val missing = ContentUris.withAppendedId(videoCollection(), Int.MAX_VALUE.toLong())
        val output = File(dir, "clip.mp4")
        val e = assertThrows(FFmpegException::class.java) {
            Trimmer.trim(MediaInput.of(resolver, missing), MediaOutput.of(output), TimeRange(0.seconds, 1.seconds))
        }
        assertEquals(Reason.INPUT_NOT_FOUND, e.reason)
        assertFalse(output.exists())
    }

    @Test
    fun noDescriptorLeaks() {
        val sample = sample(AV)
        val uri = insertVideo(sample)
        val pipeAndMissing = {
            val (read, write) = ParcelFileDescriptor.createPipe()
            write.close()
            runCatching { Trimmer.trim(MediaInput.of(read), MediaOutput.of(File(dir, "p.mp4")), TimeRange(0.seconds, 1.seconds)) }
            read.close()
            runCatching {
                Trimmer.trim(
                    MediaInput.of(resolver, ContentUris.withAppendedId(videoCollection(), Int.MAX_VALUE.toLong())),
                    MediaOutput.of(File(dir, "m.mp4")),
                    TimeRange(0.seconds, 1.seconds),
                )
            }
        }
        val round = { i: Int ->
            Trimmer.trim(MediaInput.of(resolver, uri), MediaOutput.of(File(dir, "ok-$i.mp4")), TimeRange(2.seconds, 3.seconds))
            runCatching { Trimmer.trim(MediaInput.of(resolver, uri), MediaOutput.of(File(dir, "late-$i.mp4")), TimeRange.from(20.seconds)) }
            pipeAndMissing()
        }
        round(0) // warm-up: library loading, binder and MediaStore connections
        val before = openDescriptors()
        repeat(10) { round(it + 1) }
        val after = openDescriptors()
        assertTrue("open descriptors went from $before to $after over 10 rounds", after - before < 10)
    }

    private fun insertVideo(content: File?): Uri {
        assumeTrue("MediaStore pending items need Android 10", Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ffmpegkit-test-${System.nanoTime()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/FFmpegKitTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(videoCollection(), values)!!
        inserted += uri
        if (content != null) {
            resolver.openOutputStream(uri, "w")!!.use { out -> content.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    private fun videoCollection(): Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun copyOut(uri: Uri): File = File(dir, "copy-${System.nanoTime()}.mp4").also { file ->
        resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun packets(file: File) = MediaInspector.packets(file)

    private fun temporaryFiles() = context.cacheDir.list()!!.filter { it.startsWith("ffmpegkit-") }.toSet()

    private fun openDescriptors() = File("/proc/self/fd").list()!!.size

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
