// FFmpeg log capture (roadmap 5.2). FFmpeg's log callback is global and does not know which task a
// line belongs to, so each operation marks its thread with a LogScope: lines logged on that thread
// while the scope lives go to the task's LogBuffer. Lines from other threads, or from operations without
// a task, are engine logs: they go to the caller's EngineSink, or by default warnings and errors go to
// logcat (tag FFmpegKit) on Android, else to stderr.
#pragma once

#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "ffmpegkitx.h"

namespace remux {

struct LogEntry {
    int level;  // AV_LOG_*
    std::string component;  // the logging context's class item name, e.g. "mov,mp4,m4a,3gp,3g2,mj2"; may be empty
    std::string message;
};

// The most recent entries of one task, at its level or more severe. Bounded: once full, each new
// entry drops the oldest, so a task never holds more than max_entries entries of kMaxMessageBytes.
class LogBuffer {
public:
    static constexpr size_t kMaxMessageBytes = 1024;

    LogBuffer(int level, int max_entries) : level_(level), max_entries_(max_entries) {}

    bool Wants(int level) const { return level <= level_; }
    void Add(int level, std::string component, std::string message);

    // A copy of the entries, oldest first, and how many were dropped.
    std::vector<LogEntry> Entries(int64_t* dropped) const;

private:
    const int level_;
    const int max_entries_;
    mutable std::mutex mutex_;
    std::deque<LogEntry> entries_;
    int64_t dropped_ = 0;
};

// Routes FFmpeg logs from the current thread to `buffer` (null: engine logs) until destroyed.
class LogScope {
public:
    explicit LogScope(LogBuffer* buffer);
    ~LogScope();
    LogScope(const LogScope&) = delete;
    LogScope& operator=(const LogScope&) = delete;

private:
    LogBuffer* const previous_;
};

// Installs the log callback; called once when the library is loaded, before any FFmpeg call can log.
void InstallLogCallback();

// A caller's engine log sink (ffkx_set_engine_log_sink). Each line holds a reference while it calls the
// callback, so the destructor, which releases the caller's data, runs after the last call returned.
struct EngineSink {
    int level;
    ffkx_log_callback callback;
    void* user_data;
    void (*release)(void* user_data);

    EngineSink(int level, ffkx_log_callback callback, void* user_data, void (*release)(void*))
        : level(level), callback(callback), user_data(user_data), release(release) {}
    EngineSink(const EngineSink&) = delete;
    EngineSink& operator=(const EngineSink&) = delete;
    ~EngineSink() {
        if (release != nullptr) release(user_data);
    }
};

// Replaces the engine log sink; null restores the default. The previous sink is released here if no
// thread is using it, else by the last thread that is.
void SetEngineSink(std::shared_ptr<EngineSink> sink);

// Whether the current thread is inside an engine sink's callback or release, where operations must not run.
bool InEngineSink();

}  // namespace remux
