package io.github.nova.ffmpegkit

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Output delivery on the device's real file systems. */
@RunWith(AndroidJUnit4::class)
class OutputDeliveryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dirs = mutableListOf<File>()

    @Before
    fun setUp() {
        dirs += File(context.cacheDir, "delivery-test")
        context.getExternalFilesDir(null)?.let { dirs += File(it, "delivery-test") }
        dirs.forEach { it.deleteRecursively(); it.mkdirs() }
    }

    @After
    fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun internalStoragePublishesAtomicallyFromAndroid11() {
        val target = File(dirs[0], "clip.mp4")
        val delivered = OutputDelivery.deliver(null, target) { it.writeBytes(CONTENT) }
        // renameat2 is in libc from API 30; older releases fall back to a hard link or a copy.
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(DeliveryMethod.RENAME, delivered.method)
        } else {
            assertNotEquals(DeliveryMethod.RENAME, delivered.method)
        }
        assertArrayEquals(CONTENT, target.readBytes())
        assertEquals(listOf("clip.mp4"), dirs[0].list()!!.toList())
    }

    @Test
    fun recordsWhyHardLinksAreNotUsed() {
        val existing = File(dirs[0], "existing").apply { writeBytes(CONTENT) }
        val outcome = runCatching { Files.createLink(File(dirs[0], "link").toPath(), existing.toPath()) }
        Log.i("FFmpegKitTest", "hard link in app storage: ${outcome.exceptionOrNull() ?: "allowed"}")
    }

    @Test
    fun externalStorageDeliversWithWhicheverMethodItSupports() {
        val dir = dirs.getOrNull(1) ?: return // no external storage mounted
        val target = File(dir, "clip.mp4")
        val delivered = OutputDelivery.deliver(null, target) { it.writeBytes(CONTENT) }
        Log.i("FFmpegKitTest", "external storage delivery: ${delivered.method} ($dir)")
        assertArrayEquals(CONTENT, target.readBytes())
        assertEquals(listOf("clip.mp4"), dir.list()!!.toList())
    }

    private companion object {
        val CONTENT = ByteArray(256 * 1024) { (it % 251).toByte() }
    }
}
