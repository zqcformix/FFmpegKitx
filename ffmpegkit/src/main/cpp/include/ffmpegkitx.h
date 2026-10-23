/*
 * FFmpegKitx C ABI (roadmap 6.1): the stable boundary between the portable media core
 * (libffmpegkitx_core) and the platform bindings (JNI today; Swift and Node-API later).
 *
 * Plain C99. No C++ types or exceptions cross it, and no FFmpeg types: FFmpeg's own error codes appear
 * only as numbers, for diagnostics. Objects the library allocates are opaque and each has a free function.
 *
 * Conventions
 * - Times are signed 64-bit microseconds. Paths are NUL-terminated UTF-8.
 * - Functions that can fail return an ffkx_status; FFKX_OK is 0.
 * - Strings the library returns stay valid as long as the object they come from; those returned by
 *   functions without an object are static.
 *
 * Versioned structs start with `uint32_t struct_size`. Zero the whole struct, set struct_size to its
 * sizeof, then set the fields you need: zero is every field's default.
 * - A struct you pass in (options) may be smaller than the library's, when you built against an older
 *   header: the fields you do not have count as zero. It may be larger, when you built against a newer
 *   header than the library's: every byte the library does not know must then be zero, else the call
 *   fails with FFKX_ERROR_UNSUPPORTED_VERSION, as it does for flag bits the library does not know.
 *   Smaller than the struct's first version, or above 64 KiB, fails with FFKX_ERROR_INVALID_ARGUMENT.
 * - A struct the library fills (results) is filled up to your struct_size; bytes beyond what the
 *   library knows are set to zero. struct_size itself is not changed.
 *
 * Threads and lifetimes (roadmap 6.2)
 * - The library calls back into the caller only through the engine log sink (ffkx_set_engine_log_sink).
 *   A task's progress, log and cancellation are read and set by the caller instead, from any thread:
 *   poll ffkx_task_progress to report progress. So no task callback can outlive its task.
 * - ffkx_trim, ffkx_split and ffkx_probe block the calling thread until done. Operations with different
 *   tasks, or none, may run at the same time. A task runs one operation at a time: starting another
 *   while one runs fails with FFKX_ERROR_INVALID_ARGUMENT. Every other function taking a task may be
 *   called from any thread at any time, also while an operation runs with it.
 * - Tasks are reference counted. ffkx_task_create returns one reference, ffkx_task_retain adds one and
 *   ffkx_task_release drops one; the last release frees the task. A thread may use a task only while it
 *   holds a reference no other thread releases: retain before handing a task to another thread, which
 *   releases it when done. Releasing never cancels; cancel first if the work is no longer wanted.
 * - Result objects (ffkx_log, ffkx_split_result, ffkx_media_info) never change: any number of threads
 *   may read one at the same time. Free each exactly once, after every thread has finished with it; its
 *   strings go with it.
 * - Threads the library starts: one per input read from a descriptor, ending when the operation closes
 *   the input or, after a read was abandoned (cancellation, timeout), when that read finally returns.
 *   They never call back. Because of them and of FFmpeg's log callback, the library must stay loaded
 *   until the process ends.
 *
 * FFmpeg logs: loading the library installs FFmpeg's process-wide log callback. Lines an operation logs
 * on its own thread go to its task's log (ffkx_task_log); all other lines are engine logs, which go to
 * the engine log sink: by default logcat (tag FFmpegKit) on Android and stderr elsewhere, warnings and
 * errors only.
 *
 * Changing this header: from version 1.0 on, only append functions, constants and struct fields. A new
 * field starts at or after the struct's previous sizeof on every ABI, and zero must keep the previous
 * behaviour; bump FFKX_ABI_VERSION_MINOR and add the new layout to the pins in test/capi_test.c. Anything
 * else is a new major version. Major version 0 is not frozen yet: the header ships only inside the
 * platform packages, next to the binding built from it, and becomes 1.0 once a second platform binding
 * has used it or it is distributed on its own. Until then a minor version may change anything, as long
 * as the pins change with it.
 */
