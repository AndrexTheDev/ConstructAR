/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// The one door between the vendored AprilTag C core and this repository (finding 231).
//
// Everything upstream - `apriltag.h`, the quad extractor, the homography, the tag36h11 codebook,
// `image_u8` - is C with its own handles and its own ownership rules. Nothing outside this file
// includes it: the calibration tier sees `TagDetector`, `GrayFrameView` and `TagDetection`, which
// are ours, and the boundary is a file rather than a convention. That is also why this translation
// unit is the only place that has to change if the vendored version ever does.
//
// **What it needs before it can answer.** A pinhole camera and the printed size of the tag. Without
// either, a tag's four corners are four points in a picture; with both, they are a distance in
// metres. `makeTagDetector(options)` therefore returns `AprilTagDetector` only when it has them, and
// a `NoTagDetector` whose reason says which one is missing otherwise - never a detector that
// silently reports "no tags".
#pragma once

#include <memory>

#include "tag_scale.hpp"

namespace constructar {

/**
 * tagof36h11 detection through the vendored core, for one camera and one printed tag size.
 *
 * One thread, one family, one image at a time. `apriltag_detector` can split its work across a
 * pool, and the pool would make the output order - and at the edge of the tolerance, the answers -
 * depend on scheduling; the caller is already on a worker, so the parallelism buys latency at the
 * price of reproducibility, and reproducibility is what a measurement is for.
 */
class AprilTagDetector final : public TagDetector {
 public:
  explicit AprilTagDetector(const TagDetectorOptions& options);

  bool available() const override { return true; }
  const char* unavailableReason() const override { return ""; }

  std::vector<TagDetection> detect(const GrayFrameView& view) override;

 private:
  TagDetectorOptions options_;
};

/** The decoder, if `options` has what it needs; otherwise the detector that says what is missing. */
std::unique_ptr<TagDetector> makeTagDetector(const TagDetectorOptions& options);

}  // namespace constructar
