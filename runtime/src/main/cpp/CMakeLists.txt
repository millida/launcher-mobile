cmake_minimum_required(VERSION 3.22.1)
project(millida_runtime C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(millida_bridge SHARED
        jvm_host.cpp
        gl_bridge.cpp
        input_queue.cpp
        egl_surface.cpp
        signal_guard.cpp
)

target_include_directories(millida_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)

target_link_libraries(millida_bridge
        ${log-lib}
        ${android-lib}
        ${egl-lib}
        ${gles-lib}
        dl
)

target_compile_options(millida_bridge PRIVATE -Wall -Wextra -Wno-unused-parameter)

# Android 15 devices may run with 16 KB pages; 4 KB aligned LOAD segments do not load there.
target_link_options(millida_bridge PRIVATE "-Wl,-z,max-page-size=16384")

# Plain C keeps it off libc++: SDL dlopens it beside the game's own copies.
add_library(millida_gles SHARED gles_shim.c)

set_target_properties(millida_gles PROPERTIES
        C_STANDARD 11
        C_STANDARD_REQUIRED ON
        C_VISIBILITY_PRESET hidden
)

find_library(gles2-lib GLESv2)

target_link_libraries(millida_gles
        ${log-lib}
        ${egl-lib}
        ${gles2-lib}
        dl
)

target_compile_options(millida_gles PRIVATE -Wall -Wextra -Wno-unused-parameter)

# SDL dlsyms every EGL and GL name on this library; the ones it does not define
# bionic finds only in its direct DT_NEEDED entries, so those must stay there.
target_link_options(millida_gles PRIVATE "-Wl,--no-as-needed" "-Wl,-z,max-page-size=16384")
