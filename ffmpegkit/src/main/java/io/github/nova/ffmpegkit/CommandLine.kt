package io.github.nova.ffmpegkit

/** The ffmpeg command line over the C ABI's ffkx_execute; blocking. [FFmpeg.execute] is the public suspend entry point. */
internal object CommandLine {

    /** Throws [IllegalArgumentException] for arguments no command could take. */
    fun validate(arguments: List<String>) {
        arguments.forEachIndexed { i, argument ->
            require('\u0000' !in argument) { "argument $i contains a NUL character" }
        }
    }

    fun execute(arguments: List<String>, task: Task? = null): ExecuteResult {
        validate(arguments)
        // Whole arguments that are paths read as <path> in the log (when paths are redacted).
        for (argument in arguments) {
            if (argument.startsWith("/")) task?.redact(argument, Redactions.PATH)
            if (argument.startsWith("file:")) {
                task?.redact(argument, Redactions.PATH)
                task?.redact(argument.removePrefix("file:"), Redactions.PATH)
            }
        }
        val status = NativeCommand.execute(arguments.map { it.toByteArray() }.toTypedArray(), task?.nativeHandle ?: 0L)
        task?.throwIfCancelled()
        if (status.failed) throw status.toCommandException()
        return ExecuteResult(task?.log() ?: MediaLog.EMPTY)
    }
}

internal object NativeCommand {
    init {
        System.loadLibrary("ffmpegkit")
    }

    /** Runs ffmpeg with [arguments] (UTF-8 bytes each); [task] is a [NativeTask] handle or 0. */
    @JvmStatic
    external fun execute(arguments: Array<ByteArray>, task: Long): NativeRemuxStatus
}
