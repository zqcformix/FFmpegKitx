#include "remux.h"

#include <cerrno>
#include <sys/stat.h>
#include <unistd.h>

#include "jni_util.h"

namespace remux {

namespace {

constexpr int kIoBufferSize = 64 * 1024;

int ReadFd(void* opaque, uint8_t* buffer, int size) {
    const int fd = static_cast<int>(reinterpret_cast<intptr_t>(opaque));
    ssize_t n;
    do {
        n = read(fd, buffer, size);
    } while (n < 0 && errno == EINTR);
    if (n == 0) return AVERROR_EOF;
    return n < 0 ? AVERROR(errno) : static_cast<int>(n);
}

int64_t SeekFd(void* opaque, int64_t offset, int whence) {
    const int fd = static_cast<int>(reinterpret_cast<intptr_t>(opaque));
    if (whence == AVSEEK_SIZE) {
        struct stat st {};
        return fstat(fd, &st) == 0 && S_ISREG(st.st_mode) ? st.st_size : AVERROR(ENOSYS);
    }
    const off_t position = lseek(fd, offset, whence & ~AVSEEK_FORCE);
    return position < 0 ? AVERROR(errno) : position;
}

}  // namespace

Input::~Input() {
    avformat_close_input(&ctx);  // with AVFMT_FLAG_CUSTOM_IO this leaves `io` to us
    if (io != nullptr) {
        av_freep(&io->buffer);
        avio_context_free(&io);
    }
}

Source ToSource(JNIEnv* env, jbyteArray path, jint fd) {
    Source source;
    if (path != nullptr) source.path = ToUtf8Path(env, path);
    source.fd = fd;
    return source;
}

Status Failure(int code, bool output_side, std::string detail) {
    Status s;
    s.code = code;
    s.output_side = output_side;
    s.detail = std::move(detail);
    return s;
}

Status Rejected(int reason, bool output_side, std::string detail, int code) {
    Status s = Failure(code, output_side, std::move(detail));
    s.reason = reason;
    return s;
}

void Output::Reset() {
    if (ctx == nullptr) return;
    if (!(ctx->oformat->flags & AVFMT_NOFILE)) avio_closep(&ctx->pb);
    avformat_free_context(ctx);
    ctx = nullptr;
}

Status OpenInput(const Source& source, Input* input) {
    int ret;
    if (source.fd >= 0) {
        // Pipes and some provider streams cannot seek; M1 needs seeking (keyframe search, MP4 index).
        if (lseek(source.fd, 0, SEEK_SET) < 0) {
            const int error = AVERROR(errno);
            return Rejected(kReasonUnsupportedMedia, false, "input cannot seek (pipe or stream)", error);
        }
        auto* buffer = static_cast<unsigned char*>(av_malloc(kIoBufferSize));
        if (buffer == nullptr) return Failure(AVERROR(ENOMEM), false, "allocate input buffer");
        input->io = avio_alloc_context(buffer, kIoBufferSize, 0, reinterpret_cast<void*>(static_cast<intptr_t>(source.fd)),
                                       ReadFd, nullptr, SeekFd);
        if (input->io == nullptr) {
            av_free(buffer);
            return Failure(AVERROR(ENOMEM), false, "allocate input context");
        }
        input->ctx = avformat_alloc_context();
        if (input->ctx == nullptr) return Failure(AVERROR(ENOMEM), false, "allocate input context");
        input->ctx->pb = input->io;
        input->ctx->flags |= AVFMT_FLAG_CUSTOM_IO;
        ret = avformat_open_input(&input->ctx, nullptr, nullptr, nullptr);  // frees ctx on failure
    } else {
        ret = avformat_open_input(&input->ctx, source.path.c_str(), nullptr, nullptr);
    }
    if (ret < 0) return Failure(ret, false, "open input");
    ret = avformat_find_stream_info(input->ctx, nullptr);
    if (ret < 0) return Failure(ret, false, "read stream info");

    input->tracks.assign(input->ctx->nb_streams, Track{});
    int selected = 0;
    for (unsigned i = 0; i < input->ctx->nb_streams; ++i) {
        const AVStream* st = input->ctx->streams[i];
        const AVMediaType type = st->codecpar->codec_type;
        if (st->disposition & AV_DISPOSITION_ATTACHED_PIC) continue;  // cover art
        if (type == AVMEDIA_TYPE_VIDEO) {
            if (input->has_video) return Rejected(kReasonUnsupportedMedia, false, "more than one video stream");
            input->has_video = true;
            input->tracks[i].video = true;
            input->reference_stream = static_cast<int>(i);
        } else if (type == AVMEDIA_TYPE_AUDIO) {
            if (input->reference_stream < 0) input->reference_stream = static_cast<int>(i);
        } else {
            continue;  // subtitles, data and attachments are not copied
        }
        input->tracks[i].output_index = selected++;
    }
    if (selected == 0) return Rejected(kReasonUnsupportedMedia, false, "no audio or video stream");
    input->origin = input->ctx->start_time == AV_NOPTS_VALUE ? 0 : input->ctx->start_time;
    return {};
}

Status OpenOutput(const Input& input, const std::string& path, Output* output) {
    int ret = avformat_alloc_output_context2(&output->ctx, nullptr, nullptr, path.c_str());
    if (ret < 0 || output->ctx == nullptr) {
        return Rejected(kReasonUnsupportedMedia, true, "no container matches the output file extension", ret);
    }
    AVFormatContext* out = output->ctx;
    for (unsigned i = 0; i < input.ctx->nb_streams; ++i) {
        if (input.tracks[i].output_index < 0) continue;
        const AVStream* ist = input.ctx->streams[i];
        // 1: supported, 0: not supported, <0: unknown (header writing decides).
        if (avformat_query_codec(out->oformat, ist->codecpar->codec_id, FF_COMPLIANCE_NORMAL) == 0) {
            return Rejected(kReasonUnsupportedMedia, true,
                            std::string(avcodec_get_name(ist->codecpar->codec_id)) + " cannot be stored in " +
                                out->oformat->name);
        }
        AVStream* ost = avformat_new_stream(out, nullptr);
        if (ost == nullptr) return Failure(AVERROR(ENOMEM), true, "create output stream");
        // Also copies coded side data such as the display matrix (rotation).
        ret = avcodec_parameters_copy(ost->codecpar, ist->codecpar);
        if (ret < 0) return Failure(ret, true, "copy stream parameters");
        ost->codecpar->codec_tag = 0;  // let the output container choose its own tag
        ost->time_base = ist->time_base;
        ost->disposition = ist->disposition;
    }
    if (!(out->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&out->pb, path.c_str(), AVIO_FLAG_WRITE);
        if (ret < 0) return Failure(ret, true, "open output");
    }
    ret = avformat_write_header(out, nullptr);
    if (ret == AVERROR(EINVAL) || ret == AVERROR(ENOSYS) || ret == AVERROR_PATCHWELCOME) {
        return Rejected(kReasonUnsupportedMedia, true, std::string(out->oformat->name) + " cannot store these streams", ret);
    }
    if (ret < 0) return Failure(ret, true, "write output header");
    return {};
}

Status CloseOutput(Output* output) {
    int ret = av_write_trailer(output->ctx);
    if (ret < 0) return Failure(ret, true, "write output trailer");
    if (!(output->ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_closep(&output->ctx->pb);
        if (ret < 0) return Failure(ret, true, "close output");
    }
    output->Reset();
    return {};
}

int WritePacket(const Input& input, Output* output, AVPacket* packet, int64_t anchor_pts, AVRational anchor_time_base) {
    const AVStream* ist = input.ctx->streams[packet->stream_index];
    const int out_index = input.tracks[packet->stream_index].output_index;
    const int64_t offset = av_rescale_q(anchor_pts, anchor_time_base, ist->time_base);
    if (packet->pts != AV_NOPTS_VALUE) packet->pts -= offset;
    if (packet->dts != AV_NOPTS_VALUE) packet->dts -= offset;
    av_packet_rescale_ts(packet, ist->time_base, output->ctx->streams[out_index]->time_base);
    packet->stream_index = out_index;
    packet->pos = -1;
    return av_interleaved_write_frame(output->ctx, packet);
}

jobject ToJava(JNIEnv* env, const Status& status, const std::vector<int64_t>& times_us) {
    jclass result_class = env->FindClass("io/github/nova/ffmpegkit/NativeRemuxStatus");
    if (result_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(result_class, "<init>", "(IZILjava/lang/String;[J)V");
    if (constructor == nullptr) return nullptr;
    jstring detail = status.detail.empty() ? nullptr : env->NewStringUTF(status.detail.c_str());
    jlongArray times = env->NewLongArray(static_cast<jsize>(times_us.size()));
    if (times == nullptr) return nullptr;
    env->SetLongArrayRegion(times, 0, static_cast<jsize>(times_us.size()),
                            reinterpret_cast<const jlong*>(times_us.data()));
    return env->NewObject(result_class, constructor, status.code, status.output_side, status.reason, detail, times);
}

}  // namespace remux