#ifndef FFMPEGKITX_H
#define FFMPEGKITX_H

#include <stddef.h>
#include <stdint.h>

#if defined(__GNUC__)
#define FFKX_API __attribute__((visibility("default")))
#else
#define FFKX_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Versions ---------------------------------------------------------------------------------- */

#define FFKX_ABI_VERSION_MAJOR 0
#define FFKX_ABI_VERSION_MINOR 3

/* The loaded library's ABI version: (major << 16) | minor. */
FFKX_API uint32_t ffkx_abi_version(void);

/* Nonzero if the loaded library serves callers built against this header: the same major and at least
 * this minor; while the major is 0, exactly this minor. */
static inline int ffkx_abi_is_compatible(void) {
    const uint32_t version = ffkx_abi_version();
    const int32_t minor = (int32_t)(version & 0xFFFFu);
    if ((version >> 16) != FFKX_ABI_VERSION_MAJOR) return 0;
    return FFKX_ABI_VERSION_MAJOR == 0 ? minor == FFKX_ABI_VERSION_MINOR : minor >= FFKX_ABI_VERSION_MINOR;
}

/* FFmpeg's version string (e.g. "8.0.1") and configure flags. */
FFKX_API const char* ffkx_ffmpeg_version(void);
FFKX_API const char* ffkx_ffmpeg_configuration(void);

enum {
    FFKX_LIBRARY_AVUTIL = 1,
    FFKX_LIBRARY_AVCODEC = 2,
    FFKX_LIBRARY_AVFORMAT = 3,
    FFKX_LIBRARY_AVFILTER = 4,
    FFKX_LIBRARY_SWSCALE = 5,
    FFKX_LIBRARY_SWRESAMPLE = 6
};

/* A bundled FFmpeg library's version as (major << 16) | (minor << 8) | micro; 0 for an unknown FFKX_LIBRARY_* value. */
FFKX_API uint32_t ffkx_ffmpeg_library_version(int32_t library);

/* ---- Status ------------------------------------------------------------------------------------ */

typedef int32_t ffkx_status;

/* Values never change; new ones may be added, so treat unknown values as a generic failure. */
enum {
    FFKX_OK = 0,
    /* A null or malformed argument, or a struct smaller than its first version. */
    FFKX_ERROR_INVALID_ARGUMENT = 1,
    /* A struct or flag from a newer header asks for something this library does not know. */
    FFKX_ERROR_UNSUPPORTED_VERSION = 2,
    FFKX_ERROR_OUT_OF_MEMORY = 3,
    /* An unexpected failure inside the library. */
    FFKX_ERROR_INTERNAL = 4,
    /* The task was cancelled. */
    FFKX_ERROR_CANCELLED = 5,
    FFKX_ERROR_INPUT_NOT_FOUND = 6,
    /* The input or output cannot be read or written with the current permissions. */
    FFKX_ERROR_ACCESS_DENIED = 7,
    FFKX_ERROR_OUTPUT_EXISTS = 8,
    /* The output storage is full or over quota. */
    FFKX_ERROR_INSUFFICIENT_SPACE = 9,
    /* The requested start is at or beyond the end of the media, or the range holds no packets. */
    FFKX_ERROR_RANGE_OUTSIDE_MEDIA = 10,
    /* Splitting this input into segments of segment_us would need more than max_segments. */
    FFKX_ERROR_TOO_MANY_SEGMENTS = 11,
    /* Valid media this build cannot handle: format, codec, container or stream layout. */
    FFKX_ERROR_UNSUPPORTED_MEDIA = 12,
    /* The input is corrupt, truncated or not a media file. */
    FFKX_ERROR_INVALID_MEDIA = 13,
    /* Any other read or write failure, including a read that timed out. */
    FFKX_ERROR_IO = 14,
    /* An FFmpeg failure that fits no other status; see ffkx_error.ffmpeg_error. */
    FFKX_ERROR_FFMPEG = 15
};

