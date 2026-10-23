/*
 * C ABI test (roadmap 6.1): a plain C11 program that uses libffmpegkitx_core only through
 * include/ffmpegkitx.h, as a binding on any platform would. Run on a device or emulator with
 * `gradlew :ffmpegkit:connectedCapiTest`, which pushes it with the core library, the FFmpeg libraries and
 * the androidTest media samples, then runs `ffkx_capi_test <media dir> <empty scratch dir>`.
 *
 * The layout pins below are checked whenever the library is built. Media expectations are the same
 * ffprobe-derived values the instrumented tests use (TrimTest, SplitTest, ProbeTest).
 */
#define _POSIX_C_SOURCE 200809L

#include "ffmpegkitx.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* ---- Layout pins --------------------------------------------------------------------------------- */
/*
 * Every public struct's size and field offsets, in terms of the pointer size P, so they hold on 32-bit
 * and 64-bit ABIs. A change here is an ABI change: see "Changing this header" in ffmpegkitx.h. When a
 * version appends fields, keep the old offsets and add the new ones.
 */
#define P sizeof(void*)
#define PIN_SIZE(type, size) _Static_assert(sizeof(type) == (size), "sizeof(" #type ")")
#define PIN_FIELD(type, field, offset) _Static_assert(offsetof(type, field) == (offset), #type "." #field)

PIN_SIZE(ffkx_error, 272);
PIN_FIELD(ffkx_error, struct_size, 0);
PIN_FIELD(ffkx_error, status, 4);
PIN_FIELD(ffkx_error, ffmpeg_error, 8);
PIN_FIELD(ffkx_error, side, 12);
PIN_FIELD(ffkx_error, message, 16);

PIN_SIZE(ffkx_task_options, 32);
PIN_FIELD(ffkx_task_options, flags, 4);
PIN_FIELD(ffkx_task_options, log_level, 8);
PIN_FIELD(ffkx_task_options, max_log_entries, 12);
PIN_FIELD(ffkx_task_options, read_timeout_us, 16);
PIN_FIELD(ffkx_task_options, max_buffered_bytes, 24);

PIN_SIZE(ffkx_progress, 32);
PIN_FIELD(ffkx_progress, segment, 4);
PIN_FIELD(ffkx_progress, processed_us, 8);
PIN_FIELD(ffkx_progress, duration_us, 16);
PIN_FIELD(ffkx_progress, bytes_written, 24);

PIN_SIZE(ffkx_log_entry, 8 + 2 * P);
PIN_FIELD(ffkx_log_entry, level, 4);
PIN_FIELD(ffkx_log_entry, component, 8);
PIN_FIELD(ffkx_log_entry, message, 8 + P);

PIN_SIZE(ffkx_trim_options, 32 + 2 * P);
PIN_FIELD(ffkx_trim_options, flags, 4);
PIN_FIELD(ffkx_trim_options, input_fd, 8);
PIN_FIELD(ffkx_trim_options, reserved, 12);
PIN_FIELD(ffkx_trim_options, start_us, 16);
PIN_FIELD(ffkx_trim_options, end_us, 24);
PIN_FIELD(ffkx_trim_options, input_path, 32);
PIN_FIELD(ffkx_trim_options, output_path, 32 + P);

PIN_SIZE(ffkx_trim_result, 24);
PIN_FIELD(ffkx_trim_result, actual_start_us, 8);
PIN_FIELD(ffkx_trim_result, actual_end_us, 16);

PIN_SIZE(ffkx_split_options, 24 + 4 * P);
PIN_FIELD(ffkx_split_options, flags, 4);
PIN_FIELD(ffkx_split_options, input_fd, 8);
PIN_FIELD(ffkx_split_options, max_segments, 12);
PIN_FIELD(ffkx_split_options, segment_us, 16);
PIN_FIELD(ffkx_split_options, input_path, 24);
PIN_FIELD(ffkx_split_options, output_directory, 24 + P);
PIN_FIELD(ffkx_split_options, file_name_prefix, 24 + 2 * P);
PIN_FIELD(ffkx_split_options, extension, 24 + 3 * P);

PIN_SIZE(ffkx_segment, 24);
PIN_FIELD(ffkx_segment, start_us, 8);
PIN_FIELD(ffkx_segment, end_us, 16);

PIN_SIZE(ffkx_probe_options, 16 + P);
PIN_FIELD(ffkx_probe_options, flags, 4);
PIN_FIELD(ffkx_probe_options, input_fd, 8);
PIN_FIELD(ffkx_probe_options, reserved, 12);
PIN_FIELD(ffkx_probe_options, input_path, 16);

PIN_SIZE(ffkx_format_info, 40); /* 32-bit: 36 bytes of fields, padded to 8 */
PIN_FIELD(ffkx_format_info, stream_count, 4);
PIN_FIELD(ffkx_format_info, start_time_us, 8);
PIN_FIELD(ffkx_format_info, duration_us, 16);
PIN_FIELD(ffkx_format_info, bit_rate, 24);
PIN_FIELD(ffkx_format_info, format_name, 32);

PIN_SIZE(ffkx_engine_log_sink, 8 + 3 * P);
PIN_FIELD(ffkx_engine_log_sink, level, 4);
PIN_FIELD(ffkx_engine_log_sink, callback, 8);
PIN_FIELD(ffkx_engine_log_sink, user_data, 8 + P);
PIN_FIELD(ffkx_engine_log_sink, release, 8 + 2 * P);

PIN_SIZE(ffkx_execute_options, 16 + P);
PIN_FIELD(ffkx_execute_options, flags, 4);
PIN_FIELD(ffkx_execute_options, argument_count, 8);
PIN_FIELD(ffkx_execute_options, reserved, 12);
PIN_FIELD(ffkx_execute_options, arguments, 16);

PIN_SIZE(ffkx_stream_info, 88 + 2 * P);
PIN_FIELD(ffkx_stream_info, media_type, 4);
PIN_FIELD(ffkx_stream_info, flags, 8);
PIN_FIELD(ffkx_stream_info, reserved, 12);
PIN_FIELD(ffkx_stream_info, duration_us, 16);
PIN_FIELD(ffkx_stream_info, bit_rate, 24);
PIN_FIELD(ffkx_stream_info, width, 32);
PIN_FIELD(ffkx_stream_info, height, 40);
PIN_FIELD(ffkx_stream_info, frame_rate_num, 48);
PIN_FIELD(ffkx_stream_info, frame_rate_den, 56);
PIN_FIELD(ffkx_stream_info, rotation, 64);
PIN_FIELD(ffkx_stream_info, sample_rate, 72);
PIN_FIELD(ffkx_stream_info, channels, 80);
PIN_FIELD(ffkx_stream_info, codec_name, 88);
PIN_FIELD(ffkx_stream_info, channel_layout, 88 + P);

/* Constant values never change. */
_Static_assert(FFKX_OK == 0 && FFKX_ERROR_INVALID_ARGUMENT == 1 && FFKX_ERROR_UNSUPPORTED_VERSION == 2 &&
                   FFKX_ERROR_OUT_OF_MEMORY == 3 && FFKX_ERROR_INTERNAL == 4 && FFKX_ERROR_CANCELLED == 5 &&
                   FFKX_ERROR_INPUT_NOT_FOUND == 6 && FFKX_ERROR_ACCESS_DENIED == 7 && FFKX_ERROR_OUTPUT_EXISTS == 8 &&
                   FFKX_ERROR_INSUFFICIENT_SPACE == 9 && FFKX_ERROR_RANGE_OUTSIDE_MEDIA == 10 &&
                   FFKX_ERROR_TOO_MANY_SEGMENTS == 11 && FFKX_ERROR_UNSUPPORTED_MEDIA == 12 &&
                   FFKX_ERROR_INVALID_MEDIA == 13 && FFKX_ERROR_IO == 14 && FFKX_ERROR_FFMPEG == 15,
               "status values");
_Static_assert(FFKX_SIDE_NONE == 0 && FFKX_SIDE_INPUT == 1 && FFKX_SIDE_OUTPUT == 2, "sides");
_Static_assert(FFKX_MEDIA_TYPE_UNKNOWN == 0 && FFKX_MEDIA_TYPE_VIDEO == 1 && FFKX_MEDIA_TYPE_AUDIO == 2 &&
                   FFKX_MEDIA_TYPE_SUBTITLE == 3 && FFKX_MEDIA_TYPE_DATA == 4 && FFKX_MEDIA_TYPE_ATTACHMENT == 5,
               "media types");
_Static_assert(FFKX_LIBRARY_AVUTIL == 1 && FFKX_LIBRARY_AVCODEC == 2 && FFKX_LIBRARY_AVFORMAT == 3 &&
                   FFKX_LIBRARY_AVFILTER == 4 && FFKX_LIBRARY_SWSCALE == 5 && FFKX_LIBRARY_SWRESAMPLE == 6,
               "libraries");
_Static_assert(FFKX_LOG_PANIC == 0 && FFKX_LOG_FATAL == 8 && FFKX_LOG_ERROR == 16 && FFKX_LOG_WARNING == 24 &&
                   FFKX_LOG_INFO == 32 && FFKX_LOG_VERBOSE == 40 && FFKX_LOG_DEBUG == 48 && FFKX_LOG_TRACE == 56,
               "log levels");
_Static_assert(FFKX_TRIM_EDIT_LIST == 1 && FFKX_STREAM_ATTACHED_PICTURE == 1, "flags");
_Static_assert(FFKX_MAX_SEGMENTS == 9999 && FFKX_ERROR_MESSAGE_SIZE == 256, "limits");
_Static_assert(FFKX_UNKNOWN == INT64_MIN && FFKX_TIME_END == INT64_MAX, "sentinels");

/* ---- Harness ------------------------------------------------------------------------------------- */

static const char* media_dir;
static const char* scratch_dir;
static char av_path[512]; /* the main sample; worker threads use this instead of sample() */
static const char* current_test;
static int checks, failures, skips;

