# Setup — from a clean machine to a building APK

This document is the step-by-step guide the brief asked for: toolchain, Gradle, CMake/NDK,
manifest, ARCore, the optional depth model, and the optional RTAB-Map back-end. Every command
was chosen to work on Linux/macOS/Windows with no cloud services involved.

---

## 1. Toolchain

| Tool | Version | Why exactly this |
|------|---------|------------------|
| JDK | **21** | AGP 9.x requires 17+; 21 is the current LTS and what the version catalog targets |
| Android Studio | Ladybug or newer | Compose tooling + native debugger |
| Android SDK Platform | **37** | `compileSdk = 37` (SceneView 4.63.0 is compiled against it) |
| Android SDK Build-Tools | 35.x | Ships with AGP 9 |
| NDK | **27.2.12479018** | Pinned in `native-slam/build.gradle.kts`; needed for the 16 KB page-size requirement |
| CMake | **3.22.1** | Installed through the SDK Manager so Gradle finds it without a PATH tweak |
| Device | ARCore-capable, Android 7.0+ | Depth API (raw depth + confidence) is strongly recommended |

```bash
sdkmanager "platforms;android-37" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
```

Set `local.properties`:

```properties
sdk.dir=/home/you/Android/Sdk
```

---

## 2. Build

```bash
gradle assembleDebug testDebugUnitTest   # the gate: all modules, warnings are errors
gradle :core:test :export:test :ar-engine:test       # JVM tests only, no device needed
python3 scripts/verify_sources.py                    # headers / reflection ban / i18n, no SDK
```

`gradle :app:installDebug` deploys to a connected device. The first native build takes
2–4 minutes per ABI; subsequent builds are incremental.

### What is in the repo and what you must supply

| Item | In repo | Where to get it |
|------|---------|-----------------|
| Gradle wrapper | ❌ | `gradle wrapper --gradle-version 9.6.0` once, then commit `gradle/wrapper/*` |
| Depth model `.tflite` | ❌ (25 MB binary, hash-verified) | see §4 |
| RTAB-Map source | ❌ | see §5, or skip it |
| Launcher icon | ✅ (vector) | replace with a designed adaptive icon before release |

---

## 3. AndroidManifest — what each line is for

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FLASHLIGHT" />
<!-- No INTERNET permission. Ever. The app cannot upload a measurement. -->

<uses-feature android:name="android.hardware.camera.ar" android:required="true" />
<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="true" />
<uses-feature android:glEsVersion="0x00030000" android:required="true" />

<meta-data android:name="com.google.ar.core" android:value="required" />
```

* `com.google.ar.core = required` makes Play filter out non-AR devices. If you want a
  fallback build for tablets without AR, set it to `optional` **and** keep the manual-length
  entry path — `ArSessionController` already models `Unsupported` readiness.
* `FLASHLIGHT` is needed for `Config.FlashMode.TORCH` on some OEMs even though the torch is
  driven through ARCore.
* `allowBackup="false"` plus `data_extraction_rules.xml`: measurements are not silently copied
  to Google's servers by the OS backup agent either.

---

## 4. Optional: Depth Anything V2 Small (Apache-2.0)

The app runs without it (ARCore depth only). With it, the depth field becomes dense, which
improves the TSDF in low-texture areas and gives tier-3 hits on surfaces ARCore's sparse depth
misses.

### 4.1 Get the *Small* checkpoint — and only that one

* `depth-anything/Depth-Anything-V2-Small` on Hugging Face is **Apache-2.0**.
* `Depth-Anything-V2-Base` and `-Large` are **CC-BY-NC-4.0** — non-commercial. Shipping them
  would break the licence requirement of this project. `MonocularDepthEstimator` verifies an
  optional SHA-256 and refuses anything else.

### 4.2 Convert to a quantised TFLite model

```bash
pip install "ai-edge-torch" torch torchvision
python - <<'PY'
import ai_edge_torch, torch
from transformers import AutoModelForDepthEstimation
model = AutoModelForDepthEstimation.from_pretrained(
    "depth-anything/Depth-Anything-V2-Small-hf").eval()
example = torch.randn(1, 3, 518, 518)
edge = ai_edge_torch.convert(model, (example,))
edge.export("depth_anything_v2_small_fp32.tflite")
PY
```

Then quantise to INT8 (2× faster, 4× smaller, well within the accuracy budget after the
least-squares metric calibration):

```bash
python -m ai_edge_torch.quantize --model depth_anything_v2_small_fp32.tflite \
    --output depth_anything_v2_small_int8.tflite --calibration-images ./calib/
