// Shared stream-copy plumbing for trim.cpp and split.cpp; probe.cpp uses the input side.
#pragma once

#include <jni.h>
#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "fd_reader.h"
#include "log.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
}

namespace remux {

// Must match NativeRemuxStatus.REASON_* in NativeRemux.kt.
constexpr int kReasonFromCode = 0;
constexpr int kReasonRangeOutsideMedia = 1;
constexpr int kReasonUnsupportedMedia = 2;
constexpr int kReasonTooManySegments = 3;

struct Status {
    int code = 0;              // AVERROR, 0 when the failure is not an FFmpeg error
    bool output_side = false;  // which side failed, for mapping the code
    int reason = kReasonFromCode;
    std::string detail;

    bool failed() const { return code != 0 || reason != kReasonFromCode; }
};

Status Failure(int code, bool output_side, std::string detail);
Status Rejected(int reason, bool output_side, std::string detail, int code = 0);

struct PacketDeleter {
    void operator()(AVPacket* packet) const { av_packet_free(&packet); }
};
using Packet = std::unique_ptr<AVPacket, PacketDeleter>;

struct Track {
    int output_index = -1;  // -1: not copied
    bool video = false;
    bool finished = false;
    int64_t written = 0;
};

struct Input {
    AVFormatContext* ctx = nullptr;
    AVIOContext* io = nullptr;  // custom I/O when reading from a file descriptor
    std::unique_ptr<FdReader> reader;  // behind `io`
    std::vector<Track> tracks;
    bool has_video = false;
    int reference_stream = -1;  // the video stream, else the first copied audio stream
    int64_t origin = 0;         // start_time in microseconds; all reported times are relative to it

    Input() = default;
    Input(const Input&) = delete;
    Input& operator=(const Input&) = delete;
    ~Input();
};

// Where to read from: a path, or (fd >= 0) an open file descriptor that stays owned by the caller.
struct Source {
    std::string path;
    int fd = -1;
};

// Shared with Kotlin (NativeTask) for the duration of one operation, which may make several native
// calls (split by ranges trims once per range). Kotlin sets `cancelled` when the coroutine is cancelled;
// FFmpeg's interrupt callback and the packet loops poll it, and interrupted work fails with AVERROR_EXIT
// and cleans up as usual. The worker writes the progress counters and Kotlin polls them from another
// thread, so every field is atomic; each has a single writer.
struct Task {
    explicit Task(int log_level = AV_LOG_WARNING, int max_log_entries = 0, int64_t read_timeout_us = 0)
        : read_timeout_us(read_timeout_us), log(log_level, max_log_entries) {}

    std::atomic<bool> cancelled{false};
    // A descriptor read that takes longer fails with AVERROR(ETIMEDOUT); 0: no limit (FdReader).
    std::atomic<int64_t> read_timeout_us;
    // Media time written on the operation's target timeline, summed over its calls (see Progress).
    std::atomic<int64_t> processed_us{0};
    // The input's duration once it is open; -1 while unknown, or when FFmpeg only estimated it from the bit rate.
    std::atomic<int64_t> duration_us{-1};
    // Bytes written to all outputs, including headers and trailers.
    std::atomic<int64_t> bytes_written{0};
    // 1-based segment being written: set by split.cpp, or from Kotlin before each range of a split by ranges.
    std::atomic<int32_t> segment{1};
    // What FFmpeg logged on the worker thread during the task's calls (each call holds a LogScope).
    LogBuffer log;

    bool IsCancelled() const { return cancelled.load(std::memory_order_relaxed); }
};

// The Task behind a handle from NativeTask.create(), or `scratch` for handle 0 (calls made without a
// task, whose progress nobody reads).
Task* FromHandle(jlong handle, Task* scratch);

// The log of the Task behind a handle, or null for handle 0 (its logs are engine logs).
LogBuffer* LogOf(jlong handle);

// Reports the media time one native call has written, on top of what earlier calls of the task wrote.
class Progress {
public:
    explicit Progress(Task* task) : task_(task), base_(task->processed_us.load(std::memory_order_relaxed)) {}

    // `us`: target-timeline time written by this call so far; never moves backwards.
    void Report(int64_t us) {
        if (us <= reported_) return;
        reported_ = us;
        task_->processed_us.store(base_ + us, std::memory_order_relaxed);
    }

private:
    Task* const task_;
    const int64_t base_;
    int64_t reported_ = 0;
};

struct Output {
    AVFormatContext* ctx = nullptr;
    Task* task = nullptr;   // receives the bytes written
    int64_t counted = 0;    // bytes of this output already added to task->bytes_written

    Output() = default;
    Output(const Output&) = delete;
    Output& operator=(const Output&) = delete;
    ~Output() { Reset(); }
    void Reset();
    void CountBytes();  // adds what was written since the last call to task->bytes_written
};

// Opens the input and reads its stream info without selecting streams (probe.cpp). Sets task->duration_us.
// A file descriptor must be seekable; it is read from offset 0 and never closed here.
Status OpenFormat(const Source& source, Task* task, Input* input);

// OpenFormat, then selects at most one video stream plus every audio stream (M1 limits).
Status OpenInput(const Source& source, Task* task, Input* input);

// Reads the Source passed from Kotlin as (UTF-8 path bytes or null, fd or -1).
Source ToSource(JNIEnv* env, jbyteArray path, jint fd);

// Creates the output for the selected streams and writes its header.
Status OpenOutput(const Input& input, const std::string& path, Task* task, Output* output);

// The Status for work stopped by cancellation.
Status Cancelled();

// Writes the trailer and closes the file, reporting flush errors such as a full disk.
Status CloseOutput(Output* output);

// Shifts the packet by the anchor (the same amount for every stream), converts it to the output time
// base and writes it.
int WritePacket(const Input& input, Output* output, AVPacket* packet, int64_t anchor_pts, AVRational anchor_time_base);

inline int64_t ToMicros(int64_t ts, AVRational time_base) {
    return av_rescale_q(ts, time_base, AV_TIME_BASE_Q);
}

// Builds a NativeRemuxStatus for Kotlin.
jobject ToJava(JNIEnv* env, const Status& status, const std::vector<int64_t>& times_us);

}  // namespace remux
