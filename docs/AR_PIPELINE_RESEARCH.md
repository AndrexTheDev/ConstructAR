<!--
  Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
  All rights reserved. Proprietary and confidential - see LICENSE.
  SPDX-License-Identifier: LicenseRef-Proprietary
-->

# AR pipeline: open-source research and staged overhaul

Status: **8 November 2026**, branch `arena/fb929cf6-constructar`, baseline `9b3f92a`.
This is an engineering survey, not a claim that ConstructAR is already more accurate than another
product. Upstream commit ids are recorded so that the licence and capability review is repeatable.
No third-party source was copied for the changes in this branch.

## 1. Decision framework

A candidate earns a place in the live path only if it answers all six questions:

1. **Which measured defect does it fix?** A larger feature list is not evidence.
2. **Can it preserve frame identity?** RGB, depth, confidence, intrinsics, timestamp and camera pose
   belong to one acquisition; asynchronous work must not mix them.
3. **Can it fail closed?** Unverified depth, an ambiguous closure or an unfinished optimisation
   remains unknown rather than becoming a measurement.
4. **Does it fit a phone?** Bound latency p50/p95, peak RSS, model size, queue depth and thermal
   behaviour on the actual device classes.
5. **Is every component redistributable here?** Repository licence, model weights, feature
   extractor, vocabulary and transitive native dependencies are separate checks.
6. **Can a reproducible experiment reject it?** Keep the existing pipeline as the control and
   publish the dataset, alignment, thresholds and negative results.

The existing ARCore visual-inertial tracking, strict hit cascade, sparse TSDF, Surface Nets,
Manhattan fitting, AprilTag calibration and measurement-provenance model are assets. Replacing them
wholesale would create two sources of pose and scale without demonstrating a benefit.

## 2. Current gaps mapped to code

| Gap found in the baseline | Code boundary | Required response |
| --- | --- | --- |
| A delayed AI depth image could be integrated with the newest camera pose | `MonocularDepthSource` → `TsdfScanPipeline.submitFusedDepth` | Carry acquisition geometry with the fused image; integrate with that source geometry |
| Failed AI calibration could pass low-confidence samples into a frame that no longer had a confidence plane | `AiDepthFusion.fuse` | A failed fill leaves low-confidence pixels empty |
| The initial affine depth fit could be bent by a contaminated minority, and all-sample RMS could widen its own inlier gate | `MetricDepthCalibrator` | Deterministic RANSAC seed, MAD scale, redescending Tukey IRLS, independent final inlier gate |
| Multithreaded integration omitted worker `voxelWeight` in the public statistics | `TsdfVolume.integrate` | Sum every worker's accepted weight; test the absolute accounting identity |
| RTAB-Map has a bridge but no live RGB-D keyframe feed | `rtabmap_bridge.*`, keyframe pipeline | Feature-flagged integration only after an ID/timestamp/map-correction contract and device benchmark |
| The shipped closure path proposes by proximity and needs operator confirmation; a wrong edge has no robust verification/rollback stage | `PoseGraphCloser`, `pose_graph.*` | Appearance + geometric verification, consistency checks, robust loss and transactional rollback before automation |
| Full smoothed depth was paired with confidence belonging to Raw Depth | `DepthCapture.capture` | **Source contract fixed:** raw-first acquisition pairs only Raw Depth with Raw Confidence; Full Depth carries none. Explicit `RAW_ONLY`/`FULL_ONLY` modes and source/timestamp metadata enable the still-open per-device A/B experiment |
| Corrected graph poses do not correct stored measurements or the TSDF | `PoseGraphCloser`, route/TSDF stores | Keep raw observations immutable; if useful, add a separately labelled corrected layer, never silent retroactive edits |
| Desktop timing and synthetic accuracy are not phone evidence | `docs/PERFORMANCE.md` | On-device replay/benchmark reports with device, build, thermal state and raw metrics |

## 3. Open-source candidate matrix

“Cost” below means expected integration and runtime cost on Android, not an upstream benchmark.
Licences were checked against the listed upstream files; optional dependencies still need their own
review before any actual import.

### 3.1 Tracking, place recognition and loop closure