#define CHECK(condition)                                                                            \
    do {                                                                                            \
        ++checks;                                                                                   \
        if (!(condition)) {                                                                         \
            ++failures;                                                                             \
            printf("FAIL %s (line %d): %s\n", current_test, __LINE__, #condition);                  \
        }                                                                                           \
    } while (0)

#define CHECK_STATUS(expected, actual)                                                              \
    do {                                                                                            \
        const ffkx_status status_ = (actual);                                                       \
        ++checks;                                                                                   \
        if (status_ != (expected)) {                                                                \
            ++failures;                                                                             \
            printf("FAIL %s (line %d): expected %s, got %s\n", current_test, __LINE__,              \
                   ffkx_status_name(expected), ffkx_status_name(status_));                          \
        }                                                                                           \
    } while (0)

#define CHECK_I64(expected, actual)                                                                 \
    do {                                                                                            \
        const long long expected_ = (long long)(expected), actual_ = (long long)(actual);           \
        ++checks;                                                                                   \
        if (expected_ != actual_) {                                                                 \
            ++failures;                                                                             \
            printf("FAIL %s (line %d): %s: expected %lld, got %lld\n", current_test, __LINE__,      \
                   #actual, expected_, actual_);                                                    \
        }                                                                                           \
    } while (0)

#define CHECK_STR(expected, actual)                                                                 \
    do {                                                                                            \
        const char* expected_ = (expected);                                                         \
        const char* actual_ = (actual);                                                             \
        ++checks;                                                                                   \
        if (actual_ == NULL || strcmp(expected_, actual_) != 0) {                                   \
            ++failures;                                                                             \
            printf("FAIL %s (line %d): %s: expected \"%s\", got \"%s\"\n", current_test, __LINE__,  \
                   #actual, expected_, actual_ == NULL ? "(null)" : actual_);                       \
        }                                                                                           \
    } while (0)

static void run(const char* name, void (*test)(void)) {
    const int before = failures;
    current_test = name;
    test();
    printf("%s %s\n", failures == before ? "ok  " : "FAIL", name);
    fflush(stdout);
}

static void skip(const char* why) {
    ++skips;
    printf("SKIP %s: %s\n", current_test, why);
}

/* Paths are rebuilt in a few rotating buffers so that several can be live in one call; main thread only. */
static const char* path_in(const char* dir, const char* name) {
    static char buffers[8][512];
    static int next;
    char* buffer = buffers[next++ % 8];
    snprintf(buffer, sizeof(buffers[0]), "%s/%s", dir, name);
    return buffer;
}

static const char* sample(const char* name) {
    return path_in(media_dir, name);
}

static const char* scratch(const char* name) {
    return path_in(scratch_dir, name);
}

static long long file_size(const char* path) {
    struct stat st;
    return stat(path, &st) == 0 ? (long long)st.st_size : -1;
}

static ffkx_error new_error(void) {
    ffkx_error error;
    memset(&error, 0, sizeof(error));
    error.struct_size = sizeof(error);
    return error;
}

static ffkx_trim_options trim_options(const char* input, const char* output, int64_t start_us, int64_t end_us) {
    ffkx_trim_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.input_path = input;
    options.output_path = output;
    options.start_us = start_us;
    options.end_us = end_us;
    return options;
}

static ffkx_trim_result new_trim_result(void) {
    ffkx_trim_result result;
    memset(&result, 0, sizeof(result));
    result.struct_size = sizeof(result);
    return result;
}

static ffkx_task* new_task(int32_t log_level, int32_t max_log_entries) {
    ffkx_task_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.log_level = log_level;
    options.max_log_entries = max_log_entries;
    ffkx_task* task = NULL;
    CHECK_STATUS(FFKX_OK, ffkx_task_create(&options, &task));
    return task;
}

static ffkx_progress progress_of(const ffkx_task* task) {
    ffkx_progress progress;
    memset(&progress, 0, sizeof(progress));
    progress.struct_size = sizeof(progress);
    CHECK_STATUS(FFKX_OK, ffkx_task_progress(task, &progress));
    return progress;
}

static int all_bytes_are(const unsigned char* bytes, size_t count, unsigned char value) {
    for (size_t i = 0; i < count; ++i) {
        if (bytes[i] != value) return 0;
    }
    return 1;
}

/* Whether the task's log has a line containing `text`. */
static int log_contains(const ffkx_task* task, const char* text) {
    ffkx_log* log = NULL;
    if (ffkx_task_log(task, &log) != FFKX_OK) return 0;
    int found = 0;
    for (uint32_t i = 0; i < ffkx_log_count(log) && !found; ++i) {
        ffkx_log_entry entry;
        memset(&entry, 0, sizeof(entry));
        entry.struct_size = sizeof(entry);
        found = ffkx_log_entry_at(log, i, &entry) == FFKX_OK && strstr(entry.message, text) != NULL;
    }
    ffkx_log_free(log);
    return found;
}

/* FFmpeg's error codes as a caller without FFmpeg's headers writes them: AVERROR(e) is -e, and
 * FFERRTAG(a, b, c, d) is -MKTAG(a, b, c, d). */
#define TAG(a, b, c, d) (-(int32_t)((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24)))
#define FFMPEG_INVALIDDATA TAG('I', 'N', 'D', 'A')
#define FFMPEG_EXIT TAG('E', 'X', 'I', 'T')
#define FFMPEG_DEMUXER_NOT_FOUND TAG(0xF8, 'D', 'E', 'M')

#define AV "av_h264_aac.mp4"

/* ---- Versions and status ------------------------------------------------------------------------- */

static void versions(void) {
    CHECK_I64((FFKX_ABI_VERSION_MAJOR << 16) | FFKX_ABI_VERSION_MINOR, ffkx_abi_version());
    CHECK(ffkx_abi_is_compatible());
    CHECK_STR("8.0.1", ffkx_ffmpeg_version());
    CHECK(strstr(ffkx_ffmpeg_configuration(), "--enable-libass") != NULL);
    /* As PackagedLibrariesTest and README. */
    CHECK_I64((62 << 16) | (11 << 8) | 100, ffkx_ffmpeg_library_version(FFKX_LIBRARY_AVCODEC));
    CHECK_I64((62 << 16) | (3 << 8) | 100, ffkx_ffmpeg_library_version(FFKX_LIBRARY_AVFORMAT));
    CHECK_I64((11 << 16) | (4 << 8) | 100, ffkx_ffmpeg_library_version(FFKX_LIBRARY_AVFILTER));
    CHECK_I64((60 << 16) | (8 << 8) | 100, ffkx_ffmpeg_library_version(FFKX_LIBRARY_AVUTIL));
    CHECK_I64((6 << 16) | (1 << 8) | 100, ffkx_ffmpeg_library_version(FFKX_LIBRARY_SWRESAMPLE));
    CHECK_I64((9 << 16) | (1 << 8) | 100, ffkx_ffmpeg_library_version(FFKX_LIBRARY_SWSCALE));
    CHECK_I64(0, ffkx_ffmpeg_library_version(0));
    CHECK_I64(0, ffkx_ffmpeg_library_version(7));
}

static void statuses(void) {
    CHECK_STR("FFKX_OK", ffkx_status_name(FFKX_OK));
    CHECK_STR("FFKX_ERROR_TOO_MANY_SEGMENTS", ffkx_status_name(FFKX_ERROR_TOO_MANY_SEGMENTS));
    CHECK_STR("FFKX_ERROR_FFMPEG", ffkx_status_name(FFKX_ERROR_FFMPEG));
    CHECK_STR("unknown status", ffkx_status_name(16));
    CHECK_STR("unknown status", ffkx_status_name(-1));

    CHECK_STATUS(FFKX_OK, ffkx_status_from_ffmpeg_error(0, FFKX_SIDE_INPUT));
    CHECK_STATUS(FFKX_ERROR_INPUT_NOT_FOUND, ffkx_status_from_ffmpeg_error(-ENOENT, FFKX_SIDE_INPUT));
    CHECK_STATUS(FFKX_ERROR_IO, ffkx_status_from_ffmpeg_error(-ENOENT, FFKX_SIDE_OUTPUT));
    CHECK_STATUS(FFKX_ERROR_ACCESS_DENIED, ffkx_status_from_ffmpeg_error(-EACCES, FFKX_SIDE_OUTPUT));
    CHECK_STATUS(FFKX_ERROR_OUTPUT_EXISTS, ffkx_status_from_ffmpeg_error(-EEXIST, FFKX_SIDE_OUTPUT));
    CHECK_STATUS(FFKX_ERROR_INSUFFICIENT_SPACE, ffkx_status_from_ffmpeg_error(-ENOSPC, FFKX_SIDE_OUTPUT));
    CHECK_STATUS(FFKX_ERROR_IO, ffkx_status_from_ffmpeg_error(-ETIMEDOUT, FFKX_SIDE_INPUT));
    CHECK_STATUS(FFKX_ERROR_OUT_OF_MEMORY, ffkx_status_from_ffmpeg_error(-ENOMEM, FFKX_SIDE_INPUT));
    CHECK_STATUS(FFKX_ERROR_INVALID_MEDIA, ffkx_status_from_ffmpeg_error(FFMPEG_INVALIDDATA, FFKX_SIDE_INPUT));
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_MEDIA, ffkx_status_from_ffmpeg_error(FFMPEG_DEMUXER_NOT_FOUND, FFKX_SIDE_INPUT));
    CHECK_STATUS(FFKX_ERROR_CANCELLED, ffkx_status_from_ffmpeg_error(FFMPEG_EXIT, FFKX_SIDE_INPUT));
    CHECK_STATUS(FFKX_ERROR_FFMPEG, ffkx_status_from_ffmpeg_error(-EINVAL, FFKX_SIDE_INPUT));
    CHECK_STATUS(FFKX_ERROR_FFMPEG, ffkx_status_from_ffmpeg_error(-123456, FFKX_SIDE_INPUT));

    char text[64];
    ffkx_ffmpeg_error_string(-ENOENT, text, sizeof(text));
    CHECK_STR("No such file or directory", text);
    ffkx_ffmpeg_error_string(FFMPEG_INVALIDDATA, text, sizeof(text));
    CHECK_STR("Invalid data found when processing input", text);
    ffkx_ffmpeg_error_string(-ENOENT, text, 8);
    CHECK_STR("No such", text);
    memset(text, 'x', sizeof(text));
    ffkx_ffmpeg_error_string(-ENOENT, text, 0);
    CHECK(text[0] == 'x');
    ffkx_ffmpeg_error_string(-ENOENT, NULL, 16);
}

/* ---- Struct versions ----------------------------------------------------------------------------- */

/* A caller built against a header newer than the library: its structs are longer. */
struct newer_trim_options {
    ffkx_trim_options known;
    int64_t added[2];
};

struct newer_error {
    ffkx_error known;
    unsigned char added[24];
};

struct newer_trim_result {
    ffkx_trim_result known;
    unsigned char added[16];
};

static void options_versions(void) {
    const char* output = scratch("versions.mp4");
    ffkx_error error = new_error();

    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(NULL, NULL, NULL, &error));
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, error.status);
    CHECK_STR("options is NULL", error.message);
    CHECK_I64(FFKX_SIDE_NONE, error.side);

    /* Shorter than the first version. */
    ffkx_trim_options options = trim_options(sample(AV), output, 0, 1000000);
    options.struct_size = offsetof(ffkx_trim_options, output_path);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, NULL, &error));
    options.struct_size = 0;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, NULL, &error));
    /* A garbage size is refused without reading past the struct. */
    options.struct_size = 0xFFFFFFFFu;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, NULL, &error));

    /* Unknown flags and reserved fields are from a newer version. */
    options = trim_options(sample(AV), output, 0, 1000000);
    options.flags = 0x80000000u;
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_VERSION, ffkx_trim(&options, NULL, NULL, &error));
    options.flags = 0;
    options.reserved = 1;
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_VERSION, ffkx_trim(&options, NULL, NULL, &error));

    /* A newer struct whose added fields are zero means what the library knows. */
    struct newer_trim_options newer;
    memset(&newer, 0, sizeof(newer));
    newer.known = trim_options(sample(AV), output, 3000000, 4000000);
    newer.known.struct_size = sizeof(newer);
    ffkx_trim_result result = new_trim_result();
    CHECK_STATUS(FFKX_OK, ffkx_trim(&newer.known, NULL, &result, &error));
    CHECK_I64(3000000, result.actual_start_us);
    CHECK_I64(4133333, result.actual_end_us);

    /* An added field this library would ignore is refused before any work. */
    unlink(output);
    newer.added[1] = 1;
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_VERSION, ffkx_trim(&newer.known, NULL, NULL, &error));
    CHECK_I64(-1, file_size(output));
}

