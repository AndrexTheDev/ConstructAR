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
| Android SDK Platform | **37** | `compileSdk = 37` (SceneView 4.60 is compiled against it) |
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
./gradlew :app:assembleDebug
./gradlew :core:test :export:test :ar-engine:test    # JVM tests only, no device needed
```

`./gradlew :app:installDebug` deploys to a connected device. The first native build takes
2–4 minutes per ABI; subsequent builds are incremental.

### What is in the repo and what you must supply

| Item | In repo | Where to get it |
|------|---------|-----------------|
| Gradle wrapper | ❌ | `gradle wrapper --gradle-version 9.1` once, then commit `gradle/wrapper/*` |
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

1. `MonocularDepthEstimator.create()` tries **NNAPI** → **GPU (Vulkan)** → **CPU (XNNPACK)**.
2. Inference runs at ~2 Hz on a separate job; the camera frame is downscaled into a reusable
   bitmap (no per-frame allocation).
3. `MetricDepthCalibrator` fits `1/z ≈ s·disparity + t` against ARCore's metric depth with IRLS
   + Huber weights. If the inlier ratio is below 55 % it **refuses** and the app falls back to
   ARCore depth — better no number than a wrong number.

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

### 5.2 What the bridge does

* adds keyframes (RGB feature descriptors + pose) at a spatial threshold;
* asks RTAB-Map for loop-closure candidates, which are turned into `PoseGraph` edges with a
  high weight **only** after a geometric consistency check (`ProximityLoopClosureDetector`
  in the C++ layer, if RTAB-Map is absent);
* runs the optimiser and refreshes the node poses.

With RTAB-Map off, the built-in pose graph and proximity proposer are used — the app never
depends on RTAB-Map being present.

---

## 6. SceneView integration points (read before upgrading SceneView)

SceneView's API moves between minors. The renderer deliberately talks to **Filament** directly
for everything that has geometry (`SceneMeshRenderer`), and only three things are SceneView's:

1. `ARSceneView(...)` parameters in `ar-engine/.../render/ArScene.kt` — engine, loaders,
   `planeFindingMode`, `depthMode`, `flashMode`, `planeRenderer`, `onSessionUpdated` and the
   content lambda.
2. The **content scope** exposing the Filament `Scene` and `Engine` used by
   `onSceneReady(scene, engine, materialLoader)`.
3. `MaterialLoader`, used through `SceneViewMaterialProvider` **reflectively** — so a renamed
   factory method degrades to "meshes are not drawn" instead of a build error.

If (1) or (2) changed in your SceneView version, `ArScene.kt` is the only file to adjust. Pin
the version in `gradle/libs.versions.toml` and check the release notes for `ARSceneView`.

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `UnsatisfiedLinkError: libconstructar_slam.so` | ABI not built (e.g. an x86 emulator) | The app detects it (`NativeSlamEngine.isAvailable == false`) and disables scanning; add `x86_64` to `abiFilters` or use a real device |
| Black AR view, `CameraNotAvailableException` | Another app holds the camera | `ArSessionController.resume()` retries once after 250 ms; otherwise the UI says "camera unavailable" |
| Scan looks holey at silhouettes | Free-space carving is off by default (it triples integration cost) | Enable `TsdfConfig.carveFreeSpace` for a final clean-up pass, or rescan from a second angle |
| Mesh is shifted after a relocalisation | Anchors were recreated from the persisted node positions | Expected: run the loop closure (or finish and re-measure that run); the protocol flags low-confidence runs |
| Depth hits land behind the surface | Stale depth frame used for the raycast | Must not happen: `TsdfScanPipeline` only recycles a snapshot when a newer one replaces it |
| Torch does nothing | Device reports `FlashMode.TORCH` unsupported or front camera | `ArSessionController.setTorch` returns false; the UI greys the button |
| `FAIL_ON_PROJECT_REPOS` failure | A dependency pulled in `jcenter`/a second repo | Keep everything on `google()` + `mavenCentral()`; see `settings.gradle.kts` |
