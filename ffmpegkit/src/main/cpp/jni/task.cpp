// JNI for NativeTask: a NativeTask handle is an ffkx_task pointer holding the one reference NativeTask owns.
#include <jni.h>
#include <cstring>

#include "ffmpegkitx.h"

namespace {

ffkx_task* TaskOf(jlong handle) {
    return reinterpret_cast<ffkx_task*>(handle);
}

jbyteArray Bytes(JNIEnv* env, const char* text) {
    const auto length = static_cast<jsize>(std::strlen(text));
    jbyteArray bytes = env->NewByteArray(length);
    if (bytes != nullptr) env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte*>(text));
    return bytes;
}

// Builds a NativeLog. Texts cross JNI as UTF-8 bytes: FFmpeg's text is not modified UTF-8.
jobject ToJava(JNIEnv* env, const ffkx_log* log) {
    jclass log_class = env->FindClass("io/github/nova/ffmpegkit/NativeLog");
    if (log_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(log_class, "<init>", "([I[[B[[BJ)V");
    if (constructor == nullptr) return nullptr;
    jclass bytes_class = env->FindClass("[B");
    if (bytes_class == nullptr) return nullptr;
    const auto count = static_cast<jsize>(ffkx_log_count(log));
    jintArray levels = env->NewIntArray(count);
    jobjectArray components = env->NewObjectArray(count, bytes_class, nullptr);
    jobjectArray messages = env->NewObjectArray(count, bytes_class, nullptr);
    if (levels == nullptr || components == nullptr || messages == nullptr) return nullptr;
    for (jsize i = 0; i < count; ++i) {
        ffkx_log_entry entry{};
        entry.struct_size = sizeof(entry);
        ffkx_log_entry_at(log, static_cast<uint32_t>(i), &entry);
        const jint level = entry.level;
        env->SetIntArrayRegion(levels, i, 1, &level);
        jbyteArray component = Bytes(env, entry.component);
        jbyteArray message = Bytes(env, entry.message);
        if (component == nullptr || message == nullptr) return nullptr;
        env->SetObjectArrayElement(components, i, component);
        env->SetObjectArrayElement(messages, i, message);
        env->DeleteLocalRef(component);
        env->DeleteLocalRef(message);
    }
    return env->NewObject(log_class, constructor, levels, components, messages,
                          static_cast<jlong>(ffkx_log_dropped(log)));
}

void ThrowFor(JNIEnv* env, ffkx_status status) {
    jclass type = env->FindClass(status == FFKX_ERROR_OUT_OF_MEMORY ? "java/lang/OutOfMemoryError"
                                                                    : "java/lang/IllegalArgumentException");
    if (type != nullptr) env->ThrowNew(type, ffkx_status_name(status));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_nova_ffmpegkit_NativeTask_create(JNIEnv* env, jclass /* clazz */, jint log_level, jint max_log_entries,
                                                jlong read_timeout_us, jlong max_buffered_bytes) {
    ffkx_task_options options{};
    options.struct_size = sizeof(options);
    options.log_level = log_level;
    options.max_log_entries = max_log_entries;
    options.read_timeout_us = read_timeout_us;
    options.max_buffered_bytes = max_buffered_bytes;
    ffkx_task* task = nullptr;
    const ffkx_status status = ffkx_task_create(&options, &task);
    if (status != FFKX_OK) ThrowFor(env, status);
    return reinterpret_cast<jlong>(task);
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_nova_ffmpegkit_NativeTask_log(JNIEnv* env, jclass /* clazz */, jlong handle) {
    ffkx_log* log = nullptr;
    const ffkx_status status = ffkx_task_log(TaskOf(handle), &log);
    if (status != FFKX_OK) {
        ThrowFor(env, status);
        return nullptr;
    }
    jobject result = ToJava(env, log);
    ffkx_log_free(log);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_nova_ffmpegkit_NativeTask_cancel(JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    ffkx_task_cancel(TaskOf(handle));
}

// Fills `out` (length 4, laid out as NativeTask.progress reads it): processed_us, duration_us, bytes_written, segment.
extern "C" JNIEXPORT void JNICALL
Java_io_github_nova_ffmpegkit_NativeTask_progress(JNIEnv* env, jclass /* clazz */, jlong handle, jlongArray out) {
    ffkx_progress progress{};
    progress.struct_size = sizeof(progress);
    ffkx_task_progress(TaskOf(handle), &progress);
    const jlong values[] = {progress.processed_us, progress.duration_us, progress.bytes_written, progress.segment};
    env->SetLongArrayRegion(out, 0, 4, values);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_nova_ffmpegkit_NativeTask_setSegment(JNIEnv* /* env */, jclass /* clazz */, jlong handle, jint segment) {
    ffkx_task_set_segment(TaskOf(handle), segment);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_nova_ffmpegkit_NativeTask_destroy(JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    ffkx_task_release(TaskOf(handle));
}