static void result_versions(void) {
    const char* output = scratch("results.mp4");

    /* A longer error and result: the library fills what it knows and zeroes the rest. */
    struct newer_error error;
    memset(&error, 0xAB, sizeof(error));
    error.known.struct_size = sizeof(error);
    struct newer_trim_result result;
    memset(&result, 0xAB, sizeof(result));
    result.known.struct_size = sizeof(result);
    ffkx_trim_options options = trim_options(sample(AV), output, 3000000, 4000000);
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, NULL, &result.known, &error.known));
    CHECK_I64(sizeof(error), error.known.struct_size);
    CHECK_STATUS(FFKX_OK, error.known.status);
    CHECK_I64(0, error.known.ffmpeg_error);
    CHECK_STR("", error.known.message);
    CHECK(all_bytes_are(error.added, sizeof(error.added), 0));
    CHECK_I64(sizeof(result), result.known.struct_size);
    CHECK_I64(3000000, result.known.actual_start_us);
    CHECK(all_bytes_are(result.added, sizeof(result.added), 0));

    /* Too short to fill: refused before any work, and nothing written. */
    unlink(output);
    ffkx_error short_error;
    memset(&short_error, 0xAB, sizeof(short_error));
    short_error.struct_size = offsetof(ffkx_error, message);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, NULL, &short_error));
    CHECK(all_bytes_are((const unsigned char*)&short_error + 4, sizeof(short_error) - 4, 0xAB));
    ffkx_trim_result short_result = new_trim_result();
    short_result.struct_size = 8;
    ffkx_error error2 = new_error();
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, &short_result, &error2));
    short_result.struct_size = 0x7FFFFFFFu;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, &short_result, &error2));
    CHECK_I64(-1, file_size(output));
}

static void task_options_versions(void) {
    ffkx_task* task = NULL;
    CHECK_STATUS(FFKX_OK, ffkx_task_create(NULL, &task));
    CHECK(task != NULL);
    ffkx_task_release(task);

    struct {
        ffkx_task_options known;
        int64_t added;
    } newer;
    memset(&newer, 0, sizeof(newer));
    newer.known.struct_size = sizeof(newer);
    CHECK_STATUS(FFKX_OK, ffkx_task_create(&newer.known, &task));
    ffkx_task_release(task);
    newer.added = 5;
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_VERSION, ffkx_task_create(&newer.known, &task));
    CHECK(task == NULL);

    ffkx_task_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.flags = 1;
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_VERSION, ffkx_task_create(&options, &task));
    options.flags = 0;
    options.max_log_entries = -1;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_task_create(&options, &task));
    options.max_log_entries = 0;
    options.read_timeout_us = -1;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_task_create(&options, &task));
    options.struct_size = 8;
    options.read_timeout_us = 0;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_task_create(&options, &task));
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_task_create(NULL, NULL));

    /* Results of accessors follow the same rules. */
    task = new_task(FFKX_LOG_WARNING, 10);
    struct {
        ffkx_progress known;
        unsigned char added[8];
    } progress;
    memset(&progress, 0xAB, sizeof(progress));
    progress.known.struct_size = sizeof(progress);
    CHECK_STATUS(FFKX_OK, ffkx_task_progress(task, &progress.known));
    CHECK_I64(FFKX_UNKNOWN, progress.known.duration_us);
    CHECK_I64(1, progress.known.segment);
    CHECK(all_bytes_are(progress.added, sizeof(progress.added), 0));
    progress.known.struct_size = 4;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_task_progress(task, &progress.known));
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_task_progress(NULL, &progress.known));
    ffkx_task_release(task);
}

/* ---- Trim ---------------------------------------------------------------------------------------- */

static void trim_from_path(void) {
    const char* output = scratch("trim.mp4");
    ffkx_trim_options options = trim_options(sample(AV), output, 2500000, 4500000);
    ffkx_trim_result result = new_trim_result();
    ffkx_error error = new_error();
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, NULL, &result, &error));
    /* Starts at the keyframe at 2 s; the last video packet decoded before 4.5 s ends at 4.633 s. */
    CHECK_I64(2000000, result.actual_start_us);
    CHECK_I64(4633333, result.actual_end_us);
    CHECK_STATUS(FFKX_OK, error.status);
    CHECK_I64(FFKX_SIDE_NONE, error.side);
    CHECK(file_size(output) > 0);

    /* An end beyond the media, or FFKX_TIME_END, trims to its end. */
    options = trim_options(sample(AV), scratch("trim-end.mp4"), 7500000, FFKX_TIME_END);
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, NULL, &result, NULL));
    CHECK_I64(7000000, result.actual_start_us);
    CHECK_I64(10000000, result.actual_end_us);
    options = trim_options(sample(AV), scratch("trim-end2.mp4"), 7500000, 60000000);
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, NULL, &result, NULL));
    CHECK_I64(10000000, result.actual_end_us);

    /* The output holds what the result says. */
    ffkx_probe_options probe;
    memset(&probe, 0, sizeof(probe));
    probe.struct_size = sizeof(probe);
    probe.input_path = output;
    ffkx_media_info* info = NULL;
    CHECK_STATUS(FFKX_OK, ffkx_probe(&probe, NULL, &info, NULL));
    ffkx_format_info format;
    memset(&format, 0, sizeof(format));
    format.struct_size = sizeof(format);
    CHECK_STATUS(FFKX_OK, ffkx_media_info_format(info, &format));
    CHECK_I64(2, format.stream_count);
    /* 2.567 s: MP4 counts video in decoding order, which with B-frames ends before the last picture shown. */
    CHECK(format.duration_us >= 2500000 && format.duration_us <= 2700000);
    ffkx_media_info_free(info);
}

static void trim_from_descriptor(void) {
    const int fd = open(sample(AV), O_RDONLY | O_CLOEXEC);
    CHECK(fd >= 0);
    CHECK(lseek(fd, 1000, SEEK_SET) == 1000); /* read from offset 0 regardless */
    ffkx_trim_options options = trim_options(NULL, scratch("fd.mp4"), 3000000, 4000000);
    options.input_fd = fd;
    ffkx_trim_result result = new_trim_result();
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, NULL, &result, NULL));
    CHECK_I64(3000000, result.actual_start_us);
    CHECK_I64(4133333, result.actual_end_us);
    /* Still open: the caller owns it. */
    CHECK(fcntl(fd, F_GETFD) != -1);
    close(fd);

    /* A path wins over the descriptor; zero-initialized options therefore read the path, not fd 0. */
    options = trim_options(sample(AV), scratch("fd-path.mp4"), 3000000, 4000000);
    options.input_fd = 0;
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, NULL, &result, NULL));

    /* A pipe cannot seek. */
    int pipe_fds[2];
    CHECK(pipe(pipe_fds) == 0);
    options = trim_options(NULL, scratch("pipe.mp4"), 0, 1000000);
    options.input_fd = pipe_fds[0];
    ffkx_error error = new_error();
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_MEDIA, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_STR("input cannot seek (pipe or stream)", error.message);
    CHECK_I64(-ESPIPE, error.ffmpeg_error);
    CHECK_I64(FFKX_SIDE_INPUT, error.side);
    close(pipe_fds[0]);
    close(pipe_fds[1]);
}

static void trim_edit_list(void) {
    ffkx_trim_options options = trim_options(sample(AV), scratch("edit.mp4"), 2500000, 4500000);
    options.flags = FFKX_TRIM_EDIT_LIST;
    ffkx_trim_result result = new_trim_result();
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, NULL, &result, NULL));
    CHECK_I64(2000000, result.actual_start_us);

    options.output_path = scratch("edit.mkv");
    ffkx_error error = new_error();
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_MEDIA, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_STR("edit list mode needs an MP4 or MOV output", error.message);
    CHECK_I64(FFKX_SIDE_OUTPUT, error.side);
}

/* Writes the first half of a sample: an MP4 whose moov atom (at the end) is missing. */
static const char* truncated_sample(void) {
    const char* path = scratch("truncated.mp4");
    FILE* in = fopen(sample(AV), "rb");
    FILE* out = fopen(path, "wb");
    CHECK(in != NULL && out != NULL);
    if (in == NULL || out == NULL) return path;
    char buffer[4096];
    long long remaining = file_size(sample(AV)) / 2;
    size_t n;
    while (remaining > 0 && (n = fread(buffer, 1, sizeof(buffer), in)) > 0) {
        if ((long long)n > remaining) n = (size_t)remaining;
        fwrite(buffer, 1, n, out);
        remaining -= (long long)n;
    }
    fclose(in);
    fclose(out);
    return path;
}

