<!--
  Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
  All rights reserved. Proprietary and confidential - see LICENSE.
  SPDX-License-Identifier: LicenseRef-Proprietary
-->

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
                 │ Channel(1) — refuses a frame while one is queued
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

Back-pressure is explicit: the integration channel holds **one** frame and refuses the frame
that arrives while one is queued, returning its depth array to the pool on the spot (it used to
drop the *queued* frame, whose buffer nothing ever returned). A refused frame increments
`ScanState.framesDropped`, so "the scan looks sparse" is distinguishable from "the device is
overloaded" — the overlay switches to `ScanHint.BEHIND`, with the share in the sentence, once
one offered frame in six is lost (finding 170).

The depth array itself is reference counted (`DepthFrameSnapshot.retain`/`release`, finding 168):
one frame is published to the hit cascade *and* handed to the pipe worker, so it goes back to
`DepthBufferPool` only when the last of them has let go — not when the next frame replaces it,
which said nothing about the worker that was still pinning it.

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
   * **Snap** (`ReticleSnap`, tier 1 only) — the accepted plane hit is measured against the
     room's derived edges and corners: each pair of tracked, non-subsumed polygons is
     intersected and the resulting line is clipped to *both* polygons, so two walls that do
     not touch produce no corner. Within 10 cm the hit becomes the feature's closest point.
     Depth and feature-point hits keep their raw point — they carry no polygon to derive an
     edge from — and the normal stays the aimed wall's. Toggle:
     `HitCascadeConfig.snapToArchitecturalFeatures`.
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

**Plane fitting** (`geometry/plane_fit.cpp`). The step in front of the frame solver: RANSAC for
the consensus set, the covariance's smallest eigenvector for the plane itself, the explained points
removed and the next plane looked for. Three things decide whether it is trustworthy. The RANSAC
budget is derived, not chosen: a sample is clean with probability `w³` for a surface holding a
fraction `w` of the cloud, so a six-surface room needs ~990 samples for 99 % confidence, and a
budget below that fails *quietly* — it reports a facet of a real surface, so a wall arrives in two
pieces and every downstream count is plausible. The plane is re-fitted by least squares before it
is reported, so the noisy triple decides membership and never position. And the sampler is seeded
from `PlaneFitOptions::seed` alone: a fit that differs every run cannot be regression-tested or
reproduced by the person reporting a bad room. `planarity` and `extentMetres` reject the line of
points and the sliver that would otherwise become walls, and a rejected candidate is *spent* — its
points leave the cloud so the same set cannot be proposed again, and the next surface is looked
for, because ending the fit there cost a scan every plane behind a bolt head (finding 191); the normal's sign is fixed towards the
centre of the cloud, so two fits of one room agree.

**Waist-height slice and floorplan** (`tsdf/volume.cpp`, `geometry/floorplan.cpp`). A 2D plan
starts as a horizontal cut through the volume at y = 1.0 m: `sliceSurfaceAtHeight` walks every
shard under the integration and shard locks and emits the voxels whose `|sdf| <= 0.5` and whose
`weight > 0` inside the band, so the result is measured surface at that height and nothing from
free or unobserved space. The cut is taken from the *volume* rather than the mesh on purpose — the
mesh is a triangle soup whose vertices sit wherever the triangulator placed them, so slicing it
depends on the triangulation, while the voxel lattice has a fixed spacing and a fixed cost.
`vectoriseFloorplan` then rasterises the points into a 5 cm grid, resolves the cells that touch only
at a corner (a 45° wall is a staircase of exactly those, and without the resolution its boundary
breaks into four-point bubbles and the room never closes), collects each occupied cell's
empty-sided edges with the interior on the left, chains them into loops through integer corner keys
— so closure is an exact comparison, not an epsilon — and simplifies each loop with
Ramer–Douglas–Peucker, splitting it at the vertex farthest from the start so the simplification does
not pin two arbitrary corners. Of the closed loops the largest one that encloses at least a quarter
of the slice's bounding rectangle is reported: smaller closed loops are slivers where a band's two
faces meet, and calling one of those "the room" is how a doorway scan would claim 0.23 m². Because
the loop is the outer face of a one-cell band, every wall moves outward by up to a cell and the area
reads high — measured +1.25 % on a 4 × 3 m room — and the suite's tolerance is one-sided for that
reason. A doorway leaves the ring open; the call then reports no area and no segments rather than
joining the last wall run to the first, which would draw a wall across the doorway. Manhattan angle
snapping and door/window classification are the next step and are not claimed.

