# Performance notes and measurements

All numbers below were measured **in this repository's CI sandbox** (x86-64 VM, `g++ -O3`,
3 worker threads) by running the native code standalone. They are not phone numbers. A modern
arm64 phone is roughly **1.5–3× slower** per core for this workload, so treat the desktop
figures as a lower bound and re-measure on the target device before shipping.

Reproduce with:

```bash
g++ -std=c++20 -O3 -pthread -I native-slam/src/main/cpp \
    native-slam/src/main/cpp/tsdf/volume.cpp \
    native-slam/src/main/cpp/tsdf/surface_nets.cpp your_bench.cpp -o /tmp/bench
```

---

## 1. Measured

### Depth integration + mesh extraction (640 × 480 depth, stride 2, 3 threads)

| Configuration | integrate | extract | blocks | triangles |
|---------------|-----------|---------|--------|-----------|
| Nominal (no free-space carving) | **20.6 ms** | **10.4 ms** | 680 | 31 080 |
| Free-space carving, stride 2 voxels | 132.7 ms | 25.5 ms | 2 234 | 31 184 |

The carving pass is 6.4× the cost of the integration it decorates: for every ray it walks
`depth / (voxelSize · stride)` blocks and writes "outside". At 2.5 m and 2 cm voxels with
stride 2 that is 62 block writes *per ray*. **This is why it is off by default** and why the
default stride is 4 (≈31 writes/ray) when it is enabled.

### Pose graph (13 nodes, 13 edges, one verified closure, 13 GN iterations)

| Metric | Value |
|--------|-------|
| Cost 444.32 → 0.455 | 3 orders of magnitude |
| Time | **1.5 ms** |
| Loop residual after optimisation | 1.1 mm / 2.4 mrad |

The optimiser is not on the critical path: it runs once per accepted closure, ~10 times in a
long scan.

### Reconstruction correctness used as a performance sanity check (320 × 240, sphere r = 0.5 m)

* 3 499 vertices / 6 228 triangles, area inside r = 0.45 m: **0.814 m²** vs the analytic
  0.886 m² (8 % low, expected at grazing incidence).
* 16.4 ms per frame with carving enabled at this resolution.

---

## 2. Frame budget on a phone

Target: 30 fps AR (33 ms), of which ARCore + SceneView + Filament camera rendering already
consume 8–14 ms on a mid-range device.

| Work | Thread | Budget | Actual strategy |
|------|--------|--------|-----------------|
| Depth copy (`DepthCapture.capture`) | render | 0.3 ms | ~0.15 ms: row-wise `ShortBuffer.get` into a pooled array |
| Hit cascade (tap only) | render | 2 ms | < 1 ms: plane set cached per frame, depth patch 5×5 |
| Reticle sample | render | 0.2 ms | 12 Hz, not 30 Hz |
| Anchor refresh (`RouteSession.onFrame`) | render | 0.2 ms | early-out below 5 mm movement |
| TSDF integration | worker | — | **not** on the frame budget; drops frames instead |
| Mesh extraction | worker | — | ≤ 3.3 Hz (300 ms), 10–25 ms each |
| Mesh upload to Filament | main/worker | — | only on change, ~2 ms per 30 k triangles |
| Measurement + HUD string | main | 0.1 ms | `PolylineMetrics.measure` on ≤ 50 nodes |
| Monocular depth (optional) | own worker | — | 2 Hz, all copies taken on the render thread (~0.25 ms); inference time is device-dependent and **must be measured on target hardware** (expect 40–90 ms for the int8 graph on XNNPACK with four threads) |

Consequences that are enforced in code:

* the integration channel holds **one** snapshot and drops the oldest — a slow device lowers
  scan *coverage*, never frame rate;
* `ScanState.framesDropped` is surfaced, so "sparse scan" can be diagnosed rather than guessed;
* nothing in the frame callback allocates after warm-up (pooled depth, cached plane list,
  reused `HitRequest` fields).

---

## 3. Tuning knobs (in order of impact)