/* The constant's name, e.g. "FFKX_ERROR_IO"; "unknown status" for other values. */
FFKX_API const char* ffkx_status_name(ffkx_status status);

/* Which side of an operation a failure concerns. */
enum {
    FFKX_SIDE_NONE = 0,
    FFKX_SIDE_INPUT = 1,
    FFKX_SIDE_OUTPUT = 2
};

/* The status the library reports for an FFmpeg error code (AVERROR) on the given side. */
FFKX_API ffkx_status ffkx_status_from_ffmpeg_error(int32_t ffmpeg_error, int32_t side);

/* FFmpeg's description of an FFmpeg error code, NUL-terminated and truncated to fit. Writes nothing if size is 0. */
FFKX_API void ffkx_ffmpeg_error_string(int32_t ffmpeg_error, char* buffer, size_t size);

#define FFKX_ERROR_MESSAGE_SIZE 256

/* How an operation failed. Pass one to an operation to receive it; FFKX_OK and empty on success. */
typedef struct ffkx_error {
    uint32_t struct_size;
    ffkx_status status; /* the operation's return value */
    int32_t ffmpeg_error; /* the FFmpeg or errno-based code behind the failure (AVERROR), 0 if none; for diagnostics */
    int32_t side; /* FFKX_SIDE_* */
    /* What failed, e.g. "open input failed: No such file or directory (-2)". English, NUL-terminated, never
     * a path: the caller knows which input or output `side` refers to. */
    char message[FFKX_ERROR_MESSAGE_SIZE];
} ffkx_error;

/* ---- Tasks ------------------------------------------------------------------------------------- */

/* Unknown values: a number the media does not state. Also a missing duration. */
#define FFKX_UNKNOWN INT64_MIN

/* FFmpeg log levels; a lower value is more severe. */
enum {
    FFKX_LOG_PANIC = 0,
    FFKX_LOG_FATAL = 8,
    FFKX_LOG_ERROR = 16,
    FFKX_LOG_WARNING = 24,
    FFKX_LOG_INFO = 32,
    FFKX_LOG_VERBOSE = 40,
    FFKX_LOG_DEBUG = 48,
    FFKX_LOG_TRACE = 56
};

/*
 * One operation's cancellation flag, progress counters and log. An operation that receives no task
 * cannot be cancelled and its FFmpeg lines are engine logs.
 */
typedef struct ffkx_task ffkx_task;

typedef struct ffkx_task_options {
    uint32_t struct_size;
    uint32_t flags; /* none defined yet */
    /* FFKX_LOG_*: the log keeps lines at most this severe (zero: FFKX_LOG_PANIC). */
    int32_t log_level;
    /* The log keeps the latest this many lines and counts the ones it drops; 0 keeps none. Not negative. */
    int32_t max_log_entries;
    /* A read from an input descriptor that takes longer fails with FFKX_ERROR_IO (ETIMEDOUT); 0: no limit. */
    int64_t read_timeout_us;
    /* Packet bytes a trim may hold while it looks for its start; once reached, it starts at the keyframe
     * found so far, which with an index is the same and without one may be earlier. 0: no limit. */
    int64_t max_buffered_bytes;
} ffkx_task_options;

/* Creates a task holding one reference; options may be NULL for all defaults. On failure *task is NULL. */
FFKX_API ffkx_status ffkx_task_create(const ffkx_task_options* options, ffkx_task** task);

/* Adds a reference and returns the task; NULL is returned as is. */
FFKX_API ffkx_task* ffkx_task_retain(ffkx_task* task);

/* Drops a reference; the last one frees the task. Also while an operation runs with the task on another
 * thread, as long as that thread holds its own reference. NULL is ignored. */
FFKX_API void ffkx_task_release(ffkx_task* task);

