package io.github.nova.ffmpegkit

/**
 * What FFmpeg logged during one operation (roadmap 5.2), on [TrimResult.log], [SplitResult.log] and
 * [FFmpegException.log]: the most recent [Options.maxEntries] entries at [Options.level] or more
 * severe, oldest first. Only lines logged by the operation's own work are included, never another
 * operation's, even when several run at the same time. With [Options.redactPaths] (the default),
 * the input and output paths the operation passed to FFmpeg appear as `<input>` and `<output>`.
 *
 * Configure with [FFmpeg.logOptions]. Lines FFmpeg logs outside any operation go to logcat (tag
 * `FFmpegKit`, warnings and errors) and are not redacted.
 */
class MediaLog internal constructor(
    /** Oldest first. */
    val entries: List<Entry>,
    /** How many older entries were dropped because there were more than [Options.maxEntries]. */
    val droppedCount: Int,
) {
    /** One log line; [message] has no trailing newline and is at most 1,024 bytes of UTF-8. */
    class Entry internal constructor(
        val level: Level,
        /** The FFmpeg component that logged it, e.g. `mov,mp4,m4a,3gp,3g2,mj2` for the MP4 demuxer; null if none. */
        val component: String?,
        val message: String,
    ) {
        override fun toString(): String = if (component == null) "$level: $message" else "$level [$component] $message"
    }

    /** FFmpeg's log levels, most severe first. */
    enum class Level {
        /** Includes FFmpeg's panic and fatal levels. */
        ERROR,
        WARNING,
        INFO,
        VERBOSE,

        /** Includes FFmpeg's trace level. */
        DEBUG;

        /** The `AV_LOG_*` value: a line is captured when its level is at most this. */
        internal val avLevel: Int
            get() = when (this) {
                ERROR -> 16
                WARNING -> 24
                INFO -> 32
                VERBOSE -> 40
                DEBUG -> 56
            }

        internal companion object {
            fun of(avLevel: Int): Level = when {
                avLevel <= 16 -> ERROR
                avLevel <= 24 -> WARNING
                avLevel <= 32 -> INFO
                avLevel <= 40 -> VERBOSE
                else -> DEBUG
            }
        }
    }

    /**
     * How operations capture logs; see [FFmpeg.logOptions]. Each operation holds at most [maxEntries]
     * entries of at most 1 KB, so a log never grows without bound.
     *
     * @throws IllegalArgumentException if [maxEntries] is not in 0..10,000.
     */
    class Options(
        val level: Level = Level.WARNING,
        val maxEntries: Int = 100,
        val redactPaths: Boolean = true,
    ) {
        init {
            require(maxEntries in 0..MAX_ENTRIES) { "maxEntries must be in 0..$MAX_ENTRIES: $maxEntries" }
        }

        override fun toString(): String = "MediaLog.Options(level=$level, maxEntries=$maxEntries, redactPaths=$redactPaths)"

        private companion object {
            const val MAX_ENTRIES = 10_000
        }
    }

    override fun toString(): String = buildString {
        if (droppedCount > 0) appendLine("($droppedCount earlier entries dropped)")
        entries.joinTo(this, "\n")
    }

    companion object {
        /** No entries. */
        val EMPTY = MediaLog(emptyList(), 0)
    }
}

/** Built by jni/task.cpp from an `ffkx_log`; the constructor signature is part of the JNI contract. Texts are UTF-8. */
internal class NativeLog(
    @JvmField val levels: IntArray,
    @JvmField val components: Array<ByteArray>,
    @JvmField val messages: Array<ByteArray>,
    @JvmField val dropped: Long,
) {
    /** Decodes the entries (malformed UTF-8, e.g. cut at the size limit, becomes U+FFFD) and applies [redactions]. */
    fun toMediaLog(redactions: Redactions?): MediaLog = MediaLog(
        levels.indices.map { i ->
            val component = String(components[i], Charsets.UTF_8).takeIf { it.isNotEmpty() }
            val message = String(messages[i], Charsets.UTF_8)
            MediaLog.Entry(MediaLog.Level.of(levels[i]), component, redactions?.apply(message) ?: message)
        },
        dropped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )
}

/** Paths an operation passed to FFmpeg, and what they read as in its log. Thread-safe. */
internal class Redactions {
    private val placeholders = mutableMapOf<String, String>()

    @Synchronized
    fun add(path: String?, placeholder: String) {
        if (!path.isNullOrEmpty()) placeholders[path] = placeholder
    }

    /** Replaces every registered path in [message], longest first so that a directory never splits a file path. */
    @Synchronized
    fun apply(message: String): String =
        placeholders.entries.sortedByDescending { it.key.length }.fold(message) { text, (path, placeholder) ->
            text.replace(path, placeholder)
        }

    companion object {
        const val INPUT = "<input>"
        const val OUTPUT = "<output>"
    }
}
