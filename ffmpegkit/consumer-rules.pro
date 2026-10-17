-keep class io.github.nova.ffmpegkit.FFmpegKit { *; }
# JNI symbols are derived from class and method names.
-keepclasseswithmembernames,includedescriptorclasses class io.github.nova.ffmpegkit.** {
    native <methods>;
}
