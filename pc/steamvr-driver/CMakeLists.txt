cmake_minimum_required(VERSION 3.20)
project(driver_endvr LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
FetchContent_Declare(
  openvr
  GIT_REPOSITORY https://github.com/ValveSoftware/openvr.git
  GIT_TAG master
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(openvr)

add_library(driver_endvr SHARED src/driver.cpp)
target_include_directories(driver_endvr PRIVATE ${openvr_SOURCE_DIR}/headers)
target_link_libraries(driver_endvr PRIVATE openvr_api)

if(WIN32)
  set_target_properties(driver_endvr PROPERTIES PREFIX "" OUTPUT_NAME "driver_endvr")
elseif(UNIX AND NOT APPLE)
  target_link_libraries(driver_endvr PRIVATE pthread dl)
  set_target_properties(driver_endvr PROPERTIES PREFIX "" OUTPUT_NAME "driver_endvr")
endif()