**The plan crossing the bridge.** `nativeFloorplan(handle, height, band, cell, rdpEpsilon,
minSegment)` slices the volume, masks the horizontal surfaces and vectorises what is left, and
returns one `float[]`. The mask lives on the native side because it needs the point normals, which
only exist there; doing it in Kotlin would mean shipping a slice *and* its normals across the bridge
to throw half of them away. The call takes the volume lock, which is why it belongs on the scan
worker and not on a screen: from the UI thread it would stall integration and drop frames.

The payload is a contract neither compiler can check, so it is pinned three ways - the packer is a
plain function with fifteen host assertions on its exact layout, the header size is a named constant
on both sides (`kFloorplanPayloadHeader` / `FloorplanPayload.HEADER`) and
`scripts/verify_sources.py` compares them, and the Kotlin parser refuses any payload whose length
does not prove its own header: a truncated array read field by field returns zeros, and a room with
zero-length walls is a plan that draws and is wrong. Parameters are validated natively (band in
`(0, 1] m`, cell in `[0.01, 1] m`, non-negative tolerances, NaN refused by every comparison) and
answered with `null` rather than a division by zero.

**Semantic masking** (`geometry/surface_mask.cpp`) sits between the slice and the vectoriser,
because a waist-height cut is not a cut through walls: it also crosses every table, counter and
workbench in the room, and a horizontal patch handed to a 2D vectoriser becomes a wall through the
middle of the plan, a partition in the deliverable and a wall area the material estimate pays for.
The separator is the surface normal, which costs nothing extra because a TSDF's gradient *is* the
normal: `sliceSurfacesAtHeight` takes a central difference over the observed voxels in the band
(one-sided where only one side was observed) and hands back oriented points. The mask keeps a point
when its normal is within `maxTiltFromVerticalDegrees` (20°) of vertical, and counts what it
dropped: horizontal patches in one bucket, points whose normal could not be measured in another.
Unmeasured points are *not* admitted - letting them through would reintroduce exactly the phantoms
the mask exists to remove - and the counts are published so a slice made mostly of them is visible
as such.

