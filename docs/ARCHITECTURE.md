# Architecture

## 1. The one rule

**Numbers live in `:core`, pixels live in `:ar-engine`.**

Everything that decides a length, an angle, a clip count or a mesh vertex is pure Kotlin in
`:core`, with no Android imports (enforced by a Gradle check: `forbidAndroidImports`). That is
what makes it possible to test the measurement engine on a laptop, in CI, without a phone —
and it is why the AR overlay, the PDF and the GLB can never disagree: they all consume the same
`MeshData` and `RouteMetrics`.

---

## 2. Threads

```
 ┌──────────────────────────── main thread ──────────────────────────────┐
 │ Compose UI · tap handling · RouteSession mutation · anchor creation   │
 │ · export (SAF) · project JSON                                         │
 └───────────────▲───────────────────────────────────────┬───────────────┘
                 │ StateFlow (immutable snapshots)       │ requestTap(x,y)
 ┌───────────────┴───────────────────────────────────────▼───────────────┐
 │ SceneView render thread: onSessionUpdated(session, frame)             │
 │  · DepthCapture.capture()  → pooled ShortArray copy (~0.15 ms)        │
 │  · ArInteraction.onFrameEnter() → hit cascade on the *current* frame  │
 │  · RouteSession.onFrame() → read refined anchor poses                 │
 │  · TrackingMonitor.onFrame() → cosmetic state only                    │
 └───────────────┬───────────────────────────────────────────────────────┘
                 │ Channel(1, DROP_OLDEST)
 ┌───────────────▼──────────────────── pipe worker (1 thread) ──────────┐
 │ TsdfScanPipeline.integrate()  → JNI → C++ threads (3)                │
 │ every 300 ms: extractMesh()   → Surface Nets → ScanMesh → StateFlow   │
 └──────────────────────────────────────────────────────────────────────┘
```

Why a tap is resolved on the render thread: ARCore only guarantees a `Frame` until the next
`update()`. Resolving it later from the main thread is the classic "my tap registered 30 cm
off, sometimes" bug. The tap request is queued (`ArInteraction.requestTap`), resolved inside
`onSessionUpdated`, and the result is applied back on the main thread — because
`Session.createAnchor` belongs to the UI thread.

Back-pressure is explicit: the integration channel holds **one** frame and drops the oldest.
A dropped frame increments `ScanState.framesDropped`, so "the scan looks sparse" is
distinguishable from "the device is overloaded".

---

## 3. Data flow of a single measurement

1. **Tap** → `ArInteraction.requestTap(x, y, w, h)`.
2. **Ray** → `ArHitSource.screenRay()` inverts `P · V` from ARCore's camera matrices. Inverting
   the projection is what keeps taps correct in landscape (the depth image is never rotated).
3. **Cascade** (`SurfaceHitResolver`):
   * *tier 1* — every tracked, non-subsumed `Plane`; ray/plane intersection then
     `PolygonMath.contains(polygon, point, tolerance = 3 cm)`. Nearest valid plane wins.
     Rejections are recorded with a reason ("ray outside every tracked polygon").
   * *tier 2* — ARCore `HitResult.Type.DEPTH_POINT` from `frame.hitTest`.
   * *tier 3* — our own march through `acquireDepthImage16Bits()` (`DepthImageMath.raycast`),
     confidence-filtered, refined between samples.
   * *tier 4* — `HitResult.Type.POINT`, confidence 0.4.
   * A normal is derived per tier (plane normal, least-squares fit of a 5×5 depth patch, or
     flipped towards the camera) and always oriented towards the operator.
4. **Node** → `RouteSession.addNode(hit)`: rejects taps closer than 2 cm to the previous node,
   creates an `Anchor` whose +Y axis is the surface normal, appends a `RouteNodeRecord`.
5. **Anchor refinement** → every frame `RouteSession.onFrame()` re-reads anchor poses and moves
   nodes that drifted by more than 5 mm (below that the mesh would shimmer).
6. **Measurement** → `PolylineMetrics.measure(nodes, spec, closed)` on the *surface* points.
   The conduit's centreline is offset by `axisOffsetFromSurface` only for geometry.
7. **Geometry** → `RouteGeometry.build()`: offset → fillet (`PathSmoother`, RMF frames) → tube
   sweep or duct sweep → `MeshData`.
8. **Render** → `RouteSceneController` uploads the mesh when its signature (node positions
   quantised to 1 cm + spec + loop flag) changes, and `SceneMeshRenderer` pushes
   positions + tangent-frame quaternions into Filament.

---

