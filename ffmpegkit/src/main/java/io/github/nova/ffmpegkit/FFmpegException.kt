package io.github.nova.ffmpegkit

import java.io.IOException

/**
 * Failure of a media operation.
 *
 * Invalid arguments throw [IllegalArgumentException] before any work starts, and coroutine
 * cancellation propagates as `CancellationException`; neither is wrapped in this type, so catching
 * [FFmpegException] never swallows a cancellation.
 *
 * ```kotlin
 * try {
 *     input.trimTo(output, 2.seconds, 8.seconds)
 * } catch (e: FFmpegException) {
 *     when (e.reason) {
 *         FFmpegException.Reason.OUTPUT_EXISTS -> askBeforeOverwrite(output)
 *         else -> showError(e)
 *     }
 * }
 * ```
 *
 * New [Reason] values may be added in later versions, so keep an `else` branch.
 *
 * @property reason what went wrong, for deciding how to react.
 * @property nativeErrorCode the FFmpeg `AVERROR` value when the failure came from FFmpeg, for
 *   diagnostics only; do not branch on it.
 */
class FFmpegException(
    val reason: Reason,
    message: String,
    val nativeErrorCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Reason {
        /** The input does not exist. */
        INPUT_NOT_FOUND,

        /** The input or output exists but cannot be read or written with the current permissions. */
        ACCESS_DENIED,

        /** The output already exists; it is never overwritten. */
        OUTPUT_EXISTS,

        /** The output storage is full or over quota. */
        INSUFFICIENT_SPACE,

        /** The requested start is at or beyond the end of the media. */
        RANGE_OUTSIDE_MEDIA,

        /** The media is valid but this build cannot handle it (format, codec, container or stream layout). */
        UNSUPPORTED_MEDIA,

        /** The input is corrupt, truncated or not a media file. */
        INVALID_MEDIA,

        /** Any other read or write failure. */
        IO,

        /** An FFmpeg failure that fits no other reason; see [nativeErrorCode]. */
        NATIVE,
    }

    override fun toString(): String {
        val code = nativeErrorCode?.let { ", nativeErrorCode=$it" }.orEmpty()
        return "FFmpegException($reason$code): $message"
    }
}