/* Asks the task's operation to stop: it fails with FFKX_ERROR_CANCELLED soon after, and every later
 * operation with this task fails at once. Reads stuck in an input descriptor are abandoned. */
FFKX_API void ffkx_task_cancel(ffkx_task* task);

/* Nonzero once ffkx_task_cancel was called. */
FFKX_API int32_t ffkx_task_is_cancelled(const ffkx_task* task);

typedef struct ffkx_progress {
    uint32_t struct_size;
    /* 1-based segment being written: set by ffkx_split, or by the caller with ffkx_task_set_segment. */
    int32_t segment;
    /* Media time written on the operation's target timeline (a trim's from its requested start, a split's
     * the whole input), summed over the task's operations. */
    int64_t processed_us;
    /* The input's duration once it is open; FFKX_UNKNOWN until then, or when FFmpeg could only estimate
     * it from the bit rate. */
    int64_t duration_us;
    /* Bytes written to all outputs, including headers and trailers. */
    int64_t bytes_written;
} ffkx_progress;

FFKX_API ffkx_status ffkx_task_progress(const ffkx_task* task, ffkx_progress* progress);

/* Sets the reported segment, for callers that run one operation per segment. */
FFKX_API void ffkx_task_set_segment(ffkx_task* task, int32_t segment);

/* ---- Logs -------------------------------------------------------------------------------------- */

/* A snapshot of a task's log. */
typedef struct ffkx_log ffkx_log;

typedef struct ffkx_log_entry {
    uint32_t struct_size;
    int32_t level; /* FFKX_LOG_* */
    /* The FFmpeg component that logged the line, e.g. "mov,mp4,m4a,3gp,3g2,mj2"; "" if none. */
    const char* component;
    /* The line without its trailing newline, at most 1,024 bytes. FFmpeg's bytes, usually UTF-8. */
    const char* message;
} ffkx_log_entry;

/* Copies the task's log so far, oldest line first. */
FFKX_API ffkx_status ffkx_task_log(const ffkx_task* task, ffkx_log** log);
FFKX_API uint32_t ffkx_log_count(const ffkx_log* log);
/* Lines dropped because the log was full or kept none. */
FFKX_API uint64_t ffkx_log_dropped(const ffkx_log* log);
FFKX_API ffkx_status ffkx_log_entry_at(const ffkx_log* log, uint32_t index, ffkx_log_entry* entry);
FFKX_API void ffkx_log_free(ffkx_log* log);

/* ---- Engine logs ------------------------------------------------------------------------------- */

/* One complete line: as in ffkx_log_entry, the component is "" if none and the message has no trailing
 * newline and at most 1,024 bytes. The strings are valid only during the call. */
typedef void (*ffkx_log_callback)(void* user_data, int32_t level, const char* component, const char* message);

typedef struct ffkx_engine_log_sink {
    uint32_t struct_size;
    /* FFKX_LOG_*: lines at most this severe are passed (zero: FFKX_LOG_PANIC). */
    int32_t level;
    /* Required. */
    ffkx_log_callback callback;
    void* user_data;
    /* Called exactly once with user_data, after the last call of callback with it has returned, once
     * the sink has been replaced. May be NULL. Never called at process exit. */
    void (*release)(void* user_data);
} ffkx_engine_log_sink;

