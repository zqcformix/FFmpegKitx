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
    Trimmer(remux::Source input, std::string output, int64_t start_us, int64_t end_us, bool edit_list,
            remux::Task* task)
        : input_(std::move(input)),
          output_path_(std::move(output)),
          start_us_(start_us),
          end_us_(end_us),
          edit_list_(edit_list),
          task_(task),
          progress_(task) {}

    Status Run() {
        Status s = remux::OpenInput(input_, task_, &in_);
        if (s.failed()) return s;
        if (in_.ctx->duration != AV_NOPTS_VALUE && in_.ctx->duration > 0 && start_us_ >= in_.ctx->duration) {
            return Rejected(remux::kReasonRangeOutsideMedia, false, "start is at or beyond the end of the media");
        }
        start_abs_ = in_.origin + start_us_;
        end_abs_ = end_us_ < 0 ? INT64_MAX : in_.origin + end_us_;

        if (edit_list_ && !IsMovFamily(output_path_)) {
            return Rejected(remux::kReasonUnsupportedMedia, true, "edit list mode needs an MP4 or MOV output");
        }
        s = remux::OpenOutput(in_, output_path_, task_, &out_);
        if (s.failed()) return s;
        if (start_us_ > 0) {
            // With an index (MP4, Matroska) this lands on the last keyframe at or before the start.
            // Reading from the beginning is also correct (packets before the start are dropped), only slower.
            int ret = avformat_seek_file(in_.ctx, -1, INT64_MIN, start_abs_, start_abs_, 0);
            if (ret < 0) {
                __android_log_print(ANDROID_LOG_WARN, TAG, "seek failed (%d), reading from the start", ret);
            } else {
                from_beginning_ = false;
            }
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
            if (task_->IsCancelled()) return remux::Cancelled();
            int ret = av_read_frame(in_.ctx, packet.get());
            if (ret == AVERROR_EOF) {
                // A seek can also land after the last keyframe (or audio packet) the start needs.
                const bool found_start = in_.has_video ? has_candidate_ : seen_before_start_;
                if (!anchored_ && !found_start && !from_beginning_) {
                    Status retry = SeekEarlier();
                    if (retry.failed()) return retry;
                    continue;
                }
                break;
            }
            if (ret == AVERROR_EXIT) return remux::Cancelled();
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
            if (overshot_) {
                Status retry = SeekEarlier();
                if (retry.failed()) return retry;
            }
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
                seen_before_start_ = true;
                return 0;
            }
            if (!seen_before_start_ && ToMicros(packet->pts, st->time_base) > start_abs_ && !from_beginning_) {
                overshot_ = true;  // the packet holding the start was skipped
                return 0;
            }
            SetAnchor(packet->pts, st->time_base);
            anchored_ = true;
            FixShift();
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
            if (!from_beginning_) {
                // The seek landed after the keyframe the start needs (MPEG-TS and other inputs without
                // an index seek to a byte position, not to a keyframe).
                overshot_ = true;
                return 0;
            }
            SetAnchor(packet->pts, st->time_base);  // the input's first keyframe is already past the start
            has_candidate_ = true;
            pending_.push_back(std::move(packet));
            return Flush();
        }
        const int64_t decode_abs = ToMicros(packet->dts != AV_NOPTS_VALUE ? packet->dts : packet->pts, st->time_base);
        pending_.push_back(std::move(packet));
        if ((keyframe && pts_abs > start_abs_) || decode_abs >= start_abs_) return Flush();
        return 0;
    }

    // After an overshooting seek: seeks again 1, 2, 4… s before the start, and finally to the beginning
    // of the input, where a first keyframe after the start is genuine. Forgets what was read so far.
    Status SeekEarlier() {
        overshot_ = false;
        pending_.clear();
        has_candidate_ = false;
        seen_before_start_ = false;
        pending_end_abs_ = INT64_MIN;
        backoff_us_ = backoff_us_ == 0 ? AV_TIME_BASE : backoff_us_ * 2;
        const int64_t target = start_abs_ - backoff_us_;
        if (target > in_.origin && avformat_seek_file(in_.ctx, -1, INT64_MIN, target, target, 0) >= 0) return {};
        from_beginning_ = true;
        // A byte seek reaches the start of index-less inputs; formats that refuse it seek by time.
        int ret = av_seek_frame(in_.ctx, -1, 0, AVSEEK_FLAG_BYTE);
        if (ret < 0) ret = avformat_seek_file(in_.ctx, -1, INT64_MIN, INT64_MIN, in_.origin, 0);
        if (ret == AVERROR_EXIT || task_->IsCancelled()) return remux::Cancelled();
        return ret < 0 ? Failure(ret, false, "seek back to the start of the input") : Status{};
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
        FixShift();
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
        const int ret = remux::WritePacket(in_, &out_, packet, shift_pts_, shift_time_base_);
        if (ret >= 0 && last_end_abs_ > start_abs_) progress_.Report(std::min(last_end_abs_, end_abs_) - start_abs_);
        return ret;
    }

    // What becomes time 0 in the output: the anchor keyframe, or in edit list mode the requested start.
    // There the packets from the keyframe up to the start get negative timestamps; the MP4 muxer then
    // writes an edit list so that players which honour it decode them but start showing at the start.
    void FixShift() {
        shift_pts_ = anchor_pts_;
        shift_time_base_ = anchor_time_base_;
        if (edit_list_ && av_compare_ts(start_abs_, AV_TIME_BASE_Q, anchor_pts_, anchor_time_base_) > 0) {
            shift_pts_ = start_abs_;
            shift_time_base_ = AV_TIME_BASE_Q;
        }
    }

    // The containers whose muxer (movenc) writes edit lists for negative start timestamps.
    static bool IsMovFamily(const std::string& path) {
        const AVOutputFormat* format = av_guess_format(nullptr, path.c_str(), nullptr);
        if (format == nullptr) return false;
        const std::string name = format->name;
        return name == "mp4" || name == "mov" || name == "ipod" || name == "3gp" || name == "3g2";
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

    const remux::Source input_;
    const std::string output_path_;
    const int64_t start_us_;
    const int64_t end_us_;
    const bool edit_list_;
    remux::Task* const task_;
    remux::Progress progress_;  // target timeline: from the requested start, at most to the requested end

    remux::Input in_;
    remux::Output out_;
    int64_t start_abs_ = 0;
    int64_t end_abs_ = INT64_MAX;

    bool anchored_ = false;
    bool has_candidate_ = false;
    bool from_beginning_ = true;      // false while reading from a seek position that may have overshot
    bool overshot_ = false;           // set by FindAnchor, handled by CopyPackets
    bool seen_before_start_ = false;  // audio only: a packet ending at or before the start was read
    int64_t backoff_us_ = 0;
    int64_t anchor_pts_ = 0;
    AVRational anchor_time_base_ = AV_TIME_BASE_Q;
    int64_t shift_pts_ = 0;
    AVRational shift_time_base_ = AV_TIME_BASE_Q;
    std::deque<Packet> pending_;
    int64_t pending_end_abs_ = INT64_MIN;

    int64_t first_abs_ = INT64_MAX;
    int64_t last_end_abs_ = INT64_MIN;
};

}  // namespace

// Reads from `input_path` (UTF-8 bytes) or, when `input_fd` >= 0, from that descriptor; stops with
// AVERROR_EXIT when `task` (a NativeTask handle, or 0) is cancelled, and reports progress to it.
// Returns a NativeRemuxStatus whose times are [actual start, actual end) in microseconds.
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeTrim_trim(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray input_path,
        jint input_fd,
        jbyteArray output,
        jlong start_us,
        jlong end_us,
        jboolean edit_list,
        jlong task) {
    remux::LogScope logs(remux::LogOf(task));
    remux::Task scratch;
    Trimmer trimmer(remux::ToSource(env, input_path, input_fd), ToUtf8Path(env, output), start_us, end_us,
                    edit_list == JNI_TRUE, remux::FromHandle(task, &scratch));
    const Status status = trimmer.Run();
    std::vector<int64_t> times;
    if (!status.failed()) times = {trimmer.actual_start_us(), trimmer.actual_end_us()};
    return remux::ToJava(env, status, times);
}
