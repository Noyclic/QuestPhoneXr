cmake_minimum_required(VERSION 3.22.1)
project(questphonexr_vulkan_probe LANGUAGES CXX)

find_package(OpenXR REQUIRED CONFIG)

add_library(questphonexr_vulkan_probe SHARED vulkan_probe.cpp openxr_loader_probe.cpp)

find_library(android-log-lib log)
find_library(vulkan-lib vulkan)

if(NOT vulkan-lib)
    message(FATAL_ERROR "Android Vulkan loader library was not found in the NDK sysroot")
endif()

target_compile_features(questphonexr_vulkan_probe PRIVATE cxx_std_17)
target_compile_definitions(questphonexr_vulkan_probe PRIVATE VK_USE_PLATFORM_ANDROID_KHR=1 XR_USE_PLATFORM_ANDROID=1)
target_link_libraries(questphonexr_vulkan_probe PRIVATE ${vulkan-lib} ${android-log-lib} OpenXR::openxr_loader)
