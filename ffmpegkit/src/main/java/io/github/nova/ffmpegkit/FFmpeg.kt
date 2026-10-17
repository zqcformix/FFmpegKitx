package io.github.nova.ffmpegkit

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import kotlin.time.Duration

/**
 * Editing operations. Each one suspends while the work runs on `Dispatchers.IO`, so it can be
 * called from the main thread. Cancelling the calling coroutine stops the native work; the call
 * returns (with the `CancellationException`) only after temporary files are removed, and nothing is
 * published. Failures throw [FFmpegException]; invalid arguments throw [IllegalArgumentException]
 * before any work starts.
 *
 * The native library is loaded on first use.
 */
object FFmpeg {

    /** Fast (stream copy) trim; see [TrimResult] for how the actual range relates to the request. */
    suspend fun trim(request: TrimRequest): TrimResult = runNative { cancellation ->
        workerThreadObserver?.invoke(Thread.currentThread())
        Trimmer.trim(request.input, request.output, request.range, cancellation)
    }

    /** Splits the input into segments; see [SplitRequest.byDuration] and [SplitRequest.byRanges]. */
    suspend fun split(request: SplitRequest): SplitResult = runNative { cancellation ->
        workerThreadObserver?.invoke(Thread.currentThread())
        val ranges = request.ranges
        if (ranges != null) {
            Splitter.splitByRanges(
                request.input,
                request.outputDirectory,
                ranges,
                request.fileNamePrefix,
                request.containerExtension,
                cancellation,
            )
        } else {
            Splitter.split(
                request.input,
                request.outputDirectory,
                request.segmentDuration!!,
                request.fileNamePrefix,
                request.containerExtension,
                cancellation,
            )
        }
    }

    /**
     * Reads the container and stream headers of [input] without decoding; see [MediaInfo]. Unlike trim
     * and split, any number and kind of streams is accepted.
     */
    suspend fun probe(input: MediaInput): MediaInfo = runNative { cancellation ->
        workerThreadObserver?.invoke(Thread.currentThread())
        Prober.probe(input, cancellation)
    }

    /** Test hook: sees the thread each operation's work runs on. */
    @Volatile
    internal var workerThreadObserver: ((Thread) -> Unit)? = null
}

/**
 * Trims [input] to [range] without re-encoding and writes [output]. The output extension (or, for a
 * Uri, the container extension) selects the container; the streams must fit it.
 */
class TrimRequest(
    val input: MediaInput,
    val output: MediaOutput,
    val range: TimeRange,
)

/** Describes a split; create one with [byDuration] or [byRanges]. */
class SplitRequest private constructor(
    val input: MediaInput,
    val outputDirectory: File,
    /** Set by [byDuration], null for [byRanges]. */
    val segmentDuration: Duration?,
    /** Set by [byRanges], null for [byDuration]. */
    val ranges: List<TimeRange>?,
    val fileNamePrefix: String,
    val containerExtension: String,
) {
    companion object {
        /**
         * Keyframe-aligned split into `<fileNamePrefix>-0001.<containerExtension>`, `-0002`… in
         * [outputDirectory]. Each segment but the last is at least [segmentDuration] long and starts
         * at a video keyframe (audio-only input: at a packet boundary); together the segments hold
         * exactly the input's packets. At most 9,999 segments.
         *
         * @throws IllegalArgumentException if [segmentDuration] is not positive, finite and a whole
         *   number of microseconds, [fileNamePrefix] is empty or contains `/`, or
         *   [containerExtension] is not letters and digits.
         */
        fun byDuration(
            input: MediaInput,
            outputDirectory: File,
            segmentDuration: Duration,
            fileNamePrefix: String,
            containerExtension: String,
        ): SplitRequest {
            Splitter.validate(segmentDuration, fileNamePrefix, containerExtension)
            return SplitRequest(input, outputDirectory, segmentDuration, null, fileNamePrefix, containerExtension)
        }

        /**
         * One segment per range, `<fileNamePrefix>-0001.<containerExtension>`… numbered in the order
         * of [ranges] (which may be in any order and may overlap). Each segment is exactly what
         * [FFmpeg.trim] writes for its range: it starts at the keyframe at or before the range's
         * start, so a segment usually begins before the previous range's end and adjacent segments
         * overlap; this is not an exact cut. If any range fails, for example because it starts
         * beyond the media, nothing is published.
         *
         * @throws IllegalArgumentException if [ranges] is empty or has more than 9,999 ranges,
         *   [fileNamePrefix] is empty or contains `/`, or [containerExtension] is not letters and digits.
         */
        fun byRanges(
            input: MediaInput,
            outputDirectory: File,
            ranges: List<TimeRange>,
            fileNamePrefix: String,
            containerExtension: String,
        ): SplitRequest {
            val copy = ranges.toList()
            Splitter.validate(copy, fileNamePrefix, containerExtension)
            return SplitRequest(input, outputDirectory, null, copy, fileNamePrefix, containerExtension)
        }
    }
}

