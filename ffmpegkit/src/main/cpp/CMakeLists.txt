cmake_minimum_required(VERSION 3.22.1)

project("ffmpegkit" C CXX)

# FFmpeg paths
set(FFMPEG_INCLUDE_DIR ${CMAKE_SOURCE_DIR})
set(FFMPEG_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# Import FFmpeg libraries
foreach(library avcodec avformat avfilter avutil swresample swscale)
    add_library(${library} SHARED IMPORTED)
    set_target_properties(${library} PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/lib${library}.so)
endforeach()

# Portable C++ core behind the C ABI (include/ffmpegkitx.h). Exports the ffkx_* functions only.
add_library(ffmpegkitx_core SHARED
    core/ffmpegkitx.cpp
    core/remux.cpp
    core/trim.cpp
    core/split.cpp
    core/probe.cpp
    core/log.cpp
    core/fd_reader.cpp)

target_include_directories(ffmpegkitx_core
    PUBLIC include
    PRIVATE core ${FFMPEG_INCLUDE_DIR})

set_target_properties(ffmpegkitx_core PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    LINK_DEPENDS ${CMAKE_SOURCE_DIR}/core/exports.map)

target_link_options(ffmpegkitx_core PRIVATE -Wl,--version-script=${CMAKE_SOURCE_DIR}/core/exports.map)

target_link_libraries(ffmpegkitx_core PRIVATE
    avcodec
    avformat
    avfilter
    avutil
    swresample
    swscale
    log)

# JNI binding for the Kotlin API. Reaches the core only through include/ffmpegkitx.h; the exceptions
# are errors.cpp (AVERROR constants for ErrorMapping.kt) and inspect.cpp (decodes outputs for tests),
# the only sources that see FFmpeg's headers.
add_library(${CMAKE_PROJECT_NAME} SHARED
    jni/native-lib.cpp
    jni/task.cpp
    jni/operations.cpp
    jni/errors.cpp
    jni/files.cpp
    jni/inspect.cpp)

set_source_files_properties(jni/errors.cpp jni/inspect.cpp PROPERTIES INCLUDE_DIRECTORIES ${FFMPEG_INCLUDE_DIR})

target_link_libraries(${CMAKE_PROJECT_NAME}
    ffmpegkitx_core
    avcodec
    avformat
    avutil
    android
    dl
    log)

# C ABI test program, run on a device by `gradlew :ffmpegkit:connectedCapiTest`; not packaged.
add_executable(ffkx_capi_test test/capi_test.c)
set_target_properties(ffkx_capi_test PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS OFF)
target_compile_options(ffkx_capi_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(ffkx_capi_test PRIVATE ffmpegkitx_core)

# The public header must compile as strict C99 and C++11.
add_library(ffkx_header_check OBJECT test/header_c99.c test/header_cxx11.cpp)
target_include_directories(ffkx_header_check PRIVATE include)
set_source_files_properties(test/header_c99.c PROPERTIES COMPILE_OPTIONS "-std=c99;-pedantic-errors;-Wall;-Wextra;-Werror")
set_source_files_properties(test/header_cxx11.cpp PROPERTIES COMPILE_OPTIONS "-std=c++11;-pedantic-errors;-Wall;-Wextra;-Werror")

# Every build compiles the test program, whose layout pins are static assertions, and the header checks.
add_dependencies(${CMAKE_PROJECT_NAME} ffkx_capi_test ffkx_header_check)

# ASan C ABI test runs (`connectedCapiTest -PcapiTestAsan`): instruments the core and the test program,
# which is then named ffkx_capi_test-asan. Never for a published build. Not HWASan: on a production
# Android 14 device it reports every write to memory the prebuilt FFmpeg allocated, which carries the
# system allocator's pointer tag (0xb4) instead of a HWASan tag.
option(FFKX_ASAN "Instrument libffmpegkitx_core and the C ABI test with AddressSanitizer" OFF)
if(FFKX_ASAN)
    foreach(target ffmpegkitx_core ffkx_capi_test)
        target_compile_options(${target} PRIVATE -fsanitize=address -fno-omit-frame-pointer)
        target_link_options(${target} PRIVATE -fsanitize=address)
    endforeach()
    set_target_properties(ffkx_capi_test PROPERTIES OUTPUT_NAME ffkx_capi_test-asan)
endif()