/*
 * Sends engine logs (FFmpeg lines logged outside any task's operation, e.g. by operations without a
 * task) to `sink`, or back to the default sink when `sink` is NULL. Process-wide; the library copies
 * the struct.
 *
 * - The callback runs on the thread FFmpeg logged on: any thread, several at the same time, so it must
 *   be thread-safe. Lines from one thread arrive in order. It must return normally: no C++ exception
 *   or longjmp may leave it or release, since FFmpeg's C code is on the stack.
 * - In the callback and in release, do not call FFmpeg: lines it logs there are dropped. Operations
 *   started there fail with FFKX_ERROR_INVALID_ARGUMENT. Calling ffkx_set_engine_log_sink is allowed
 *   and takes effect for later lines.
 * - Returns without waiting for threads that are still in the previous sink's callback. Its release
 *   runs when the last of them returns, on that thread, or before this function returns, on this
 *   thread, when none was in it. A line logged while the sink changes goes to the old or the new sink,
 *   never both.
 * - On any failure (FFKX_ERROR_INVALID_ARGUMENT for a missing callback or a bad struct_size, or
 *   FFKX_ERROR_UNSUPPORTED_VERSION, or FFKX_ERROR_OUT_OF_MEMORY) the sink is not installed and its
 *   release is not called.
 */
FFKX_API ffkx_status ffkx_set_engine_log_sink(const ffkx_engine_log_sink* sink);

/* ---- Operations -------------------------------------------------------------------------------- */
/*
 * Inputs: input_path, or when it is NULL the open, seekable file descriptor input_fd, which is read
 * from offset 0 and never closed. The library copies at most one video stream and every audio stream;
 * cover art, subtitle and data streams are skipped.
 *
 * Outputs are created or truncated: the library does not look for existing files. Bindings write to a
 * temporary location and publish without overwriting. A failed operation may leave partial outputs.
 *
 * Every operation takes an optional task, and an optional error to fill; result pointers may be NULL
 * when the result is not needed. Results are filled only on success; result handles are NULL after a
 * failure.
 */

/* Ends at the end of the media; an end beyond it has the same effect. */
#define FFKX_TIME_END INT64_MAX

/* Output time 0 is start_us instead of the keyframe: the packets from the keyframe get negative
 * timestamps and the MP4/MOV muxer writes an edit list. Needs an MP4 or MOV family output. */
#define FFKX_TRIM_EDIT_LIST 0x1u

typedef struct ffkx_trim_options {
    uint32_t struct_size;
    uint32_t flags; /* FFKX_TRIM_* */
    int32_t input_fd;
    int32_t reserved; /* must be 0 */
    /* [start_us, end_us) from the input's start time: 0 <= start_us < end_us. */
    int64_t start_us;
    int64_t end_us;
    const char* input_path;
    /* Its extension chooses the container. */
    const char* output_path;
} ffkx_trim_options;

typedef struct ffkx_trim_result {
    uint32_t struct_size;
    uint32_t reserved;
    /* What was written, [start, end) from the input's start time: the start is at or before the requested
     * start (a keyframe, or an audio packet boundary), the end where the last packet ends. */
    int64_t actual_start_us;
    int64_t actual_end_us;
} ffkx_trim_result;

/*
 * Fast trim by stream copy: starts at the last video keyframe at or before start_us (audio only: the
 * packet that holds it) and ends where each stream's decoding time reaches end_us.
 */
FFKX_API ffkx_status ffkx_trim(const ffkx_trim_options* options, ffkx_task* task, ffkx_trim_result* result,
                               ffkx_error* error);

/* The most segments a split may write: four-digit numbers. */
#define FFKX_MAX_SEGMENTS 9999

typedef struct ffkx_split_options {
    uint32_t struct_size;
    uint32_t flags; /* none defined yet */
    int32_t input_fd;
    /* At most this many segments, 1..FFKX_MAX_SEGMENTS; zero: FFKX_MAX_SEGMENTS. */
    int32_t max_segments;
    /* Minimum segment length; positive. */
    int64_t segment_us;
    const char* input_path;
    /* An existing directory the segments are written into. */
    const char* output_directory;
    /* Segments are named <file_name_prefix><NNNN>.<extension> with NNNN = 0001, 0002, ...; the prefix may
     * be NULL or empty and contains no '/'; the extension is letters and digits and chooses the container. */
    const char* file_name_prefix;
    const char* extension;
} ffkx_split_options;

/* The segments a split wrote. */
typedef struct ffkx_split_result ffkx_split_result;

