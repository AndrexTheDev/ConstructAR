/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Passive marker calibration — the arithmetic, and (since finding 231) the decoder.
//
// A printed AprilTag of known size, seen in enough depth frames, gives the scan a length that
// does not depend on the operator holding a bank card still at arm's length: the tag's corners
// are reconstructed, their distance is compared with the tag's printed size, and the scan's metre
// is corrected. That is the useful half, and it is arithmetic.
//
// The other half - finding the tag in the pixels - used to be a licensing decision this
// repository declined to make: quad extraction, homography, tag36h11 bit decoding and its error
// correction, all of it somebody else's code. It is now vendored, unmodified and credited:
// AprilTag 3.1.0's C core, BSD-2-Clause, in `native-slam/src/main/cpp/third_party/apriltag/`
// (provenance, file list and the licence in `VENDORED.md` next to it, and the attribution in
// `NOTICE`, `licenses/BSD-2-Clause.txt` and the Help screen's list). `AprilTagDetector` in
// `apriltag_detector.hpp` is the one door between that tree and this code: nothing else in the
// repository includes an apriltag header, which is what keeps a decoder we did not write from
// leaking its types into a layer we did.
//
//   * `TagDetector`, the interface a caller uses, with an honest `available()` - false when no
//     camera intrinsics were supplied, so a caller cannot mistake "cannot convert pixels to
//     metres" for "no tags in view" (the shape finding 98 had to establish for `RtabMapBridge`);
//   * `AprilTagDetector`, the implementation, which needs a pinhole camera and the printed size
//     before it can answer, because a tag's pixel size means nothing in metres without them;
//   * `estimateScaleFromTagSightings`, the marker-to-scale update: nominal tag spacing over
//     measured tag spacing, robust to a tag that was mis-detected or mis-placed.
//
// **Vendored, not wired.** `MarkerFrameReader` in `:ar-engine`
// packs an ARCore camera plane (`:core`'s `LuminancePlane`) into the grey frame
// `nativeDetectAprilTagScale` takes, and hands it the session's intrinsics and the printed size -
// so a frame can become a distance without a phone in the room to prove it. What is still missing
// is the last metre: no `:app` screen requests a frame, so nothing the operator does produces a
// marker reading, and no stored number depends on one. `estimateScaleFromTagSightings` has no
// caller at all. The difference between "the pipeline reaches the decoder" and "the app uses it" is
// the point of this paragraph: the first is true, the second is not.
//
// **It proposes, it never decides.** The acceptance window and the decision to store a factor
// belong to `:core` (`ReferenceCalibration.accepted`), exactly as they do for the bank-card
// reference: the native layer produces a factor and the statistics it was derived from, and the
// Kotlin layer says yes or no. That is why the window's two numbers do not appear in this
// directory at all - not even in a comment, so there is no copy to drift.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "../core/types.hpp"

namespace constructar {

/** A luminance frame a decoder would read: 8-bit greyscale, row-major, `rowStride` in bytes. */
struct GrayFrameView {
  const uint8_t* pixels = nullptr;
  int width = 0;
  int height = 0;
  int rowStride = 0;
  double timestampSeconds = 0.0;

  /** A view that a decoder can safely read: the buffer exists and the geometry is sane. */
  bool isUsable() const {
    return pixels != nullptr && width > 0 && height > 0 && rowStride >= width;
  }
};

/** One decoded tag in one frame. */
struct TagDetection {
  int id = -1;
  /** Tag centre in the camera frame, metres (positive Z forward). */
  Vec3 centreInCamera;
  /** Decoder confidence in [0, 1]; a tag read through blur has a low one. */
  float decisionMargin = 0.f;
  /** Bit errors the decoder had to correct. 0 is a clean read. */
  int hamming = 0;
};

/**
 * The interface a pixel decoder implements.
 *
 * There is exactly one implementation in this repository - `NoTagDetector`, whose `available()`
 * is false - and that is the point: the app-facing code can be written, compiled and tested
 * against this interface today, and the day a decoder lands nothing above this line changes.
 */
class TagDetector {
 public:
  virtual ~TagDetector() = default;

  /** False when this detector cannot answer at all. Never a synonym for "no tags in this frame". */
  virtual bool available() const = 0;

  /** Why it is unavailable, or "" when it is. Static storage; never null. */
  virtual const char* unavailableReason() const = 0;

