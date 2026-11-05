#!/usr/bin/env bash
# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
#
# Builds and runs the native host suite - the C++ core (TSDF, Surface Nets, pose graph) checked
# with a plain compiler, no Android SDK, NDK or CMake. This is the script `host_tests.cpp` and
# `.github/workflows/native-tests.yml` describe.
#
#   ./tools/verify-native.sh          # g++ -O2 -Wall -Wextra -Werror, then clang++ when installed
#   ./tools/verify-native.sh --tsan   # the same suite under ThreadSanitizer
#
# The TSAN run is not decoration. The TSDF integration fuses voxels from several worker threads,
# and the fusion is a read-modify-write of two arrays: with the update outside the shard lock,
# ThreadSanitizer reports three races on three lines of `volume.cpp` and two threads write each
# other's observation away (finding 181). A pass means the race is gone; the suite alone cannot
# see it, because the lost update needs the two threads inside the same few instructions.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

cpp="native-slam/src/main/cpp"
sources=(
  "$cpp/tsdf/volume.cpp"
  "$cpp/tsdf/surface_nets.cpp"
  "$cpp/slam/pose_graph.cpp"
  "$cpp/geometry/manhattan_world.cpp"
  "$cpp/calibration/tag_scale.cpp"
  "$cpp/calibration/apriltag_detector.cpp"
  "$cpp/geometry/plane_fit.cpp"
  "$cpp/geometry/floorplan.cpp"
  "$cpp/geometry/surface_mask.cpp"
  "$cpp/slam/rtabmap_bridge.cpp"
  "$cpp/tests/host_tests.cpp"
)
flags=(-std=c++20 -pthread -Wall -Wextra -Werror -I "$cpp")

# The vendored AprilTag core is C99 and not ours, so it is compiled by the C compiler, without
# `-Werror` (a warning in somebody else's source is not a build failure of ours) and with only the
# files the decoder needs. `-D_GNU_SOURCE` is what upstream assumes: `strdup`, `random` and `usleep`
# are POSIX, and a strict C11 translation unit does not declare them.
vendored=(
  "$cpp/third_party/apriltag/apriltag.c"
  "$cpp/third_party/apriltag/apriltag_quad_thresh.c"
  "$cpp/third_party/apriltag/apriltag_pose.c"
  "$cpp/third_party/apriltag/tag36h11.c"
  "$cpp/third_party/apriltag/common/g2d.c"
  "$cpp/third_party/apriltag/common/homography.c"
  "$cpp/third_party/apriltag/common/image_u8.c"
  "$cpp/third_party/apriltag/common/image_u8x3.c"
  "$cpp/third_party/apriltag/common/matd.c"
  "$cpp/third_party/apriltag/common/pnm.c"
  "$cpp/third_party/apriltag/common/svd22.c"
  "$cpp/third_party/apriltag/common/time_util.c"
  "$cpp/third_party/apriltag/common/unionfind.c"
  "$cpp/third_party/apriltag/common/workerpool.c"
  "$cpp/third_party/apriltag/common/zarray.c"
  "$cpp/third_party/apriltag/common/zhash.c"
  "$cpp/third_party/apriltag/common/zmaxheap.c"
)

# Compiles the vendored C into objects for one compiler and one optimisation, and fills
# `c_objects` with the paths - the linker call takes them alongside the C++ sources.
build_vendored() {
  local cc="$1" tag="$2"
  shift 2
  local out="$build_dir/c-$tag"
  mkdir -p "$out"
  c_objects=()
  local source
  for source in "${vendored[@]}"; do
    local object="$out/$(basename "${source%.c}").o"
    "$cc" -std=gnu11 -D_GNU_SOURCE -w -I "$cpp/third_party/apriltag" "$@" -c "$source" -o "$object"
    c_objects+=("$object")
  done
}

build_dir="${TMPDIR:-/tmp}/constructar-native"
mkdir -p "$build_dir"

run_suite() {
  local binary="$1" label="$2"
  local log="$build_dir/$label.log"
  local status=0
  echo "== run ($label)"
  "$binary" >"$log" 2>&1 || status=$?
  cat "$log"
  # A race is a failure even though the assertions may all pass: ThreadSanitizer only reports,
  # it does not stop the program (halt_on_error=0 in the --tsan branch, so every race is listed).
  if grep -q "WARNING: ThreadSanitizer\|FATAL: ThreadSanitizer" "$log"; then
    echo "FAIL: $label reported a data race (log: $log)" >&2
    exit 1
  fi
  if [[ $status -ne 0 ]]; then
    echo "FAIL: $label exited $status (log: $log)" >&2
    exit 1
  fi
}

