// FFmpeg log capture (roadmap 5.2). FFmpeg's log callback is global and does not know which task a
// line belongs to, so each native call marks its thread with a LogScope: lines logged on that thread
// while the scope lives go to the task's LogBuffer. Lines from other threads, or from calls without a
// task, are engine logs and go to logcat (tag FFmpegKit, warnings and errors only).
#pragma once

#include <jni.h>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

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
    std::vector<LogEntry> Entries(int* dropped) const;

private:
    const int level_;
    const int max_entries_;
    mutable std::mutex mutex_;
    std::deque<LogEntry> entries_;
    int dropped_ = 0;
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

// Builds a NativeLog for Kotlin. Texts cross JNI as UTF-8 bytes: FFmpeg's text is not modified UTF-8.
jobject LogToJava(JNIEnv* env, const LogBuffer& buffer);

}  // namespace remux