**Distance-weighted fusion.** Every observation is weighted by 1/Z² (`observationWeight`, clamped
to [1/36, 4]), because a depth camera's error grows with range: without it, one frame taken from
across the room pulls as hard on a voxel as fifty frames taken at arm's length. The weight is
applied inside `fuseVoxel`, under the shard lock, and the running average uses it - so the
observations that are worth more are the ones that decide where the surface is. Two consequences
worth naming: the voxel weight array stopped being a count and became 16-bit fixed point in
1/256ths (`TsdfVolume::kWeightScale`), since a flat array would have truncated a 0.0625 observation
to zero; and `IntegrationStats::voxelWeight` reports the evidence a frame added, so the volume's
`totalVoxelWeightExact()` is exactly the sum of it over every frame - the accounting identity that
catches a fusion which is counted but not stored (finding 181's guard).

**Net wall area and the drywall take-off** (`core/.../wall`). The first `:core` package that
turns geometry into a *purchase order*. `wallSurface` subtracts each wall's openings as a **union**
of axis-aligned rectangles - swept over the x edges the openings introduce, with the y spans merged
inside each strip - so two windows that overlap take board off the wall once. That is not a detail:
subtracting the sum removes board from a wall that is still there, and the estimate then comes out
short, which is the failure that ends with a fitter in a half-boarded room. Openings that do not fit
their wall are clipped to it **and counted**, with the reason enumerated (`NEGATIVE_SIZE`,
`OUTSIDE_WALL`, `ABOVE_WALL`) rather than silently absorbed, because a window 20 cm past the end of
its wall is a measurement the operator can check. `estimateDrywall` then applies the trade's
numbers: boards of 2.50 x 1.25 m (one board is one wall height, which is why the default wall height
is 2.50 m - and why the room's own height is a separate, stated fact, finding 227), CW studs at 62.5 cm centres so every board edge lands on metal (a run gets a stud at
both ends - a 4 m wall is 7, not 6), UW track top and bottom, 29 screws per square metre of
*installed* board (it is the board that is screwed, not the net wall), and 10 % waste on everything
that is cut. A spec that cannot be built - zero spacing, zero board - returns `valid = false` with
every number zero rather than dividing by it. Both entry points are pure and unit-tested; since
phase 5 they are reached from a screen — see §5e below, which is where the last link of the chain
lives.

**DIN 18015 zones and clash detection** (`core/.../wall`). Two checks that stand between a
measured run and a drawing. A tap in AR lands where the reticle rests, not where the standard says,
so `snapToInstallationZone` assigns a height to the socket line (30 cm), the switch line (105 cm) or
the ceiling outlet (ceiling − 15 cm, so it follows the room rather than being a fixed number) and
refuses anything more than the tolerance away - naming the line it was nearest, because "25 cm above
the socket line" is a sentence the operator can act on and a silent snap is a socket at 55 cm on a
drawing that claims the standard. Ties within 1e-4 go to the lower zone: the lines are 75 cm apart,
so a tie means the point is not between two of them. `checkClearance` then holds the measured
polyline against boxes that stand for walls, beams and services, with three deliberate choices: the
intersection test is a real slab clip, because a segment through the *middle* of a beam touches none
of its twelve edges and an edge-only check reports a penetration as half a metre clear; the
non-intersecting distance is closed-form (both endpoints, then the twelve edge-to-segment distances),
never sampled, because a step size that cannot miss a crossing is finer than the clearances being
asked about; and invalid input - a non-finite point, a route of one point, an inside-out box -
returns `valid = false` with no hits, since "clear" computed from nonsense is worse than no answer.
The zones are wired into the *Walls* screen (§5e). The clearance check is **not**: nothing in
`:app` constructs an `Obstacle` yet — the app has no source of walls, beams and services as boxes,
and a clearance card with nothing to check against would print a claim it cannot back, so it stays
in `:core` with its tests until a source exists.

**Manhattan-World frame** (`geometry/manhattan_world.cpp`). A room is meant to be orthogonal;
the planes fitted from depth are not, because each carries its own noise and the pose graph's
drift, and the error is systematic - two walls that should be 4.00 m apart read 4.03 m and the
corner reads 87.6°. The solver finds the frame the room is trying to be and snaps to it only the
planes that are already close: the dominant direction is the top eigenvector of Σ w·n·nᵀ, the
remaining degree of freedom is one turn angle in the plane perpendicular to it, found by a 1-D
search of a Tukey-weighted inlier score, and the third axis is the cross product. Both the search
and the kernel are the finding (187): in a square room the two horizontal families carry nearly
equal weight, so the eigen-decomposition's second direction is ill-conditioned and a single
crooked wall tilted the "square" pair by 15.7°; and a hard tolerance cut is a cliff the search
walks into, where a bay window 35° off square was worth enough at 19.4° to tilt the room by 15°
again. A plausible-looking wrong answer, on a
problem nothing else in the app checks. A plane with a centroid is squared *through* that
centroid, so a wall 8° off stays at the distance it was measured at instead of moving by its
cosine; a plane more than 20° off (a bay window, a chamfer) is returned untouched, bit for bit,
and a set of parallel walls produces no frame at all rather than an invented one. The solver
decides geometry only: no length, no node position and no fitting count passes through it. The
frame's vertical is *not* taken from the top eigenvector (finding 189): in a room whose floor and
walls carry comparable weight the two horizontal families couple through one crooked wall and their
rotated pair out-eigenvalues the vertical, so each eigenvector is tried as the primary and the frame
that explains the most inliers wins; a frame also needs two of its three axes evidenced, which is
what separates "a room" from "a floor and a ceiling". The *plane* half is wired as of finding 230:
`nativeFloorplan` builds a cloud from three bands of the volume (capped at 800 points each, six planes,
700 RANSAC iterations, so the fit stays inside the 400 ms call), fits the room's walls with `fitPlanes`, solves the frame and hands it to the floorplan as a `ManhattanHint` - so a rotated room
is squared onto the room rather than onto the world axes, and a room with one long wall is not
weighted by that wall alone. A hint that disagrees with the slice's own runs by more than 30 degrees
is refused and the runs decide, because a frame fitted from the wrong cloud would rotate a room that
is already right. `nativeAlignManhattanFrame` returns the frame that was used (thirteen floats: the
residual, the plane counts and three axes); nothing else consumes these planes yet.

The *floorplan* half is wired and observable (finding 229): a room that is square on site comes out
of a hand-held scan as a parallelogram - 5 degrees of shear on every corner, systematic, and
averaging does not remove it - so `vectoriseFloorplan` squares its own loop before anything is
drawn from it. The runs *are* the evidence: the length-weighted mean of their **quadrupled** angles
is the frame (doubling is not enough - in an orthogonal room the two wall families are 90 degrees
apart, so `2θ` differs by 180 degrees between them and the two cancel exactly), each run within the
tolerance is moved onto that frame, and the corners become the intersections of the straightened
runs, so the loop stays closed and a corner stays one corner. A run further off than the tolerance
- a bay, a chamfer, a party wall at 35 degrees - keeps its measured angle. A fit that would change
the enclosed area by more than a quarter is rejected and the measured loop is reported instead. The
tolerance is a parameter of the JNI call (default 10 degrees, 0 disables), and `nativeLastManhattan`
reports what the step did: the residual in degrees, the runs snapped and the corners moved.

**Passive marker detection** (`calibration/apriltag_detector.cpp`, `calibration/tag_scale.cpp`).
Tier 2 of the calibration ladder — a printed AprilTag of known size, seen in enough frames, gives
the scan a length that does not depend on an operator holding a bank card still. Since finding 231
both halves are in the tree. The pixel half is vendored: AprilTag 3.1.0's C core, BSD-2-Clause,
unmodified, one family (tag36h11), in `third_party/apriltag/` with `VENDORED.md` recording what was
taken, what was left and how it was obtained, and with the attribution in `NOTICE` and on the Help
screen. `calibration/apriltag_detector.cpp` is the single translation unit that includes it — a
decoder this repository did not write must not leak `image_u8_t` into a layer it did — and it turns
detections into `constructar::TagDetection` in metres, which needs two things the caller must
supply: the pinhole intrinsics and the printed size of the black square. Without them the detector
is `NoTagDetector`, whose `available()` is false and whose reason names the camera; an empty
detection list from a working detector means "no tags in view", and the two answers stay apart for
the reason finding 98 established for the RTAB-Map back-end. The other half is the marker-to-scale
update, in two shapes: `MarkerBaseline` (`:core`) for one printed marker seen from two places - the
camera-to-tag vectors rotated into world axes give one baseline, ARCore's camera positions give
another, and their ratio is the correction - and `estimateScaleFromTagSightings` for a printed
sheet of tags: pair ratios of nominal over measured against the printed layout, a median to find the
majority, a deviation filter for the tag that was mis-detected, and a baseline-weighted mean of what
survives. It publishes a factor, the pairs that agreed, the pairs it rejected and the longest
baseline behind it, and it decides nothing — the acceptance window lives in `:core` beside the
bank-card reference, which is why the native directory does not contain its two numbers even in a
comment. **The decoder is vendored and wired to one screen** (finding 235), and what is still unproven is
said rather than implied: no device has ever pointed at a real tag.
`nativeDetectAprilTagScale` is the door, `:ar-engine`'s `MarkerFrameReader` is the only thing that
knocks on it - it packs one ARCore camera plane with `:core`'s `LuminancePlane` (rows are strided;
the packed buffer is what the native layer is told is the width), supplies the session's intrinsics
and the printed size, and keeps the three answers apart: nothing at all for a question it could not
ask, an empty report for a frame without a tag, metres otherwise. `CameraPlanes` is the only adapter
that touches `android.media.Image`, and it closes the image on every path: ARCore's CPU image queue
is two deep, so a leak stalls the camera within a second. `MarkerProbe` composes reader and sweep
(every tag the decoder read - a printed sheet is several markers, and taking only the nearest
made `markerIds` a count that could never exceed 1 (finding 236) - then the camera pose, shared by
all the sightings of one frame, so a pair inside a frame has a zero baseline and is refused), and
`MarkerSweep` (`:core`)
holds what a screen actually has: a *sequence*. Three things are refused there rather than in a
composable: a sighting from another ARCore session (a new session is a new world origin - finding
217 one layer up), a sighting older than the newest eight of its marker, and the temptation to
publish a factor from two sightings at the same spot, which stays `Watching`. The two trust knobs
come from `TagScaleOptions` over `nativeTagScaleDefaults()`, so the Kotlin side has no second
definition of a baseline worth measuring. `TsdfScanPipeline.openMarkerProbe` builds the whole chain
against the engine it already owns, and `ArMeasureController` samples one frame in fifteen - a
sweep is answered by a walk, not by 30 pictures a second - and drops the sweep when a new session
begins. `estimateScaleFromTagSightings` - the printed *layout* path, which needs a sheet of tags -
is still reached from nowhere, and no stored metre depends on a marker today.

