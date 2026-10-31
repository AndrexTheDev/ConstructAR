# ConstructAR

**Offline AR measuring and routing for electrical, plumbing and ventilation work.**

An electrician stands in an unfinished basement. There is no signal, the lights are on a
temporary circuit, and the customer wants to know today how much 16 mm cable and how many
clips the run needs. ConstructAR lets them *draw the conduit on the wall* with the phone,
measures it to the centimetre, and exports a protocol PDF, a material list for procurement and
a 3D model for BIM — **without a single byte leaving the device**.

```
 ┌──────────────┐   ARCore frame    ┌─────────────────┐   300 ms    ┌────────────────┐
 │ SceneView AR │ ───────────────►  │ native TSDF     │ ─────────►  │ Filament mesh  │
 │ (render thr.)│  depth+pose copy  │ (C++ workers)   │  SurfaceNet │ (room overlay) │
 └──────┬───────┘                   └─────────────────┘             └────────────────┘
        │ tap                                    ▲
        ▼                                        │
 ┌──────────────┐   4-tier cascade    ┌──────────┴───────┐
 │ hit testing  │ ──────────────────► │ route session    │──► anchors + geometry
 └──────────────┘  plane→depth→ray→pt  └────────┬─────────┘
                                                ▼
                             ┌──────────────────────────────────────┐
                             │ :export  PDF · CSV · GLB · PLY       │
                             └──────────────────────────────────────┘
```

---

## What it does

| # | Feature | Where | Notes |
|---|---------|-------|-------|
| **A** | Live room scan → TSDF volume → Surface Nets mesh, drawn as a translucent overlay | `native-slam/src/main/cpp/tsdf`, `ar-engine/.../scan` | 2 cm voxels, 8 cm truncation, extraction throttled to **300 ms** |
| **B** | Strict hit-test cascade: **plane polygon → `DepthPoint` → direct depth raycast → feature point** | `ar-engine/.../hit` | Each tier is strictly less trustworthy; a node inherits the reliability of its tier and the export flags soft ones |
| **C** | Surface-normal aligned procedural geometry: tubes for cables/pipes, channels for ducts, Bézier/fillet smoothing at bends | `core/.../mesh`, `core/.../geo` | Normals come from the hit tier, then are blended along the run so a tube hugs a wall without twisting |
| **D** | Polyline length `L = Σ‖Pᵢ − Pᵢ₊₁‖`, every node bound to an ARCore `Anchor`, live HUD label ("Length: 14.82 m") | `ar-engine/.../route`, `core/.../geo/PolylineMetrics.kt` | Measured on the **surface** points, never the offset centreline |
| **E** | Local export: PDF protocol, CSV material list, `.glb` (glTF 2.0 binary) and `.ply` | `export/` | Written through the Storage Access Framework; no media storage permission, no share-to-cloud |

Plus: auto-torch with hysteresis, monocular depth (Depth Anything V2 Small) fused into the
metric depth field, loop-closure pose graph, thermal-aware degradation, and explicit handling
for every way AR can fail on a site.

---

## Guarantees

**100 % offline.** The app has no `INTERNET` permission in its manifest — it is not "offline
by policy", it is offline by construction. No cloud anchors, no analytics, no crash reporting,
no model downloads. Everything runs on the phone's CPU/GPU/NPU.

**No per-frame allocation in the hot path.** Depth is copied into a pooled `ShortArray`
(`DepthBufferPool`), the hit cascade reuses pre-allocated lists, and the TSDF mesh is re-uploaded
only when extraction produced a new one (≤ 3.3 Hz). ARCore frame handling stays on the render
thread; depth filtering, TSDF integration, surface extraction and pose-graph optimisation run on
native worker threads.

**License-fee free, verified at build time.** SceneView (Apache-2.0) + Filament (Apache-2.0),
ARCore (proprietary but free, ships in Google Play Services for AR), RTAB-Map compiled
**without** OpenCV non-free / SURF (BSD-3-Clause, SIFT/ORB only), Depth Anything V2 **Small
only** (Apache-2.0; Base/Large are CC-BY-NC and are refused by a checksum in
`MonocularDepthEstimator`). See [docs/LICENSING.md](docs/LICENSING.md).

---

## Modules

| Module | Language | Responsibility |
|--------|----------|----------------|
| `:core` | Kotlin/JVM, **no Android deps** | Maths (`Vec3`, `Quat`, `Mat4`, `Plane3`, polygon containment), routing model, fillet/RMF smoothing, tube & duct mesh generation, measurement, bill of materials, formatting. This is the trust boundary: everything that decides a *number* lives here and is unit-tested on the JVM. |
| `:native-slam` | C++20 + JNI | TSDF volume (block-hashed, free-space carving optional), Surface Nets extraction, pose graph with loop closures, RTAB-Map bridge. Host-testable: `native-slam/src/main/cpp/tests`. |
| `:ar-engine` | Kotlin/Android | ARCore session & config, torch policy, tracking monitor, depth capture, TSDF pipeline, hit cascade, AI depth, anchors, routing state, Filament mesh upload, Compose AR surface. |
| `:export` | Kotlin/Android | PDF protocol, CSV bill of materials, GLB writer, PLY writer. Storage-agnostic (works on `OutputStream`). |
| `:app` | Kotlin/Android | Single activity, Compose Material 3 UI with glove-sized controls, project storage (JSON files), navigation. |

