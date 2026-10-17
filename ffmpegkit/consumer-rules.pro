-keep class io.github.nova.ffmpegkit.FFmpegKit { *; }
# JNI symbols are derived from class and method names.
-keepclasseswithmembernames,includedescriptorclasses class io.github.nova.ffmpegkit.** {
    native <methods>;
}
# Constructed from remux.cpp.
-keep class io.github.nova.ffmpegkit.NativeRemuxStatus {
    <init>(int, boolean, int, java.lang.String, long[]);
}
# Constructed from probe.cpp.
-keep class io.github.nova.ffmpegkit.NativeProbeResult {
    <init>(io.github.nova.ffmpegkit.NativeRemuxStatus, long[], java.lang.String[]);
}