static void trim_failures(void) {
    ffkx_error error = new_error();

    ffkx_trim_options options = trim_options(scratch("missing.mp4"), scratch("x.mp4"), 0, 1000000);
    CHECK_STATUS(FFKX_ERROR_INPUT_NOT_FOUND, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_STATUS(FFKX_ERROR_INPUT_NOT_FOUND, error.status);
    CHECK_I64(-ENOENT, error.ffmpeg_error);
    CHECK_I64(FFKX_SIDE_INPUT, error.side);
    CHECK_STR("open input failed: No such file or directory (-2)", error.message);

    options = trim_options(sample(AV), scratch("late.mp4"), 10000000, FFKX_TIME_END);
    CHECK_STATUS(FFKX_ERROR_RANGE_OUTSIDE_MEDIA, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_STR("start is at or beyond the end of the media", error.message);

    options = trim_options(sample(AV), scratch("clip.xyz"), 0, 1000000);
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_MEDIA, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_I64(FFKX_SIDE_OUTPUT, error.side);
    CHECK_STR("no container matches the output file extension", error.message);

    options = trim_options(sample(AV), scratch("clip.mp3"), 0, 1000000);
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_MEDIA, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_I64(FFKX_SIDE_OUTPUT, error.side);

    options = trim_options(sample(AV), scratch("missing-dir/clip.mp4"), 0, 1000000);
    CHECK_STATUS(FFKX_ERROR_IO, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_I64(-ENOENT, error.ffmpeg_error);
    CHECK_I64(FFKX_SIDE_OUTPUT, error.side);

    ffkx_task* task = new_task(FFKX_LOG_ERROR, 10);
    options = trim_options(truncated_sample(), scratch("truncated-out.mp4"), 0, 1000000);
    CHECK_STATUS(FFKX_ERROR_INVALID_MEDIA, ffkx_trim(&options, task, NULL, &error));
    CHECK_I64(FFMPEG_INVALIDDATA, error.ffmpeg_error);
    CHECK(log_contains(task, "moov atom not found"));
    ffkx_task_release(task);
}

static void trim_arguments(void) {
    ffkx_error error = new_error();
    ffkx_trim_options options = trim_options(NULL, scratch("args.mp4"), 0, 1000000);
    options.input_fd = -1;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_STR("neither input_path nor input_fd is set", error.message);

    /* An empty path names no file, as for open(). */
    options = trim_options("", scratch("args.mp4"), 0, 1000000);
    CHECK_STATUS(FFKX_ERROR_INPUT_NOT_FOUND, ffkx_trim(&options, NULL, NULL, &error));
    options = trim_options(sample(AV), NULL, 0, 1000000);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, NULL, &error));
    options = trim_options(sample(AV), scratch("args.mp4"), -1, 1000000);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_STR("start_us is negative", error.message);
    options = trim_options(sample(AV), scratch("args.mp4"), 1000000, 1000000);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_STR("end_us is not after start_us", error.message);
    /* Zero-initialized options with only paths set: an empty range. */
    options = trim_options(sample(AV), scratch("args.mp4"), 0, 0);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_trim(&options, NULL, NULL, &error));
    CHECK_I64(-1, file_size(scratch("args.mp4")));
}

/* ---- Split --------------------------------------------------------------------------------------- */

static ffkx_split_options split_options(const char* directory, int64_t segment_us) {
    ffkx_split_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.input_path = av_path;
    options.output_directory = directory;
    options.file_name_prefix = "clip-";
    options.extension = "mp4";
    options.segment_us = segment_us;
    return options;
}

static const char* new_directory(const char* name) {
    const char* path = scratch(name);
    CHECK(mkdir(path, 0700) == 0);
    return path;
}

static int entries_in(const char* directory) {
    int count = 0;
    char name[32];
    for (int i = 1; i <= 20; ++i) {
        snprintf(name, sizeof(name), "clip-%04d.mp4", i);
        if (file_size(path_in(directory, name)) >= 0) ++count;
    }
    return count;
}

static void split_segments(void) {
    const char* directory = new_directory("split");
    ffkx_split_options options = split_options(directory, 3000000);
    ffkx_task* task = new_task(FFKX_LOG_WARNING, 10);
    ffkx_split_result* result = NULL;
    ffkx_error error = new_error();
    CHECK_STATUS(FFKX_OK, ffkx_split(&options, task, &result, &error));
    /* Keyframes every second: cuts at 3, 6 and 9 s. */
    const int64_t expected[][2] = {{0, 3000000}, {3000000, 6000000}, {6000000, 9000000}, {9000000, 10000000}};
    CHECK_I64(4, ffkx_split_result_count(result));
    for (uint32_t i = 0; i < 4 && i < ffkx_split_result_count(result); ++i) {
        ffkx_segment segment;
        memset(&segment, 0, sizeof(segment));
        segment.struct_size = sizeof(segment);
        CHECK_STATUS(FFKX_OK, ffkx_split_result_segment(result, i, &segment));
        CHECK_I64(expected[i][0], segment.start_us);
        CHECK_I64(expected[i][1], segment.end_us);
    }
    ffkx_segment segment;
    memset(&segment, 0, sizeof(segment));
    segment.struct_size = sizeof(segment);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_split_result_segment(result, 4, &segment));
    CHECK(file_size(path_in(directory, "clip-0001.mp4")) > 0);
    CHECK(file_size(path_in(directory, "clip-0004.mp4")) > 0);
    CHECK_I64(4, entries_in(directory));
    const ffkx_progress progress = progress_of(task);
    CHECK_I64(4, progress.segment);
    CHECK_I64(10000000, progress.processed_us);
    ffkx_split_result_free(result);
    ffkx_task_release(task);

    /* Without a prefix the names are just numbers; the result pointer may be NULL. */
    directory = new_directory("split-plain");
    options = split_options(directory, 5000000);
    options.file_name_prefix = NULL;
    CHECK_STATUS(FFKX_OK, ffkx_split(&options, NULL, NULL, NULL));
    CHECK(file_size(path_in(directory, "0001.mp4")) > 0);
    CHECK(file_size(path_in(directory, "0002.mp4")) > 0);

    /* The longest segment length is one segment, also from an input whose start time is not 0. */
    directory = new_directory("split-whole");
    options = split_options(directory, INT64_MAX);
    options.input_path = sample("av_offset.ts");
    options.extension = "ts";
    CHECK_STATUS(FFKX_OK, ffkx_split(&options, NULL, &result, NULL));
    CHECK_I64(1, ffkx_split_result_count(result));
    ffkx_split_result_free(result);
}

static void split_limits(void) {
    const char* directory = new_directory("split-limits");
    ffkx_split_result* result = (ffkx_split_result*)&result; /* must be reset to NULL */
    ffkx_error error = new_error();

    /* 10 s in 1 ms segments could need 10 001: refused before writing. */
    ffkx_split_options options = split_options(directory, 1000);
    CHECK_STATUS(FFKX_ERROR_TOO_MANY_SEGMENTS, ffkx_split(&options, NULL, &result, &error));
    CHECK(result == NULL);
    CHECK_I64(0, entries_in(directory));

    options = split_options(directory, 3000000);
    options.max_segments = 2;
    CHECK_STATUS(FFKX_ERROR_TOO_MANY_SEGMENTS, ffkx_split(&options, NULL, &result, &error));
    CHECK_I64(0, entries_in(directory));

    options = split_options(directory, 0);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_split(&options, NULL, &result, &error));
    options = split_options(directory, 1000000);
    options.max_segments = FFKX_MAX_SEGMENTS + 1;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_split(&options, NULL, &result, &error));
    options.max_segments = -1;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_split(&options, NULL, &result, &error));
    options = split_options(directory, 1000000);
    options.extension = "m/p4";
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_split(&options, NULL, &result, &error));
    options.extension = "";
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_split(&options, NULL, &result, &error));
    options = split_options(directory, 1000000);
    options.file_name_prefix = "../clip-";
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_split(&options, NULL, &result, &error));
    CHECK_STR("file_name_prefix contains '/'", error.message);
    options = split_options(NULL, 1000000);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_split(&options, NULL, &result, &error));
    CHECK_I64(0, entries_in(directory));

    CHECK_I64(0, ffkx_split_result_count(NULL));
    ffkx_split_result_free(NULL);
}

/* ---- Probe --------------------------------------------------------------------------------------- */

static ffkx_media_info* probe_path(const char* path) {
    ffkx_probe_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.input_path = path;
    ffkx_media_info* info = NULL;
    CHECK_STATUS(FFKX_OK, ffkx_probe(&options, NULL, &info, NULL));
    return info;
}

static ffkx_format_info format_of(const ffkx_media_info* info) {
    ffkx_format_info format;
    memset(&format, 0, sizeof(format));
    format.struct_size = sizeof(format);
    CHECK_STATUS(FFKX_OK, ffkx_media_info_format(info, &format));
    return format;
}

static ffkx_stream_info stream_of(const ffkx_media_info* info, uint32_t index) {
    ffkx_stream_info stream;
    memset(&stream, 0, sizeof(stream));
    stream.struct_size = sizeof(stream);
    CHECK_STATUS(FFKX_OK, ffkx_media_info_stream(info, index, &stream));
    return stream;
}

static void probe_streams(void) {
    ffkx_media_info* info = probe_path(sample(AV));
    const ffkx_format_info format = format_of(info);
    CHECK_STR("mov,mp4,m4a,3gp,3g2,mj2", format.format_name);
    CHECK_I64(0, format.start_time_us);
    CHECK_I64(10000000, format.duration_us);
    CHECK_I64(267559, format.bit_rate);
    CHECK_I64(2, format.stream_count);

    const ffkx_stream_info video = stream_of(info, 0);
    CHECK_I64(FFKX_MEDIA_TYPE_VIDEO, video.media_type);
    CHECK_STR("h264", video.codec_name);
    CHECK_I64(320, video.width);
    CHECK_I64(240, video.height);
    CHECK_I64(30, video.frame_rate_num);
    CHECK_I64(1, video.frame_rate_den);
    CHECK_I64(0, video.rotation);
    CHECK_I64(0, video.flags);
    CHECK_I64(FFKX_UNKNOWN, video.sample_rate);
    CHECK(video.channel_layout == NULL);

    const ffkx_stream_info audio = stream_of(info, 1);
    CHECK_I64(FFKX_MEDIA_TYPE_AUDIO, audio.media_type);
    CHECK_STR("aac", audio.codec_name);
    CHECK_I64(44100, audio.sample_rate);
    CHECK_I64(2, audio.channels);
    CHECK_STR("stereo", audio.channel_layout);
    CHECK_I64(10000000, audio.duration_us);
    CHECK_I64(64216, audio.bit_rate);
    CHECK_I64(FFKX_UNKNOWN, audio.width);
    CHECK_I64(FFKX_UNKNOWN, audio.rotation);

    ffkx_stream_info stream;
    memset(&stream, 0, sizeof(stream));
    stream.struct_size = sizeof(stream);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_media_info_stream(info, 2, &stream));
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_media_info_format(info, NULL));
    ffkx_media_info_free(info);
    ffkx_media_info_free(NULL);
}

