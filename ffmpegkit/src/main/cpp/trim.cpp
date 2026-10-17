// Fast (stream copy) time trimming. See docs/ANDROID_API.zh-CN.md §3 and §7 for the contract.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <climits>
#include <deque>
#include <string>
#include <vector>

#include "jni_util.h"
#include "remux.h"

#define TAG "FFmpegKit"

namespace {

using remux::Failure;
using remux::Packet;
using remux::Rejected;
using remux::Status;
using remux::ToMicros;

class Trimmer {
public:
    Trimmer(std::string input, std::string output, int64_t start_us, int64_t end_us)
        : input_path_(std::move(input)), output_path_(std::move(output)), start_us_(start_us), end_us_(end_us) {}

    Status Run() {
        Status s = remux::OpenInput(input_path_, &in_);
        if (s.failed()) return s;
        if (in_.ctx->duration != AV_NOPTS_VALUE && in_.ctx->duration > 0 && start_us_ >= in_.ctx->duration) {
            return Rejected(remux::kReasonRangeOutsideMedia, false, "start is at or beyond the end of the media");
        }
        start_abs_ = in_.origin + start_us_;
        end_abs_ = end_us_ < 0 ? INT64_MAX : in_.origin + end_us_;

        s = remux::OpenOutput(in_, output_path_, &out_);
        if (s.failed()) return s;
        if (start_us_ > 0) {
            // Lands on the last keyframe at or before the start; reading from the beginning is also
            // correct (packets before the start are dropped), only slower.
            int ret = avformat_seek_file(in_.ctx, -1, INT64_MIN, start_abs_, start_abs_, 0);
            if (ret < 0) __android_log_print(ANDROID_LOG_WARN, TAG, "seek failed (%d), reading from the start", ret);
        }
        s = CopyPackets();
        if (s.failed()) return s;
        return Finish();
    }

    int64_t actual_start_us() const { return std::max<int64_t>(0, first_abs_ - in_.origin); }  // e.g. AAC priming
    int64_t actual_end_us() const { return std::max(last_end_abs_ - in_.origin, actual_start_us() + 1); }

private:
    Status CopyPackets() {
        Packet packet(av_packet_alloc());
        if (!packet) return Failure(AVERROR(ENOMEM), false, "allocate packet");
        while (!AllFinished()) {
            int ret = av_read_frame(in_.ctx, packet.get());
            if (ret == AVERROR_EOF) break;
            if (ret < 0) return Failure(ret, false, "read input");
            if (in_.tracks[packet->stream_index].output_index < 0) {
                av_packet_unref(packet.get());
                continue;
            }
            ret = anchored_ ? Emit(packet.get()) : FindAnchor(std::move(packet));
            if (!packet) packet.reset(av_packet_alloc());
            if (!packet) return Failure(AVERROR(ENOMEM), false, "allocate packet");
            av_packet_unref(packet.get());
            if (ret < 0) return Failure(ret, true, "write output");
        }
        if (!anchored_) {
            // End of input while looking for the start.
            if (in_.has_video && !has_candidate_) {
                return Rejected(remux::kReasonUnsupportedMedia, false, "no video keyframe in the input");
            }
            if (!has_candidate_ || pending_end_abs_ <= start_abs_) {
                return Rejected(remux::kReasonRangeOutsideMedia, false, "start is at or beyond the end of the media");
            }
            int ret = Flush();
            if (ret < 0) return Failure(ret, true, "write output");
        }
        return {};
    }

    // Video: keeps the packets from the last keyframe at or before the start (one GOP at most)
    // until decoding order reaches the start, then fixes that keyframe as the anchor.
    // Audio only: the anchor is the first packet that ends after the start.
    int FindAnchor(Packet packet) {
        const AVStream* st = in_.ctx->streams[packet->stream_index];
        const remux::Track& track = in_.tracks[packet->stream_index];
        if (packet->pts != AV_NOPTS_VALUE) {
            pending_end_abs_ = std::max(pending_end_abs_, ToMicros(packet->pts + packet->duration, st->time_base));
        }

        if (!in_.has_video) {
            if (packet->pts == AV_NOPTS_VALUE ||
                ToMicros(packet->pts + packet->duration, st->time_base) <= start_abs_) {
                return 0;
            }
            SetAnchor(packet->pts, st->time_base);
            anchored_ = true;
            return Emit(packet.get());
        }

        if (!track.video) {
            // Audio stored before the keyframe in the file may still play after it.
            pending_.push_back(std::move(packet));
            return 0;
        }
        const bool keyframe = (packet->flags & AV_PKT_FLAG_KEY) && packet->pts != AV_NOPTS_VALUE;
        const int64_t pts_abs = packet->pts == AV_NOPTS_VALUE ? INT64_MIN : ToMicros(packet->pts, st->time_base);
        if (keyframe && pts_abs <= start_abs_) {
            // A later keyframe still at or before the start replaces the earlier GOP.
            has_candidate_ = true;
            SetAnchor(packet->pts, st->time_base);
            DropPendingBeforeAnchor();
            pending_.push_back(std::move(packet));
            return 0;
        }
        if (!has_candidate_) {
            if (!keyframe) return 0;  // cannot decode without the preceding keyframe
            SetAnchor(packet->pts, st->time_base);  // the first keyframe is already past the start
            has_candidate_ = true;
            pending_.push_back(std::move(packet));
            return Flush();
        }
        const int64_t decode_abs = ToMicros(packet->dts != AV_NOPTS_VALUE ? packet->dts : packet->pts, st->time_base);
        pending_.push_back(std::move(packet));
        if ((keyframe && pts_abs > start_abs_) || decode_abs >= start_abs_) return Flush();
        return 0;
    }

