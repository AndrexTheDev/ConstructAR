# Release checklist

Everything here is a gate before an APK reaches a site, in the order it should be done.

## 1. Build and signing

- [ ] `gradle clean assembleDebug testDebugUnitTest` passes from a clean checkout
      (`allWarningsAsErrors` is on for every module — a warning is a build failure)
- [ ] `gradle lint` has no `Error` severity issues
- [ ] No `-Pconstructar.lenientWarnings=true` in any release invocation (that flag exists only
      to unblock a toolchain regression, never a release)
- [ ] `python3 tools/seo_audit.py`, `python3 tools/i18n_audit.py` and
      `python3 tools/consistency_audit.py` all report `OK` (the published pages, the six
      translations, and agreement between the page and the app)
- [ ] `python3 scripts/verify_sources.py --verbose` reports `OK` (headers, reflection ban,
      string-resource references, six-locale parity)
- [ ] `.github/workflows/android-build.yml` is green, including the check that
      `libconstructar_slam.so` ships inside the arm64 APK
- [ ] Release keystore configured outside VCS (`~/.gradle/gradle.properties`), never committed
- [ ] `versionCode` bumped, `versionName` matches the protocol PDF footer
- [ ] R8: verify `NativeSlamEngine` natives, `com.google.ar.core.**`, `io.github.sceneview.**`
      and the kotlinx.serialization serializers survive (`app/proguard-rules.pro`)
- [ ] 16 KB page-size compatible (`ndk { debugSymbolLevel }`, NDK r27+)

## 2. Licensing

- [ ] `LicencePublicKey.PUBLIC_KEY_BASE64` is the **production** key, not `DEMO_KEY_BASE64`.
      With the placeholder in place the app verifies licences no real signer ever produced, and
      the Help screen shows the developer note. `python3 scripts/verify_sources.py` prints the
      reminder on every run
- [ ] `LICENSE` present and unmodified (All Rights Reserved — see `docs/LICENSING.md`)
- [ ] Third-party attributions (`NOTICE` or the in-app licences screen) cover SceneView,
      Filament, ARCore and Depth Anything V2 Small, as Apache-2.0 requires
- [ ] Depth model is **Small / Apache-2.0**, SHA-256 pinned in `MonocularDepthEstimator.Config`
- [ ] RTAB-Map (if used) built with `OPENCV_ENABLE_NONFREE=OFF`; no SURF anywhere in `ldd`
- [ ] In-app "Licences" screen lists SceneView, Filament, ARCore, LiteRT, RTAB-Map, DA V2
- [ ] Google Play data-safety form: **no data collected, no data shared** (there is no
      `INTERNET` permission — say so explicitly)

## 3. Device matrix (minimum)

| Device class | What to verify |
|---|---|
| Depth-capable flagship (ToF/LiDAR) | raw depth + confidence, tier-2/3 hits, fused AI depth |
| Depth-capable mid-range | frame drops under load, thermal behaviour, HUD readability in sunlight |
| Plane-only (no Depth API) | cascade falls back to plane + feature point, messaging is honest |
| Tablet | glove controls, layout, split-screen |

- [ ] Camera permission denied twice → the gate offers *open settings* instead of a dead
      *grant* button, and granting there is noticed on return (no app restart needed)
- [ ] ARCore not installed → install flow → back to the app
- [ ] ARCore session failure (occupy the camera from another app) → full-screen message, no
      live controls behind it; *try again* recovers, *save* keeps a run in progress
- [ ] Airplane mode: scan, measure, export all work
- [ ] Torch: supported device turns it on below 40 lux, off above 120 lux, no flicker
- [ ] Tracking loss (cover the camera) → coaching text, recovery without losing the run
- [ ] Back gesture and close button after ≥ 2 points → dialog offers save / discard / cancel;
      back with the dialog open closes the dialog, not the screen
- [ ] Thermal: 15-minute continuous scan without ANR, throttle mode visible in the HUD
- [ ] Font scale set to 200 % (Settings → Display → Font size, largest) on a 640 dp-tall
      phone: onboarding shows *next* and *skip*, and every screen can be scrolled to its
      last button. Onboarding did not scroll until finding 66
- [ ] Process death during a scan → project list intact, no half-written JSON
- [ ] Kill the app mid-run (Developer options → *Don't keep activities*, or swipe it away
      after 3+ points) → re-entering the AR screen offers the run back; *save* restores the
      length and the fittings, *discard* removes the offer for good

## 4. Measurement verification (the one that matters)

- [ ] Measure a **known 5 m wall** with a laser: report the deviation. Acceptance: ≤ 2 cm on a
      plane hit, ≤ 6 cm on depth-only runs.
- [ ] Measure a 90° corner run: check the bend geometry does not cut the corner, and the
      reported length equals the *chord* sum.
- [ ] Compare the PDF figure, the CSV figure and the on-screen HUD for the same run — they must
      be identical to the last digit.
- [ ] Re-open the exported `.glb` in Blender and in Revit/Navisworks: geometry sits on the
      floor plane, Y-up, metres, colours preserved.
- [ ] Open the `.csv` in Excel (European locale): one column per field, decimal points intact.

## 5. Performance

- [ ] Integration time on the target SoC (see `docs/PERFORMANCE.md` §4); adjust `pixelStep`
      before touching the voxel size
- [ ] 30 fps maintained with the AR overlay + scan mesh visible
- [ ] Battery: 1 h scan + torch ≤ 20 % on a 4 500 mAh device
- [ ] No allocation in the frame callback (Android Studio profiler, 2 minutes)

## 6. Documentation

- [ ] `README.md` accuracy table matches what §4 measured
- [ ] `docs/SETUP.md` §6 SceneView integration points re-verified against the pinned version
- [ ] `docs/PERFORMANCE.md` numbers replaced with the real device figures

---

© 2026 **AndrexTheDev** <hippie.highho@gmail.com> — All rights reserved. See [LICENSE](../LICENSE).