static void probe_samples(void) {
    ffkx_media_info* info = probe_path(sample("video_h264_rotated.mp4"));
    CHECK_I64(90, stream_of(info, 0).rotation);
    ffkx_media_info_free(info);

    /* ADTS: FFmpeg only estimates the duration from the bit rate. */
    info = probe_path(sample("audio_aac.aac"));
    const ffkx_format_info format = format_of(info);
    CHECK_STR("aac", format.format_name);
    CHECK_I64(FFKX_UNKNOWN, format.duration_us);
    CHECK_I64(FFKX_UNKNOWN, stream_of(info, 0).duration_us);
    ffkx_media_info_free(info);

    /* A descriptor, and a NULL result pointer (only checks that the input opens). */
    const int fd = open(sample(AV), O_RDONLY | O_CLOEXEC);
    ffkx_probe_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.input_fd = fd;
    info = NULL;
    CHECK_STATUS(FFKX_OK, ffkx_probe(&options, NULL, &info, NULL));
    CHECK_I64(2, format_of(info).stream_count);
    ffkx_media_info_free(info);
    CHECK_STATUS(FFKX_OK, ffkx_probe(&options, NULL, NULL, NULL));
    close(fd);

    ffkx_error error = new_error();
    options.input_fd = -1;
    options.input_path = scratch("missing.mp4");
    info = (ffkx_media_info*)&info;
    CHECK_STATUS(FFKX_ERROR_INPUT_NOT_FOUND, ffkx_probe(&options, NULL, &info, &error));
    CHECK(info == NULL);
}

/* ---- Tasks, progress and logs -------------------------------------------------------------------- */

static void task_progress(void) {
    ffkx_task* task = new_task(FFKX_LOG_WARNING, 10);
    ffkx_progress progress = progress_of(task);
    CHECK_I64(0, progress.processed_us);
    CHECK_I64(FFKX_UNKNOWN, progress.duration_us);
    CHECK_I64(0, progress.bytes_written);

    ffkx_trim_options options = trim_options(sample(AV), scratch("progress.mp4"), 2500000, 4500000);
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, task, NULL, NULL));
    progress = progress_of(task);
    /* Target timeline: from the requested start to the requested end. */
    CHECK_I64(2000000, progress.processed_us);
    CHECK_I64(10000000, progress.duration_us);
    CHECK(progress.bytes_written > 0);
    const int64_t first_bytes = progress.bytes_written;

    /* Counters add up over the task's operations; the segment is the caller's to set. */
    ffkx_task_set_segment(task, 2);
    options = trim_options(sample(AV), scratch("progress2.mp4"), 3000000, 4000000);
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, task, NULL, NULL));
    progress = progress_of(task);
    CHECK_I64(3000000, progress.processed_us);
    CHECK(progress.bytes_written > first_bytes);
    CHECK_I64(2, progress.segment);
    ffkx_task_release(task);
}

static void task_logs(void) {
    /* Debug lines far outnumber the 5 kept: the latest are kept, the rest counted. */
    ffkx_task* task = new_task(FFKX_LOG_DEBUG, 5);
    ffkx_trim_options options = trim_options(sample(AV), scratch("logs.mp4"), 2000000, 4000000);
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, task, NULL, NULL));
    ffkx_log* log = NULL;
    CHECK_STATUS(FFKX_OK, ffkx_task_log(task, &log));
    CHECK_I64(5, ffkx_log_count(log));
    CHECK(ffkx_log_dropped(log) > 0);
    for (uint32_t i = 0; i < ffkx_log_count(log); ++i) {
        ffkx_log_entry entry;
        memset(&entry, 0, sizeof(entry));
        entry.struct_size = sizeof(entry);
        CHECK_STATUS(FFKX_OK, ffkx_log_entry_at(log, i, &entry));
        CHECK(entry.level <= FFKX_LOG_DEBUG);
        CHECK(entry.component != NULL && entry.message != NULL && entry.message[0] != '\0');
        const size_t length = entry.message == NULL ? 0 : strlen(entry.message);
        CHECK(length <= 1024 && (length == 0 || entry.message[length - 1] != '\n'));
    }
    ffkx_log_entry entry;
    memset(&entry, 0, sizeof(entry));
    entry.struct_size = sizeof(entry);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_log_entry_at(log, 5, &entry));
    ffkx_log_free(log);
    ffkx_task_release(task);

    /* A task that keeps nothing still counts. */
    task = new_task(FFKX_LOG_DEBUG, 0);
    CHECK_STATUS(FFKX_OK, ffkx_trim(&options, task, NULL, NULL));
    CHECK_STATUS(FFKX_OK, ffkx_task_log(task, &log));
    CHECK_I64(0, ffkx_log_count(log));
    CHECK(ffkx_log_dropped(log) > 0);
    ffkx_log_free(log);
    ffkx_task_release(task);

    CHECK_I64(0, ffkx_log_count(NULL));
    CHECK_I64(0, ffkx_log_dropped(NULL));
    ffkx_log_free(NULL);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_task_log(NULL, &log));
    CHECK(log == NULL);
}

/* ---- Cancellation and threads -------------------------------------------------------------------- */

static void cancel_before_start(void) {
    ffkx_task* task = new_task(FFKX_LOG_WARNING, 10);
    CHECK_I64(0, ffkx_task_is_cancelled(task));
    ffkx_task_cancel(task);
    ffkx_task_cancel(task);
    CHECK_I64(1, ffkx_task_is_cancelled(task));

    const char* output = scratch("cancelled.mp4");
    ffkx_trim_options options = trim_options(sample(AV), output, 0, 1000000);
    ffkx_error error = new_error();
    CHECK_STATUS(FFKX_ERROR_CANCELLED, ffkx_trim(&options, task, NULL, &error));
    CHECK_STR("cancelled", error.message);
    CHECK_I64(FFKX_SIDE_NONE, error.side);
    CHECK_I64(-1, file_size(output));

    ffkx_probe_options probe;
    memset(&probe, 0, sizeof(probe));
    probe.struct_size = sizeof(probe);
    probe.input_path = sample(AV);
    CHECK_STATUS(FFKX_ERROR_CANCELLED, ffkx_probe(&probe, task, NULL, NULL));
    ffkx_task_release(task);

    ffkx_task_cancel(NULL);
    ffkx_task_set_segment(NULL, 1);
    ffkx_task_release(NULL);
    CHECK_I64(0, ffkx_task_is_cancelled(NULL));
}

/* Worker threads record what they saw; only the main thread checks. */
struct split_run {
    ffkx_task* task;
    const char* directory;
    ffkx_status status;
    int has_result;
    atomic_int done;
    int release_when_done; /* the worker owns a reference and releases it after the split */
};

static void* run_split(void* argument) {
    struct split_run* run = argument;
    ffkx_split_options options = split_options(run->directory, 1000000);
    ffkx_split_result* result = NULL;
    run->status = ffkx_split(&options, run->task, &result, NULL);
    run->has_result = result != NULL;
    ffkx_split_result_free(result);
    if (run->release_when_done) ffkx_task_release(run->task);
    atomic_store(&run->done, 1);
    return NULL;
}

/* Waits until the split is writing its second segment (1), or has ended first (0). */
static int wait_for_second_segment(struct split_run* run, ffkx_task* task) {
    while (!atomic_load(&run->done)) {
        ffkx_progress progress;
        memset(&progress, 0, sizeof(progress));
        progress.struct_size = sizeof(progress);
        ffkx_task_progress(task, &progress);
        if (progress.segment >= 2) return 1;
        sched_yield();
    }
    return 0;
}

/* Cancels a split from another thread once it is writing its second segment. The samples are small, so
 * a run may end before that is seen; it is retried, and skipped if never seen. */
static void cancel_while_running(void) {
    for (int attempt = 0; attempt < 20; ++attempt) {
        char name[32];
        snprintf(name, sizeof(name), "cancel-%d", attempt);
        struct split_run run = {new_task(FFKX_LOG_WARNING, 10), new_directory(name), FFKX_OK, 0, 0, 0};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, run_split, &run) == 0);
        const int cancelled = wait_for_second_segment(&run, run.task);
        if (cancelled) ffkx_task_cancel(run.task);
        pthread_join(thread, NULL);
        ffkx_task_release(run.task);
        if (cancelled && run.status == FFKX_ERROR_CANCELLED) {
            CHECK(!run.has_result);
            return;
        }
        CHECK_STATUS(FFKX_OK, run.status);
        CHECK(run.has_result);
    }
    skip("every split finished before its second segment was seen");
}

/* ---- References and races (roadmap 6.2) ---------------------------------------------------------- */

static void task_references(void) {
    ffkx_task* task = new_task(FFKX_LOG_WARNING, 10);
    CHECK(ffkx_task_retain(task) == task);
    CHECK(ffkx_task_retain(NULL) == NULL);
    ffkx_task_release(task);
    /* One reference left: still usable. */
    ffkx_task_cancel(task);
    CHECK_I64(1, ffkx_task_is_cancelled(task));
    ffkx_task_release(task);
    ffkx_task_release(NULL);
}

/* The main thread drops its reference while the worker's split runs on its own: the split finishes. */
static void release_while_running(void) {
    for (int attempt = 0; attempt < 20; ++attempt) {
        char name[32];
        snprintf(name, sizeof(name), "release-%d", attempt);
        ffkx_task* task = new_task(FFKX_LOG_WARNING, 10);
        struct split_run run = {ffkx_task_retain(task), new_directory(name), FFKX_OK, 0, 0, 1};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, run_split, &run) == 0);
        const int midway = wait_for_second_segment(&run, task);
        ffkx_task_release(task); /* the worker's reference keeps the task until it is done */
        pthread_join(thread, NULL);
        CHECK_STATUS(FFKX_OK, run.status);
        CHECK(run.has_result);
        if (midway) return;
    }
    skip("every split finished before its second segment was seen");
}

