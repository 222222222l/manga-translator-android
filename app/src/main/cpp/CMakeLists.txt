cmake_minimum_required(VERSION 3.22.1)
project(minicpm_v_jni)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libs" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build common utils" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "Disable OpenSSL for Android native build" FORCE)

add_subdirectory(llama.cpp)
add_subdirectory(llama.cpp/tools/mtmd llama.cpp-tools-mtmd EXCLUDE_FROM_ALL)

add_library(minicpm_v_jni SHARED
    minicpm_v_jni.cpp
)

target_include_directories(minicpm_v_jni PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
    llama.cpp/tools/mtmd
)

target_link_libraries(minicpm_v_jni
    log
    android
    mtmd
    llama
    ggml
)