    void SetAnchor(int64_t pts, AVRational time_base) {
        anchor_pts_ = pts;
        anchor_time_base_ = time_base;
    }

    // Keeps memory bounded to about one GOP while reading towards the start.
    void DropPendingBeforeAnchor() {
        pending_.erase(
            std::remove_if(pending_.begin(), pending_.end(), [this](const Packet& packet) {
                const AVStream* st = in_.ctx->streams[packet->stream_index];
                return packet->pts == AV_NOPTS_VALUE ||
                       av_compare_ts(packet->pts, st->time_base, anchor_pts_, anchor_time_base_) < 0;
            }),
            pending_.end());
    }

    int Flush() {
        anchored_ = true;
        while (!pending_.empty()) {
            Packet packet = std::move(pending_.front());
            pending_.pop_front();
            int ret = Emit(packet.get());
            if (ret < 0) return ret;
        }
        return 0;
    }

    int Emit(AVPacket* packet) {
        remux::Track& track = in_.tracks[packet->stream_index];
        if (track.finished) return 0;
        const AVStream* ist = in_.ctx->streams[packet->stream_index];
        const int64_t decode_ts = packet->dts != AV_NOPTS_VALUE ? packet->dts : packet->pts;
        if (decode_ts != AV_NOPTS_VALUE && ToMicros(decode_ts, ist->time_base) >= end_abs_) {
            track.finished = true;
            return 0;
        }
        // Drops audio before the video keyframe and leading pictures that need an earlier GOP.
        if (packet->pts != AV_NOPTS_VALUE &&
            av_compare_ts(packet->pts, ist->time_base, anchor_pts_, anchor_time_base_) < 0) {
            return 0;
        }
        if (packet->pts != AV_NOPTS_VALUE) {
            first_abs_ = std::min(first_abs_, ToMicros(packet->pts, ist->time_base));
            last_end_abs_ = std::max(last_end_abs_, ToMicros(packet->pts + packet->duration, ist->time_base));
        }
        ++track.written;
        // Every stream moves by the same amount, so audio and video keep their relative timing.
        return remux::WritePacket(in_, &out_, packet, anchor_pts_, anchor_time_base_);
    }

    bool AllFinished() const {
        for (const remux::Track& track : in_.tracks) {
            if (track.output_index >= 0 && !track.finished) return false;
        }
        return true;
    }

    Status Finish() {
        int64_t total = 0;
        for (const remux::Track& track : in_.tracks) {
            if (track.output_index >= 0) total += track.written;
        }
        if (total == 0) {
            return Rejected(remux::kReasonRangeOutsideMedia, false, "no packets in the requested range");
        }
        for (unsigned i = 0; i < in_.tracks.size(); ++i) {
            if (in_.tracks[i].output_index >= 0 && in_.tracks[i].written == 0) {
                return Rejected(remux::kReasonUnsupportedMedia, false,
                                "stream " + std::to_string(i) + " has no packets in the requested range");
            }
        }
        return remux::CloseOutput(&out_);
    }

    const std::string input_path_;
    const std::string output_path_;
    const int64_t start_us_;
    const int64_t end_us_;

    remux::Input in_;
    remux::Output out_;
    int64_t start_abs_ = 0;
    int64_t end_abs_ = INT64_MAX;

    bool anchored_ = false;
    bool has_candidate_ = false;
    int64_t anchor_pts_ = 0;
    AVRational anchor_time_base_ = AV_TIME_BASE_Q;
    std::deque<Packet> pending_;
    int64_t pending_end_abs_ = INT64_MIN;

    int64_t first_abs_ = INT64_MAX;
    int64_t last_end_abs_ = INT64_MIN;
};

}  // namespace

// Returns a NativeRemuxStatus whose times are [actual start, actual end) in microseconds.
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeTrim_trim(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray input,
        jbyteArray output,
        jlong start_us,
        jlong end_us) {
    Trimmer trimmer(ToUtf8Path(env, input), ToUtf8Path(env, output), start_us, end_us);
    const Status status = trimmer.Run();
    std::vector<int64_t> times;
    if (!status.failed()) times = {trimmer.actual_start_us(), trimmer.actual_end_us()};
    return remux::ToJava(env, status, times);
}
