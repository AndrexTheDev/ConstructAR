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
| **D** | Polyline length `L = Σ‖Pᵢ − Pᵢ₊₁‖`, every node bound to an ARCore `Anchor`, live HUD label from the `hud_length` format (`%1$.2f m`, localised) | `ar-engine/.../route`, `core/.../geo/PolylineMetrics.kt`, `app/src/main/res/values*/strings.xml` | Measured on the **surface** points, never the offset centreline |
| **E** | Local export: PDF protocol, CSV material list, `.glb` (glTF 2.0 binary) and `.ply` | `export/` | Written through the Storage Access Framework; no media storage permission, no share-to-cloud |

Plus: auto-torch with hysteresis, monocular depth (Depth Anything V2 Small) fused into the
metric depth field, loop-closure pose graph, thermal-aware degradation, and explicit handling
for every way AR can fail on a site.

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
   standing in the room with this phone, not reading the PDF at a desk.
4. **Measure → Export** — the AR viewfinder with its HUD, then the export screen, where the
   operator picks a folder once and gets PDF, CSV and (with Pro) GLB/PLY. The customer can sign
   on the glass with a finger; the signature is rasterised black-on-white and written onto its
   own final page of the protocol, because a handover document without a signature is a note.

This replaced a two-screen design (list → AR) that was technically sufficient and practically
wrong: a foreman standing in a basement could not tell whether the scan had worked, whether the
runs were saved, or what was left to do.

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
`MonocularDepthEstimator`). See [docs/LICENSING.md](docs/LICENSING.md).

---

## Languages

Six locales ship as resources, and **no user-facing text is hardcoded**: the engines report
*states* (`TrackingMonitor.Guidance`, `RouteSession.RouteWarning`, `SurfaceKind`, `HitSource`,
`ArReadiness`) and the app maps them to `stringResource`.

| Directory | Locale | Keys |
|-----------|--------|------|
| `app/src/main/res/values/` | English (default) | 81 strings + 2 plurals |
| `values-de/` | Deutsch | parity enforced |
| `values-es/` | Español | parity enforced |
| `values-fr/` | Français | parity enforced |
| `values-it/` | Italiano | parity enforced |
| `values-pl/` | Polski | includes `few`/`many` plural forms |

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
| `:native-slam` | C++20 + JNI | TSDF volume (block-hashed, free-space carving optional), Surface Nets extraction, pose graph with loop closures, RTAB-Map bridge. Host-testable: `native-slam/src/main/cpp/tests`. |
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
python3 scripts/verify_sources.py        # header / reflection / i18n gates, no SDK needed
```

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
* **Drift.** With no loop closure, ARCore drift is ~1 % of travelled distance; the pose graph
  redistributes a closure over the whole trajectory, so a 1.4 m closure error becomes ~0.1 m of
  per-edge residual rather than a kink in the mesh.

---

## Testing

* **Native (C++) — 44 assertions, all green**, exercised with plain `g++` (no Android SDK):
  SE(3) exp/log round trip, TSDF integration on synthetic depth, sphere/wall reconstruction
  areas and extents, triangle budget, invalid input, pose-graph closure, proximity loop closure.
* **JVM (`:core`)** — transform maths, plane fits and polygon containment, fillet smoothing,
  measurement, tube/duct mesh invariants, BOM aggregation, the working-time clock, the money
  arithmetic (`Pricing`: rounding, both decimal separators, thousand dots, euro forced on a
  `pl-PL` phone) and the photo-token naming contract.
* **JVM (`:app`, Robolectric)** — the storage layer against a real filesystem: a room survives
  a save and a load, a blank name falls back, restoring a backup of a room that is still on the
  phone keeps both, text that is not a project is refused, a corrupt file is reported as
  unreadable but never deleted (the bytes are the operator's only copy), a temp file left by a
  kill is swept while the room is not, deleting one room leaves the others and their photos
  intact, and the backup is byte-for-byte what the app wrote. Plus the theme: WCAG contrast is
  computed for every text pair the UI draws and asserted at 4.5:1.
* **JVM (`:app`)** — the printed cost block: no block without time or material, labour +
  material = net total through the same formatter the PDF uses, a running session billed to now,
  a project rate overriding the firm's.
* **JVM (`:ar-engine`, `:export`)** — hit cascade priority and rejection reasons, metric depth
  calibration (including "refuses to fit on noise"), routing/anchor behaviour, GLB/PLY/CSV
  structural validation, and the ten rules of the reticle stabiliser (a settled median, an
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
  and `clang++` at `-O1`/`-O2` with `-Werror`, on every push that touches the C++ core.
* **CI**: `.github/workflows/android-build.yml` runs `gradle assembleDebug testDebugUnitTest`
  (warnings are errors), asserts `libconstructar_slam.so` is inside the arm64 APK, and uploads
  the test reports. That workflow — not a local machine — is where the "it compiles" claim is
  proven.
* **Static gates**: `scripts/verify_sources.py` checks copyright headers, the reflection ban,
  hardcoded UI text in shipped code, string-resource references, locale parity, XML
  well-formedness and the manifest hardening — without needing a JDK.
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
* **The temporary screenshot matrix** (`.github/workflows/screenshots.yml`) renders every screen
  in all six languages on the JVM and every landing page in real Chromium at three viewports. It
  lives in build output, is not part of the release gate, and is meant to be deleted after the
  review — [docs/TESTING.md](docs/TESTING.md) says exactly what it touches.

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
| `scripts/verify_sources.py` (headers, reflection ban, i18n parity) | pass — 162 files |
| `tools/seo_audit.py` (metadata, structured data, links, sitemap, manifest) | pass — 6 pages + 404 |
| `tools/i18n_audit.py` / `tools/consistency_audit.py` | pass — no English left in a locale, one address form per surface, no price or address drift |
| `:core` / `:export` unit tests | pass |
| `:ar-engine` unit tests | pass |
| `:app` unit tests (incl. Robolectric storage and WCAG contrast) | pass — 210 tests in the app+core+ar-engine+export suites |
| Kotlin compile of all five modules with `allWarningsAsErrors` | pass — no compiler diagnostics |
| Host C++ suite (`-O2 -Wall -Wextra -Werror`) | pass — 44 checks, 0 failures |
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
terms); see [docs/LICENSING.md](docs/LICENSING.md) for the inventory and obligations.

---

© 2026 **AndrexTheDev** <hippie.highho@gmail.com> — All rights reserved. See [LICENSE](LICENSE).
