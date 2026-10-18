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
 * Trim and split take an optional `onProgress` callback; see [MediaProgress]. It runs on an IO
 * worker thread, never concurrently with itself and never after the call returned, so it may update
 * a `StateFlow` but not views. An exception it throws stops the work and is rethrown by the call.
 *
 * The native library is loaded on first use.
 */
object FFmpeg {

    /** Fast (stream copy) trim; see [TrimResult] for how the actual range relates to the request. */
    suspend fun trim(request: TrimRequest): TrimResult = trim(request, null)

    /** [trim] reporting progress to [onProgress]; see [MediaProgress]. */
    suspend fun trim(request: TrimRequest, onProgress: ((MediaProgress) -> Unit)?): TrimResult = runNative(
        "trim",
        onProgress?.let {
            val copies = request.output is MediaOutput.ToUri || OutputDelivery.expectsCopy
            ProgressReporter(ProgressTimeline.ranges(listOf(request.range)), copies, it)
        },
    ) { task ->
        workerThreadObserver?.invoke(Thread.currentThread())
        Trimmer.trim(request.input, request.output, request.range, task, request.mode)
    }

    /** Splits the input into segments; see [SplitRequest.byDuration] and [SplitRequest.byRanges]. */
    suspend fun split(request: SplitRequest): SplitResult = split(request, null)

    /** [split] reporting progress to [onProgress]; see [MediaProgress]. */
    suspend fun split(request: SplitRequest, onProgress: ((MediaProgress) -> Unit)?): SplitResult {
        val ranges = request.ranges
        val timeline = if (ranges != null) ProgressTimeline.ranges(ranges) else ProgressTimeline.WHOLE_INPUT
        return runNative("split", onProgress?.let { ProgressReporter(timeline, OutputDelivery.expectsCopy, it) }) { task ->
            workerThreadObserver?.invoke(Thread.currentThread())
            if (ranges != null) {
                Splitter.splitByRanges(
                    request.input,
                    request.outputDirectory,
                    ranges,
                    request.fileNamePrefix,
                    request.containerExtension,
                    task,
                )
            } else {
                Splitter.split(
                    request.input,
                    request.outputDirectory,
                    request.segmentDuration!!,
                    request.fileNamePrefix,
                    request.containerExtension,
                    task,
                )
            }
        }
    }

    /**
     * Reads the container and stream headers of [input] without decoding; see [MediaInfo]. Unlike trim
     * and split, any number and kind of streams is accepted.
     */
    suspend fun probe(input: MediaInput): MediaInfo = runNative("probe") { task ->
        workerThreadObserver?.invoke(Thread.currentThread())
        Prober.probe(input, task)
    }

    /**
     * How operations started from now on capture FFmpeg's log; see [MediaLog]. By default warnings
     * and errors, the latest 100 entries per operation, with paths redacted.
     */
    @Volatile
    var logOptions: MediaLog.Options = MediaLog.Options()

    /**
     * The longest a single read from a `Uri` or [android.os.ParcelFileDescriptor] input may take
     * before the operation fails with [FFmpegException.Reason.IO]; null (the default) for no limit.
     * Applies to operations started from now on. Cancellation stops a stuck read either way. Local
     * file inputs are read by FFmpeg directly and are not affected.
     *
     * @throws IllegalArgumentException if set to a value that is not positive and finite.
     */
    @Volatile
    var readTimeout: Duration? = null
        set(value) {
            require(value == null || (value.isPositive() && value.isFinite())) { "readTimeout must be positive and finite: $value" }
            field = value
        }

    /** Test hook: sees the thread each operation's work runs on. */
    @Volatile
    internal var workerThreadObserver: ((Thread) -> Unit)? = null

    /** Test hook: sees every session state change, on the thread that made it. */
    @Volatile
    internal var sessionListener: ((Session, Session.State) -> Unit)? = null
}

/**
 * Trims [input] to [range] without re-encoding and writes [output]. The output extension (or, for a
 * Uri, the container extension) selects the container; the streams must fit it.
 *
 * @throws IllegalArgumentException if [mode] is [TrimMode.EDIT_LIST] and the output is not MP4/MOV.
 */
