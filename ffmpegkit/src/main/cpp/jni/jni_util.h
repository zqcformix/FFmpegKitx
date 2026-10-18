#pragma once

#include <jni.h>
#include <string>

// Paths arrive as UTF-8 bytes: JNI's GetStringUTFChars returns modified UTF-8, which encodes
// characters outside the BMP (such as emoji) differently from what the file system expects.
inline std::string ToUtf8Path(JNIEnv* env, jbyteArray bytes) {
    const jsize length = env->GetArrayLength(bytes);
    std::string path(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(path.data()));
    return path;
}
