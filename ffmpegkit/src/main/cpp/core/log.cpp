#include "log.h"

#include <cstdarg>
#include <cstdio>

#ifdef __ANDROID__
#include <android/log.h>
#endif

extern "C" {
#include "libavutil/log.h"
}

namespace remux {

namespace {

// Engine logs (no task) at this level or more severe are written out (WriteEngineLog).
constexpr int kEngineLevel = AV_LOG_WARNING;

// FFmpeg may build one line from several calls; the parts are joined until a newline.
struct ThreadLog {
    LogBuffer* buffer = nullptr;
    std::string pending;
    int pending_level = AV_LOG_INFO;
    std::string pending_component;
};

thread_local ThreadLog current;

void WriteEngineLog(int level, const std::string& component, const std::string& text) {
#ifdef __ANDROID__
    const int priority = level <= AV_LOG_ERROR     ? ANDROID_LOG_ERROR
                         : level <= AV_LOG_WARNING ? ANDROID_LOG_WARN
                         : level <= AV_LOG_INFO    ? ANDROID_LOG_INFO
                                                   : ANDROID_LOG_DEBUG;
    __android_log_print(priority, "FFmpegKit", "[%s] %s", component.c_str(), text.c_str());
#else
    (void)level;
    fprintf(stderr, "FFmpegKit [%s] %s\n", component.c_str(), text.c_str());
#endif
}

void Flush(ThreadLog& log) {
    std::string& text = log.pending;
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r' || text.back() == ' ')) text.pop_back();
    if (!text.empty()) {
        if (log.buffer != nullptr) {
            log.buffer->Add(log.pending_level, std::move(log.pending_component), std::move(text));
        } else {
            WriteEngineLog(log.pending_level, log.pending_component, text);
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

std::vector<LogEntry> LogBuffer::Entries(int64_t* dropped) const {
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

void InstallLogCallback() {
    av_log_set_callback(Callback);
}

}  // namespace remux