/* A second operation on a task that is running one is refused, without touching the first. */
static void busy_task(void) {
    for (int attempt = 0; attempt < 20; ++attempt) {
        char name[32];
        snprintf(name, sizeof(name), "busy-%d", attempt);
        ffkx_task* task = new_task(FFKX_LOG_WARNING, 10);
        struct split_run run = {task, new_directory(name), FFKX_OK, 0, 0, 0};
        pthread_t thread;
        CHECK(pthread_create(&thread, NULL, run_split, &run) == 0);
        const int midway = wait_for_second_segment(&run, task);
        ffkx_status second = FFKX_OK;
        ffkx_error error = new_error();
        const char* output = scratch("busy.mp4");
        if (midway) {
            ffkx_trim_options options = trim_options(av_path, output, 0, 1000000);
            second = ffkx_trim(&options, task, NULL, &error);
        }
        pthread_join(thread, NULL);
        CHECK_STATUS(FFKX_OK, run.status);
        if (midway && second != FFKX_OK) {
            CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, second);
            CHECK_STR("task is running another operation", error.message);
            CHECK_I64(-1, file_size(output));
            /* Free again once the first is done. */
            ffkx_trim_options options = trim_options(av_path, output, 0, 1000000);
            CHECK_STATUS(FFKX_OK, ffkx_trim(&options, task, NULL, NULL));
            ffkx_task_release(task);
            return;
        }
        unlink(output);
        ffkx_task_release(task);
    }
    skip("every split finished before the second operation started");
}

struct canceller {
    ffkx_task* task;
    long delay_ns;
};

static void* cancel_later(void* argument) {
    struct canceller* canceller = argument;
    const struct timespec delay = {0, canceller->delay_ns};
    nanosleep(&delay, NULL);
    ffkx_task_cancel(canceller->task);
    ffkx_task_release(canceller->task);
    return NULL;
}

/* Cancelling and releasing race with the operation and with each other: three threads, three references,
 * released in whatever order the timing gives. */
static void cancel_release_races(void) {
    int cancelled = 0, finished = 0;
    for (int round = 0; round < 40; ++round) {
        char name[32];
        snprintf(name, sizeof(name), "race-%d", round);
        ffkx_task* task = new_task(FFKX_LOG_DEBUG, 50);
        struct split_run run = {ffkx_task_retain(task), new_directory(name), FFKX_OK, 0, 0, 1};
        /* 0 to 32 ms: early cancellations and ones after the split ended, on fast and slow devices. */
        struct canceller canceller = {ffkx_task_retain(task), (long)(round % 10) * (round % 10) * 400000L};
        pthread_t worker, cancelling;
        CHECK(pthread_create(&worker, NULL, run_split, &run) == 0);
        CHECK(pthread_create(&cancelling, NULL, cancel_later, &canceller) == 0);
        ffkx_task_release(task);
        pthread_join(worker, NULL);
        pthread_join(cancelling, NULL);
        CHECK(run.status == FFKX_OK || run.status == FFKX_ERROR_CANCELLED);
        CHECK(run.has_result == (run.status == FFKX_OK));
        if (run.status == FFKX_OK) ++finished; else ++cancelled;
    }
    printf("     cancel_release_races: %d cancelled, %d finished\n", cancelled, finished);
}

/* ---- Engine log sink (roadmap 6.2) --------------------------------------------------------------- */

#define SINK_ALIVE 0x5A5A5A5A
#define SINK_DEAD 0xDEADDEAD

/* A sink's user data. Released sinks are marked dead and kept, so a late call is seen, not a crash. */
struct recorder {
    atomic_uint magic;
    atomic_int calls;
    atomic_int releases;
    atomic_int in_callback;
    int released_while_in_callback;
    int32_t last_level;
    char last_component[64];
    char last_message[256];
    struct recorder* install_from_callback; /* installed by the first call, once */
};

static atomic_int late_calls; /* calls on a released sink */

static void record_line(void* user_data, int32_t level, const char* component, const char* message) {
    struct recorder* recorder = user_data;
    atomic_fetch_add(&recorder->in_callback, 1);
    if (atomic_load(&recorder->magic) != SINK_ALIVE) atomic_fetch_add(&late_calls, 1);
    if (atomic_fetch_add(&recorder->calls, 1) == 0) {
        recorder->last_level = level;
        snprintf(recorder->last_component, sizeof(recorder->last_component), "%s", component);
        snprintf(recorder->last_message, sizeof(recorder->last_message), "%s", message);
        struct recorder* next = recorder->install_from_callback;
        if (next != NULL) {
            recorder->install_from_callback = NULL;
            ffkx_engine_log_sink sink;
            memset(&sink, 0, sizeof(sink));
            sink.struct_size = sizeof(sink);
            sink.level = FFKX_LOG_ERROR;
            sink.callback = record_line;
            sink.user_data = next;
            ffkx_set_engine_log_sink(&sink);
        }
    }
    atomic_fetch_sub(&recorder->in_callback, 1);
}

static void release_recorder(void* user_data) {
    struct recorder* recorder = user_data;
    /* Dead first: a callback that starts after this sees it, one that started before is counted here. */
    atomic_store(&recorder->magic, SINK_DEAD);
    if (atomic_load(&recorder->in_callback) != 0) recorder->released_while_in_callback = 1;
    atomic_fetch_add(&recorder->releases, 1);
}

static void new_recorder(struct recorder* recorder) {
    memset(recorder, 0, sizeof(*recorder));
    atomic_store(&recorder->magic, SINK_ALIVE);
}

static ffkx_status install(struct recorder* recorder, int32_t level) {
    ffkx_engine_log_sink sink;
    memset(&sink, 0, sizeof(sink));
    sink.struct_size = sizeof(sink);
    sink.level = level;
    sink.callback = record_line;
    sink.user_data = recorder;
    sink.release = release_recorder;
    return ffkx_set_engine_log_sink(&sink);
}

/* A probe of the truncated sample logs "moov atom not found" at error level. */
static void probe_truncated(const char* path, ffkx_task* task) {
    ffkx_probe_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.input_path = path;
    ffkx_probe(&options, task, NULL, NULL);
}

static void engine_sink(void) {
    const char* truncated = truncated_sample();
    struct recorder first, second;
    new_recorder(&first);
    CHECK_STATUS(FFKX_OK, install(&first, FFKX_LOG_ERROR));
    probe_truncated(truncated, NULL);
    const int calls = atomic_load(&first.calls);
    CHECK(calls >= 1);
    CHECK_I64(FFKX_LOG_ERROR, first.last_level);
    CHECK_STR("mov,mp4,m4a,3gp,3g2,mj2", first.last_component);
    CHECK_STR("moov atom not found", first.last_message);

    /* Lines of an operation with a task go to its log, not to the sink. */
    ffkx_task* task = new_task(FFKX_LOG_ERROR, 10);
    probe_truncated(truncated, task);
    CHECK_I64(calls, atomic_load(&first.calls));
    CHECK(log_contains(task, "moov atom not found"));
    ffkx_task_release(task);

    /* Replacing a sink no thread is in releases it at once, exactly once. */
    new_recorder(&second);
    CHECK_STATUS(FFKX_OK, install(&second, FFKX_LOG_PANIC));
    CHECK_I64(1, atomic_load(&first.releases));
    probe_truncated(truncated, NULL); /* an error is less severe than panic: filtered */
    CHECK_I64(0, atomic_load(&second.calls));
    CHECK_STATUS(FFKX_OK, ffkx_set_engine_log_sink(NULL));
    CHECK_I64(1, atomic_load(&second.releases));
    CHECK_I64(1, atomic_load(&first.releases));

    /* A rejected sink is neither installed nor released. */
    struct recorder rejected;
    new_recorder(&rejected);
    ffkx_engine_log_sink sink;
    memset(&sink, 0, sizeof(sink));
    sink.struct_size = sizeof(sink);
    sink.user_data = &rejected;
    sink.release = release_recorder;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_set_engine_log_sink(&sink));
    sink.callback = record_line;
    sink.struct_size = 8;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_set_engine_log_sink(&sink));
    CHECK_I64(0, atomic_load(&rejected.releases));

    /* A callback may install another sink: the next line goes there, and the first is released after
     * its callback returned. */
    struct recorder outer, inner;
    new_recorder(&outer);
    new_recorder(&inner);
    outer.install_from_callback = &inner;
    CHECK_STATUS(FFKX_OK, install(&outer, FFKX_LOG_ERROR));
    probe_truncated(truncated, NULL);
    CHECK_I64(1, atomic_load(&outer.releases));
    CHECK_I64(0, outer.released_while_in_callback);
    probe_truncated(truncated, NULL);
    CHECK_I64(1, atomic_load(&outer.calls));
    CHECK(atomic_load(&inner.calls) >= 1);
    CHECK_STATUS(FFKX_OK, ffkx_set_engine_log_sink(NULL));
    CHECK_I64(0, atomic_load(&late_calls));
}

/* A sink whose callback tries to run an operation, which the contract forbids: the library refuses it, and
 * the line being sent is neither sent again nor changed. */
struct nested {
    int calls;
    ffkx_status nested_status;
    char message[256];
    char message_after[256];
    const char* path;
};

static void run_operation_in_callback(void* user_data, int32_t level, const char* component, const char* message) {
    struct nested* nested = user_data;
    (void)level;
    (void)component;
    if (nested->calls++ > 0) return;
    snprintf(nested->message, sizeof(nested->message), "%s", message);
    ffkx_probe_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.input_path = nested->path;
    ffkx_error error = new_error();
    nested->nested_status = ffkx_probe(&options, NULL, NULL, &error);
    snprintf(nested->message_after, sizeof(nested->message_after), "%s", message);
}

