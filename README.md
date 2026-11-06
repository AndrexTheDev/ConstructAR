<!--
  Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
  All rights reserved. Proprietary and confidential - see LICENSE.
  SPDX-License-Identifier: LicenseRef-Proprietary
-->

# ConstructAR

**Offline AR measuring and routing for electrical, plumbing and ventilation work.**

| | |
|---|---|
| Developer | **AndrexTheDev** |
| Contact | <hippie.highho@gmail.com> |
| Repository | <https://github.com/AndrexTheDev/ConstructAR> |
| Licence | **All Rights Reserved** ([LICENSE](LICENSE)) — the repository is public for CI/CD and review only |
| Languages | English · Deutsch · Español · Français · Italiano · Polski |
| Landing page | <https://andrexthedev.github.io/ConstructAR/> (generated, six languages) |

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
| **D** | Polyline length `L = Σ‖Pᵢ − Pᵢ₊₁‖`, every node bound to an ARCore `Anchor`, live HUD label from the `hud_length` resource (`%1$s m`, the digits from `LengthFormatter`) | `ar-engine/.../route`, `core/.../geo/PolylineMetrics.kt`, `app/src/main/res/values*/strings.xml` | Measured on the **surface** points, never the offset centreline |
| **D2** | Crosshair snapping: a tap - or the resting reticle, so the HUD already shows the committed length - within 4 cm of a corner the run already has **is** that corner | `ar-engine/.../route/RouteSession.kt` | Exact geometry and an exact fitting count when a run returns to a corner or closes a ring on its start; the last node is never a target, and a snap that would land inside the 2 cm spacing guard is refused |
| **E** | Local export: PDF protocol, CSV material list, `.glb` (glTF 2.0 binary) and `.ply` | `export/` | Written through the Storage Access Framework; no media storage permission, no share-to-cloud |
| **F** | Reference calibration against a known object: an ISO/IEC 7810 ID-1 bank card (85.60 x 53.98 mm) or a folding rule at the 1 m / 2 m mark, `S = d_real / ||P2 - P1||`, refused outside `[0.85, 1.15]`, applied about the reference point rather than the world origin | `core/.../calib`, `app/.../ui` | Arithmetic and its unit tests are in place (docs/CALIBRATION.md); *Calibrate* on the AR screen takes the reticle point twice against a card or a folding rule, and the resulting factor is stored on the project (`calibrationScale`) and applied once to every printed length and to the bill of materials — never to the geometry; the protocol and the CSV say which factor was in force |

Plus: auto-torch with hysteresis, monocular depth (Depth Anything V2 Small) fused into the
metric depth field — wired from `ArMeasureController` with the model's path taken from
`BuildConfig.DEPTH_MODEL_ASSET`; the weights are **not in the repository**, so a build without them
says which depth it is measuring with (`DepthAvailability`: AI-fused, ARCore depth, or planes
only) instead of quietly falling back — a loop-closure pose graph (built and host-tested — *not yet
driven by the scan pipeline*, see finding 98), thermal-aware degradation, and explicit handling for
every way AR can fail on a site.

**Drywall take-off** (`core/.../wall`, **and it is wired**: the project card's *Walls* button
renders `WallPlan`, which builds the room's ring from the stored outline, calls `wallSurface` and
`estimateDrywall`, and prints the board size, stud spacing and waste the numbers were computed
under — a purchase order without its assumptions is a number to argue with; each wall is drawn as
an elevation, a tap on it chooses where a door or a window goes, and the picture is built from the
same rectangles the take-off subtracts, so a hole in the drawing is a hole in the purchase
order): net wall area with
the openings subtracted as a *union* (two windows that overlap take
board off a wall once, not twice — double-subtracting is how an estimate comes out short), then
boards at the standard 2.50 × 1.25 m, CW studs at 62.5 cm centres (a stud under every board edge),
UW track top and bottom, 29 screws per square metre of installed board, and 10 % waste on
everything that gets cut. An opening that does not fit its wall is clipped *and counted*, with the
reason enumerated, rather than quietly absorbed.

**DIN 18015 zones** (`core/.../wall`, **and it is wired**: the same *Walls* screen classifies every
measured point and prints the sockets, switches and ceiling outlets, and it refuses to classify
anything until the session has tracked a floor — ARCore's origin is wherever the phone started, so
a height taken from it would print a socket at 1.40 m; `FloorEvaluator` scores the candidate planes
on area, distance from the phone's chest height and depth and reports `TRACKED`, `UNCERTAIN` or
`MISSING` with its evidence, `HitResolution` carries that decision out of the hit source where the
planes live, and the project stores one floor **per AR session** — a height is a difference inside
one coordinate system, and a node from another session is refused rather than measured from this
one (finding 217)): a measured height is snapped to the
socket line (30 cm), the switch line (105 cm) or the
ceiling outlet (15 cm below the ceiling, so it follows the room), and refused outside a 10 cm
tolerance with the nearest line still named — a point 25 cm off the socket line is not silently
drawn as a socket at 30 cm.

**Clash detection** (`core/.../wall`, arithmetic and unit tests only — **no screen calls it yet**,
because nothing in the app constructs an `Obstacle` yet: there is no source of walls, beams and
services as boxes, and a clearance card with nothing to check against would be a claim the app
cannot back): answers the other question a run has to pass — the
measured polyline against boxes standing for walls, beams and services, with a proper slab
intersection (a segment through the *middle* of a beam touches none of its twelve edges, so an
edge-only check calls a penetration "0.5 m clear"), exact segment-to-box distances, and the default
or per-obstacle clearance enforced.