**Pose graph** (`slam/pose_graph.cpp`). Gauss–Newton on SE(3) with central-difference Jacobians,
block-sparse Hessian solved by preconditioned conjugate gradient, backtracking line search (a
full step overshoots on a room with one closure), snapshot/rollback so a diverging iteration can
never corrupt the trajectory. Loop closures carry 100× the odometry weight because they are
appearance-verified. The graph is *not yet driven by the scan pipeline* (finding 98): it is
host-tested and reachable from `NativeSlamEngine`, but no keyframe reaches it today, so the app
measures on ARCore's own tracking.

**Monocular depth fusion.** Optional and strictly additive, and it names itself: the model's asset
path comes from `BuildConfig.DEPTH_MODEL_ASSET` (one declaration, so a variant that ships a
different export cannot look for the old file and switch the feature off without a compile error),
`MonocularDepthSource` reports whether the load succeeded, and `DepthAvailability` turns that plus
ARCore's `depthSupported` into one of three states — `AI_FUSED`, `ARCORE_ONLY`, `PLANES_ONLY` — which
the HUD prints when the answer is the third one. The weights are not in this repository.

`YuvFrame` copies the camera planes on the render thread (~0.2 ms), the inference worker runs
Depth Anything V2 Small through LiteRT on XNNPACK, `MetricDepthCalibrator` fits
`1/z = s·d + t` to ARCore's metric samples on the *same* frame, and `AiDepthFusion` writes a
densified millimetre frame in which confident ARCore pixels are copied verbatim and only holes
are filled. `TsdfScanPipeline.submitFusedDepth` accepts it with an 800 ms TTL and integrates it
with no separate confidence gate (the fusion already applied one).

