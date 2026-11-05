<!--
  Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
  All rights reserved. Proprietary and confidential - see LICENSE.
  SPDX-License-Identifier: LicenseRef-Proprietary
-->

# Calibration — three tiers, and what of them exists

ARCore's own metre is metric: its VIO scale is fixed by the IMU, so a run measured on a tracked
phone is right to the percent, not to the centimetre. The three tiers below are the ways to find
out *whether that is true on this site, on this phone, today* — from fully automatic to a
deliberate check the operator performs when a document has to be defensible.

This file says what is built, what is not, and why. It is written that way on purpose: the
project's rule elsewhere (`docs/TESTING.md`) is that a claim nobody can reproduce is a claim that
drifts.

| Tier | Source of the scale | State |
|---|---|---|
| 1 | ARCore VIO + Raw Depth (+ AI depth fused into holes, when the model asset ships) | ARCore and Raw Depth path **built**; the AI fusion **is wired** (`MonocularDepthSource` runs on its own worker from `ArMeasureController`, and the model path comes from `BuildConfig.DEPTH_MODEL_ASSET`), but `app/src/main/assets/models/*.tflite` is not in the repository — so on every build shipped from this tree the model is absent, the app says so (`DepthAvailability.PLANES_ONLY` or `ARCORE_ONLY`), and it measures on ARCore's depth or on the planes it has recognised |
| 2 | Passive marker detection (AprilTag 3 / tag36h11) in a native worker | **Decoder vendored, frame path built, screen missing.** AprilTag 3.1.0's C core (BSD-2-Clause, unmodified, one family) sits in `native-slam/src/main/cpp/third_party/apriltag/` with its notice in `NOTICE`, `licenses/BSD-2-Clause.txt` and the Help screen; `calibration/apriltag_detector.cpp` is the one file that includes it, `nativeDetectAprilTagScale` exposes it over JNI, and the host suite proves it against rendered tags at 2 m and 4 m through four rotations. The marker-to-scale arithmetic and its outlier filter are in `calibration/tag_scale.hpp`. **No app source captures a frame and calls either** - the tier measures nothing until that frame path exists |
| 3 | Active reference calibration: bank card or folding rule | **Complete in the app**: arithmetic, sweep, storage and application (`core/.../calib`, `ProjectRecord.calibrationScale`), a *Calibrate* button and dialog on the AR screen that takes the reticle point twice, six locales, and a correction notice on the room screen. The protocol header and both CSV files carry the factor as their own line, so a document showing corrected metres also shows what corrected them |

## Tier 3 — the arithmetic

```
S = d_real / ||P2 - P1||                     the factor every measured metre is multiplied by
accepted  ⟺  S ∈ [0.85, 1.15]                both ends included
P' = P_ref + S · (P - P_ref)                 a correction about the reference, not the origin
```

**References.** `CalibrationReference` holds the only copies of the physical sizes:

* `CREDIT_CARD_LONG` = 0.0856 m and `CREDIT_CARD_SHORT` = 0.05398 m — ISO/IEC 7810 **ID-1**
  (85.60 mm x 53.98 mm): the bank card, the health-insurance card, the site pass. Best under
  ~1.5 m, where the edge is still large on screen.
* `ZOLLSTOCK_1M` = 1.0 m and `ZOLLSTOCK_2M` = 2.0 m — the folding rule a German site carries
  (MID class II). The reference for room-sized distances, 1 m – 4 m.

**The window is a refusal, not a clamp.** Outside `[0.85, 1.15]` the app stores nothing and says
what it rejected. A factor of 1.43 is not "a bit off", it is a mis-tap or a tracker that has lost
the room; clamping it to 1.15 would print a document labelled *calibrated* over a measurement
nobody checked. The window is deliberately the one the specification asked for, even though
ARCore's own error is far smaller — tightening it to ±3 % would be a product decision, not an
implementation detail, and it is not taken here.

**The anchor matters.** Scaling about the world origin moves a node 20 m from the reference by
`20 m x (S - 1)`: at S = 1.05 that is a metre of drift in a room, on geometry nobody re-measured.
`P' = P_ref + S · (P - P_ref)` leaves the measured object where it stands and moves only what was
measured relative to it. `apply` returns the point unchanged to the bit when `S == 1.0`, so a
project that never calibrated cannot pick up rounding noise on its way out.

**Where the factor is applied — and where it is not.** `ProjectRecord.calibrationScale` (default
`1.0`) is the stored factor. It is multiplied into exactly two places, both of them *reporting*:
`ProjectRecord.totalLengthMeters` / `calibratedMeters(route)` (the room screen, the protocol tables,
the CSV run details) and the metre totals in `MaterialListBuilder.build`. It is deliberately **not**
applied to:

* `route.nodes` — the geometry is what the anchors, the overlay and the 3D export are built from,
  and a factor multiplied in could not be corrected by a better one tomorrow;
* the fitting counts — a clip is "one per spacing", and length and spacing were measured in the
  same, equally short, metre, so the ratio and therefore the count is unchanged. A certified
  calibration changes what the customer is charged for in metres, not how many clips are needed;
* the AR HUD while measuring — the operator measures what the phone sees; the paperwork is where
  the correction belongs, and the room screen says so in words when a factor is in force.

**What the paperwork says.** When a factor is in force the protocol prints one line under its
header — *Calibrated: factor 1.053* — and the full CSV and the wholesaler's order list print a
`Calibration;…` preamble row, above the metres it qualifies. It is a row and not a `#` comment
because these files are opened in Excel; it is a separate line and not part of the meta line
because that line is ellipsised, and the reason for a corrected number must not be the first thing
cut. `:export` never composes the sentence: the app owns the string table and passes a localised
label, which is also why the label is resolved in composition and not inside the export coroutine.

**Where the number goes.** `formatScale` prints three decimals in the root locale: it is a ratio,
not a measurement, and a German phone must not write `1,053` inside a sentence that already calls
it a factor. The localised *label* for a protocol header ("Metric: Folding rule, factor 1.053")
will be assembled in `:app` and passed to `:export` — the export module has no string table by
design — once the dialog that produces it exists.

## The sweep — why it is a state and not two nullables

`CalibrationSweep` is the sequence around the division, written once as a value: `Idle` →
`FirstPoint` → `SecondPoint` → `Finished`. Three of those steps exist because of how a person
behaves on site:

* **A third tap must not recompute.** While the dialog says *factor 1.053 — apply?*, a tap on the
  scene is a dismissal, not a new measurement; `Finished.tap` returns itself, so the number under
  the operator's thumb cannot change while he decides.
* **A mis-tap is not undone, it is restarted.** `start` from any state goes back to `FirstPoint`
  with the new reference, so "measure it again" never means "measure from the wrong end".
* **Two taps on the same end are `Unmeasurable`, not `Infinity`.** The sweep answers with the same
  three-valued outcome the arithmetic does, so the screen has one thing to render.

## The dialog

*Calibrate* sits under the AR viewfinder. It offers the four references, takes the point under the
reticle twice (`Take point`, disabled while the reticle has no hit), and shows the factor the
sweep produced. Only an `Accepted` outcome enables `Apply`; a `Rejected` one shows the factor and
the window it missed, in the danger colour, and cannot be applied — the dialog and the view model
both refuse it, and the *refusal* is decided in `:core`, not in the screen.

The sweep itself is not saved state: a rotation ends the measurement, which costs two taps and
never a stored number. A calibration that has been applied is different — it is written to the
project file, which is why it survives the phone being put in a pocket.

## Not built, and why

* **Dual-camera stereo depth (Camera2).** ARCore owns the camera while a session runs; a second,
  concurrent Camera2 stream on the wide/tele pair is not part of the `android.hardware.camera.ar`
  contract this app requires in its manifest. Writing it would be code that claims a capability
  the target devices do not have — the opposite of what this repository does everywhere else. The
  metric source stays ARCore Raw Depth, with the AI fusion filling holes only.
* **A dual-camera baseline as a second metric source.** Covered above: ARCore owns the camera
  while a session runs.

## Tier 2 — the marker decoder

Tier 2 has two halves, and since finding 231 both of them exist: the decoder that finds the tag in
the pixels, and the arithmetic that turns sightings into a factor.

**The decoder is vendored.** AprilTag 3.1.0's C core, BSD-2-Clause, unmodified, in
`native-slam/src/main/cpp/third_party/apriltag/` — with its licence, its provenance and the list of
what was taken and left in `VENDORED.md` beside it, an entry in `NOTICE`, a copy in
`licenses/BSD-2-Clause.txt`, and an entry on the Help screen's third-party list. One family is
compiled (tag36h11, the one a printed wall marker uses). Exactly one translation unit includes it,
`calibration/apriltag_detector.cpp`, so a decoder this repository did not write cannot leak its
types into a layer it did — `scripts/verify_sources.py` holds that door shut.

`native-slam/.../calibration/tag_scale.hpp` holds the rest:

* **`TagDetector`**, the interface callers use, with `AprilTagDetector` behind it when a camera and
  a printed tag size are supplied, and `NoTagDetector` when they are not: `available()` is false and
  `unavailableReason()` is a sentence a diagnostic screen can print, naming *what is missing* — the
  camera, not the decoder. **It does not prove the answer.** The host suite renders tag36h11 id 7
  from the pattern a printer would put on paper, at two known distances and four rotations, and
  asserts the distance the decoder reports; that is arithmetic checked against geometry, which is
  as much as a desktop can prove. A camera, a lens and a real marker are a device test this
  repository cannot run, and nothing here claims otherwise.