**Architectural snapping** (`ar-engine/.../hit`, **and it is wired**: `ReticleSnap` runs inside
`ArHitSource.resolve`, so every accepted plane hit - the reticle's and the tap's - is pulled onto
the room's own geometry before it can become a route node): the edges and corners of a room are
derived from the tracked plane polygons by intersecting each pair and clipping the result to
*both* polygons, because two walls that do not touch still intersect in an infinite line and
snapping to that line would put a measurement on a wall that is not there. A hit within 10 cm of
a derived edge or corner *is* that edge or corner - 3 cm short of a corner is the corner - so a
run ends exactly where the fitter stopped and the exported plan shows the room that was measured
rather than the room ARCore happened to see. Only `HitSource.PLANE_POLYGON` hits move: a depth or
feature-point hit has no polygon of its own, and pulling it onto a corner would claim precision
the sensor never measured; the accepted normal likewise stays the aimed wall's, so a corner does
not silently move the line onto the perpendicular wall. The minimum feature length (20 cm) is
deliberately longer than the tolerance (10 cm) - a corner whose clipped segment is shorter than
the tolerance could never be reached from a hit near its end, and would disappear. This is the
room's geometry; the 4 cm crosshair snap above is a different rule, and still snaps to corners the
*run* already has.

**When the snap moves the reticle, the screen says so.** A pulled point is a *different* point from
the one the operator aimed at - up to ten centimetres away - and until now the only trace of it was
the number under the crosshair changing between two taps, which trains a fitter to tap again and
distrust the one step that makes his measurement exact. The move is now recorded on the hit
(`SnapInfo`: which kind of feature, how many metres), stated on the HUD in the accent colour -
*aligned to the corner · moved 4.2 cm* - and drawn as a ring where the reticle samples, so the
jump has an explanation at the place it happens. Four whole sentences per language instead of one
sentence with the noun as an argument: *an der Ecke* and *an der Bodenkante* are different cases,
and a placeholder would make every translator bend the grammar around a word they cannot decline.
The digits come from the same formatter as every other length in the app, and a distance that is
not a length (a NaN, a negative) prints nothing rather than a sentence with a hole in it
(finding 213).

**Room thumbnails** (`export/.../svg` + `app/.../data/RoomThumbnail.kt`): the home screen's card
wants a picture of *that* room, and a JPEG of the camera frame is the wrong answer twice over — it
carries the customer's kitchen into a file list, and it is 40 kB of pixels to say "4 x 3 m".
`FloorplanSvg` writes a standalone SVG of the outline instead: a few hundred bytes, sharp at any
size, drawn from the same numbers the protocol prints. One scale factor serves both axes (a stretch
is exactly what makes opposite walls stop being parallel), the long side sets that scale and the
drawing is centred in the square, the wall is a *physical* 10 cm so a 2 m bathroom and a 20 m
corridor both read right, and a 1 px floor keeps the walls of a 200 m hall visible. Numbers are
written in the C locale: a decimal comma inside an SVG `points` list is a *separator*, so the picture
would silently become a different polygon, and nothing anywhere would report it. The file holds no
text at all — `:export` receives numbers and identifiers, `:app` owns every word (finding 100's
rule), and an unnamed `role="img"` is announced as a nameless image, so the card, which prints the
room's name next to the drawing, is the accessible element. Only a real room is drawn: fewer than
three corners, a NaN, a room under 10 cm or one over 200 m is refused with an enumerated reason
rather than guessed at.

The picture is **written**, at the moment a run is committed: the scan worker slices the volume
while it is still alive (`TsdfScanPipeline.floorplan`, bounded to 400 ms and never called from a
screen's thread), `RoomThumbnail` picks the room out of the plan — the largest *closed* loop, because
a doorway leaves the ring open and joining it would draw a wall across the door — and the repository
writes `files/projects/<id>/thumbnail.svg` through the same temp-file/`fsync`/rename path a project
file uses. Deleting a room deletes its folder, so no orphan picture outlives the room it shows.

The **card** does not read that file, and must not: Android ships no SVG renderer, and a second
parser for a format this app only writes would be a liability with no upside. The same loop — same
corners, same order, chosen once — is stored in the project file itself, as
`sketch: [{xMeters, zMeters}, …]` on `ProjectRecord` (at most 512 corners, drawn only from three,
its area from the same `PolygonMath` the protocol uses), and the card draws it into a 40 dp
`Canvas` through `RoomThumbnail.pathPoints`: one scale on the long side, centred in the square,
world `z` running down — the numbers `FloorplanSvg` prints, and the tests pin them to the same
pixel. Because the outline lives in the room, a backup carries it and a restored room still has its
picture; the SVG stays what it always was for: the artefact that leaves the phone. A room with no
closed plan keeps the icon, because a placeholder outline would be a shape the operator never
walked.

A room the scan never closed keeps its icon, but its corners are **not thrown away**: the sketch is
stored with `Closure.Open`, and the card offers the one gesture that fixes it — *close the room*.
The operator has walked the gap and says it is a wall; the ring becomes a room with an area, a
perimeter, a picture and an exported SVG, and `Closure.DrawnByHand` records that the closing wall was
asserted rather than measured, so a protocol that prints an accuracy never counts it as measured.
Nothing is decided on the operator's behalf: a doorway is a doorway until he says otherwise.

**The protocol carries the plan, not only its numbers.** The document a customer signs had an area,
a bill and a signature line, and no picture of the room those numbers describe — the one page he
keeps looked the same for a 4 m bathroom and a hall. It now has a plan page of its own, printed
before the signature and built from exactly the ring the card draws: `FloorplanSection` carries the
corners and the words, `FloorplanLayout` fits them into the printable box (one scale for both axes,
the longer extent setting it, world `z` down the page, a 10 cm wall that is clamped to the printable
range of 0.75 to 6 points so a hall's wall is not a hairline and a bathroom's is not a blot), and
`ProtocolPdfWriter` draws a closed path with that stroke, the caption and the note underneath. The
caption is the two numbers the card shows, formatted once by `RoomSizeText`; the note says that the
closing wall was drawn by hand when it was. Both are resources in six languages — `:export` receives
them, never composes a sentence (finding 100's rule). A room without a closed ring gets no plan page
at all, because a plan of a walk that never came back to its first corner is not the room. The
geometry is a class with no `PdfDocument` in it, so the scale, the orientation, the clamped wall and
the refusals are tested on the JVM — the same reason `PageFlow` exists.

The same outline states the room's **size**. Under the card's name sit two badges — the area from
`PolygonMath.area` and the perimeter of the ring, closing edge included (a 4 × 3 m room is 12.0 m²
and 14.00 m, not 11 m: the walk back to the first corner is a wall, and it is what skirting and
trunking are priced on). Both numbers are `LengthFormatter`'s digits, in the reader's decimal mark —
`10.5 m²` in English, `10,5 m²` in German — and both units live in the resources (`card_area`,
`card_perimeter`) so six locales stay in step. The PDF's copy of the same two numbers keeps the
point: a document is a protocol, a screen is prose (finding 222). They appear only when the picture does: a row that
drew a shape but stated no size, or stated a size beside an icon, would be two answers to "was this
room measured".

**Depth fusion policy.** ARCore depth is metric but holey; the network is dense but only
relative. `AiDepthFusion` therefore lets the network **fill holes only** — a confident ARCore
sample is never overwritten — after `MetricDepthCalibrator` fits `1/z = s·d + t` against
ARCore's metric samples with IRLS + Huber weights. If the fit's inlier ratio is below 55 % the
fusion is refused and the app uses ARCore depth alone. A fused frame older than 800 ms is
discarded rather than integrated into geometry the camera has already left.

---

## How it is used

Four screens, in the order of the job, and no screen that exists only to show off a feature:

1. **Introduction** — three steps (scan, draw, hand over) that can be skipped. Shown once.
2. **Home** — the first card is always *the room you were last in*, with one button to continue.
   Below it, every project; at the bottom, one button that starts a new one.
3. **Project hub** — the screen the operator returns to after every run. A step trail
   (Scan → Measure → Export) says what is missing, the running metres and the order quantities
   say what has been got, and one primary button says what to do next. Every run is listed with
   its type, size and length and can be deleted; renaming the room, the fitter's name and the
   *note for the office* live here, one tap deeper. **Material & order** opens the shopping list —
   per conduit type, net metres plus the metres to order with the site's waste factor, and the
   clips, couplings and bends the runs actually need. The person who drives to the wholesaler is
   standing in the room with this phone, not reading the PDF at a desk. **Walls** opens the other
   half of the same job: the room's ring as wall area (openings subtracted, the closing wall
   counted), an elevation per wall where a tap places a door or a window, the drywall purchase with
   the board size, stud spacing and waste it was computed under, and the DIN 18015 zones — sockets
   at 30 cm, switches at 105 cm, ceiling outlets under the ceiling — assigned from the floor the AR
   session actually tracked, or not assigned at all when it tracked none, with the count of points
   from another session printed instead of quietly dropped.
4. **Measure → Export** — the AR viewfinder with its HUD, then the export screen, where the
   operator picks a folder once and gets PDF, CSV (plus an optional one-line-per-article order
   list for the wholesaler) and, with Pro, GLB/PLY. The customer can sign
   on the glass with a finger; the signature is rasterised black-on-white and written onto its
   own final page of the protocol, because a handover document without a signature is a note.

This replaced a two-screen design (list → AR) that was technically sufficient and practically
wrong: a foreman standing in a basement could not tell whether the scan had worked, whether the
runs were saved, or what was left to do.

**Every control is glove-sized.** The theme's `gloveTouchTarget` is 64 dp and the primary buttons
are 72 dp, because the phone is used with work gloves on, one-handed, next to a laser. That was a
comment for eleven rounds while the buttons that are actually pressed on site were 40, 48, 52 and
56 dp — the delete on a diary row, the defect toggle, and the two buttons the AR screen shows while
the other hand is busy. They are all at the target now, and `scripts/verify_sources.py` reads every
`IconButton` in `:app` and refuses one smaller: the design constant and the composable are two
numbers in two files, and no compiler compares them (finding 205). The card that opens a room also
announces itself as a button to a screen reader, and the room's picture carries no description of
its own — the row already prints the name, the runs, the metres, the area and the perimeter as
text.

**And every control says what it is.** Glove-sized was half of it; the other half is spoken. A row
that opens the paywall was an unlabelled `clickable`, the three-way severity choice was three texts
whose selection lived in a background colour, the export format switches were a nameless `Switch`
beside a label it could not name, the conduit-spec buttons never said which one was chosen, the
signature pad was a blank canvas with no name and no way to clear it, and the AR screen — the one
screen a blind operator would need most — ended in a full-surface `Box` with nothing to
double-tap. Every acting row now carries a role; choices are `selectable` groups that announce the
chosen one; a setting's tap belongs to its row, not to a switch that cannot name it; every text
field has a `label` rather than a placeholder, because a placeholder is decoration and not a name;
the pad announces its field, whether it is signed, and offers *clear* as an accessibility action;
and the camera surface offers the only action it can offer without a fingertip — set the point in
the middle of the screen, where the reticle already is (finding 211). Six new sentences in six
locales, and `scripts/verify_sources.py` reads the modifiers instead of trusting them.

---

## Site file — the paperwork around the measurement

Measured metres are half a job. The other half is the evidence a tradesman still keeps on paper
today — the hours on the back of a delivery note, the diary in a notebook that gets wet, the
punch list printed two weeks later — and it all lives in the same project file:

| Part | What it records | Where it ends up |
|------|-----------------|------------------|
| **Customer / object** | name, address, phone | header block of the protocol |
| **Working time** | start/stop, one open session at most, per-session and total hours | *Working time* table with a total |
| **Site diary** | timestamped free text, newest first | *Site diary* section, printed forwards in time |
| **Defects** | what, where, severity (minor / important / critical), open or resolved | *Defects* table with status |
| **Entry photos** | one documentation photo per diary entry and per defect, taken in the app | *Photo documentation* appendix, two per row |
| **Costs** | hours x hourly rate + the material lump sum typed on site | *Costs* block: labour, material, net total |

Four deliberate choices:

* **The clock cannot be double-started.** `startSession` refuses a second open session, so a
  double tap on a wet screen cannot double the hours; `stopSession` closes whatever is open.
  The arithmetic lives in `core/model/FieldNotes.kt` (`WorkTime`) and is unit-tested, because
  this is the number that appears on an invoice.
* **Nothing is a second app.** A diary entry is as much "the job moved" as a new run, so it
  touches `updatedAtEpochMs` and the project list re-sorts with it. One JSON file per room, one
  backup, one export.
* **The protocol carries it.** `ProtocolPdfWriter` learned a generic `PdfSection` (table and/or
  paragraphs plus a note); the app assembles the sections from localised strings, because only
  the app has the string table. Adding a new kind of on-site note needs no change in `:export`.
* **A photo is a file name, not a database row.** `PhotoTokens` defines the three names
  (`<project>`, `<project>__diary_<entry>`, `<project>__defect_<entry>`), so a backup, a restore
  onto a new phone and the cascade delete all work from the name alone — and a project deleted on
  site cannot leave an orphan image behind.
* **Money is integer cents, formatted once.** `Pricing` (labour = work time x rate, rounded once)
  and `MoneyFormatter` (always euro, in the phone's own grouping) are the only place money is
  computed or printed, so the card on screen, the PDF and the CSV cannot disagree. Both decimal
  separators are accepted, both thousand-dot habits too, and an impossible grouping is rejected
  rather than guessed.

The project screen reflects the file back before the operator leaves the site: six readiness
chips (customer, technician, scan, runs, photo, time) and a line naming exactly what is still
missing for a handover-quality protocol.

The costs block is not a quotation engine and says so in the protocol: no VAT, no discount, no
per-item material prices — the tradesman types the lump sum he would have typed into a
spreadsheet. Not done, and on the roadmap rather than in the product: a draft/run persistence
across app restarts, a Bestellliste export, and a multi-run room summary.

---

## Data safety

A project is one JSON file in app-private storage, written atomically
(`tmp` + `fsync` + rename in `core/io/SafeFiles.kt`), so a crash mid-write can neither leave a
half project behind nor delete the previous version — the rename is the only moment the file
changes, and it is atomic. That survives updates and crashes — it does not
survive a cracked screen or an "app data" wipe, and for a foreman the measurements *are* the job.
So the help screen can copy every project into a folder the operator picks (through the Storage
Access Framework — no cloud unless they choose one) and read projects back from any phone.
Restores never overwrite: a project whose id already exists is imported under a fresh id, so
restoring onto a phone that still has the original leaves both copies in place. Restoring also
obeys the plan's project limit — a free installation cannot be filled from somebody else's
backup — and the files beyond the limit are left untouched in the zip, with the count shown.

---

## Licence and payment (freemium)

The app is proprietary *and* fully usable for free: one room, unlimited runs, the CSV bill of
materials and a watermarked protocol. **Pro** — EUR 10/month, EUR 100/year, EUR 999 lifetime —
adds unlimited rooms, a clean protocol and the GLB/PLY exports a BIM workflow consumes.

Payment is **crypto only** (SOL, BTC, ETH), because a store billing library needs the network and
this app does not have it. A licence is a short signed string verified on the device against a
public key that ships in the APK; the private key never leaves the developer. Everything about the
model and the key handling are in [docs/BILLING.md](docs/BILLING.md); how far an offline
licence can resist a cracked installation — clock rollback, sealed trial state, repackaged APKs,
R8 dropping the verifier — and what is *not* claimed, is in [docs/SECURITY.md](docs/SECURITY.md).

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

**Zero reflection.** `:ar-engine` and `:app` contain no `Class.forName`, no reflective method
lookup and no indirect `invoke`. SceneView **4.63.0** is used through its typed Compose API —
`rememberEngine()`, `rememberModelLoader(engine)`, `rememberMaterialLoader(engine)`,
`rememberEnvironmentLoader(engine)`, `rememberScene(engine)`, the typed `ARSceneView(...)`
parameters (including `sessionConfiguration`, `flashMode`, `depthMode`) and the typed
`MaterialLoader.createColorInstance` / `createUnlitColorInstance` factories. A SceneView upgrade
therefore fails the *build* if an API moved, instead of failing silently on a device.
`scripts/verify_sources.py` enforces the ban.

**License-fee free, verified at build time.** SceneView (Apache-2.0) + Filament (Apache-2.0),
ARCore (proprietary but free, ships in Google Play Services for AR), RTAB-Map compiled
**without** OpenCV non-free / SURF (BSD-3-Clause, SIFT/ORB only), Depth Anything V2 **Small
only** (Apache-2.0; Base/Large are CC-BY-NC and are refused by a checksum in
`MonocularDepthEstimator`). The notices those licences require are shown in the app itself
(**Help → Third-party components**), listed in [NOTICE](NOTICE) with the verbatim texts under
`licenses/`, and cross-checked by `scripts/verify_sources.py` rule 20. See
[docs/LICENSING.md](docs/LICENSING.md).

---

## Languages

Six locales ship as resources, and **no user-facing text is hardcoded**: the engines report
*states* (`TrackingMonitor.Guidance`, `RouteSession.RouteWarning`, `SurfaceKind`, `HitSource`,
`ArReadiness`) and the app maps them to `stringResource`.

| Directory | Locale | Keys |
|-----------|--------|------|
| `app/src/main/res/values/` | English (default) | 499 strings + 13 plurals |
| `app/src/main/res/values-de/` | Deutsch | 499 strings + 13 plurals |
| `app/src/main/res/values-es/` | Español | 499 strings + 13 plurals |
| `app/src/main/res/values-fr/` | Français | 499 strings + 13 plurals |
| `app/src/main/res/values-it/` | Italiano | 499 strings + 13 plurals |
| `app/src/main/res/values-pl/` | Polski | 499 strings + 13 plurals (`few`/`many` included) |

`scripts/verify_sources.py` fails on a missing key, an untranslated extra key, or a mismatched
format placeholder — so a half-translated release cannot ship. That includes the exported
protocol: every heading, column and footnote is a `pdf_*` resource, passed into `:export` as a
`PdfLabels` value object, so the document a Spanish-speaking electrician hands to a Spanish
customer is in Spanish while `:export` itself stays a plain JVM library with no resource table.

---

## Landing page

The public page in `docs/` is **generated**, not hand-edited, so the six languages cannot drift
apart or fall out of sync with the app's wording:

```bash
python3 tools/build_site.py      # rewrites docs/ — standard library only
python3 -m http.server -d docs 8080   # look at it
```

| Path | Contents |
|------|----------|
| `docs/index.html` | English (the default page, `x-default` target) |
| `docs/{de,es,fr,it,pl}/index.html` | the other five languages |
| `docs/assets/` | logo, favicon, apple-touch icon, Open Graph image (1200×630) |
| `docs/404.html` | one page for every lost visitor, in all six languages |
| `docs/sitemap.xml`, `docs/robots.txt`, `docs/.nojekyll` | what a static host needs |

`tools/site_translations.py` holds the five translations next to a 95-key English source; the
generator fails loudly rather than emitting a page with a missing key, and
`.github/workflows/pages.yml` repeats that check before publishing.

The page is also the shop: a problem section ("the hours on the delivery note"), a comparison
table against cloud site apps, the four numbers that sell it (±2 cm, 0 bytes, 6 languages, 3
files), a price anchor in journeyman wages, the three steps to a licence and a sticky mobile
call-to-action — all in the language the reader came with.

Search-engine work is in the template rather than bolted on: `lang` on `<html>`, per-language
`<title>`/description, canonical URL, the full `hreflang` matrix plus `x-default`, Open Graph and
Twitter cards, and JSON-LD `SoftwareApplication` (with the real 0 €/10 €/100 €/999 € offers) and
`FAQPage` entities. There is no CDN, no web font and no analytics — the page makes the same
promise as the app: nothing leaves the device, and here nothing is even requested from another
host.

Publishing (free, GitHub Pages) — after this branch is merged:

* **Settings → Pages → Source: "GitHub Actions"** (recommended; the workflow re-renders on every
  change to `docs/` or the generator), or
* **Settings → Pages → Source: "Deploy from a branch" → `main` / `/docs`** (no workflow needed).

The expected URL is <https://andrexthedev.github.io/ConstructAR/>; if the repository is renamed,
change `BASE_URL` at the top of `tools/build_site.py` and re-run it, because the canonical links
and the sitemap are absolute.

---

## Modules

| Module | Language | Responsibility |
|--------|----------|----------------|
| `:core` | Kotlin/JVM, **no Android deps** | Maths (`Vec3`, `Quat`, `Mat4`, `Plane3`, polygon containment), routing model, fillet/RMF smoothing, tube & duct mesh generation, measurement, bill of materials, formatting. This is the trust boundary: everything that decides a *number* lives here and is unit-tested on the JVM. |
| `:native-slam` | C++20 + JNI | TSDF volume (block-hashed, free-space carving optional), Surface Nets extraction, Manhattan-World frame solver, plane fitting, AprilTag 3.1.0 decoder (vendored, BSD-2-Clause) and marker-to-scale arithmetic, pose graph with loop closures, RTAB-Map bridge. Host-testable: `native-slam/src/main/cpp/tests`; the optional RTAB-Map branch is compile-checked in CI against stub headers that mirror upstream's declarations (finding 183). |
| `:ar-engine` | Kotlin/Android | ARCore session & config, torch policy, tracking monitor, depth capture, TSDF pipeline, hit cascade, AI depth, anchors, routing state, Filament mesh upload, Compose AR surface. |
| `:export` | Kotlin/Android | PDF protocol, CSV bill of materials, GLB writer, PLY writer. Storage-agnostic (works on `OutputStream`). |
| `:app` | Kotlin/Android | Single activity, Compose Material 3 UI with glove-sized controls, project storage (JSON files), navigation. |

---

## Build

Requirements: **JDK 21**, Gradle **9.6.0** (AGP 9.4.1 requires ≥ 9.6), Android SDK **37**, NDK **27.2.12479018**,
CMake **3.22.1**, and the SceneView **4.63.0** / ARCore **1.57.0** / Filament **1.72.1**
artifacts (pinned in `gradle/libs.versions.toml`).

```bash
git clone https://github.com/AndrexTheDev/ConstructAR.git && cd ConstructAR
gradle assembleDebug testDebugUnitTest   # the full gate: every module, warnings are errors
gradle :app:assembleDebug                # APK in app/build/outputs/apk/debug/
gradle :app:assembleRelease              # the shipping artifact: R8 + resource shrink
python3 scripts/verify_sources.py        # copyright notice / reflection / i18n gates, no SDK needed
```

**A beta build for a first hands-on run (finding 255).** Nobody has run the AR part on a real
phone yet, so there is a build type that removes every paywall for testing without touching the
shipping artifact:

```bash
gradle :app:assembleBeta                 # APK in app/build/outputs/apk/beta/
```

It carries `BETA_BUILD = true` (the entitlement layer then answers "lifetime", so the
one-project limit, the PDF watermark and the locked GLB/PLY export are gone), its own
application id (`….beta`, so it installs next to the sold app instead of over it), the debug
signing key, and the label *ConstructAR Beta*. It is unminified and debuggable on purpose - a
crash report from it is readable. `.github/workflows/beta-apk.yml` builds it into the
`constructar-beta-apk` artifact; `workflow_dispatch` needs the `actions: write` scope, so a push
that touches that file is the fallback trigger. What it collects stays on the phone:
`files/diagnostics/session-*.log` and `crash-*.log`, written by `Diagnostics`, plus the same
lines on the *Hilfe* screen, where **Dateien speichern** copies them into a folder the user picks
(finding 256). Nothing is uploaded, ever - the app has no `INTERNET` permission.

**16 KB pages (Android 15+).** The NDK build spells the alignment flags out
(`-Wl,-z,max-page-size=16384`, `-Wl,-z,common-page-size=16384`) instead of trusting the toolchain's
default, no source file in `:native-slam` assumes a 4 KB page (a gate forbids the literal and any
`mmap`/`madvise`/aligned allocation that does not ask `sysconf(_SC_PAGESIZE)` first), and CI reads
the result back: every `LOAD` segment of `libconstructar_slam.so` must be `0x4000` and the packaged
APK must pass `zipalign -c -P 16 -v 4`.

**Size is a feature.** The release APK is **25 MB** — R8 with resource shrinking, six locales
instead of the eighty a dependency tree carries, no preview/tooling artifacts, and native
libraries for one ABI instead of two (the second existed only for the emulator). `app/build.gradle.kts`
documents each lever, CI prints the debug and release sizes on every push, and the before/after
is in **[docs/PERFORMANCE.md](docs/PERFORMANCE.md) §4**. Cold start verifies the licence once,
keeps the device tag lazy and puts every file operation on IO.

The CI workflow downloads Gradle 9.6.0 and puts it on `PATH`; the wrapper scripts and JAR are
deliberately not committed, so `gradle` (not `./gradlew`) is the documented entry point here.
`allWarningsAsErrors` is on for every module, so a deprecation is a build failure.

The native volume mesher can be exercised without Gradle or a phone:

```bash
g++ -std=c++20 -O2 -pthread -I native-slam/src/main/cpp \
    native-slam/src/main/cpp/tsdf/volume.cpp \
    native-slam/src/main/cpp/tsdf/surface_nets.cpp \
    native-slam/src/main/cpp/slam/pose_graph.cpp \
    native-slam/src/main/cpp/geometry/manhattan_world.cpp \
    native-slam/src/main/cpp/geometry/plane_fit.cpp \
    native-slam/src/main/cpp/geometry/floorplan.cpp \
    native-slam/src/main/cpp/geometry/surface_mask.cpp \
    native-slam/src/main/cpp/calibration/tag_scale.cpp \
    native-slam/src/main/cpp/slam/rtabmap_bridge.cpp \
    native-slam/src/main/cpp/tests/host_tests.cpp -o /tmp/host_tests && /tmp/host_tests
```

Optional pieces (both are opt-in, the app works without them):

* **AI depth** — drop `depth_anything_v2_small_int8.tflite` into
  `app/src/main/assets/models/` and set `constructar.depthModelAsset` if the name differs.
  Without it the app uses ARCore depth only.
* **RTAB-Map** — set `constructar.rtabmap.dir=<path to RTAB-Map source>` in
  `gradle.properties`; the CMake build then compiles the BSD-only bridge. Note what is wired
  today: the app measures on ARCore's own tracking. Since finding 243 the built-in pose graph is
  fed - `KeyframeFeed` decides on each placed frame which pose is worth keeping (0.5 m of
  translation, 15° of rotation, tracker valid, 30 features, 200 ms apart), and the scan worker
  writes the node and the odometry edge between it and the previous one. Since finding 245 the
  correction is driven too, but never by itself: the native proximity detector proposes a revisit,
  the app asks the operator once ("the same place?"), and only a confirmation adds the closure
  edge and runs the optimiser on the worker, which reports the residual before and after and how
  far it moved the poses (finding 246) - and when the solver stops without converging it says
  that instead, because a half-finished descent is not a result (finding 247). What it
  does **not** do is move your measurements: the corrected poses are reported, not written back
  into the measured runs, because a run's metres were measured with taps and an inference may not
  replace them (finding 217). Appearance verification (RTAB-Map) is still not wired, so the
  closure rests on the operator's confirmation and says so. `verify_sources.py` reads the wiring
  from the code and refuses this file if it claims the opposite - in either direction: no document
  may promise a closure nothing feeds (finding 98), and none may call a live one dead (finding 248).

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
| Effect of the settled reticle | **−30 to −60 %** of the per-point scatter | `HitStabilizer` commits the per-axis median of a 1.2 s / 5 sample window instead of one frame; a stray frame cannot move the point |
| Wall-thickness slips | **caught, not measured** | `RouteWarning.SURFACE_JUMP`: a point that lands on a *parallel* surface more than 20 cm behind the last one is flagged (the "tapped through the doorway" error); a real corner stays silent |

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
* **The HUD percentage is detail, not area.** The number next to the triangle count is the share
  of a 68 k-triangle reference budget (`ScanState.detailFraction`), not a fraction of the room: a
  cluttered cellar fills it with a third of the walls captured, an empty hall may never reach it.
  It answers "is there enough geometry in the mesh to pick a cable out of", which is what a run
  is measured on. How much of the *room* was captured is what the scan-limit warning in the HUD
  (finding 97) and the protocol's scan note (finding 100) report; the app never computes a room's
  total surface, so it does not print a room percentage anywhere.
* **Drift.** ARCore drift is ~1 % of travelled distance and the shipped pipeline does not
  correct it: the pose graph that would redistribute a closure over the whole trajectory is
  host-tested but never fed (see *Optional pieces*), so a long run accumulates the error instead
  of folding it back. Re-measure a run longer than ~8 m, or measure it as two runs from a shared
  corner.

---

## Testing

* **Native (C++) — 332 assertions, all green**, exercised with plain `g++` (no Android SDK; the
  binary reports 336 passes because four of them sit inside the loop over the tag's rotations):
  SE(3) exp/log round trip, TSDF integration on synthetic depth, sphere/wall reconstruction
  areas and extents, a 30°-tilted plane (which pins the depth convention), free-space carving
  (a ghost disappears, the real wall does not), triangle budget, invalid input, pose-graph
  closure, proximity loop closure, every observation the integration counted is still in the
  volume's weights, and the Manhattan-World frame (finding 187): a 4 x 3 x 2.5 m room with one
  wall 8° off square and a 35° bay window comes back square to a fifth of a degree, the crooked
  wall is squared and stays where its points are, the bay is returned untouched, a set of parallel
  walls is refused rather than guessed at, and a room turned 30° yields the turned frame.
  It is host-tested and **not yet wired**: nothing in the app feeds it, so nothing the app prints
  is squared by it today.
* **Plane fitting** — the step in front of it: RANSAC for the consensus set, a covariance
  eigen-decomposition for the plane itself, the explained points removed, the next plane looked
  for. RANSAC is seeded from the options and from nothing else, so a fit is reproducible from its
  options alone; the triples only decide *which* points belong to a plane, never where it is; a
  plane has to be flat (`planarity`) and to cover something (`extentMetres`), which is what stops a
  line of points and a sliver of skirting board becoming walls, and a refused candidate is spent
  rather than ending the fit — one bolt head in front of a wall cost the scan every plane behind
  it (finding 191). The host suite runs the whole
  chain — a synthetic 4 × 3 m room cloud with 3 mm of noise and one wall 8° off square, six
  surfaces, one frame, five snapped planes and the crooked wall left at the 4 m it was measured
  at.
* **Waist-height slice and floorplan** (`tsdf/volume.cpp`, `geometry/floorplan.cpp`) — the first
  half of a 2D plan: `sliceSurfaceAtHeight(1.0f, 0.05f)` returns the surface voxels inside a 5 cm
  band at standing height (observed voxels only, so free and unseen space contribute nothing), and
  `vectoriseFloorplan` turns those points into wall segments — a 5 cm occupancy grid, boundary
  edges with the room on the left, loops chained through integer corner keys, and
  Ramer–Douglas–Peucker over each loop. A 4 × 3 m room comes back as four segments, 12.15 m² and
  14.10 m; an L-shaped room as six; a room with a 45° wall as three (the grid's corner-touching
  pinches are resolved first, or a diagonal wall breaks the loop into bubbles). Two things are
  deliberate: the traced loop is the *outer* face of a one-cell surface band, so area reads up to
  ~2 % high — measured +1.25 % on the rectangle, +1.7 % on the L, +2.4 % on the triangle, and the
  suite's tolerance is one-sided because reading *low* would mean a lost wall; and a doorway opens
  the ring, so a scan with the door open reports **no** area and **no** segments rather than
  closing the gap with a wall that is not there. Gap classification is the next step.
* **The plan reaches Kotlin** (`jni/slam_jni.cpp` → `nativebridge/FloorplanPayload.kt`) — one
  `float[]` per call: eight floats of header (valid, closed, area, perimeter, three counts and the
  occupied cells), then the wall runs as `x/z` pairs, the loop lengths with their closure flags and
  the outline. The pipeline (slice → mask → vectorise) runs entirely on the native side, because
  the mask needs the point normals and the normals never cross the bridge; a table top in the slice
  becomes a floorplan of the table the operator walked around. The *packer* is a plain function in
  `geometry/floorplan.cpp` with fifteen host assertions on its exact layout, so the payload is
  tested even though the JNI call itself cannot be. The Kotlin parser refuses anything the length
  does not prove — a truncated array read field by field yields zeros, and a room with zero-length
  walls is a plan that draws and is wrong — and a count that is fractional, negative or larger than
  the array is a broken writer rather than a rounding. The plan reaches the app the moment a run is
  committed — the scan worker slices the volume while it is still alive (400 ms bound),
  `ArMeasureController.finalFloorplan()` hands the payload on, and
  `ProjectViewModel.saveFloorplanThumbnail` writes the room's outline and its SVG (see *Room
  thumbnails* above and ARCHITECTURE §5b for the four links of that chain).
* **Semantic masking and distance-weighted fusion** — two pieces the slice needs to be usable. A
  waist-height cut crosses tables, counters and workbenches as well as walls, and a horizontal
  patch drawn as a wall puts a partition through the middle of the room: `maskVerticalSurfaces`
  keeps a point only when the surface normal is within 20° of vertical, which is the SDF gradient
  the slice already carries (`sliceSurfacesAtHeight`), and it publishes how many points it dropped
  as horizontal and how many had no measurable normal rather than guessing either. Measured on a
  synthetic frame: a vertical wall keeps all 2 580 of its points, the same plane seen by a camera
  looking straight up is 100 % dropped. And observations are now weighted by **1/Z²**, so a reading
  at 1 m carries sixteen times the authority of the same pixel at 4 m in the voxel's running
  average (clamped to [1/36, 4]); the evidence a frame deposits per voxel write is asserted to be
  exactly that number, so a helper that never reaches the integrator fails. Fractional weights are
  why the block's 16-bit weight array became fixed point in 1/256ths (`kWeightScale`) instead of a
  count. Both are host-tested and **not yet wired**: no JNI symbol, no caller.
* **AprilTag — the decoder is vendored, the wiring is not.** AprilTag 3.1.0's C core (BSD-2-Clause,
  unmodified, one family: tag36h11) sits in `native-slam/src/main/cpp/third_party/apriltag/` with
  its `VENDORED.md`, its entry in `NOTICE`, its licence text in `licenses/` and its row on the Help
  screen. `calibration/apriltag_detector.cpp` is the one file that includes it; `nativeDetectAprilTagScale`
  exposes it over JNI, and the host suite renders the printed pattern for id 7 and asserts the
  distances it reports (2 m and 4 m, at four rotations). `calibration/tag_scale.hpp` holds the rest:
  `TagDetector`, whose `available()` is false when no camera was supplied and whose reason says
  camera rather than decoder, and the marker-to-scale arithmetic (pair ratios against the printed
  layout, a median, a deviation filter, a baseline-weighted mean) which publishes a factor and its
  evidence and decides nothing - the window stays in `:core`. The frame path exists too:
  `:core`'s `MarkerBaseline` turns two sightings of one marker into a factor (camera-to-tag vectors
  rotated into world axes against ARCore's camera positions, median-filtered, baseline-weighted,
  and decided by `ReferenceCalibration.accepted`). `MarkerFrameReader` (`:ar-engine`) packs an ARCore Y plane with `:core`'s `LuminancePlane` (the
  plane is strided; a buffer that ignored the padding shears every row and decodes nothing) and
  calls the decoder with the session's intrinsics and the printed size - unit-tested with a decoder
  that records what it was handed, three answers kept apart: `null` for a broken question, an empty
  report for "no tag in view", metres otherwise. Since finding 235 the path reaches a screen:
  `MarkerProbe` (`:ar-engine`) composes reader and sweep (one sighting per tag in the frame, so a
  printed sheet of tags counts as several markers rather than one), `CameraPlanes` is the one adapter that
  touches `android.media.Image` (and closes it on every path - ARCore's CPU image queue is two
  deep, so a leaked image stalls the camera), and `MarkerSweep` (`:core`) holds the sequence a
  screen has: sightings stamped with the ARCore session, so a re-localisation cannot pair two
  world origins; capped per marker, so a tag seen in another room stops voting; and `Watching`
  until a real baseline exists instead of the 1.0 that standing still would certify. The AR
  screen's marker dialog shows that state in six locales through `:core`'s `MarkerStatusLine`,
  which never prints a factor without the evidence behind it - the pairs that agreed, the baseline
  they span and how tightly they agreed - announces the number as a live region while the operator
  walks, and applies
  an accepted factor through the same reporting-layer callback the two-tap sweep uses. The two trust knobs come from the native `TagScaleOptions`
  over `nativeTagScaleDefaults()`; `:app` invents none of them. Unproven and stated as such: no
  device has held a real tag against this path, and `estimateScaleFromTagSightings` - the printed
  *layout* sheet - is still reached from nowhere. `tools/verify-native.sh` builds and runs all of it the way CI does;
  `--tsan` runs the same suite under ThreadSanitizer, which is a gate: the fusion of a voxel is a
  read-modify-write of two arrays done by several workers, and only the sanitizer can see it when
  that update escapes the shard lock (finding 181). It reports none.
