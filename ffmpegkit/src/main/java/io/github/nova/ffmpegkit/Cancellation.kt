package io.github.nova.ffmpegkit

import java.io.Closeable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A cancellation flag shared with native code (remux::Cancellation). Native work polls it through
 * FFmpeg's interrupt callback and between packets, then stops and cleans up.
 */
internal class NativeCancellation : Closeable {
    private val lock = Any()
    private var handle: Long = create()

    @Volatile
    var isCancelled: Boolean = false
        private set

    /** The handle to pass to native functions; 0 once closed. */
    val nativeHandle: Long
        get() = synchronized(lock) { handle }

    fun cancel() {
        synchronized(lock) {
            isCancelled = true
            if (handle != 0L) cancel(handle)
        }
    }

    /** Throws [CancellationException] if [cancel] was called; checked before publishing outputs. */
    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("cancelled")
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) destroy(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("ffmpegkit")
        }

        @JvmStatic
        private external fun create(): Long

        @JvmStatic
        private external fun cancel(handle: Long)

        @JvmStatic
        private external fun destroy(handle: Long)
    }
}

/**
 * Runs blocking native [work] on [Dispatchers.IO]. If the calling coroutine is cancelled, the
 * native side is told to stop, and this only returns (by rethrowing the cancellation) once [work]
 * has finished cleaning up. Cancellation is never turned into an [FFmpegException].
 */
internal suspend fun <T> runNative(work: (NativeCancellation) -> T): T {
    val cancellation = NativeCancellation()
    try {
        return coroutineScope {
            val job = async(Dispatchers.IO) {
                try {
                    work(cancellation)
                } catch (e: Exception) {
                    // Work stopped by cancel() must end as a cancellation, not as the error it caused.
                    cancellation.throwIfCancelled()
                    throw e
                }
            }
            try {
                job.await()
            } catch (e: CancellationException) {
                cancellation.cancel()
                throw e // coroutineScope still waits for `job` to finish its cleanup
            }
        }
    } finally {
        cancellation.close()
    }
}
