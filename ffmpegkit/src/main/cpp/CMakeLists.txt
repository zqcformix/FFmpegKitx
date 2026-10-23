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
    core/fd_reader.cpp
    core/command.cpp)

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
    dl
    log)

# Command-line engine (roadmap 6.3): FFmpeg 8.0.1's fftools as a library, LGPL-2.1+ like FFmpeg. The core
# loads it on the first ffkx_execute and never links it. See fftools/README.md.
set(FFTOOLS_GENERATED ${CMAKE_CURRENT_BINARY_DIR}/fftools)

# The prebuilt libraries' configure line, which fftools print and compare in the banner.
file(STRINGS ${FFMPEG_LIB_DIR}/libavutil.so FFMPEG_CONFIGURATION REGEX "--target-os=" LIMIT_COUNT 1)
if(NOT FFMPEG_CONFIGURATION)
    message(FATAL_ERROR "No configure line in ${FFMPEG_LIB_DIR}/libavutil.so")
endif()
file(WRITE ${FFTOOLS_GENERATED}/ffkx_build.h
    "#define FFMPEG_CONFIGURATION \"${FFMPEG_CONFIGURATION}\"\n"
    "#define CC_IDENT \"${CMAKE_C_COMPILER_ID} ${CMAKE_C_COMPILER_VERSION}\"\n")

# graph.css and graph.html as C arrays, as FFmpeg's bin2c writes them: NUL-terminated, length without it.
foreach(resource graph.css graph.html)
    set(input ${CMAKE_SOURCE_DIR}/fftools/resources/${resource})
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${input})
    file(READ ${input} hex HEX)
    string(LENGTH "${hex}" hex_length)
    math(EXPR length "${hex_length} / 2")
    string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1, " bytes "${hex}")
    string(REPLACE "." "_" name ${resource})
    file(WRITE ${FFTOOLS_GENERATED}/${resource}.c
        "const unsigned char ff_${name}_data[] = { ${bytes}0x00 };\n"
        "const unsigned int ff_${name}_len = ${length};\n")
endforeach()

add_library(ffmpegkitx_cli SHARED
    fftools/ffkx_fftools.c
    fftools/cmdutils.c
    fftools/ffmpeg.c
    fftools/ffmpeg_dec.c
    fftools/ffmpeg_demux.c
    fftools/ffmpeg_enc.c
    fftools/ffmpeg_filter.c
    fftools/ffmpeg_hw.c
    fftools/ffmpeg_mux.c
    fftools/ffmpeg_mux_init.c
    fftools/ffmpeg_opt.c
    fftools/ffmpeg_sched.c
    fftools/opt_common.c
    fftools/sync_queue.c
    fftools/thread_queue.c
    fftools/graph/graphprint.c
    fftools/textformat/avtextformat.c
    fftools/textformat/tf_compact.c
    fftools/textformat/tf_default.c
    fftools/textformat/tf_flat.c
    fftools/textformat/tf_ini.c
    fftools/textformat/tf_json.c
    fftools/textformat/tf_mermaid.c
    fftools/textformat/tf_xml.c
    fftools/textformat/tw_avio.c
    fftools/textformat/tw_buffer.c
    fftools/textformat/tw_stdout.c
    fftools/resources/resman.c
    ${FFTOOLS_GENERATED}/graph.css.c
    ${FFTOOLS_GENERATED}/graph.html.c)

# fftools/ for config.h; the generated directory for ffkx_build.h; cpp/ for "fftools/...", "compat/..." and
# FFmpeg's headers; compat/stdbit for C23's <stdbit.h>, which the NDK does not have (FFmpeg's fallback).
target_include_directories(ffmpegkitx_cli PRIVATE fftools ${FFTOOLS_GENERATED} ${FFMPEG_INCLUDE_DIR} compat/stdbit)

set_target_properties(ffmpegkitx_cli PROPERTIES
    C_STANDARD 11
    C_EXTENSIONS ON
    C_VISIBILITY_PRESET hidden
    LINK_DEPENDS ${CMAKE_SOURCE_DIR}/fftools/exports.map)

# FFmpeg's own build silences these for the same code.
target_compile_options(ffmpegkitx_cli PRIVATE
    -Wno-deprecated-declarations -Wno-pointer-sign -Wno-parentheses -Wno-switch -Wno-unused-function)

target_link_options(ffmpegkitx_cli PRIVATE -Wl,--version-script=${CMAKE_SOURCE_DIR}/fftools/exports.map)

target_link_libraries(ffmpegkitx_cli PRIVATE
    avcodec
    avformat
    avfilter
    avutil
    swresample
    swscale
    m)

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
