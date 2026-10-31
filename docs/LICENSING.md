# Licensing

The product requirement is strict: **no license fees, no non-free modules**. This file records
what is used, under which licence, and the obligations that follow.

## Runtime components

| Component | Version | Licence | Obligation |
|-----------|---------|---------|------------|
| SceneView for Android | 4.63.0 | Apache-2.0 | Attribution in the about screen (to be added before release); no copyleft |
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

## Source licence — All Rights Reserved

ConstructAR's **own** source code is **proprietary**. The repository is public for exactly one
reason: GitHub Actions must be able to build it, and reviewers must be able to read it. Being
visible is not a licence.

`LICENSE` (repository root) is the authoritative text. In summary, and without replacing it:

* no use, copying, modification, publication, distribution, sublicensing, sale, deployment,
  hosting, or derivative work — commercial or otherwise — is permitted;
* reverse engineering, decompiling, or extracting the source from a build is prohibited;
* copyright headers in every source file and the ownership metadata in build outputs must not
  be removed.

The third-party components in the table above stay under **their** licences. Nothing in the
proprietary terms above narrows an Apache-2.0 or BSD-3-Clause right that a third-party
component grants you for that component.

## Ownership and attribution

| Field | Value |
|-------|-------|
| Copyright holder | AndrexTheDev |
| Contact | <hippie.highho@gmail.com> |
| Repository | <https://github.com/AndrexTheDev/ConstructAR> |
| SPDX identifier used in headers | `LicenseRef-Proprietary` |
| Purpose of the public repository | CI/CD and review only |

Every Kotlin, C++, CMake, Gradle and XML source file carries:

```
Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
All rights reserved. Proprietary and confidential - see LICENSE.
SPDX-License-Identifier: LicenseRef-Proprietary
```

`scripts/verify_sources.py` fails the build when a source file is missing it.

## Before any public release

1. Keep `LICENSE` in sync with the owner's intent (it is the only authoritative statement).
2. Keep the in-app attribution for SceneView, Filament, ARCore and Depth Anything V2 Small —
   Apache-2.0 requires it, regardless of this project's own licence.
3. Do not ship Depth Anything V2 Base/Large weights; the checksum gate in
   `MonocularDepthEstimator` refuses anything but the Small INT8 model.

---

© 2026 **AndrexTheDev** <hippie.highho@gmail.com> — All rights reserved. See [LICENSE](../LICENSE).
