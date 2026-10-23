/*
 * FFmpegKitx: configuration for building FFmpeg 8.0.1's fftools (the ffmpeg command line) as a library
 * on Android, against the prebuilt FFmpeg libraries. FFmpeg's configure generates this file for the
 * whole source tree; fftools and the private libavutil headers they include (thread.h, libm.h,
 * getenv_utf8.h) only use the macros below. Values describe Android's bionic libc and the prebuilt
 * build (--disable-avdevice, no GPL, version 3 or nonfree components).
 *
 * This file is part of FFmpegKitx's command-line engine and, like fftools, licensed under the
 * GNU Lesser General Public License version 2.1 or later.
 */
#ifndef FFKX_FFTOOLS_CONFIG_H
#define FFKX_FFTOOLS_CONFIG_H

/* FFMPEG_CONFIGURATION (the prebuilt libraries' configure line, read from libavutil.so by CMake) and
 * CC_IDENT (this compiler). */
#include "ffkx_build.h"

/* fftools run inside an app: no signal handlers, terminal changes, stdin or process-wide options.
 * Changes to the upstream sources are marked "FFKX". */
#define FFKX_EMBEDDED 1

#define FFMPEG_LICENSE "LGPL version 2.1 or later"
#define CONFIG_THIS_YEAR 2025

/* Where -vpre and -fpre presets are looked up, as configure's default --datadir; besides $HOME/.ffmpeg
 * and the FFMPEG_DATADIR and AVCONV_DATADIR environment variables. */
#define FFMPEG_DATADIR "/usr/local/share/ffmpeg"
#define AVCONV_DATADIR "/usr/local/share/ffmpeg"

#define CONFIG_AVUTIL 1
#define CONFIG_AVCODEC 1
#define CONFIG_AVFORMAT 1
#define CONFIG_AVDEVICE 0
#define CONFIG_AVFILTER 1
#define CONFIG_SWSCALE 1
#define CONFIG_SWRESAMPLE 1
#define CONFIG_GPL 0
#define CONFIG_GPLV3 0
#define CONFIG_LGPLV3 0
#define CONFIG_NONFREE 0
#define CONFIG_VAAPI 0
#define CONFIG_QSV 0
#define CONFIG_RESOURCE_COMPRESSION 0

/* POSIX, as on Android. */
#define HAVE_UNISTD_H 1
#define HAVE_TERMIOS_H 1
#define HAVE_SYS_RESOURCE_H 1
#define HAVE_SYS_SELECT_H 1
#define HAVE_GETRUSAGE 1
#define HAVE_STRUCT_RUSAGE_RU_MAXRSS 1
/* -timelimit is refused (setrlimit would limit the app's CPU time); this keeps it inert regardless. */
#define HAVE_SETRLIMIT 0
#define HAVE_GETENV 1
#define HAVE_PTHREADS 1
#define HAVE_PRCTL 1
#define HAVE_PTHREAD_SETNAME_NP 1
#define HAVE_PTHREAD_SET_NAME_NP 0
#define HAVE_PTHREAD_NP_H 0
#define HAVE_W32THREADS 0
#define HAVE_OS2THREADS 0

/* Windows only. */
#define HAVE_KBHIT 0
#define HAVE_IO_H 0
#define HAVE_SETCONSOLECTRLHANDLER 0
#define HAVE_GETMODULEHANDLE 0
#define HAVE_COMMANDLINETOARGVW 0
#define HAVE_GETPROCESSTIMES 0
#define HAVE_GETPROCESSMEMORYINFO 0
#define HAVE_SETDLLDIRECTORY 0
#define HAVE_PEEKNAMEDPIPE 0
#define HAVE_GETSTDHANDLE 0

/* C99 math, which bionic has in full: libavutil/libm.h then defines no fallbacks. */
#define HAVE_ATAN2F 1
#define HAVE_ATANF 1
#define HAVE_CBRT 1
#define HAVE_CBRTF 1
#define HAVE_COPYSIGN 1
#define HAVE_COSF 1
#define HAVE_ERF 1
#define HAVE_EXP2 1
#define HAVE_EXP2F 1
#define HAVE_EXPF 1
#define HAVE_HYPOT 1
#define HAVE_ISFINITE 1
#define HAVE_ISINF 1
#define HAVE_ISNAN 1
#define HAVE_LDEXPF 1
#define HAVE_LLRINT 1
#define HAVE_LLRINTF 1
#define HAVE_LOG10F 1
#define HAVE_LOG2 1
#define HAVE_LOG2F 1
#define HAVE_LRINT 1
#define HAVE_LRINTF 1
#define HAVE_POWF 1
#define HAVE_RINT 1
#define HAVE_ROUND 1
#define HAVE_ROUNDF 1
#define HAVE_SINF 1
#define HAVE_TRUNC 1
#define HAVE_TRUNCF 1
#define HAVE_MIPSFPU 0
#define HAVE_INLINE_ASM 0

/* libavcodec/mathops.h (for mid_pred) then uses its portable C versions on every ABI. */
#define ARCH_ARM 0
#define ARCH_MIPS 0
#define ARCH_PPC 0
#define ARCH_X86 0

#endif /* FFKX_FFTOOLS_CONFIG_H */
