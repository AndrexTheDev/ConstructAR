# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary


# ConstructAR — :native-slam NDK build.
#
# Produces libconstructar_slam.so: sparse TSDF + Surface Nets meshing, SE(3) pose-graph
# optimisation, the optional RTAB-Map back-end and the JNI surface used by
# com.constructar.nativebridge.NativeSlamEngine.
#
# Two build flavours:
#   * default                    — self-contained (no external C++ dependencies at all)
#   * CONSTRUCTAR_WITH_RTABMAP   — links a BSD-only RTAB-Map build (see docs/SETUP.md §3)
cmake_minimum_required(VERSION 3.22.1)
project(constructar_slam LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(CONSTRUCTAR_WITH_RTABMAP "Compile the RTAB-Map loop-closure back-end" OFF)
option(CONSTRUCTAR_BUILD_HOST_TESTS "Build the host (non-Android) test binary" OFF)
set(RTABMAP_SOURCE_DIR "" CACHE PATH "Path to an RTAB-Map source checkout (BSD-3-Clause)")

set(SLAM_SOURCES
    tsdf/volume.cpp
    tsdf/surface_nets.cpp
    slam/pose_graph.cpp
    slam/rtabmap_bridge.cpp
)

if(CONSTRUCTAR_BUILD_HOST_TESTS)
  add_executable(constructar_host_tests
      ${SLAM_SOURCES}
      tests/host_tests.cpp
  )
  target_include_directories(constructar_host_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_compile_options(constructar_host_tests PRIVATE -O2 -Wall -Wextra -Werror)
  find_package(Threads REQUIRED)
  target_link_libraries(constructar_host_tests PRIVATE Threads::Threads)
  return()
endif()

add_library(constructar_slam SHARED
    ${SLAM_SOURCES}
    jni/slam_jni.cpp
)

target_include_directories(constructar_slam PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# -O2 without -ffast-math: geometric accuracy is the product, and reassociation is not worth
# the last few percent of throughput. `-Werror` is part of the build contract - a warning here
# fails the build exactly as a Kotlin warning does (`allWarningsAsErrors`).
#
# `-Wno-unused-parameter` is the one suppression: JNI entry points must declare `JNIEnv*` and
# `jobject` even where a particular function does not use them, and the signature is fixed by
# the ABI rather than by us.
target_compile_options(constructar_slam PRIVATE
    -O2
    -fno-exceptions
    -fno-rtti
    -Wall
    -Wextra
    -Werror
    -Wno-unused-parameter
)

find_library(ANDROID_LIB android)
find_library(LOG_LIB log)
find_library(HARDWARE_BUFFER_LIB hardwarebuffer)

target_link_libraries(constructar_slam
    ${LOG_LIB}
    ${ANDROID_LIB}
    ${HARDWARE_BUFFER_LIB}
    jnigraphics
)

if(CONSTRUCTAR_WITH_RTABMAP)
  if(NOT RTABMAP_SOURCE_DIR OR NOT EXISTS "${RTABMAP_SOURCE_DIR}/core/include/rtabmap/core/Rtabmap.h")
    message(FATAL_ERROR
        "CONSTRUCTAR_WITH_RTABMAP=ON requires -DRTABMAP_SOURCE_DIR=<rtabmap checkout>. "
        "See docs/SETUP.md §3 for the BSD-only build recipe.")
  endif()
  message(STATUS "ConstructAR: building with RTAB-Map from ${RTABMAP_SOURCE_DIR}")
  target_compile_definitions(constructar_slam PRIVATE CONSTRUCTAR_WITH_RTABMAP=1)
  target_include_directories(constructar_slam PRIVATE
      "${RTABMAP_SOURCE_DIR}/core/include"
      "${RTABMAP_SOURCE_DIR}/utilite/include"
  )
  # RTAB-Map itself is built as a static library by the developer (docs/SETUP.md §3) so this
  # link stays explicit and auditable:
  target_link_libraries(constructar_slam
      "${RTABMAP_SOURCE_DIR}/build/bin/librtabmap_core.a"
      "${RTABMAP_SOURCE_DIR}/build/bin/librtabmap_utilite.a"
      opencv_core opencv_imgproc opencv_features2d
  )
endif()
