// The media operations behind the C ABI (ffmpegkitx.cpp), which validates the caller's arguments first.
// Each runs on the calling thread and reports to `task`, which is never null.
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "remux.h"

namespace remux {

struct TrimRequest {
    Source input;
    std::string output;
    int64_t start_us = 0;
    int64_t end_us = FFKX_TIME_END;  // exclusive; anything beyond the media trims to its end
    bool edit_list = false;
};

// Fast trim (trim.cpp). On success, sets the [actual start, end) written.
Status Trim(const TrimRequest& request, Task* task, int64_t* actual_start_us, int64_t* actual_end_us);

struct SplitRequest {
    Source input;
    std::string directory;
    std::string prefix;
    std::string extension;
    int64_t segment_us = 0;
    int max_segments = FFKX_MAX_SEGMENTS;
};

struct Segment {
    int64_t start_us;
    int64_t end_us;
};

// Keyframe-aligned single-pass split (split.cpp). On success, sets one [start, end) per segment.
Status Split(const SplitRequest& request, Task* task, std::vector<Segment>* segments);

// Unknown numbers are FFKX_UNKNOWN; see ffkx_stream_info.
struct StreamDescription {
    int32_t media_type = FFKX_MEDIA_TYPE_UNKNOWN;
    uint32_t flags = 0;
    int64_t duration_us = FFKX_UNKNOWN;
    int64_t bit_rate = FFKX_UNKNOWN;
    int64_t width = FFKX_UNKNOWN;
    int64_t height = FFKX_UNKNOWN;
    int64_t frame_rate_num = FFKX_UNKNOWN;
    int64_t frame_rate_den = FFKX_UNKNOWN;
    int64_t rotation = FFKX_UNKNOWN;
    int64_t sample_rate = FFKX_UNKNOWN;
    int64_t channels = FFKX_UNKNOWN;
    std::string codec_name;
    std::optional<std::string> channel_layout;
};

struct MediaDescription {
    std::string format_name;
    int64_t start_time_us = FFKX_UNKNOWN;
    int64_t duration_us = FFKX_UNKNOWN;
    int64_t bit_rate = FFKX_UNKNOWN;
    std::vector<StreamDescription> streams;
};

// Structured probe (probe.cpp).
Status Probe(const Source& source, Task* task, MediaDescription* description);

}  // namespace remux
