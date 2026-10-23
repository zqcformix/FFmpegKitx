// ffkx_execute's engine: libffmpegkitx_cli (fftools/), loaded on first use and run one command at a time,
// because fftools keep their state in globals. This library only loads it, so that it stays separate:
// LGPL-2.1+ like FFmpeg, and optional.
#include "command.h"

#include <dlfcn.h>
#include <chrono>
#include <memory>
#include <mutex>

#include "fftools/ffkx_fftools.h"

extern "C" {
#include "libavutil/log.h"
}

namespace remux {

namespace {

using RunFunction = int (*)(int, char**, const FfkxFftoolsHooks*);

struct Engine {
    RunFunction run = nullptr;
    std::string error;  // why run is null
};

// Loaded once and never unloaded: like this library, it must stay loaded while any of its threads runs.
const Engine& LoadEngine() {
    static const Engine* const engine = [] {
        auto* loaded = new Engine();
        void* handle = dlopen("libffmpegkitx_cli.so", RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            const char* why = dlerror();
            loaded->error = why != nullptr ? why : "dlopen failed";
        } else {
            loaded->run = reinterpret_cast<RunFunction>(dlsym(handle, "ffkx_fftools_run"));
            if (loaded->run == nullptr) loaded->error = "libffmpegkitx_cli.so has no ffkx_fftools_run";
        }
        return loaded;
    }();
    return *engine;
}

// Held while a command runs. Never destroyed, like the engine.
std::timed_mutex& CommandLock() {
    static auto* lock = new std::timed_mutex();
    return *lock;
}

// How often a command waiting for the lock checks for cancellation.
constexpr auto kLockPoll = std::chrono::milliseconds(20);

// The running command, as its hooks see it.
struct Run {
    Task* task;
    LogBuffer* log;
    Progress progress;
    int64_t bytes_base;  // what earlier operations of the task wrote
    std::string refused;
};

// The log scope of an ffmpeg worker thread, from its start to its end.
thread_local std::unique_ptr<LogScope> worker_scope;

int HookCancelled(void* context) {
    return static_cast<Run*>(context)->task->IsCancelled() ? 1 : 0;
}

void HookThreadStarted(void* context) {
    worker_scope = std::make_unique<LogScope>(static_cast<Run*>(context)->log);
}

void HookThreadEnded(void* /* context */) {
    worker_scope.reset();
}

void HookProgress(void* context, int64_t time_us, int64_t bytes) {
    auto* run = static_cast<Run*>(context);
    run->progress.Report(time_us);
    if (bytes >= 0) run->task->bytes_written.store(run->bytes_base + bytes, std::memory_order_relaxed);
}

void HookRefused(void* context, const char* option) {
    static_cast<Run*>(context)->refused = option;
}

}  // namespace

Status RunCommand(const std::vector<std::string>& arguments, Task* task, LogBuffer* log) {
    const Engine& engine = LoadEngine();
    if (engine.run == nullptr) {
        return Rejected(FFKX_ERROR_INTERNAL, false, "the command-line engine is not available: " + engine.error);
    }
    std::unique_lock<std::timed_mutex> lock(CommandLock(), std::defer_lock);
    while (!lock.try_lock_for(kLockPoll)) {
        if (task->IsCancelled()) return Cancelled();
    }
    if (task->IsCancelled()) return Cancelled();

    // fftools take char**; they get copies.
    std::vector<std::string> copies;
    copies.reserve(arguments.size() + 1);
    copies.emplace_back("ffmpeg");
    copies.insert(copies.end(), arguments.begin(), arguments.end());
    std::vector<char*> argv;
    for (std::string& argument : copies) argv.push_back(argument.data());
    argv.push_back(nullptr);

    Run run{task, log, Progress(task), task->bytes_written.load(std::memory_order_relaxed), {}};
    const FfkxFftoolsHooks hooks{&run, HookCancelled, HookThreadStarted, HookThreadEnded, HookProgress, HookRefused};
    // ffmpeg sets FFmpeg's global log level and flags (-loglevel); they hold for this command only.
    const int level = av_log_get_level();
    const int flags = av_log_get_flags();
    const int ret = engine.run(static_cast<int>(copies.size()), argv.data(), &hooks);
    av_log_set_level(level);
    av_log_set_flags(flags);

    if (task->IsCancelled()) return Cancelled();
    if (!run.refused.empty()) {
        return Rejected(FFKX_ERROR_INVALID_ARGUMENT, false,
                        "option -" + run.refused + " is not available: it would change the whole process");
    }
    if (ret == 0) return {};
    if (ret < 0) return Failure(ret, false, "ffmpeg");
    return Rejected(FFKX_ERROR_FFMPEG, false, "ffmpeg exited with code " + std::to_string(ret));
}

}  // namespace remux
