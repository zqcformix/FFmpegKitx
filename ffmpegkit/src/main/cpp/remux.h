// Shared stream-copy plumbing for trim.cpp and split.cpp.
#pragma once

#include <jni.h>
#include <memory>
#include <string>
#include <vector>

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

struct Output {
    AVFormatContext* ctx = nullptr;

    Output() = default;
    Output(const Output&) = delete;
    Output& operator=(const Output&) = delete;
    ~Output() { Reset(); }
    void Reset();
};

// Opens the input and selects at most one video stream plus every audio stream (M1 limits).
// A file descriptor must be seekable; it is read from offset 0 and never closed here.
Status OpenInput(const Source& source, Input* input);

// Reads the Source passed from Kotlin as (UTF-8 path bytes or null, fd or -1).
Source ToSource(JNIEnv* env, jbyteArray path, jint fd);

// Creates the output for the selected streams and writes its header.
Status OpenOutput(const Input& input, const std::string& path, Output* output);

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
