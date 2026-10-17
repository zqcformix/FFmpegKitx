#include <jni.h>
#include <cerrno>

extern "C" {
#include "libavutil/error.h"
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_nova_ffmpegkit_NativeErrors_describe(
        JNIEnv* env,
        jobject /* this */,
        jint code) {
    char buffer[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(code, buffer, sizeof(buffer));
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
            AVERROR_INVALIDDATA, AVERROR_EOF, AVERROR_PATCHWELCOME,
            AVERROR_DEMUXER_NOT_FOUND, AVERROR_MUXER_NOT_FOUND, AVERROR_DECODER_NOT_FOUND,
            AVERROR_ENCODER_NOT_FOUND, AVERROR_STREAM_NOT_FOUND, AVERROR_PROTOCOL_NOT_FOUND,
            AVERROR_BSF_NOT_FOUND, AVERROR_FILTER_NOT_FOUND,
    };
    const jsize count = sizeof(values) / sizeof(values[0]);
    jintArray result = env->NewIntArray(count);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, count, values);
    }
    return result;
}