class TrimRequest(
    val input: MediaInput,
    val output: MediaOutput,
    val range: TimeRange,
    val mode: TrimMode = TrimMode.KEYFRAME,
) {
    init {
        if (mode == TrimMode.EDIT_LIST) {
            val extension = when (output) {
                is MediaOutput.ToFile -> output.file.extension
                is MediaOutput.ToUri -> output.containerExtension
            }
            require(extension.lowercase() in TrimMode.EDIT_LIST_EXTENSIONS) {
                "TrimMode.EDIT_LIST needs an MP4 or MOV output (${TrimMode.EDIT_LIST_EXTENSIONS.joinToString()}), not \"$extension\""
            }
        }
    }
}

/** Where a trim's output starts; see [FFmpeg.trim]. */
enum class TrimMode {
    /**
     * The output starts at the keyframe at or before the requested start ([TrimResult.actualRange]),
     * so it may show up to one GOP before it. Works with every container and player.
     */
    KEYFRAME,

    /**
     * MP4/MOV only. The output holds the same data as [KEYFRAME], but its timeline starts at the
     * requested start: the frames before it get negative timestamps and an edit list tells players to
     * decode but not show them. Players that ignore edit lists show those frames, or start with a delay.
     */
    EDIT_LIST;

    internal companion object {
        val EDIT_LIST_EXTENSIONS = setOf("mp4", "m4v", "m4a", "mov", "3gp", "3g2")
    }
}

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
 * requested end because of B-frame reordering. With [TrimMode.EDIT_LIST] the same data is written,
 * but players that honour edit lists start showing at [requestedRange]'s start. The output is
 * complete when the call returns.
 */
class TrimResult internal constructor(
    val requestedRange: TimeRange,
    val actualRange: TimeRange,
    val output: MediaOutput,
    /** What FFmpeg logged during the trim, e.g. warnings about the input; see [MediaLog]. */
    val log: MediaLog = MediaLog.EMPTY,
) {
    override fun toString(): String = "TrimResult(requested=$requestedRange, actual=$actualRange, output=$output)"
}

/**
 * Result of [FFmpeg.split]: the published segments in time order ([SplitRequest.byDuration]) or in
 * the order of the requested ranges ([SplitRequest.byRanges]).
 */
