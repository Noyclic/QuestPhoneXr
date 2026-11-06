cmake_minimum_required(VERSION 3.20)
project(driver_endvr LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
FetchContent_Declare(
  openvr
  GIT_REPOSITORY https://github.com/ValveSoftware/openvr.git
  # Immutable upstream revision: avoid non-reproducible builds from master.
  GIT_TAG 0924064316de3effbcd1acf1e309182a2deb1c05
)
FetchContent_MakeAvailable(openvr)

add_library(driver_endvr SHARED src/driver.cpp)
target_include_directories(driver_endvr PRIVATE ${openvr_SOURCE_DIR}/headers)

# Valve's OpenVR CMake target is platform-specific on 64-bit Windows.
# Linux builds the target as openvr_api; Windows x64 builds openvr_api64.
if(WIN32 AND CMAKE_SIZEOF_VOID_P EQUAL 8)
  set(ENDVR_OPENVR_TARGET openvr_api64)
else()
  set(ENDVR_OPENVR_TARGET openvr_api)
endif()

if(NOT TARGET ${ENDVR_OPENVR_TARGET})
  message(FATAL_ERROR "Expected OpenVR CMake target '${ENDVR_OPENVR_TARGET}' was not created")
endif()
target_link_libraries(driver_endvr PRIVATE ${ENDVR_OPENVR_TARGET})

if(WIN32)
  set_target_properties(driver_endvr PROPERTIES PREFIX "" OUTPUT_NAME "driver_endvr")
elseif(UNIX AND NOT APPLE)
  target_link_libraries(driver_endvr PRIVATE pthread dl)
  set_target_properties(driver_endvr PROPERTIES PREFIX "" OUTPUT_NAME "driver_endvr")
endif()
