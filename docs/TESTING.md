# Testing ConstructAR

Everything in this document has run. Nothing in it is a plan.

The app is verified in seven layers. Each one answers a question the others cannot, and each one
is cheap enough to run on every change — which is the only property that matters, because a test
that is too expensive to run is a document, not a test.

| Layer | Runs in | Proves |
| --- | --- | --- |
| 1. Static gates | `scripts/verify_sources.py` | headers, reflection ban, no hardcoded UI text, every `R.string` exists, six locales agree, XML is well formed, Kotlin brackets balance, no INTERNET permission, no cloud SDK, manifest hardening |
| 2. Site gate | `tools/seo_audit.py` | the six landing pages, their metadata, their structured data, every internal link, the sitemap, robots.txt, the manifest and the 404 |
| 3. JVM unit tests | `gradle … testDebugUnitTest` | geometry, pricing, licences, storage, BOM arithmetic, formatting, routing warnings, hit stabilisation, the printed cost block |
| 4. Host C++ tests | `g++ … tests/host_tests.cpp` | TSDF integration, Surface Nets, pose-graph closure — with `-Werror`, no NDK needed |
| 5. Full build | `.github/workflows/android-build.yml` | `assembleDebug testDebugUnitTest` across five modules, warnings as errors, R8 on the release variant, the licence verifier surviving R8, the arm64 native library inside the APK |
| 6. Screenshot matrix (temporary) | `.github/workflows/screenshots.yml` | every screen in all six languages renders on the JVM; every landing page renders in real Chromium at three viewports |
| 7. Human review | a person, a tape measure | ±2 cm on a detected wall; whether a plumber understands the HUD |

## Running it locally

```bash
python3 scripts/verify_sources.py --verbose          # layer 1, no JDK needed
python3 tools/build_site.py && python3 tools/seo_audit.py --verbose   # layer 2
gradle assembleDebug testDebugUnitTest               # layers 3 and 5
```

The screenshot matrix needs a browser and a JVM and is opt-in:

```bash
gradle :app:testDebugUnitTest --tests "com.constructar.app.screenshots.*" \
  -Pconstructar.screenshots=true                     # 6 locales × 7 screens → app/build/screenshot-matrix
npm install --no-save playwright && npx playwright install --with-deps chromium
node tools/screenshots_site.mjs                      # 7 pages × 3 viewports → build/site-matrix
```

## The matrix is temporary, on purpose

`docs/TESTING.md` will outlive it; the matrix will not. It exists to answer the question *"does
this screen fit in Polish?"* without a device farm, and it writes into `build/`, which is never
committed. When the review is over, delete `.github/workflows/screenshots.yml`,
`tools/screenshots_site.mjs` and `app/src/test/kotlin/com/constructar/app/screenshots/` — no
other file depends on them.

It is deliberately **not** a golden-image test. A marketing page that fails CI because a headline
grew by two words is a test that gets deleted within a month. What is asserted is structural:
every screen renders text, no screen shows a raw resource key, every page has one `<h1>` and a
description, no image is missing `alt`, and nothing scrolls sideways on a 390 px phone.

## What is *not* verified

* **ARCore on a device.** No emulator and no physical phone are part of this repository. The AR
  pipeline is unit-tested at the seam (`SurfaceHitResolver`, `HitStabilizer`, `RouteSession`,
  `MetricDepthCalibrator`) and never end-to-end. Nothing here claims otherwise.
* **The measurement claim.** ±2 cm on a detected wall between 1 and 4 m is a design target from
  the accuracy table; `docs/RELEASE_CHECKLIST.md` lists the laser-measure comparison that has to
  happen on real hardware before it can be called measured.
* **The PDF and the GLB on a device.** Both writers are pure Kotlin and are unit-tested for
  structure, but `PdfDocument` is an Android class: the file is produced, never opened, in CI.
* **Anything about a real purchase.** There is no checkout server; the licence flow is tested
  with keys minted by `tools/mint_licence.py`.

## Findings from the systematic beta test

Each row is a defect that was found, fixed and — where a machine can see it — now prevented from
coming back. The "guard" column is the reason to believe it stays fixed.

| # | Finding | Found by | Fix | Guard |
| --- | --- | --- | --- | --- |
| 1 | The landing page scrolled sideways on a phone: 161 px of horizontal overflow in English, 307 px in French. The accuracy and comparison tables could not shrink below the width of their `nowrap` cells, so the whole document widened instead | real Chromium at 390×844 | tables get their own scroll box, cells may break inside a word, `nowrap` dropped below 700 px | the site matrix fails on any overflow and names the widest element |
| 2 | The language switcher on the **English** page linked to `../de/`, `../es/` … which resolves to `https://…/de/` — one level *above* the site. Every one of the ten language links on the root page was a 404 | `tools/seo_audit.py` (internal-link check) | `sibling_for()` computes the correct relative depth per page | the same audit checks every internal link on every page |
| 3 | The 404 page used relative asset and language URLs. GitHub Pages serves `404.html` at the *requested* path, so those resolve against a directory that does not exist | `seo_audit.py` + Chromium (`ERR_BLOCKED_BY_ORB`) | absolute URLs, with the reason in a comment | `check_not_found()` rejects any relative URL on that page |
| 4 | The 404 page had no description and an `<h1>` that read "404" | Chromium audit | a real headline and a description; the six-language list follows it | `seo_audit.py` + the site matrix |
| 5 | The Spanish `<title>` was 72 characters — truncated in a search result | `seo_audit.py` (20–70 window) | shortened to 52 | the same window, on all six pages |
| 6 | `LengthFormatter`'s documentation promised that 0.995 m prints as `1.00 m`. It cannot: `0.995f` is 0.99499994… in binary, so it prints `0.99 m` | a new boundary test | the documentation now says what the code does, and both sides of the boundary are tested | `LengthFormatterTest` |
| 7 | Missing `og:image:alt`, `twitter:image:alt`, a web app manifest, `color-scheme`, `sitemap` `<lastmod>`, `availability` on the offers and a `WebSite` node | manual metadata audit | all added by the generator | `seo_audit.py` checks each one, including that a declared icon size matches the file on disk |
| 8 | The shipped-UI text gate flagged the screenshot fixtures, i.e. it was not scoped to shipped code | `verify_sources.py` itself | test sources are exempt, with the reason stated in the docstring | — |
| 9 | CI reported a failed build as an annotation one line long: the workflow-command payload was cut at its first newline, which is why a real Gradle failure arrived as a deprecation warning | this round | the payload is joined into a single line, and the whole `FAILURE …` block plus the head of the log is reported | — |

Two of these (1 and 2) were user-visible on the live page. Neither was visible in the HTML: a
`grep` finds `../de/` and calls it a relative link, and no static check can measure a table.
