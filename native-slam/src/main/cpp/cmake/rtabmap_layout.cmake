# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
#
# Where an RTAB-Map checkout keeps its headers and its static archives.
#
# This is a separate module so it can be exercised without an NDK, a toolchain or an RTAB-Map
# build: `tests/rtabmap-layout-check.cmake` runs it in CMake script mode against fake trees that
# mirror the upstream layout, which is the only way a "does the option work at all" question can
# be answered on a machine that has no RTAB-Map (finding 110).
#
# The layout knowledge is not invented. Upstream (introlab/rtabmap) keeps the public headers in
# `corelib/include`, not `core/include`; an in-source build writes `build/bin/librtabmap_core.a`,
# and the out-of-source build the setup guide prescribes (`-B rtabmap-build`) writes
# `rtabmap-build/bin/`. Checking only the first of each pair made the option impossible to switch
# on: a developer who had just built RTAB-Map exactly as documented was told to build it.

# Probes, in the order a checkout is likely to be laid out. Nothing outside these is guessed at,
# so the error message can list exactly what was looked for.
set(CONSTRUCTAR_RTABMAP_INCLUDE_ROOTS
    "corelib/include"
    "core/include"
)
set(CONSTRUCTAR_RTABMAP_LIB_DIRS
    "bin"
    "build/bin"
    "rtabmap-build/bin"
    "../rtabmap-build/bin"
)
set(CONSTRUCTAR_RTABMAP_ARCHIVES
    "librtabmap_core.a"
    "librtabmap_utilite.a"
)

# Sets OUT_INCLUDE (the include root to pass to the compiler), OUT_LIB_DIR (the directory holding
# both archives) and OUT_PROBLEM ("" when everything resolved, otherwise a sentence naming what is
# missing and where it was looked for). Deliberately does not raise: the caller decides how loud
# to be, and the script-mode test can inspect the problem string instead of dying on it.
function(constructar_locate_rtabmap source_dir out_include out_lib_dir out_problem)
    set(${out_include} "" PARENT_SCOPE)
    set(${out_lib_dir} "" PARENT_SCOPE)

    if(NOT source_dir)
        set(${out_problem}
            "no checkout given - pass -DRTABMAP_SOURCE_DIR=<path to an introlab/rtabmap clone>."
            PARENT_SCOPE)
        return()
    endif()
    if(NOT IS_DIRECTORY "${source_dir}")
        set(${out_problem}
            "RTABMAP_SOURCE_DIR is '${source_dir}', which is not a directory."
            PARENT_SCOPE)
        return()
    endif()

    set(found_include "")
    foreach(root IN LISTS CONSTRUCTAR_RTABMAP_INCLUDE_ROOTS)
        if(EXISTS "${source_dir}/${root}/rtabmap/core/Rtabmap.h")
            set(found_include "${source_dir}/${root}")
            break()
        endif()
    endforeach()
    if(NOT found_include)
        set(searched "")
        foreach(root IN LISTS CONSTRUCTAR_RTABMAP_INCLUDE_ROOTS)
            string(APPEND searched " ${root}/rtabmap/core/Rtabmap.h;")
        endforeach()
        set(${out_problem}
            "no RTAB-Map headers under '${source_dir}' - looked for:${searched}"
            PARENT_SCOPE)
        return()
    endif()

    set(found_lib_dir "")
    foreach(candidate IN LISTS CONSTRUCTAR_RTABMAP_LIB_DIRS)
        set(all_present TRUE)
        foreach(archive IN LISTS CONSTRUCTAR_RTABMAP_ARCHIVES)
            if(NOT EXISTS "${source_dir}/${candidate}/${archive}")
                set(all_present FALSE)
            endif()
        endforeach()
        if(all_present)
            set(found_lib_dir "${source_dir}/${candidate}")
            break()
        endif()
    endforeach()
    if(NOT found_lib_dir)
        set(searched "")
        foreach(candidate IN LISTS CONSTRUCTAR_RTABMAP_LIB_DIRS)
            string(APPEND searched " ${candidate}/librtabmap_core.a + librtabmap_utilite.a;")
        endforeach()
        string(CONCAT problem
            "the headers are in '${found_include}' but the static archives are missing - "
            "build RTAB-Map first (docs/SETUP.md 5.1); looked for:${searched}")
        set(${out_problem} "${problem}" PARENT_SCOPE)
        return()
    endif()

    # Canonical paths: the candidates are built by appending to whatever the developer passed, so
    # a checkout reached through `../` would otherwise hand the compiler a path with `..` in it and
    # make two runs of the same configuration compare unequal.
    get_filename_component(found_include "${found_include}" REALPATH)
    get_filename_component(found_lib_dir "${found_lib_dir}" REALPATH)
    set(${out_include} "${found_include}" PARENT_SCOPE)
    set(${out_lib_dir} "${found_lib_dir}" PARENT_SCOPE)
    set(${out_problem} "" PARENT_SCOPE)
    message(STATUS "ConstructAR: RTAB-Map headers ${found_include}")
    message(STATUS "ConstructAR: RTAB-Map archives ${found_lib_dir}")
endfunction()