---

## Build

Requirements: **JDK 21**, Android SDK **37**, NDK **27.2.12479018**, CMake **3.22.1**, and the
SceneView 4.60.0 / ARCore 1.57 artifacts (already pinned in `gradle/libs.versions.toml`).

```bash
git clone <this repo> && cd ConstructAR
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew :core:test :export:test     # JVM unit tests
```

The native volume mesher can be exercised without Gradle or a phone:

```bash
g++ -std=c++20 -O2 -pthread -I native-slam/src/main/cpp \
    native-slam/src/main/cpp/tsdf/volume.cpp \
    native-slam/src/main/cpp/tsdf/surface_nets.cpp \
    native-slam/src/main/cpp/slam/pose_graph.cpp \
    native-slam/src/main/cpp/slam/rtabmap_bridge.cpp \
    native-slam/src/main/cpp/tests/host_tests.cpp -o /tmp/host_tests && /tmp/host_tests
```

Optional pieces (both are opt-in, the app works without them):

* **AI depth** — drop `depth_anything_v2_small_int8.tflite` into
  `app/src/main/assets/models/` and set `constructar.depthModelAsset` if the name differs.
  Without it the app uses ARCore depth only.
* **RTAB-Map** — set `constructar.rtabmap.dir=<path to RTAB-Map source>` in
  `gradle.properties`; the CMake build then compiles the BSD-only bridge. Without it, the
  built-in pose graph and proximity loop-closure proposer are used.

Full step-by-step setup — including how to convert the depth model and how to build RTAB-Map
without non-free modules — is in **[docs/SETUP.md](docs/SETUP.md)**.

---

## Accuracy, honestly stated

| What | Expected error | Why |
|------|----------------|-----|
| Flat wall run, 1–4 m, plane hit | **±1–2 cm** | ARCore plane geometry + anchor refinement; the dominant term is the plane's own fit |
| Run across depth-only areas | **±3–6 cm** | Raw depth resolution plus confidence filtering |
| Bend allowance | ~0 | Not measured: the *chord* length is what a tape measure shows. Bend allowance is reported separately and only used for procurement |
| Angle / diagonal run on a plane | **±1 %** | Anchor drift over distance |

The app never inflates a measured length with a "cable slack" factor. Waste percentages are
applied only in the procurement column, and the protocol prints both numbers.

Known limitations, deliberately documented rather than hidden:

* **Single-view reconstruction.** The TSDF sees what the camera saw. A sphere test in the host
  suite reconstructs 0.89 m² of a 1.57 m² hemisphere: the rim where the surface is within ~10°
  of the view ray is not resolvable at 2 cm voxels. Behind a pipe, on the far side of a corner,
  the mesh simply ends — which is correct, but looks like a hole.
* **Reflective and transparent surfaces** (windows, mirrors, polished steel) break both depth
  and tracking. The tracking monitor catches the *symptom* (depth confidence collapse) and
  coaches the operator; it cannot fix the physics.
* **Drift.** With no loop closure, ARCore drift is ~1 % of travelled distance; the pose graph
  redistributes a closure over the whole trajectory, so a 1.4 m closure error becomes ~0.1 m of
  per-edge residual rather than a kink in the mesh.

---

## Testing

* **Native (C++) — 44 assertions, all green**, exercised with plain `g++` (no Android SDK):
  SE(3) exp/log round trip, TSDF integration on synthetic depth, sphere/wall reconstruction
  areas and extents, triangle budget, invalid input, pose-graph closure, proximity loop closure.
* **JVM (`:core`)** — transform maths, plane fits and polygon containment, fillet smoothing,
  measurement, tube/duct mesh invariants, BOM aggregation.
* **JVM (`:ar-engine`, `:export`)** — hit cascade priority and rejection reasons, metric depth
  calibration (including "refuses to fit on noise"), routing/anchor behaviour, GLB/PLY/CSV
  structural validation.
* **Instrumented tests** would need a physical ARCore device; none are included, and nothing in
  this repository claims to have run on one.

---

## Status

Implemented and verified in this environment:

* the complete `:core` maths/geometry/measurement layer;
* the C++ TSDF + Surface Nets + pose graph, with a passing host test suite;
* the Kotlin AR engine, export and app layers, reviewed for correctness but **not compiled**
  here (no JDK/Gradle/Android SDK/NDK in the build sandbox, and no way to install one).

Before a store release: build the APK, run it on at least one depth-capable and one
plane-only device, re-check the SceneView integration points listed in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and retune the TSDF constants from
[docs/PERFORMANCE.md](docs/PERFORMANCE.md) on the target hardware.

---

## Licence

Source: Apache-2.0. Third-party components and their obligations:
[docs/LICENSING.md](docs/LICENSING.md).
