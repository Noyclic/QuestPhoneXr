cmake_minimum_required(VERSION 3.22.1)
project(openxr_loader_probe LANGUAGES CXX)

find_package(OpenXR REQUIRED CONFIG)

find_library(android-lib android)
find_library(log-lib log)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)

if(NOT android-lib)
    message(FATAL_ERROR "Android NDK android library was not found")
endif()
if(NOT log-lib)
    message(FATAL_ERROR "Android log library was not found")
endif()
if(NOT egl-lib)
    message(FATAL_ERROR "Android EGL library was not found")
endif()
if(NOT gles-lib)
    message(FATAL_ERROR "Android OpenGL ES 3 library was not found")
endif()

add_library(openxr_loader_probe SHARED
    openxr_loader_probe.cpp
)

target_compile_features(openxr_loader_probe PRIVATE cxx_std_17)
target_compile_definitions(openxr_loader_probe PRIVATE XR_USE_PLATFORM_ANDROID=1 XR_USE_GRAPHICS_API_OPENGL_ES=1)
target_compile_options(openxr_loader_probe PRIVATE -Wall -Wextra -Werror)

target_link_libraries(
    openxr_loader_probe
    PRIVATE
    OpenXR::openxr_loader
    ${android-lib}
    ${log-lib}
    ${egl-lib}
    ${gles-lib}
)
