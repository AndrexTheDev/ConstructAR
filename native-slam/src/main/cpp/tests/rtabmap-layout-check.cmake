# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
#
# Script-mode test for cmake/rtabmap_layout.cmake (finding 110).
#
#   cmake -P native-slam/src/main/cpp/tests/rtabmap-layout-check.cmake
#
# The optional RTAB-Map back-end is switched on with CONSTRUCTAR_WITH_RTABMAP=ON and a path to a
# checkout. Until now that switch could never be satisfied: the guard looked for
# `<checkout>/core/include/rtabmap/core/Rtabmap.h`, while upstream keeps it in `corelib/include`,
# so a developer who had just built RTAB-Map exactly as docs/SETUP.md prescribes was told to go and
# build it. The archives had the same problem for the documented out-of-source build. Neither
# mistake can be caught by a compiler, because both are paths, so they are checked here against
# fake trees that mirror the two real layouts.
#
# Needs nothing but `cmake` - no NDK, no toolchain, no RTAB-Map checkout.

include("${CMAKE_CURRENT_LIST_DIR}/../cmake/rtabmap_layout.cmake")

set(root "${CMAKE_CURRENT_LIST_DIR}/../../../../../build/rtabmap-layout")
file(REMOVE_RECURSE "${root}")

set(RUN_OK 0)
set(RUN_FAIL 0)
set(RUN_ALL 0)

function(run_case label source_dir expected_include expected_lib_dir expected_problem)
    constructar_locate_rtabmap("${source_dir}" got_include got_lib_dir got_problem)
    # Compare canonical paths: the module returns REALPATHs, the fixtures are built with `../` in
    # them on purpose (the test lives four directories deep).
    foreach(pair "expected_include" "expected_lib_dir")
        if(${pair})
            get_filename_component(canonical "${${pair}}" REALPATH)
            set(${pair} "${canonical}")
        endif()
    endforeach()
    set(ok TRUE)
    if(NOT got_include STREQUAL expected_include)
        set(ok FALSE)
        message(STATUS "  ${label}: include '${got_include}' expected '${expected_include}'")
    endif()
    if(NOT got_lib_dir STREQUAL expected_lib_dir)
        set(ok FALSE)
        message(STATUS "  ${label}: archives '${got_lib_dir}' expected '${expected_lib_dir}'")
    endif()
    if(expected_problem)
        if(NOT got_problem MATCHES "${expected_problem}")
            set(ok FALSE)
            message(STATUS "  ${label}: problem '${got_problem}' does not match "
                           "'${expected_problem}'")
        endif()
    elseif(got_problem)
        set(ok FALSE)
        message(STATUS "  ${label}: unexpected problem '${got_problem}'")
    endif()
    if(ok)
        message(STATUS "  ok   ${label}")
        math(EXPR n "${RUN_OK} + 1")
        set(RUN_OK ${n} PARENT_SCOPE)
    else()
        message(STATUS "  FAIL ${label}")
        math(EXPR n "${RUN_FAIL} + 1")
        set(RUN_FAIL ${n} PARENT_SCOPE)
    endif()
    math(EXPR n "${RUN_ALL} + 1")
    set(RUN_ALL ${n} PARENT_SCOPE)
endfunction()

# 1. Upstream layout, built in-source: `corelib/include` + `build/bin`, which is what a plain
#    `cmake -S rtabmap -B rtabmap && cmake --build rtabmap` leaves behind.
set(in_source "${root}/in-source")
file(MAKE_DIRECTORY "${in_source}/corelib/include/rtabmap/core")
file(WRITE "${in_source}/corelib/include/rtabmap/core/Rtabmap.h" "// stub\n")
file(MAKE_DIRECTORY "${in_source}/utilite/include/rtabmap/utilite")
file(MAKE_DIRECTORY "${in_source}/build/bin")
file(WRITE "${in_source}/build/bin/librtabmap_core.a" "")
file(WRITE "${in_source}/build/bin/librtabmap_utilite.a" "")

# 2. The out-of-source build docs/SETUP.md prescribes (`-B rtabmap-build`, beside the checkout).
set(out_of_source "${root}/out-of-source")
file(MAKE_DIRECTORY "${out_of_source}/src/corelib/include/rtabmap/core")
file(WRITE "${out_of_source}/src/corelib/include/rtabmap/core/Rtabmap.h" "// stub\n")
file(MAKE_DIRECTORY "${out_of_source}/rtabmap-build/bin")
file(WRITE "${out_of_source}/rtabmap-build/bin/librtabmap_core.a" "")
file(WRITE "${out_of_source}/rtabmap-build/bin/librtabmap_utilite.a" "")

# 3. The old guess still resolves, so a hand-assembled tree keeps working.
set(handmade "${root}/handmade")
file(MAKE_DIRECTORY "${handmade}/core/include/rtabmap/core")
file(WRITE "${handmade}/core/include/rtabmap/core/Rtabmap.h" "// stub\n")
file(MAKE_DIRECTORY "${handmade}/bin")
file(WRITE "${handmade}/bin/librtabmap_core.a" "")
file(WRITE "${handmade}/bin/librtabmap_utilite.a" "")

# 4. Headers but no archives: the message must name the missing step, not just "set the path".
set(headers_only "${root}/headers-only")
file(MAKE_DIRECTORY "${headers_only}/corelib/include/rtabmap/core")
file(WRITE "${headers_only}/corelib/include/rtabmap/core/Rtabmap.h" "// stub\n")

# 5. A directory that holds nothing of the sort.
set(empty "${root}/empty")
file(MAKE_DIRECTORY "${empty}")

message(STATUS "rtabmap layout check")
run_case("upstream layout, in-source build" "${in_source}"
         "${in_source}/corelib/include" "${in_source}/build/bin" "")
run_case("out-of-source build beside the checkout" "${out_of_source}/src"
         "${out_of_source}/src/corelib/include" "${out_of_source}/rtabmap-build/bin" "")
run_case("hand-assembled core/include tree" "${handmade}"
         "${handmade}/core/include" "${handmade}/bin" "")
run_case("headers without archives" "${headers_only}" "" "" "static archives are missing")
run_case("empty directory" "${empty}" "" "" "no RTAB-Map headers")
run_case("no path given" "" "" "" "no checkout given")

file(REMOVE_RECURSE "${root}")

message(STATUS "rtabmap layout: ${RUN_ALL} checks, ${RUN_FAIL} failures")
if(RUN_FAIL GREATER 0)
    message(FATAL_ERROR "rtabmap layout check failed")
endif()