```

Place it at `app/src/main/assets/models/depth_anything_v2_small_int8.tflite` and record its
hash:

```bash
sha256sum app/src/main/assets/models/depth_anything_v2_small_int8.tflite
# → put the value into MonocularDepthEstimator.Config.expectedSha256
```

> The ONNX path (`onnx` → `onnx2tf`) produces an equivalent model if you prefer it; only the
> input shape (`1×518×518×3`, float, 0..1) and the output (`1×H×W×1` relative disparity) matter
> to `MonocularDepthEstimator`.

### 4.3 What happens at runtime

1. `MonocularDepthEstimator.create()` builds one **CPU/XNNPACK** interpreter (4 threads). NNAPI
   and the Vulkan GPU delegate are gone on purpose: LiteRT's standalone runtime does not ship
   NNAPI, and the GPU delegate cannot run an int8 graph. The estimator is quantisation-aware —
   it reads the model's input/output dtypes, so an int8 export *and* a float32 export both
   work.
2. Inference runs at ~2 Hz on a separate job; the camera frame is downscaled into a reusable
   bitmap (no per-frame allocation).
3. `MetricDepthCalibrator` fits `1/z ≈ s·disparity + t` against ARCore's metric depth with IRLS
   + Huber weights. If the inlier ratio is below 55 % it **refuses** and the app falls back to
   ARCore depth — better no number than a wrong number.
4. `AiDepthFusion` merges the two: ARCore's confident samples are kept verbatim, holes are
   filled from the calibrated network output, clamped to 0.25–8 m. The fused frame is handed to
   the TSDF pipeline (`submitFusedDepth`) and expires after 800 ms.

Diagnostics: `MonocularDepthSource.backend` (`"cpu/xnnpack"`),
`.lastCalibration.inlierRatio` and `.lastFillRatio` are exposed for a debug screen and for
field reports.

---

## 5. Optional: RTAB-Map as the SLAM back-end

RTAB-Map is BSD-3-Clause and adds appearance-based loop closure and graph optimisation on top
of what ARCore tracks. The bridge in `native-slam/src/main/cpp/slam/rtabmap_bridge.cpp` is
compiled **only** when you point the build at an RTAB-Map source tree.

### 5.1 Build RTAB-Map without non-free modules

RTAB-Map's default CMake picks up OpenCV, and some distributions ship OpenCV with the
non-free (SIFT/SURF) module enabled. The build must be restricted to free feature detectors:

```bash
# Build OpenCV first (only if your distro's build enables non-free):
cmake -S opencv -B opencv-build \
  -DOPENCV_ENABLE_NONFREE=OFF \
  -DBUILD_LIST=core,imgproc,features2d,calib3d,flann \
  -DWITH_IPP=OFF

cmake -S rtabmap -B rtabmap-build \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_APP=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TOOLS=OFF \
  -DWITH_QT=OFF -DWITH_OPENGL=OFF -DWITH_PCL=OFF -DWITH_OCTOMAP=OFF \
  -DWITH_OPENGV=OFF -DWITH_TORO=OFF -DWITH_G2O=OFF -DWITH_GTSAM=OFF \
  -DOpenCV_DIR=$(pwd)/opencv-build \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24
