# AprilTag 3.1.0 — vendored C core

This directory is **not** ConstructAR code. It is AprilTag's C core, copied verbatim, and the
copyright in every file in it belongs to its authors, not to this project.

* **Project:** AprilTag — <https://github.com/AprilRobotics/apriltag>
* **Version:** 3.1.0 (`project(... VERSION 3.1.0)` in upstream's `CMakeLists.txt`)
* **Licence:** BSD 2-Clause — see `LICENSE.md` in this directory, and `licenses/BSD-2-Clause.txt`,
  the `NOTICE` entry and the Help screen's third-party list for the attribution that travels with
  the app.
* **Provenance, honestly:** this sandbox's egress reaches GitHub for this repository only, so the
  files were taken from the `pupil-apriltags` 1.0.4.post11 source distribution on PyPI, whose
  `apriltags-source/` directory is upstream's 3.1.0 tree. There are no pupil-specific changes in
  the files listed below (the wrapper `apriltag_pywrap.c` is not among them and was not copied).
  A downstream maintainer with unrestricted network access should re-fetch from upstream and
  confirm the file hashes before relying on the provenance claim above.

## What was taken, and what was left

Compiled (see `CMakeLists.txt`'s `APRILTAG_SOURCES` and `tools/verify-native.sh`):

```
apriltag.c  apriltag.h  apriltag_math.h  apriltag_pose.c  apriltag_pose.h
apriltag_quad_thresh.c  tag36h11.c  tag36h11.h
common/{g2d,homography,image_u8,image_u8x3,matd,pnm,svd22,time_util,unionfind,
        workerpool,zarray,zhash,zmaxheap}.{c,h}  and the headers they need
```

Left out, and why:

* **Every other tag family** (`tag16h5`, `tag25h9`, `tag36artoolkit`, `tagCircle*`, `tagCustom*`,
  `tagStandard*`). One family is compiled because one family is what a printed wall marker uses;
  each codebook is ~600 lines of table and a family nobody prints is a table nobody measured.
* **`apriltag_demo`, `example/`, the Python wrapper, OpenCV glue, `pthreads` demo plumbing, and the
  `test/` suite.** They are upstream's tooling, not the decoder, and none of them is built here.
* `getopt.c`, `string_util.c` and the demo helpers inside `time_util.c`: present because headers
  reference them, **not compiled** - the build lists its sources explicitly in both places it
  builds (CMake and `tools/verify-native.sh`), so nothing here is pulled in by a wildcard.

## The one door

Exactly one translation unit in this repository includes these headers:
`native-slam/src/main/cpp/calibration/apriltag_detector.cpp`. Everything else sees
`constructar::TagDetector`, whose types are ours. `scripts/verify_sources.py` enforces that: a second
file that includes an apriltag header is a finding, because a decoder we did not write must not
leak its types into a layer we did.

## Modifications

**None.** No file in this directory was edited. The build adapts to upstream, not the other way
round: `_GNU_SOURCE` and `-w` are passed by the build scripts, and `std::vector`/ownership live in
the wrapper. If a future change to a file in this directory becomes necessary, it must be recorded
here with its reason — an unrecorded edit inside a vendored tree is how a fork becomes a guess.

## Not wired into the app

The decoder is compiled, exported over JNI (`nativeDetectAprilTagScale`), and proved in the host
suite against rendered tags. `:app` does not capture a luminance frame and call it: the marker tier
is a decoded measurement with no route into the project file yet, and `docs/CALIBRATION.md` tier 2
states that rather than implying the opposite.