| Project / algorithm | Upstream licence at reviewed revision | Mobile cost and integration boundary | Expected value, risk and decision |
| --- | --- | --- | --- |
| [RTAB-Map](https://github.com/introlab/rtabmap) `8ebc407d0e67`: incremental bag-of-words place recognition, geometric verification, graph optimisation and working-memory management | BSD-3-Clause core; avoid OpenCV nonfree/SURF and inspect optimizer/features | **High**. Feed sparse RGB-D keyframes and ARCore odometry into the existing native bridge. No second VIO. Bound working memory and processing cadence | **Priority 1** for verified closures/relocalisation because the integration seam already exists. Risks: OpenCV/NDK size, ID mismatch, frame alignment, memory, confusing `map→odom` correction with raw measured points. Ship behind a flag and retain manual fallback |
| [Kimera-RPGO](https://github.com/MIT-SPARK/Kimera-RPGO) `d28b4df0570d`: odometry consistency, Pairwise Consistent Measurement Set Maximisation (PCM), robust pose-graph optimisation | BSD-2-Clause; GTSAM and third-party dependencies separately licensed | **Medium–high**. First port the *checks*, not the stack: test a proposed closure against odometry and previously accepted loops; later consider GTSAM/GNC | **Priority 1 algorithm reference**. Perceptual aliasing in identical corridors is exactly the failure a proximity proposer cannot detect. A robust kernel alone is not verification |
| [Ceres Solver](https://github.com/ceres-solver/ceres-solver) `bbb2d0082d4f`: manifold nonlinear least squares, Huber/Cauchy/Tukey losses, covariance | BSD-3-Clause | **Medium–high**. Android is supported upstream, but importing it would replace a small working block-sparse solver and add native dependencies | **Reference now, candidate later** if profiling shows solver limits or landmark BA is needed. Adopt robust-loss and covariance ideas; do not import merely for prestige |
| [GTSAM](https://github.com/borglab/gtsam) `ba592c78fa8e`: factor graphs, iSAM2, IMU preintegration, robust noise models | Simplified BSD core with documented third-party licences | **High**. Useful for incremental smoothing, fixed-lag estimation and covariance, but a substantial runtime/build commitment | **Defer**. ARCore already supplies VIO; the room-scale native graph does not yet justify iSAM2. Re-evaluate with >500-keyframe traces and covariance requirements |
| [LightGlue](https://github.com/cvg/LightGlue) `5a650c444718` + [DISK](https://github.com/cvlab-epfl/disk) `8dc6d4d65b47` or [ALIKED](https://github.com/Shiaoming/ALIKED) `683d7c651973`: learned local features and adaptive matching | LightGlue/DISK Apache-2.0; ALIKED BSD-3-Clause | **High**. Needs model conversion, bounded keypoints, int8/FP16 accuracy checks and depth-backed PnP/RANSAC on a worker thread | **Priority 2 experiment** for low-texture/repetitive sites. Never substitute the original non-commercial SuperPoint weights. Appearance similarity still requires geometric verification |
| [Kimera-VIO](https://github.com/MIT-SPARK/Kimera-VIO) `ce8c59b7b273`: stereo/mono+IMU sliding-window VIO and mesh side channel | BSD-2-Clause | **Very high**. Camera/IMU calibration, time synchronisation, GTSAM and a parallel tracking stack | **Do not integrate into live tracking now**. Useful as an offline comparison and for plane-aware/structureless-factor ideas |
| [Basalt](https://github.com/VladyslavUsenko/basalt) `0f3b2b52c807`: KLT optical flow, marginalisation, visual-inertial bundle adjustment and calibration | BSD-3-Clause | **Very high** for the same reasons; expects a calibrated sensor stream, not ARCore poses | **Offline reference only**. Replacing ARCore would risk device coverage and duplicate IMU/camera work without a measured need |
| [ORB-SLAM3](https://github.com/UZ-SLAMLab/ORB_SLAM3) `4452a3c4ab75`: ORB atlas, multi-map relocalisation, visual-inertial BA | GPL-3.0; separate commercial licence offered upstream | Not a drop-in proprietary-app dependency | **Reject for shipping under current terms**; paper/behaviour reference only |
| [OpenVINS](https://github.com/rpng/open_vins) `69488123ed93`: MSCKF, online calibration, plane-aware VIO and evaluation suites | GPL-3.0 | Same conflict; technically interesting but not licence-compatible with this proprietary app | **Reject for shipping under current terms**. Its evaluation discipline and plane factors remain useful reading |

### 3.2 Depth, fusion and reconstruction

| Project / algorithm | Upstream licence at reviewed revision | Mobile cost and integration boundary | Expected value, risk and decision |
| --- | --- | --- | --- |
| [Depth Anything V2](https://github.com/DepthAnything/Depth-Anything-V2) `a561b849ebae`: relative and metric monocular depth | **Small** Apache-2.0; Base/Large/Giant CC-BY-NC-4.0 | Already integrated as Small through LiteRT. Keep checksum/variant enforcement | **Retain, harden**. Network output fills holes only; it never overrides measured ARCore samples. Main risks are temporal flicker, scale/shift drift, moving objects and plausible-looking hallucinations |
| [Video Depth Anything](https://github.com/DepthAnything/Video-Depth-Anything) `4f5ae23172ba`: temporal attention and experimental streaming | Small Apache-2.0; Base/Large CC-BY-NC-4.0 | **High**. Small has 28.4M parameters; published desktop-GPU latency is not phone latency. Streaming caches need bounded memory and a LiteRT-compatible export | **Priority 2 benchmark**, initially for recorded/offline scans. Acceptance requires better temporal depth error without worse latency, occlusion ghosts or calibration failures |
| [voxblox](https://github.com/ethz-asl/voxblox) `c8066b04075d`: block-hashed TSDF, grouped raycasting, incremental ESDF | BSD-3-Clause | **Medium** conceptually, **high** as a replacement. CPU-oriented robotics implementation with its own map/mesh contracts | **Algorithm reference**. Evaluate ray bundling, allocation/coalescing and observation weights in the existing volume rather than adding a second TSDF |
| [supereight2](https://github.com/ethz-mrl/supereight2) `eb9260f43e4f`: multiresolution octree occupancy/TSDF and explicit free space | BSD-3-Clause (REUSE licence files) | **High**. Templates, octree map and pipeline replacement; useful multiresolution ideas | **Defer**. Candidate if measured room size/RSS exceeds block-hash capacity; benchmark thin-wall and pipe errors before resolution adaptation |
| [Open3D](https://github.com/isl-org/Open3D) `b6c5e196384a`: scalable TSDF, RGB-D integration, registration, RANSAC planes, point-cloud filters | MIT core | **High** on mobile and unnecessary in the APK; excellent desktop oracle | **Use offline now**. Compare native meshes against Open3D on identical TUM/synthetic frames and use its plane/registration tools as an independent reference |
| [PCL](https://github.com/PointCloudLibrary/pcl): RANSAC plane models, normal estimation, smoothness-constrained region growing | BSD | **High** as a full mobile dependency | **Reference for room geometry**. Improve multi-plane support and connectivity in existing native code; do not square a non-Manhattan room just because a rectangle looks nicer |
| Directional TSDF / point-to-plane integration, incidence-angle and bilateral-normal weighting ([paper](https://www.ais.uni-bonn.de/papers/IROS_2019_Splietker.pdf)) | Paper is readable; implementation licence must be checked before copying any code | **Medium**. Local depth normals and angle weights fit the current integrator; six directional volumes would multiply memory | **Priority 2 algorithm experiment** using only range/confidence/incidence weighting first. Test grazing walls, thin pipes, corners and opposing surfaces |
| [InfiniTAM](https://github.com/victorprad/InfiniTAM) `4156547e7f11`: voxel hashing, fern relocalisation, submaps, CPU/GPU fusion | Upstream licence restricts commercial use and redistribution | Technically relevant Android/iOS history, legally unsuitable as a dependency | **Reject under current terms**. Read published submap concepts only; do not copy its code |

### 3.3 Optimisation, room geometry and evaluation

| Candidate | Role in ConstructAR | Validation and decision |
| --- | --- | --- |
| Robust losses / Graduated Non-Convexity (GNC) | Limit a bad loop's influence, then move from a convex start towards near-binary inlier decisions | **GNC/TLS implemented for loop edges only.** An independent odometry-deformation gate and rollback are still required: the adversarial five-metre test otherwise converges to a folded local minimum. Broader 0–50 % false-loop benchmarks and full batch PCM remain open |
| PCM / pairwise cycle consistency | Reject loops that disagree with odometry or accepted closures | **Newest-loop PCM implemented.** A candidate is checked against comparable accepted loops through measured-odometry cycles; only the inconsistent newcomer is removed. A single loop still needs GNC/deformation checks, and automatic acceptance still needs appearance/depth-backed PnP. Maximum-clique/batch set selection is not implemented |
| Deformation graphs / submaps, as used by Kimera-PGMO ([paper](https://ar5iv.labs.arxiv.org/html/2101.06894)) | Apply accepted global corrections to a derived mesh without “de-integrating” history | **Long term.** Store raw and corrected geometry separately with source graph revision. Benchmark extra solve/memory and preserve raw measured runs |
| Multi-model RANSAC + region growing | Separate intersecting walls, floors, ceilings and clutter before Manhattan regularisation | **Targeted improvement.** Report plane residual/support and leave unsupported/open/non-Manhattan geometry unchanged |
| [evo](https://github.com/MichaelGrupp/evo) `146b6291a7b7` (GPL-3.0) | External ATE/RPE tooling; never linked into the APK | **Development-only.** Record TUM-format trajectories, timestamp association and SE(3) alignment. No Sim(3) scale correction for an already metric ARCore trace |
| [TUM RGB-D](https://cvg.cit.tum.de/data/datasets/rgbd-dataset) (data CC BY 4.0, tools BSD-2-Clause) | Public RGB-D sequences with motion-capture trajectories | **Offline regression**, with attribution. It tests the native reconstruction/loop algorithms, not ARCore tracking on a phone |
| ICL-NUIM / [SLAMBench](https://github.com/pamela-project/slambench) | Synthetic RGB-D with trajectory and scene ground truth; useful accuracy/runtime/energy methodology | **Offline candidate after confirming each downloaded asset's licence**. Synthetic results must not be presented as device accuracy |

## 4. What is implemented in this first branch stage

These are correctness fixes, not benchmark claims:

- **Frame-conserving AI integration:** `FusedDepthFrame` carries depth, dimensions, source timestamp,
  source intrinsics and source camera-to-world. `DepthIntegrationSelector` accepts only a fresh,
  dimension-matching result. The old “old pixels + newest pose” path is removed.
- **Source-correct ARCore acquisition:** `PREFER_RAW` attempts Raw Depth before Full Depth, while
  `RAW_ONLY` and `FULL_ONLY` prevent cross-source fallback during A/B capture. Raw Confidence is
  acquired only beside Raw Depth. Snapshots, fused frames, integration selection and `ScanState`
  retain the actual source plus camera/depth timestamps; explicit modes never relabel the result.
- **Confidence-safe fallback:** when calibration is unusable, confident measured ARCore pixels pass
  through and low-confidence pixels stay zero. The network is not used.
- **Robust metric calibration:** deterministic two-point RANSAC minimises truncated affine
  inverse-depth cost; MAD supplies a residual scale; Tukey-biweight IRLS gives gross outliers zero
  weight. Final usability still requires positive bounded scale, inlier ratio and inlier RMS gates.
- **Complete native evidence accounting:** multithreaded reductions include `voxelWeight`; the test
  compares absolute counted/stored difference and rejects a zero-on-one-side pass.
- **Confidence-weighted integration:** fused depth now carries a parallel confidence plane.
  Measured pixels keep ARCore's value, calibrated fills use the acceptance floor and pooled holes
  are cleared to zero. Native fusion retains the hard floor, then multiplies accepted range weight
  by `confidence / 255`; absent confidence preserves the previous unit multiplier and malformed
  confidence geometry fails closed.
- **Local-incidence integration:** accepted observations now also multiply by
  `max(|dot(localNormal, pixelRay)|, 0.20)`. Four immediate, range/confidence-qualified depth
  neighbours provide a bounded tangent basis with padded-stride support. Opposite pairs average
  noise, one-sided pairs handle borders, depth jumps cannot create false tangents, and unknown
  normals stay neutral. `weightByIncidence=false` is the native A/B control. An analytic padded
  3×3 plane at 4 m and 60° verifies deposited evidence of approximately `1/16 × 1/2`, including
  fixed-point quantization; isolated, discontinuous and low-confidence-neighbour cases remain
  neutral. This is one directional weight, not six directional TSDF volumes or a device accuracy claim.
- **Robust, transactional loop attempts:** super-linear GNC/TLS affects loop hypotheses only. An
  independent odometry-deformation gate catches the folded local minimum that GNC alone accepted
  in a five-metre adversarial fixture. The fallback disables loops and recovers measured odometry;
  non-converged/non-finite/cost-worsening runs restore every input pose and become `Failed` in the
  operator conversation. `optimizeLastLoop` additionally removes a failed or zero-weight new edge.
  Newest-loop PCM now checks measured-odometry cycles against accepted older loops and removes only
  an inconsistent newcomer, preserving valid companions. It is not maximum-clique batch PCM or
  automatic appearance verification.

Local evidence at the time of writing:

- Current `tools/verify-native.sh`: **381 checks, 0 failures**, in local O1 and O2 builds; the
  separate TSAN run also passed all 381 checks with no race reported.
- Native CI run **42425772140** passed g++ and clang++, O1 and O2, with **369 checks per cell**,
  plus TSAN, RTAB-Map stub compilation and the layout probe.
- Android run **42425769021** passed all **1293 JVM tests**, Kotlin/Java compilation, NDK, host
  C++, debug/release, R8, licence checks and 16 KB alignment.
- New deterministic cases cover explicit Raw/Full acquisition order, source/depth-timestamp
  propagation, source-pose selection, stale/future/mismatched fusion, fallback
  confidence, 25 % gross calibration contamination, exact repeatability, confidence- and
  incidence-scaled stored evidence, padded-stride local normals, discontinuity/isolation fallback,
  malformed confidence metadata, newest-loop PCM translation/rotation rejection,
  preservation of consistent companion loops, a five-metre false loop and exact rollback.

These tests prove software contracts and synthetic robustness. They do **not** establish phone
tracking accuracy, relocalisation rate, thermal stability or a universal depth-error percentage.

## 5. Prioritised delivery plan

### Phase 1 — correctness and measurable baseline (this branch)

1. Land the four fixes above with full Android/JVM/native CI.
2. Add a local diagnostic/replay record containing frame/depth timestamps, acquisition source,
   intrinsics, original pose, tracking state, confidence histogram, calibration inliers/RMS,
   integration/extraction p50/p95, dropped frames, graph revision and peak RSS. Images stay opt-in;
   no network permission is introduced.
3. Export original and optimised trajectories in TUM format with explicit frame conventions.
4. Build a small deterministic benchmark runner: moving synthetic camera, flat/grazing walls,
   openings, thin pipes, crossed views, contaminated depth and false loop closures.

**Exit gate:** green full CI; no frame/pose mix in replay; baseline JSON published with exact build
and fixture; every subsequent candidate has a control result.

### Phase 2 — better measurements before more automation

1. **Raw versus full depth A/B** on at least one ToF and one non-ToF ARCore device. The explicit
   `RAW_ONLY`/`FULL_ONLY` acquisition modes and source/timestamp metadata now make that comparison
   possible without mismatched confidence or silent fallback. Google's Raw Depth documentation
   reports sparser but more accurate samples and a matching confidence image; the app must still
   measure whether that holds for its wall/pipe workloads and must not silently switch all devices.
2. **Soft confidence and local incidence delivered:** accepted observations now use
   `confidence / 255` and a bounded local-normal cosine alongside the existing 1/Z² range term and
   hard floor. Next measure the combined effect against `weightByIncidence=false` on grazing walls,
   thin pipes, corners and opposing surfaces rather than assuming the extra factor helps.
3. Reject fills at temporal disocclusions and inconsistent calibration regions. A network hole fill
   receives lower weight than measured depth and never becomes a high-confidence hit tier.
4. Benchmark Video Depth Anything Small against frame-wise V2 on recorded sequences. Keep it only
   if temporal error improves within the phone's latency/RSS/thermal budget.

**Metrics:** plane MAE/p95, 2/5 cm accuracy/completeness/F-score, pipe false-positive rate,
calibration failure rate, temporal depth variance on static surfaces, hit-tier distribution,
integration p50/p95, RSS and thermal throttle onset.

### Phase 3 — verified, reversible loop closure and relocalisation

1. Wire RTAB-Map RGB-D keyframes to ARCore odometry behind a build/runtime flag. Verify exact
   keyframe IDs, optical-frame rotations, strides, timestamps and `map→odom` semantics.
2. Require appearance support **and** depth-backed geometric verification. Proximity remains only a
   proposal. Ambiguous results keep the existing operator conversation.
3. **Delivered guards:** GNC/TLS, an odometry-deformation fallback, graph transactions and
   newest-loop pairwise cycle consistency against accepted companions.
   **Still required before automation:** appearance/depth-backed verification, full batch/maximum-
   clique consistency where needed, and a benchmark matrix with broader multi-false-loop rates.
4. Persist only bounded descriptors/keyframe metadata needed for relocalisation. Compare ORB/SIFT
   with DISK/ALIKED+LightGlue after model and weight licence checks.

**Metrics:** precision/recall on true and adversarial loops, ATE/RPE before/after, rollback success,
relocalisation success/time after tracking loss, peak working memory and per-keyframe latency.
A false accepted loop is more costly than a missed loop; the acceptance threshold must reflect that.

### Phase 4 — corrected geometry without rewritten evidence

1. Store a graph revision and a separate corrected pose/mesh layer. Raw measured nodes, taps,
   confidences and original poses remain immutable and exportable.
2. Evaluate submaps or deformation graphs against a full TSDF rebuild on recorded scans.
3. Improve multi-plane room fitting before adding stronger Manhattan constraints. Preserve openings,
   curved walls and non-orthogonal rooms with explicit residuals.

**Exit gate:** the user can distinguish raw from corrected geometry; every correction is reversible;
exports identify the layer and graph revision; no material quantity changes silently.

## 6. Device acceptance protocol

A release candidate must report at least the following, separately per device and thermal state:

| Dimension | Required measurement |
| --- | --- |
| Pose stability | Static-anchor drift over 60 s; moving RPE translation/rotation per metre; ATE on a surveyed loop |
| Loop closure | True/false acceptance matrix, final loop error, convergence/rollback, correction magnitude |
| Relocalisation | Success rate and time after forced tracking loss at known AprilTag/surveyed positions |
| Depth | Wall/pipe absolute error and p95, raw/full/AI ablation, invalid/low-confidence rates |
| Geometry | Mesh accuracy/completeness at declared tolerances, duplicate/ghost surface rate, Manhattan residual |
| Runtime | Camera FPS, integrated FPS, dropped fraction, integration/extraction/inference p50/p95 |
| Resources | Peak RSS, native block count, model/APK size, battery and time to thermal throttle |
| Recovery | Queue saturation, model absent/corrupt, depth unavailable, tracking loss, block budget and teardown |

At least five runs are needed for stochastic/device measurements; report median and spread. Keep
raw JSON and failed runs. Do not convert estimates into “±X cm” product claims. A number applies
only to its device, scene, route, lighting, model checksum, configuration and alignment protocol.

## 7. Explicit non-goals for now

- No Gaussian-splatting/NeRF renderer as a substitute for trustworthy metric geometry.
- No second visual-inertial tracker competing with ARCore without a demonstrated tracking defect.
- No GPL/non-commercial code or model weights in the proprietary APK under their current terms.
- No cloud upload, remote depth service or new telemetry dependency.
- No automatic loop acceptance based on proximity alone.
- No “beautification” of walls or routes that hides uncertainty or rewrites measured evidence.
