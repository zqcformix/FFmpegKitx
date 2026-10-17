package io.github.nova.ffmpegkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that the info-query API, run against the .so files actually packaged for the device ABI,
 * reports what README documents (FFmpeg version, library versions, enabled features, license).
 * Update README and these expectations together when the prebuilt libraries change.
 */
@RunWith(AndroidJUnit4::class)
class PackagedLibrariesTest {

    @Test
    fun ffmpegVersionMatchesReadme() {
        assertEquals("8.0.1", FFmpegKit.getVersion())
    }

    @Test
    fun libraryVersionsMatchReadme() {
        FFmpegKit.printInfo()
        // printInfo() is the only API that reports per-library versions; read it back from our own log.
        val log = Runtime.getRuntime()
            .exec(arrayOf("logcat", "-d", "-v", "raw", "-s", "FFmpegKit:I"))
            .inputStream.bufferedReader().use { it.readLines() }
        val expected = mapOf(
            "libavcodec" to "62.11.100",
            "libavformat" to "62.3.100",
            "libavfilter" to "11.4.100",
            "libavutil" to "60.8.100",
            "libswresample" to "6.1.100",
            "libswscale" to "9.1.100",
        )
        for ((library, version) in expected) {
            val reported = log.lastOrNull { it.startsWith("$library ") }?.substringAfter(library)?.trim()
            assertEquals(library, version, reported)
        }
    }

    @Test
    fun buildConfigurationMatchesReadme() {
        val flags = FFmpegKit.getBuildConfiguration().split(' ').toSet()
        listOf(
            "--enable-libass",
            "--enable-libfreetype",
            "--enable-libfribidi",
            "--enable-mediacodec",
            "--enable-jni",
            "--enable-shared",
            "--disable-programs",
            "--disable-avdevice",
        ).forEach { assertTrue("missing $it", it in flags) }
        // README documents an LGPL build without GPL, version 3 or nonfree components.
        listOf("--enable-gpl", "--enable-version3", "--enable-nonfree")
            .forEach { assertFalse("unexpected $it", it in flags) }
    }

    @Test
    fun libassIsReportedAvailable() {
        assertTrue(FFmpegKit.isLibassAvailable())
        assertTrue(FFmpegKit.checkLibassAvailability().startsWith("libass: available"))
    }
}
