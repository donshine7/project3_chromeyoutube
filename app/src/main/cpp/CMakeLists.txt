cmake_minimum_required(VERSION 3.22.1)

project("project3_whisper_jni")

set(BUILD_SHARED_LIBS OFF)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_FFMPEG OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/whisper.cpp EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE third_party/whisper.cpp/include)

find_library(log-lib log)

target_link_libraries(whisper_jni whisper ${log-lib})