XNNPACK is the *only* backend, deliberately: ARCore's standalone LiteRT runtime no longer ships
the NNAPI delegate, and the Vulkan GPU delegate cannot execute the int8 graph that the licence
allows us to ship (Apache-2.0 Small). The estimator reads the graph's own tensor dtypes and
quantises/dequantises accordingly, so an int8 or a float32 export both work without code
changes.

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

## 5a. Controls and the gloved hand

`SiteColors.gloveTouchTarget` is 64 dp and `GloveButton` is 72 dp; every `IconButton` in `:app`
carries one or the other. The convention is enforced, not remembered:
`check_glove_buttons_are_big_enough` walks every `IconButton` call in `:app` and refuses a bare one
(Material's 48 dp default) or an explicit size below the target, naming the size it found
(finding 205). The two AR buttons — export and leave, the primary actions of the screen used with
gloves on — are 72 dp. A card that opens a room passes `role = Role.Button` to `clickable`, and the
room picture is decorative by design: the row prints name, runs, metres, area and perimeter as text,
which is what a screen reader reads.

---

## 5b. The plan page in the protocol

The protocol's floorplan page is a page of its own, printed before the signature: a closed path
through the stored ring, a grey floor, a caption with the room's area and perimeter, and - when the
ring was closed by a person rather than by the scan - the sentence that says so.

The split follows the module boundaries that already existed:

| Where | What it owns | Why there |
|---|---|---|
| `:app` `data/RoomPlanPage` | which ring, and which words | the sketch is the app's, and `:export` has no string table: it receives a title, a caption and a note, never composing a sentence (finding 100's rule) |
| `:export` `pdf/FloorplanLayout` | the projection into the printable box | geometry that must be testable without a `PdfDocument`, exactly like `PageFlow` |
| `:export` `pdf/ProtocolPdfWriter` | ink: path, stroke, text | the writer is the only place that knows what a page looks like |

`FloorplanLayout` borrows the SVG's three rules so paper and phone cannot show different rooms: one
scale for both axes (the longer extent sets it), world `z` running down the page, and a wall that is
a physical 10 cm at that scale clamped to `[0.75, 6]` points. It returns `null` - and the writer then
prints the heading with no drawing rather than an empty frame - for fewer than three corners, a
non-finite coordinate, a box without area, or an outline with no extent at all. A caller that has no
closed ring hands over `null` and the document simply has no plan page.

`check_the_protocol_prints_the_room_plan` walks the chain - mapper, request, service, writer, and the
two resources in six locales - because every link of it can be dropped without a compile error: a
nullable field nobody passes, a section that is passed and never drawn, a page drawn after the
signature is already on the paper (finding 206).

---

## 5c. What the screen says out loud

Every acting row in `:app` announces three things: what it is (`role`), what it does, and what it
currently is (`selected`, `stateDescription`, or the value a `toggleable` carries). The conventions
live in one place — `check_every_interactive_row_announces_itself` — because each of them is a
modifier in one file and a promise in another:

| Convention | Why it is not optional | Where it is enforced |
|---|---|---|
| `clickable`/`selectable`/`toggleable` carry a `role` | an unlabelled clickable is announced as tappable and nothing more | every call in `:app`, read with balanced parentheses |
| a `Switch` hands its tap to its row (`onCheckedChange = null`) | otherwise the control is a second node that cannot name the setting it changes | every `Switch(` in `:app` |
| every `OutlinedTextField` has a `label` | a placeholder is decoration inside the box and not the field's name; Material draws the label in the same spot when the field is empty | every field in `:app` |
| a chooser is a `selectableGroup` | "16 mm, selected" only means something inside a group of five | the severity row and the conduit-spec row |
| the signature pad has a name, a state and a clear action | a canvas with two pointer inputs is silence, and its clear button is across the screen from it | `SignaturePad.kt` |
| the AR tap surface offers `onClick` | it is the only way to set a point; a camera preview with no action cannot be measured by a screen reader user | `ArMeasureScreen.kt` |