typedef struct ffkx_segment {
    uint32_t struct_size;
    uint32_t reserved;
    /* [start, end) from the input's start time. */
    int64_t start_us;
    int64_t end_us;
} ffkx_segment;

/*
 * Keyframe-aligned split in one pass: a new segment starts at the first video keyframe (audio only: the
 * first packet) at least segment_us after the current segment's start, so every segment but the last is
 * at least that long. Concatenated, the segments hold every copied input packet exactly once. Fails with
 * FFKX_ERROR_TOO_MANY_SEGMENTS before writing anything when the input's duration could need more than
 * max_segments, or once a segment beyond it would start.
 */
FFKX_API ffkx_status ffkx_split(const ffkx_split_options* options, ffkx_task* task, ffkx_split_result** result,
                                ffkx_error* error);
FFKX_API uint32_t ffkx_split_result_count(const ffkx_split_result* result);
FFKX_API ffkx_status ffkx_split_result_segment(const ffkx_split_result* result, uint32_t index,
                                               ffkx_segment* segment);
FFKX_API void ffkx_split_result_free(ffkx_split_result* result);

typedef struct ffkx_probe_options {
    uint32_t struct_size;
    uint32_t flags; /* none defined yet */
    int32_t input_fd;
    int32_t reserved; /* must be 0 */
    const char* input_path;
} ffkx_probe_options;

/* What a probe read from the container and stream headers. */
typedef struct ffkx_media_info ffkx_media_info;

typedef struct ffkx_format_info {
    uint32_t struct_size;
    uint32_t stream_count;
    /* Unknown values are FFKX_UNKNOWN. A duration FFmpeg could only estimate from the bit rate (raw
     * streams such as ADTS AAC) is unknown. */
    int64_t start_time_us;
    int64_t duration_us;
    int64_t bit_rate; /* bits per second */
    /* FFmpeg's demuxer name, as ffprobe's format_name (e.g. "mov,mp4,m4a,3gp,3g2,mj2"). */
    const char* format_name;
} ffkx_format_info;

enum {
    FFKX_MEDIA_TYPE_UNKNOWN = 0,
    FFKX_MEDIA_TYPE_VIDEO = 1,
    FFKX_MEDIA_TYPE_AUDIO = 2,
    FFKX_MEDIA_TYPE_SUBTITLE = 3,
    FFKX_MEDIA_TYPE_DATA = 4,
    FFKX_MEDIA_TYPE_ATTACHMENT = 5
};

/* Cover art stored as a video stream; operations skip it. */
#define FFKX_STREAM_ATTACHED_PICTURE 0x1u

typedef struct ffkx_stream_info {
    uint32_t struct_size;
    int32_t media_type; /* FFKX_MEDIA_TYPE_* */
    uint32_t flags; /* FFKX_STREAM_* */
    uint32_t reserved;
    /* Unknown values, and values that do not apply to the media type, are FFKX_UNKNOWN. */
    int64_t duration_us;
    int64_t bit_rate;
    /* Video: coded size, before rotation. */
    int64_t width;
    int64_t height;
    /* Video: average frame rate as a fraction (ffprobe's avg_frame_rate). */
    int64_t frame_rate_num;
    int64_t frame_rate_den;
    /* Video: clockwise degrees to rotate the picture for display, 0..359; 0 without a display matrix,
     * FFKX_UNKNOWN when the matrix is not a rotation. */
    int64_t rotation;
    /* Audio. */
    int64_t sample_rate;
    int64_t channels;
    /* FFmpeg's codec name, as ffprobe's codec_name (e.g. "h264"); "none" if unknown. */
    const char* codec_name;
    /* Audio: FFmpeg's channel layout description (e.g. "stereo"); NULL if unknown or not audio. */
    const char* channel_layout;
} ffkx_stream_info;

/* Reads the container and stream headers without decoding. Unlike trim and split, accepts any streams. */
FFKX_API ffkx_status ffkx_probe(const ffkx_probe_options* options, ffkx_task* task, ffkx_media_info** info,
                                ffkx_error* error);