cmake --build rtabmap-build -j
```

Then, in `gradle.properties`:

```properties
constructar.rtabmap.dir=/absolute/path/to/rtabmap
```

`native-slam/build.gradle.kts` forwards this as `-DCONSTRUCTAR_WITH_RTABMAP=ON
-DRTABMAP_SOURCE_DIR=…` to CMake.

CMake finds the checkout's headers and archives instead of assuming one layout: upstream keeps
them in `corelib/include`, an in-source build writes `build/bin/librtabmap_core.a`, and the
`-B rtabmap-build` above writes `rtabmap-build/bin/` next to the checkout. All three are
recognised (`native-slam/src/main/cpp/cmake/rtabmap_layout.cmake`); if none matches, the configure
step stops and names every path it looked for, so a typo is one line of output rather than a wall
of missing-header errors. That probe is itself tested by
`cmake -P native-slam/src/main/cpp/tests/rtabmap-layout-check.cmake` (fake trees, no NDK), which
`native-tests` runs on every push.

### 5.2 What the bridge does — and what is wired today

The C++ side is complete, and the optional branch is **compiled** by the `native-tests` job on
every push — in the `-DCONSTRUCTAR_WITH_RTABMAP=1` configuration, against the stub headers in
`native-slam/src/main/cpp/tests/rtabmap-stubs`. It cannot be *linked* or run there (no RTAB-Map
checkout in CI). What that check proves is the *shape* of the calls: those headers mirror
upstream's declarations — the parameter keys are functions returning `std::string`, parameters go
to `init()`, the odometry pose is a `process(data, odomPose)` argument, `CameraModel` takes its
optical rotation and a `cv::Size` — and they are mutated in review to make sure they still
refuse a wrong call.

It has not always been like that. Until finding 107 the branch could not compile at all (the
RTAB-Map includes sat inside `namespace constructar`); until finding 183 it still could not,
because the stubs had been written from the bridge: seven signatures differed from upstream
(`setParameters`, `setOptimized`, a nine-argument `CameraModel`, an odometry pose inside
`SensorData`, `kFeatureORB`, the keys as values, and a one-argument `process`), so the job was
green for a build that would have failed. A real checkout is still the only thing that can prove
a link, and no run of this app has done that.

* `RtabMapBridge::processKeyframe` feeds one keyframe (RGB + the ARCore odometry pose) and
  reports whether RTAB-Map accepted a loop closure and which node it matched. It does **not**
  return an optimised pose list — the poses come from the pose-graph call below, which the app
  does not make yet;
* `ProximityLoopClosureDetector` proposes a revisit by pose proximity (proposals only — a false
  closure folds a room in half, so nothing is promoted silently);
* `PoseGraph::optimize` redistributes a closure over the whole trajectory.

**It is not yet driven from the app.** No line in `:app` or `:ar-engine` calls
`optimizePoseGraph`, `addPoseNode`, `addPoseEdge` or `loopClosureCandidates`, and the JNI exposes
no keyframe feed, so compiling RTAB-Map in changes nothing the operator sees: today the app
measures on ARCore's own tracking and no closure is optimised. The loop closure is therefore
**not yet driven by the scan pipeline** (finding 98), and `scripts/verify_sources.py` fails if
this document claims the optimiser runs while it is still true. Wiring it is a real piece of
work — keyframe extraction, a verified closure policy and a re-anchor of the measured nodes —
not a build flag.

---

## 6. SceneView integration points (read before upgrading SceneView)

Pinned to **SceneView 4.63.0**, used through its **typed** Compose API. Nothing is looked up by
name, so an API change is a compile error rather than a device-only surprise.

| What | Typed factory / parameter | Where |
|------|---------------------------|-------|
| `Engine` | `rememberEngine()` | `ar-engine/.../render/ArScene.kt` |
| `ModelLoader` | `rememberModelLoader(engine)` | same |
| `MaterialLoader` | `rememberMaterialLoader(engine)` | same |
| `EnvironmentLoader` | `rememberEnvironmentLoader(engine)` | same |
| `Scene` (Filament) | `rememberScene(engine)` → handed to `onSceneReady(scene, engine, materialLoader)` | same |
| AR surface | `ARSceneView(scene = …, planeRenderer = false, planeFindingMode, depthMode, flashMode, cloudAnchorMode = DISABLED, instantPlacementMode = DISABLED, geospatialMode = DISABLED, sessionConfiguration, onSessionUpdated, onTrackingFailureChanged, onSessionFailed)` | same |
| Materials | `MaterialLoader.createColorInstance(color: Int, …)`, `createUnlitColorInstance(color: Int)` | `ar-engine/.../render/SceneViewSupport.kt` |

Two consequences that are easy to miss:

1. **`sessionConfiguration` is the only safe hook for ARCore policy.** SceneView calls it
   immediately before `Session.configure()`; configuring a resumed session throws. ConstructAR's
   offline policy (raw depth, ambient light estimation, no cloud anchors/instant placement/
   geospatial/streetscape) is applied there by `ArSessionController.applyPolicy`.
2. **SceneView owns the session lifecycle.** `ArSessionController` no longer creates, resumes,
   pauses or closes a session — doing so raced SceneView's own `configure()` and closed a
   session the renderer still held. It is now policy + readiness only.

If a SceneView upgrade touches any of the rows above, `ArScene.kt`, `SceneViewSupport.kt` and
`ArSessionController.applyPolicy` are the only files to adjust. Keep `sceneview` pinned exactly
in `gradle/libs.versions.toml`; the CI build in `.github/workflows/android-build.yml` is what
proves the pin compiles.

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `UnsatisfiedLinkError: libconstructar_slam.so` | ABI not built (e.g. an x86 emulator) | The app detects it (`NativeSlamEngine.isAvailable == false`) and disables scanning; add `x86_64` to `abiFilters` or use a real device |
| Black AR view, `CameraNotAvailableException` | Another app holds the camera | `ArSessionController.resume()` retries once after 250 ms; otherwise the UI says "camera unavailable" |
| Scan looks holey at silhouettes | Free-space carving is off by default (it costs ~7.7× integration: 7.3 → 55.9 ms per 320×240 frame) | Enable `TsdfConfig.carveFreeSpace` for a final clean-up pass, or rescan from a second angle |
| Mesh is shifted after a relocalisation | Anchors were recreated from the persisted node positions | Nothing folds the trajectory back yet - the app does not run a loop closure (finding 98) - so finish the run and re-measure it, or scan again; the protocol flags low-confidence runs |
| Depth hits land behind the surface | Stale depth frame used for the raycast | Must not happen: a snapshot's array goes back to `DepthBufferPool` only when its last reference is released, so a frame the hit cascade or the worker is still reading cannot be overwritten (finding 168) |
| Torch does nothing | Device reports `FlashMode.TORCH` unsupported, or the front camera is in use | SceneView silently downgrades the typed `flashMode`; `TorchController` keeps the button state honest |
| `FAIL_ON_PROJECT_REPOS` failure | A dependency pulled in `jcenter`/a second repo | Keep everything on `google()` + `mavenCentral()`; see `settings.gradle.kts` |

---

© 2026 **AndrexTheDev** <hippie.highho@gmail.com> — All rights reserved. See [LICENSE](../LICENSE).
