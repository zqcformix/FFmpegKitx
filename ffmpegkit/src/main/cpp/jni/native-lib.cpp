#include <jni.h>
#include <string>
#include <android/log.h>

#include "ffmpegkitx.h"

#define TAG "FFmpegKit"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

void LogLibraryVersion(const char* name, int32_t library) {
    const uint32_t version = ffkx_ffmpeg_library_version(library);
    LOGI("%-14s %u.%u.%u", name, version >> 16, (version >> 8) & 0xFF, version & 0xFF);
}

bool HasLibass() {
    return std::string(ffkx_ffmpeg_configuration()).find("--enable-libass") != std::string::npos;
}

}  // namespace

// Refuses to load against a core library built from a different C ABI version: this binding and
// libffmpegkitx_core.so ship together, so a mismatch means a broken package.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /* vm */, void* /* reserved */) {
    if (!ffkx_abi_is_compatible()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "libffmpegkitx_core C ABI %u.%u, expected %d.%d",
                            ffkx_abi_version() >> 16, ffkx_abi_version() & 0xFFFF, FFKX_ABI_VERSION_MAJOR,
                            FFKX_ABI_VERSION_MINOR);
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_nova_ffmpegkit_FFmpegKit_printInfo(
        JNIEnv* /* env */,
        jobject /* this */) {

    LOGI("========== FFmpeg Integration Check ==========");

    LOGI("FFmpeg version: %s", ffkx_ffmpeg_version());

    LogLibraryVersion("libavcodec", FFKX_LIBRARY_AVCODEC);
    LogLibraryVersion("libavformat", FFKX_LIBRARY_AVFORMAT);
    LogLibraryVersion("libavfilter", FFKX_LIBRARY_AVFILTER);
    LogLibraryVersion("libavutil", FFKX_LIBRARY_AVUTIL);
    LogLibraryVersion("libswresample", FFKX_LIBRARY_SWRESAMPLE);
    LogLibraryVersion("libswscale", FFKX_LIBRARY_SWSCALE);

    LOGI("Build configuration: %s", ffkx_ffmpeg_configuration());

    LOGI("libass: %s", HasLibass() ? "AVAILABLE" : "NOT AVAILABLE");

    LOGI("========== FFmpeg Check Complete ==========");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_nova_ffmpegkit_FFmpegKit_getVersion(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF(ffkx_ffmpeg_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_nova_ffmpegkit_FFmpegKit_getBuildConfiguration(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF(ffkx_ffmpeg_configuration());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_nova_ffmpegkit_FFmpegKit_checkLibassAvailability(
        JNIEnv* env,
        jobject /* this */) {
    const char* result = HasLibass()
            ? "libass: available (found --enable-libass in build configuration)"
            : "libass: NOT available (--enable-libass not found in build configuration)";
    return env->NewStringUTF(result);
}
