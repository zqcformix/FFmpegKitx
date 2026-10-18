-keep class io.github.nova.ffmpegkit.FFmpegKit { *; }
# JNI symbols are derived from class and method names.
-keepclasseswithmembernames,includedescriptorclasses class io.github.nova.ffmpegkit.** {
    native <methods>;
}
# Constructed from jni/operations.cpp.
-keep class io.github.nova.ffmpegkit.NativeRemuxStatus {
    <init>(int, int, int, byte[], long[]);
}
-keep class io.github.nova.ffmpegkit.NativeProbeResult {
    <init>(io.github.nova.ffmpegkit.NativeRemuxStatus, long[], java.lang.String[]);
}
# Constructed from jni/task.cpp.
-keep class io.github.nova.ffmpegkit.NativeLog {
    <init>(int[], byte[][], byte[][], long);
}
