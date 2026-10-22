# JamesDSP native engine for BitChord.
#
# BitChord already owns the decoded Float32 stream before it reaches Media3's
# AudioTrack sink (see DspChain / PrecisionAudioSink). That means JamesDSP can
# run in-process on exactly BitChord's own playback, with no root, no
# AudioRecord capture and no effect on other apps' audio.
#
# The engine is built into a static library and linked into our JNI shim, so
# the APK ships one self-contained .so rather than a second library that has
# to be discovered by the dynamic linker.
cmake_minimum_required(VERSION 3.22.1)

file(GLOB_RECURSE JAMESDSP_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/*.c")
list(APPEND JAMESDSP_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/cpthread.c")

add_library(jamesdsp STATIC ${JAMESDSP_SOURCES})
set_target_properties(jamesdsp PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(jamesdsp PRIVATE
    -O3
    -ffast-math
    -std=gnu11
    -fvisibility=hidden
    # The engine predates Clang 18's stricter C type checking. Rootless
    # JamesDSP builds it with an older NDK; these are the same source-level
    # patterns, not new bugs in the DSP math.
    -Wno-incompatible-function-pointer-types
    -Wno-incompatible-pointer-types
    -Wno-int-conversion
    -Wno-implicit-function-declaration
    -Wno-deprecated-non-prototype
    -Wno-error=implicit-int)
target_include_directories(jamesdsp PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp")

add_library(bitchord_jamesdsp SHARED
    jni/JamesDspJni.cpp)
target_compile_options(bitchord_jamesdsp PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden)
target_link_options(bitchord_jamesdsp PRIVATE
    "-Wl,-z,max-page-size=16384")

find_library(jamesdsp_log-lib log)
target_link_libraries(bitchord_jamesdsp jamesdsp ${jamesdsp_log-lib})