The words are resources like every other word in the app: `a11y_signature_pad`, `_signed`,
`_empty`, `_clear`, `a11y_ar_view` and `a11y_ar_place_point`, in six locales, checked by
`tools/i18n_audit.py` and by `A11yLabelsTest` — which cannot judge a translation, but can refuse
two sentences that must not be confused in the ear being the same string.

---

## 5d. A point that was moved, and the screen that says so

`ReticleSnap` pulls an accepted plane hit onto the nearest derived corner or edge within 10 cm, in
the engine, before the hit can become a route node. That is the right default and it is silent by
nature: the operator's aim was not the measurement, the architecture is. Silence is the problem —
a reticle that jumps two centimetres and a length that changes between two taps read as an unstable
tool, so the operator taps again and keeps whichever number he likes.

The move therefore travels with the hit:

| Step | Where | What it carries |
|---|---|---|
| record | `:ar-engine` `SnapInfo` on `SurfaceHit` | the kind of feature and the distance moved |
| survive the window | `HitStabilizer.stabilize` | the record stays while the point becomes the median of a settled window — every sample was snapped to the same feature |
| say it | `:app` `SnapReading` + `SnapFeatureKind.snapMovedRes` | centimetres from `LengthFormatter`, one whole sentence per kind per language |
| show it | `:app` `SnapMarker` | a ring at the centre of the AR view, where the reticle samples |

Two deliberate omissions. The record is **not** part of `SurfaceHit.toRouteNode`: a stored node
records where the point *is*, and the protocol prints that position, not the reticle's opinion of
it. And the sentence is **not** one resource with the feature as an argument: German needs *an der
Ecke* against *an der Bodenkante*, Polish needs a different case again, and a placeholder would
force every translator to bend a sentence around a noun they cannot decline. Four sentences per
language is smaller than the grammar it avoids.

`check_the_hud_says_when_the_reticle_moved` names the five links — record, snap, sentences, digits
with their guard, screen — and the four resources in six locales (finding 213).

## 5e. The take-off, the floor it is measured from, and the screen that shows both

Three `:core` engines - net wall area, the drywall estimate, the DIN 18015 zones - plus a fourth,
the clearance check, had unit tests and no screen. The *Walls* button on the project card now opens
`WallScreen`, and `WallPlanBuilder` is the whole of what stands between the project file and it:

| Question | Answer, and why that answer |
|---|---|
| What is a wall? | An edge of the room's stored ring (`FloorplanSketch`), **including the closing one**: a 4 x 3 m room is 14 m of wall, not 11 m, and 3 m of missing wall is a board and a half on the order. An open ring is not a room yet and produces no walls at all - the card already offers to close it by hand. |
| Which metres? | The project's `calibrationScale` is applied to the run and to the nodes, once, at the reporting layer - the same rule the bill of materials follows. It is **not** applied to the wall *height*: the height is not a length the session measured. |
| How high is the wall? | The height the operator stated on the wall screen (`ProjectRecord.wallHeightM`, 2.00–4.50 m through `statedRoomHeight`), or `DEFAULT_WALL_HEIGHT_M` = 2.50 m when nobody stated one. The 2.50 m is the *board's* height - the figure the estimator assumes so one board covers floor to ceiling - and it used to be the height of every room: a 2.80 m room printed 35.0 m² where it has 39.2 m², four boards short (finding 227). `WallPlan.wallHeightM` and `wallHeightAssumed` carry the number and whether it was stated, one of them reaches the drawing, the areas, the boards and the ceiling zone, and the screen prints an assumed height in red as an assumption. It is deliberately not derived from the measured points: a run is drawn at socket height, so the tallest point in a room is not its ceiling. |
| Where is 30 cm? | On the finished floor - and the only honest source of that is a tracked horizontal plane. ARCore's world origin is wherever the session started, usually at chest height, so `y` alone is a height above nothing an electrician would recognise. `FloorReference` picks the highest upward-facing tracked plane at or below the viewer within 3.5 m (`null` when there is none), `HitResolution` carries it out of `ArHitSource` where the planes are alive, `ProjectRecord.floorHeightM` stores it, and without it `WallPlan.installations` is `null` and the screen says so instead of printing a socket at 1.40 m. |
| What is not shown? | The clearance check, because nothing constructs an `Obstacle` (§4). Openings are shown: since finding 220 the elevation records doors and windows, `wallSurface` subtracts them as a union, and a room with none says so rather than printing a silent `0.00 m²`. |

The screen prints the assumptions under the purchase (board size, stud spacing, waste) and the room
height it was made at, for the same reason the protocol prints its expected error: a number without
its conditions is an argument. Every
digit comes from `LengthFormatter`, every word from six locales, and the retrofit is backward
compatible — `floorHeightM` is defaulted and `null` is omitted by the repository's JSON, so a room
recorded by the previous release loads unchanged with no zones and no complaint.

