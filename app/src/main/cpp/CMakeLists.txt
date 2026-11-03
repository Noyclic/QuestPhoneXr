cmake_minimum_required(VERSION 3.22.1)
project(questphonexr_vulkan_probe LANGUAGES CXX)

find_package(OpenXR REQUIRED CONFIG)

add_library(questphonexr_vulkan_probe SHARED
    vulkan_probe.cpp
    openxr_loader_probe.cpp
    endxr_runtime_core.cpp
)

find_library(android-log-lib log)
find_library(dl-lib dl)
find_library(vulkan-lib vulkan)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)

if(NOT vulkan-lib)
    message(FATAL_ERROR "Android Vulkan loader library was not found in the NDK sysroot")
endif()
if(NOT egl-lib)
    message(FATAL_ERROR "Android EGL library was not found in the NDK sysroot")
endif()
if(NOT gles-lib)
    message(FATAL_ERROR "Android OpenGL ES 3 library was not found in the NDK sysroot")
endif()
if(NOT dl-lib)
    message(FATAL_ERROR "Android dynamic linker library was not found in the NDK sysroot")
endif()

target_compile_features(questphonexr_vulkan_probe PRIVATE cxx_std_17)
target_compile_definitions(questphonexr_vulkan_probe PRIVATE VK_USE_PLATFORM_ANDROID_KHR=1 XR_USE_PLATFORM_ANDROID=1 XR_USE_GRAPHICS_API_OPENGL_ES=1)
target_link_libraries(questphonexr_vulkan_probe PRIVATE ${vulkan-lib} ${egl-lib} ${gles-lib} ${android-log-lib} ${dl-lib} OpenXR::openxr_loader)
