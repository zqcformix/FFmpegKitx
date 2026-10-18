// Structured probe: container and per-stream properties after avformat_find_stream_info, without decoding.
#include <cmath>
#include <optional>
#include <string>

#include "operations.h"

extern "C" {
#include "libavutil/channel_layout.h"
#include "libavutil/display.h"
}

namespace {

constexpr int64_t kUnknown = FFKX_UNKNOWN;

int64_t Micros(int64_t ts, AVRational time_base) {
    return ts == AV_NOPTS_VALUE ? kUnknown : remux::ToMicros(ts, time_base);
}

// FFmpeg stores "not known" as 0 in sizes, rates and counts.
int64_t Positive(int64_t value) {
    return value > 0 ? value : kUnknown;
}

int32_t MediaType(AVMediaType type) {
    switch (type) {
        case AVMEDIA_TYPE_VIDEO:
            return FFKX_MEDIA_TYPE_VIDEO;
        case AVMEDIA_TYPE_AUDIO:
            return FFKX_MEDIA_TYPE_AUDIO;
        case AVMEDIA_TYPE_SUBTITLE:
            return FFKX_MEDIA_TYPE_SUBTITLE;
        case AVMEDIA_TYPE_DATA:
            return FFKX_MEDIA_TYPE_DATA;
        case AVMEDIA_TYPE_ATTACHMENT:
            return FFKX_MEDIA_TYPE_ATTACHMENT;
        default:
            return FFKX_MEDIA_TYPE_UNKNOWN;
    }
}

// Clockwise degrees in 0..359 (as Android's MediaFormat.KEY_ROTATION): the display matrix gives
// counterclockwise degrees in [-180, 180].
int64_t RotationCw(const AVCodecParameters* par) {
    const AVPacketSideData* side = av_packet_side_data_get(par->coded_side_data, par->nb_coded_side_data,
                                                           AV_PKT_DATA_DISPLAYMATRIX);
    if (side == nullptr || side->size < 9 * sizeof(int32_t)) return 0;
    const double degrees = av_display_rotation_get(reinterpret_cast<const int32_t*>(side->data));
    if (std::isnan(degrees)) return kUnknown;
    return ((-std::llround(degrees)) % 360 + 360) % 360;
}

std::optional<std::string> ChannelLayout(const AVChannelLayout& layout) {
    if (layout.nb_channels <= 0 || layout.order == AV_CHANNEL_ORDER_UNSPEC) return std::nullopt;
    char buffer[128];
    if (av_channel_layout_describe(&layout, buffer, sizeof(buffer)) < 0) return std::nullopt;
    return std::string(buffer);
}

void Describe(const AVFormatContext* ctx, remux::MediaDescription* description) {
    // A duration estimated from the bit rate (raw streams such as ADTS) is a guess; report it as unknown.
    const bool guessed = ctx->duration_estimation_method == AVFMT_DURATION_FROM_BITRATE;
    description->format_name = ctx->iformat->name;
    description->start_time_us = Micros(ctx->start_time, AV_TIME_BASE_Q);
    description->duration_us = guessed ? kUnknown : Micros(ctx->duration, AV_TIME_BASE_Q);
    description->bit_rate = Positive(ctx->bit_rate);

    for (unsigned i = 0; i < ctx->nb_streams; ++i) {
        const AVStream* st = ctx->streams[i];
        const AVCodecParameters* par = st->codecpar;
        const bool video = par->codec_type == AVMEDIA_TYPE_VIDEO;
        const bool audio = par->codec_type == AVMEDIA_TYPE_AUDIO;
        const AVRational rate = st->avg_frame_rate;
        const bool rate_known = video && rate.num > 0 && rate.den > 0;
        remux::StreamDescription stream;
        stream.media_type = MediaType(par->codec_type);
        stream.flags = (st->disposition & AV_DISPOSITION_ATTACHED_PIC) ? FFKX_STREAM_ATTACHED_PICTURE : 0;
        stream.duration_us = guessed ? kUnknown : Micros(st->duration, st->time_base);
        stream.bit_rate = Positive(par->bit_rate);
        if (video) {
            stream.width = Positive(par->width);
            stream.height = Positive(par->height);
            stream.frame_rate_num = rate_known ? rate.num : kUnknown;
            stream.frame_rate_den = rate_known ? rate.den : kUnknown;
            stream.rotation = RotationCw(par);
        }
        if (audio) {
            stream.sample_rate = Positive(par->sample_rate);
            stream.channels = Positive(par->ch_layout.nb_channels);
            stream.channel_layout = ChannelLayout(par->ch_layout);
        }
        stream.codec_name = avcodec_get_name(par->codec_id);
        description->streams.push_back(std::move(stream));
    }
}

}  // namespace

namespace remux {

Status Probe(const Source& source, Task* task, MediaDescription* description) {
    Input input;
    const Status status = OpenFormat(source, task, &input);
    if (!status.failed()) Describe(input.ctx, description);
    return status;
}

}  // namespace remux
