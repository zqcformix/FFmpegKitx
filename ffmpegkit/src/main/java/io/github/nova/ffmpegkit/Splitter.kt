package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Splitting; blocking. [FFmpeg.split] is the public suspend entry point.
 *
 * [split] is the keyframe-aligned single-pass split (roadmap 2.2): every segment except the last is
 * at least `segmentDuration` long and starts at a video keyframe (audio-only input: at a packet
 * boundary); concatenated, the segments hold exactly the input's packets. [splitByRanges] (roadmap
 * 2.3) trims each requested range independently, exactly as [Trimmer] would, so neighbouring
 * segments can overlap where a start falls back to an earlier keyframe.
 *
 * Either way, segments are written into a temporary directory next to the outputs and published only
 * after all of them succeeded; if publishing fails part-way, the segments this call published are
 * removed again.
 */
internal object Splitter {

    /** Four-digit numbering keeps the names in order when sorted as text. */
    const val MAX_SEGMENTS = 9_999

    fun split(
        input: File,
        outputDirectory: File,
        segmentDuration: Duration,
        fileNamePrefix: String,
        containerExtension: String,
    ): SplitResult = split(MediaInput.of(input), outputDirectory, segmentDuration, fileNamePrefix, containerExtension)

    /** Throws [IllegalArgumentException] for arguments no input could accept. */
    fun validate(segmentDuration: Duration, fileNamePrefix: String, containerExtension: String) {
        require(segmentDuration.isPositive() && segmentDuration.isFinite()) {
            "segmentDuration must be positive and finite: $segmentDuration"
        }
        require(segmentDuration.inWholeMicroseconds.microseconds == segmentDuration) {
            "segmentDuration must be a whole number of microseconds: $segmentDuration"
        }
        validateNaming(fileNamePrefix, containerExtension)
    }

    /** Throws [IllegalArgumentException] for arguments no input could accept. */
    fun validate(ranges: List<TimeRange>, fileNamePrefix: String, containerExtension: String) {
        require(ranges.isNotEmpty()) { "ranges must not be empty" }
        require(ranges.size <= MAX_SEGMENTS) { "at most $MAX_SEGMENTS ranges, got ${ranges.size}" }
        validateNaming(fileNamePrefix, containerExtension)
    }

    private fun validateNaming(fileNamePrefix: String, containerExtension: String) {
        require(fileNamePrefix.isNotEmpty() && fileNamePrefix.none { it == '/' || it == '\u0000' } &&
            fileNamePrefix != "." && fileNamePrefix != "..") {
            "fileNamePrefix must be a non-empty file name without '/': \"$fileNamePrefix\""
        }
        require(containerExtension.isNotEmpty() && containerExtension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            "containerExtension must be letters and digits only, without the dot: \"$containerExtension\""
        }
    }

    fun split(
        input: MediaInput,
        outputDirectory: File,
        segmentDuration: Duration,
        fileNamePrefix: String,
        containerExtension: String,
        task: Task? = null,
    ): SplitResult {
        validate(segmentDuration, fileNamePrefix, containerExtension)
        val segments = writeAndPublish(input, outputDirectory, fileNamePrefix, containerExtension, task) { opened, workDirectory ->
            val status = NativeSplit.split(
                opened.path?.toByteArray(),
                opened.fd,
                workDirectory.path.toByteArray(),
                containerExtension.toByteArray(),
                segmentDuration.inWholeMicroseconds,
                MAX_SEGMENTS,
                task?.nativeHandle ?: 0L,
            )
            task?.throwIfCancelled() // before anything is published
            if (status.failed) throw status.toException(input.description, outputDirectory.path)
            (0 until status.timesUs.size / 2).map { TimeRange(status.timesUs[2 * it].microseconds, status.timesUs[2 * it + 1].microseconds) }
        }
        return SplitResult(segments.map { (file, actual) -> SplitSegment(file, actual, requestedRange = null) }, task?.log() ?: MediaLog.EMPTY)
    }

    /** One segment per range, numbered in the order of [ranges]; each is what [Trimmer.trim] writes for that range. */
    fun splitByRanges(
        input: MediaInput,
        outputDirectory: File,
        ranges: List<TimeRange>,
        fileNamePrefix: String,
        containerExtension: String,
        task: Task? = null,
    ): SplitResult {
        validate(ranges, fileNamePrefix, containerExtension)
        val segments = writeAndPublish(input, outputDirectory, fileNamePrefix, containerExtension, task) { opened, workDirectory ->
            // Each range opens the input again (a descriptor is read from offset 0 every time) and seeks on its own.
            ranges.mapIndexed { i, range ->
                task?.setSegment(i + 1)
                val status = NativeTrim.trim(
                    opened.path?.toByteArray(),
                    opened.fd,
                    File(workDirectory, number(i + 1) + "." + containerExtension).path.toByteArray(),
                    range.startMicros,
                    range.endExclusiveMicros ?: NativeTrim.END_OF_MEDIA,
                    false, // byRanges always starts at the keyframe
                    task?.nativeHandle ?: 0L,
                )
                task?.throwIfCancelled() // before anything is published
                if (status.failed) throw status.toException("${input.description} (range ${i + 1}: $range)", outputDirectory.path)
                TimeRange(status.timesUs[0].microseconds, status.timesUs[1].microseconds)
            }
        }
        return SplitResult(segments.mapIndexed { i, (file, actual) -> SplitSegment(file, actual, ranges[i]) }, task?.log() ?: MediaLog.EMPTY)
    }

