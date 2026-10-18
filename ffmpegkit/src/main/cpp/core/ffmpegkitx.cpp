// The C ABI (include/ffmpegkitx.h): checks arguments and struct versions, converts between the public
// structs and the core's types, and keeps C++ exceptions inside the library.
#include "ffmpegkitx.h"

#include <algorithm>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <exception>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "operations.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavfilter/avfilter.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libswresample/swresample.h"
#include "libswscale/swscale.h"
}

static_assert(FFKX_LOG_PANIC == AV_LOG_PANIC && FFKX_LOG_FATAL == AV_LOG_FATAL && FFKX_LOG_ERROR == AV_LOG_ERROR &&
                  FFKX_LOG_WARNING == AV_LOG_WARNING && FFKX_LOG_INFO == AV_LOG_INFO &&
                  FFKX_LOG_VERBOSE == AV_LOG_VERBOSE && FFKX_LOG_DEBUG == AV_LOG_DEBUG && FFKX_LOG_TRACE == AV_LOG_TRACE,
              "FFKX_LOG_* are FFmpeg's levels");
static_assert(FFKX_UNKNOWN == AV_NOPTS_VALUE, "probe.cpp passes AV_NOPTS_VALUE through as unknown");

struct ffkx_task {
    explicit ffkx_task(const ffkx_task_options& options)
        : impl(options.log_level, options.max_log_entries, options.read_timeout_us, options.max_buffered_bytes) {}

    remux::Task impl;
};

struct ffkx_log {
    std::vector<remux::LogEntry> entries;
    int64_t dropped = 0;
};

struct ffkx_split_result {
    std::vector<remux::Segment> segments;
};

struct ffkx_media_info {
    remux::MediaDescription description;
};

