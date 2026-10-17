// Decodes a whole file and summarises each stream. Used to verify outputs; a starting point for probe.
#include <jni.h>
#include <algorithm>
#include <climits>
#include <string>
#include <vector>

#include "jni_util.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
}

// Result layout, parsed by MediaInspector.kt:
//   status, start_time_us, duration_us (-1 if unknown),
//   then per stream: type, packets, keyframes, first_pts_us, end_us, frames, decode_errors

namespace {

struct Stream {
    AVCodecContext* decoder = nullptr;
    int64_t type = -1;
    int64_t packets = 0;
    int64_t keyframes = 0;
    int64_t first_pts_us = INT64_MAX;
    int64_t end_us = INT64_MIN;
    int64_t frames = 0;
    int64_t errors = 0;
};

void Drain(Stream& s, AVFrame* frame) {
    while (true) {
        const int ret = avcodec_receive_frame(s.decoder, frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return;
        if (ret < 0) {
            ++s.errors;
            return;
        }
        ++s.frames;
        if (frame->decode_error_flags != 0 || (frame->flags & AV_FRAME_FLAG_CORRUPT)) ++s.errors;
        av_frame_unref(frame);
    }
}

void Decode(Stream& s, const AVPacket* packet, AVFrame* frame) {
    if (s.decoder == nullptr) return;
    if (avcodec_send_packet(s.decoder, packet) < 0) ++s.errors;
    Drain(s, frame);
}

jlongArray ToArray(JNIEnv* env, const std::vector<jlong>& values) {
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr) env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_nova_ffmpegkit_NativeInspector_inspect(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray path) {
    const std::string file = ToUtf8Path(env, path);
    AVFormatContext* ctx = nullptr;
    int ret = avformat_open_input(&ctx, file.c_str(), nullptr, nullptr);
    if (ret >= 0) ret = avformat_find_stream_info(ctx, nullptr);
    if (ret < 0) {
        avformat_close_input(&ctx);
        return ToArray(env, {ret, 0, 0});
    }

    std::vector<Stream> streams(ctx->nb_streams);
    for (unsigned i = 0; i < ctx->nb_streams; ++i) {
        const AVCodecParameters* par = ctx->streams[i]->codecpar;
        streams[i].type = par->codec_type;
        const AVCodec* codec = avcodec_find_decoder(par->codec_id);
        if (codec == nullptr) continue;
        AVCodecContext* decoder = avcodec_alloc_context3(codec);
        if (decoder == nullptr) continue;
        decoder->err_recognition |= AV_EF_EXPLODE;  // report damage instead of concealing it
        if (avcodec_parameters_to_context(decoder, par) < 0 || avcodec_open2(decoder, codec, nullptr) < 0) {
            avcodec_free_context(&decoder);
            continue;
        }
        streams[i].decoder = decoder;
    }

    AVPacket* packet = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    int64_t status = 0;
    while ((ret = av_read_frame(ctx, packet)) >= 0) {
        Stream& s = streams[packet->stream_index];
        const AVRational tb = ctx->streams[packet->stream_index]->time_base;
        ++s.packets;
        if (packet->flags & AV_PKT_FLAG_KEY) ++s.keyframes;
        if (packet->pts != AV_NOPTS_VALUE) {
            s.first_pts_us = std::min(s.first_pts_us, av_rescale_q(packet->pts, tb, AV_TIME_BASE_Q));
            s.end_us = std::max(s.end_us, av_rescale_q(packet->pts + packet->duration, tb, AV_TIME_BASE_Q));
        }
        Decode(s, packet, frame);
        av_packet_unref(packet);
    }
    if (ret != AVERROR_EOF) status = ret;
    for (Stream& s : streams) Decode(s, nullptr, frame);  // flush delayed frames

    std::vector<jlong> values = {
            status,
            ctx->start_time == AV_NOPTS_VALUE ? 0 : ctx->start_time,
            ctx->duration == AV_NOPTS_VALUE ? -1 : ctx->duration,
    };
    for (Stream& s : streams) {
        values.insert(values.end(), {s.type, s.packets, s.keyframes, s.first_pts_us, s.end_us, s.frames, s.errors});
        avcodec_free_context(&s.decoder);
    }
    av_frame_free(&frame);
    av_packet_free(&packet);
    avformat_close_input(&ctx);
    return ToArray(env, values);
}

// Every packet in read order: status, then per packet stream_index, size, keyframe (0/1), FNV-1a hash
// of the data. Used to check that split segments hold exactly the input's packets.
extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_nova_ffmpegkit_NativeInspector_packets(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray path) {
    const std::string file = ToUtf8Path(env, path);
    AVFormatContext* ctx = nullptr;
    int ret = avformat_open_input(&ctx, file.c_str(), nullptr, nullptr);
    if (ret < 0) return ToArray(env, {ret});
    std::vector<jlong> values = {0};
    AVPacket* packet = av_packet_alloc();
    while ((ret = av_read_frame(ctx, packet)) >= 0) {
        uint64_t hash = 1469598103934665603ULL;
        for (int i = 0; i < packet->size; ++i) {
            hash = (hash ^ packet->data[i]) * 1099511628211ULL;
        }
        values.insert(values.end(), {packet->stream_index, packet->size, (packet->flags & AV_PKT_FLAG_KEY) ? 1 : 0,
                                     static_cast<jlong>(hash)});
        av_packet_unref(packet);
    }
    if (ret != AVERROR_EOF) values[0] = ret;
    av_packet_free(&packet);
    avformat_close_input(&ctx);
    return ToArray(env, values);
}