* **JVM (`:core`)** — transform maths, plane fits and polygon containment, fillet smoothing,
  measurement, net wall area with openings subtracted as a union (the overlap case included),
  the drywall take-off's arithmetic (boards, studs per run, tracks, screws, waste), DIN 18015 zone
  snapping (refusals, tie-break, a room with no ceiling zone), clash geometry (segment–box slab
  clip, exact distances, segment–segment),
  tube/duct mesh invariants, BOM aggregation, the working-time clock, the money
  arithmetic (`Pricing`: rounding, both decimal separators, thousand dots, euro forced on a
  `pl-PL` phone), the photo-token naming contract and the third-party notice list the Help screen
  shows (every linked component attributed, only fee-free licences).
* **JVM (`:app`, Robolectric)** — the storage layer against a real filesystem: a room survives
  a save and a load, a blank name falls back, restoring a backup of a room that is still on the
  phone keeps both, text that is not a project is refused, a corrupt file is reported as
  unreadable but never deleted (the bytes are the operator's only copy), a temp file left by a
  kill is swept while the room is not, deleting one room leaves the others and their photos
  intact, and the backup is byte-for-byte what the app wrote. Plus the theme: WCAG contrast is
  computed for every text pair the UI draws and asserted at 4.5:1.
* **JVM (`:app`)** — the printed cost block: no block without time or material, labour +
  material = net total through the same formatter the PDF uses, a running session billed to now,
  a project rate overriding the firm's. Plus the wall screen's assembly: a closed ring is four
  walls and the closing one counts, an open ring is none, the calibration factor scales the run
  and not the assumed wall height, a mounting height that is not relative to a tracked floor
  produces no zones, a point below the floor is refused rather than assigned, and a project file
  written before `floorHeightM` existed still loads.
* **JVM (`:native-slam`)** — the pose-graph report as it crosses the JNI bridge: the
  optimiser's convergence verdict is carried across instead of being re-derived from the two
  costs, and the pose block is read past that flag rather than from it. Plus the engine's
  lifetime: `close()` frees the native context once however often it is called (three calls in a
  row, or two threads at the same moment), and every other method is a no-op afterwards instead of
  handing a freed pointer to the JNI layer (finding 182).
* **JVM (`:ar-engine`, `:export`)** — hit cascade priority and rejection reasons, architectural
  snapping (a derived corner found, an edge clipped out of two polygons, walls that stop short
  producing no corner, the tie that a corner wins over an edge, and the tiers that are never
  snapped), metric depth
  calibration (including "refuses to fit on noise"), routing/anchor behaviour, GLB/PLY/CSV
  structural validation, the SVG thumbnail (one scale for both axes, a physical wall width, the
  C locale under a German default, and every refusal) and the picture the AR screen writes from a
  plan (the largest closed loop wins, an open loop is never closed here, a bigger open loop never
  beats a smaller closed one, the file lands in the room's own folder, an unsafe id and an
  oversized SVG are refused, and deleting a room takes the file with it), the protocol's note for a capture that hit the scan limit, the rows of
  its summary, the order list's uncertain-hit note, the HUD's detail budget, the tracking
  monitor's steady state (a frame that changes nothing publishes nothing), the torch's refusal
  path (a camera that says no is not asked again), which plane under the operator is *the floor*
  (a ceiling is not, the highest floor below the phone wins, a stairwell's storey is not this
  room), and the ten rules of the reticle stabiliser (a settled median, an
  outvoted stray frame, a moving hand that never locks, a plane or tier change that resets the
  window, a miss that clears it).
* **JVM (`:core` licence)** — the freemium gate: a signed licence verifies, a tampered payload
  and a key from another signing key are refused, a licence bound to another phone is refused,
  an expired subscription is reported as *expired* rather than invalid, the URL-safe base64
  round-trips every byte value, and — the interesting one — a licence minted by
  `tools/mint_licence.py` with `openssl` verifies inside the app, which pins the
  openssl↔`java.security` contract rather than assuming it.
* **Instrumented tests** would need a physical ARCore device; none are included, and nothing in
  this repository claims to have run on one.
* **CI**: `.github/workflows/native-tests.yml` builds and runs the native suite with both `g++`
  and `clang++` at `-O1`/`-O2` with `-Werror`, on every push that touches the C++ core. It also
  compiles the optional RTAB-Map branch against stub headers that mirror upstream's own
  declarations — so the check has an opinion — and runs
  `cmake -P native-slam/src/main/cpp/tests/rtabmap-layout-check.cmake` — six checks on where a
  real checkout keeps its headers and archives, without an NDK, because that switch had never
  once been satisfied by the documented build (finding 110).
* **CI**: `.github/workflows/android-build.yml` runs `gradle assembleDebug testDebugUnitTest`
  (warnings are errors), asserts `libconstructar_slam.so` is inside the arm64 APK, and uploads
  the test reports. That workflow — not a local machine — is where the "it compiles" claim is
  proven.
* **Static gates**: `scripts/verify_sources.py` checks that every authored file states its owner -
  the notice itself (`Copyright (c) <year> AndrexTheDev <hippie…>`), not merely the author's handle,
  which a GitHub link satisfies (finding 215) - the reflection ban,
  the published test and assertion counts against the suites themselves, that no document calls a
  shipped path dead (finding 216),
  hardcoded UI text in shipped code, string-resource references, locale parity, XML
  well-formedness and the manifest hardening — without needing a JDK. One of its rules holds the
  native layer to its four build lists at once (a `geometry/*.cpp` must be compiled by CMake, the
  host script and both workflows, or it is dead code in the configuration nobody tried), and
  another refuses an Eigen include in the self-contained build. Two more keep a
  *claim* tied to the code: no document may describe a loop closure while nothing feeds the pose
  graph (finding 98), the native assertion count in this file must equal the call sites in
  `host_tests.cpp` (finding 99), and a project file's `schemaVersion` must actually be compared
  before a restore accepts it (finding 101). A third holds a *document* to an *artefact*: every
  component the release checklist promises to attribute must exist in `ThirdPartyNotices.ALL`
  and in `NOTICE`, and any licence text `NOTICE` points at must be there (finding 109). And a
  *screen* to its *promise*: the AR gate may not fetch ARCore's answer once and keep it, because
  an install finishes in the Play Store while the screen is stopped (finding 146), and a
  percentage may not be truncated on its way to the signed page (finding 147), and a printed
  clock may not take its convention or its digits from the phone's language (finding 148), and
  the plan's room budget may only be computed in `ProjectSlots` (finding 149), and a room file
  the build cannot read has to be reachable and deletable from the list (finding 150), and both
  estimating options the engine reads must be passed where the list is built (finding 151), and the
  order list that leaves the shop must state the waste it applied (finding 152), and a backup has to
  copy the bytes it found rather than re-encode them (finding 153), and every length total has to be
  the sum of the rows the documents print (finding 154), and a column of numbers may not sit under a
  blank header (finding 155), and a control may not ask the operator to pick a value it never shows
  (finding 156), a CI failure reporter has to be able to find its pull request (finding 157), and
  the phone may not read a legal confidence as an absent one (finding 158), a memory budget may only
  be spent atomically (finding 159), a block's per-frame bookkeeping may only happen under the lock
  that owns it (finding 160), a run left out of the order sheet has to be counted somewhere
  (finding 161), the run the operator is measuring has to reach the screen (finding 162), and a
  frame may only be placed where the tracker actually knows the phone is (finding 163), and a
  finished run must give back what it was measured with (finding 164), and a runtime handle must
  not be written into the project file (finding 165), and a rule that was fixed twice may not
  keep a second implementation around for the tests to hold (finding 166), and a format the
  export can actually produce has to be the one it produces (finding 167), and a buffer handed
  to two threads may not go back to the pool on one reader's word (finding 168), and a rule
  about frames may not be read off a clock that ticks in someone else's steps (finding 169), and
  a counter the documents promise the operator can see has to reach a screen (finding 170), and
  a state object may not carry a field nothing reads (finding 171), and the render loop may not
  allocate a plane it already has a pool for (finding 172), and a pass that must copy may reuse
  the buffer it copied into last time (finding 173), and a sentence the operator's own labels
  made longer has to move down the page rather than print over the footer (finding 174), and a
  document may not promise a second read of a photo that nothing performs (finding 175), and a
  failed save may not delete the room it was saving (finding 176), and a clamp may not cut an
  emoji in half on its way into the customer's PDF (finding 177), and a run name may not reach
  the mesh hand-off as a row of question marks (finding 178), and a warning the app derived from
  the geometry has to be able to go away again (finding 179), and a control that offers "the trade
  default" has to say what that is before it is chosen (finding 180).
* **Site gate**: `tools/seo_audit.py` reads the *generated* landing pages and refuses a
  regression: canonical and the six `hreflang` pairs, title/description lengths, Open Graph and
  Twitter cards with a real image size, JSON-LD whose offers match the four real prices, every
  internal link and fragment resolving, sitemap `<lastmod>`, robots.txt, the manifest icons
  (declared size checked against the file), and a 404 that is `noindex` with absolute URLs only.
* **Agreement gates**: `tools/i18n_audit.py` fails when a translated locale still carries the
  English string (with a reviewed allowlist for words that are genuinely the same — "Export" is
  German, "Surface" is French), when a surface mixes address forms (the app says *du/tú/tu*
  throughout; the French page says *vous* throughout, which is its own decision and not a
  defect), when French or Italian writes `\'` where every other string uses `’`, and
  `tools/consistency_audit.py` fails when the landing page and the app disagree on the three
  wallet addresses, the four prices, the six languages or the version.
* **No screenshot matrix, on purpose.** One existed during the systematic beta test and was
  deleted with it: a pipeline that renders screens nobody can compare against is coverage in
  appearance only. Layout is a device-matrix item
  ([docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)); the six locales are held by the
  string gates above.

The pre-release gate (device matrix, measurement verification against a laser, licensing,
performance) is [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md). How each layer is run,
what it can and cannot prove, and the defect log of the systematic beta test are in
[docs/TESTING.md](docs/TESTING.md).

---

## Status

**The full gate is green in CI** — `assembleDebug testDebugUnitTest`, every module, warnings as
errors:

| Gate | Result |
|------|--------|
| `scripts/verify_sources.py` (copyright notice, reflection ban, i18n parity, no stale claim) | pass — every authored file, the eight generated site artefacts included |
| `tools/seo_audit.py` (metadata, structured data, links, sitemap, manifest) | pass — 6 pages + 404 |
| `tools/i18n_audit.py` / `tools/consistency_audit.py` | pass — no English left in a locale, one address form per surface, no price or address drift |
| `:core` / `:export` unit tests | pass |
| `:ar-engine` unit tests | pass |
| `:app` unit tests (incl. Robolectric storage and WCAG contrast) | pass — 1123 tests in the app+core+ar-engine+export+native-slam suites |
| Kotlin compile of all five modules with `allWarningsAsErrors` | pass — no compiler diagnostics |
| Host C++ suite, `tools/verify-native.sh` (`-O2 -Wall -Wextra -Werror`) | pass — 332 checks, 0 failures |
| Host C++ suite under ThreadSanitizer (`--tsan`) | pass — 332 checks, 0 failures, no data race reported |
| RTAB-Map layout probe (`cmake -P …/rtabmap-layout-check.cmake`) | pass — 6 checks, 0 failures |
| NDK build, `arm64-v8a` + `x86_64` | pass — `libconstructar_slam.so` per ABI (16 KB page alignment requested via `android.experimental.nativeLibraryAlignmentPageSize=16k`) |
| APK packaging + `lib/arm64-v8a/libconstructar_slam.so` check | pass — debug APK uploaded as an artifact |

The build sandbox used during development has no JVM, no Android SDK and no NDK, so every one of
those numbers comes from the `android-build` workflow on GitHub-hosted runners; the workflow
posts the failing diagnostic block to the pull request so the result is reproducible by anyone
with the token, not just by the author.

One console notice remains on purpose: AGP 9 wants to own Kotlin support, and the project opts
out (`android.builtInKotlin=false` + `android.newDsl=false` in `gradle.properties`) because
`allWarningsAsErrors`, `jvmTarget` and the Compose compiler are configured through the Kotlin
Gradle plugin. The opt-out prints a deprecation notice at configure time; it is not a compiler
warning and does not fail the gate. The full migration to built-in Kotlin is a mechanical change
for the day AGP removes the switch.

Still true of a release not made from this commit: it has not been run on physical hardware
here. Before shipping, run it on at least one depth-capable and one plane-only device, re-check
the SceneView integration points in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and retune the
TSDF constants from [docs/PERFORMANCE.md](docs/PERFORMANCE.md) on the target hardware.

---

## Licence

**All Rights Reserved** — see [LICENSE](LICENSE). Copyright (c) 2026
**AndrexTheDev** <hippie.highho@gmail.com>.

The repository is public **only** so that GitHub Actions can build it and reviewers can read it.
Being visible is not a licence: use, copying, modification, distribution, commercial use and
reverse engineering are prohibited. Every source file carries the corresponding header
(`SPDX-License-Identifier: LicenseRef-Proprietary`).

Third-party components remain under their own licences (Apache-2.0 / BSD-3-Clause / ARCore SDK
terms) and keep their notices: [NOTICE](NOTICE) and `licenses/` reproduce them, and the app shows
the same list on the Help screen. See [docs/LICENSING.md](docs/LICENSING.md) for the inventory and
obligations.

---

© 2026 **AndrexTheDev** <hippie.highho@gmail.com> — All rights reserved. See [LICENSE](LICENSE).