`check_the_wall_screen_uses_the_core_numbers` names each link — the three calls, the closing wall,
where the calibration factor is and is not applied, the floor's four hops from plane to screen, the
route and the button, the four sentences a reader needs to understand an empty or refused state —
and the eleven resources in six locales (finding 214).

---

## 6. Persistence

A project is one JSON file (`filesDir/projects/<uuid>.json`) written atomically (temp file +
rename). Routes store node positions, normals, the hit source and confidence — so a run
measured from soft hits is identifiable months later. The scan mesh is *not* persisted: it is
10 MB of derived data, regenerated on demand, exported on request.

A run that is still being measured is written as it grows
(`filesDir/drafts/<project id>.json`, same atomic write, one file per project). It is not a
project and never appears in a backup: it exists so that a process killed mid-measurement — a
phone call, a low-memory device, a battery that gave up — costs the operator nothing. On the next
start the AR screen offers the run back. What survives exactly is what the paperwork needs (the
points, the conduit spec, whether the loop was closed); what cannot survive is the ARCore world
origin, so the recovered route is marked `recovered`: it counts fully in the bill of materials
and the protocol, and it is kept out of the 3D export and the AR preview, where its coordinates
would be a length of conduit floating somewhere in the room.

**The room's picture.** Next to the JSON, in `filesDir/projects/`, the product plan puts a
`thumbnail.svg` for the home screen's card — a few hundred bytes of polygon instead of a camera
frame, drawn by `:export`'s `FloorplanSvg` from the outline in world metres (one scale for both
axes, plan view: world `x` right, world `z` down, walls 10 cm thick to scale with a 1 px floor).
The chain that writes it, in the order the data travels:

1. `TsdfScanPipeline.floorplan()` — `withContext(dispatcher)` puts the slice in the queue behind the
   frame being integrated, so it takes the volume lock from the one thread that already owns it. A
   bounded 400 ms wait: a picture is a convenience and the run must not wait for it.
2. `ArMeasureController.finalFloorplan()` — called from `commitRun`, the moment a run is finished and
   the volume still holds the walls that were walked. After `release()` the volume is gone.
3. `ArMeasureScreen` hands the plan to `onFloorplan` (the screen has no repository, and does not
   grow one for a picture).
4. `ProjectViewModel.saveFloorplanThumbnail` → `RoomThumbnail.svg` — the largest **closed** loop is
   the room; a doorway leaves the ring open, and closing it here would draw a wall across the door.
5. `ProjectRepository.saveThumbnail` — `files/projects/<id>/thumbnail.svg` through
   `SafeFiles.writeAtomically`, with the id token-checked and the text capped at 256 kB; `delete()`
   removes the whole folder, so no orphan picture outlives its room.

A per-room folder rather than a sibling file keeps `<id>.json` a single file that `list()` and
`backupEntries()` read by extension; a folder is invisible to both. The SVG is **not** in the
backup - it is derived, and it is rewritten the next time a run is committed - but the outline the
card draws *is*, because it is a field on the project.

**The card draws the project, not the file.** Android has no SVG renderer, so step 5's output is
for laptops, mail and paper; what the home screen draws is a field on the room itself. The same
`largestClosedLoop` decision is taken a second time by `RoomThumbnail.sketch`, which turns the loop
into `ProjectRecord.sketch: FloorplanSketch?` — a list of `{xMeters, zMeters}` corners, at most
`FloorplanSketch.MAX_POINTS` (512, and three needed before anything is drawable), written by the same
`repository.save` that writes every other field and projected by `RoomThumbnail.pathPoints` into the
40 dp `Canvas` in `HomeScreen.RoomPicture`. The projection is `FloorplanSvg`'s (one scale, centred,
world `z` down) because the card and the exported picture are the same room; the two are pinned to
each other by a test, not by a comment.

**The open ring is data, not a failure.** A scan of a room with a doorway in it traces a ring that
never closes; round 94 stored nothing at all for it, and the payload those corners lived in is freed
when the run is committed - so the only recovery was re-walking the room. The sketch is now stored
whatever its closure says, `FloorplanSketch.Closure` is the three-way answer (`Open`, `Measured`,
`DrawnByHand`), and `closeRoomByHand` performs the one gesture that turns an open ring into a room:
`ProjectViewModel.closeRoomByHand` loads the room, takes `closedByHand()`, saves it through the same
atomic write, writes the SVG from the sketch (`RoomThumbnail.svg(sketch)`, the same renderer the
payload path uses), and refreshes the list. The card's chip asks before it acts, and the row shows
that the wall was drawn. Nothing here guesses: a doorway stays a doorway until a person says
otherwise.