/**
 * Result of [FFmpeg.trim]. [actualRange] is what was written, on the input's timeline from its start:
 * it begins at the keyframe at or before [requestedRange]'s start and may end slightly after the
 * requested end because of B-frame reordering. The output is complete when the call returns.
 */
class TrimResult internal constructor(
    val requestedRange: TimeRange,
    val actualRange: TimeRange,
    val output: MediaOutput,
) {
    override fun toString(): String = "TrimResult(requested=$requestedRange, actual=$actualRange, output=$output)"
}

/**
 * Result of [FFmpeg.split]: the published segments in time order ([SplitRequest.byDuration]) or in
 * the order of the requested ranges ([SplitRequest.byRanges]).
 */
class SplitResult internal constructor(val segments: List<SplitSegment>) {
    override fun toString(): String = "SplitResult($segments)"
}

/**
 * One published segment; [actualRange] is on the input's timeline from its start. [requestedRange]
 * is the range it was cut for by [SplitRequest.byRanges], null for [SplitRequest.byDuration].
 */
class SplitSegment internal constructor(val file: File, val actualRange: TimeRange, val requestedRange: TimeRange?) {
    override fun toString(): String =
        if (requestedRange == null) "SplitSegment($file, $actualRange)" else "SplitSegment($file, requested=$requestedRange, actual=$actualRange)"
}

/** Trims this file to [start, endExclusive) (to the end of the media if [endExclusive] is null) into [output]. */
suspend fun File.trimTo(output: File, start: Duration, endExclusive: Duration? = null): TrimResult =
    FFmpeg.trim(TrimRequest(MediaInput.of(this), MediaOutput.of(output), TimeRange(start, endExclusive)))

/** Trims the content behind this Uri to [start, endExclusive) into [output]. */
suspend fun Uri.trimTo(resolver: ContentResolver, output: File, start: Duration, endExclusive: Duration? = null): TrimResult =
    FFmpeg.trim(TrimRequest(MediaInput.of(resolver, this), MediaOutput.of(output), TimeRange(start, endExclusive)))

/** Splits this file; see [SplitRequest.byDuration]. */
suspend fun File.splitTo(
    outputDirectory: File,
    segmentDuration: Duration,
    fileNamePrefix: String,
    containerExtension: String,
): SplitResult = FFmpeg.split(
    SplitRequest.byDuration(MediaInput.of(this), outputDirectory, segmentDuration, fileNamePrefix, containerExtension),
)

/** Splits the content behind this Uri; see [SplitRequest.byDuration]. */
suspend fun Uri.splitTo(
    resolver: ContentResolver,
    outputDirectory: File,
    segmentDuration: Duration,
    fileNamePrefix: String,
    containerExtension: String,
): SplitResult = FFmpeg.split(
    SplitRequest.byDuration(MediaInput.of(resolver, this), outputDirectory, segmentDuration, fileNamePrefix, containerExtension),
)

/** Splits this file into one segment per range; see [SplitRequest.byRanges]. */
suspend fun File.splitTo(
    outputDirectory: File,
    ranges: List<TimeRange>,
    fileNamePrefix: String,
    containerExtension: String,
): SplitResult = FFmpeg.split(
    SplitRequest.byRanges(MediaInput.of(this), outputDirectory, ranges, fileNamePrefix, containerExtension),
)

/** Splits the content behind this Uri into one segment per range; see [SplitRequest.byRanges]. */
suspend fun Uri.splitTo(
    resolver: ContentResolver,
    outputDirectory: File,
    ranges: List<TimeRange>,
    fileNamePrefix: String,
    containerExtension: String,
): SplitResult = FFmpeg.split(
    SplitRequest.byRanges(MediaInput.of(resolver, this), outputDirectory, ranges, fileNamePrefix, containerExtension),
)

/** Probes this file; see [FFmpeg.probe]. */
suspend fun File.probe(): MediaInfo = FFmpeg.probe(MediaInput.of(this))

/** Probes the content behind this Uri; see [FFmpeg.probe]. */
suspend fun Uri.probe(resolver: ContentResolver): MediaInfo = FFmpeg.probe(MediaInput.of(resolver, this))