# ── 16 KB page sizes (Android 15+) ────────────────────────────────────────────────────────────
# The NDK build sets `-Wl,-z,max-page-size=16384` (CMakeLists.txt), and that flag is a claim until
# something reads the ELF back: the loader maps LOAD segments, so *those* are what must be 16 KB
# aligned. This script runs on a laptop without an NDK, so the check is skipped there with the
# reason printed - the CI step "16 KB page size" runs the same two commands where the artifacts
# and the tools exist, and that is where a regression is caught.
check_page_size() {
  local so
  so=$(find native-slam/build -name 'libconstructar_slam.so' -print -quit 2>/dev/null || true)
  if [[ -z "$so" ]]; then
    echo "== 16 KB pages: skipped (no NDK-built libconstructar_slam.so - :native-slam:assembleDebug builds it)"
  else
    local reader=""
    for candidate in llvm-readelf readelf "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"; do
      if [[ -n "$candidate" ]] && command -v "$candidate" >/dev/null 2>&1; then reader="$candidate"; break; fi
    done
    if [[ -z "$reader" ]]; then
      echo "== 16 KB pages: skipped (no readelf on PATH and no NDK to borrow one from)"
    else
      local bad
      bad=$("$reader" -lW "$so" | awk '$1 == "LOAD" && $NF != "0x4000"')
      if [[ -n "$bad" ]]; then
        echo "libconstructar_slam.so has a LOAD segment that is not 16 KB aligned:" >&2
        echo "$bad" >&2
        exit 1
      fi
      echo "== 16 KB pages: every LOAD segment of libconstructar_slam.so is 0x4000"
    fi
  fi

  local apk zipalign=""
  apk=$(find app/build/outputs/apk -name '*.apk' -print -quit 2>/dev/null || true)
  [[ -n "${ANDROID_HOME:-}" ]] && zipalign=$(find "$ANDROID_HOME/build-tools" -name zipalign -print -quit 2>/dev/null || true)
  command -v zipalign >/dev/null 2>&1 && zipalign=$(command -v zipalign)
  if [[ -z "$apk" ]]; then
    echo "== 16 KB pages: skipped the APK check (no APK built here)"
  elif [[ -z "$zipalign" ]]; then
    echo "== 16 KB pages: skipped the APK check (no zipalign; needs build-tools 35+ for -P 16)"
  else
    if ! "$zipalign" -c -P 16 -v 4 "$apk" >/dev/null 2>&1; then
      echo "$apk is not aligned for 16 KB pages:" >&2
      "$zipalign" -c -P 16 -v 4 "$apk" >&2 || true
      exit 1
    fi
    echo "== 16 KB pages: $(basename "$apk") is zipaligned for 16 KB (-P 16)"
  fi
}

if [[ "${1:-}" == "--tsan" ]]; then
  if ! command -v g++ >/dev/null 2>&1; then
    echo "FAIL: --tsan needs g++ with libtsan, and g++ is not installed" >&2
    exit 1
  fi
  binary="$build_dir/host_tests_tsan"
  echo "== build (g++ -O1 -g -fsanitize=thread)"
  build_vendored gcc "gpp-O1-tsan" -O1 -g -fsanitize=thread
  g++ "${flags[@]}" -O1 -g -fsanitize=thread "${sources[@]}" "${c_objects[@]}" -o "$binary"
  # halt_on_error=0 so every race is reported, not just the first: the point is to see how many
  # lines of the integrator are unsynchronised.
  export TSAN_OPTIONS="halt_on_error=0"
  run_suite "$binary" "thread-sanitizer"
  echo "== no data race reported"
  exit 0
fi

compilers=(g++)
if command -v clang++ >/dev/null 2>&1; then compilers+=(clang++); fi
for compiler in "${compilers[@]}"; do
  for optimisation in -O1 -O2; do
    binary="$build_dir/host_tests_${compiler//+/p}_$optimisation"
    echo "== build ($compiler $optimisation)"
    case "$compiler" in
      g++) cc=gcc ;;
      clang++) cc=clang ;;
      *) cc="$compiler" ;;
    esac
    build_vendored "$cc" "${compiler//+/p}-$optimisation" "$optimisation"
    "$compiler" "${flags[@]}" "$optimisation" "${sources[@]}" "${c_objects[@]}" -o "$binary"
    run_suite "$binary" "${compiler//+/p}$optimisation"
  done
done
check_page_size
echo "== native host suite passed"
