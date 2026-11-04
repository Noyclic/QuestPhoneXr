cmake_minimum_required(VERSION 3.22.1)
project(questphonexr_native LANGUAGES CXX)

add_library(questphonexr_native SHARED
    vulkan_probe.cpp
    endxr_runtime_core.cpp
    endxr_runtime_jni.cpp
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

target_compile_features(questphonexr_native PRIVATE cxx_std_17)
target_compile_definitions(questphonexr_native PRIVATE VK_USE_PLATFORM_ANDROID_KHR=1)
target_link_libraries(
    questphonexr_native
    PRIVATE
    ${vulkan-lib}
    ${egl-lib}
    ${gles-lib}
    ${android-log-lib}
    ${dl-lib}
)


# Loader-facing runtime. Kept separate from the app's JNI library because
# OpenXR loads this .so by the filename in Android runtime metadata.
add_library(endxr_openxr SHARED
    endxr_openxr_runtime.cpp
)
target_include_directories(endxr_openxr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/openxr/include
)
target_compile_features(endxr_openxr PRIVATE cxx_std_17)
target_compile_options(endxr_openxr PRIVATE -Wall -Wextra -Werror)
target_link_libraries(endxr_openxr PRIVATE ${egl-lib} ${gles-lib})
target_link_libraries(questphonexr_native PRIVATE endxr_openxr)
set_target_properties(endxr_openxr PROPERTIES
    OUTPUT_NAME "endxr_openxr"
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
