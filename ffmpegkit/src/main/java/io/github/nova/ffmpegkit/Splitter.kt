package io.github.nova.ffmpegkit

import io.github.nova.ffmpegkit.FFmpegException.Reason
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/** One published segment; [actualRange] is on the input's timeline from its start. */
internal class SplitSegment(val file: File, val actualRange: TimeRange)

internal class SplitResult(val segments: List<SplitSegment>)

/**
 * Keyframe-aligned single-pass split of a local file (roadmap 2.2). Blocking; the public suspend
 * entry point is roadmap 4.2.
 *
 * Every segment except the last is at least [segmentDuration] long and starts at a video keyframe
 * (audio-only input: at a packet boundary); concatenated, the segments hold exactly the input's
 * packets. Segments are written into a temporary directory next to the outputs and published only
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

    fun split(
        input: MediaInput,
        outputDirectory: File,
        segmentDuration: Duration,
        fileNamePrefix: String,
        containerExtension: String,
    ): SplitResult {
        require(segmentDuration.isPositive() && segmentDuration.isFinite()) {
            "segmentDuration must be positive and finite: $segmentDuration"
        }
        require(segmentDuration.inWholeMicroseconds.microseconds == segmentDuration) {
            "segmentDuration must be a whole number of microseconds: $segmentDuration"
        }
        require(fileNamePrefix.isNotEmpty() && fileNamePrefix.none { it == '/' || it == '\u0000' } &&
            fileNamePrefix != "." && fileNamePrefix != "..") {
            "fileNamePrefix must be a non-empty file name without '/': \"$fileNamePrefix\""
        }
        require(containerExtension.isNotEmpty() && containerExtension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            "containerExtension must be letters and digits only, without the dot: \"$containerExtension\""
        }
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
            val status = input.open().use { opened ->
                NativeSplit.split(
                    opened.path?.toByteArray(),
                    opened.fd,
                    workDirectory.path.toByteArray(),
                    containerExtension.toByteArray(),
                    segmentDuration.inWholeMicroseconds,
                    MAX_SEGMENTS,
                )
            }
            if (status.failed) throw status.toException(input.description, outputDirectory.path)
            val count = status.timesUs.size / 2
            val files = (1..count).map { index ->
                File(workDirectory, number(index) + "." + containerExtension) to
                    File(outputDirectory, fileNamePrefix + "-" + number(index) + "." + containerExtension)
            }
            SegmentPublisher.publishAll(input.file, files)
            return SplitResult(
                files.mapIndexed { i, (_, target) ->
                    SplitSegment(target, TimeRange(status.timesUs[2 * i].microseconds, status.timesUs[2 * i + 1].microseconds))
                },
            )
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
     * Publishes each (temporary file, target) pair in order. If any step fails, removes the targets
     * this call already published, each only while it is still the file that was published, and
     * rethrows; targets that cannot be confirmed stay and are reported as suppressed exceptions.
     */
    fun publishAll(
        input: File?,
        files: List<Pair<File, File>>,
        publish: (temp: File, target: File) -> Unit = { temp, target -> OutputDelivery.publishFile(temp, target) },
        identity: (File) -> Any? = OutputDelivery::identityOf,
    ) {
        val published = mutableListOf<Pair<File, Any?>>()
        try {
            for ((temp, target) in files) {
                OutputDelivery.checkTarget(input, target)
                publish(temp, target)
                published += target to identity(target)
            }
        } catch (e: Throwable) {
            for ((target, key) in published.asReversed()) {
                if (key == null || identity(target) != key) {
                    e.addSuppressed(FFmpegException(Reason.IO, "left published segment $target: cannot confirm it is unchanged"))
                } else if (!target.delete() && target.exists()) {
                    e.addSuppressed(FFmpegException(Reason.IO, "could not delete published segment $target"))
                }
            }
            throw e
        }
    }
}

internal object NativeSplit {
    init {
        System.loadLibrary("ffmpegkit")
    }

    /**
     * Reads [inputPath] or, when [inputFd] >= 0, that descriptor, and writes `0001.<extension>`,
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
    ): NativeRemuxStatus
}