## 4. Native reconstruction

**TSDF.** 2 cm voxels, 8 cm truncation (4 voxels), block-hashed (8³ voxels per block, 160 k
block budget). SDF is `(d − z)` with positive *in front of* the surface — ARCore/Filament
camera convention (−Z forward), Euclidean range (`depthIsOptimalAxis = false`). Unobserved
voxels are **NaN**, and Surface Nets skips any cell touching a NaN: initialising unknown space
to 0 or 1 produces phantom walls at scan edges and inside every unmapped corner.

Free-space carving (marking camera→truncation as "outside") is implemented but **off by
default**: it takes integration from ~21 ms to ~133 ms per 640×480 frame. The UI exposes it as
an explicit "ghost removal" pass.

**Surface Nets.** One vertex per sign-changing cell, placed at the centroid of the edge
crossings; one quad per sign-changing edge, split into two triangles with a winding consistent
with the SDF gradient. Output is un-welded per face, which is what both Filament and glTF want.

**Pose graph** (`slam/pose_graph.cpp`). Gauss–Newton on SE(3) with central-difference Jacobians,
block-sparse Hessian solved by preconditioned conjugate gradient, backtracking line search (a
full step overshoots on a room with one closure), snapshot/rollback so a diverging iteration can
never corrupt the trajectory. Loop closures carry 100× the odometry weight because they are
appearance-verified.

**RTAB-Map bridge.** Optional; compiled only with `-DCONSTRUCTAR_WITH_RTABMAP=ON`. Without it
the built-in proximity proposer suggests closures (same room visited twice → graph distance
small, traversed distance large) which the UI asks the operator to confirm.

---

## 5. AR failure handling

| Failure | Detection | Behaviour |
|---------|-----------|-----------|
| No camera permission | `checkSelfPermission` | Rationale + request; the screen stays usable as a manual length entry |
| ARCore missing / too old / unsupported | `ArCoreApk.checkAvailability` + `requestInstall` | Typed `ArReadiness` variants, each with an actionable message |
| Camera busy | `CameraNotAvailableException` on resume | One retry after 250 ms, then a clear message |
| Tracking loss | `Camera.trackingState` + `TrackingFailureReason` | Coaching line (`trackingMessage`), measurement still allowed in `LIMITED` |
| Low light | `LightEstimate.ambientIntensity` | Automatic torch with 40/120 lux hysteresis and 4 s dwell |
| Weak depth | mean confidence of the frame, usable-pixel ratio | Depth tiers downgrade to `LIMITED`, guidance suggests re-aiming |
| Thermal throttling | `PowerManager.currentThermalStatus` | Extraction 300 → 700 ms, integration stride 3 → 5, every other frame integrated |
| No native library | `NativeSlamEngine.isAvailable` | Scanning disabled, measuring unaffected |

---

## 6. Persistence

A project is one JSON file (`filesDir/projects/<uuid>.json`) written atomically (temp file +
rename). Routes store node positions, normals, the hit source and confidence — so a run
measured from soft hits is identifiable months later. The scan mesh is *not* persisted: it is
10 MB of derived data, regenerated on demand, exported on request.

Schema version `1`; `ProjectMigrations` is the place for the next one.

---

## 7. Decisions worth knowing about

**Depth units.** ARCore exposes `acquireDepthImage16Bits()` (millimetres) and
`acquireDepthImageConfidence()`; there is no `acquireDepthImageMeters`. The 16-bit plane is
used and divided by 1000 — the metric view the brief asks for, without the lossy visualisation
path.

**Zero-copy paths.** Three integration entry points exist: pooled `ShortArray` (default,
`GetShortArrayElements` pins the array), a direct `ByteBuffer` (one copy fewer), and
`AHardwareBuffer` locking on API 26+ (truly zero-copy, but some devices expose the depth buffer
as a PRIVATE format and refuse the lock — the caller falls back automatically).

**The HUD label.** The length label is a 3D-anchored readout rendered as a Compose chip
projected from the run's anchor, not a textured quad. Text stays vector-crisp at every
distance, needs no `.filamat` and no texture upload per update, and is readable in sunlight.
The trade-off is that it is screen-space, so it cannot be occluded by geometry — accepted
deliberately.

**Reflection for materials.** `SceneViewMaterialProvider` resolves Filament material factories
reflectively because SceneView's `MaterialLoader` signature changed three times across majors.
A build error there would block every upgrade; a runtime miss only means meshes are not drawn,
and the measuring path keeps working. Pin the version and replace it with direct calls when you
freeze dependencies.
