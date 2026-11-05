/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// The marker-to-scale update. See the header for why only this half exists.
//
// The estimate is deliberately built from *ratios between pairs of tags*, not from one tag's
// reconstructed edge length: a single quad's corners are blurred by the same sub-pixel effect the
// scan's metre is wrong by, whereas the distance between two tag centres is a baseline the whole
// scan agrees on. Pair ratios also make the outlier test local and cheap - one tag that was
// detected on the wrong id or stuck to the wrong wall shows up as a pair ratio far from its
// neighbours, and the median absorbs it.
#include "tag_scale.hpp"

#include <algorithm>
#include <cmath>

namespace constructar {

NoTagDetector::NoTagDetector(std::string reason) : reason_(std::move(reason)) {}

bool TagCamera::isPlausible(int width, int height) const {
    if (!(fx > 0.f) || !(fy > 0.f)) return false;  // NaN-safe: a zero focal length is not a camera
    if (!std::isfinite(fx) || !std::isfinite(fy) || !std::isfinite(cx) || !std::isfinite(cy)) {
        return false;
    }
    return width > 0 && height > 0 && cx >= 0.f && cy >= 0.f && cx <= static_cast<float>(width) &&
           cy <= static_cast<float>(height);
}

std::unique_ptr<TagDetector> makeTagDetector() {
  // One string, spelled once: a diagnostic screen prints it, and a second copy in the app would
  // drift from this one. It names what is actually missing - the camera, not the decoder - because
  // a build that says "no decoder" while carrying one sends a reader to the wrong file.
  return std::make_unique<NoTagDetector>(
      "no camera intrinsics were supplied, so a tag's pixels cannot become metres - the AprilTag "
      "36h11 decoder is vendored (BSD-2-Clause, third_party/apriltag), and docs/CALIBRATION.md tier "
      "2 says what it still does not reach");
}

namespace {

bool isTrusted(const TagSighting& sighting, const TagScaleOptions& options) {
  if (sighting.hamming > options.maxHamming) return false;
  if (!(sighting.decisionMargin >= options.minDecisionMargin)) return false;  // NaN-safe
  return sighting.centroid.isFinite();
}

struct PairRatio {
  float ratio = 1.f;
  float baseline = 0.f;
};

/** Median of the ratios; for an even count the mean of the two middle ones. */
float medianRatio(std::vector<PairRatio> ratios) {
  std::sort(ratios.begin(), ratios.end(),
            [](const PairRatio& a, const PairRatio& b) { return a.ratio < b.ratio; });
  const size_t count = ratios.size();
  if (count % 2 == 1) return ratios[count / 2].ratio;
  return 0.5f * (ratios[count / 2 - 1].ratio + ratios[count / 2].ratio);
}

}  // namespace

TagScaleEstimate estimateScaleFromTagSightings(const std::vector<TagSighting>& sightings,
                                               const std::vector<TagLayoutEntry>& layout,
                                               const TagScaleOptions& options) {
  TagScaleEstimate estimate;

  // One sighting per id, and only the confident ones: two detections of the same tag are one tag,
  // and the weaker one is what a false positive looks like.
  std::vector<TagSighting> best;
  for (const TagSighting& sighting : sightings) {
    if (!isTrusted(sighting, options)) continue;
    auto slot = std::find_if(best.begin(), best.end(),
                             [&sighting](const TagSighting& kept) { return kept.id == sighting.id; });
    if (slot == best.end()) {
      best.push_back(sighting);
    } else if (sighting.decisionMargin > slot->decisionMargin) {
      *slot = sighting;
    }
  }

  // Build every pair that has a printed distance to compare with.
  std::vector<PairRatio> ratios;
  for (size_t i = 0; i < best.size(); ++i) {
    for (size_t j = i + 1; j < best.size(); ++j) {
      const TagLayoutEntry* nominalA = nullptr;
      const TagLayoutEntry* nominalB = nullptr;
      for (const TagLayoutEntry& entry : layout) {
        if (entry.id == best[i].id) nominalA = &entry;
        if (entry.id == best[j].id) nominalB = &entry;
      }
      if (nominalA == nullptr || nominalB == nullptr) continue;
      if (!nominalA->nominal.isFinite() || !nominalB->nominal.isFinite()) continue;
      const float printed = (nominalA->nominal - nominalB->nominal).length();
      const float measured = (best[i].centroid - best[j].centroid).length();
      if (printed < options.minBaselineMetres) continue;
      if (!(measured > 1e-4f)) continue;  // two tags on top of each other say nothing
      ratios.push_back(PairRatio{printed / measured, printed});
    }
  }
  if (ratios.empty()) return estimate;

  const float median = medianRatio(ratios);
  if (!(median > 0.f) || !std::isfinite(median)) return estimate;

  double weightedSum = 0.0;
  double totalWeight = 0.0;
  for (const PairRatio& pair : ratios) {
    const float deviation = std::fabs(pair.ratio - median) / median;
    if (deviation > options.maxRelativeDeviation) {
      ++estimate.rejectedPairs;
      continue;
    }
    ++estimate.pairs;
    weightedSum += static_cast<double>(pair.ratio) * static_cast<double>(pair.baseline);
    totalWeight += static_cast<double>(pair.baseline);
    estimate.spread = std::max(estimate.spread, deviation);
    estimate.baselineMetres = std::max(estimate.baselineMetres, pair.baseline);
  }
  if (estimate.pairs < options.minPairs || !(totalWeight > 0.0)) {
    // Keep the counts - the caller can tell "one pair agreed" from "nothing was seen" - but do
    // not publish a factor that a single pair produced.
    estimate.valid = false;
    return estimate;
  }
  estimate.factor = static_cast<float>(weightedSum / totalWeight);
  estimate.valid = std::isfinite(estimate.factor) && estimate.factor > 0.f;
  if (!estimate.valid) estimate.factor = 1.f;
  return estimate;
}

}  // namespace constructar
