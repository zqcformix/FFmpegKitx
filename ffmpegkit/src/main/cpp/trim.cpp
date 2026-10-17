// Fast (stream copy) time trimming. See docs/ANDROID_API.zh-CN.md §3 and §7 for the contract.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <climits>
#include <deque>
#include <memory>
#include <string>
#include <vector>

#include "jni_util.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
}

#define TAG "FFmpegKit"

namespace {

// Must match NativeTrimResult.REASON_* in Trimmer.kt.
constexpr int kReasonFromCode = 0;
constexpr int kReasonRangeOutsideMedia = 1;
constexpr int kReasonUnsupportedMedia = 2;

struct Outcome {
    int code = 0;              // AVERROR, 0 when the failure is not an FFmpeg error
    bool output_side = false;  // which side failed, for mapping the code
    int reason = kReasonFromCode;
    std::string detail;
    int64_t actual_start_us = 0;
    int64_t actual_end_us = 0;

    bool failed() const { return code != 0 || reason != kReasonFromCode; }
};

Outcome Failure(int code, bool output_side, std::string detail) {
    Outcome o;
    o.code = code;
    o.output_side = output_side;
    o.detail = std::move(detail);
    return o;
}

Outcome Rejected(int reason, bool output_side, std::string detail, int code = 0) {
    Outcome o = Failure(code, output_side, std::move(detail));
    o.reason = reason;
    return o;
}

struct InputContext {
    AVFormatContext* ctx = nullptr;
    ~InputContext() { avformat_close_input(&ctx); }
};

struct OutputContext {
    AVFormatContext* ctx = nullptr;
    ~OutputContext() {
        if (ctx == nullptr) return;
        if (!(ctx->oformat->flags & AVFMT_NOFILE)) avio_closep(&ctx->pb);
        avformat_free_context(ctx);
    }
};

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

int64_t ToMicros(int64_t ts, AVRational time_base) {
    return av_rescale_q(ts, time_base, AV_TIME_BASE_Q);
}

class Trimmer {
public:
    Trimmer(const std::string& input, const std::string& output, int64_t start_us, int64_t end_us)
        : input_path_(input), output_path_(output), start_us_(start_us), end_us_(end_us) {}

    Outcome Run() {
        Outcome o = OpenInput();
        if (o.failed()) return o;
        o = OpenOutput();
        if (o.failed()) return o;
        o = CopyPackets();
        if (o.failed()) return o;
        return Finish();
    }

private:
    Outcome OpenInput() {
        int ret = avformat_open_input(&in_.ctx, input_path_.c_str(), nullptr, nullptr);
        if (ret < 0) return Failure(ret, false, "open input");
        ret = avformat_find_stream_info(in_.ctx, nullptr);
        if (ret < 0) return Failure(ret, false, "read stream info");

        tracks_.resize(in_.ctx->nb_streams);
        int selected = 0;
        for (unsigned i = 0; i < in_.ctx->nb_streams; ++i) {
            const AVStream* st = in_.ctx->streams[i];
            const AVMediaType type = st->codecpar->codec_type;
            if (st->disposition & AV_DISPOSITION_ATTACHED_PIC) continue;  // cover art
            if (type == AVMEDIA_TYPE_VIDEO) {
                if (has_video_) {
                    return Rejected(kReasonUnsupportedMedia, false, "more than one video stream");
                }
                has_video_ = true;
                tracks_[i].video = true;
            } else if (type != AVMEDIA_TYPE_AUDIO) {
                continue;  // subtitles, data and attachments are not copied
            }
            tracks_[i].output_index = selected++;
        }
        if (selected == 0) return Rejected(kReasonUnsupportedMedia, false, "no audio or video stream");

        origin_ = in_.ctx->start_time == AV_NOPTS_VALUE ? 0 : in_.ctx->start_time;
        if (in_.ctx->duration != AV_NOPTS_VALUE && in_.ctx->duration > 0 &&
            start_us_ >= in_.ctx->duration) {
            return Rejected(kReasonRangeOutsideMedia, false, "start is at or beyond the end of the media");
        }
        start_abs_ = origin_ + start_us_;
        end_abs_ = end_us_ < 0 ? INT64_MAX : origin_ + end_us_;
        return {};
    }

    Outcome OpenOutput() {
        int ret = avformat_alloc_output_context2(&out_.ctx, nullptr, nullptr, output_path_.c_str());
        if (ret < 0 || out_.ctx == nullptr) {
            return Rejected(kReasonUnsupportedMedia, true, "no container matches the output file extension", ret);
        }
        for (unsigned i = 0; i < in_.ctx->nb_streams; ++i) {
            if (tracks_[i].output_index < 0) continue;
            const AVStream* ist = in_.ctx->streams[i];
            // 1: supported, 0: not supported, <0: unknown (header writing decides).
            if (avformat_query_codec(out_.ctx->oformat, ist->codecpar->codec_id, FF_COMPLIANCE_NORMAL) == 0) {
                return Rejected(kReasonUnsupportedMedia, true,
                                std::string(avcodec_get_name(ist->codecpar->codec_id)) +
                                    " cannot be stored in " + out_.ctx->oformat->name);
            }
            AVStream* ost = avformat_new_stream(out_.ctx, nullptr);
            if (ost == nullptr) return Failure(AVERROR(ENOMEM), true, "create output stream");
            // Also copies coded side data such as the display matrix (rotation).
            ret = avcodec_parameters_copy(ost->codecpar, ist->codecpar);
            if (ret < 0) return Failure(ret, true, "copy stream parameters");
            ost->codecpar->codec_tag = 0;  // let the output container choose its own tag
            ost->time_base = ist->time_base;
            ost->disposition = ist->disposition;
        }

        if (start_us_ > 0) {
            // Lands on the last keyframe at or before the start; reading from the beginning is also
            // correct (packets before the start are dropped), only slower.
            ret = avformat_seek_file(in_.ctx, -1, INT64_MIN, start_abs_, start_abs_, 0);
            if (ret < 0) __android_log_print(ANDROID_LOG_WARN, TAG, "seek failed (%d), reading from the start", ret);
        }

        if (!(out_.ctx->oformat->flags & AVFMT_NOFILE)) {
            ret = avio_open(&out_.ctx->pb, output_path_.c_str(), AVIO_FLAG_WRITE);
            if (ret < 0) return Failure(ret, true, "open output");
        }
        ret = avformat_write_header(out_.ctx, nullptr);
        if (ret == AVERROR(EINVAL) || ret == AVERROR(ENOSYS) || ret == AVERROR_PATCHWELCOME) {
            return Rejected(kReasonUnsupportedMedia, true,
                            std::string(out_.ctx->oformat->name) + " cannot store these streams", ret);
        }
        if (ret < 0) return Failure(ret, true, "write output header");
        return {};
    }