static void engine_sink_reentry(void) {
    struct nested nested;
    memset(&nested, 0, sizeof(nested));
    nested.path = scratch("truncated.mp4");
    ffkx_engine_log_sink sink;
    memset(&sink, 0, sizeof(sink));
    sink.struct_size = sizeof(sink);
    sink.level = FFKX_LOG_ERROR;
    sink.callback = run_operation_in_callback;
    sink.user_data = &nested;
    CHECK_STATUS(FFKX_OK, ffkx_set_engine_log_sink(&sink));
    probe_truncated(nested.path, NULL);
    CHECK_STATUS(FFKX_OK, ffkx_set_engine_log_sink(NULL));
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, nested.nested_status);
    CHECK_STR("moov atom not found", nested.message);
    CHECK_STR("moov atom not found", nested.message_after);
    CHECK(nested.calls >= 1);
}

/* Two threads install sinks at the same time, one with a debug level, one with error: afterwards the
 * level the library filters by is the installed sink's, so the debug sink gets the debug lines of a
 * probe that logs no error. */
struct setter {
    struct recorder* recorder;
    int32_t level;
    pthread_barrier_t* start;
};

static void* install_at_once(void* argument) {
    struct setter* setter = argument;
    pthread_barrier_wait(setter->start);
    install(setter->recorder, setter->level);
    return NULL;
}

static void engine_sink_concurrent_setters(void) {
    /* Without the lock in SetEngineSink this caught about one mismatch in 400 rounds on the emulator. */
    enum { kRounds = 1000 };
    int mismatches = 0, debug_won = 0;
    for (int round = 0; round < kRounds; ++round) {
        struct recorder debug, error;
        new_recorder(&debug);
        new_recorder(&error);
        pthread_barrier_t start;
        pthread_barrier_init(&start, NULL, 2);
        struct setter setters[2] = {{&debug, FFKX_LOG_DEBUG, &start}, {&error, FFKX_LOG_ERROR, &start}};
        pthread_t threads[2];
        for (int i = 0; i < 2; ++i) pthread_create(&threads[i], NULL, install_at_once, &setters[i]);
        for (int i = 0; i < 2; ++i) pthread_join(threads[i], NULL);
        pthread_barrier_destroy(&start);
        /* The one not yet released is installed. */
        const int debug_installed = atomic_load(&debug.releases) == 0;
        CHECK_I64(1, atomic_load(&debug.releases) + atomic_load(&error.releases));
        probe_truncated(av_path, NULL); /* a valid file: debug lines, no error */
        if (debug_installed) {
            ++debug_won;
            if (atomic_load(&debug.calls) == 0) ++mismatches;
        }
        CHECK_STATUS(FFKX_OK, ffkx_set_engine_log_sink(NULL));
    }
    CHECK_I64(0, mismatches);
    CHECK_I64(0, atomic_load(&late_calls));
    printf("     engine_sink_concurrent_setters: debug sink won %d of %d rounds\n", debug_won, kRounds);
}

struct logger {
    const char* path;
    atomic_int* stop;
    int probes;
};

static void* log_until_stopped(void* argument) {
    struct logger* logger = argument;
    while (!atomic_load(logger->stop)) {
        probe_truncated(logger->path, NULL);
        ++logger->probes;
    }
    return NULL;
}

/* Sinks replaced while four threads log through them: every sink is released exactly once, never while a
 * thread is in its callback, and no callback runs after its release. */
static void engine_sink_races(void) {
    enum { kSinks = 400, kLoggers = 4 };
    static struct recorder sinks[kSinks];
    const char* truncated = scratch("truncated.mp4"); /* written by engine_sink */
    atomic_int stop;
    atomic_init(&stop, 0);
    struct logger loggers[kLoggers];
    pthread_t threads[kLoggers];
    for (int i = 0; i < kLoggers; ++i) {
        loggers[i] = (struct logger){truncated, &stop, 0};
        CHECK(pthread_create(&threads[i], NULL, log_until_stopped, &loggers[i]) == 0);
    }
    for (int i = 0; i < kSinks; ++i) {
        new_recorder(&sinks[i]);
        CHECK_STATUS(FFKX_OK, install(&sinks[i], FFKX_LOG_ERROR));
        const struct timespec pause = {0, 50000};
        nanosleep(&pause, NULL);
    }
    CHECK_STATUS(FFKX_OK, ffkx_set_engine_log_sink(NULL));
    atomic_store(&stop, 1);
    int probes = 0, calls = 0, wrong = 0;
    for (int i = 0; i < kLoggers; ++i) {
        pthread_join(threads[i], NULL);
        probes += loggers[i].probes;
    }
    for (int i = 0; i < kSinks; ++i) {
        calls += atomic_load(&sinks[i].calls);
        if (atomic_load(&sinks[i].releases) != 1 || sinks[i].released_while_in_callback) ++wrong;
    }
    CHECK_I64(0, wrong);
    CHECK_I64(0, atomic_load(&late_calls));
    CHECK(calls > 0);
    printf("     engine_sink_races: %d probes, %d lines to %d sinks\n", probes, calls, kSinks);
}

struct parallel_trim {
    int64_t start_us;
    char output[512];
    ffkx_status status;
    ffkx_trim_result result;
};

static void* run_trim(void* argument) {
    struct parallel_trim* trim = argument;
    ffkx_task* task = NULL;
    trim->status = ffkx_task_create(NULL, &task);
    if (trim->status != FFKX_OK) return NULL;
    ffkx_trim_options options = trim_options(av_path, trim->output, trim->start_us, trim->start_us + 1000000);
    trim->result = new_trim_result();
    trim->status = ffkx_trim(&options, task, &trim->result, NULL);
    ffkx_task_release(task);
    return NULL;
}

static void parallel_operations(void) {
    struct parallel_trim trims[6];
    pthread_t threads[6];
    for (int i = 0; i < 6; ++i) {
        trims[i].start_us = (int64_t)(i + 1) * 1000000;
        snprintf(trims[i].output, sizeof(trims[i].output), "%s/parallel-%d.mp4", scratch_dir, i);
        CHECK(pthread_create(&threads[i], NULL, run_trim, &trims[i]) == 0);
    }
    for (int i = 0; i < 6; ++i) {
        pthread_join(threads[i], NULL);
        CHECK_STATUS(FFKX_OK, trims[i].status);
        /* Starts on a keyframe, so the start is exact. */
        CHECK_I64(trims[i].start_us, trims[i].result.actual_start_us);
    }
}

/* ---- Command line (roadmap 6.3) ------------------------------------------------------------------ */

#include <stdarg.h>

/* Runs ffmpeg with the arguments that follow, up to a NULL. */
static ffkx_status execute(ffkx_task* task, ffkx_error* error, ...) {
    const char* arguments[32];
    int count = 0;
    va_list list;
    va_start(list, error);
    for (const char* argument; count < 32 && (argument = va_arg(list, const char*)) != NULL;) arguments[count++] = argument;
    va_end(list);
    ffkx_execute_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.argument_count = count;
    options.arguments = arguments;
    return ffkx_execute(&options, task, error);
}

static void execute_copy(void) {
    const char* output = scratch("command-copy.mp4");
    ffkx_task* task = new_task(FFKX_LOG_VERBOSE, 1000);
    ffkx_error error = new_error();
    CHECK_STATUS(FFKX_OK, execute(task, &error, "-hide_banner", "-nostdin", "-i", av_path, "-t", "2", "-c", "copy", output, NULL));
    CHECK_STATUS(FFKX_OK, error.status);
    CHECK_I64(FFKX_SIDE_NONE, error.side);
    /* The worker threads' lines reach the task's log too. */
    CHECK(log_contains(task, "Terminating thread with return code 0"));
    const ffkx_progress progress = progress_of(task);
    CHECK(progress.processed_us >= 1900000);
    CHECK(progress.bytes_written > 0);
    CHECK_I64(FFKX_UNKNOWN, progress.duration_us);
    ffkx_task_release(task);

    ffkx_media_info* info = probe_path(output);
    const ffkx_format_info format = format_of(info);
    CHECK_I64(2, format.stream_count);
    CHECK(format.duration_us >= 1900000 && format.duration_us <= 2200000);
    ffkx_media_info_free(info);
}

static void execute_transcode(void) {
    const char* output = scratch("command-transcode.mp4");
    ffkx_error error = new_error();
    CHECK_STATUS(FFKX_OK, execute(NULL, &error, "-i", av_path, "-t", "1", "-vf", "scale=160:120", "-c:v", "mpeg4",
                                  "-c:a", "aac", output, NULL));
    ffkx_media_info* info = probe_path(output);
    const ffkx_stream_info video = stream_of(info, 0);
    CHECK_STR("mpeg4", video.codec_name);
    CHECK_I64(160, video.width);
    CHECK_I64(120, video.height);
    CHECK_STR("aac", stream_of(info, 1).codec_name);
    ffkx_media_info_free(info);
}

static void execute_failures(void) {
    const char* output = scratch("command-exists.mp4");
    ffkx_error error = new_error();
    /* -y overwrites, and is forgotten by the next command, which refuses to. */
    CHECK_STATUS(FFKX_OK, execute(NULL, &error, "-i", av_path, "-t", "1", "-c", "copy", output, NULL));
    CHECK_STATUS(FFKX_OK, execute(NULL, &error, "-y", "-i", av_path, "-t", "1", "-c", "copy", output, NULL));
    CHECK_STATUS(FFKX_ERROR_OUTPUT_EXISTS, execute(NULL, &error, "-i", av_path, "-t", "1", "-c", "copy", output, NULL));
    CHECK_I64(-EEXIST, error.ffmpeg_error);
    CHECK_I64(FFKX_SIDE_NONE, error.side);

    CHECK_STATUS(FFKX_ERROR_INPUT_NOT_FOUND,
                 execute(NULL, &error, "-i", scratch("missing.mp4"), scratch("command-missing.mp4"), NULL));
    CHECK_STR("ffmpeg failed: No such file or directory (-2)", error.message);

    CHECK_STATUS(FFKX_ERROR_FFMPEG, execute(NULL, &error, "-notanoption", "-i", av_path, scratch("x.mp4"), NULL));
    CHECK_I64(TAG(0xF8, 'O', 'P', 'T'), error.ffmpeg_error); /* AVERROR_OPTION_NOT_FOUND */

    /* No output: ffmpeg prints its usage and returns 1. */
    CHECK_STATUS(FFKX_ERROR_FFMPEG, execute(NULL, &error, NULL));
    CHECK_STR("ffmpeg exited with code 1", error.message);
    CHECK_I64(0, error.ffmpeg_error);
}