* **`available()` is still the distinction that matters.** A frame with no tag in it and a call
  without intrinsics both produce no detections, and only the second is a broken question - the
  detector that answers "cannot" carries the reason (`no camera intrinsics were supplied…`), so a
  diagnostic screen prints what is missing instead of blaming the room. That is the shape
  `RtabMapBridge::isAvailable` established in finding 98, and it survives the decoder arriving:
  the absence moved, it did not disappear.
* **`MarkerBaseline`** (`:core`), the case a single printed marker actually is. One tag stuck to a
  wall has no layout, so the ratios above do not apply; what it has is geometry: seen from two
  places, the triangle camera-camera-tag gives a baseline twice over - once between the two camera
  positions in the app's metres, once between the two camera-to-tag vectors in the marker's metres,
  each rotated into world axes first. Their ratio is the correction, and the rotation is not
  optional: turn the phone between the sightings and an unrotated subtraction compares two
  different frames and reports a phone that was right as one that needs recalibrating. It publishes
  a factor, the agreeing and rejected pair counts, the longest baseline and the spread, and it asks
  `ReferenceCalibration.accepted` rather than carrying a second window.
* **`estimateScaleFromTagSightings`**, the layout case: the ratios between every pair
  of tags that the printed layout also knows (`nominal / measured`), a median to find the majority,
  a relative-deviation filter to drop the pairs that disagree with it, and a baseline-weighted mean
  of what survives. Long baselines dominate because a ratio's error falls with its baseline, and a
  tag seen at 0.6 decision margin or read through a corrected bit is not used at all.
* **What it will not do:** decide. It publishes a factor, the pairs that agreed, the pairs it
  rejected and the longest baseline behind it; the window and the decision to store anything stay
  in `:core` with the bank-card reference, and the native directory does not contain the window's
  two numbers even in a comment.

The decoder is **vendored and wired to one screen**, and what is still missing is stated rather
than implied: no device has held a real tag against it, and the *layout* path
(`estimateScaleFromTagSightings`, which needs a printed sheet of tags) is still reached from
nowhere. What exists is the frame path and the sequence on top of it:
`MarkerFrameReader` (`:ar-engine`)
packs an ARCore plane with `:core`'s `LuminancePlane` - the Y plane is *strided*, and a buffer that
ignored the padding would shear every row and decode nothing - and calls the decoder with the
session's intrinsics and the printed size; the composition is unit-tested with a decoder that
records what it was handed. Above it, `MarkerProbe` (`:ar-engine`) turns a frame into one sighting
per tag it holds - a printed sheet is several markers, and a probe that kept only the nearest one
made the sweep's marker count a field that could never exceed 1 (finding 236) - and
`MarkerSweep` (`:core`) holds the sequence: every sighting carries the ARCore session it came from
and a sighting from another one is counted, never paired (a new session is a new world origin -
finding 217 one layer up); older sightings of the same marker fall off after eight, so a tag in the
room the operator left stops voting; and two sightings from the same spot leave the sweep
`Watching` instead of publishing the 1.0 that would certify an unchecked phone. The two trust knobs
(baseline floor, relative deviation) are read out of `TagScaleOptions` over
`nativeTagScaleDefaults()` — this file does not restate them and neither does `:app`. The AR screen
samples one frame in fifteen while the marker dialog is open, drops the sweep when ARCore starts a
new session, and offers `Apply` only for a factor `ReferenceCalibration.accepted` would take. What
the dialog prints is `:core`'s `MarkerStatusLine`: four cases (off, watching, measured, outside the
window) with the evidence travelling alongside the factor - pairs that agreed, pairs the deviation
filter rejected, and the baseline they span - because a bare "factor 1.03" cannot tell the operator
whether it rests on one 20 cm pair or six pairs over three metres (finding 237). What is
still unproven: a real printed tag on a real device, which is the one thing no JVM test can supply.
`estimateScaleFromTagSightings` — the printed *layout* sheet — is still reached from nowhere, and no
stored number depends on a marker today.
* **Applying a factor to stored geometry.** Today a route's metres are what ARCore measured. Both
  dialogs (two-tap and marker) hand their factor to the same `onCalibration` callback, which stores
  it on the project; the reporting layer applies it (bill of materials, protocol tables, CSV) and
  never the node positions, because a factor that is baked in cannot be corrected by a better one
  tomorrow.

## Guard

`scripts/verify_sources.py` rule 78 keeps the three numbers in one place: the two card edges must
be declared in `ReferenceCalibration`, no other source file may contain `0.0856` or `0.05398`,
the acceptance window must be stated there, the anchor form `reference + (point - reference) *
factor` must be present, and the five `calib_*` keys must exist in all six locales. All four are
mutation-tested.
