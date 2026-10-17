// File system helpers that Java's file APIs do not offer on Android.
#include <jni.h>
#include <cerrno>
#include <dlfcn.h>
#include <fcntl.h>
#include <string>

#include "jni_util.h"

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1 << 0)
#endif

namespace {

using Renameat2 = int (*)(int, const char*, int, const char*, unsigned int);

// libc exports renameat2 from Android 11 (API 30). Earlier releases may block the raw syscall with
// seccomp, which kills the process, so never issue it directly.
Renameat2 LookUpRenameat2() {
    static const auto function = reinterpret_cast<Renameat2>(dlsym(RTLD_DEFAULT, "renameat2"));
    return function;
}

}  // namespace

// Moves `from` to `to` atomically unless `to` exists. Returns 0 or -errno (-ENOSYS without renameat2).
extern "C" JNIEXPORT jint JNICALL
Java_io_github_nova_ffmpegkit_NativeFiles_renameNoReplace(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray from,
        jbyteArray to) {
    const Renameat2 renameat2 = LookUpRenameat2();
    if (renameat2 == nullptr) return -ENOSYS;
    const std::string source = ToUtf8Path(env, from);
    const std::string target = ToUtf8Path(env, to);
    return renameat2(AT_FDCWD, source.c_str(), AT_FDCWD, target.c_str(), RENAME_NOREPLACE) == 0 ? 0 : -errno;
}