    /**
     * Checks the naming scheme, lets [write] fill a temporary directory with `0001.<extension>`… and
     * return their actual ranges, then publishes them as `<prefix>-0001.<extension>`… in
     * [outputDirectory]. The temporary directory is always removed.
     */
    private fun writeAndPublish(
        input: MediaInput,
        outputDirectory: File,
        fileNamePrefix: String,
        containerExtension: String,
        task: Task?,
        write: (OpenedInput, File) -> List<TimeRange>,
    ): List<Pair<File, TimeRange>> {
        if (!outputDirectory.isDirectory) {
            throw FFmpegException(Reason.IO, "output directory does not exist: $outputDirectory")
        }
        // Any existing file of this naming scheme could collide; refuse before doing any work.
        val scheme = Regex(Regex.escape(fileNamePrefix) + "-\\d{4}\\." + Regex.escape(containerExtension))
        outputDirectory.list()?.firstOrNull { scheme.matches(it) }?.let {
            throw FFmpegException(Reason.OUTPUT_EXISTS, "output already exists: ${File(outputDirectory, it)}")
        }

        val workDirectory = mapIoFailures(MediaRole.OUTPUT, "create temporary directory in $outputDirectory") {
            Files.createTempDirectory(outputDirectory.toPath(), "." + fileNamePrefix.take(40) + ".split.").toFile()
        }
        var failure: Throwable? = null
        try {
            val actualRanges = input.open(task).use { opened ->
                task?.redact(opened.path, Redactions.INPUT)
                task?.redact(workDirectory.path, Redactions.OUTPUT)
                write(opened, workDirectory)
            }
            val files = actualRanges.indices.map { i ->
                File(workDirectory, number(i + 1) + "." + containerExtension) to
                    File(outputDirectory, fileNamePrefix + "-" + number(i + 1) + "." + containerExtension)
            }
            val published = SegmentPublisher.publishAll(input.file, files, task?.delivery)
            task?.rollbacks?.add { e -> SegmentPublisher.rollBack(published, e) }
            return files.map { it.second }.zip(actualRanges)
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (!workDirectory.deleteRecursively()) {
                failure?.addSuppressed(FFmpegException(Reason.IO, "could not delete temporary directory $workDirectory"))
            }
        }
    }

    // Locale.ROOT: some locales format digits other than 0-9.
    private fun number(index: Int) = String.format(Locale.ROOT, "%04d", index)
}

internal object SegmentPublisher {

    /**
     * Publishes each (temporary file, target) pair in order and returns the targets with their
     * identities. If any step fails, including a cancellation of [meter]'s task between two segments,
     * [rollBack]s the targets this call already published and rethrows.
     */
    fun publishAll(
        input: File?,
        files: List<Pair<File, File>>,
        meter: DeliveryMeter? = null,
        publish: (temp: File, target: File) -> Unit = { temp, target -> OutputDelivery.publishFile(temp, target, meter) },
        identity: (File) -> Any? = OutputDelivery::identityOf,
    ): List<Pair<File, Any?>> {
        meter?.expect(files.sumOf { it.first.length() })
        val published = mutableListOf<Pair<File, Any?>>()
        try {
            for ((temp, target) in files) {
                meter?.checkCancelled()
                OutputDelivery.checkTarget(input, target)
                publish(temp, target)
                published += target to identity(target)
            }
        } catch (e: Throwable) {
            rollBack(published, e, identity)
            throw e
        }
        return published
    }

    /**
     * Removes [published] targets, newest first, each only while it is still the file that was
     * published; targets that cannot be confirmed or removed stay and are reported on [failure].
     */
    fun rollBack(published: List<Pair<File, Any?>>, failure: Throwable, identity: (File) -> Any? = OutputDelivery::identityOf) {
        for ((target, key) in published.asReversed()) {
            if (key == null || identity(target) != key) {
                failure.addSuppressed(FFmpegException(Reason.IO, "left published segment $target: cannot confirm it is unchanged"))
            } else if (!target.delete() && target.exists()) {
                failure.addSuppressed(FFmpegException(Reason.IO, "could not delete published segment $target"))
            }
        }
    }
}

internal object NativeSplit {
    init {
        System.loadLibrary("ffmpegkit")
    }

    /**
     * Reads [inputPath] or, when it is null, the descriptor [inputFd], and writes `0001.<extension>`,
     * `0002.<extension>`… into [directory]. Paths are UTF-8 bytes. Times: one [start, end) pair per segment.
     */
    @JvmStatic
    external fun split(
        inputPath: ByteArray?,
        inputFd: Int,
        directory: ByteArray,
        extension: ByteArray,
        segmentUs: Long,
        maxSegments: Int,
        task: Long,
    ): NativeRemuxStatus
}
