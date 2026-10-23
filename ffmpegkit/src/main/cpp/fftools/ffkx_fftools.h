/*
 * FFmpegKitx: FFmpeg 8.0.1's ffmpeg command line as a library (libffmpegkitx_cli, roadmap 6.3).
 *
 * libffmpegkitx_core loads this library on the first ffkx_execute, runs one command at a time and passes
 * hooks that tie the run to its task. ffkx_fftools_run is the library's only export. The rest of this
 * header is for the fftools sources, whose changes are marked "FFKX".
 *
 * Part of FFmpegKitx's command-line engine, licensed like fftools under the GNU Lesser General Public
 * License version 2.1 or later.
 */
#ifndef FFKX_FFTOOLS_H
#define FFKX_FFTOOLS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* What a run tells its caller, and asks it. Called on the run's threads while ffkx_fftools_run lasts. */
typedef struct FfkxFftoolsHooks {
    void* context;
    /* Nonzero once the run is to stop: ffmpeg then finishes its outputs, as after 'q'. Any thread. */
    int (*cancelled)(void* context);
    /* A worker thread of the run starts or ends; the FFmpeg lines it logs in between belong to the run. */
    void (*thread_started)(void* context);
    void (*thread_ended)(void* context);
    /* Output time written so far (microseconds) and the first output's size in bytes. */
    void (*progress)(void* context, int64_t time_us, int64_t bytes);
    /* An option this library refuses (it would change the whole process, or prints help); the run then
     * returns without doing anything. */
    void (*refused)(void* context, const char* option);
} FfkxFftoolsHooks;

/*
 * Runs ffmpeg with argv[0..argc), argv[0] being the program name, and returns what its main() returns:
 * 0, an AVERROR code, or 1, 69 or 255. Never two at a time: fftools keep their state in globals.
 */
__attribute__((visibility("default"))) int ffkx_fftools_run(int argc, char** argv, const FfkxFftoolsHooks* hooks);

/* ---- For the fftools sources ------------------------------------------------------------------- */

struct OptionDef;

/* ffmpeg's main(), renamed. */
int ffkx_ffmpeg_main(int argc, char** argv);

/* After a cancellation, how long finishing the outputs may take before their I/O is interrupted too. */
#define FFKX_CANCEL_GRACE_US 3000000
/* The main loop wakes up at least this often to see a cancellation and report progress. */
#define FFKX_POLL_US 100000

int ffkx_fftools_cancelled(void);
/* Nonzero once the run was cancelled at least `us` ago (counted from when fftools first saw it). */
int ffkx_fftools_cancelled_for(int64_t us);
void ffkx_fftools_thread_started(void);
void ffkx_fftools_thread_ended(void);
void ffkx_fftools_progress(int64_t time_us, int64_t bytes);

/* AVERROR(EINVAL) if argv holds an option the library refuses, after reporting it; else 0. */
int ffkx_fftools_refuse_options(int argc, char** argv, const struct OptionDef* options);

/* cmdutils.c: the first of names[] given as an option in argv, else NULL. Like locate_option, it knows
 * which options take a value; unlike it, "-noX" (negating a boolean X) is not X. */
const char* ffkx_locate_options(int argc, char** argv, const struct OptionDef* options, const char* const* names,
                                int nb_names);

/* Each changed source file puts its global state back to how the program starts. */
void ffkx_reset_ffmpeg(void);
void ffkx_reset_ffmpeg_opt(void);
void ffkx_reset_cmdutils(void);
void ffkx_reset_opt_common(void);
void ffkx_reset_ffmpeg_hw(void);
void ffkx_reset_ffmpeg_mux_init(void);

#ifdef __cplusplus
}
#endif

#endif /* FFKX_FFTOOLS_H */