  /**
   * Decodes the tags in one frame. Returns an empty vector when the decoder cannot answer at all,
   * which is indistinguishable from "none visible" *by design* - callers must ask `available()`
   * first, and `unavailableReason()` is what a diagnostic screen prints. With the vendored decoder
   * in place the usual reason is a missing camera, not a missing decoder.
   */
  virtual std::vector<TagDetection> detect(const GrayFrameView& view) = 0;
};

/**
 * The detector that answers "cannot" - used when the caller has not supplied what a decoder needs.
 *
 * A decoder is compiled in (finding 231), so this class no longer means "none exists"; it means
 * the *question* was incomplete. The reason it carries is what a diagnostic screen prints, and it
 * is spelled once, here, so that a screen cannot paraphrase it into something more reassuring.
 */
class NoTagDetector final : public TagDetector {
 public:
  explicit NoTagDetector(std::string reason);

  bool available() const override { return false; }
  const char* unavailableReason() const override { return reason_.c_str(); }
  std::vector<TagDetection> detect(const GrayFrameView& view) override {
    (void)view;
    return {};
  }

 private:
  std::string reason_;
};

/**
 * What a decoder needs before it can turn pixels into metres.
 *
 * `fx`/`fy`/`cx`/`cy` are the pinhole intrinsics in pixels, from the camera the frame came from -
 * not ARCore's, not a nominal 60-degree lens. `tagSizeMetres` is the printed side of the tag's
 * black square, quiet zone excluded: it *is* the scale, and a wrong number here produces a
 * confidently wrong distance rather than a failure.
 */
struct TagCamera {
  float fx = 0.f;
  float fy = 0.f;
  float cx = 0.f;
  float cy = 0.f;

  /** Positive focal lengths and a principal point inside the image: a camera, not four zeros. */
  bool isPlausible(int width, int height) const;
};

struct TagDetectorOptions {
  TagCamera camera;
  /** Side of the tag's black square, in metres. */
  float tagSizeMetres = 0.2f;
  /** Upper bound on returned detections; the strongest are kept. */
  int maxDetections = 8;
};

/**
 * The detector this build offers *without* a camera: an object whose `available()` is false and
 * whose reason names the missing intrinsics. Kept as an overload because the callers that only
 * want to know "can this build read tags at all" should not have to invent a camera.
 */
std::unique_ptr<TagDetector> makeTagDetector();

/** One tag seen by the scan: its id and where its centre ended up in the scan's own frame. */
struct TagSighting {
  int id = -1;
  Vec3 centroid;
  float decisionMargin = 1.f;
  int hamming = 0;
};

/** A tag's centre where the printed layout says it is, in metres, in the layout's own frame. */
struct TagLayoutEntry {
  int id = -1;
  Vec3 nominal;
};

struct TagScaleOptions {
  /** Below this the decoded id is not trusted enough to move a metre. */
  float minDecisionMargin = 0.6f;
  /** Above this the decoder was guessing: a tag read through a corrected bit is not a ruler. */
  int maxHamming = 0;
  /** A pair whose ratio is further than this from the median is an outlier, not evidence. */
  float maxRelativeDeviation = 0.04f;
  /** Two tags closer than this are one tag as far as a ratio is concerned. */
  float minBaselineMetres = 0.20f;
  /** How many agreeing pairs before a factor is published at all. */
  int minPairs = 2;
};

/**
 * The marker-to-scale proposal: `factor` is what measured metres must be multiplied by.
 *
 * `spread` and `pairs` are published with it because a factor from two pairs and a factor from
 * forty are not the same claim, and the layer that decides has to be able to see the difference.
 */
struct TagScaleEstimate {
  bool valid = false;
  float factor = 1.f;
  /** Pairs that agreed with the median ratio. */
  int pairs = 0;
  /** Pairs that were built and then rejected by the deviation filter. */
  int rejectedPairs = 0;
  /** Worst relative deviation among the accepted pairs. */
  float spread = 0.f;
  /** Longest accepted nominal baseline: the evidence this factor actually rests on. */
  float baselineMetres = 0.f;
};

/**
 * Estimates the scale correction from tag sightings and the printed layout.
 *
 * `factor` here is nominal over measured, i.e. what a *measured* metre has to be multiplied by -
 * the same direction as `ProjectRecord.calibrationScale`, so a caller that has both does not have
 * to think about which way round it is.
 *
 * Robustness: only tags above `minDecisionMargin` with at most `maxHamming` corrected bits are
 * used, one sighting per id (the most confident), pairs shorter than `minBaselineMetres` are
 * ignored, and a pair more than `maxRelativeDeviation` from the median ratio is rejected. The
 * factor is the baseline-weighted mean of the accepted ratios, so a 2 m pair counts ten times what
 * a 0.2 m pair does - which is the honest weighting, since the error of a ratio falls with its
 * baseline.
 *
 * The window, the clamp and the decision to store anything are **not** here: see the header note.
 */
TagScaleEstimate estimateScaleFromTagSightings(const std::vector<TagSighting>& sightings,
                                               const std::vector<TagLayoutEntry>& layout,
                                               const TagScaleOptions& options = {});

}  // namespace constructar