namespace {

// FFmpeg's log callback is installed when the library is loaded, before any FFmpeg call can log.
[[maybe_unused]] const bool kLogCallbackInstalled = (remux::InstallLogCallback(), true);

// The size of a struct's first version, which callers may not go below: where its last field ended.
// Keep these as they are when fields are appended.
#define FIRST_VERSION_SIZE(type, last_field) (offsetof(type, last_field) + sizeof(type::last_field))
constexpr size_t kTaskOptionsSize = FIRST_VERSION_SIZE(ffkx_task_options, max_buffered_bytes);
constexpr size_t kErrorSize = FIRST_VERSION_SIZE(ffkx_error, message);
constexpr size_t kProgressSize = FIRST_VERSION_SIZE(ffkx_progress, bytes_written);
constexpr size_t kLogEntrySize = FIRST_VERSION_SIZE(ffkx_log_entry, message);
constexpr size_t kTrimOptionsSize = FIRST_VERSION_SIZE(ffkx_trim_options, output_path);
constexpr size_t kTrimResultSize = FIRST_VERSION_SIZE(ffkx_trim_result, actual_end_us);
constexpr size_t kSplitOptionsSize = FIRST_VERSION_SIZE(ffkx_split_options, extension);
constexpr size_t kSegmentSize = FIRST_VERSION_SIZE(ffkx_segment, end_us);
constexpr size_t kProbeOptionsSize = FIRST_VERSION_SIZE(ffkx_probe_options, input_path);
constexpr size_t kFormatInfoSize = FIRST_VERSION_SIZE(ffkx_format_info, format_name);
constexpr size_t kStreamInfoSize = FIRST_VERSION_SIZE(ffkx_stream_info, channel_layout);
#undef FIRST_VERSION_SIZE

// No version of any struct gets near this; a larger struct_size is garbage, not a newer header.
constexpr size_t kMaxStructSize = 64 * 1024;

// An argument the caller got wrong; the message names it.
struct Invalid {
    ffkx_status status;
    const char* message;
};

// Copies a struct the caller passed in into `out`, zero where the caller's version has no field.
// Bytes the library does not know must be zero.
template <typename T>
ffkx_status ReadStruct(const T* in, size_t first_size, T* out) {
    if (in == nullptr || in->struct_size < first_size || in->struct_size > kMaxStructSize) {
        return FFKX_ERROR_INVALID_ARGUMENT;
    }
    const size_t size = in->struct_size;
    const auto* bytes = reinterpret_cast<const unsigned char*>(in);
    for (size_t i = sizeof(T); i < size; ++i) {
        if (bytes[i] != 0) return FFKX_ERROR_UNSUPPORTED_VERSION;
    }
    std::memset(out, 0, sizeof(T));
    std::memcpy(out, in, std::min(size, sizeof(T)));
    return FFKX_OK;
}

// Whether the library may fill a struct of the caller's.
template <typename T>
bool Writable(const T* out, size_t first_size) {
    return out != nullptr && out->struct_size >= first_size && out->struct_size <= kMaxStructSize;
}

// Fills a struct of the caller's up to its struct_size, zeroing what the library does not know.
template <typename T>
void WriteStruct(T value, T* out) {
    const size_t size = out->struct_size;
    value.struct_size = out->struct_size;
    std::memcpy(out, &value, std::min(size, sizeof(T)));
    if (size > sizeof(T)) std::memset(reinterpret_cast<unsigned char*>(out) + sizeof(T), 0, size - sizeof(T));
}

// Copies `message` into the error, cut at a UTF-8 character boundary if it does not fit.
void CopyMessage(const char* message, char (&buffer)[FFKX_ERROR_MESSAGE_SIZE]) {
    size_t length = std::strlen(message);
    if (length >= sizeof(buffer)) {
        length = sizeof(buffer) - 1;
        while (length > 0 && (static_cast<unsigned char>(message[length]) & 0xC0) == 0x80) --length;
    }
    std::memcpy(buffer, message, length);
    buffer[length] = '\0';
}

ffkx_status Report(ffkx_error* error, ffkx_status status, int32_t code, int32_t side, const char* message) {
    if (error != nullptr) {
        ffkx_error value{};
        value.status = status;
        value.ffmpeg_error = code;
        value.side = side;
        CopyMessage(message, value.message);
        WriteStruct(value, error);
    }
    return status;
}

ffkx_status Report(ffkx_error* error, const remux::Status& status) {
    // Cancellation and the library's own failures concern neither side.
    const bool sided = status.failed() && status.status != FFKX_ERROR_CANCELLED &&
                       status.status != FFKX_ERROR_OUT_OF_MEMORY && status.status != FFKX_ERROR_INTERNAL;
    const int32_t side = !sided ? FFKX_SIDE_NONE : status.output_side ? FFKX_SIDE_OUTPUT : FFKX_SIDE_INPUT;
    return Report(error, status.status, status.code, side, status.message.c_str());
}

ffkx_status Report(ffkx_error* error, const Invalid& invalid) {
    return Report(error, invalid.status, 0, FFKX_SIDE_NONE, invalid.message);
}

// Reads a versioned options struct; on failure says which one.
template <typename T>
bool ReadOptions(const T* in, size_t first_size, T* out, Invalid* invalid) {
    const ffkx_status status = ReadStruct(in, first_size, out);
    if (status == FFKX_OK) return true;
    if (in == nullptr) {
        *invalid = {status, "options is NULL"};
    } else if (status == FFKX_ERROR_INVALID_ARGUMENT) {
        *invalid = {status, "options.struct_size is smaller than the first version of the struct, or garbage"};
    } else {
        *invalid = {status, "options sets fields this library does not know"};
    }
    return false;
}

// Flags and reserved fields from a newer header are versions this library does not support.
bool CheckFlags(uint32_t flags, uint32_t known, int32_t reserved, Invalid* invalid) {
    if ((flags & ~known) != 0) {
        *invalid = {FFKX_ERROR_UNSUPPORTED_VERSION, "options.flags has bits this library does not know"};
        return false;
    }
    if (reserved != 0) {
        *invalid = {FFKX_ERROR_UNSUPPORTED_VERSION, "options.reserved is not 0"};
        return false;
    }
    return true;
}

bool ReadSource(const char* path, int32_t fd, remux::Source* source, Invalid* invalid) {
    if (path != nullptr) {
        source->path = path;  // an empty path is a missing file, as for open()
        return true;
    }
    if (fd < 0) {
        *invalid = {FFKX_ERROR_INVALID_ARGUMENT, "neither input_path nor input_fd is set"};
        return false;
    }
    source->fd = fd;
    return true;
}

// Runs `body`, turning exceptions into statuses: no exception may cross the C ABI.
template <typename Body>
ffkx_status Guard(ffkx_error* error, Body&& body) {
    try {
        return body();
    } catch (const std::bad_alloc&) {
        return Report(error, FFKX_ERROR_OUT_OF_MEMORY, AVERROR(ENOMEM), FFKX_SIDE_NONE, "out of memory");
    } catch (const std::exception& e) {
        return Report(error, FFKX_ERROR_INTERNAL, 0, FFKX_SIDE_NONE, e.what());
    } catch (...) {
        return Report(error, FFKX_ERROR_INTERNAL, 0, FFKX_SIDE_NONE, "unknown exception");
    }
}

// The task an operation reports to, and its log scope: a scratch task when the caller passed none,
// whose FFmpeg lines are engine logs.
class OperationTask {
public:
    explicit OperationTask(ffkx_task* task)
        : task_(task != nullptr ? &task->impl : &scratch_), logs_(task != nullptr ? &task->impl.log : nullptr) {}

