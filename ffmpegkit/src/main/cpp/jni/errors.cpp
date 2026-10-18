#include <jni.h>
#include <cerrno>

#include "ffmpegkitx.h"

extern "C" {
#include "libavutil/error.h"  // AVERROR constants only; compiled from the header, nothing is linked
}

namespace {

jintArray ToArray(JNIEnv* env, const jint* values, jsize count) {
    jintArray result = env->NewIntArray(count);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, count, values);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_nova_ffmpegkit_NativeErrors_describe(
        JNIEnv* env,
        jobject /* this */,
        jint code) {
    char buffer[AV_ERROR_MAX_STRING_SIZE];
    ffkx_ffmpeg_error_string(code, buffer, sizeof(buffer));
    return env->NewStringUTF(buffer);
}

// Same order as AvError.all in ErrorMapping.kt.
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_nova_ffmpegkit_NativeErrors_constants(
        JNIEnv* env,
        jobject /* this */) {
    const jint values[] = {
            AVERROR(EPERM), AVERROR(ENOENT), AVERROR(EIO), AVERROR(EACCES), AVERROR(EEXIST),
            AVERROR(EINVAL), AVERROR(ENOSPC), AVERROR(EROFS), AVERROR(ENOSYS), AVERROR(EDQUOT),
            AVERROR(ETIMEDOUT),
            AVERROR_INVALIDDATA, AVERROR_EOF, AVERROR_PATCHWELCOME,
            AVERROR_DEMUXER_NOT_FOUND, AVERROR_MUXER_NOT_FOUND, AVERROR_DECODER_NOT_FOUND,
            AVERROR_ENCODER_NOT_FOUND, AVERROR_STREAM_NOT_FOUND, AVERROR_PROTOCOL_NOT_FOUND,
            AVERROR_BSF_NOT_FOUND, AVERROR_FILTER_NOT_FOUND,
    };
    return ToArray(env, values, sizeof(values) / sizeof(values[0]));
}

// The FFKX_* status values, in the order of NativeStatus.all in NativeRemux.kt.
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_nova_ffmpegkit_NativeErrors_statuses(
        JNIEnv* env,
        jobject /* this */) {
    const jint values[] = {
            FFKX_OK, FFKX_ERROR_INVALID_ARGUMENT, FFKX_ERROR_UNSUPPORTED_VERSION, FFKX_ERROR_OUT_OF_MEMORY,
            FFKX_ERROR_INTERNAL, FFKX_ERROR_CANCELLED, FFKX_ERROR_INPUT_NOT_FOUND, FFKX_ERROR_ACCESS_DENIED,
            FFKX_ERROR_OUTPUT_EXISTS, FFKX_ERROR_INSUFFICIENT_SPACE, FFKX_ERROR_RANGE_OUTSIDE_MEDIA,
            FFKX_ERROR_TOO_MANY_SEGMENTS, FFKX_ERROR_UNSUPPORTED_MEDIA, FFKX_ERROR_INVALID_MEDIA, FFKX_ERROR_IO,
            FFKX_ERROR_FFMPEG,
    };
    return ToArray(env, values, sizeof(values) / sizeof(values[0]));
}

// The status the core reports for an FFmpeg error code on one side.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_nova_ffmpegkit_NativeErrors_statusOf(
        JNIEnv* /* env */,
        jobject /* this */,
        jint code,
        jboolean output_side) {
    return ffkx_status_from_ffmpeg_error(code, output_side == JNI_TRUE ? FFKX_SIDE_OUTPUT : FFKX_SIDE_INPUT);
}