static void execute_refused_options(void) {
    ffkx_error error = new_error();
    const char* output = scratch("command-refused.mp4");
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, execute(NULL, &error, "-report", "-i", av_path, output, NULL));
    CHECK_STR("option -report is not available: it would change the whole process", error.message);
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, execute(NULL, &error, "-version", NULL));
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, execute(NULL, &error, "-i", av_path, "-cpuflags", "0", output, NULL));
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, execute(NULL, &error, "-stdin", "-i", av_path, output, NULL));
    /* -timelimit would set the whole app's CPU limit, and SIGXCPU would kill it. */
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, execute(NULL, &error, "-timelimit", "60", "-i", av_path, output, NULL));
    CHECK_I64(-1, file_size(output));
    /* An option's value is not an option, and -nostdin is not -stdin. */
    CHECK_STATUS(FFKX_OK, execute(NULL, &error, "-nostdin", "-i", av_path, "-t", "1", "-metadata", "title=-report",
                                  "-c", "copy", output, NULL));

    /* Struct rules as for the operations. */
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_execute(NULL, NULL, &error));
    ffkx_execute_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.argument_count = 1;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_execute(&options, NULL, &error));
    const char* with_null[] = {"-i", NULL};
    options.argument_count = 2;
    options.arguments = with_null;
    CHECK_STATUS(FFKX_ERROR_INVALID_ARGUMENT, ffkx_execute(&options, NULL, &error));
    options.argument_count = 0;
    options.reserved = 1;
    CHECK_STATUS(FFKX_ERROR_UNSUPPORTED_VERSION, ffkx_execute(&options, NULL, &error));
}

struct command_run {
    ffkx_task* task;
    ffkx_status status;
    atomic_int done;
};

/* Copies the sample in a loop forever into the null muxer: runs until cancelled. */
static void* run_endless_command(void* argument) {
    struct command_run* run = argument;
    ffkx_error error = new_error();
    const char* arguments[] = {"-nostdin", "-stream_loop", "-1", "-i", av_path, "-c", "copy", "-f", "null", "-"};
    ffkx_execute_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.argument_count = 10;
    options.arguments = arguments;
    run->status = ffkx_execute(&options, run->task, &error);
    atomic_store(&run->done, 1);
    return NULL;
}

static int64_t processed_us(const ffkx_task* task) {
    ffkx_progress progress;
    memset(&progress, 0, sizeof(progress));
    progress.struct_size = sizeof(progress);
    ffkx_task_progress(task, &progress);
    return progress.processed_us;
}

static int64_t now_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

/* An endless command is cancelled once it makes progress; a second command waiting for it is cancelled
 * while it waits. */
static void execute_cancel(void) {
    struct command_run running = {new_task(FFKX_LOG_WARNING, 10), FFKX_OK, 0};
    struct command_run waiting = {new_task(FFKX_LOG_WARNING, 10), FFKX_OK, 0};
    pthread_t first, second;
    CHECK(pthread_create(&first, NULL, run_endless_command, &running) == 0);
    const int64_t start = now_ms();
    while (processed_us(running.task) < 3000000 && now_ms() - start < 20000) sched_yield();
    CHECK(processed_us(running.task) >= 3000000);

    CHECK(pthread_create(&second, NULL, run_endless_command, &waiting) == 0);
    const struct timespec pause = {0, 100000000};
    nanosleep(&pause, NULL);
    CHECK(!atomic_load(&waiting.done)); /* waits for the first */
    int64_t cancelled_at = now_ms();
    ffkx_task_cancel(waiting.task);
    pthread_join(second, NULL);
    CHECK_STATUS(FFKX_ERROR_CANCELLED, waiting.status);
    CHECK(now_ms() - cancelled_at < 1000);
    CHECK_I64(0, processed_us(waiting.task));

    cancelled_at = now_ms();
    ffkx_task_cancel(running.task);
    pthread_join(first, NULL);
    CHECK_STATUS(FFKX_ERROR_CANCELLED, running.status);
    printf("     execute_cancel: stopped %lld ms after cancel\n", (long long)(now_ms() - cancelled_at));
    CHECK(now_ms() - cancelled_at < 3000);
    ffkx_task_release(running.task);
    ffkx_task_release(waiting.task);

    /* The next command runs normally. */
    ffkx_error error = new_error();
    CHECK_STATUS(FFKX_OK, execute(NULL, &error, "-i", av_path, "-t", "1", "-c", "copy", scratch("after-cancel.mp4"), NULL));
}

/* A TCP server that sends the start of an MPEG-TS sample and then nothing, keeping the connection open:
 * an input that stalls after transcoding has started. */
struct stalling_server {
    int listener;
    const char* source;
    atomic_int release;
};

static void* serve_then_stall(void* argument) {
    struct stalling_server* server = argument;
    const int client = accept(server->listener, NULL, NULL);
    if (client < 0) return NULL;
    FILE* source = fopen(server->source, "rb");
    char buffer[4096];
    size_t sent = 0, n;
    while (source != NULL && sent < 240 * 1024 && (n = fread(buffer, 1, sizeof(buffer), source)) > 0) {
        if (write(client, buffer, n) != (ssize_t)n) break;
        sent += n;
    }
    if (source != NULL) fclose(source);
    while (!atomic_load(&server->release)) {
        const struct timespec pause = {0, 10000000};
        nanosleep(&pause, NULL);
    }
    close(client);
    return NULL;
}

/* A command run on its own thread with given options. */
struct execute_call {
    struct command_run* run;
    const ffkx_execute_options* options;
};

static void* run_with_options(void* argument) {
    struct execute_call* call = argument;
    ffkx_error error = new_error();
    call->run->status = ffkx_execute(call->options, call->run->task, &error);
    atomic_store(&call->run->done, 1);
    return NULL;
}

/* Cancelling a command whose input stalls after transcoding started: the input is interrupted at once
 * (not only before transcoding, as for outputs), the command ends and frees the command line. */
static void execute_cancel_stalled_input(void) {
    char source[512]; /* the server thread reads it; sample()'s buffers are reused */
    snprintf(source, sizeof(source), "%s", sample("av_offset.ts"));
    struct stalling_server server = {socket(AF_INET, SOCK_STREAM, 0), source, 0};
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    socklen_t length = sizeof(address);
    CHECK(bind(server.listener, (struct sockaddr*)&address, sizeof(address)) == 0);
    CHECK(listen(server.listener, 1) == 0);
    CHECK(getsockname(server.listener, (struct sockaddr*)&address, &length) == 0);
    char url[64];
    snprintf(url, sizeof(url), "tcp://127.0.0.1:%d", ntohs(address.sin_port));
    pthread_t serving;
    CHECK(pthread_create(&serving, NULL, serve_then_stall, &server) == 0);

    struct command_run run = {new_task(FFKX_LOG_WARNING, 10), FFKX_OK, 0};
    /* A short probe, so that transcoding starts on the data sent. */
    const char* arguments[] = {"-nostdin", "-probesize", "65536", "-analyzeduration", "1000000", "-i", url,
                               "-c", "copy", "-f", "null", "-"};
    ffkx_execute_options options;
    memset(&options, 0, sizeof(options));
    options.struct_size = sizeof(options);
    options.argument_count = 12;
    options.arguments = arguments;
    pthread_t command;
    struct execute_call call = {&run, &options};
    CHECK(pthread_create(&command, NULL, run_with_options, &call) == 0);

    /* Transcoding has started once something was written; then the input stalls. */
    const int64_t start = now_ms();
    while (processed_us(run.task) == 0 && now_ms() - start < 20000) sched_yield();
    CHECK(processed_us(run.task) > 0);
    const struct timespec settle = {0, 300000000};
    nanosleep(&settle, NULL);
    CHECK(!atomic_load(&run.done));

    const int64_t cancelled_at = now_ms();
    ffkx_task_cancel(run.task);
    for (int64_t waited = 0; !atomic_load(&run.done) && waited < 10000; waited = now_ms() - cancelled_at) sched_yield();
    const int64_t took = now_ms() - cancelled_at;
    printf("     execute_cancel_stalled_input: stopped %lld ms after cancel\n", (long long)took);
    CHECK(atomic_load(&run.done));
    CHECK(took < 2000);

    atomic_store(&server.release, 1);
    pthread_join(command, NULL);
    pthread_join(serving, NULL);
    close(server.listener);
    CHECK_STATUS(FFKX_ERROR_CANCELLED, run.status);
    ffkx_task_release(run.task);
}

/* ---- Main ---------------------------------------------------------------------------------------- */

int main(int argc, char** argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <media dir> <empty scratch dir>\n", argv[0]);
        return 2;
    }
    media_dir = argv[1];
    scratch_dir = argv[2];
    snprintf(av_path, sizeof(av_path), "%s/%s", media_dir, AV);
    printf("ffkx_capi_test: C ABI %u.%u, FFmpeg %s, %zu-bit\n", ffkx_abi_version() >> 16,
           ffkx_abi_version() & 0xFFFFu, ffkx_ffmpeg_version(), P * 8);

    run("versions", versions);
    run("statuses", statuses);
    run("options_versions", options_versions);
    run("result_versions", result_versions);
    run("task_options_versions", task_options_versions);
    run("trim_from_path", trim_from_path);
    run("trim_from_descriptor", trim_from_descriptor);
    run("trim_edit_list", trim_edit_list);
    run("trim_failures", trim_failures);
    run("trim_arguments", trim_arguments);
    run("split_segments", split_segments);
    run("split_limits", split_limits);
    run("probe_streams", probe_streams);
    run("probe_samples", probe_samples);
    run("task_progress", task_progress);
    run("task_logs", task_logs);
    run("cancel_before_start", cancel_before_start);
    run("cancel_while_running", cancel_while_running);
    run("parallel_operations", parallel_operations);
    run("task_references", task_references);
    run("release_while_running", release_while_running);
    run("busy_task", busy_task);
    run("cancel_release_races", cancel_release_races);
    run("engine_sink", engine_sink);
    run("engine_sink_reentry", engine_sink_reentry);
    run("engine_sink_concurrent_setters", engine_sink_concurrent_setters);
    run("engine_sink_races", engine_sink_races);
    run("execute_copy", execute_copy);
    run("execute_transcode", execute_transcode);
    run("execute_failures", execute_failures);
    run("execute_refused_options", execute_refused_options);
    run("execute_cancel", execute_cancel);
    run("execute_cancel_stalled_input", execute_cancel_stalled_input);

    printf("ffkx_capi_test: %d checks, %d failed, %d skipped\n", checks, failures, skips);
    return failures == 0 ? 0 : 1;
}
