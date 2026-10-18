package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.MediaLog.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Log entries from native code, levels, redaction and options (roadmap 5.2). */
class MediaLogTest {

    @Test
    fun levelsMapFromFFmpeg() {
        // AV_LOG_PANIC, FATAL, ERROR, WARNING, INFO, VERBOSE, DEBUG, TRACE
        assertEquals(
            listOf(Level.ERROR, Level.ERROR, Level.ERROR, Level.WARNING, Level.INFO, Level.VERBOSE, Level.DEBUG, Level.DEBUG),
            listOf(0, 8, 16, 24, 32, 40, 48, 56).map(Level::of),
        )
        // Every captured level maps back to itself.
        Level.entries.forEach { assertEquals(it, Level.of(it.avLevel)) }
    }

    @Test
    fun nativeEntriesAreDecodedAsUtf8() {
        val log = NativeLog(
            intArrayOf(16, 24),
            arrayOf("mov,mp4,m4a,3gp,3g2,mj2".toByteArray(), ByteArray(0)),
            arrayOf("moov atom not found".toByteArray(), byteArrayOf(0x61, 0xE6.toByte(), 0x96.toByte())), // "a" + a cut-off "文"
            3,
        ).toMediaLog(null)
        assertEquals(3, log.droppedCount)
        assertEquals(Level.ERROR, log.entries[0].level)
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", log.entries[0].component)
        assertEquals("moov atom not found", log.entries[0].message)
        assertNull(log.entries[1].component)
        assertEquals("a�", log.entries[1].message)
        assertEquals("(3 earlier entries dropped)\nERROR [mov,mp4,m4a,3gp,3g2,mj2] moov atom not found\nWARNING: a�", log.toString())
    }

    @Test
    fun redactionReplacesKnownPathsLongestFirst() {
        val redactions = Redactions().apply {
            add("/data/user/0/app/files", Redactions.OUTPUT)
            add("/data/user/0/app/files/in 视频.mp4", Redactions.INPUT)
            add(null, Redactions.INPUT)
            add("", Redactions.INPUT)
        }
        assertEquals(
            "Opening '<input>' and '<output>/0001.mp4'",
            redactions.apply("Opening '/data/user/0/app/files/in 视频.mp4' and '/data/user/0/app/files/0001.mp4'"),
        )
        val log = NativeLog(intArrayOf(24), arrayOf(ByteArray(0)), arrayOf("at /data/user/0/app/files/x".toByteArray()), 0)
        assertEquals("at <output>/x", log.toMediaLog(redactions).entries.single().message)
        assertEquals("at /data/user/0/app/files/x", log.toMediaLog(null).entries.single().message)
    }

    @Test
    fun optionsBoundTheEntries() {
        val defaults = MediaLog.Options()
        assertEquals(Level.WARNING, defaults.level)
        assertEquals(100, defaults.maxEntries)
        assertEquals(true, defaults.redactPaths)
        MediaLog.Options(maxEntries = 0)
        MediaLog.Options(maxEntries = 10_000)
        assertThrows(IllegalArgumentException::class.java) { MediaLog.Options(maxEntries = -1) }
        assertThrows(IllegalArgumentException::class.java) { MediaLog.Options(maxEntries = 10_001) }
    }
}