| Knob | Default | Effect |
|------|---------|--------|
| `TsdfConfig.voxelSizeMeters` | 0.02 | 2 cm resolves a 16 mm cable; 3 cm is ~2× faster but merges thin geometry |
| `TsdfConfig.truncationVoxels` | 4 | 8 cm tolerates ARCore's ±3 cm depth noise; lower values sharpen detail and add holes |
| `TsdfConfig.maxBlocks` | 160 000 | ≈ 2 048 m³ of mapped volume at 2 cm; the budget hit is reported in the HUD |
| integration `pixelStep` | 3 (2 at 3 cm voxels) | Linear cost; 3 keeps 640×480 at ~21 k rays |
| `carveFreeSpace` / `freeSpaceStrideVoxels` | false / 4 | Ghost removal at ~3× integration cost |
| `EXTRACT_INTERVAL_MS` | 300 | Required by the brief; below 150 ms it dominates the worker |
| Thermal mode | auto | SEVERE → 700 ms extraction, stride 5, every other frame |
| `ConduitSpec` vertex density (`TubeMeshOptions`) | 2 cm arc sampling | A 10 m run is ~4 k triangles; halving the sampling halves it |

---

## 4. Size and cold start

The app is a measuring tool used in a basement, often on a two-year-old mid-range phone with a
cracked screen. Two budgets follow from that: how long the APK takes to install, and how long
it takes before the project list is on screen.

### What is in the APK, and what is not

| Lever | Where | Effect |
|---|---|---|
| R8 (`isMinifyEnabled`) + `isShrinkResources` | `app/build.gradle.kts` | Removes unused classes and resources, including the unused icons of `material-icons-extended` and the Compose preview code |
| `androidResources.localeFilters` = en/de/es/fr/it/pl | `app/build.gradle.kts` | Drops the ~80 library translations (Material3, Compose) the app can never display; resource shrinking keeps locale folders of *used* resources, so it cannot do this alone |
| `compose.ui.tooling.preview` moved to `debugImplementation` | `app/build.gradle.kts` | Annotation/preview artifact no longer packaged at all |
| `packaging.resources.excludes` | `app/build.gradle.kts` | `META-INF` licences, index and `.version` files of dependency jars |
| `release { ndk.abiFilters = arm64-v8a }` | `app/build.gradle.kts` | The native libraries (Filament, ARCore, LiteRT, `libconstructar_slam.so`) were packaged for two ABIs because the emulator needs x86_64; every ARCore phone is 64-bit ARM. Debug keeps all ABIs |
| Six locales only, assets excluded from VCS | `res/values-*`, `.gitignore` | The 25 MB depth model is imported by the operator, not shipped |

CI builds `assembleRelease` on every push, prints the size of both APKs in the job summary and
as a check annotation (`Measure the APKs`), and lists the largest entries in the package — so
these claims are measured rather than asserted.

Measured on 2026-10-31 (AGP 9.4.1, R8 full mode, arm64 release):

| Stage | Release APK |
|---|---|
| R8 + resource shrink, six locales, both ABIs | 65 MB |
| … with the release ABI filter (`arm64-v8a`) | **25 MB** |

The debug APK stays at 87 MB on purpose: it carries every ABI (so the emulator runs), the
Compose tooling and the inspector. It is a development artifact and is never shipped.

What is left in the 25 MB is the part that cannot be argued away: Filament's material shaders,
ARCore, LiteRT and `libconstructar_slam.so` for one ABI. Dropping any of them would mean
dropping depth, rendering or the room scan.

### Cold start

* **One licence verification.** `EntitlementStore` verifies the stored licence in its
  constructor; `ProjectViewModel.init` used to call `refreshEntitlements()` as well, running the
  same ECDSA verification twice before the first frame. It now seeds its state from what the
  store already computed.
* **The device tag is lazy.** Deriving it reads (or writes) the installation id in
  `SharedPreferences`; it is computed on first use and cached for the paywall, activation and
  verification paths.
* **No I/O on the main thread.** Project listing, photo decode, backup, export and every save run
  on `Dispatchers.IO`; the UI reads a `StateFlow`.
* **Nothing on a timer that is not needed.** The site file's clock only ticks (every 30 s) while
  a session is running; there is no background service, no job scheduler and no alarm.

---

## 5. What to re-measure on hardware

1. Integration time per frame on the target SoC with `depthMode = RAW_DEPTH_ONLY`
   (`ScanState.lastIntegrationMillis` is already in the HUD state).
2. Whether `pixelStep = 3` keeps integration under ~12 ms; if not, raise the step before
   touching the voxel size — resolution is what makes the tool useful.
3. Filament upload cost at 30 k / 100 k / 300 k triangles (`SceneMeshRenderer.upload`).
4. Thermal behaviour over a 15-minute scan: if `THERMAL_STATUS_SEVERE` arrives before
   ~10 minutes, drop the extraction rate first, then the integration stride.
5. Battery: expect 8–12 %/hour with depth + torch off, 15–20 %/hour with the torch on.

---

© 2026 **AndrexTheDev** <hippie.highho@gmail.com> — All rights reserved. See [LICENSE](../LICENSE).