class SplitResult internal constructor(
    val segments: List<SplitSegment>,
    /** What FFmpeg logged during the split, over all segments; see [MediaLog]. */
    val log: MediaLog = MediaLog.EMPTY,
) {
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

/**
 * Trims this file to [start, endExclusive) (to the end of the media if [endExclusive] is null) into
 * [output], reporting to [onProgress] if given; see [FFmpeg.trim].
 */
suspend fun File.trimTo(
    output: File,
    start: Duration,
    endExclusive: Duration? = null,
    mode: TrimMode = TrimMode.KEYFRAME,
    onProgress: ((MediaProgress) -> Unit)? = null,
): TrimResult = FFmpeg.trim(TrimRequest(MediaInput.of(this), MediaOutput.of(output), TimeRange(start, endExclusive), mode), onProgress)

/** Trims the content behind this Uri to [start, endExclusive) into [output]; see [File.trimTo]. */
suspend fun Uri.trimTo(
    resolver: ContentResolver,
    output: File,
    start: Duration,
    endExclusive: Duration? = null,
    mode: TrimMode = TrimMode.KEYFRAME,
    onProgress: ((MediaProgress) -> Unit)? = null,
): TrimResult = FFmpeg.trim(
    TrimRequest(MediaInput.of(resolver, this), MediaOutput.of(output), TimeRange(start, endExclusive), mode),
    onProgress,
)

/** Splits this file; see [SplitRequest.byDuration]. */
suspend fun File.splitTo(
    outputDirectory: File,
    segmentDuration: Duration,
    fileNamePrefix: String,
    containerExtension: String,
    onProgress: ((MediaProgress) -> Unit)? = null,
): SplitResult = FFmpeg.split(
    SplitRequest.byDuration(MediaInput.of(this), outputDirectory, segmentDuration, fileNamePrefix, containerExtension),
    onProgress,
)

/** Splits the content behind this Uri; see [SplitRequest.byDuration]. */
suspend fun Uri.splitTo(
    resolver: ContentResolver,
    outputDirectory: File,
    segmentDuration: Duration,
    fileNamePrefix: String,
    containerExtension: String,
    onProgress: ((MediaProgress) -> Unit)? = null,
): SplitResult = FFmpeg.split(
    SplitRequest.byDuration(MediaInput.of(resolver, this), outputDirectory, segmentDuration, fileNamePrefix, containerExtension),
    onProgress,
)

/** Splits this file into one segment per range; see [SplitRequest.byRanges]. */
suspend fun File.splitTo(
    outputDirectory: File,
    ranges: List<TimeRange>,
    fileNamePrefix: String,
    containerExtension: String,
    onProgress: ((MediaProgress) -> Unit)? = null,
): SplitResult = FFmpeg.split(
    SplitRequest.byRanges(MediaInput.of(this), outputDirectory, ranges, fileNamePrefix, containerExtension),
    onProgress,
)

/** Splits the content behind this Uri into one segment per range; see [SplitRequest.byRanges]. */
suspend fun Uri.splitTo(
    resolver: ContentResolver,
    outputDirectory: File,
    ranges: List<TimeRange>,
    fileNamePrefix: String,
    containerExtension: String,
    onProgress: ((MediaProgress) -> Unit)? = null,
): SplitResult = FFmpeg.split(
    SplitRequest.byRanges(MediaInput.of(resolver, this), outputDirectory, ranges, fileNamePrefix, containerExtension),
    onProgress,
)

/** Probes this file; see [FFmpeg.probe]. */
suspend fun File.probe(): MediaInfo = FFmpeg.probe(MediaInput.of(this))

/** Probes the content behind this Uri; see [FFmpeg.probe]. */
suspend fun Uri.probe(resolver: ContentResolver): MediaInfo = FFmpeg.probe(MediaInput.of(resolver, this))

// v1.1.0 signatures without onProgress, kept for code compiled against that release.

@Deprecated("Binary compatibility with v1.1.0", level = DeprecationLevel.HIDDEN)
suspend fun File.trimTo(output: File, start: Duration, endExclusive: Duration? = null, mode: TrimMode = TrimMode.KEYFRAME): TrimResult =
    trimTo(output, start, endExclusive, mode, null)

@Deprecated("Binary compatibility with v1.1.0", level = DeprecationLevel.HIDDEN)
suspend fun Uri.trimTo(
    resolver: ContentResolver,
    output: File,
    start: Duration,
    endExclusive: Duration? = null,
    mode: TrimMode = TrimMode.KEYFRAME,
): TrimResult = trimTo(resolver, output, start, endExclusive, mode, null)

@Deprecated("Binary compatibility with v1.1.0", level = DeprecationLevel.HIDDEN)
suspend fun File.splitTo(outputDirectory: File, segmentDuration: Duration, fileNamePrefix: String, containerExtension: String): SplitResult =
    splitTo(outputDirectory, segmentDuration, fileNamePrefix, containerExtension, null)

@Deprecated("Binary compatibility with v1.1.0", level = DeprecationLevel.HIDDEN)
suspend fun Uri.splitTo(
    resolver: ContentResolver,
    outputDirectory: File,
    segmentDuration: Duration,
    fileNamePrefix: String,
    containerExtension: String,
): SplitResult = splitTo(resolver, outputDirectory, segmentDuration, fileNamePrefix, containerExtension, null)

@Deprecated("Binary compatibility with v1.1.0", level = DeprecationLevel.HIDDEN)
suspend fun File.splitTo(outputDirectory: File, ranges: List<TimeRange>, fileNamePrefix: String, containerExtension: String): SplitResult =
    splitTo(outputDirectory, ranges, fileNamePrefix, containerExtension, null)

@Deprecated("Binary compatibility with v1.1.0", level = DeprecationLevel.HIDDEN)
suspend fun Uri.splitTo(
    resolver: ContentResolver,
    outputDirectory: File,
    ranges: List<TimeRange>,
    fileNamePrefix: String,
    containerExtension: String,
): SplitResult = splitTo(resolver, outputDirectory, ranges, fileNamePrefix, containerExtension, null)
