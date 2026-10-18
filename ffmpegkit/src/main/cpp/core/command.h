// The command line behind ffkx_execute (roadmap 6.3): fftools' ffmpeg in libffmpegkitx_cli.
#pragma once

#include <string>
#include <vector>

#include "remux.h"

namespace remux {

// Runs ffmpeg with `arguments` (after the program name) on the calling thread, reporting to `task`.
// `log` receives the lines of ffmpeg's worker threads; null when the caller passed no task, so that they
// are engine logs like the calling thread's. Waits while another command runs, until the task is cancelled.
Status RunCommand(const std::vector<std::string>& arguments, Task* task, LogBuffer* log);

}  // namespace remux