The same outline is read a third time for the card's **size** badges: `RoomSizeText.of(sketch)`
turns the ring into the digits of its area (`PolygonMath.area`) and its perimeter (the closed walk,
last corner back to the first) and returns `null` for anything the picture would refuse — the two
states cannot disagree about whether the room was measured. The units are resources
(`card_area`, `card_perimeter`); a unit typed into a screen would ship in one language, and the
i18n audit reads resources, not Kotlin.

Two consequences worth stating. The outline is inside the project JSON, so `backupEntries()` carries
it: a room restored on another phone still draws its plan, while the `thumbnail.svg` beside it is
regenerated or simply absent (`explicitNulls = false` means a room that was never scanned writes no
`sketch` key at all, and such a file loads unchanged). And the outline is *not* the scan: it is eight
floats per corner, which is the reason it can live in the JSON while the TSDF volume never does.

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

**No reflection, typed SceneView.** `SceneViewMaterialProvider` calls SceneView 4.63.0's typed
`MaterialLoader.createColorInstance(color: Int, …)` and `createUnlitColorInstance(color: Int)`
directly. An earlier revision looked the factories up reflectively; that is gone, because a
silent runtime miss (geometry simply not drawn) is a worse failure than a build error, and
because reflection is unverifiable by the compiler, by R8 and by review. The version is pinned
exactly (`gradle/libs.versions.toml`, `sceneview = "4.63.0"`) and
`scripts/verify_sources.py` fails the build on any `Class.forName`, `getDeclaredMethod`,
`getMethod`, or `javaClass.methods` in `:core`, `:ar-engine`, `:app`, `:export` or
`:native-slam`.

**Material lifetime.** Both factories are `@MainThread` on `MaterialLoader`, so
`SceneViewMaterialProvider` pre-warms the whole palette (the six preset colours plus the
translucent scan overlay) in its constructor — which runs from `onSceneReady`, on the main
thread. A frame then only reads a `ConcurrentHashMap`: no material is created, and no object is
allocated, in the render loop.

**Numbers in an exported document.** One policy, three cases. Lengths, quantities and the
duration line use `Locale.ROOT`, so the same protocol prints `14.82 m`, `2 h 15 min` and
`1 250` on every phone - a signed handover document must not change its digits because the
device was set to another language, and formatting with the device locale once put
Arabic-Indic digits (`٢ h ١٥ min`) into an English page. Money is the deliberate exception:
`MoneyFormatter` follows the phone's convention (`1.250,00 €` in German) because the price is
read by the customer, and it forces **euro** regardless of locale. Dates are two cases of
their own: free-form labels (a diary line) follow the phone's `DateFormat`, while the fixed
numeric day (`01.11.2026` in the project list and on the licence expiry) is pinned to
`Locale.ROOT` - a `SimpleDateFormat` takes its *calendar* from the locale, so a Thai phone
printed a licence expiring in 2569. The CSV is the third case:
it is data for a spreadsheet, so it is always `.`-decimal with `;` separators, plus a UTF-8
BOM for Excel - see `docs/TESTING.md` findings 28 and 30.

**Localisation seam.** The engine modules contain no user-facing strings. `TrackingMonitor`
publishes a `Guidance` code, `RouteSession` a `RouteWarning` code, the hit cascade a
`SurfaceKind`/`HitSource`, `ArSessionController` an `ArReadiness` branch. `:app` maps each to
`stringResource` in `ui/Labels.kt`, and every length is formatted by `LengthFormatter` - where
the resource carries the unit (`hud_length` is `%1$s m`), the digits arrive through the formatter,
never through a `%f` format, because a `%f` resource takes its decimal mark from the device and put
`12,50 m` beside `28.80 m` in one HUD column (finding 39).

Since finding 222 the two surfaces are deliberately different, and the difference is the point.
`LengthFormatter` stays `Locale.ROOT` - PDF, CSV, JSON, the PLY floats - because a document is a
protocol: a German Excel reads a `,` as a column separator. A *screen* asks for the reader's mark:
`CompositionLocalProvider(LocalDecimalMark provides rememberDecimalMark())` in `AppRoot`, and the
screens call `shownLength`/`shownLengthWithUnit` from `ui/DecimalMark.kt`. The character itself is
`NumberText.decimalMark(locale)` in `:core`, guarded so a locale whose separator came back as a
digit cannot turn `1.40` into `1140`. That is what makes six locales complete without the engines
carrying a Context — and it keeps the engines unit-testable on the JVM.

---

© 2026 **AndrexTheDev** <hippie.highho@gmail.com> — All rights reserved. See [LICENSE](../LICENSE).
