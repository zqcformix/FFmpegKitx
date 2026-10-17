package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Host-side parts of splitting: argument checks, the up-front collision check and rollback. */
class SplitterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var work: File
    private lateinit var out: File
    private val input = File("input.mp4")

    @Before
    fun setUp() {
        work = folder.newFolder("work")
        out = folder.newFolder("out")
    }

    @Test
    fun rejectsInvalidArgumentsBeforeAnyWork() {
        assertInvalid { split(segment = Duration.ZERO) }
        assertInvalid { split(segment = (-1).seconds) }
        assertInvalid { split(segment = Duration.INFINITE) }
        assertInvalid { split(segment = 1_500.nanoseconds) }
        assertInvalid { split(prefix = "") }
        assertInvalid { split(prefix = "a/b") }
        assertInvalid { split(prefix = "..") }
        assertInvalid { split(extension = "") }
        assertInvalid { split(extension = ".mp4") }
        assertInvalid { split(extension = "mp 4") }
        assertEquals(emptyList<String>(), out.list()!!.toList())
    }

    @Test
    fun refusesWhenAnyFileOfTheNamingSchemeExists() {
        File(out, "clip-0003.mp4").writeBytes(EXISTING)
        File(out, "clip-final.mp4").writeBytes(EXISTING) // not part of the scheme
        val e = assertThrows(FFmpegException::class.java) { split() }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
        assertTrue(e.message, e.message!!.endsWith("clip-0003.mp4"))
        assertEquals(setOf("clip-0003.mp4", "clip-final.mp4"), out.list()!!.toSet()) // no temporary directory
    }

    @Test
    fun missingOutputDirectory() {
        val e = assertThrows(FFmpegException::class.java) {
            Splitter.split(input, File(out, "missing"), 1.seconds, "clip", "mp4")
        }
        assertEquals(Reason.IO, e.reason)
    }

    @Test
    fun publishesAllInOrder() {
        val files = segments(3)
        SegmentPublisher.publishAll(input, files)
        files.forEachIndexed { i, (_, target) -> assertArrayEquals(content(i), target.readBytes()) }
    }

    @Test
    fun failureRemovesWhatThisCallPublished() {
        val files = segments(3)
        val failure = IOException("disk gone")
        val thrown = assertThrows(IOException::class.java) {
            SegmentPublisher.publishAll(input, files, publish = failingAt(2, failure), identity = { it.name })
        }
        assertSame(failure, thrown)
        assertEquals(0, thrown.suppressed.size)
        assertEquals(emptyList<String>(), out.list()!!.toList())
    }

    @Test
    fun existingTargetStopsPublishingAndIsLeftAlone() {
        val files = segments(3)
        files[1].second.writeBytes(EXISTING)
        val e = assertThrows(FFmpegException::class.java) {
            SegmentPublisher.publishAll(input, files, identity = { it.name })
        }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
        assertEquals(listOf("clip-0002.mp4"), out.list()!!.toList())
        assertArrayEquals(EXISTING, files[1].second.readBytes())
    }

    @Test
    fun segmentsThatChangedAfterPublishingAreKept() {
        val files = segments(3)
        var calls = 0
        val e = assertThrows(IOException::class.java) {
            SegmentPublisher.publishAll(
                input, files,
                publish = failingAt(2, IOException("disk gone")),
                identity = { "identity-${calls++}" }, // never matches the recorded one
            )
        }
        assertEquals(2, e.suppressed.size)
        assertTrue(e.suppressed.all { it.message!!.startsWith("left published segment") })
        assertEquals(setOf("clip-0001.mp4", "clip-0002.mp4"), out.list()!!.toSet())
    }

    @Test
    fun cancellationRollsBackAndPropagatesUnwrapped() {
        val files = segments(2)
        val cancellation = CancellationException("cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            SegmentPublisher.publishAll(input, files, publish = failingAt(1, cancellation), identity = { it.name })
        }
        assertSame(cancellation, thrown)
        assertEquals(emptyList<String>(), out.list()!!.toList())
    }

    private fun split(segment: Duration = 1.seconds, prefix: String = "clip", extension: String = "mp4") =
        Splitter.split(input, out, segment, prefix, extension)

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    private fun segments(count: Int): List<Pair<File, File>> = (0 until count).map { i ->
        val name = String.format("%04d", i + 1)
        File(work, "$name.mp4").apply { writeBytes(content(i)) } to File(out, "clip-$name.mp4")
    }

    /** Publishes normally, but throws [failure] instead of publishing the segment at [index]. */
    private fun failingAt(index: Int, failure: Throwable): (File, File) -> Unit {
        var calls = 0
        return { temp, target ->
            if (calls++ == index) throw failure
            OutputDelivery.publishFile(temp, target)
        }
    }

    private companion object {
        val EXISTING = byteArrayOf(9, 9, 9)
        fun content(i: Int) = ByteArray(1024) { (it + i).toByte() }
    }
}
