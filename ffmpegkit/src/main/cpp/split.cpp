// Keyframe-aligned single-pass split. See docs/ANDROID_API.zh-CN.md §4 for the contract.
#include <jni.h>
#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdio>
#include <string>
#include <vector>

#include "jni_util.h"
#include "remux.h"

namespace {

using remux::Failure;
using remux::Packet;
using remux::Rejected;
using remux::Status;
using remux::ToMicros;

// Every copied packet goes to exactly one segment, in read order, so the segments concatenated
// give back the input's packets. A new segment starts at a reference-stream packet (a keyframe when
// there is video) whose pts is at least `segment_us` after the current segment's start; segments
// are therefore never shorter than that, except the last.
class Splitter {
public:
    Splitter(remux::Source input, std::string directory, std::string extension, int64_t segment_us, int max_segments,
             remux::Task* task)
        : input_(std::move(input)),
          directory_(std::move(directory)),
          extension_(std::move(extension)),
          segment_us_(segment_us),
          max_segments_(max_segments),
          task_(task),
          progress_(task) {}

    Status Run() {
        Status s = remux::OpenInput(input_, task_, &in_);
        if (s.failed()) return s;
        if (in_.ctx->duration != AV_NOPTS_VALUE && in_.ctx->duration > 0 &&
            in_.ctx->duration / segment_us_ + 1 > max_segments_) {
            return Rejected(remux::kReasonTooManySegments, false, TooManySegments());
        }

        Packet packet(av_packet_alloc());
        if (!packet) return Failure(AVERROR(ENOMEM), false, "allocate packet");
        int ret = 0;
        while (!task_->IsCancelled() && (ret = av_read_frame(in_.ctx, packet.get())) >= 0) {
            if (in_.tracks[packet->stream_index].output_index >= 0) {
                s = Write(packet.get());
                if (s.failed()) return s;
            }
            av_packet_unref(packet.get());
        }
        if (task_->IsCancelled() || ret == AVERROR_EXIT) return remux::Cancelled();
        if (ret != AVERROR_EOF) return Failure(ret, false, "read input");
        if (starts_abs_.empty()) return Rejected(remux::kReasonRangeOutsideMedia, false, "the input has no packets");
        return remux::CloseOutput(&out_);
    }

    // [start, end) of each segment in microseconds from the input's start time.
    std::vector<int64_t> Times() const {
        std::vector<int64_t> times;
        for (size_t i = 0; i < starts_abs_.size(); ++i) {
            const int64_t start = std::max<int64_t>(0, starts_abs_[i] - in_.origin);
            const int64_t end = i + 1 < starts_abs_.size() ? starts_abs_[i + 1] - in_.origin : last_end_abs_ - in_.origin;
            times.push_back(start);
            times.push_back(std::max(end, start + 1));
        }
        return times;
    }

private:
    Status Write(AVPacket* packet) {
        const AVStream* st = in_.ctx->streams[packet->stream_index];
        if (starts_abs_.empty()) {
            // The first segment keeps everything from the beginning, including leading pictures and
            // AAC priming, and starts at the input's start time.
            Status s = StartSegment(in_.origin, AV_TIME_BASE_Q, in_.origin);
            if (s.failed()) return s;
        } else if (IsCut(packet)) {
            Status s = remux::CloseOutput(&out_);
            if (s.failed()) return s;
            s = StartSegment(packet->pts, st->time_base, ToMicros(packet->pts, st->time_base));
            if (s.failed()) return s;
        }
        if (packet->pts != AV_NOPTS_VALUE) {
            last_end_abs_ = std::max(last_end_abs_, ToMicros(packet->pts + packet->duration, st->time_base));
        }
        const int ret = remux::WritePacket(in_, &out_, packet, anchor_pts_, anchor_time_base_);
        if (ret >= 0 && last_end_abs_ > in_.origin) progress_.Report(last_end_abs_ - in_.origin);
        return ret < 0 ? Failure(ret, true, "write segment " + std::to_string(starts_abs_.size())) : Status{};
    }

    bool IsCut(const AVPacket* packet) const {
        if (packet->stream_index != in_.reference_stream || packet->pts == AV_NOPTS_VALUE) return false;
        if (in_.has_video && !(packet->flags & AV_PKT_FLAG_KEY)) return false;
        const AVStream* st = in_.ctx->streams[packet->stream_index];
        return ToMicros(packet->pts, st->time_base) >= starts_abs_.back() + segment_us_;
    }

    Status StartSegment(int64_t anchor_pts, AVRational anchor_time_base, int64_t start_abs) {
        const int index = static_cast<int>(starts_abs_.size()) + 1;
        if (index > max_segments_) return Rejected(remux::kReasonTooManySegments, false, TooManySegments());
        Status s = remux::OpenOutput(in_, SegmentPath(index), task_, &out_);
        if (s.failed()) return s;
        anchor_pts_ = anchor_pts;
        anchor_time_base_ = anchor_time_base;
        starts_abs_.push_back(start_abs);
        task_->segment.store(index, std::memory_order_relaxed);
        return {};
    }

    std::string SegmentPath(int index) const {
        char number[16];
        snprintf(number, sizeof(number), "%04d", index);
        return directory_ + "/" + number + "." + extension_;
    }

    std::string TooManySegments() const {
        return "more than " + std::to_string(max_segments_) + " segments of " + std::to_string(segment_us_) + " us";
    }

    const remux::Source input_;
    const std::string directory_;
    const std::string extension_;
    const int64_t segment_us_;
    const int max_segments_;
    remux::Task* const task_;
    remux::Progress progress_;  // target timeline: the whole input

    remux::Input in_;
    remux::Output out_;
    int64_t anchor_pts_ = 0;
    AVRational anchor_time_base_ = AV_TIME_BASE_Q;
    std::vector<int64_t> starts_abs_;  // one per segment written so far
    int64_t last_end_abs_ = INT64_MIN;
};

}  // namespace

// Reads from `input_path` (UTF-8 bytes) or, when `input_fd` >= 0, from that descriptor, and writes
// segments 0001.<extension>, 0002.<extension>… into `directory`. Returns a NativeRemuxStatus whose
// times are [start, end) pairs, one per segment.
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeSplit_split(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray input_path,
        jint input_fd,
        jbyteArray directory,
        jbyteArray extension,
        jlong segment_us,
        jint max_segments,
        jlong task) {
    remux::LogScope logs(remux::LogOf(task));
    remux::Task scratch;
    Splitter splitter(remux::ToSource(env, input_path, input_fd), ToUtf8Path(env, directory), ToUtf8Path(env, extension),
                      segment_us, max_segments, remux::FromHandle(task, &scratch));
    const Status status = splitter.Run();
    return remux::ToJava(env, status, status.failed() ? std::vector<int64_t>{} : splitter.Times());
}
