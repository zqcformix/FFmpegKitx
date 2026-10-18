// Structured probe: container and per-stream properties after avformat_find_stream_info, without decoding.
#include <jni.h>
#include <cmath>
#include <iterator>
#include <optional>
#include <string>
#include <vector>

#include "remux.h"

extern "C" {
#include "libavutil/channel_layout.h"
#include "libavutil/display.h"
}

// Result layout, parsed by Prober.kt. Unknown numbers are INT64_MIN (AV_NOPTS_VALUE); unknown strings null.
//   numbers: start_time_us, duration_us, bit_rate,
//     then per stream: media_type, duration_us, bit_rate, width, height, frame_rate_num, frame_rate_den,
//     rotation_ccw (degrees, 0 without a display matrix), attached_picture, sample_rate, channels
//   strings: format_name, then per stream: codec_name, channel_layout

namespace {

constexpr int64_t kUnknown = INT64_MIN;

int64_t Micros(int64_t ts, AVRational time_base) {
    return ts == AV_NOPTS_VALUE ? kUnknown : remux::ToMicros(ts, time_base);
}

// FFmpeg stores "not known" as 0 in sizes, rates and counts.
int64_t Positive(int64_t value) {
    return value > 0 ? value : kUnknown;
}

int64_t RotationCcw(const AVCodecParameters* par) {
    const AVPacketSideData* side = av_packet_side_data_get(par->coded_side_data, par->nb_coded_side_data,
                                                           AV_PKT_DATA_DISPLAYMATRIX);
    if (side == nullptr || side->size < 9 * sizeof(int32_t)) return 0;
    const double degrees = av_display_rotation_get(reinterpret_cast<const int32_t*>(side->data));
    return std::isnan(degrees) ? kUnknown : std::llround(degrees);
}

std::optional<std::string> ChannelLayout(const AVChannelLayout& layout) {
    if (layout.nb_channels <= 0 || layout.order == AV_CHANNEL_ORDER_UNSPEC) return std::nullopt;
    char buffer[128];
    if (av_channel_layout_describe(&layout, buffer, sizeof(buffer)) < 0) return std::nullopt;
    return std::string(buffer);
}

void Describe(const AVFormatContext* ctx, std::vector<int64_t>* numbers, std::vector<std::optional<std::string>>* strings) {
    // A duration estimated from the bit rate (raw streams such as ADTS) is a guess; report it as unknown.
    const bool guessed = ctx->duration_estimation_method == AVFMT_DURATION_FROM_BITRATE;
    numbers->push_back(Micros(ctx->start_time, AV_TIME_BASE_Q));
    numbers->push_back(guessed ? kUnknown : Micros(ctx->duration, AV_TIME_BASE_Q));
    numbers->push_back(Positive(ctx->bit_rate));
    strings->emplace_back(ctx->iformat->name);

    for (unsigned i = 0; i < ctx->nb_streams; ++i) {
        const AVStream* st = ctx->streams[i];
        const AVCodecParameters* par = st->codecpar;
        const bool video = par->codec_type == AVMEDIA_TYPE_VIDEO;
        const bool audio = par->codec_type == AVMEDIA_TYPE_AUDIO;
        const AVRational rate = st->avg_frame_rate;
        const bool rate_known = video && rate.num > 0 && rate.den > 0;
        const int64_t fields[] = {
            par->codec_type,
            guessed ? kUnknown : Micros(st->duration, st->time_base),
            Positive(par->bit_rate),
            video ? Positive(par->width) : kUnknown,
            video ? Positive(par->height) : kUnknown,
            rate_known ? rate.num : kUnknown,
            rate_known ? rate.den : kUnknown,
            video ? RotationCcw(par) : kUnknown,
            (st->disposition & AV_DISPOSITION_ATTACHED_PIC) ? 1 : 0,
            audio ? Positive(par->sample_rate) : kUnknown,
            audio ? Positive(par->ch_layout.nb_channels) : kUnknown,
        };
        numbers->insert(numbers->end(), std::begin(fields), std::end(fields));
        strings->emplace_back(avcodec_get_name(par->codec_id));
        strings->push_back(audio ? ChannelLayout(par->ch_layout) : std::nullopt);
    }
}

jobject ToJava(JNIEnv* env, const remux::Status& status, const std::vector<int64_t>& numbers,
               const std::vector<std::optional<std::string>>& strings) {
    jobject java_status = remux::ToJava(env, status, {});
    if (java_status == nullptr) return nullptr;
    jclass result_class = env->FindClass("io/github/nova/ffmpegkit/NativeProbeResult");
    if (result_class == nullptr) return nullptr;
    jmethodID constructor =
        env->GetMethodID(result_class, "<init>", "(Lio/github/nova/ffmpegkit/NativeRemuxStatus;[J[Ljava/lang/String;)V");
    if (constructor == nullptr) return nullptr;

    jlongArray java_numbers = env->NewLongArray(static_cast<jsize>(numbers.size()));
    if (java_numbers == nullptr) return nullptr;
    env->SetLongArrayRegion(java_numbers, 0, static_cast<jsize>(numbers.size()),
                            reinterpret_cast<const jlong*>(numbers.data()));
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray java_strings = env->NewObjectArray(static_cast<jsize>(strings.size()), string_class, nullptr);
    if (java_strings == nullptr) return nullptr;
    for (size_t i = 0; i < strings.size(); ++i) {
        if (!strings[i]) continue;
        // Names and layouts from FFmpeg are ASCII, so modified UTF-8 is exact here.
        jstring value = env->NewStringUTF(strings[i]->c_str());
        if (value == nullptr) return nullptr;
        env->SetObjectArrayElement(java_strings, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return env->NewObject(result_class, constructor, java_status, java_numbers, java_strings);
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeProbe_probe(JNIEnv* env, jclass /* clazz */, jbyteArray path, jint fd,
                                                jlong task) {
    remux::LogScope logs(remux::LogOf(task));
    remux::Task scratch;
    remux::Input input;
    const remux::Status status =
        remux::OpenFormat(remux::ToSource(env, path, fd), remux::FromHandle(task, &scratch), &input);
    std::vector<int64_t> numbers;
    std::vector<std::optional<std::string>> strings;
    if (!status.failed()) Describe(input.ctx, &numbers, &strings);
    return ToJava(env, status, numbers, strings);
}
