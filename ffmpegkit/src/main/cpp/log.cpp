#include "log.h"

#include <android/log.h>
#include <cstdarg>
#include <cstdio>

extern "C" {
#include "libavutil/log.h"
}

namespace remux {

namespace {

// Engine logs (no task) at this level or more severe go to logcat.
constexpr int kEngineLevel = AV_LOG_WARNING;

// FFmpeg may build one line from several calls; the parts are joined until a newline.
struct ThreadLog {
    LogBuffer* buffer = nullptr;
    std::string pending;
    int pending_level = AV_LOG_INFO;
    std::string pending_component;
};

thread_local ThreadLog current;

int LogcatPriority(int level) {
    if (level <= AV_LOG_ERROR) return ANDROID_LOG_ERROR;
    if (level <= AV_LOG_WARNING) return ANDROID_LOG_WARN;
    if (level <= AV_LOG_INFO) return ANDROID_LOG_INFO;
    return ANDROID_LOG_DEBUG;
}

void Flush(ThreadLog& log) {
    std::string& text = log.pending;
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r' || text.back() == ' ')) text.pop_back();
    if (!text.empty()) {
        if (log.buffer != nullptr) {
            log.buffer->Add(log.pending_level, std::move(log.pending_component), std::move(text));
        } else {
            __android_log_print(LogcatPriority(log.pending_level), "FFmpegKit", "[%s] %s", log.pending_component.c_str(),
                                text.c_str());
        }
    }
    text.clear();
    log.pending_component.clear();
}

const char* Component(void* avcl) {
    if (avcl == nullptr) return "";
    const AVClass* cls = *static_cast<const AVClass* const*>(avcl);
    if (cls == nullptr || cls->item_name == nullptr) return "";
    const char* name = cls->item_name(avcl);
    return name == nullptr ? "" : name;
}

void Callback(void* avcl, int level, const char* fmt, va_list vl) {
    ThreadLog& log = current;
    const bool wanted = log.buffer != nullptr ? log.buffer->Wants(level) : level <= kEngineLevel;
    if (!wanted) return;
    char chunk[LogBuffer::kMaxMessageBytes];
    va_list copy;
    va_copy(copy, vl);
    vsnprintf(chunk, sizeof(chunk), fmt, copy);
    va_end(copy);
    if (log.pending.empty()) {
        log.pending_level = level;
        log.pending_component = Component(avcl);
    }
    if (log.pending.size() < LogBuffer::kMaxMessageBytes) log.pending.append(chunk);
    if (log.pending.size() > LogBuffer::kMaxMessageBytes) log.pending.resize(LogBuffer::kMaxMessageBytes);
    if (!log.pending.empty() && log.pending.back() == '\n') Flush(log);
}

jbyteArray Bytes(JNIEnv* env, const std::string& text) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (bytes != nullptr) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    }
    return bytes;
}

}  // namespace

void LogBuffer::Add(int level, std::string component, std::string message) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (max_entries_ <= 0) {
        ++dropped_;
        return;
    }
    if (static_cast<int>(entries_.size()) >= max_entries_) {
        entries_.pop_front();
        ++dropped_;
    }
    entries_.push_back(LogEntry{level, std::move(component), std::move(message)});
}

std::vector<LogEntry> LogBuffer::Entries(int* dropped) const {
    std::lock_guard<std::mutex> lock(mutex_);
    *dropped = dropped_;
    return {entries_.begin(), entries_.end()};
}

LogScope::LogScope(LogBuffer* buffer) : previous_(current.buffer) {
    Flush(current);  // an unfinished line belongs to whoever logged it before
    current.buffer = buffer;
}

LogScope::~LogScope() {
    Flush(current);
    current.buffer = previous_;
}

jobject LogToJava(JNIEnv* env, const LogBuffer& buffer) {
    int dropped = 0;
    const std::vector<LogEntry> entries = buffer.Entries(&dropped);
    jclass log_class = env->FindClass("io/github/nova/ffmpegkit/NativeLog");
    if (log_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(log_class, "<init>", "([I[[B[[BI)V");
    if (constructor == nullptr) return nullptr;
    jclass bytes_class = env->FindClass("[B");
    if (bytes_class == nullptr) return nullptr;
    const auto count = static_cast<jsize>(entries.size());
    jintArray levels = env->NewIntArray(count);
    jobjectArray components = env->NewObjectArray(count, bytes_class, nullptr);
    jobjectArray messages = env->NewObjectArray(count, bytes_class, nullptr);
    if (levels == nullptr || components == nullptr || messages == nullptr) return nullptr;
    for (jsize i = 0; i < count; ++i) {
        const jint level = entries[i].level;
        env->SetIntArrayRegion(levels, i, 1, &level);
        jbyteArray component = Bytes(env, entries[i].component);
        jbyteArray message = Bytes(env, entries[i].message);
        if (component == nullptr || message == nullptr) return nullptr;
        env->SetObjectArrayElement(components, i, component);
        env->SetObjectArrayElement(messages, i, message);
        env->DeleteLocalRef(component);
        env->DeleteLocalRef(message);
    }
    return env->NewObject(log_class, constructor, levels, components, messages, dropped);
}

}  // namespace remux

// Installs the log callback once the library is loaded, before any FFmpeg call can log.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /* vm */, void* /* reserved */) {
    av_log_set_callback(remux::Callback);
    return JNI_VERSION_1_6;
}
