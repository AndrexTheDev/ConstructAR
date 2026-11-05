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
#   * CONSTRUCTAR_WITH_RTABMAP   — links a BSD-only RTAB-Map build (see docs/SETUP.md §5.1)
cmake_minimum_required(VERSION 3.22.1)
project(constructar_slam LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

option(CONSTRUCTAR_WITH_RTABMAP "Compile the RTAB-Map loop-closure back-end" OFF)
option(CONSTRUCTAR_BUILD_HOST_TESTS "Build the host (non-Android) test binary" OFF)
set(RTABMAP_SOURCE_DIR "" CACHE PATH "Path to an RTAB-Map source checkout (BSD-3-Clause)")

# AprilTag 3.1.0's C core, vendored unmodified (BSD-2-Clause). See third_party/apriltag/
# VENDORED.md for the file list, the provenance and why only tag36h11 is here. It is built as its
# own static library so that `-Werror` - which is a contract for the code this repository writes -
# does not turn a warning in somebody else's source into our build failure, and so that the one
# include path it needs stays with it.
set(APRILTAG_SOURCES
    third_party/apriltag/apriltag.c
    third_party/apriltag/apriltag_quad_thresh.c
    third_party/apriltag/apriltag_pose.c
    third_party/apriltag/tag36h11.c
    third_party/apriltag/common/g2d.c
    third_party/apriltag/common/homography.c
    third_party/apriltag/common/image_u8.c
    third_party/apriltag/common/image_u8x3.c
    third_party/apriltag/common/matd.c
    third_party/apriltag/common/pnm.c
    third_party/apriltag/common/svd22.c
    third_party/apriltag/common/time_util.c
    third_party/apriltag/common/unionfind.c
    third_party/apriltag/common/workerpool.c
    third_party/apriltag/common/zarray.c
    third_party/apriltag/common/zhash.c
    third_party/apriltag/common/zmaxheap.c
)

add_library(apriltag_core STATIC ${APRILTAG_SOURCES})
target_include_directories(apriltag_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/apriltag)
# Upstream assumes POSIX (`strdup`, `random`, `usleep`) and is compiled verbatim: no warning flags
# beyond silencing, because a warning we cannot fix without editing the vendored tree is noise.
target_compile_options(apriltag_core PRIVATE -w)
target_compile_definitions(apriltag_core PRIVATE _GNU_SOURCE)

set(SLAM_SOURCES
    tsdf/volume.cpp
    tsdf/surface_nets.cpp
    slam/pose_graph.cpp
    geometry/manhattan_world.cpp
    calibration/tag_scale.cpp
    calibration/apriltag_detector.cpp
    geometry/plane_fit.cpp
    geometry/floorplan.cpp
    geometry/surface_mask.cpp
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
  target_link_libraries(constructar_host_tests PRIVATE Threads::Threads apriltag_core)
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

# AHardwareBuffer lives in libandroid (there is no `libhardwarebuffer.so` in the NDK
# sysroot), and its use is compile-time guarded to API 26+ in jni/slam_jni.cpp.
target_link_libraries(constructar_slam
    apriltag_core
    ${LOG_LIB}
    ${ANDROID_LIB}
    jnigraphics
)

if(CONSTRUCTAR_WITH_RTABMAP)
  # The layout is detected rather than assumed (finding 110): upstream keeps the headers in
  # `corelib/include`, not `core/include`, and the archives are in `build/bin` only for an
  # in-source build - the recipe in docs/SETUP.md builds out of source. The probe lives in
  # `cmake/rtabmap_layout.cmake` and is exercised by `tests/rtabmap-layout-check.cmake`, which
  # runs without an NDK.
  include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/rtabmap_layout.cmake")
  constructar_locate_rtabmap("${RTABMAP_SOURCE_DIR}"
      CONSTRUCTAR_RTABMAP_INCLUDE CONSTRUCTAR_RTABMAP_LIBDIR CONSTRUCTAR_RTABMAP_PROBLEM)
  if(CONSTRUCTAR_RTABMAP_PROBLEM)
    message(FATAL_ERROR
        "CONSTRUCTAR_WITH_RTABMAP=ON: ${CONSTRUCTAR_RTABMAP_PROBLEM} "
        "See docs/SETUP.md 5.1 for the BSD-only build recipe.")
  endif()
  message(STATUS "ConstructAR: building with RTAB-Map from ${RTABMAP_SOURCE_DIR}")
  target_compile_definitions(constructar_slam PRIVATE CONSTRUCTAR_WITH_RTABMAP=1)
  target_include_directories(constructar_slam PRIVATE
      "${CONSTRUCTAR_RTABMAP_INCLUDE}"
      "${RTABMAP_SOURCE_DIR}/utilite/include"
  )
  # RTAB-Map itself is built as a static library by the developer (docs/SETUP.md 5.1) so this
  # link stays explicit and auditable:
  target_link_libraries(constructar_slam
      "${CONSTRUCTAR_RTABMAP_LIBDIR}/librtabmap_core.a"
      "${CONSTRUCTAR_RTABMAP_LIBDIR}/librtabmap_utilite.a"
      opencv_core opencv_imgproc opencv_features2d
  )
endif()