    Outcome CopyPackets() {
        Packet packet(av_packet_alloc());
        if (!packet) return Failure(AVERROR(ENOMEM), false, "allocate packet");
        while (!AllFinished()) {
            int ret = av_read_frame(in_.ctx, packet.get());
            if (ret == AVERROR_EOF) break;
            if (ret < 0) return Failure(ret, false, "read input");
            if (tracks_[packet->stream_index].output_index < 0) {
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
            if (has_video_ && !has_candidate_) {
                return Rejected(kReasonUnsupportedMedia, false, "no video keyframe in the input");
            }
            if (!has_candidate_ || pending_end_abs_ <= start_abs_) {
                return Rejected(kReasonRangeOutsideMedia, false, "start is at or beyond the end of the media");
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
        const Track& track = tracks_[packet->stream_index];
        if (packet->pts != AV_NOPTS_VALUE) {
            pending_end_abs_ = std::max(pending_end_abs_, ToMicros(packet->pts + packet->duration, st->time_base));
        }

        if (!has_video_) {
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
        Track& track = tracks_[packet->stream_index];
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
        // Every stream moves by the same amount, so audio and video keep their relative timing.
        const int64_t offset = av_rescale_q(anchor_pts_, anchor_time_base_, ist->time_base);
        if (packet->pts != AV_NOPTS_VALUE) packet->pts -= offset;
        if (packet->dts != AV_NOPTS_VALUE) packet->dts -= offset;
        const AVStream* ost = out_.ctx->streams[track.output_index];
        av_packet_rescale_ts(packet, ist->time_base, ost->time_base);
        packet->stream_index = track.output_index;
        packet->pos = -1;
        ++track.written;
        return av_interleaved_write_frame(out_.ctx, packet);
    }

    bool AllFinished() const {
        for (const Track& track : tracks_) {
            if (track.output_index >= 0 && !track.finished) return false;
        }
        return true;
    }

    Outcome Finish() {
        int64_t total = 0;
        for (unsigned i = 0; i < tracks_.size(); ++i) {
            if (tracks_[i].output_index < 0) continue;
            total += tracks_[i].written;
        }
        if (total == 0) {
            return Rejected(kReasonRangeOutsideMedia, false, "no packets in the requested range");
        }
        for (unsigned i = 0; i < tracks_.size(); ++i) {
            if (tracks_[i].output_index >= 0 && tracks_[i].written == 0) {
                return Rejected(kReasonUnsupportedMedia, false,
                                "stream " + std::to_string(i) + " has no packets in the requested range");
            }
        }
        int ret = av_write_trailer(out_.ctx);
        if (ret < 0) return Failure(ret, true, "write output trailer");
        if (!(out_.ctx->oformat->flags & AVFMT_NOFILE)) {
            ret = avio_closep(&out_.ctx->pb);  // reports flush errors such as a full disk
            if (ret < 0) return Failure(ret, true, "close output");
        }
        Outcome o;
        o.actual_start_us = std::max<int64_t>(0, first_abs_ - origin_);  // e.g. AAC priming before the start
        o.actual_end_us = std::max(last_end_abs_ - origin_, o.actual_start_us + 1);
        return o;
    }

    const std::string input_path_;
    const std::string output_path_;
    const int64_t start_us_;
    const int64_t end_us_;

    InputContext in_;
    OutputContext out_;
    std::vector<Track> tracks_;
    bool has_video_ = false;
    int64_t origin_ = 0;
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

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeTrim_trim(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray input,
        jbyteArray output,
        jlong start_us,
        jlong end_us) {
    Trimmer trimmer(ToUtf8Path(env, input), ToUtf8Path(env, output), start_us, end_us);
    const Outcome o = trimmer.Run();

    jclass result_class = env->FindClass("io/github/nova/ffmpegkit/NativeTrimResult");
    if (result_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(result_class, "<init>", "(IZILjava/lang/String;JJ)V");
    if (constructor == nullptr) return nullptr;
    jstring detail = o.detail.empty() ? nullptr : env->NewStringUTF(o.detail.c_str());
    return env->NewObject(result_class, constructor, o.code, o.output_side, o.reason, detail,
                          static_cast<jlong>(o.actual_start_us), static_cast<jlong>(o.actual_end_us));
}
