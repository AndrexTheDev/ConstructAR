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
| 1 | ARCore VIO + Raw Depth (+ AI depth fused into holes, when the model asset ships) | ARCore and Raw Depth path **built**; the AI fusion is coded and unit-tested but **not wired** — `MonocularDepthEstimator` is never constructed in `:app`/`:ar-engine` and `app/src/main/assets/models/*.tflite` is not in the repository |
| 2 | Passive marker detection (AprilTag 3 / ArUco) in a native worker | **Not built.** A background detector needs a decoder (its own vendored source and license entry) and cannot be built or verified in the development sandbox; see "Not built" below |
| 3 | Active reference calibration: bank card or folding rule | **Arithmetic, sweep, storage and application are built and tested** (`core/.../calib/ReferenceCalibration.kt`, `CalibrationSweep.kt`, `ProjectRecord.calibrationScale`), strings in all six locales, a correction notice on the room screen. What follows: the AR button that drives the sweep and the calibration line in the protocol header |

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

## Not built, and why

* **Dual-camera stereo depth (Camera2).** ARCore owns the camera while a session runs; a second,
  concurrent Camera2 stream on the wide/tele pair is not part of the `android.hardware.camera.ar`
  contract this app requires in its manifest. Writing it would be code that claims a capability
  the target devices do not have — the opposite of what this repository does everywhere else. The
  metric source stays ARCore Raw Depth, with the AI fusion filling holes only.
* **AprilTag 3 / ArUco.** A background detector means vendoring a decoder (denoising, quad
  extraction, tag36h11 decode) plus its BSD license entry in `NOTICE` and `docs/LICENSING.md`.
  The next step here is the *contract*: a detector interface, the marker-to-scale update and its
  outlier filter, host-tested with synthetic tags; the pixel decoder lands only when it can be
  built for the NDK and verified on a device.
* **Applying a factor to stored geometry.** Today a route's metres are what ARCore measured. Once
  the dialog exists, the factor is stored on the project and applied at the reporting layer (bill
  of materials, protocol tables, CSV) — never baked into the node positions, because a factor that
  is baked in cannot be corrected by a better one tomorrow.

## Guard

`scripts/verify_sources.py` rule 78 keeps the three numbers in one place: the two card edges must
be declared in `ReferenceCalibration`, no other source file may contain `0.0856` or `0.05398`,
the acceptance window must be stated there, the anchor form `reference + (point - reference) *
factor` must be present, and the five `calib_*` keys must exist in all six locales. All four are
mutation-tested.
