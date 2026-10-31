# Licensing

The product requirement is strict: **no license fees, no non-free modules**. This file records
what is used, under which licence, and the obligations that follow.

## Runtime components

| Component | Version | Licence | Obligation |
|-----------|---------|---------|------------|
| SceneView for Android | 4.60.0 | Apache-2.0 | Attribution in the about screen (to be added before release); no copyleft |
| Filament | 1.72.1 | Apache-2.0 | Same |
| ARCore (`com.google.ar:core`) | 1.57.0 | Google ARCore SDK licence (free, closed) | Distributed through Google Play Services for AR; no fee, no attribution requirement, but it **is** proprietary — acceptable because it ships with the platform |
| Kotlin / AndroidX / Compose | catalog | Apache-2.0 | Attribution |
| kotlinx.coroutines / serialization | catalog | Apache-2.0 | Attribution |
| LiteRT (former TensorFlow Lite) | 2.3.0 | Apache-2.0 | Attribution |
| **Depth Anything V2 Small** | weights | **Apache-2.0** (Small only) | Attribution. Base/Large are **CC-BY-NC-4.0** and must not be shipped — enforced by the checksum in `MonocularDepthEstimator` |
| **RTAB-Map** (optional) | source | **BSD-3-Clause** | Keep the copyright notice. Must be built with `OPENCV_ENABLE_NONFREE=OFF`; only SIFT/ORB/BRISK (free) detectors may be linked |
| OpenCV (transitive, if RTAB-Map enabled) | 4.x | Apache-2.0 (without the `nonfree` module) | Do not enable `opencv_contrib`'s `nonfree` module. SIFT is free since OpenCV 4.4 and is what we use |

## Explicitly excluded

* **SURF / SIFT from `opencv_contrib/nonfree`** — patented-adjacent, non-free. Use OpenCV 4's
  free SIFT or ORB.
* **Google ML Kit / Firebase / gms measurement** — the root `build.gradle.kts` fails the build
  if any of those groups appear in the dependency graph (`OFFLINE_FORBIDDEN_GROUPS`).
* **Any cloud inference SDK** — the app has no `INTERNET` permission, so this is enforced by
  the platform, not by convention.
* **GPL/AGPL PDF or glTF libraries** — hence the hand-written `GlbWriter` and the
  `android.graphics.pdf` based protocol writer.

## Model weights and datasets

Depth Anything V2 Small is trained on a mixture of public datasets; the *weights* are
Apache-2.0 according to the upstream repository. Datasets themselves may have their own terms,
but they are not redistributed here — only the derived weights are, under the upstream licence.
If your legal team requires the dataset provenance, see the model card upstream; nothing in
this repository redistributes dataset content.

## Source licence

This repository's own source is Apache-2.0. Add a `LICENSE` file (Apache-2.0 text) and the
`NOTICE` attributions for SceneView, Filament and Depth Anything V2 before a public release.
