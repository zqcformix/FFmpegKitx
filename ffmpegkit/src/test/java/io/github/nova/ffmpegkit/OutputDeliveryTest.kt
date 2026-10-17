package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutputDeliveryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var target: File

    @Before
    fun setUp() {
        dir = folder.newFolder("out")
        target = File(dir, "clip.mp4")
    }

    @Test
    fun publishesWithHardLinkAndRemovesTemp() {
        val delivered = OutputDelivery.deliver(null, target) { temp ->
            temp.writeBytes(CONTENT)
            "value"
        }
        assertEquals("value", delivered.value)
        assertEquals(DeliveryMethod.HARD_LINK, delivered.method)
        assertArrayEquals(CONTENT, target.readBytes())
        assertOnlyFiles("clip.mp4")
    }

    @Test
    fun tempIsHiddenNextToTargetAndKeepsExtension() {
        OutputDelivery.deliver(null, target) { temp ->
            assertEquals(dir.canonicalFile, temp.canonicalFile.parentFile)
            assertTrue(temp.name, temp.name.startsWith(".clip.") && temp.name.endsWith(".part.mp4"))
            assertTrue(temp.exists())
            assertFalse(target.exists())
        }
    }

    @Test
    fun longNamesStillGetATempFile() {
        val long = File(dir, "长".repeat(60) + ".mkv")
        OutputDelivery.deliver(null, long) { it.writeBytes(CONTENT) }
        assertArrayEquals(CONTENT, long.readBytes())
    }

    @Test
    fun reportsTheAtomicMethodUsed() {
        val delivered = OutputDelivery.deliver(
            null, target,
            publish = { t, temp -> Files.move(temp, t); DeliveryMethod.RENAME },
        ) { it.writeBytes(CONTENT) }
        assertEquals(DeliveryMethod.RENAME, delivered.method)
        assertArrayEquals(CONTENT, target.readBytes())
        assertOnlyFiles("clip.mp4")
    }

    @Test
    fun copiesWhenAtomicPublishIsUnsupported() {
        val delivered = OutputDelivery.deliver(null, target, publish = { _, _ -> throw UnsupportedOperationException() }) {
            it.writeBytes(CONTENT)
        }
        assertEquals(DeliveryMethod.COPY, delivered.method)
        assertArrayEquals(CONTENT, target.readBytes())
        assertOnlyFiles("clip.mp4")
    }

    @Test
    fun copiesWhenAtomicPublishIsRefused() {
        val delivered = OutputDelivery.deliver(
            null, target,
            publish = { t, _ -> throw FileSystemException(t.toString(), null, "Operation not permitted") },
        ) { it.writeBytes(CONTENT) }
        assertEquals(DeliveryMethod.COPY, delivered.method)
        assertArrayEquals(CONTENT, target.readBytes())
    }

    @Test
    fun existingTargetIsNeverWritten() {
        target.writeBytes(EXISTING)
        var wrote = false
        val e = assertThrows(FFmpegException::class.java) {
            OutputDelivery.deliver(null, target) { wrote = true }
        }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
        assertFalse(wrote)
        assertArrayEquals(EXISTING, target.readBytes())
        assertOnlyFiles("clip.mp4")
    }

    @Test
    fun targetThatIsTheInputIsReported() {
        target.writeBytes(EXISTING)
        val e = assertThrows(FFmpegException::class.java) {
            OutputDelivery.deliver(target, File(dir, "./clip.mp4")) { }
        }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
        assertTrue(e.message, e.message!!.startsWith("output is the input file"))
        assertArrayEquals(EXISTING, target.readBytes())
    }

    @Test
    fun targetCreatedWhileWritingWinsWithHardLink() {
        assertConcurrentTargetWins(publish = null)
    }

    @Test
    fun targetCreatedWhileWritingWinsWithCopy() {
        assertConcurrentTargetWins(publish = { _, _ -> throw UnsupportedOperationException() })
    }

    @Test
    fun missingDirectory() {
        var wrote = false
        val e = assertThrows(FFmpegException::class.java) {
            OutputDelivery.deliver(null, File(dir, "missing/clip.mp4")) { wrote = true }
        }
        assertEquals(Reason.IO, e.reason)
        assertFalse(wrote)
    }

    @Test
    fun writeFailurePropagatesUnchangedAndCleansUp() {
        val failure = FFmpegException(Reason.INVALID_MEDIA, "bad input")
        val thrown = assertThrows(FFmpegException::class.java) {
            OutputDelivery.deliver(null, target) { temp ->
                temp.writeBytes(CONTENT)
                throw failure
            }
        }
        assertSame(failure, thrown)
        assertOnlyFiles()
    }

    @Test
    fun cancellationPropagatesUnwrappedAndCleansUp() {
        val cancellation = CancellationException("cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            OutputDelivery.deliver(null, target) { temp ->
                temp.writeBytes(CONTENT)
                throw cancellation
            }
        }
        assertSame(cancellation, thrown)
        assertOnlyFiles()
    }

    @Test
    fun failedCopyRemovesTheOutputItCreated() {
        val e = assertThrows(FFmpegException::class.java) {
            OutputDelivery.deliver(
                null, target,
                publish = { _, _ -> throw UnsupportedOperationException() },
                copy = ::halfCopyThenFail,
                identity = { path -> path.fileName.toString() }, // stable identity for this test
            ) { it.writeBytes(CONTENT) }
        }
        assertEquals(Reason.IO, e.reason)
        assertEquals(0, e.suppressed.size)
        assertOnlyFiles()
    }

    @Test
    fun failedCopyKeepsAnOutputItCannotIdentify() {
        val e = assertThrows(FFmpegException::class.java) {
            OutputDelivery.deliver(
                null, target,
                publish = { _, _ -> throw UnsupportedOperationException() },
                copy = ::halfCopyThenFail,
                identity = { null },
            ) { it.writeBytes(CONTENT) }
        }
        assertEquals(Reason.IO, e.reason)
        assertTrue(e.suppressed.single().message!!.startsWith("left incomplete output"))
        assertOnlyFiles("clip.mp4")
    }

    @Test
    fun failedCopyKeepsAFileReplacedByAnotherWriter() {
        var calls = 0
        val e = assertThrows(FFmpegException::class.java) {
            OutputDelivery.deliver(
                null, target,
                publish = { _, _ -> throw UnsupportedOperationException() },
                copy = ::halfCopyThenFail,
                identity = { "identity-${calls++}" }, // changes between creation and cleanup
            ) { it.writeBytes(CONTENT) }
        }
        assertTrue(e.suppressed.single().message!!.startsWith("left incomplete output"))
        assertTrue(target.exists())
    }

    private fun assertConcurrentTargetWins(publish: ((Path, Path) -> DeliveryMethod)?) {
        val write: (File) -> Unit = { temp ->
            temp.writeBytes(CONTENT)
            target.writeBytes(EXISTING) // another writer creates the target meanwhile
        }
        val e = assertThrows(FFmpegException::class.java) {
            if (publish == null) OutputDelivery.deliver(null, target, write = write)
            else OutputDelivery.deliver(null, target, publish = publish, write = write)
        }
        assertEquals(Reason.OUTPUT_EXISTS, e.reason)
        assertArrayEquals(EXISTING, target.readBytes())
        assertOnlyFiles("clip.mp4")
    }

    private fun assertOnlyFiles(vararg names: String) {
        assertEquals(names.toSet(), dir.list()!!.toSet())
    }

    private companion object {
        val CONTENT = ByteArray(64 * 1024) { (it % 251).toByte() }
        val EXISTING = byteArrayOf(9, 9, 9)

        fun halfCopyThenFail(@Suppress("UNUSED_PARAMETER") source: Path, target: FileChannel) {
            target.write(ByteBuffer.wrap(CONTENT, 0, CONTENT.size / 2))
            throw IOException("device went away")
        }
    }
}
