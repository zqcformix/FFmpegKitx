cmake_minimum_required(VERSION 3.22.1)

project("ffmpegkit")

# FFmpeg paths
set(FFMPEG_INCLUDE_DIR ${CMAKE_SOURCE_DIR})
set(FFMPEG_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# Import FFmpeg libraries
add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavformat.so)

add_library(avfilter SHARED IMPORTED)
set_target_properties(avfilter PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavfilter.so)

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavutil.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswresample.so)

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswscale.so)

add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    errors.cpp
    trim.cpp
    inspect.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${FFMPEG_INCLUDE_DIR})

target_link_libraries(${CMAKE_PROJECT_NAME}
    avcodec
    avformat
    avfilter
    avutil
    swresample
    swscale
    android
    log)