FFKX_API ffkx_status ffkx_media_info_format(const ffkx_media_info* info, ffkx_format_info* format);
/* Streams in container order; index < stream_count. */
FFKX_API ffkx_status ffkx_media_info_stream(const ffkx_media_info* info, uint32_t index, ffkx_stream_info* stream);
FFKX_API void ffkx_media_info_free(ffkx_media_info* info);

/* ---- Command line (roadmap 6.3) ---------------------------------------------------------------- */

typedef struct ffkx_execute_options {
    uint32_t struct_size;
    uint32_t flags; /* none defined yet */
    int32_t argument_count;
    int32_t reserved; /* must be 0 */
    /* ffmpeg's arguments after the program name, UTF-8. Each is passed as one argument, as is: nothing
     * splits, unquotes or expands them, so a path needs no quoting. */
    const char* const* arguments;
} ffkx_execute_options;

/*
 * Runs FFmpeg 8.0.1's ffmpeg command line in this process, on the calling thread. The engine is
 * libffmpegkitx_cli (FFmpeg's fftools, LGPL-2.1+), loaded on the first call.
 *
 * - One command at a time per process, because ffmpeg keeps its state in globals. A call waits while
 *   another command runs; cancelling its task ends the wait. Operations (trim, split, probe) do not wait.
 * - ffmpeg opens and writes its outputs itself, unlike the operations:
 *   - there is no temporary file;
 *   - a failed or cancelled command may leave partial outputs;
 *   - an existing output fails with FFKX_ERROR_OUTPUT_EXISTS, unless -y overwrites it. Only the main
 *     output file is checked: files that muxers such as image2 patterns, segment, hls, dash or tee
 *     write, and files of -sdp_file, -vstats_file, -passlogfile or -progress, are overwritten;
 *   - paths are what ffmpeg accepts (files, protocols such as fd:), not content Uris.
 * - Refused with FFKX_ERROR_INVALID_ARGUMENT before anything runs: options that would change the whole
 *   process or print help and exit. These are -report, -cpuflags, -cpucount, -max_alloc, -timelimit and
 *   -stdin, and
 *   -h, -version, -buildconf, -formats and the other listings. The FFREPORT environment variable is
 *   ignored. There is no stdin and no signal handling.
 * - Logs:
 *   - -loglevel and -v set FFmpeg's global level only while the command runs.
 *   - The task's log keeps lines by its own level, from the calling thread and from ffmpeg's worker
 *     threads. Lines from threads inside codecs and filters are engine logs.
 * - Cancelling the task stops ffmpeg within about 100 ms, as 'q' would: inputs stop being read and the
 *   outputs written so far are finished properly. If finishing them stalls (an output that blocks),
 *   their I/O is interrupted 3 s later. A read blocked inside the kernel (a stalled pipe or provider
 *   behind fd:) ends only when it returns.
 * - Progress: processed_us is the output time written, bytes_written the first output's size;
 *   duration_us stays unknown.
 * - Status:
 *   - FFKX_OK when ffmpeg returns 0.
 *   - A negative return is an FFmpeg error code, classified as for operations with side FFKX_SIDE_NONE;
 *     ffkx_error.ffmpeg_error has the code. Commands do not tell inputs from outputs: a missing output
 *     directory (ENOENT) also reads as FFKX_ERROR_INPUT_NOT_FOUND.
 *   - Any other return (1, 69, 255) is FFKX_ERROR_FFMPEG, with the code in the message.
 *   - FFKX_ERROR_INTERNAL if libffmpegkitx_cli cannot be loaded.
 */
FFKX_API ffkx_status ffkx_execute(const ffkx_execute_options* options, ffkx_task* task, ffkx_error* error);

#ifdef __cplusplus
}
#endif

#endif /* FFMPEGKITX_H */
