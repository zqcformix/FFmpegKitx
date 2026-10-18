#include "log.h"

#include <atomic>
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

// The default sink writes engine logs (no task) at this level or more severe (WriteEngineLog).
constexpr int kEngineLevel = AV_LOG_WARNING;

// FFmpeg may build one line from several calls; the parts are joined until a newline.
struct ThreadLog {
    LogBuffer* buffer = nullptr;
    std::string pending;
    int pending_level = AV_LOG_INFO;
    std::string pending_component;
    bool in_sink = false;  // inside an engine sink's callback or release: lines logged there are dropped
};

thread_local ThreadLog current;

// The caller's engine sink, null for the default. Never destroyed, so that no release runs at exit.
std::shared_ptr<EngineSink>& SinkSlot() {
    static auto* slot = new std::shared_ptr<EngineSink>();
    return *slot;
}

// The installed sink's level, read for every engine line before it is formatted.
std::atomic<int> engine_level{kEngineLevel};

// Drops a sink reference with lines from the current thread blocked: the last one runs the release.
void DropSink(ThreadLog& log, std::shared_ptr<EngineSink>& sink) {
    const bool was_in_sink = log.in_sink;
    log.in_sink = true;
    sink.reset();
    log.in_sink = was_in_sink;
}

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

// Sends a complete engine line to the sink installed now, holding it for the duration of the call.
void SendEngineLine(ThreadLog& log, int level, const std::string& component, const std::string& text) {
    std::shared_ptr<EngineSink> sink = std::atomic_load(&SinkSlot());
    if (sink == nullptr) {
        if (level <= kEngineLevel) WriteEngineLog(level, component, text);
        return;
    }
    if (level <= sink->level) {
        const bool was_in_sink = log.in_sink;
        log.in_sink = true;
        sink->callback(sink->user_data, level, component.c_str(), text.c_str());
        log.in_sink = was_in_sink;
    }
    DropSink(log, sink);
}

void Flush(ThreadLog& log) {
    // Taken out first: whatever runs while the line is sent finds nothing pending to send again.
    std::string text = std::move(log.pending);
    std::string component = std::move(log.pending_component);
    log.pending.clear();
    log.pending_component.clear();
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r' || text.back() == ' ')) text.pop_back();
    if (text.empty()) return;
    if (log.buffer != nullptr) {
        log.buffer->Add(log.pending_level, std::move(component), std::move(text));
    } else {
        SendEngineLine(log, log.pending_level, component, text);
    }
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
    if (log.in_sink) return;  // the sink's own FFmpeg calls; would reenter the line being sent
    const bool wanted = log.buffer != nullptr ? log.buffer->Wants(level)
                                              : level <= engine_level.load(std::memory_order_relaxed);
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

void SetEngineSink(std::shared_ptr<EngineSink> sink) {
    // Level and slot change together, or two threads setting at once could leave one sink's level with
    // the other sink. Never destroyed, like the slot. The release runs outside: it may set again.
    static auto* setting = new std::mutex();
    std::shared_ptr<EngineSink> previous;
    {
        std::lock_guard<std::mutex> lock(*setting);
        // The level first: a line it lets through before the swap reaches the old sink, which filters it.
        engine_level.store(sink != nullptr ? sink->level : kEngineLevel, std::memory_order_relaxed);
        previous = std::atomic_exchange(&SinkSlot(), std::move(sink));
    }
    DropSink(current, previous);
}

bool InEngineSink() {
    return current.in_sink;
}

}  // namespace remux
