// JNI for NativeTrim, NativeSplit and NativeProbe over the C ABI: converts arguments and results only.
#include <jni.h>
#include <cstring>
#include <string>
#include <vector>

#include "ffmpegkitx.h"
#include "jni_util.h"

namespace {

// An input as Kotlin passes it: UTF-8 path bytes, or null and a descriptor.
struct Input {
    Input(JNIEnv* env, jbyteArray path_bytes, jint fd_value) : fd(fd_value) {
        if (path_bytes != nullptr) path = ToUtf8Path(env, path_bytes);
        has_path = path_bytes != nullptr;
    }

    const char* c_path() const { return has_path ? path.c_str() : nullptr; }

    std::string path;
    bool has_path = false;
    int fd;
};

ffkx_error NewError() {
    ffkx_error error{};
    error.struct_size = sizeof(error);
    return error;
}

// Builds a NativeRemuxStatus. The message crosses as UTF-8 bytes, like log lines.
jobject ToStatus(JNIEnv* env, const ffkx_error& error, const std::vector<jlong>& times_us) {
    jclass status_class = env->FindClass("io/github/nova/ffmpegkit/NativeRemuxStatus");
    if (status_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(status_class, "<init>", "(III[B[J)V");
    if (constructor == nullptr) return nullptr;
    const auto length = static_cast<jsize>(std::strlen(error.message));
    jbyteArray message = env->NewByteArray(length);
    if (message == nullptr) return nullptr;
    env->SetByteArrayRegion(message, 0, length, reinterpret_cast<const jbyte*>(error.message));
    jlongArray times = env->NewLongArray(static_cast<jsize>(times_us.size()));
    if (times == nullptr) return nullptr;
    env->SetLongArrayRegion(times, 0, static_cast<jsize>(times_us.size()), times_us.data());
    return env->NewObject(status_class, constructor, error.status, error.ffmpeg_error, error.side, message, times);
}

// Builds a NativeProbeResult; null with an exception pending if the JVM runs out of memory.
jobject ToJava(JNIEnv* env, const ffkx_error& error, const std::vector<jlong>& numbers,
               const std::vector<const char*>& strings) {
    jobject status = ToStatus(env, error, {});
    if (status == nullptr) return nullptr;
    jclass result_class = env->FindClass("io/github/nova/ffmpegkit/NativeProbeResult");
    if (result_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(
        result_class, "<init>", "(Lio/github/nova/ffmpegkit/NativeRemuxStatus;[J[Ljava/lang/String;)V");
    if (constructor == nullptr) return nullptr;
    jlongArray java_numbers = env->NewLongArray(static_cast<jsize>(numbers.size()));
    if (java_numbers == nullptr) return nullptr;
    env->SetLongArrayRegion(java_numbers, 0, static_cast<jsize>(numbers.size()), numbers.data());
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray java_strings = env->NewObjectArray(static_cast<jsize>(strings.size()), string_class, nullptr);
    if (java_strings == nullptr) return nullptr;
    for (size_t i = 0; i < strings.size(); ++i) {
        if (strings[i] == nullptr) continue;
        // Names and layouts from FFmpeg are ASCII, so modified UTF-8 is exact here.
        jstring value = env->NewStringUTF(strings[i]);
        if (value == nullptr) return nullptr;
        env->SetObjectArrayElement(java_strings, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return env->NewObject(result_class, constructor, status, java_numbers, java_strings);
}

}  // namespace

// [task] is a NativeTask handle (an ffkx_task) or 0. Times: [actual start, end).
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeTrim_trim(JNIEnv* env, jobject /* this */, jbyteArray input_path, jint input_fd,
                                              jbyteArray output, jlong start_us, jlong end_us, jboolean edit_list,
                                              jlong task) {
    const Input input(env, input_path, input_fd);
    const std::string output_path = ToUtf8Path(env, output);
    ffkx_trim_options options{};
    options.struct_size = sizeof(options);
    options.flags = edit_list == JNI_TRUE ? FFKX_TRIM_EDIT_LIST : 0;
    options.input_fd = input.fd;
    options.input_path = input.c_path();
    options.output_path = output_path.c_str();
    options.start_us = start_us;
    options.end_us = end_us;
    ffkx_trim_result result{};
    result.struct_size = sizeof(result);
    ffkx_error error = NewError();
    const ffkx_status status = ffkx_trim(&options, reinterpret_cast<ffkx_task*>(task), &result, &error);
    std::vector<jlong> times;
    if (status == FFKX_OK) times = {result.actual_start_us, result.actual_end_us};
    return ToStatus(env, error, times);
}

// Writes 0001.<extension>, 0002.<extension>… into [directory]. Times: one [start, end) pair per segment.
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeSplit_split(JNIEnv* env, jobject /* this */, jbyteArray input_path, jint input_fd,
                                                jbyteArray directory, jbyteArray extension, jlong segment_us,
                                                jint max_segments, jlong task) {
    const Input input(env, input_path, input_fd);
    const std::string directory_path = ToUtf8Path(env, directory);
    const std::string extension_text = ToUtf8Path(env, extension);
    ffkx_split_options options{};
    options.struct_size = sizeof(options);
    options.input_fd = input.fd;
    options.input_path = input.c_path();
    options.output_directory = directory_path.c_str();
    options.extension = extension_text.c_str();
    options.segment_us = segment_us;
    options.max_segments = max_segments;
    ffkx_split_result* result = nullptr;
    ffkx_error error = NewError();
    const ffkx_status status = ffkx_split(&options, reinterpret_cast<ffkx_task*>(task), &result, &error);
    std::vector<jlong> times;
    if (status == FFKX_OK) {
        for (uint32_t i = 0; i < ffkx_split_result_count(result); ++i) {
            ffkx_segment segment{};
            segment.struct_size = sizeof(segment);
            ffkx_split_result_segment(result, i, &segment);
            times.push_back(segment.start_us);
            times.push_back(segment.end_us);
        }
    }
    ffkx_split_result_free(result);
    return ToStatus(env, error, times);
}

// Runs ffmpeg with [arguments], each UTF-8 bytes; see ffkx_execute.
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeCommand_execute(JNIEnv* env, jclass /* clazz */, jobjectArray arguments, jlong task) {
    const jsize count = env->GetArrayLength(arguments);
    std::vector<std::string> values;
    values.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(arguments, i));
        values.push_back(ToUtf8Path(env, bytes));
        env->DeleteLocalRef(bytes);
    }
    std::vector<const char*> pointers;
    for (const std::string& value : values) pointers.push_back(value.c_str());
    ffkx_execute_options options{};
    options.struct_size = sizeof(options);
    options.argument_count = count;
    options.arguments = pointers.data();
    ffkx_error error = NewError();
    ffkx_execute(&options, reinterpret_cast<ffkx_task*>(task), &error);
    return ToStatus(env, error, {});
}

// Result layout, parsed by Prober.kt (NativeProbeResult). Unknown numbers are FFKX_UNKNOWN (Long.MIN_VALUE).
//   numbers: start_time_us, duration_us, bit_rate,
//     then per stream: media_type (FFKX_MEDIA_TYPE_*), flags (FFKX_STREAM_*), duration_us, bit_rate, width,
//     height, frame_rate_num, frame_rate_den, rotation (clockwise), sample_rate, channels
//   strings: format_name, then per stream: codec_name, channel_layout (null if unknown)
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeProbe_probe(JNIEnv* env, jclass /* clazz */, jbyteArray path, jint fd, jlong task) {
    const Input input(env, path, fd);
    ffkx_probe_options options{};
    options.struct_size = sizeof(options);
    options.input_fd = input.fd;
    options.input_path = input.c_path();
    ffkx_media_info* info = nullptr;
    ffkx_error error = NewError();
    const ffkx_status status = ffkx_probe(&options, reinterpret_cast<ffkx_task*>(task), &info, &error);

    std::vector<jlong> numbers;
    std::vector<const char*> strings;
    if (status == FFKX_OK) {
        ffkx_format_info format{};
        format.struct_size = sizeof(format);
        ffkx_media_info_format(info, &format);
        numbers.insert(numbers.end(), {format.start_time_us, format.duration_us, format.bit_rate});
        strings.push_back(format.format_name);
        for (uint32_t i = 0; i < format.stream_count; ++i) {
            ffkx_stream_info stream{};
            stream.struct_size = sizeof(stream);
            ffkx_media_info_stream(info, i, &stream);
            numbers.insert(numbers.end(), {stream.media_type, stream.flags, stream.duration_us, stream.bit_rate,
                                           stream.width, stream.height, stream.frame_rate_num, stream.frame_rate_den,
                                           stream.rotation, stream.sample_rate, stream.channels});
            strings.push_back(stream.codec_name);
            strings.push_back(stream.channel_layout);
        }
    }

    jobject result = ToJava(env, error, numbers, strings);  // the strings point into `info`
    ffkx_media_info_free(info);
    return result;
}
