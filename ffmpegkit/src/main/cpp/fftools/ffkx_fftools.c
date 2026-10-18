/*
 * FFmpegKitx: runs fftools' ffmpeg inside the library; see ffkx_fftools.h.
 *
 * Part of FFmpegKitx's command-line engine, licensed like fftools under the GNU Lesser General Public
 * License version 2.1 or later.
 */
#include "config.h"

#include <stdatomic.h>
#include <stddef.h>

#include "libavutil/error.h"
#include "libavutil/log.h"
#include "libavutil/time.h"

#include "fftools/cmdutils.h"
#include "fftools/ffkx_fftools.h"

/* The running command's hooks. ffkx_fftools_run is never called twice at once. */
static const FfkxFftoolsHooks* current;

/* When fftools first saw the run cancelled (av_gettime_relative), 0 before. */
static _Atomic int64_t cancelled_at;

/*
 * Options that change the whole process, so another operation or command would see the change, or that
 * print help and exit. -report and the FFREPORT environment variable would replace FFmpeg's log
 * callback, through which the library captures each operation's log; -timelimit sets the process's CPU
 * limit (setrlimit), whose SIGXCPU would kill the app long after the command.
 */
static const char* const refused_options[] = {
    "report", "cpuflags", "cpucount", "max_alloc", "stdin", "timelimit",
    "L", "h", "?", "help", "-help", "version", "buildconf",
    "formats", "muxers", "demuxers", "devices", "codecs", "decoders", "encoders", "bsfs", "protocols",
    "filters", "pix_fmts", "layouts", "sample_fmts", "dispositions", "colors", "sources", "sinks", "hwaccels",
};

static void reset_all(void)
{
    ffkx_reset_ffmpeg();
    ffkx_reset_ffmpeg_opt();
    ffkx_reset_cmdutils();
    ffkx_reset_opt_common();
    ffkx_reset_ffmpeg_hw();
    ffkx_reset_ffmpeg_mux_init();
}

int ffkx_fftools_run(int argc, char** argv, const FfkxFftoolsHooks* hooks)
{
    int ret;
    current = hooks;
    atomic_store(&cancelled_at, 0);
    reset_all();
    ret = ffkx_ffmpeg_main(argc, argv);
    reset_all(); /* the cleanup freed what these point to */
    current = NULL;
    return ret;
}

int ffkx_fftools_cancelled(void)
{
    int64_t expected = 0;
    if (!(current && current->cancelled && current->cancelled(current->context)))
        return 0;
    atomic_compare_exchange_strong(&cancelled_at, &expected, av_gettime_relative());
    return 1;
}

int ffkx_fftools_cancelled_for(int64_t us)
{
    return ffkx_fftools_cancelled() && av_gettime_relative() - atomic_load(&cancelled_at) >= us;
}

void ffkx_fftools_thread_started(void)
{
    if (current && current->thread_started)
        current->thread_started(current->context);
}

void ffkx_fftools_thread_ended(void)
{
    if (current && current->thread_ended)
        current->thread_ended(current->context);
}

void ffkx_fftools_progress(int64_t time_us, int64_t bytes)
{
    if (current && current->progress)
        current->progress(current->context, time_us, bytes);
}

int ffkx_fftools_refuse_options(int argc, char** argv, const struct OptionDef* options)
{
    const int count = sizeof(refused_options) / sizeof(refused_options[0]);
    const char* option = ffkx_locate_options(argc, argv, options, refused_options, count);
    if (!option)
        return 0;
    av_log(NULL, AV_LOG_FATAL, "Option -%s is not available in FFmpegKitx: it would change the whole "
           "process\n", option);
    if (current && current->refused)
        current->refused(current->context, option);
    return AVERROR(EINVAL);
}