    remux::Task* get() { return task_; }

private:
    remux::Task scratch_;
    remux::Task* const task_;
    remux::LogScope logs_;
};

bool IsLetterOrDigit(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
}

}  // namespace

extern "C" {

// ---- Versions ------------------------------------------------------------------------------------

uint32_t ffkx_abi_version(void) {
    return (FFKX_ABI_VERSION_MAJOR << 16) | FFKX_ABI_VERSION_MINOR;
}

const char* ffkx_ffmpeg_version(void) {
    return av_version_info();
}

const char* ffkx_ffmpeg_configuration(void) {
    return avcodec_configuration();
}

uint32_t ffkx_ffmpeg_library_version(int32_t library) {
    // FFmpeg packs versions as (major << 16) | (minor << 8) | micro too (AV_VERSION_INT).
    switch (library) {
        case FFKX_LIBRARY_AVUTIL:
            return avutil_version();
        case FFKX_LIBRARY_AVCODEC:
            return avcodec_version();
        case FFKX_LIBRARY_AVFORMAT:
            return avformat_version();
        case FFKX_LIBRARY_AVFILTER:
            return avfilter_version();
        case FFKX_LIBRARY_SWSCALE:
            return swscale_version();
        case FFKX_LIBRARY_SWRESAMPLE:
            return swresample_version();
        default:
            return 0;
    }
}

// ---- Status --------------------------------------------------------------------------------------

const char* ffkx_status_name(ffkx_status status) {
    switch (status) {
        case FFKX_OK:
            return "FFKX_OK";
        case FFKX_ERROR_INVALID_ARGUMENT:
            return "FFKX_ERROR_INVALID_ARGUMENT";
        case FFKX_ERROR_UNSUPPORTED_VERSION:
            return "FFKX_ERROR_UNSUPPORTED_VERSION";
        case FFKX_ERROR_OUT_OF_MEMORY:
            return "FFKX_ERROR_OUT_OF_MEMORY";
        case FFKX_ERROR_INTERNAL:
            return "FFKX_ERROR_INTERNAL";
        case FFKX_ERROR_CANCELLED:
            return "FFKX_ERROR_CANCELLED";
        case FFKX_ERROR_INPUT_NOT_FOUND:
            return "FFKX_ERROR_INPUT_NOT_FOUND";
        case FFKX_ERROR_ACCESS_DENIED:
            return "FFKX_ERROR_ACCESS_DENIED";
        case FFKX_ERROR_OUTPUT_EXISTS:
            return "FFKX_ERROR_OUTPUT_EXISTS";
        case FFKX_ERROR_INSUFFICIENT_SPACE:
            return "FFKX_ERROR_INSUFFICIENT_SPACE";
        case FFKX_ERROR_RANGE_OUTSIDE_MEDIA:
            return "FFKX_ERROR_RANGE_OUTSIDE_MEDIA";
        case FFKX_ERROR_TOO_MANY_SEGMENTS:
            return "FFKX_ERROR_TOO_MANY_SEGMENTS";
        case FFKX_ERROR_UNSUPPORTED_MEDIA:
            return "FFKX_ERROR_UNSUPPORTED_MEDIA";
        case FFKX_ERROR_INVALID_MEDIA:
            return "FFKX_ERROR_INVALID_MEDIA";
        case FFKX_ERROR_IO:
            return "FFKX_ERROR_IO";
        case FFKX_ERROR_FFMPEG:
            return "FFKX_ERROR_FFMPEG";
        default:
            return "unknown status";
    }
}

ffkx_status ffkx_status_from_ffmpeg_error(int32_t ffmpeg_error, int32_t side) {
    return remux::StatusOf(ffmpeg_error, side == FFKX_SIDE_OUTPUT);
}

void ffkx_ffmpeg_error_string(int32_t ffmpeg_error, char* buffer, size_t size) {
    if (buffer == nullptr || size == 0) return;
    // av_strerror gives up on the description when the buffer is short; format it whole, then cut.
    char text[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(ffmpeg_error, text, sizeof(text));
    const size_t length = std::min(std::strlen(text), size - 1);
    std::memcpy(buffer, text, length);
    buffer[length] = '\0';
}

// ---- Tasks ---------------------------------------------------------------------------------------

ffkx_status ffkx_task_create(const ffkx_task_options* options, ffkx_task** task) {
    if (task == nullptr) return FFKX_ERROR_INVALID_ARGUMENT;
    *task = nullptr;
    ffkx_task_options values{};
    if (options != nullptr) {
        const ffkx_status status = ReadStruct(options, kTaskOptionsSize, &values);
        if (status != FFKX_OK) return status;
    }
    if (values.flags != 0) return FFKX_ERROR_UNSUPPORTED_VERSION;
    if (values.max_log_entries < 0 || values.read_timeout_us < 0 || values.max_buffered_bytes < 0) {
        return FFKX_ERROR_INVALID_ARGUMENT;
    }
    try {
        *task = new ffkx_task(values);
        return FFKX_OK;
    } catch (const std::bad_alloc&) {
        return FFKX_ERROR_OUT_OF_MEMORY;
    }
}

void ffkx_task_destroy(ffkx_task* task) {
    delete task;
}

void ffkx_task_cancel(ffkx_task* task) {
    if (task != nullptr) task->impl.cancelled.store(true, std::memory_order_relaxed);
}

int32_t ffkx_task_is_cancelled(const ffkx_task* task) {
    return task != nullptr && task->impl.IsCancelled() ? 1 : 0;
}

ffkx_status ffkx_task_progress(const ffkx_task* task, ffkx_progress* progress) {
    if (task == nullptr || !Writable(progress, kProgressSize)) return FFKX_ERROR_INVALID_ARGUMENT;
    ffkx_progress value{};
    value.processed_us = task->impl.processed_us.load(std::memory_order_relaxed);
    value.duration_us = task->impl.duration_us.load(std::memory_order_relaxed);
    value.bytes_written = task->impl.bytes_written.load(std::memory_order_relaxed);
    value.segment = task->impl.segment.load(std::memory_order_relaxed);
    WriteStruct(value, progress);
    return FFKX_OK;
}

void ffkx_task_set_segment(ffkx_task* task, int32_t segment) {
    if (task != nullptr) task->impl.segment.store(segment, std::memory_order_relaxed);
}

// ---- Logs ----------------------------------------------------------------------------------------

ffkx_status ffkx_task_log(const ffkx_task* task, ffkx_log** log) {
    if (log == nullptr) return FFKX_ERROR_INVALID_ARGUMENT;
    *log = nullptr;
    if (task == nullptr) return FFKX_ERROR_INVALID_ARGUMENT;
    try {
        auto snapshot = std::make_unique<ffkx_log>();
        snapshot->entries = task->impl.log.Entries(&snapshot->dropped);
        *log = snapshot.release();
        return FFKX_OK;
    } catch (const std::bad_alloc&) {
        return FFKX_ERROR_OUT_OF_MEMORY;
    }
}

uint32_t ffkx_log_count(const ffkx_log* log) {
    return log == nullptr ? 0 : static_cast<uint32_t>(log->entries.size());
}

uint64_t ffkx_log_dropped(const ffkx_log* log) {
    return log == nullptr ? 0 : static_cast<uint64_t>(log->dropped);
}

ffkx_status ffkx_log_entry_at(const ffkx_log* log, uint32_t index, ffkx_log_entry* entry) {
    if (log == nullptr || index >= log->entries.size() || !Writable(entry, kLogEntrySize)) {
        return FFKX_ERROR_INVALID_ARGUMENT;
    }
    const remux::LogEntry& source = log->entries[index];
    ffkx_log_entry value{};
    value.level = source.level;
    value.component = source.component.c_str();
    value.message = source.message.c_str();
    WriteStruct(value, entry);
    return FFKX_OK;
}

void ffkx_log_free(ffkx_log* log) {
    delete log;
}

// ---- Operations ----------------------------------------------------------------------------------

ffkx_status ffkx_trim(const ffkx_trim_options* options, ffkx_task* task, ffkx_trim_result* result, ffkx_error* error) {
    if (error != nullptr && !Writable(error, kErrorSize)) return FFKX_ERROR_INVALID_ARGUMENT;
    return Guard(error, [&]() -> ffkx_status {
        ffkx_trim_options values;
        remux::TrimRequest request;
        Invalid invalid{};
        if (!ReadOptions(options, kTrimOptionsSize, &values, &invalid) ||
            !CheckFlags(values.flags, FFKX_TRIM_EDIT_LIST, values.reserved, &invalid) ||
            !ReadSource(values.input_path, values.input_fd, &request.input, &invalid)) {
            return Report(error, invalid);
        }
        if (result != nullptr && !Writable(result, kTrimResultSize)) {
            return Report(error, Invalid{FFKX_ERROR_INVALID_ARGUMENT, "result.struct_size is smaller than the first version of the struct"});
        }
        if (values.output_path == nullptr || *values.output_path == '\0') {
            return Report(error, Invalid{FFKX_ERROR_INVALID_ARGUMENT, "output_path is not set"});
        }
        if (values.start_us < 0) return Report(error, Invalid{FFKX_ERROR_INVALID_ARGUMENT, "start_us is negative"});
        if (values.end_us <= values.start_us) {
            return Report(error, Invalid{FFKX_ERROR_INVALID_ARGUMENT, "end_us is not after start_us"});
        }
        request.output = values.output_path;
        request.start_us = values.start_us;
        request.end_us = values.end_us;
        request.edit_list = (values.flags & FFKX_TRIM_EDIT_LIST) != 0;

        OperationTask operation(task);
        ffkx_trim_result value{};
        const remux::Status status = remux::Trim(request, operation.get(), &value.actual_start_us, &value.actual_end_us);
        if (!status.failed() && result != nullptr) WriteStruct(value, result);
        return Report(error, status);
    });
}

ffkx_status ffkx_split(const ffkx_split_options* options, ffkx_task* task, ffkx_split_result** result,
                       ffkx_error* error) {
    if (result != nullptr) *result = nullptr;
    if (error != nullptr && !Writable(error, kErrorSize)) return FFKX_ERROR_INVALID_ARGUMENT;
    return Guard(error, [&]() -> ffkx_status {
        ffkx_split_options values;
        remux::SplitRequest request;
        Invalid invalid{};
        if (!ReadOptions(options, kSplitOptionsSize, &values, &invalid) ||
            !CheckFlags(values.flags, 0, 0, &invalid) ||
            !ReadSource(values.input_path, values.input_fd, &request.input, &invalid)) {
            return Report(error, invalid);
        }
        if (values.segment_us <= 0) return Report(error, Invalid{FFKX_ERROR_INVALID_ARGUMENT, "segment_us is not positive"});
        if (values.max_segments < 0 || values.max_segments > FFKX_MAX_SEGMENTS) {
            return Report(error, Invalid{FFKX_ERROR_INVALID_ARGUMENT, "max_segments is outside 0..FFKX_MAX_SEGMENTS"});
        }
        if (values.output_directory == nullptr || *values.output_directory == '\0') {
            return Report(error, Invalid{FFKX_ERROR_INVALID_ARGUMENT, "output_directory is not set"});
        }
        const char* prefix = values.file_name_prefix != nullptr ? values.file_name_prefix : "";
        if (std::strchr(prefix, '/') != nullptr) {
            return Report(error, Invalid{FFKX_ERROR_INVALID_ARGUMENT, "file_name_prefix contains '/'"});
        }
        const char* extension = values.extension != nullptr ? values.extension : "";
        if (*extension == '\0' || !std::all_of(extension, extension + std::strlen(extension), IsLetterOrDigit)) {
            return Report(error, Invalid{FFKX_ERROR_INVALID_ARGUMENT, "extension is not letters and digits"});
        }
        request.directory = values.output_directory;
        request.prefix = prefix;
        request.extension = extension;
        request.segment_us = values.segment_us;
        request.max_segments = values.max_segments == 0 ? FFKX_MAX_SEGMENTS : values.max_segments;

        OperationTask operation(task);
        auto segments = std::make_unique<ffkx_split_result>();
        const remux::Status status = remux::Split(request, operation.get(), &segments->segments);
        if (!status.failed() && result != nullptr) *result = segments.release();
        return Report(error, status);
    });
}

uint32_t ffkx_split_result_count(const ffkx_split_result* result) {
    return result == nullptr ? 0 : static_cast<uint32_t>(result->segments.size());
}

ffkx_status ffkx_split_result_segment(const ffkx_split_result* result, uint32_t index, ffkx_segment* segment) {
    if (result == nullptr || index >= result->segments.size() || !Writable(segment, kSegmentSize)) {
        return FFKX_ERROR_INVALID_ARGUMENT;
    }
    ffkx_segment value{};
    value.start_us = result->segments[index].start_us;
    value.end_us = result->segments[index].end_us;
    WriteStruct(value, segment);
    return FFKX_OK;
}

void ffkx_split_result_free(ffkx_split_result* result) {
    delete result;
}

ffkx_status ffkx_probe(const ffkx_probe_options* options, ffkx_task* task, ffkx_media_info** info,
                       ffkx_error* error) {
    if (info != nullptr) *info = nullptr;
    if (error != nullptr && !Writable(error, kErrorSize)) return FFKX_ERROR_INVALID_ARGUMENT;
    return Guard(error, [&]() -> ffkx_status {
        ffkx_probe_options values;
        remux::Source source;
        Invalid invalid{};
        if (!ReadOptions(options, kProbeOptionsSize, &values, &invalid) ||
            !CheckFlags(values.flags, 0, values.reserved, &invalid) ||
            !ReadSource(values.input_path, values.input_fd, &source, &invalid)) {
            return Report(error, invalid);
        }
        OperationTask operation(task);
        auto media = std::make_unique<ffkx_media_info>();
        const remux::Status status = remux::Probe(source, operation.get(), &media->description);
        if (!status.failed() && info != nullptr) *info = media.release();
        return Report(error, status);
    });
}

ffkx_status ffkx_media_info_format(const ffkx_media_info* info, ffkx_format_info* format) {
    if (info == nullptr || !Writable(format, kFormatInfoSize)) return FFKX_ERROR_INVALID_ARGUMENT;
    const remux::MediaDescription& media = info->description;
    ffkx_format_info value{};
    value.stream_count = static_cast<uint32_t>(media.streams.size());
    value.start_time_us = media.start_time_us;
    value.duration_us = media.duration_us;
    value.bit_rate = media.bit_rate;
    value.format_name = media.format_name.c_str();
    WriteStruct(value, format);
    return FFKX_OK;
}

ffkx_status ffkx_media_info_stream(const ffkx_media_info* info, uint32_t index, ffkx_stream_info* stream) {
    if (info == nullptr || index >= info->description.streams.size() || !Writable(stream, kStreamInfoSize)) {
        return FFKX_ERROR_INVALID_ARGUMENT;
    }
    const remux::StreamDescription& source = info->description.streams[index];
    ffkx_stream_info value{};
    value.media_type = source.media_type;
    value.flags = source.flags;
    value.duration_us = source.duration_us;
    value.bit_rate = source.bit_rate;
    value.width = source.width;
    value.height = source.height;
    value.frame_rate_num = source.frame_rate_num;
    value.frame_rate_den = source.frame_rate_den;
    value.rotation = source.rotation;
    value.sample_rate = source.sample_rate;
    value.channels = source.channels;
    value.codec_name = source.codec_name.c_str();
    value.channel_layout = source.channel_layout ? source.channel_layout->c_str() : nullptr;
    WriteStruct(value, stream);
    return FFKX_OK;
}

void ffkx_media_info_free(ffkx_media_info* info) {
    delete info;
}

}  // extern "C"
