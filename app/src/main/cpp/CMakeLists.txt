cmake_minimum_required(VERSION 3.22.1)
project(minicpm_v_jni)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libs" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Build common utils" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "Disable OpenSSL for Android native build" FORCE)

add_subdirectory(llama.cpp)

find_package(Threads REQUIRED)

set(MTMD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/tools/mtmd)
file(GLOB MTMD_SOURCES CONFIGURE_DEPENDS
    "${MTMD_DIR}/*.cpp"
    "${MTMD_DIR}/models/*.cpp"
)
list(REMOVE_ITEM MTMD_SOURCES
    "${MTMD_DIR}/mtmd-cli.cpp"
    "${MTMD_DIR}/deprecation-warning.cpp"
)

add_library(mtmd STATIC ${MTMD_SOURCES})
target_link_libraries(mtmd PUBLIC ggml llama PRIVATE Threads::Threads)
target_include_directories(mtmd
    PUBLIC ${MTMD_DIR}
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/vendor
)
target_compile_features(mtmd PRIVATE cxx_std_17)
if (NOT MSVC)
    target_compile_options(mtmd PRIVATE -Wno-cast-qual)
endif()
if (ANDROID)
    target_compile_options(mtmd PRIVATE -Wno-missing-prototypes)
endif()

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
