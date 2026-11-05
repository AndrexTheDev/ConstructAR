/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

#include "apriltag_detector.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

// The vendored core, and the only place in the repository that includes it. Its files keep their
// own BSD-2-Clause headers (see third_party/apriltag/VENDORED.md); ours do not travel into it.
#include "../third_party/apriltag/apriltag.h"
#include "../third_party/apriltag/apriltag_pose.h"
#include "../third_party/apriltag/common/image_u8.h"
#include "../third_party/apriltag/common/matd.h"
#include "../third_party/apriltag/tag36h11.h"

namespace constructar {
namespace {

constexpr int kMaxDimension = 8192;
constexpr int kMaxDetections = 32;
constexpr float kMinTagSizeMetres = 0.01f;
constexpr float kMaxTagSizeMetres = 5.f;
constexpr float kMaxRangeMetres = 100.f;
/** apriltag reports `decision_margin` in grey levels; the contract says [0, 1]. */
constexpr float kMarginScale = 1.f / 255.f;

/** Distance of a pose translation, or a non-finite value when the matrix is not a 3-vector. */
float translationLength(const matd_t* t) {
  if (t == nullptr || t->nrows != 3 || t->ncols != 1) return std::nanf("");
  const double x = t->data[0];
  const double y = t->data[1];
  const double z = t->data[2];
  if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) return std::nanf("");
  return static_cast<float>(std::sqrt(x * x + y * y + z * z));
}

bool poseIsALocation(float distance, const double* translation) {
  if (!std::isfinite(distance) || distance <= 0.f || distance > kMaxRangeMetres) return false;
  return std::isfinite(translation[0]) && std::isfinite(translation[1]) && std::isfinite(translation[2]);
}

}  // namespace

AprilTagDetector::AprilTagDetector(const TagDetectorOptions& options) : options_(options) {}

std::vector<TagDetection> AprilTagDetector::detect(const GrayFrameView& view) {
  std::vector<TagDetection> out;
  if (!view.isUsable()) return out;
  if (view.width > kMaxDimension || view.height > kMaxDimension) return out;
  if (!options_.camera.isPlausible(view.width, view.height)) return out;
  if (!std::isfinite(options_.tagSizeMetres) || options_.tagSizeMetres < kMinTagSizeMetres ||
      options_.tagSizeMetres > kMaxTagSizeMetres) {
    return out;
  }
  const int limit = std::clamp(options_.maxDetections, 0, kMaxDetections);

  // A private, tightly packed copy: a row stride is a promise about the caller's memory, and the
  // detector is not promised to leave that memory alone.
  image_u8_t* image = image_u8_create(static_cast<unsigned int>(view.width),
                                      static_cast<unsigned int>(view.height));
  if (image == nullptr) return out;
  for (int row = 0; row < view.height; ++row) {
    std::memcpy(image->buf + static_cast<std::size_t>(row) * image->stride,
                view.pixels + static_cast<std::size_t>(row) * static_cast<std::size_t>(view.rowStride),
                static_cast<std::size_t>(view.width));
  }

  apriltag_family_t* family = tag36h11_create();
  apriltag_detector_t* detector = apriltag_detector_create();
  apriltag_detector_add_family(detector, family);
  detector->nthreads = 1;
  detector->quad_decimate = 1.0f;
  detector->debug = 0;

  zarray_t* found = apriltag_detector_detect(detector, image);
  if (found != nullptr) {
    for (int index = 0; index < zarray_size(found); ++index) {
      apriltag_detection_t* detection = nullptr;
      zarray_get(found, index, &detection);
      if (detection == nullptr) continue;

      apriltag_detection_info_t info;
      info.det = detection;
      info.tagsize = options_.tagSizeMetres;
      info.fx = options_.camera.fx;
      info.fy = options_.camera.fy;
      info.cx = options_.camera.cx;
      info.cy = options_.camera.cy;
      apriltag_pose_t pose;
      std::memset(&pose, 0, sizeof(pose));
      estimate_tag_pose(&info, &pose);
      const float distance = translationLength(pose.t);
      double translation[3] = {0.0, 0.0, 0.0};
      const bool hasTranslation = pose.t != nullptr && pose.t->nrows == 3 && pose.t->ncols == 1;
      if (hasTranslation) {
        translation[0] = pose.t->data[0];
        translation[1] = pose.t->data[1];
        translation[2] = pose.t->data[2];
      }
      if (pose.R != nullptr) matd_destroy(pose.R);
      if (pose.t != nullptr) matd_destroy(pose.t);
      if (!hasTranslation || !poseIsALocation(distance, translation)) continue;

      TagDetection tag;
      tag.id = detection->id;
      tag.centreInCamera = Vec3{static_cast<float>(translation[0]), static_cast<float>(translation[1]),
                                static_cast<float>(translation[2])};
      tag.decisionMargin = std::clamp(static_cast<float>(detection->decision_margin) * kMarginScale,
                                      0.f, 1.f);
      tag.hamming = detection->hamming;
      out.push_back(tag);
    }
    apriltag_detections_destroy(found);
  }
  apriltag_detector_destroy(detector);
  tag36h11_destroy(family);
  image_u8_destroy(image);

  // Strongest first, ties by id: two runs over one image must produce one list, and a caller that
  // keeps the first detection must keep the same one every time.
  std::sort(out.begin(), out.end(), [](const TagDetection& a, const TagDetection& b) {
    if (a.decisionMargin != b.decisionMargin) return a.decisionMargin > b.decisionMargin;
    return a.id < b.id;
  });
  if (static_cast<int>(out.size()) > limit) out.resize(static_cast<std::size_t>(limit));
  return out;
}

std::unique_ptr<TagDetector> makeTagDetector(const TagDetectorOptions& options) {
  // The principal point is checked per frame, against that frame's size; the focal lengths are not,
  // because they are the one part of a pinhole camera that does not depend on the image.
  if (!(options.camera.fx > 0.f) || !(options.camera.fy > 0.f) ||
      !std::isfinite(options.camera.fx) || !std::isfinite(options.camera.fy)) {
    return std::make_unique<NoTagDetector>(
        "no camera intrinsics were supplied, so a tag's pixels cannot become metres - the AprilTag "
        "36h11 decoder is vendored, and docs/CALIBRATION.md tier 2 says what it does not reach");
  }
  if (!std::isfinite(options.tagSizeMetres) || options.tagSizeMetres < kMinTagSizeMetres ||
      options.tagSizeMetres > kMaxTagSizeMetres) {
    return std::make_unique<NoTagDetector>(
        "the printed tag size is missing or outside 1 cm to 5 m, and the tag's size is the scale - "
        "without it a detection is a shape, not a distance (docs/CALIBRATION.md)");
  }
  return std::make_unique<AprilTagDetector>(options);
}

}  // namespace constructar
