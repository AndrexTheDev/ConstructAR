#!/usr/bin/env python3
# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
"""
Static gates for ConstructAR.

`./gradlew assembleDebug testDebugUnitTest` is the real gate; this script is what can run
*without* an Android SDK, JDK or NDK — on a bare CI runner, in a container, or on a workstation
before the toolchain is installed. It catches the failure classes that are cheapest to fix here
and most expensive to discover in a 12-minute Gradle run:

  1. copyright header present in every source file            (LICENSE compliance)
  2. no reflection anywhere in any module                     (hard product requirement)
  3. every R.string / R.plurals reference resolves            (aapt2 break)
  4. the six locale files define the same keys/placeholders   (i18n completeness)
  5. no hardcoded user-visible text in Compose UI             (i18n completeness)
  6. resource XML is well-formed, apostrophes escaped         (aapt2 break)
  7. Kotlin files have balanced brackets                      (truncation guard)
  8. no INTERNET permission and no cloud SDK markers           (offline guarantee)
  9. no work-time total cached in `remember`                  (a clock must tick)
 10. the signature pad keeps its tap handler (finding 82)      (a tap is a signature)
 11. text the operator typed survives a rotation (finding 83)  (rememberSaveable, not remember)
 12. a money field is not keyed on the amount it writes (84)   (no re-formatting under the finger)
 13. every protocol states its expected error, in all six languages (88)
 14. no document promises a loop closure nothing feeds        (finding 98)
 15. the README's native assertion count equals the suite      (finding 99)
 16. the project schema version is actually compared            (finding 101)
 17. a kotlin.test assertion is called value-first              (finding 101)
 18. no backticked name carries a Windows-hostile character     (finding 102)
 19. the README's Kotlin test count equals the suite            (finding 104)
 20. every component the checklist promises to attribute is     (finding 109)
     in the app's notice list
 21. a field shared between the main and render threads is       (finding 118)
     declared `@Volatile`
 22. the two figures PERFORMANCE.md derives from a knob are      (finding 119)
     recomputed from that knob
 23. every confirmation that destroys something names what it    (finding 125)
     destroys
 24. every write into a folder the operator picked goes through   (findings 130, 131)
     ExportFiles.writeOnce
 25. something re-derives the licence when the app resumes        (finding 132)
 26. a screen that shows the device code also lets it be copied   (finding 133)
 27. a count of days is a plural resource, never a plain string   (finding 135)

Exit code 0 means "no finding". It does **not** mean "compiles" — only Gradle can say that.

Usage:  python3 scripts/verify_sources.py [--verbose]
"""

from __future__ import annotations

import math
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERBOSE = "--verbose" in sys.argv

LOCALES = ["values", "values-de", "values-es", "values-fr", "values-it", "values-pl"]
RES_DIR = ROOT / "app" / "src" / "main" / "res"

HEADER_MARKERS = ("AndrexTheDev",)
HEADER_SUFFIXES = {
    ".kt", ".kts", ".java", ".cpp", ".cc", ".hpp", ".h", ".c",
    ".xml", ".toml", ".properties", ".yml", ".yaml", ".txt", ".py", ".sh", ".md",
}

REFLECTION_PATTERNS = [
    re.compile(r"Class\s*\.\s*forName\s*\("),
    re.compile(r"\.getDeclaredMethod\s*\("),
    re.compile(r"\.getDeclaredField\s*\("),
    re.compile(r"\.getMethod\s*\("),
    re.compile(r"\.getDeclaredConstructor\s*\("),
    re.compile(r"javaClass\s*\.\s*methods"),
    re.compile(r"\.declaredMethods\b"),
    re.compile(r"ClassLoader\s*\.\s*getSystemClassLoader"),
    re.compile(r"loadClass\s*\("),
    re.compile(r"javaClass\s*\.\s*classLoader"),
]

# `Text("…")`, `label = "…"`, `contentDescription = "…"`, `text = "…"` — a literal, not a resource.
HARDCODED_UI_PATTERNS = [
    re.compile(r"\bText\s*\(\s*\"[^\"\n]{2,}\""),
    re.compile(r"\blabel\s*=\s*\"[^\"\n]{2,}\""),
    re.compile(r"\bcontentDescription\s*=\s*\"[^\"\n]{2,}\""),
    re.compile(r"\btext\s*=\s*\"[^\"\n]{2,}\""),
    re.compile(r"\bstringResource\s*\(\s*\""),
]

# A `remember` block that adds up work time: the key it is remembered on is the session list,
# and that list does not change while a clock runs - so the value freezes at the moment the
# screen opened (finding 59). Anything that totals a running clock has to be recomputed from
# a clock that ticks (`ui/ClockTick.kt`).
CACHED_CLOCK = re.compile(r"remember\s*\([^)]*\)\s*\{[^}]*totalWorkMillis\s*\(")

# The AR overlay is the only surface that draws text over a *camera image*, so a transparency
# written as a number there is text whose contrast nobody measures: it had drifted to 3.47:1 and
# 2.81:1 against a white wall, and to 1.4:1 on the selected conduit chip (finding 69). Every
# transparency lives in `HudStyle`, where `HudContrastTest` composites it over a white and a black
# wall. A numeric alpha in the screen itself is a finding.
AR_OVERLAY_SCREEN = "app/src/main/kotlin/com/constructar/app/ui/ar/ArMeasureScreen.kt"
AR_LITERAL_ALPHA = re.compile(r"alpha\s*=\s*[0-9]*\.?[0-9]+f")

# `repository.save(...)` returns whether the write landed - the phone may be full, the storage may
# be read-only. A call whose result is thrown away is work lost without a word: the field photo
# path reported "attached" for a file no room pointed at, and the copy failure before it was not
# even shown (finding 72).
# A write on the repository that answers whether it landed - `save`, `savePhoto`, `savePhotoAs`,
# `saveDraft`, and the deletes. The photo copy in `importBackup` was the one that got away: the
# rule knew only `save(` (finding 92), and the three deletes whose answer nobody read were found
# by widening it (finding 93).
DISCARDED_SAVE = re.compile(
    r"(?m)^[ \t]*repository\s*\.\s*(?:save(?:Photo(?:As)?|Draft)?|delete(?:Photo(?:As)?|Draft)?)\s*\("
)

# A platform exception's `message` is English, Java and a POSIX error number - not a sentence for
# an operator who reads German. The export and the backup printed it verbatim inside their own
# error dialogs (finding 73); every failure now maps to a localised sentence. A call site that
# really does want the raw text (ARCore's own diagnosis) says so in a comment above it.
RAW_DETAIL = re.compile(r"\.message\s*(\?:|!!)")

# A draft write is fire-and-forget, so two of them can complete out of order. They may therefore
# only be issued through `writeDraft`, which hands out a ticket in submission order (finding 74) -
# a direct `repository.saveDraft`/`deleteDraft` anywhere else is a save that can resurrect a draft
# the operator just deleted.
DRAFT_WRITE = re.compile(r"repository\s*\.\s*(save|delete)Draft\s*\(")
DRAFT_GUARD_WINDOW = 40

R_REFERENCE = re.compile(r"\bR\s*\.\s*(string|plurals|array|drawable|xml|style)\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)")

FORBIDDEN_PERMISSIONS = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"]
FORBIDDEN_DEPENDENCY_MARKERS = [
    "com.google.android.gms:play-services",
    "com.google.firebase",
    "com.google.mlkit",
    "com.google.android.gms.measurement",
]

findings: list[str] = []
notices: list[str] = []


def add(path: Path, message: str) -> None:
    try:
        rel = path.relative_to(ROOT)
    except ValueError:
        rel = path
    findings.append(f"{rel}: {message}")


def tracked_sources() -> list[Path]:
    """All source files worth checking, excluding build output and the git metadata."""
    out: list[Path] = []
    skip_dirs = {".git", "build", ".gradle", ".idea", "node_modules", "__pycache__"}
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        if any(part in skip_dirs or part.startswith(".") and part not in {".github"} for part in path.parts):
            continue
        if path.suffix in HEADER_SUFFIXES or path.name in {"CMakeLists.txt", "gradle.properties"}:
            out.append(path)
    return out


# Verbatim third-party licence texts: adding our own header would corrupt the licence, so they are
# the one place in the tree that legitimately has someone else's copyright line (finding 109).
UNHEADERED_LICENCE_DIRS = {"licenses"}


def check_headers(files: list[Path]) -> None:
    for path in files:
        if path.name in {"LICENSE"}:
            continue
        if path.parent.name in UNHEADERED_LICENCE_DIRS:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if not any(marker in text for marker in HEADER_MARKERS):
            add(path, "missing copyright header (AndrexTheDev)")


def kotlin_sources(*modules: str) -> list[Path]:
    out = []
    for module in modules:
        out.extend((ROOT / module / "src").rglob("*.kt"))
    return out


def check_reflection() -> None:
    for path in kotlin_sources("core", "ar-engine", "app", "export", "native-slam"):
        text = path.read_text(encoding="utf-8")
        for pattern in REFLECTION_PATTERNS:
            for match in pattern.finditer(text):
                line = text[: match.start()].count("\n") + 1
                add(path, f"reflection is forbidden (line {line}): {match.group(0).strip()}")


def check_app_hardcoded_text() -> None:
    """No literal user-visible string in shipped UI code.

    Test sources are exempt on purpose: a test fixture and a unit-test expectation are
    sample data, not something a user reads on a phone, and translating them would make the
    fixtures unreadable without making the app any more localised.
    """
    for path in kotlin_sources("app"):
        if "/res/" in str(path) or "/src/test/" in str(path) or "/src/androidTest/" in str(path):
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("*") or stripped.startswith("//") or stripped.startswith("/*"):
                continue
            for pattern in HARDCODED_UI_PATTERNS:
                if pattern.search(line):
                    add(path, f"hardcoded user-facing text (line {number}): {stripped[:100]}")


def load_locale(folder: str) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    path = RES_DIR / folder / "strings.xml"
    strings: dict[str, str] = {}
    plurals: dict[str, dict[str, str]] = {}
    tree = ElementTree.parse(path)
    for node in tree.getroot():
        if node.tag == "string":
            strings[node.attrib["name"]] = node.text or ""
        elif node.tag == "plurals":
            plurals[node.attrib["name"]] = {
                item.attrib["quantity"]: item.text or "" for item in node
            }
    return strings, plurals


def placeholders(text: str) -> list[str]:
    return sorted(re.findall(r"%\d+\$[a-zA-Z]|%%|%[a-zA-Z]", text))


def check_locales() -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    base_strings, base_plurals = load_locale(LOCALES[0])
    for folder in LOCALES[1:]:
        strings, plurals = load_locale(folder)
        missing = set(base_strings) - set(strings)
        extra = set(strings) - set(base_strings)
        for key in sorted(missing):
            add(RES_DIR / folder / "strings.xml", f"missing string '{key}' (present in values)")
        for key in sorted(extra):
            add(RES_DIR / folder / "strings.xml", f"string '{key}' is not in the default 'values'")
        for key in sorted(set(base_strings) & set(strings)):
            if placeholders(base_strings[key]) != placeholders(strings[key]):
                add(
                    RES_DIR / folder / "strings.xml",
                    f"placeholders differ for '{key}': "
                    f"values={placeholders(base_strings[key])} {folder}={placeholders(strings[key])}",
                )
        for name, forms in base_plurals.items():
            if name not in plurals:
                add(RES_DIR / folder / "strings.xml", f"missing plurals '{name}'")
                continue
            if "other" not in plurals[name]:
                add(RES_DIR / folder / "strings.xml", f"plurals '{name}' has no 'other' form")
            if set(forms) - set(plurals[name]) - {"few", "many", "two", "zero"}:
                add(RES_DIR / folder / "strings.xml", f"plurals '{name}' is missing a quantity form")
    return base_strings, base_plurals


def check_references(strings: dict[str, str], plurals: dict[str, dict[str, str]]) -> None:
    known_strings = set(strings)
    known_plurals = set(plurals)
    for path in kotlin_sources("app"):
        text = path.read_text(encoding="utf-8")
        for kind, name in R_REFERENCE.findall(text):
            if kind == "string" and name not in known_strings:
                add(path, f"R.string.{name} is not defined in res/values/strings.xml")
            elif kind == "plurals" and name not in known_plurals:
                add(path, f"R.plurals.{name} is not defined in res/values/strings.xml")


def check_offline_guarantees() -> None:
    manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    for permission in FORBIDDEN_PERMISSIONS:
        if permission in text:
            add(manifest, f"{permission} breaks the 100%-offline guarantee")

    for path in tracked_sources():
        if path.suffix not in {".kts", ".gradle", ".toml"}:
            continue
        body = path.read_text(encoding="utf-8")
        for marker in FORBIDDEN_DEPENDENCY_MARKERS:
            if marker in body and "OFFLINE_FORBIDDEN_GROUPS" not in body:
                add(path, f"forbidden cloud dependency marker: {marker}")


def check_cached_clock() -> None:
    """A running total may not be remembered: it would freeze when the screen opened."""
    for path in kotlin_sources("app"):
        text = path.read_text(encoding="utf-8")
        for match in CACHED_CLOCK.finditer(text):
            line = text[: match.start()].count("\n") + 1
            add(path, f"line {line}: a work-time total inside `remember` cannot move while a clock "
                      f"runs - total it from rememberNowMs(...) instead")


def check_discarded_saves() -> None:
    """A save whose Boolean is discarded is a write nobody checks (finding 72).

    One exemption: `writeDraft` is the single place a draft reaches the disk and it is
    deliberately fire-and-forget - the operator is mid-run and there is no screen to report a lost
    draft to. Its ordering is what protects the file, and `check_draft_write_ordering` owns that.
    """
    for path in kotlin_sources("app"):
        # Tests set state up and may ignore the answer; production code may not.
        if "/src/test/" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        guard_start, guard_end = _function_span(text, "fun writeDraft(")
        for match in DISCARDED_SAVE.finditer(text):
            if 0 <= guard_start <= match.start() < guard_end:
                continue
            line = text[: match.start()].count("\n") + 1
            call = match.group(0).strip().splitlines()[-1].strip()
            add(path, f"line {line}: `{call}` returns whether the write landed - "
                      f"bind it and report the failure instead of dropping it")


def check_ar_alpha_literals() -> None:
    """No transparency may be written as a number inside the AR screen (finding 69)."""
    path = ROOT / AR_OVERLAY_SCREEN
    if not path.exists():
        add(path, "the AR overlay screen is missing")
        return
    text = path.read_text(encoding="utf-8")
    for match in AR_LITERAL_ALPHA.finditer(text):
        line = text[: match.start()].count("\n") + 1
        add(path, f"line {line}: transparency over the camera must come from `HudStyle`, where "
                  f"`HudContrastTest` can measure it - not from a number here")


def check_raw_exception_messages() -> None:
    """No platform exception text in a UI error dialog unless the site is marked deliberate."""
    for path in kotlin_sources("app"):
        if "/src/test/" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
        for match in RAW_DETAIL.finditer(text):
            index = text[: match.start()].count("\n")
            context = "\n".join(lines[max(0, index - 6): index + 1])
            if "raw-detail" in context:
                continue
            add(path, f"line {index + 1}: an exception's own message is English and unlocalised - "
                      f"map the failure with `storageFailureOf` or mark the site `raw-detail`")


# A stored amount that a money field writes back on every keystroke. Used as a `remember` key it
# re-initialises the field's text from the value just saved, so the field rewrites itself as the
# operator types (finding 84).
MONEY_KEY = re.compile(
    r"by\s+remember(?:Saveable)?\(\s*[^)]*\b(firmHourlyRateCents|hourlyRateCents|materialLumpSumCents|wasteOverride)\b"
)


# Names a screen gives the state of a field the operator types into: a draft, a name, an address,
# a phone number, a note, a search query, a licence key, a title, a location. Booleans, dialogs,
# selections and one-shot status messages are deliberately not in this list - they may reset.
TYPED_FIELD_NAME = re.compile(
    r"^\s*var\s+(\w*(?:Draft|Name|Address|Phone|Notes|Query|Text|Title|Location))\s+by\s+remember(?![A-Za-z])",
    re.MULTILINE,
)


def check_signature_tap() -> None:
    """The signature pad must accept a tap, not only a drag (finding 82).

    `detectDragGestures` reports nothing until the touch has moved past the slop, and it was the
    pad's only input path: the dot renderers existed for a one-point stroke that no gesture could
    ever produce, so a customer who touched the glass left an empty pad and an unsigned protocol.
    The tap handler is what makes those dots reachable, and a refactor that drops it would take the
    whole guarantee with it - silently, because an empty pad is a legal state.
    """
    path = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" / "SignaturePad.kt"
    if not path.exists():
        add(path, "the signature pad is missing")
        return
    text = path.read_text(encoding="utf-8")
    if "detectTapGestures" not in text:
        add(path, "the pad has no `detectTapGestures`: a tap (a touch without a drag) would sign "
                  "nothing, and the protocol would leave the site without a signature page")
    if "state.commit(" not in text:
        add(path, "the pad must commit strokes through `commit`, so the drag and the tap path "
                  "cannot disagree about what counts as a finished stroke")


def check_money_fields_are_not_rekeyed() -> None:
    """A field that writes a stored amount must not be keyed on it (finding 84).

    `remember(key)` re-runs its initializer when the key changes, and this field stores its text on
    every keystroke: `var rateDraft by remember(firmHourlyRateCents) { format(firmHourlyRateCents) }`
    therefore rewrote the field after each digit. Typing "4" printed "4,00", the next keystroke
    appended to it ("4,005"), and 4.005,00 EUR - a valid rate - was saved for every future job.
    """
    for path in kotlin_sources("app"):
        if "/src/test/" in path.as_posix() or "/ui/" not in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        for match in MONEY_KEY.finditer(text):
            line = text[: match.start()].count("\n") + 1
            add(path, f"line {line}: the field is keyed on `{match.group(1)}`, which it writes on "
                      f"every keystroke - the text would be re-created from the stored amount while "
                      f"the operator types; key it on nothing (or on the room), not on the value")


def check_typed_fields_are_saved() -> None:
    """Text the operator typed must be `rememberSaveable`, not `remember` (finding 83).

    A `remember` is not part of the saved state: the phone turning, a font-size change or the
    system recreating the screen drops it, and the operator - who is standing next to the customer
    with one thumb free - finds the name, the address and the notes he just typed gone, with no
    message. The signature pad had the same defect (finding 77) and so did the onboarding screen
    (finding 66); this rule is the cheap net under all three.
    """
    for path in kotlin_sources("app"):
        if "/src/test/" in path.as_posix() or "/ui/" not in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        for match in TYPED_FIELD_NAME.finditer(text):
            line = text[: match.start()].count("\n") + 1
            add(path, f"line {line}: `{match.group(1)}` is a field the operator types into and it "
                      f"is held by `remember` - a rotation throws the typing away; use "
                      f"`rememberSaveable`")


def check_pdf_accuracy_note() -> None:
    """The protocol must state its expected error in every language (finding 88).

    The shop page told every visitor, in six languages, that "the protocol states the expected
    error too". The protocol said nothing of the kind: no tolerance anywhere, and a "Confidence"
    column that no line of the document explained. A customer signed a percentage with no unit and
    no bar - in the one file he keeps. The sentence now exists; this rule keeps it existing, in all
    six locales, with the figures a reader can act on and the placeholder the writer fills the
    confidence bar into, so a translator cannot quietly reduce it to "accurate measurement".
    """
    pdf_writer = (ROOT / "export" / "src" / "main" / "kotlin" / "com" / "constructar" / "export" /
                  "pdf" / "ProtocolPdfWriter.kt")
    pdf_text = pdf_writer.read_text(encoding="utf-8")
    if "val calibrationLabel: String = \"\"" not in pdf_text:
        add(pdf_writer, "the protocol no longer carries the calibration line, so a document with "
                        "corrected metres would not say what corrected them")
    elif "if (content.calibrationLabel.isNotBlank()) {" not in pdf_text:
        add(pdf_writer, "the protocol accepts a calibration label but no longer prints it")
    csv_writer = (ROOT / "export" / "src" / "main" / "kotlin" / "com" / "constructar" / "export" /
                  "csv" / "MaterialCsvWriter.kt")
    csv_text = csv_writer.read_text(encoding="utf-8")
    if csv_text.count("calibrationLabel.isNotBlank()") < 2:
        add(csv_writer, "the CSV writers no longer print the calibration row - both the full bill "
                        "and the wholesaler's order list need it")
    export_screen = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" /
                     "ui" / "ExportScreen.kt")
    screen_text = export_screen.read_text(encoding="utf-8")
    if "calibrationLabel = calibrationLabel" not in screen_text:
        add(export_screen, "the export screen no longer hands the calibration label to the "
                           "service, so the parameter would always be blank in the field")
    elif "stringResource(" in screen_text.split("scope.launch")[-1].split("ExportService.Request")[0]:
        add(export_screen, "a string resource is read inside the export coroutine - that is not a "
                           "composable context and cannot compile")
    ar_screen = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" /
                 "ar" / "ArMeasureScreen.kt")
    ar_text = ar_screen.read_text(encoding="utf-8")
    if "CalibrationSweep" not in ar_text or "onCalibration" not in ar_text:
        add(ar_screen, "the AR screen no longer drives the calibration sweep, so the factor has no "
                       "producer and the two-tap flow has no user")
    elif "ReferenceCalibration.accepted" in strip_kotlin(ar_text):
        add(ar_screen, "the screen decides the acceptance window itself - that decision belongs to "
                       "`:core`, or the dialog and the file can disagree about what counts")
    root_file = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" /
                 "AppRoot.kt")
    root_text = root_file.read_text(encoding="utf-8")
    if "viewModel.setCalibration(" not in root_text:
        add(root_file, "nothing hands the calibrated factor to the view model, so the AR dialog "
                       "would measure a factor and throw it away")
    for folder in LOCALES:
        path = RES_DIR / folder / "strings.xml"
        if not path.exists():
            continue
        strings, _ = load_locale(folder)
        note = strings.get("pdf_note_accuracy")
        if note is None:
            add(path, "missing string 'pdf_note_accuracy' - the signed protocol would carry no "
                      "accuracy statement at all")
            continue
        if "cm" not in note:
            add(path, "'pdf_note_accuracy' names no centimetre figure - the expected error the "
                      "shop page promises is the number the customer checks")
        if "%%" not in note:
            add(path, "'pdf_note_accuracy' has no escaped '%' - the accuracy is a percentage and "
                      "String.format would throw on a bare one")
        if "%1$d" not in note:
            add(path, "'pdf_note_accuracy' has no %1$d placeholder - the confidence bar would be "
                      "typed into the translation instead of coming from HitConfidence.RUN_TRUSTED")


# ── findings 98/99: a claim has to match the code ────────────────────────────────────────────────

NATIVE_SUITE = ROOT / "native-slam" / "src" / "main" / "cpp" / "tests" / "host_tests.cpp"
UNWIRED_MARKER = "not yet driven by the scan pipeline"

POSE_GRAPH_CALL = re.compile(
    r"\.\s*(optimizePoseGraph|addPoseNode|addPoseEdge|loopClosureCandidates|resetPoseGraph)\s*\("
)

STALE_LOOP_CLAIMS = (
    ("README.md", "proximity loop-closure proposer are used"),
    ("README.md", "loop-closure pose graph, thermal-aware"),
    ("docs/SETUP.md", "runs the optimiser and refreshes the node poses"),
    ("docs/SETUP.md", "Expected: run the loop closure"),
)


def check_loop_closure_claim() -> None:
    """A document may not promise a loop closure that nothing feeds (finding 98).

    Four documents described the loop closure as present behaviour - README's *Optional pieces*
    ("without RTAB-Map the built-in pose graph and proximity loop-closure proposer are used"),
    README's drift bullet ("the pose graph redistributes a closure over the whole trajectory"),
    SETUP.md 5.2 ("runs the optimiser and refreshes the node poses"), SETUP.md's troubleshooting
    table ("Expected: run the loop closure") and ARCHITECTURE.md's pose-graph section - while
    the truth was
    that no line of `:app` or `:ar-engine` ever called `optimizePoseGraph`, `addPoseNode`,
    `addPoseEdge` or `loopClosureCandidates`, and the JNI exposed no keyframe feed at all. The
    pose graph, the proximity proposer and the RTAB-Map bridge were built and host-tested, and
    nothing in the shipped app reached any of them: a room that was walked twice was never
    folded back together, and the accuracy table's "~1 % drift" rested on a correction that
    never ran.

    The rule reads the truth from the code: if some non-test, non-bridge Kotlin file calls the
    pose graph, the documents must stop carrying the marker; if nothing does, all of them must
    carry it. Either direction fails, so wiring the graph forces the docs back and unwiring it
    cannot be papered over, and the four stale sentences are named so they cannot return.
    """
    documents = {
        "README.md": ROOT / "README.md",
        "docs/SETUP.md": ROOT / "docs" / "SETUP.md",
        "docs/ARCHITECTURE.md": ROOT / "docs" / "ARCHITECTURE.md",
    }
    texts = {name: path.read_text(encoding="utf-8") for name, path in documents.items()}

    drivers: list[str] = []
    for path in kotlin_sources("app", "ar-engine", "native-slam"):
        posix = path.as_posix()
        if "/src/test/" in posix or "/nativebridge/" in posix:
            continue
        if POSE_GRAPH_CALL.search(path.read_text(encoding="utf-8")):
            drivers.append(posix.replace(ROOT.as_posix() + "/", ""))

    if drivers:
        for name, text in texts.items():
            if UNWIRED_MARKER in text:
                add(documents[name], f"'{drivers[0]}' now drives the pose graph, but this text "
                                     f"still says it is '{UNWIRED_MARKER}' - update the document "
                                     f"and this rule (finding 98)")
        return

    for name, text in texts.items():
        if UNWIRED_MARKER not in text:
            add(documents[name], f"nothing outside the bridge calls the pose graph, so this text "
                                 f"must say it is '{UNWIRED_MARKER}' - it otherwise promises a "
                                 f"loop closure the app never runs (finding 98)")
    for name, needle in STALE_LOOP_CLAIMS:
        if needle in texts[name]:
            add(documents[name], f"still claims '{needle}', and no code feeds that path "
                                 f"(finding 98)")


def check_native_assertion_count() -> None:
    """The README's native assertion count must equal the suite (finding 99).

    Two places in the README said "44 assertions" / "44 checks, 0 failures" long after the
    host suite had grown to 49 (findings 93, 96 and 97 each added assertions), so the one number
    a reader uses to judge the native layer was a stale copy of a fact the code owns. This rule
    counts the `check(...)` / `checkNear(...)` call sites in `host_tests.cpp` - excluding the
    `check` that `checkNear` makes on its own behalf - and requires both README figures to match
    it. The suite is written without conditional checks, so the count of call sites is the count
    of executed assertions; the CI run of the suite proves the runtime half.
    """
    readme = ROOT / "README.md"
    text = readme.read_text(encoding="utf-8")
    suite = NATIVE_SUITE.read_text(encoding="utf-8")
    body = re.sub(r"void checkNear\(.*?\n\}\n", "", suite, flags=re.S)
    call_sites = len(re.findall(r"\bcheck(?:Near)?\s*\(", body)) - 1  # the `void check(` line
    claims = (
        re.search(r"Native \(C\+\+\) — (\d+) assertions", text),
        re.search(r"pass — (\d+) checks, 0 failures", text),
    )
    if any(match is None for match in claims):
        add(readme, "the native assertion count has disappeared from the testing section - "
                    "it is the only figure a reader has for the C++ layer (finding 99)")
    claimed = sorted({int(match.group(1)) for match in claims if match is not None})
    if claimed and claimed != [call_sites]:
        figures = " and ".join(str(value) for value in claimed)
        add(readme, f"claims {figures} native assertions, `host_tests.cpp` has {call_sites} "
                    f"call sites - the count is a claim like any other (finding 99)")


TEST_ANNOTATION = re.compile(r"^\s*@(?:[\w.]+\.)?Test\b", re.M)
KOTLIN_SUITE_CLAIM = re.compile(
    r"pass — (\d+) tests in the app\+core\+ar-engine\+export\+native-slam suites"
)
KOTLIN_SUITE_MODULES = ("app", "core", "ar-engine", "export", "native-slam")


def check_readme_test_count() -> None:
    """The README's Kotlin test count must equal the suite (finding 104).

    The native half of the README has had this rule since finding 99; the Kotlin half has not,
    and the number has been carried forward from round to round instead. It is a claim about a
    tree anyone can count: the test sources hold 479 `@Test` methods while the README said 482,
    and no command in the repository could tell you which of the two was right. A number nobody
    can reproduce is a number that drifts, so the count of annotated test methods in every
    module with a `src/test` tree is compared with the figure the README publishes - the CI run of
    `testDebugUnitTest` proves the runtime half, this proves the arithmetic. `:native-slam` joined
    the list with finding 112: its `PoseGraphResult` parsing is JVM-testable even though the rest
    of the module is native, and a module with tests that no count covers is a module whose tests
    can be deleted silently.
    """
    readme = ROOT / "README.md"
    text = readme.read_text(encoding="utf-8")
    claim = KOTLIN_SUITE_CLAIM.search(text)
    if claim is None:
        add(readme, "the 'pass - N tests in the app+core+ar-engine+export suites' row has "
                    "disappeared - it is the only figure a reader has for the JVM suites "
                    "(finding 104)")
        return
    counted = 0
    for module in KOTLIN_SUITE_MODULES:
        test_root = ROOT / module / "src" / "test"
        if not test_root.is_dir():
            continue
        for path in test_root.rglob("*.kt"):
            counted += len(TEST_ANNOTATION.findall(path.read_text(encoding="utf-8")))
    if int(claim.group(1)) != counted:
        add(readme, f"claims {claim.group(1)} Kotlin tests, the test sources hold {counted} "
                    f"`@Test` methods - the suite's count is a claim like the native one "
                    f"(finding 104)")


CHECKLIST_ATTRIBUTIONS = re.compile(r'In-app "Licences" screen lists ([^.\n]+?)\s*$', re.M)
NOTICES_SOURCE = (
    ROOT / "core" / "src" / "main" / "kotlin" / "com" / "constructar" / "core" / "licence"
    / "ThirdPartyNotices.kt"
)
LICENSING_DOC = ROOT / "docs" / "LICENSING.md"
NOTICE_FILE = ROOT / "NOTICE"


def check_licence_notices() -> None:
    """Every component the release checklist promises to attribute must be in the app (finding 109).

    Apache-2.0 §4 and BSD-3-Clause make the notice an obligation of *distribution*, and the
    checklist has carried two items about it since the first release - while the app had no
    licences section at all, so neither item could be ticked, and `docs/LICENSING.md` said the
    attribution was "to be added before release". The names the checklist asks for are read out of
    that document and looked for in the notice list the Help screen renders, so the promise and
    the artefact cannot part company again. The other half - whether what is listed is what the
    app links, and whether every licence may ship at all - is `ThirdPartyNoticesTest` in `:core`.
    """
    checklist = (ROOT / "docs" / "RELEASE_CHECKLIST.md").read_text(encoding="utf-8")
    match = CHECKLIST_ATTRIBUTIONS.search(checklist)
    if match is None:
        add(
            ROOT / "docs" / "RELEASE_CHECKLIST.md",
            "the in-app licences item has disappeared from the checklist - it is the "
            "only place that says which components must be attributed (finding 109)",
        )
        return
    if not NOTICES_SOURCE.exists():
        add(
            NOTICES_SOURCE,
            "the third-party notice list is gone, so the app attributes nothing while it ships "
            "Apache-2.0 and BSD-3-Clause code (finding 109)",
        )
        return
    source_text = NOTICES_SOURCE.read_text(encoding="utf-8")
    source = source_text.lower()
    for name in re.split(r",|\band\b", match.group(1)):
        component = name.strip()
        if component and component.lower() not in source:
            add(
                NOTICES_SOURCE,
                f"{component} is named in docs/RELEASE_CHECKLIST.md but has no notice the app "
                f"can show - the licence requires it (finding 109)",
            )
    # The app and the repository both carry the list, by hand. Hold them together: a component the
    # APK attributes must appear in NOTICE too, and every licence text NOTICE points at must exist
    # (Apache-2.0 §4(a) asks for a copy of the licence, not only its name).
    if not NOTICE_FILE.exists():
        add(NOTICE_FILE, "the repository has no NOTICE, so a source distribution attributes nothing (finding 109)")
    else:
        notice = NOTICE_FILE.read_text(encoding="utf-8")
        for component in re.findall(r'name = "([^"]+)"', source_text):
            if component not in notice:
                add(NOTICE_FILE, f"{component} is attributed in the app but not in NOTICE - "
                                 f"both lists are edited by hand (finding 109)")
        for referenced in re.findall(r"licenses/([A-Za-z0-9.\-]+\.txt)", notice):
            if not (ROOT / "licenses" / referenced).exists():
                add(NOTICE_FILE, f"points at licenses/{referenced}, which does not exist (finding 109)")

    if "to be added before release" in LICENSING_DOC.read_text(encoding="utf-8"):
        add(
            LICENSING_DOC,
            "still says the attribution is 'to be added before release' although the app now "
            "carries it - a document may not postpone what already exists (finding 109)",
        )


SCHEMA_VERSION_DECL = re.compile(r"val\s+schemaVersion\s*:\s*Int\s*=\s*SCHEMA_VERSION")


def check_schema_version_is_read() -> None:
    """A persisted schema version that nothing compares protects nothing (finding 101).

    Every project file has carried `schemaVersion` since the first release, and no line of the app
    ever looked at it. The parser ignores unknown keys, so a room backed up by a newer ConstructAR
    was accepted while everything that version had added - a diary entry, a photo reference, a
    defect - was dropped: the operator saw a room that had "restored" with part of the job gone,
    and the file in his backup was the only complete copy left. The restore now refuses a newer
    file, and this rule keeps the comparison from disappearing again: a model that declares the
    version needs a reader that compares it against the constant, not against a literal.
    """
    declared: list[Path] = []
    readers: list[Path] = []
    for path in kotlin_sources("app", "ar-engine", "core", "export"):
        if "/src/test/" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        if SCHEMA_VERSION_DECL.search(text):
            declared.append(path)
            continue
        if "schemaVersion" in text and "SCHEMA_VERSION" in text:
            readers.append(path)
    if declared and not readers:
        add(declared[0], "the project file carries `schemaVersion` and no file compares it against "
                         "`SCHEMA_VERSION` - a backup from a newer app would be parsed with its "
                         "unknown keys dropped, which is data loss reported as a successful "
                         "restore (finding 101)")


# `kotlin.test` takes the value first, `org.junit.Assert` takes the message first. The mix-up
# compiles in neither order, and the round trip through CI to find out is six minutes.
KOTLIN_TEST_MESSAGE_FIRST = re.compile(r"assert(?:True|False)\(\s*\"(?:[^\"\\]|\\.)*\"\s*,")


def check_assert_message_order() -> None:
    """A `kotlin.test` assertion must be called value-first (findings 95, 101).

    `org.junit.Assert.assertFalse("message", condition)` is the order most Java-adjacent code
    uses; `kotlin.test.assertFalse(condition, "message")` is the other one. A file that imports
    one and is written in the style of the other does not compile - it cost a red `android-build`
    on finding 95 and another one on finding 101 - and both times the only way to find out was a
    six-minute CI round trip, because no Kotlin compiler runs on the workstation here.

    The rule fires only in files that import `kotlin.test`, and only when a string literal is
    followed by a comma, so `assertTrue("abc".length == 3)` stays legal.
    """
    for path in kotlin_sources("app", "ar-engine", "core", "export", "native-slam"):
        if "/src/test/" not in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        if "import kotlin.test." not in text:
            continue
        for match in KOTLIN_TEST_MESSAGE_FIRST.finditer(text):
            line = text[: match.start()].count("\n") + 1
            add(path, f"line {line}: a kotlin.test assertion with the message first - kotlin.test "
                      f"takes the value first (org.junit takes the message first), and the "
                      f"mix-up only shows up as a red build (finding 101)")


# Only a declaration, never a KDoc code span: `:native-slam` in a comment is not an identifier.
BACKTICKED_NAME = re.compile(r"(?:fun|class|val|var|object)\s+`([^`\n]+)`")
WINDOWS_HOSTILE = set('%/:*?"<>|\\')


def check_backticked_names() -> None:
    """A backticked function name may not carry `%`, `/`, `:`, `*`, `?`, quotes, `<>`, `|`, `\\`.

    Kotlin allows a backticked name to be almost anything, and the compiler then refuses the ones
    that cannot be written as a file name on Windows: `fun \`a dense mesh cannot print more than
    100 %\`` failed the build with "Name contains character(s) that can cause problems on
    Windows: %", which no amount of reading the diff would have predicted (finding 102). Test
    names are written as sentences here, so the trap is one keystroke away every round.
    """
    for path in kotlin_sources("app", "ar-engine", "core", "export", "native-slam"):
        text = path.read_text(encoding="utf-8")
        if "`" not in text:
            continue
        for match in BACKTICKED_NAME.finditer(text):
            bad = sorted({char for char in match.group(1) if char in WINDOWS_HOSTILE})
            if not bad:
                continue
            line = text[: match.start()].count("\n") + 1
            add(path, f"line {line}: the backticked name contains {', '.join(repr(c) for c in bad)} "
                      f"- Kotlin rejects it as a file name on Windows and the build stops "
                      f"(finding 102)")


# Fields that are written on one thread and read on another. Each entry names the file, the
# field and the two sides of the boundary; adding a line here is the cheapest possible review of a
# threading contract, and the check below fails if the declaration loses its `@Volatile`.
CROSS_THREAD_FIELDS = [
    (
        "ar-engine/src/main/kotlin/com/constructar/arengine/hit/ArInteraction.kt",
        "previousNode",
        "written in `applyPendingHit`/`undo`/`finish` on the main thread, read in `onFrameEnter`",
    ),
    (
        "ar-engine/src/main/kotlin/com/constructar/arengine/hit/ArInteraction.kt",
        "onTapResolved",
        "assigned when the session is created, invoked on the render thread",
    ),
    (
        "ar-engine/src/main/kotlin/com/constructar/arengine/hit/ArInteraction.kt",
        "latestSnapshot",
        "published by the TSDF pipeline, read by the hit cascade",
    ),
    (
        "ar-engine/src/main/kotlin/com/constructar/arengine/hit/ArInteraction.kt",
        "reticlePoint",
        "written from the render thread, read by the cascade and the HUD",
    ),
]


def check_cross_thread_fields() -> None:
    """A field the render thread and the main thread share must be `@Volatile` (finding 118).

    The Java memory model promises a reader nothing about a plain field written by another
    thread, so the reader can work with a value that was replaced long ago. In `ArInteraction`
    that reader is the per-frame hit cascade: it validates a tap against `previousNode`, and a
    stale node means the "too close to the previous node" and normal-continuity checks answer
    about a point the chain no longer ends at.
    """
    for relative, field, why in CROSS_THREAD_FIELDS:
        path = ROOT / relative
        text = path.read_text(encoding="utf-8")
        pattern = re.compile(r"^([ \t]*)(@Volatile[ \t]*\n[ \t]*)?var " + field + r"\b", re.M)
        match = pattern.search(text)
        if match is None:
            add(path, f"`{field}` is no longer declared here - update the threading contract")
        elif match.group(2) is None:
            add(path, f"`{field}` is shared across threads ({why}) and must be `@Volatile`")


def _source_text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def _first_number(pattern: str, text: str) -> float | None:
    match = re.search(pattern, text)
    if match is None:
        return None
    return float(match.group(1).replace(" ", "").replace("_", ""))


def check_performance_numbers() -> None:
    """The figures `docs/PERFORMANCE.md` derives from a knob are recomputed (finding 119).

    A tuning table is only worth reading if its numbers follow from the values next to them. Two
    did not: the block budget was quoted as "≈ 2 048 m³ of mapped volume at 2 cm", which is what
    160 000 blocks came to at an older, larger budget (500 000), and the integration stride was
    quoted as "~21 k rays" for 640×480 at a step of 3, where the loop - which steps both axes -
    makes 214 × 160 = 34 240. Both numbers are arithmetic, so both are checked here against the
    sources that own the inputs: `TsdfVolume::integrate` steps rows and columns, `kResolution` and
    `maxBlocks` live in `volume.hpp`, and the default voxel size lives in `NativeSlamTypes.kt`.
    """
    doc = _source_text("docs/PERFORMANCE.md")
    volume_hpp = _source_text("native-slam/src/main/cpp/tsdf/volume.hpp")
    types_kt = _source_text("native-slam/src/main/kotlin/com/constructar/nativebridge/NativeSlamTypes.kt")

    blocks = _first_number(r"size_t maxBlocks = (\d+);", volume_hpp)
    resolution = _first_number(r"static constexpr int kResolution = (\d+);", volume_hpp)
    voxel = _first_number(r"val voxelSizeMeters: Float = ([0-9.]+)f", types_kt)
    if blocks is None or resolution is None or voxel is None:
        add(ROOT / "scripts/verify_sources.py",
            "the TSDF constants are no longer where rule 22 reads them")
        return

    cubic_meters = blocks * resolution ** 3 * voxel ** 3
    quoted = _first_number(r"≈ (\d[\d ]*) m³ of mapped volume", doc)
    if quoted is None:
        add(ROOT / "docs/PERFORMANCE.md",
            "the block-budget row no longer states a mapped volume - rule 22 cannot check it")
    elif abs(quoted - cubic_meters) / cubic_meters > 0.05:
        add(ROOT / "docs/PERFORMANCE.md",
            f"the block budget is quoted as {quoted:.0f} m³, but {blocks:.0f} blocks of "
            f"{resolution:.0f}³ voxels at {voxel:.3f} m are {cubic_meters:.0f} m³")

    step = _first_number(r"Linear cost; (\d+) keeps 640×480", doc)
    rays = _first_number(r"keeps 640×480 at ~(\d[\d ]*) k rays", doc)
    if step is None or rays is None:
        add(ROOT / "docs/PERFORMANCE.md",
            "the pixelStep row no longer states the step and the ray count together")
    else:
        expected = math.ceil(640 / step) * math.ceil(480 / step) / 1000.0
        if abs(rays - expected) / expected > 0.10:
            add(ROOT / "docs/PERFORMANCE.md",
                f"640×480 at a step of {step:.0f} is {expected:.0f} k rays (both axes are "
                f"stepped), not the {rays:.0f} k quoted")


def check_draft_write_ordering() -> None:
    """Draft writes only inside `writeDraft`, which orders them (finding 74)."""
    for path in kotlin_sources("app"):
        if "/src/test/" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        guard_start, guard_end = _function_span(text, "fun writeDraft(")
        if guard_start < 0:
            continue
        for match in DRAFT_WRITE.finditer(text):
            if guard_start <= match.start() < guard_end:
                continue
            line = text[: match.start()].count("\n") + 1
            add(path, f"line {line}: a draft write outside `writeDraft` can complete out of order "
                      f"and resurrect a draft that was just deleted")


def _function_span(text: str, signature: str) -> tuple[int, int]:
    """Character range of the function body that starts at [signature], or (-1, -1)."""
    start = text.find(signature)
    if start < 0:
        return -1, -1
    opening = text.find("{", start)
    if opening < 0:
        return start, start
    depth = 0
    for index in range(opening, len(text)):
        character = text[index]
        if character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                return start, index
    return start, len(text)


def check_manifest_hardening() -> None:
    """Rules that keep a release build from handing its own data to anyone with a cable.

    These are cheap to check and expensive to get wrong: a debug flag that slips into a
    release, or a component exported "just for testing", is exactly the kind of hole that is
    never noticed until it is used.
    """
    manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    if "android:debuggable" in text:
        add(manifest, "android:debuggable must never be set in the manifest")
    if 'android:allowBackup="true"' in text:
        add(manifest, "allowBackup must stay false: projects are backed up by the operator, "
                      "not extracted over adb")
    # Every component must declare its exposure explicitly, and only the launcher may be
    # exported. `exported` defaults changed in API 31; relying on the default is a bug either
    # way because nobody can see the intent.
    for match in re.finditer(r"<(activity|service|receiver|provider)\b([^>]*)>", text):
        kind, attributes = match.group(1), match.group(2)
        name = re.search(r'android:name="([^"]+)"', attributes)
        label = name.group(1) if name else kind
        exported = re.search(r'android:exported="(true|false)"', attributes)
        if not exported:
            add(manifest, f"{kind} {label} does not declare android:exported")
            continue
        if exported.group(1) != "true":
            continue
        # Only the launcher entry point may be exported; its MAIN action lives in the child
        # <intent-filter>, so the element body is what has to be searched.
        body = text[match.end():match.end() + 400]
        if "android.intent.action.MAIN" not in attributes + body:
            add(manifest, f"{kind} {label} is exported without being the launcher entry point")


def strip_kotlin(text: str) -> str:
    """Removes comments and string/char literals so structural counting is meaningful.

    Order matters and both orders are wrong on their own:

      * comments first, and a literal like `"///"` becomes an unterminated string - the
        remainder of the file shifts and the gate reports an unbalanced `}` in code that
        compiles (this happened, in a test of `ExportService.slug`);
      * literals first, and an apostrophe in a comment (`// don't`) opens a character literal
        that never closes.

    So: literals first, then comments, and the comment pass is a single character scan that
    knows it is outside every literal by then. Raw strings are removed before normal ones, and
    each removal collapses to `""`, which is why the bracket count still means something.
    """
    text = re.sub(r'"""(?:.|\n)*?"""', '""', text)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
    text = re.sub(r"'(?:\\.|[^'\\\n])'", "''", text)

    out: list[str] = []
    index = 0
    length = len(text)
    while index < length:
        pair = text[index:index + 2]
        if pair == "//":
            newline = text.find("\n", index)
            index = length if newline < 0 else newline
            continue
        if pair == "/*":
            end_of_block = text.find("*/", index + 2)
            index = length if end_of_block < 0 else end_of_block + 2
            continue
        out.append(text[index])
        index += 1
    return "".join(out)


def check_kotlin_structure() -> None:
    """Catches truncated or duplicated files: unbalanced brackets never compile."""
    pairs = {"(": ")", "[": "]", "{": "}"}
    for path in kotlin_sources("core", "ar-engine", "app", "export", "native-slam"):
        body = strip_kotlin(path.read_text(encoding="utf-8"))
        stack: list[str] = []
        for index, char in enumerate(body):
            if char in pairs:
                stack.append(char)
            elif char in pairs.values():
                if not stack or pairs[stack.pop()] != char:
                    line = body[:index].count("\n") + 1
                    add(path, f"unbalanced '{char}' (line ~{line})")
                    break
        else:
            if stack:
                add(path, f"{len(stack)} unclosed '{stack[-1]}'")


def check_resource_xml() -> None:
    for path in (ROOT / "app" / "src" / "main" / "res").rglob("*.xml"):
        try:
            ElementTree.parse(path)
        except ElementTree.ParseError as error:
            add(path, f"malformed XML: {error}")
    for folder in LOCALES:
        path = RES_DIR / folder / "strings.xml"
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), start=1):
            body = re.sub(r"<[^>]+>", "", line)
            if "</string>" in line and ("&" in body or "<" in body):
                add(path, f"unescaped entity (line {number}): {line.strip()[:100]}")
            if re.search(r"(?<!\\)'", body) and body.strip():
                add(path, f"unescaped apostrophe (line {number}): {line.strip()[:100]}")


def check_licence_key() -> None:
    """Not a failure, a reminder: the tree ships a demo key and only a human can swap it.

    `LicencePublicKey.isDemoKey` is what shows the developer note on the Help screen, so the
    placeholder is deliberate and visible. But a release cut with it in place would verify
    licences that no real signer ever produced - so the release checklist asks for the swap, and
    every verify run repeats it, because the CI log is where a release actually gets cut from.
    """
    key_file = (
        ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "billing"
        / "LicencePublicKey.kt"
    )
    if not key_file.exists():
        return
    text = key_file.read_text(encoding="utf-8")

    def literal(name: str) -> str:
        """The concatenated string literal assigned to `name`, or "" when there is none."""
        match = re.search(rf"{name}\s*(?::\s*String\s*)?=\s*((?:\s*\"[^\"]*\"\s*\+?)+)", text)
        return "".join(re.findall(r'"([^"]*)"', match.group(1))) if match else ""

    shipped = literal("PUBLIC_KEY_BASE64")
    if shipped and shipped == literal("DEMO_KEY_BASE64"):
        notices.append(
            "the licence key is still DEMO_KEY_BASE64 - replace it before publishing a release"
        )


DESTRUCTIVE_BODY = re.compile(r"^(.*_)?(delete|leave)_body.*$")


def check_destructive_dialogs() -> None:
    """A confirmation that destroys something must say what (finding 125).

    The project screen's delete dialog said "All measurements in this project are deleted" - no
    room name, and no mention of the photos, the site diary, the defects, the recorded working
    hours, the customer's details or the draft of a run that was never recovered, all of which
    `ProjectRepository.delete` takes with it. Its icon sits 52 dp from the rename icon, on a phone
    held by a gloved hand, and the dialog is the last thing between a mis-tap and a lost job.

    Every string that ends up in such a confirmation is therefore required to name its subject:
    a placeholder for the thing that goes (`%1$s` - the room, the run, the measurement). A
    translation that quietly drops it turns the warning back into a question with no object.
    """
    for folder in LOCALES:
        path = RES_DIR / folder / "strings.xml"
        if not path.exists():
            continue
        strings, _ = load_locale(folder)
        for key, value in sorted(strings.items()):
            if not DESTRUCTIVE_BODY.match(key):
                continue
            if "%1$s" not in value:
                add(
                    path,
                    f"'{key}' confirms a destructive action without naming its subject - "
                    "the dialog cannot tell the operator what he is about to lose",
                )


def check_folder_writes() -> None:
    """Every write into a folder the operator picked goes through one guarded writer.

    The Storage Access Framework hands back a *file*, and a file is not a promise: the provider
    can create it and then refuse the stream, and the write itself can die halfway. Both screens
    that write into a picked folder used to spell the sequence out inline - `createFile`,
    `openOutputStream`, `write`, and an `else { file?.delete() }` that only covered the missing
    stream. A throw left the half-written file exactly where the finished one belonged and aborted
    the rest (findings 130 and 131): a truncated protocol that opens and looks complete, or a
    truncated project JSON that no restore can read while the backup folder looks whole.

    Deleting is therefore part of writing, in [ExportFiles.writeOnce] and nowhere else. A new
    `openOutputStream` in a screen is this defect returning, so it is refused here before it can be
    compiled into a release.
    """
    ui = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui"
    writer = ui / "ExportFiles.kt"
    for path in sorted(ui.glob("*.kt")):
        if path == writer:
            continue
        lines = path.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(lines, start=1):
            if ".openOutputStream(" not in line:
                continue
            # The one legal shape: the `open` argument of the guarded writer.
            if line.strip().startswith("open = {"):
                continue
            add(
                path,
                f"line {number}: writes into a picked folder without ExportFiles.writeOnce - "
                "a write that throws would leave the half-written file in place",
            )
    for name in ("ExportScreen.kt", "BackupCard.kt"):
        path = ui / name
        if path.exists() and "ExportFiles.writeOnce(" not in path.read_text(encoding="utf-8"):
            add(path, "no longer writes its files through ExportFiles.writeOnce")


def check_resume_entitlement_refresh() -> None:
    """A plan that expires has to be re-derived when the app comes back (finding 132).

    There is no server and no timer, so expiry is a comparison against the current day that
    happens whenever `EntitlementStore.refresh()` runs. The class documents the plan plainly -
    "cheap and idempotent, so the app calls it on resume: that is how a licence that expires at
    midnight stops working at midnight" - and nothing called it: `refreshEntitlements()` was
    reachable only from the paywall, so a monthly subscription and a seven-day trial both stayed
    open for as long as the process lived, which on a site phone is days.
    """
    app = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app"
    root = app / "ui" / "AppRoot.kt"
    text = root.read_text(encoding="utf-8")
    if "Lifecycle.Event.ON_RESUME" not in text or "refreshEntitlements" not in text:
        add(
            root,
            "nothing re-derives the licence when the app resumes - a plan that expired at "
            "midnight would keep working until the process is killed",
        )
    view_model = app / "ProjectViewModel.kt"
    if "fun refreshEntitlements(" not in view_model.read_text(encoding="utf-8"):
        add(view_model, "refreshEntitlements() is gone, so the resume hook has nothing to call")


def check_device_code_is_copyable() -> None:
    """A screen that prints the device code must offer a way to copy it (finding 133).

    The tag is the only string the operator has to transmit to buy a licence: twelve hex
    characters that go into a mail together with the transaction id, and the whole purchase
    depends on them arriving correctly. The paywall printed it as a headline with no copy button -
    while each wallet address three lines above had one - and the help screen printed it again
    without one, so it had to be retyped by hand from a phone screen into the same phone. A single
    wrong character mints a key for another installation, which fails on activation.

    `DeviceCodeLine` is the one place that prints it, and it carries the button.
    """
    ui = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui"
    writer = ui / "DeviceCode.kt"
    # A screen *renders* the code; passing it down as an argument is not rendering.
    renders = re.compile(r"Text\(\s*(text\s*=\s*)?deviceTag\b")
    for path in sorted(ui.rglob("*.kt")):
        if path == writer:
            continue
        text = path.read_text(encoding="utf-8")
        if not renders.search(text):
            continue
        if "DeviceCodeLine(" not in text:
            add(
                path,
                "prints the device code without DeviceCodeLine - the operator would have to retype "
                "the one string the purchase depends on",
            )


def check_counted_days_are_plurals() -> None:
    """A number of days goes through `plurals`, not through a sentence with a `%d` (finding 135).

    The plan chip on the list and the two sentences on the paywall said "Trial · %1$d days" and
    "Trial active, %1$d days left". On the last day of the trial that count is **one**, and all
    six languages then printed "1 days", "1 Tage", "1 jours", "1 giorni", "1 días", "1 dni" - in
    the sentence that tells the operator whether to buy, on the day he is deciding. Android has
    `plurals` for this and every other counted sentence in the app already used it (finding 94);
    these two were added after that rule was written.

    So the keys may not exist as plain strings in any locale, and the app must ask for the plural.
    """
    for folder in LOCALES:
        path = RES_DIR / folder / "strings.xml"
        if not path.exists():
            continue
        strings, plurals = load_locale(folder)
        for key in ("plan_trial_days", "paywall_trial_active"):
            if key in strings:
                add(path, f"'{key}' prints a count of days as a plain string - a single day reads "
                          "as a plural in every one of the six languages")
            if key not in plurals:
                add(path, f"plurals '{key}' is missing")
    for path in kotlin_sources("app"):
        text = path.read_text(encoding="utf-8")
        for key in ("plan_trial_days", "paywall_trial_active"):
            if f"R.string.{key}" in text:
                add(path, f"R.string.{key} is a count - use R.plurals.{key}")


def check_defect_toggle_is_unambiguous() -> None:
    """One button on a defect row may not carry one sentence for two opposite actions (finding 136).

    The row showed a tick in both states with the description "Mark resolved / open again". On a
    defect that was already resolved the tick says "done" and tapping it *undoes* done - silently,
    on the row the operator is most likely to tap while reading through the punch list - and the
    reopened defect then prints as outstanding on the document the customer signs. The two keys
    below are the fix; the combined one is the defect and may not come back.
    """
    labels = ("field_defect_resolve", "field_defect_reopen")
    for folder in LOCALES:
        path = RES_DIR / folder / "strings.xml"
        if not path.exists():
            continue
        strings, _ = load_locale(folder)
        if "field_defect_toggle" in strings:
            add(path, "'field_defect_toggle' is back: one sentence for a tick that closes an open "
                      "defect and reopens a resolved one")
        values = []
        for key in labels:
            if key not in strings:
                add(path, f"'{key}' is missing - one direction of the defect button has no sentence")
            else:
                values.append(strings[key])
        if len(values) == len(labels) and len(set(values)) != len(values):
            add(path, "the resolve and the reopen sentence of the defect button are identical, so "
                      "the button still does not say which way it moves the defect")
    for path in kotlin_sources("app"):
        if "R.string.field_defect_toggle" in path.read_text(encoding="utf-8"):
            add(path, "R.string.field_defect_toggle is a shape of finding 136 - ask DefectToggle.labelRes")


def check_mirrored_icons_use_automirrored() -> None:
    """A deprecated icon that got an auto-mirrored twin may not be drawn through `Icons.Default`.

    `Icons.Filled.Undo` is deprecated in favour of `Icons.AutoMirrored.Filled.Undo`, and this app
    builds with `-Werror`, so drawing the old one does not warn - it fails `:app:compileDebugKotlin`
    (round 29, finding 136's fix, CI run 41613928215). Nothing in this repository can compile
    Kotlin, so the failure was only visible 6 minutes into CI; this rule makes it a local one.
    """
    deprecated = (
        "ArrowBack", "ArrowForward", "ArrowLeft", "ArrowRight", "KeyboardArrowLeft",
        "KeyboardArrowRight", "ExitToApp", "Help", "HelpOutline", "List", "Login", "Logout",
        "Redo", "Send", "Sort", "Undo", "VolumeOff", "VolumeUp",
    )
    pattern = re.compile(r"Icons\.(?:Default|Filled)\.(" + "|".join(deprecated) + r")\b")
    for path in kotlin_sources("app", "export", "core"):
        for match in pattern.finditer(path.read_text(encoding="utf-8")):
            add(path, f"{match.group(0)} is deprecated - use Icons.AutoMirrored.Filled."
                      f"{match.group(1)}, which builds under -Werror")

def check_licence_diagnostics_reach_the_screen() -> None:
    """Every diagnosis `EntitlementStore` computes has to arrive at something the operator sees (finding 139).

    `Diagnostics.localStateTampered` was computed, documented as "for the licence screen", and read
    by nothing: no `UiState` field carried it, no screen printed it, and `refresh()` cleared it on
    its way out - so the one place the app could have explained itself (a trial that "was already
    used" without anyone having used it) knew nothing. A diagnosis nobody can read is not a
    diagnosis; it is a comment.
    """
    store = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app"
             / "billing" / "EntitlementStore.kt")
    text = store.read_text(encoding="utf-8")
    block = re.search(r"data class Diagnostics\((.*?)\n    \)", text, re.S)
    if block is None:
        add(store, "EntitlementStore.Diagnostics is gone - this rule cannot see what it explains")
        return
    fields = re.findall(r"val (\w+): Boolean =", block.group(1))
    if not fields:
        add(store, "Diagnostics has no boolean field - the rule would pass without checking anything")
        return
    view_model = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app"
                  / "ProjectViewModel.kt").read_text(encoding="utf-8")
    ui = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui"
    screens = "\n".join(path.read_text(encoding="utf-8") for path in ui.rglob("*.kt"))
    for field in fields:
        if f"val {field}: Boolean" not in view_model:
            add(store, f"Diagnostics.{field} never reaches UiState - no screen can show it")
        if field not in screens:
            add(store, f"Diagnostics.{field} is printed by no screen in app/ui")

def check_onboarding_position_is_saved_state() -> None:
    """The introduction's position must survive the screen being recreated (finding 145).

    The deck is shown exactly once in the life of the app - `onboardingDone` is persisted when the
    operator leaves it - and its position lived in a plain `remember`, so a rotation, the obvious
    thing to do while reading a slide, started it again at slide one. The step also did its own
    arithmetic (`step++`, `step < 2`) beside a helper that exists to keep it in bounds, so a fourth
    slide would have been a silent off-by-one.

    Both are pinned here: the position is saved state, and every move goes through `OnboardingStep`.
    """
    screen = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui"
              / "OnboardingScreen.kt")
    text = screen.read_text(encoding="utf-8")
    if not re.search(r"var\s+step\s+by\s+rememberSaveable", text):
        add(screen, "the introduction's position is not saved state - a rotation restarts the deck "
                    "at slide one, and the deck is shown once in the life of the app")
    if re.search(r"var\s+step\s+by\s+remember\s*\{", text):
        add(screen, "the introduction's position is a plain `remember` again (finding 145)")
    if re.search(r"step\s*\+\s*1|step\s*-\s*1|step\+\+|\+\+\s*step|step--", text):
        add(screen, "the slide arithmetic is written inline - it belongs to `OnboardingStep`, which "
                    "clamps at both ends")

def check_ar_readiness_rechecks_on_resume() -> None:
    """The AR gate must re-ask ARCore when the AR screen comes back (finding 146).

    The answer was fetched once per *camera permission* - `LaunchedEffect(hasCameraPermission)`.
    An ARCore install or update finishes in the Play Store, i.e. while this screen is merely
    stopped and the permission has not changed, so the gate kept saying "ARCore needs an update"
    over a device that no longer needed one; the only way out was to leave the screen and come
    back. Two things are pinned here: the resume observer invalidates the answer, and the effect
    that fetches it is keyed on that invalidation.
    """
    screen = ROOT / AR_OVERLAY_SCREEN
    text = screen.read_text(encoding="utf-8")
    if not re.search(r"ON_RESUME[\s\S]{0,400}?readinessRecheck", text):
        add(screen, "coming back to the AR screen does not invalidate the ARCore answer - an "
                    "install finished in the Play Store leaves the gate blocking (finding 146)")
    effects = re.findall(r"LaunchedEffect\(([^)]*)\)\s*\{([\s\S]{0,600}?)checkReadiness", text)
    if not effects:
        add(screen, "nothing fetches ARCore readiness before the surface is composed")
    elif not any("readinessRecheck" in keys for keys, _ in effects):
        add(screen, "the readiness check is keyed on the camera permission alone, so it is asked "
                    "once and never again (finding 146)")

def check_percentages_are_rounded() -> None:
    """A percent the operator reads is rounded by `PercentText`, never truncated (finding 147).

    `(ratio * 100).toInt()` is one unit low whenever the multiplication lands below the integer -
    `0.29f * 100f` is `28.999998` - and it was written in five surfaces while the app already
    carried three other rules for the same kind of number (`roundToInt` for the AR warning,
    `Math.round` in the protocol's accuracy note, `%.0f` in a formatter nobody called). The one
    that mattered: a run whose weakest point is 0.849 read 85 % on the phone and 84 % in the
    signed protocol, and that document's own sentence says anything below 85 % needs a look.

    Banned in the modules that draw or print: `:app`, `:export` and `:core`. `:ar-engine` is out
    of scope because its `* 100).toInt()` call sites are a log line and a position hash, not a
    percent (finding 147).
    """
    pattern = re.compile(r"\*\s*100(?:\.0)?f?\s*\)?\s*\.toInt\(\)")
    for module in ("app", "core", "export"):
        for path in sorted((ROOT / module / "src" / "main").rglob("*.kt")):
            text = path.read_text(encoding="utf-8")
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                add(path,
                    f"line {line}: a percentage is truncated with `.toInt()` - a percent is "
                    "rounded by `PercentText.of`, or the phone and the protocol disagree about "
                    "the same number (finding 147)")

def check_clock_follows_the_phone_setting() -> None:
    """A printed clock time follows the phone's 12/24-hour setting, never its language (finding 148).

    `DateFormat.getTimeInstance(SHORT).format(...)` looks like the polite call and takes **two**
    things from the locale: the 12/24-hour convention (so a German fitter with an English phone
    read `10:00 PM` on a protocol whose other numbers are 24-hour) and the digits (so the same
    protocol on an Arabic phone carried a time table written `٢:٠٥ م` beside lengths written
    `14.82 m`). Finding 121 pinned the date column for the same second reason; the clock column
    was the one left behind.

    The app now asks `timePattern(use24Hour = ...)`, fed by `DateFormat.is24HourFormat(context)`,
    and formats with `Locale.ROOT`. Any `getTimeInstance(...)` call that formats is a finding.
    """
    pattern = re.compile(r"getTimeInstance\s*\(")
    for path in sorted((ROOT / "app" / "src" / "main").rglob("*.kt")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(lines, start=1):
            stripped = line.strip()
            # The comment that explains why this call is gone may name it.
            if stripped.startswith("*") or stripped.startswith("//"):
                continue
            for _ in pattern.finditer(line):
                add(path, f"line {number}: a clock time is formatted straight from the device "
                          "locale - the convention belongs to the phone's setting and the digits "
                          "to no locale at all (finding 148)")

def check_room_budget_has_one_definition() -> None:
    """The plan's room budget is computed in `ProjectSlots` and nowhere else (findings 126, 149).

    Finding 126 made the home screen count the room files a build *cannot read* as rooms - a
    damaged file is still a room on the operator's phone - and fixed the button with it. The two
    places that actually create a room kept their own arithmetic over the readable list:
    `createProject`'s gate and the number of rooms a restore is granted. So a free installation
    whose only room file was damaged was told to upgrade by the button, and could still restore
    last week's backup into a second room. One definition, asked twice.
    """
    pattern = re.compile(r"maxProjects|allowsAnotherProject\s*\(")
    for path in sorted((ROOT / "app" / "src" / "main").rglob("*.kt")):
        if path.name == "ProjectSlots.kt":
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("*") or stripped.startswith("//"):
                continue
            if pattern.search(line):
                add(path, f"line {number}: the room budget is computed here instead of in "
                          "`ProjectSlots` - a count that leaves out a room file this build "
                          "cannot read is how a free installation ends up with two rooms "
                          "(findings 126, 149)")

def check_both_estimating_overrides_are_passed() -> None:
    """The app's one material-list build sets both documented estimating options (finding 151).

    `MaterialListOptions` documents a waste factor and a clip spacing; finding 113 made the builder
    *read* both, and this file's own rule pinned that. What nobody pinned is that the app passes
    them: `ProjectViewModel` passed the waste factor and forgot the spacing, so every job in the
    app was counted at the trade default (60 cm of cable, 90 cm of pipe) while the engine could do
    better - and a shop that clips a fire-alarm cable every 30 cm ordered half the clips it needs.
    Both knobs, in the one place the list is built.
    """
    view_model = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ProjectViewModel.kt"
    text = view_model.read_text(encoding="utf-8")
    builds = re.findall(r"MaterialListOptions\(([^)]*)\)", text)
    if not builds:
        add(view_model, "nothing builds a material list with any options at all")
        return
    for arguments in builds:
        for option in ("wasteFactorOverride", "clipSpacingMetersOverride"):
            if option not in arguments:
                add(view_model, f"the material list is built without `{option}`, which "
                                "`MaterialListOptions` documents and the engine reads - the app "
                                "then counts every job at the trade default (finding 151)")

def check_order_list_states_its_waste() -> None:
    """The order list may not print an order length without the waste that made it (finding 152).

    Three surfaces state the pair: the material screen ("Net" / "Waste" / order length), the
    protocol (a net column and a waste column) and the full CSV (`Net length (m)`, `Waste (%)`,
    `Order length (m)`). The order list - the one document that leaves the shop, gets mailed to the
    wholesaler and carried on site - printed the order length alone, so the only number on it that
    is not a measurement could not be checked against the site or traced back to a chosen factor.
    """
    csv_writer = (ROOT / "export" / "src" / "main" / "kotlin" / "com" / "constructar" /
                  "export" / "csv" / "MaterialCsvWriter.kt")
    text = csv_writer.read_text(encoding="utf-8")
    start = text.find("fun writeOrderList(")
    if start < 0:
        add(csv_writer, "the order list writer is gone; this rule has nothing to check")
        return
    body = text[start:text.find("\n    /**", start)]
    if "Order length (m)" not in body:
        return
    for required in ('"Net length (m)"', '"Waste (%)"', "wasteFactor"):
        if required not in body:
            add(csv_writer, "the order list prints an order length without "
                            f"`{required}` - the only figure on that sheet that is not a "
                            "measurement, unverifiable against the site (finding 152)")

def check_backup_copies_bytes() -> None:
    """The operator's backup has to be a copy, not a transcription (finding 153).

    `backupEntries` documents itself as "a byte-for-byte copy of what the app wrote ... so a
    corrupted-but-readable file is not silently fixed into something else" - and it read every file
    as text while the card wrote that text back out as UTF-8. A byte that is not valid UTF-8
    decodes to the replacement character and returns as three bytes that were never in the file,
    so the one file where a copy matters most (the damaged room the list keeps because it is the
    operator's only copy, finding 150) came back from a backup altered. Reading bytes and writing
    bytes is the whole rule; `readText`/`toByteArray` in this path is the defect.
    """
    repository = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" /
                  "data" / "ProjectRepository.kt")
    text = repository.read_text(encoding="utf-8")
    start = text.find("suspend fun backupEntries(")
    if start >= 0:
        body = text[start:text.find("\n    }", start)]
        if "readBytes()" not in body:
            add(repository, "the backup does not read the project files as bytes - a decode and a "
                            "re-encode is not a copy (finding 153)")
        if ".readText()" in body:
            add(repository, "the backup decodes project files to text, so a byte that is not "
                            "valid UTF-8 comes back as something else (finding 153)")

    card = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" /
            "BackupCard.kt")
    card_text = card.read_text(encoding="utf-8")
    if "it.write(entry.text" in card_text or "entry.text.toByteArray" in card_text:
        add(card, "the backup card writes a re-encoded string instead of the file's own bytes "
                  "(finding 153)")

def check_totals_are_sums_of_printed_rows() -> None:
    """Every length total has to be the sum of the rows the documents print (finding 154).

    The screen, the bill of materials, the order list and the protocol each print one row per
    article to the centimetre and a total under the rows. Adding the raw floats and rounding once
    at the end produced a total that the column did not add up to - three articles of 3.335 m print
    "3.34" each under a "10.01" - which is the same defect the minutes and the money were fixed
    for (findings 76, 81). The fix lives in `MaterialList`, so the rule can be local: the aggregate
    getters must go through `printedMeters`.
    """
    material_list = ROOT / "core" / "src" / "main" / "kotlin" / "com" / "constructar" / "core" / "bom" / "MaterialList.kt"
    text = material_list.read_text(encoding="utf-8")
    # Only the list's own aggregates: `MaterialLine` declares a `totalMeters` of its own, and that
    # one is the row, not the total.
    body_from = text.find("data class MaterialList(")
    if body_from < 0:
        add(material_list, "`data class MaterialList` is gone; this rule has nothing to check")
        return
    for getter in ("val totalMeters:", "val totalWithWasteMeters:", "fun metersFor(", "fun orderMetersFor("):
        start = text.find(getter, body_from)
        if start < 0:
            add(material_list, f"`{getter}` is gone; this rule has nothing to check")
            continue
        # The getter, plus the one continuation line a wrapped body may use.
        window = text[start:text.find("\n", start) + 120]
        if "printedMeters(" not in window:
            add(material_list, f"`{getter.strip()}` sums the raw floats, so the total is not the "
                               "sum of the printed rows and the paperwork does not add up "
                               "(finding 154)")

def check_csv_headers_name_their_cells() -> None:
    """A column of numbers may not sit under a blank header (finding 155).

    The bill of materials ended with a "Summary by trade" block whose header row was
    `Summary by trade;` - one named cell and one empty one - over a per-trade length. The number in
    that unnamed column was the *net* metres, in a file that elsewhere distinguishes net from order
    length and whose whole purpose is to be ordered from, so the one figure that answered "how much
    cable altogether" was also the one a buyer could not identify (and not the one he orders by).
    Every header cell in this writer has to say what its column is.
    """
    csv_writer = (ROOT / "export" / "src" / "main" / "kotlin" / "com" / "constructar" / "export" /
                  "csv" / "MaterialCsvWriter.kt")
    for number, line in enumerate(csv_writer.read_text(encoding="utf-8").splitlines(), start=1):
        if "row(" not in line and "listOf(" not in line:
            continue
        for blank in ('""', '\'\''):
            if f", {blank})" in line or f"({blank}, " in line:
                add(csv_writer, f"line {number}: a CSV cell is blank - a number under a blank "
                                "header cannot be looked up (finding 155)")

def check_screen_states_the_trade_defaults() -> None:
    """The waste control has to say what "the trade default" is before it is chosen (finding 156).

    It is three numbers - 7 % of cable, 8 % of pipe, 10 % of duct - and the chip row asked the
    operator to choose between "Default" and 5/10/15 % without ever showing them. The only place a
    default appeared was a material card, one line at a time, *after* the choice. So the screen
    builds that sentence from `PolylineMetrics.defaultWasteFactor` for the three families, and a
    literal percentage in this file would be the defect returning.
    """
    screen = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" /
              "MaterialsScreen.kt")
    text = screen.read_text(encoding="utf-8")
    calls = text.count("PolylineMetrics.defaultWasteFactor(")
    if calls < 3:
        add(screen, "the material screen states the trade waste default for "
                    f"{calls} of the three families, so the chip row asks the operator to choose "
                    "a number he cannot see (finding 156)")
    # The chip row deliberately offers 5/10/15 %, so those literals are not evidence of anything -
    # 7 % and 8 % are nobody's chip, and a screen that spells one out is printing its own number.
    if "0.07f" in text or "0.08f" in text:
        add(screen, "the screen hard-codes a waste factor instead of asking the engine for it - "
                    "the printed default and the applied one would be two numbers (finding 156)")

def check_failure_reporter_finds_the_pull_request() -> None:
    """A CI step that reports a failure has to be able to find the pull request (finding 157).

    Both workflows end with a "Report the failure on the pull request" step, written because the
    Actions log endpoint redirects to a host that is not always reachable and a red job used to
    arrive as "Run failed" with no message (finding 111). It resolved the pull request with
    `gh pr list --head "$GITHUB_REF_NAME"` - and on a `pull_request` run `GITHUB_REF_NAME` is
    `<pr>/merge`, which is not a branch, so the lookup matched nothing, the step printed one line
    into a log nobody reads and exited **0**. `native-tests` only ever posts from a pull_request
    run, so it never posted at all: every native failure still arrived mute. The number comes from
    the event payload now, the branch name is the fallback for push runs, and "no pull request
    found" is a visible warning rather than a silent success.
    """
    workflows = ROOT / ".github" / "workflows"
    for path in sorted(workflows.glob("*.yml")):
        text = path.read_text(encoding="utf-8")
        if "Report the failure on the pull request" not in text:
            continue
        if '"$GITHUB_REF_NAME"' in text:
            add(path, "the failure reporter still resolves the pull request from "
                      "`GITHUB_REF_NAME`, which is `<pr>/merge` on the runs that fail - the "
                      "comment is never posted (finding 157)")
        if "github.event.pull_request.number" not in text:
            add(path, "the failure reporter does not take the pull request number from the event, "
                      "so a `pull_request` run cannot find its own PR (finding 157)")
        if "no open PR for" in text:
            add(path, "the reporter exits successfully when it cannot find the pull request, so a "
                      "failure is still reported nowhere (finding 157)")

def check_confidence_zero_is_a_reading() -> None:
    """The HUD may not treat a legal confidence as an absent one (finding 158).

    `RunConfidence.weakPointPercent` returned `null` when the run's weakest point was exactly `0`,
    reading it as "the engine reported no number". `0` is inside the range `RouteNodeRecord`
    documents (0..1), the "no nodes yet" case is already answered by `nodeCount`, and the order
    list has always counted it (`confidence < HitConfidence.RUN_TRUSTED`): the same run was
    questioned on the paperwork and passed in silence on the phone - the disagreement the shared
    threshold exists to prevent (finding 47).
    """
    hud = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" / "ar" /
           "RunConfidence.kt")
    text = hud.read_text(encoding="utf-8")
    if "worstNodeConfidence <= 0f" in text or "worstNodeConfidence > 0f" in text:
        add(hud, "the HUD still reads a confidence of 0 as 'no reading', while the bill of "
                 "materials counts it as the weakest possible point (finding 158)")

def find_or_create_block_body(text: str) -> str:
    """The body of the block lookup, whichever name it currently carries.

    Findings 159 and 160 both grep inside `findOrCreateBlock`. Finding 181 split the lock out of
    it - the lookup is now `findOrCreateBlockLocked`, called with the shard mutex already held -
    and a rule that only knows the old name silently degrades to "`findOrCreateBlock` is gone;
    this rule has nothing to check", which reads like a finding and gets ignored. Both names are
    accepted here, and neither being present is what the callers report.
    """
    for name in ("TsdfVolume::findOrCreateBlockLocked(", "TsdfVolume::findOrCreateBlock("):
        start = text.find(name)
        if start >= 0:
            return text[start:text.find("\n}\n", start)]
    return ""


def check_block_budget_claim_is_atomic() -> None:
    """A budget spent from several threads has to be claimed atomically (finding 159).

    `TsdfVolume::findOrCreateBlock` runs on every worker thread, the 64 shards have independent
    mutexes, and the block budget is the only thing between a full scan and the phone's memory. A
    relaxed load, a comparison and a later `fetch_add` let two threads both take the last slot -
    seven blocks against a budget of six, measured with eight workers - and the native test only
    failed on machines with enough cores to open that window.
    """
    volume = (ROOT / "native-slam" / "src" / "main" / "cpp" / "tsdf" / "volume.cpp")
    text = volume.read_text(encoding="utf-8")
    body = find_or_create_block_body(text)
    if not body:
        add(volume, "`findOrCreateBlock` is gone; this rule has nothing to check")
        return
    if "maxBlocks" not in body:
        return
    if "compare_exchange_weak" not in body:
        add(volume, "the block budget is compared before the slot is claimed, so two threads can "
                    "both allocate past it - the memory guard leaks (finding 159)")

def check_block_touch_is_locked() -> None:
    """The per-frame block bookkeeping belongs inside the shard lock (finding 160).

    `integrateRows` used to touch each block one line after the lookup:

        if (block->lastFrame != frameCounter_) { block->lastFrame = frameCounter_; ++allocated; }

    That is an unsynchronised read-modify-write of a field of a block another worker thread may be
    writing at the same instant, and the two mistakes it hid were both visible in the number the
    JNI publishes: a block created during the frame was never counted (its `lastFrame` was set at
    creation, so the caller's test said no - one frame into an empty volume reported hundreds of
    blocks and zero allocated), and a block reached by two threads was counted twice. The touch now
    happens in `findOrCreateBlock`, under the shard mutex, and hands back whether it was the first
    of the frame.
    """
    volume = (ROOT / "native-slam" / "src" / "main" / "cpp" / "tsdf" / "volume.cpp")
    text = volume.read_text(encoding="utf-8")
    start = text.find("TsdfVolume::findOrCreateBlock")
    if start < 0:
        add(volume, "`findOrCreateBlock` is gone; this rule has nothing to check")
        return
    end = text.find("\n}\n", start)
    outside = text[:start] + text[end:]
    for number, line in enumerate(outside.splitlines(), start=1):
        if "lastFrame" in line:
            add(volume, f"line {number}: `lastFrame` is touched outside `findOrCreateBlock`, where "
                        "no shard lock is held - the frame's block count is a race, and a new "
                        "block is counted as nothing (finding 160)")

def check_excluded_runs_are_recorded() -> None:
    """A run left out of the bill of materials has to be counted somewhere (finding 161).

    `MaterialListOptions.includeLowConfidenceRuns = false` removes a run from `lines`, and every
    warning the app has - the screen line, the order list's note, the protocol's per-run flag -
    counts the runs that *survived*. A list built with the option therefore printed a
    complete-looking bill of materials, a total short by the metres it dropped, and "0" wherever
    it was asked how uncertain it was. The builder now adds the run to
    `excludedLowConfidenceRuns` / `excludedLowConfidenceMeters` before it `continue`s, and the
    documents can say what is missing.
    """
    source = ROOT / "core" / "src" / "main" / "kotlin" / "com" / "constructar" / "core" / "bom" / "MaterialList.kt"
    text = source.read_text(encoding="utf-8")
    if "includeLowConfidenceRuns" not in text:
        add(source, "`includeLowConfidenceRuns` is gone; this rule has nothing to check")
        return
    if "excludedLowConfidenceRuns" not in text:
        add(source, "`includeLowConfidenceRuns` drops runs without recording them, so the list, "
                    "the order note and the screen all report no uncertainty on the job that has "
                    "the most of it (finding 161)")
    if "excludedLowConfidenceMeters" not in text:
        add(source, "the excluded runs' metres are not on the list, so no document can say how "
                    "much of the job is missing from it (finding 161)")

def check_live_route_is_drawn() -> None:
    """The run being measured has to reach the renderer (finding 162).

    `RouteSession` builds the preview mesh and `RouteSceneController.syncLiveRoute` uploads it, but
    for as long as the routing state existed nothing in the app called the second one: the only
    thing on screen while measuring was the crosshair and the numbers, the conduit appeared in one
    piece when the run was finished, and the preview was rebuilt up to twelve times a second for
    nobody. A unit test of the controller cannot see a missing caller, so the wiring is a rule.
    """
    app = ROOT / "app" / "src" / "main" / "kotlin"
    controller = ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" / "arengine" / "render" / "RouteSceneController.kt"
    calls = 0
    for source in app.rglob("*.kt"):
        text = source.read_text(encoding="utf-8")
        for line in text.splitlines():
            trimmed = line.strip()
            if trimmed.startswith(("*", "//")):
                continue
            if "syncLiveRoute(" in trimmed:
                calls += 1
    if calls == 0:
        add(controller, "nothing in the app calls `syncLiveRoute`, so the run being measured is "
                       "never drawn and the preview mesh is built for nobody (finding 162)")
    text = controller.read_text(encoding="utf-8")
    start = text.find("fun syncLiveRoute(")
    if start < 0:
        add(controller, "`syncLiveRoute` is gone; this rule has nothing to check")
        return
    end = text.find("\n    }\n", start)
    body = text[start:end if end > 0 else len(text)]
    if "uploadedSignatures" in body:
        add(controller, "the live preview is deduped on the node signature, but its geometry "
                        "changes when the reticle moves without a node being added - the drawn "
                        "run trails the operator by one node (finding 162)")

def check_only_posed_frames_are_placed() -> None:
    """A frame is integrated only when it has a pose of its own (finding 163).

    `ArMeasureController.onFrame` used to call `pipeline.onFrame` first thing, before it had looked
    at `frame.camera.trackingState` at all, and `ArInteraction.onFrameEnter` answered taps with the
    same pose. ARCore's `PAUSED` (covered lens, fast motion, warm-up, backgrounded session) reports
    the pose it last had and `STOPPED` the pose it last ever had, so the scan smeared the current
    view into the old camera position and a tap landed on a wall the phone was no longer looking
    at. `TrackingMonitor.requiresRelocalisation` was written for this and read by nobody; the gate
    is `canPlaceFrame`, and a pending tap is dropped with `clearTap`.
    """
    controller = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" / "ar" / "ArMeasureController.kt"
    text = controller.read_text(encoding="utf-8")
    start = text.find("pipeline.onFrame(")
    if start < 0:
        add(controller, "`pipeline.onFrame` is gone; this rule has nothing to check")
        return
    guard = text.rfind("canPlaceFrame(", max(0, start - 400), start)
    if guard < 0:
        add(controller, "the scan is integrated before the frame's pose has been checked - a "
                        "paused or stopped tracker reports the pose it last had, so depth lands "
                        "where the camera used to be (finding 163)")
    if "clearTap(" not in text:
        add(controller, "a tap waiting for a frame is never dropped, so one made while the tracker "
                        "was lost is answered by the first frame after it comes back (finding 163)")

def check_finished_runs_release_their_anchors() -> None:
    """A finished run's anchors are released with it (finding 164).

    `RouteSession.onFrame` refines the nodes of the run **being measured** - that is the whole
    point of giving every node an ARCore anchor - and `finish()` copies those positions into the
    route. Nothing reads the anchors afterwards, but they were kept until the screen closed, so a
    session held one native anchor per node of every run measured in it, and the state published
    `anchors.size` (the session's total) next to `nodeCount` (this run's).
    """
    source = ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" / "arengine" / "route" / "RouteSession.kt"
    text = source.read_text(encoding="utf-8")
    start = text.find("fun finish(")
    if start < 0:
        add(source, "`finish` is gone; this rule has nothing to check")
        return
    end = text.find("\n    }\n", start)
    body = text[start:end if end > 0 else len(text)]
    if ".detach()" not in body:
        add(source, "a finished run keeps the anchors it was measured with - nothing reads them "
                    "again, so the session holds one native anchor per node of every run on the "
                    "screen (finding 164)")
    if "fun anchorFor(" in text:
        add(source, "`anchorFor` looks only at the run being measured, so it cannot reach a "
                    "finished run's anchors - the accessor promised a lookup that does not exist "
                    "(finding 164)")

def check_anchor_id_is_transient() -> None:
    """An anchor id is a runtime handle and must not be written to the project (finding 165).

    An ARCore anchor does not survive its session, and this app releases a run's anchors the
    moment the run is finished (finding 164), so a stored id can only ever name something that is
    gone. Every project file carried one per node anyway, and the draft path already stripped them
    by hand with the comment "a dangling id invites a later caller to look one up". The field is
    `@Transient` now, which is the same guarantee for every path.
    """
    source = ROOT / "core" / "src" / "main" / "kotlin" / "com" / "constructar" / "core" / "model" / "Route.kt"
    lines = source.read_text(encoding="utf-8").splitlines()
    index = next((i for i, line in enumerate(lines) if "val anchorId:" in line), None)
    if index is None:
        add(source, "`anchorId` is gone; this rule has nothing to check")
        return
    above = [line.strip() for line in lines[max(0, index - 6):index] if line.strip()]
    if not any(line.startswith("@Transient") for line in above):
        add(source, "`anchorId` is serialised into the project file, where it names an anchor the "
                    "app has already released - a dead handle per node of every saved run "
                    "(finding 165)")

def check_one_home_for_the_room_limit() -> None:
    """The one-room rule is answered in exactly one place (finding 166).

    The free plan allows one room, and "may another one be created" depends on what is on the
    phone: a room file this build cannot read still holds a slot (findings 126 and 149). That
    question needs the file list, so it is answered in the app's `ProjectSlots` - it used to be
    answered in `core` as well, by `Entitlements.allowsAnotherProject(currentCount: Int)`, which
    took a single number and could therefore only ever see the readable rooms. Nothing in the app
    called it; the tests did, which is how a fixed bug is kept alive in a second implementation.
    """
    second_home = []
    for source in ROOT.rglob("*.kt"):
        text = source.read_text(encoding="utf-8")
        if "fun allowsAnotherProject(" in text:
            second_home.append(source)
    for source in second_home:
        add(source, "a second implementation of the free plan's one-room rule - it takes one count "
                    "and cannot see a room file that does not parse, the exact count findings 126 "
                    "and 149 were about (finding 166)")

    app = ROOT / "app" / "src" / "main" / "kotlin"
    asks = 0
    gates = 0
    for source in app.rglob("*.kt"):
        text = source.read_text(encoding="utf-8")
        asks += text.count("ProjectSlots.allowsAnother(")
        gates += text.count("ProjectSlots.remaining(")
    if asks < 2:
        add(app, "the home screen's button and the create gate must both ask "
                 "`ProjectSlots.allowsAnother` - a gate that counts rooms by itself is how a free "
                 "install reached two room files (findings 126, 149)")
    if gates < 1:
        add(app, "the restore gate must ask `ProjectSlots.remaining` - a restore brings rooms in "
                 "bulk and is the other way past the limit (finding 149)")

def check_ply_layout_is_decided_once() -> None:
    """The PLY layout is chosen in the writer, by size (finding 167).

    `PlyWriter` documents two layouts of the same geometry, and `ExportService` called only
    `writeAscii` - so every PLY the app ever produced was the text one, including a bundle big
    enough that nobody opens it, and the binary writer existed, was tested, and shipped with the
    app as dead code. The choice is now `PlyWriter.write`, which reads the triangle count; the
    export must not go round it and pick a layout of its own.
    """
    service = ROOT / "export" / "src" / "main" / "kotlin" / "com" / "constructar" / "export" / "ExportService.kt"
    text = service.read_text(encoding="utf-8")
    if "PlyWriter.write(" not in text:
        add(service, "the export does not go through `PlyWriter.write`, so the layout is decided "
                     "somewhere else - which is how every PLY the app produced ended up as text "
                     "while the binary writer sat unused (finding 167)")
    for bypass in ("PlyWriter.writeAscii(", "PlyWriter.writeBinaryLittleEndian("):
        if bypass in text:
            add(service, f"the export calls `{bypass.rstrip('(')}` directly, so the size rule in "
                         "`PlyWriter.write` is bypassed (finding 167)")

def check_depth_arrays_go_back_to_the_pool_by_themselves() -> None:
    """A pooled depth array is returned by its last reader (finding 168).

    `DepthFrameSnapshot.depthMm` is 600 kB out of a three-buffer pool and it has two readers at
    once: the published snapshot the hit cascade and the AI-depth pass sample, and the
    integration worker. `TsdfScanPipeline` used to return it when the next frame replaced it -
    a guess about the worker, and on a hot device integration outlasts a frame, so the worker
    was pinning an array that was already back in the pool and being rewritten by the next
    `capture()`. The reference count is the only place that knows when the last reader lets go,
    so no caller may hand an array back on its own.
    """
    snapshot = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" /
                "arengine" / "depth" / "DepthFrameSnapshot.kt")
    text = snapshot.read_text(encoding="utf-8")
    for required in ("fun retain(", "fun release(", "AtomicInteger"):
        if required not in text:
            add(snapshot, f"`DepthFrameSnapshot` no longer carries `{required.rstrip('(')}`, so "
                          "nothing can tell when the last reader has let go of the pixels "
                          "(finding 168)")
    capture = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" /
               "arengine" / "scan" / "DepthCapture.kt")
    capture_text = capture.read_text(encoding="utf-8")
    if "onLastRelease = {" not in capture_text or "pool.release(target)" not in capture_text:
        add(capture, "the captured snapshot does not know its own pool, so the arrays can only "
                      "be returned by a caller guessing that it was the last one (finding 168)")

    pipeline = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" /
                "arengine" / "scan" / "TsdfScanPipeline.kt")
    pipeline_text = pipeline.read_text(encoding="utf-8")
    for bypass in ("capture.recycle(", "pool.release("):
        if bypass in pipeline_text:
            add(pipeline, f"the pipeline calls `{bypass.rstrip('(')}`, returning a depth array "
                          "while the integration worker may still be pinning it (finding 168)")
    if ".release()" not in pipeline_text:
        add(pipeline, "the pipeline never releases a snapshot, so no depth array can go back to "
                      "the pool (finding 168)")

def check_thermal_down_sampling_counts_frames() -> None:
    """The hot-device skip counts frames, it does not read the clock (finding 169).

    `TsdfScanPipeline` skipped `frame.timestamp % 2L != 0L` frames while throttled. That is the
    parity of a nanosecond counter kept by the camera HAL; a 30 fps stream quantised to
    microseconds arrives in 33 333 000 ns steps - even - so the parity of every stamp equals the
    parity of the first, and the pipeline skipped either all frames (the mesh silently stops
    updating on a hot device) or none (the thermal saving never happens). The count lives in
    `ThermalFrameGate`, which no timestamp can influence.
    """
    pipeline = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" /
                "arengine" / "scan" / "TsdfScanPipeline.kt")
    text = pipeline.read_text(encoding="utf-8")
    if "frame.timestamp %" in text:
        add(pipeline, "the thermal down-sample is decided by the parity of `frame.timestamp`, "
                      "which the camera HAL controls - an even frame period makes it constant, "
                      "so a hot device skips every frame or none (finding 169)")
    if "thermalGate.admits()" not in text:
        add(pipeline, "the pipeline no longer asks `ThermalFrameGate` which throttled frames to "
                      "integrate, so the rule lives nowhere (finding 169)")
    gate = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" / "arengine" /
            "scan" / "ThermalFrameGate.kt")
    if not gate.is_file():
        add(gate, "the frame-counting gate is missing, so nothing decides the throttled cadence "
                  "(finding 169)")

def check_the_dropped_frames_reach_a_screen() -> None:
    """The frames the integrator refused are on the screen, not only in the state (finding 170).

    `TsdfScanPipeline` counts `framesDropped` on every refused frame, and `docs/ARCHITECTURE.md`
    and `docs/PERFORMANCE.md` both promise that this is what makes "the scan looks sparse"
    distinguishable from "the device is overloaded" - but no screen read the number, so the
    promise was about a field only a debugger could see. The share is `ScanState.droppedFraction`
    and the overlay says it out loud above one offered frame in six.
    """
    state = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" / "arengine" /
             "scan" / "TsdfScanPipeline.kt")
    if "val droppedFraction" not in state.read_text(encoding="utf-8"):
        add(state, "`ScanState` no longer derives the share of refused frames, so the counters "
                   "the documents promise are visible to nothing (finding 170)")
    hint = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" / "ar" / "ScanHint.kt"
    if "BEHIND" not in hint.read_text(encoding="utf-8"):
        add(hint, "the overlay has no state for a queue that cannot keep up, so a slow scan and "
                  "a saturated one read the same (finding 170)")
    screen = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" / "ar" / "ArMeasureScreen.kt"
    screen_text = screen.read_text(encoding="utf-8")
    if "scanState.droppedFraction" not in screen_text:
        add(screen, "the AR screen never asks for the dropped share, so the operator cannot tell a "
                    "slow scan from a phone that is not keeping up (finding 170)")
    if "R.string.ar_scan_behind" not in screen_text:
        add(screen, "the AR screen has no sentence for a queue that cannot keep up (finding 170)")

def check_the_hud_state_holds_only_what_is_read() -> None:
    """`ScanState` is the HUD's contract, so every field in it is read (finding 171).

    The state carried six fields nobody could see: `integrating` (only ever assigned `false`, so
    a spinner bound to it would never appear), `vertexCount` and `blockCount` (the renderer and
    the exporters ask the mesh, and the flag that matters is `hitBlockBudget`), and `error` (an
    English sentence no screen printed and no log recorded). A field in a state object whose
    whole purpose is to be displayed, but which nothing displays, is a promise - and the
    integration copy also merged the block-budget flag as `?: false`, so a failed call cleared
    the one warning that *is* on screen (finding 97).
    """
    pipeline = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" /
                "arengine" / "scan" / "TsdfScanPipeline.kt")
    text = pipeline.read_text(encoding="utf-8")
    for gone in ("val integrating: Boolean", "val vertexCount: Int", "val blockCount: Int",
                 "val error: String?"):
        if gone in text:
            add(pipeline, f"`ScanState` carries `{gone.split(':')[0].replace('val ', '')}` again, "
                          "a state field nothing reads (finding 171)")
    if "fun afterIntegration(" not in text:
        add(pipeline, "the per-frame merge of the block-budget flag is hand-written again, which "
                      "is how a failed call cleared it (finding 171)")

def check_the_confidence_plane_is_pooled() -> None:
    """The confidence plane comes out of the pool too (finding 172).

    `DepthCapture` documents itself as the class that does not allocate in the render loop, and
    its depth plane was pooled while the confidence plane was a fresh `ByteArray` on every frame:
    19 kB, thirty times a second, 0.6 MB/s of garbage in the one thread the app cannot afford to
    stall. [DepthBufferPool] holds both planes now.
    """
    capture = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" /
               "arengine" / "scan" / "DepthCapture.kt")
    text = capture.read_text(encoding="utf-8")
    if "ByteArray(confidenceRowStride" in text:
        add(capture, "the confidence plane is allocated per frame again, in the class whose "
                     "comment promises it never allocates in the render loop (finding 172)")
    if "acquireConfidence(" not in text:
        add(capture, "the confidence plane does not come from the pool (finding 172)")
    if "releaseConfidence" not in text:
        add(capture, "the confidence plane is never returned to the pool (finding 172)")

def check_the_fusion_pass_reuses_its_planes() -> None:
    """The monocular pass copies into padded planes, not into fresh arrays (finding 173).

    The pass runs on its own worker and outlives the frame's pooled arrays, so it must copy -
    but `snapshot.depthMm.copyOf()` allocates 600 kB on the render thread every 500 ms (about
    1.2 MB/s), in a class that already reuses its ARGB buffer and its output buffer for the same
    reason. `ScratchPlanes` holds the pair, safe because `busy` keeps one pass at a time.
    """
    source = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" /
              "arengine" / "ai" / "MonocularDepthSource.kt")
    text = source.read_text(encoding="utf-8")
    for fresh in (".depthMm.copyOf()", "confidence?.copyOf()"):
        if fresh in text:
            add(source, f"`{fresh}` allocates a plane per pass on the render thread again "
                        "(finding 173)")
    for required in ("scratch.depthFrom(", "scratch.confidenceFrom("):
        if required not in text:
            add(source, f"the fusion pass no longer copies into `{required.rstrip('(')}`, so the "
                        "reused planes are gone or unused (finding 173)")

def check_the_protocol_notes_can_turn_the_page() -> None:
    """Notes carry operator text, so they cannot be drawn as one fixed line (finding 174).

    `drawNote` returned where the caller could continue, and two call sites threw that value
    away: the procurement note ("order lengths include the site waste factor", two lines in every
    translation) and the exclusion note under it printed on the same baseline, two sentences on
    top of each other. No note could turn a page at all, so the list of runs that are in the
    paperwork but not in the model - built by joining the operator's own labels - ran into the
    footer. The notes go through one local `note()` that advances the cursor and breaks pages.
    """
    writer = (ROOT / "export" / "src" / "main" / "kotlin" / "com" / "constructar" / "export" /
              "pdf" / "ProtocolPdfWriter.kt")
    text = writer.read_text(encoding="utf-8")
    if re.search(r"(?<![A-Za-z])y = drawNote\(", text):
        add(writer, "a note's returned cursor is thrown away again, so the next sentence prints "
                    "on the same baseline (finding 174)")
    if "fun note(text: String)" not in text:
        add(writer, "the flowing note is gone, so a note longer than the space left runs into "
                    "the footer (finding 174)")
    flow = (ROOT / "export" / "src" / "main" / "kotlin" / "com" / "constructar" / "export" /
            "pdf" / "PageFlow.kt")
    if not flow.is_file():
        add(flow, "the page-break arithmetic for notes is missing (finding 174)")

def check_the_appendix_prints_the_photos_the_screen_holds() -> None:
    """The protocol appendix is filled from the thumbnails in memory (finding 175).

    `docs/PERFORMANCE.md` promised that "the export re-reads the same file at 1 200 px for the
    appendix, one page at a time", and `ProjectRepository.loadPhotoAs` said the same thing. No
    code did it - the appendix printed the 640 px thumbnails the site file already held - so the
    paragraph described ~35 MB of peak bitmaps that were never allocated and ~430 ppi that was
    never printed. The design that shipped is the safe one; the documents now say it.
    """
    screen = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" / "ExportScreen.kt"
    text = screen.read_text(encoding="utf-8")
    if "buildPhotoAppendix(project, fieldPhotos" not in text:
        add(screen, "the appendix is no longer built from the decoded thumbnails, so something "
                    "else is decoding the same files again (finding 175)")
    if "loadPhotoAs(" in text:
        add(screen, "the export screen decodes a photo itself, which is the second read the "
                    "appendix never needed (finding 175)")

def check_a_failed_save_keeps_the_room() -> None:
    """The rename-over fallback moves the old file aside instead of deleting it (finding 176).

    `writeAtomically` steps aside to "delete the target, then rename" on filesystems that refuse
    to rename over an existing file. Deleting first destroys the old version before the new one
    is in place: on a volume that refuses both moves - which is the only place this branch ever
    runs - a failed save took the room with it. The old file is moved to `<name>.<serial>.bak`
    instead, put back if the second move fails, and `clearStaleTemporaries` restores it on the
    next start when the process died between the two moves.
    """
    safe_files = ROOT / "core" / "src" / "main" / "kotlin" / "com" / "constructar" / "core" / "io" / "SafeFiles.kt"
    text = safe_files.read_text(encoding="utf-8")
    if "target.delete()" in text:
        add(safe_files, "the fallback deletes the old file before the new one is in place, so a "
                        "refused rename leaves no room at all (finding 176)")
    if "fun replaceOverExisting(" not in text:
        add(safe_files, "the rename-over fallback is gone - check how a refused rename is handled "
                        "(finding 176)")
    if "BACKUP_SUFFIX" not in text:
        add(safe_files, "nothing puts back a room whose replacement died between the two moves "
                        "(finding 176)")


def check_the_ellipsis_does_not_cut_a_character() -> None:
    """The PDF text clamp backs off a whole character, never half of one (finding 177).

    `PdfText.ellipsize` cuts user text to the width of a cell, and it cut by UTF-16 unit: a room
    called "Wohnzimmer 🏗 Erdgeschoss" could be cut between the two halves of the emoji,
    which leaves a lone surrogate in the string handed to `Canvas.drawText` - the replacement
    glyph, i.e. the box `PdfText` exists to keep off the protocol. `ExportService.slug` has
    guarded a file name against exactly this since finding 122; the page did not.
    """
    pdf_text = ROOT / "export" / "src" / "main" / "kotlin" / "com" / "constructar" / "export" / "pdf" / "PdfText.kt"
    text = pdf_text.read_text(encoding="utf-8")
    if "isHighSurrogate()" not in text:
        add(pdf_text, "the clamp cuts by UTF-16 unit again, so half an emoji can reach the page "
                      "(finding 177)")


def check_the_ply_header_keeps_the_run_names() -> None:
    """The PLY header names its runs, in both layouts, in the operator's own letters (finding 178).

    `PlyWriter` encoded the header as `Charsets.US_ASCII`, which does not fail on a letter outside
    ASCII: it substitutes `?` for it. A German room was `comment mesh K?che` in the file the
    surveyor opens, and four of the app's six locales type letters that encoding cannot hold. The
    binary header carried no per-part comment at all, so the same bundle stopped naming its meshes
    once it crossed the size limit that switches the layout.
    """
    ply = ROOT / "export" / "src" / "main" / "kotlin" / "com" / "constructar" / "export" / "ply" / "PlyWriter.kt"
    text = ply.read_text(encoding="utf-8")
    if "Charsets.US_ASCII" in text:
        add(ply, "the header is encoded as US-ASCII again, which turns every letter outside it "
                 "into '?' - and the names in it are the operator's own (finding 178)")
    if text.count('"comment mesh ') < 2:
        add(ply, "only one of the two layouts names its runs, so a bundle loses its mesh names "
                 "when the size limit switches the layout (finding 178)")


def check_a_derived_warning_can_be_cleared() -> None:
    """A warning computed from the geometry is recomputed, never inherited (finding 179).

    `RouteSession.recompute` publishes a warning and reads `state.warning` back on the next call,
    so an action-raised warning is sticky by design. The bend-radius verdict is not one of those:
    it comes *out* of that function, from the preview. Feeding it back made it permanent, and
    `setSpec` - which swaps the conduit and therefore the radius the corner must meet - forgot to
    rebuild the preview at all, so the verdict kept judging the old profile too.
    """
    session = ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" / "arengine" / "route" / "RouteSession.kt"
    text = session.read_text(encoding="utf-8")
    if "state.warning.isPresent ->" in text:
        add(session, "the sticky branch takes every warning, including the one this function "
                     "derives, so a bend-radius complaint can never be cleared (finding 179)")
    elif "!= RouteWarning.BEND_RADIUS_TOO_TIGHT" not in text:
        add(session, "nothing keeps the derived bend-radius warning out of the sticky branch "
                     "(finding 179)")
    start = text.find("fun setSpec(")
    if start < 0:
        add(session, "setSpec is gone - check how the conduit is switched (finding 179)")
    else:
        body = text[start:text.find("\n    }\n", start)]
        if "previewDirty = true" not in body:
            add(session, "switching the conduit does not rebuild the preview, so the tube and its "
                         "bend verdict stay on the old profile (finding 179)")


def check_the_clip_row_states_its_defaults() -> None:
    """The material screen says what "the trade default" clip spacing is, before it is picked.

    The waste row got this sentence in finding 156; the clip row directly below it kept asking the
    operator to choose between a named default and 30/60/90/100 cm without saying that the default
    is 0.60 m of cable, 0.90 m of pipe and 0.50 m of duct - and 0.50 m is not one of the chips, so
    on a duct job the selected value appeared nowhere on the screen. The three numbers have to come
    from `PolylineMetrics.defaultClipSpacing`, never from the screen (finding 180).
    """
    screen = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" / "MaterialsScreen.kt"
    text = screen.read_text(encoding="utf-8")
    if "R.string.clips_hint" not in text:
        add(screen, "the clip row prints no hint at all (finding 180)")
    for kind in ("CABLE", "PIPE", "DUCT"):
        if f"defaultClipSpacing(ConduitKind.{kind})" not in text:
            add(screen, f"the clip hint does not state the {kind} default from the engine, so the "
                        f"row is back to a named default nobody can read (finding 180)")


def check_the_native_fusion_is_locked() -> None:
    """The voxel update happens under the shard lock that found the block.

    `findOrCreateBlock` returns a `Block*` and releases the shard mutex, so the fusion that
    followed it - read `weight`, read `sdf`, write both - ran on every worker thread at once
    against the same two arrays. Two rays through the truncation band of one voxel is the normal
    case, not an edge case, so the writes were simply lost: ThreadSanitizer reports three races on
    those three lines and the running average counts some views twice (finding 181). The fix is
    `fuseVoxel`, which does the lookup, the allocation and the read-modify-write under one lock;
    `integrateRows` must not write voxels itself again, and the carve pass must use it too. The
    real gate is `tools/verify-native.sh --tsan` in CI - this rule is the shape of it, so the
    pattern cannot come back without a compiler to prove it.
    """
    source = (ROOT / "native-slam" / "src" / "main" / "cpp" / "tsdf" / "volume.cpp").read_text(encoding="utf-8")
    marker = "TsdfVolume::Fusion TsdfVolume::fuseVoxel"
    if marker not in source:
        add(ROOT / "native-slam/src/main/cpp/tsdf/volume.cpp",
            "there is no fuseVoxel, so the fusion is back outside the lock (finding 181)")
        return
    body = source[source.index(marker):source.index("void TsdfVolume::integrateRows")]
    if "std::lock_guard<std::mutex> lock(shard.mutex)" not in body:
        add(ROOT / "native-slam/src/main/cpp/tsdf/volume.cpp",
            "fuseVoxel does not hold the shard lock (finding 181)")
    for index, field in (("weight", "block->weight[idx]"), ("sdf", "block->sdf[idx]")):
        if field not in body:
            add(ROOT / "native-slam/src/main/cpp/tsdf/volume.cpp",
                f"fuseVoxel does not touch {field}, so the fusion moved somewhere else (finding 181)")
    rows = source[source.index("void TsdfVolume::integrateRows"):source.index("IntegrationStats TsdfVolume::integrate(")]
    for field in ("block->sdf[", "block->weight[", "->sdf[idx]", "->weight[idx]"):
        if field in rows:
            add(ROOT / "native-slam/src/main/cpp/tsdf/volume.cpp",
                f"integrateRows writes a voxel itself ({field}) instead of calling fuseVoxel, "
                f"which is the unlocked read-modify-write of finding 181")
    if rows.count("fuseVoxel(") < 2:
        add(ROOT / "native-slam/src/main/cpp/tsdf/volume.cpp",
            "the integrator no longer routes both the depth pass and the carve pass through "
            "fuseVoxel (finding 181)")


def check_the_native_engine_is_freed_once() -> None:
    """The native TSDF context is freed once, by the thread that was last inside it.

    `NativeSlamEngine` wraps a raw `EngineContext*` in a `Long`. Until finding 182 that handle was
    a `val`, so `close()` left it standing: a second close passed the same pointer to `delete`
    twice (a double free), and every call after the first close handed freed memory to the JNI
    layer, where `contextFrom()` cannot tell it from a live context. The `handle == 0L` guard in
    each entry point could never fire.

    The other half is the scan pipeline. `stop()` waited for its worker with a bounded
    `withTimeoutOrNull(500)` and then called `engine?.close()` whether or not the wait had finished
    - so a mesh extraction that ran longer than half a second on a hot phone was deleted out from
    under itself, in `gatherBlockGrid`, which is the crash the KDoc above the function says the
    join exists to prevent. The worker owns the free now: it is the last thread that can be inside
    the volume, and it closes the engine when its loop ends.

    This rule refuses the three shapes that reintroduce the defect: a `val` handle, a `close()`
    that does not zero it, a native call that passes the field instead of the local copy, and a
    `stop()` that frees the engine itself after a timeout.
    """
    engine = (ROOT / "native-slam" / "src" / "main" / "kotlin" / "com" / "constructar" /
              "nativebridge" / "NativeSlamEngine.kt")
    text = engine.read_text(encoding="utf-8")
    if "private var handle: Long" not in text:
        add(engine, "the native handle is not a `var` any more, so `close()` cannot invalidate it "
                    "- a second close is a double free and every call after it a use-after-free "
                    "(finding 182)")
    close_start = text.find("override fun close()")
    if close_start < 0:
        add(engine, "`close()` has disappeared; nothing frees the native context (finding 182)")
    else:
        close_body = text[close_start:text.find("\n    }\n", close_start)]
        if "handle = 0L" not in close_body:
            add(engine, "`close()` does not zero the handle, so it is not idempotent and the "
                        "guards in the other methods never fire after it (finding 182)")
    for match in re.finditer(r"native[A-Za-z]+\(\s*handle\s*[,)]", text):
        line = text.count("\n", 0, match.start()) + 1
        add(engine, f"line {line}: a native call passes the field instead of the local copy, so a "
                    f"`close()` that lands mid-call can hand the JNI layer a freed context "
                    f"(finding 182)")

    pipeline = (ROOT / "ar-engine" / "src" / "main" / "kotlin" / "com" / "constructar" / "arengine" /
                "scan" / "TsdfScanPipeline.kt")
    pipeline_text = pipeline.read_text(encoding="utf-8")
    stop_start = pipeline_text.find("fun stop()")
    if stop_start < 0:
        add(pipeline, "`stop()` has disappeared; the native volume is never released (finding 182)")
        return
    stop_body = pipeline_text[stop_start:pipeline_text.find("\n    }\n", stop_start)]
    if "close()" in stop_body and "job == null" not in stop_body:
        add(pipeline, "`stop()` frees the engine itself, so a worker still inside the native volume "
                      "loses its context - a use-after-free after a bounded join (finding 182)")
    if "withTimeoutOrNull" in stop_body and re.search(r"withTimeoutOrNull[^\n]*\n(?:[^\n]*\n){0,4}[^\n]*close\(\)", stop_body):
        add(pipeline, "`stop()` waits with a timeout and then frees the engine, which is the "
                      "use-after-free of finding 182 - the wait must not decide when it is freed")
    start_body = pipeline_text[pipeline_text.find("fun start("):pipeline_text.find("fun onFrame(")]
    if "created.close()" not in start_body:
        add(pipeline, "the scan worker no longer closes the engine when its loop ends, so `stop()` "
                      "is the only closer again (finding 182)")


def check_the_camera_answer_survives_a_reclaim() -> None:
    """Every `TakePicture` launcher restores what it sent out from saved state (finding 186).

    The camera is a separate app; to make room for it Android may reclaim this one, and a screen
    that has just been measuring with AR is the first candidate. The field book learned this in
    finding 137 and keeps the capture as three strings in `rememberSaveable`; the project screen
    kept it as one `Uri?` in a plain `remember`, so a picture taken after a reclaim came back to a
    callback whose pending field was `null` - dropped silently, nothing copied into private
    storage, the room's documentation photo gone. The rule is stated over the launcher rather than
    over one screen, because a third screen with a camera will be written one day.
    """
    for path in kotlin_sources("app"):
        if "/src/test/" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        if "ActivityResultContracts.TakePicture()" not in text:
            continue
        if "PendingCapture.restore(" not in text:
            add(path, "a `TakePicture` result is claimed without `PendingCapture.restore`, so a "
                      "process reclaim while the camera is in front drops the picture (finding 186)")
        loose = re.search(r"var \w+ by remember \{ mutableStateOf<Uri\?>\(null\) \}", text)
        if loose is not None:
            line = text[: loose.start()].count("\n") + 1
            add(path, f"line {line}: the URI the camera is writing into is a plain `remember`, so "
                      f"it is gone after a recreation - the picture is taken and dropped "
                      f"(finding 186)")


def check_the_draft_choice_is_saved() -> None:
    """Every part of the field-book draft is `rememberSaveable` (finding 185).

    `diaryDraft`, `defectTitle` and `defectLocation` are saved state because of findings 137 and
    143, and `defectSeverity` - the chip that decides which of three colours the defect gets in the
    protocol, and in what order it is printed - was the one part of that draft held by a plain
    `remember`. A rotation, or the process Android rebuilds while the camera app is in front, put
    the typed title back with the chip silently on MEDIUM, so the next tap on "add" wrote a HIGH
    defect into the project file as a medium one: not a lost tap, a wrong value in the file.

    `pendingDelete` right above it is deliberately *not* in this list: losing a confirmation costs
    one tap and deletes nothing, which is the safe direction. The distinction is the point of the
    rule, so the list is by name and the reason is written here.
    """
    screen = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" /
              "FieldBookScreen.kt")
    text = screen.read_text(encoding="utf-8")
    for field in ("diaryDraft", "defectTitle", "defectLocation", "defectSeverity"):
        if f"var {field} by rememberSaveable(" not in text:
            if f"var {field} by remember(" in text:
                add(screen, f"`{field}` is held by `remember`, so a rotation throws this part of "
                            f"the draft away - and it is the part that is written to the project "
                            f"file (finding 185)")
            else:
                add(screen, f"`{field}` is no longer a `rememberSaveable` draft - check how the "
                            f"field book holds it now (finding 185)")


def check_the_skipped_counts_name_the_total() -> None:
    """The backup card's "N of M" lines count the job, not the copies (finding 184).

    `BackupOutcome.Saved.photos` and `.projects` are the counts that were *written* - the "N photos
    copied" plurals need exactly that - and the two skipped lines say "N of M … could not be
    copied". The project line was given `projects + projectsSkipped` from the day finding 85 added
    it; the photo line was handed `photos`, the success count, so a backup that got three of five
    photos printed "3 photos copied" and then "2 of 3 photos could not be copied" underneath it.
    Both lines now take an accessor on the data class, and this rule refuses the arithmetic being
    unrolled at the call site again: a denominator written by hand there is how the asymmetry
    survived six rounds of reading this file.
    """
    card = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "constructar" / "app" / "ui" /
            "BackupCard.kt")
    text = card.read_text(encoding="utf-8")
    for resource, accessor, written in (
        ("backup_photos_skipped", "photosTotal", "photos"),
        ("backup_projects_skipped", "projectsTotal", "projects"),
    ):
        index = text.find(f"R.string.{resource}")
        if index < 0:
            add(card, f"`{resource}` is gone - check what the skip line says now (finding 184)")
            continue
        call = text[index:index + 400]
        call = call[:call.find("),")] if ")," in call else call
        arguments = call[call.find("(") + 1:]
        if accessor not in arguments:
            add(card, f"the `{resource}` line no longer takes `{accessor}`, so its denominator is "
                      f"whatever is written here - and `{written}` is the count that was copied "
                      f"(finding 184)")
        elif f"current.{written}," in arguments.replace(" ", " "):
            add(card, f"the `{resource}` line is handed `current.{written}` again, which is the "
                      f"number of successes, not the number in the job (finding 184)")
    for accessor, field, skipped in (
        ("photosTotal", "photos", "photosSkipped"),
        ("projectsTotal", "projects", "projectsSkipped"),
    ):
        if f"val {accessor}: Int get() = {field} + {skipped}" not in text:
            add(card, f"`{accessor}` is no longer derived from `{field}` and `{skipped}`, so the "
                      f"denominator and the numbers on the card can disagree (finding 184)")


def cpp_code_only(text: str) -> str:
    """The text with `//` and `/* … */` comments removed.

    Rule 68 reads C++: the files it checks talk about the very mistakes it looks for - "there is no
    `setOptimized()` upstream" is a comment in the stub that *declares* that fact, and the first
    version of the rule reported it as the defect.
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def check_the_rtabmap_stubs_mirror_upstream() -> None:
    """The optional RTAB-Map path is compiled against headers copied from RTAB-Map, not from it.

    `native-tests` proves the `CONSTRUCTAR_WITH_RTABMAP=1` translation unit compiles - against
    `tests/rtabmap-stubs`, which until finding 183 were written from the bridge itself. That makes
    the check circular: the stubs declared `Parameters::kX` as `constexpr const char*`,
    `Rtabmap::setParameters`, `Rtabmap::setOptimized`, `SensorData(…, const Transform& odomPose)`
    and a nine-argument `CameraModel`, and the bridge used all five. Upstream has none of them -
    the keys are *functions* returning `std::string`, parameters go to `init()`, the odometry is a
    `process(data, odomPose)` argument, and the camera is built from `cv::Size` - so the documented
    `CONSTRUCTAR_WITH_RTABMAP=ON` build could not have compiled, while CI reported that it did.

    This rule pins the shapes that were wrong, on both sides: the stub must declare what upstream
    declares, and the bridge must call it the way upstream wants. It cannot prove the whole API -
    only a real checkout can - but it keeps the stub from drifting back to "whatever the bridge
    says", which is the defect this row is about.
    """
    bridge = ROOT / "native-slam" / "src" / "main" / "cpp" / "slam" / "rtabmap_bridge.cpp"
    stubs = ROOT / "native-slam" / "src" / "main" / "cpp" / "tests" / "rtabmap-stubs" / "rtabmap" / "core"
    text = cpp_code_only(bridge.read_text(encoding="utf-8"))

    for invented in ("setOptimized", "rtabmap.setParameters"):
        if invented in text:
            add(bridge, f"`{invented}` does not exist in RTAB-Map; the branch only compiles against "
                        f"a stub that declares it (finding 183)")
    if "kFeatureORB" in text:
        add(bridge, "`Feature2D::Type::kFeatureORB` is spelled `kFeatureOrb` upstream (finding 183)")
    for match in re.finditer(r"Parameters::k[A-Za-z]+\s*[,)]", text):
        line = text.count("\n", 0, match.start()) + 1
        add(bridge, f"line {line}: a parameter key is passed as a value; upstream's keys are "
                    f"functions (`kMemIncrementalMemory()`) and a bare name is a function pointer "
                    f"(finding 183)")
    if "rtabmap.process(data, odom)" not in text:
        add(bridge, "the odometry pose is not handed to `process(data, odomPose)`, which is the "
                    "only place upstream accepts it (finding 183)")

    parameters = cpp_code_only((stubs / "Parameters.h").read_text(encoding="utf-8"))
    if "constexpr const char*" in parameters:
        add(stubs / "Parameters.h", "the stub declares the keys as values again; upstream declares "
                                    "them as static functions returning std::string (finding 183)")
    if "static std::string k" not in parameters:
        add(stubs / "Parameters.h", "the stub no longer mirrors upstream's key shape (finding 183)")
    if "kFeatureORB" in parameters or "kFeatureOrb" not in parameters:
        add(stubs / "Parameters.h", "the stub's Feature2D enumerator is not upstream's "
                                    "`kFeatureOrb` (finding 183)")

    rtabmap = cpp_code_only((stubs / "Rtabmap.h").read_text(encoding="utf-8"))
    for missing in ("void init(", "globalBundleAdjustment(", "process(const SensorData& data, Transform odomPose"):
        if missing not in rtabmap:
            add(stubs / "Rtabmap.h", f"the stub no longer declares `{missing}` the way upstream does "
                                     f"(finding 183)")
    for invented in ("setParameters", "setOptimized"):
        if invented in rtabmap:
            add(stubs / "Rtabmap.h", f"the stub declares `{invented}`, which RTAB-Map does not have "
                                     f"- that is how finding 183 stayed invisible")

    sensor_data = cpp_code_only((stubs / "SensorData.h").read_text(encoding="utf-8"))
    if "odomPose" in sensor_data or "userData" not in sensor_data:
        add(stubs / "SensorData.h", "the stub puts an odometry pose in SensorData's constructor "
                                    "again; upstream's last parameter there is a user-data matrix "
                                    "(finding 183)")

    camera_model = cpp_code_only((stubs / "CameraModel.h").read_text(encoding="utf-8"))
    if "opticalRotation" not in camera_model or "cv::Size" not in camera_model:
        add(stubs / "CameraModel.h", "the stub's CameraModel no longer mirrors upstream's "
                                     "intrinsics constructor (opticalRotation + cv::Size, "
                                     "finding 183)")


MANHATTAN_HEADER = (ROOT / "native-slam" / "src" / "main" / "cpp" / "geometry" /
                    "manhattan_world.hpp")
MANHATTAN_SOURCE = (ROOT / "native-slam" / "src" / "main" / "cpp" / "geometry" /
                    "manhattan_world.cpp")


def check_the_frame_solver_is_built_everywhere() -> None:
    """One translation unit, four build lists (finding 187).

    The native layer is compiled in four places that nobody edits together: the NDK library's
    `SLAM_SOURCES`, the host script a developer runs, the `native-tests` workflow matrix and the
    host step of `android-build`. A source added to three of them compiles, tests green, and then
    fails to link in the APK - or, worse, is tested in a configuration the app never builds. So
    the file lists are compared as sets: every `.cpp` under the native tree is either in all four
    lists or in none of them.
    """
    if not MANHATTAN_SOURCE.exists():
        add(MANHATTAN_SOURCE, "the Manhattan-World solver is gone - the room's planes would be "
                              "reported at whatever angle the depth noise left them")
        return
    built = {
        "CMakeLists.txt": (ROOT / "native-slam" / "src" / "main" / "cpp" / "CMakeLists.txt"),
        "tools/verify-native.sh": ROOT / "tools" / "verify-native.sh",
        ".github/workflows/native-tests.yml": ROOT / ".github" / "workflows" / "native-tests.yml",
        ".github/workflows/android-build.yml": ROOT / ".github" / "workflows" / "android-build.yml",
    }
    for label, path in built.items():
        if "manhattan_world.cpp" not in path.read_text(encoding="utf-8"):
            add(path, f"{label} does not compile `geometry/manhattan_world.cpp`, so the solver "
                      f"would be dead code in that configuration (and the others would still "
                      f"pass)")
    suite = NATIVE_SUITE.read_text(encoding="utf-8")
    if "testManhattanFrame();" not in suite:
        add(NATIVE_SUITE, "the host suite no longer runs `testManhattanFrame`, so 41 assertions "
                          "about the solver are written and never executed")
    # The licensing contract: no Eigen, no OpenCV in the self-contained build. Eigen is LGPL-ish
    # in combination, MPI is not ours to ship, and the NDK build must stay BSD-3/MIT only.
    for path in (ROOT / "native-slam" / "src" / "main" / "cpp").rglob("*.cpp"):
        text = path.read_text(encoding="utf-8")
        if "#include <Eigen" in text:
            add(path, "Eigen in the self-contained native build - the NDK library must stay "
                      "BSD-3/MIT only (docs/LICENSING.md)")
    for path in (ROOT / "native-slam" / "src" / "main" / "cpp").rglob("*.hpp"):
        if "#include <Eigen" in path.read_text(encoding="utf-8"):
            add(path, "Eigen in the self-contained native build - the NDK library must stay "
                      "BSD-3/MIT only (docs/LICENSING.md)")


def check_the_squared_wall_keeps_its_distance() -> None:
    """A snap may not move the wall it claims to square (finding 187).

    Replacing a normal by an axis has to place the plane somewhere, and the naive choice - keep
    the foot-point distance, `offset' = offset * cos(angle)` - moves a wall that is 8 degrees off
    by `4 m * (1 - cos 8)`, which is 4 cm of a 4 m room and 8 cm of a 4 m wall's own position.
    The fit knows where its points are, so a plane that carries a centroid is squared *through*
    that centroid (`a . c`) and the fallback, used only when no centroid was supplied, says so in
    its own expression. Both branches are pinned because swapping them is invisible: the room
    still looks square, and every length in the report is quietly short.
    """
    if not MANHATTAN_SOURCE.exists():
        return  # the missing file is already a finding above
    text = MANHATTAN_SOURCE.read_text(encoding="utf-8")
    if "out.normal.dot(plane.centroid)" not in text:
        add(MANHATTAN_SOURCE, "a plane with a centroid is no longer squared through that point, "
                              "so an 8 degree wall moves by its own cosine - centimetres that no "
                              "screen would show")
    if "unitOffset * aligned" not in text:
        add(MANHATTAN_SOURCE, "the centroid-free fallback is gone, so a plane without a centroid "
                              "would be snapped by some other rule than the foot-point rotation "
                              "the header documents")
    header = MANHATTAN_HEADER.read_text(encoding="utf-8")
    if "0.94f" not in header:
        add(MANHATTAN_HEADER, "the 20 degree default tolerance is not stated where the option "
                              "lives - a reader would have to infer it from the numbers in the "
                              "tests")
    if "if (!frame.valid" not in text:
        add(MANHATTAN_SOURCE, "`snapPlanes` no longer refuses an invalid frame, so a set of "
                              "parallel walls would still have its planes rotated onto axes the "
                              "solver explicitly said it had not determined")


def check_the_calibration_has_one_home() -> None:
    """The card, the window and the anchor formula live in one file (calibration, tier 3).

    The reference calibration turns "the operator measured a known object" into the factor every
    measured metre is multiplied by. Three numbers decide it - the ISO/IEC 7810 ID-1 edges and the
    acceptance window - and all three are physical facts, not preferences: a second copy of
    `0.0856` in a screen, or a second `1.15` in a dialog, is a second definition of a centimetre,
    and the protocol would then print a factor that a different part of the app disagrees with.

    The anchor rule is checked as a shape too, because getting it wrong is silent: scaling a point
    about the world origin instead of about the reference moves a node 20 m away by 20 m x (S - 1),
    which is centimetres nobody asked for and no test would notice on a 2 m room.
    """
    calibration = (ROOT / "core" / "src" / "main" / "kotlin" / "com" / "constructar" / "core" /
                   "calib" / "ReferenceCalibration.kt")
    if not calibration.exists():
        add(calibration, "the calibration arithmetic is gone - every screen would have to invent "
                         "its own factor")
        return
    text = calibration.read_text(encoding="utf-8")
    for needle, why in (
        ("CREDIT_CARD_LONG(0.0856)", "the ISO/IEC 7810 ID-1 long edge (85.60 mm)"),
        ("CREDIT_CARD_SHORT(0.05398)", "the ISO/IEC 7810 ID-1 short edge (53.98 mm)"),
        ("MIN_SCALE: Double = 0.85", "the lower edge of the acceptance window"),
        ("MAX_SCALE: Double = 1.15", "the upper edge of the acceptance window"),
    ):
        if needle not in text:
            add(calibration, f"{why} is not stated here - the reference would be defined somewhere "
                             f"a reader does not look")
    sweep = (ROOT / "core" / "src" / "main" / "kotlin" / "com" / "constructar" / "core" /
             "calib" / "CalibrationSweep.kt")
    if not sweep.exists():
        add(sweep, "the two-tap sweep is gone - the screen would have to keep the first point in a "
                    "bare nullable again")
    elif sweep.read_text(encoding="utf-8").count("override fun tap(point: Vec3): CalibrationSweep = this") < 1:
        add(sweep, "a finished sweep no longer ignores taps, so a tap while the operator reads the "
                    "factor recomputes it from the last two taps")
    if "reference + (point - reference) * factor" not in text:
        add(calibration, "the correction no longer scales about the reference point: `P' = P_ref + "
                         "S * (P - P_ref)` is the only form that leaves the measured object where "
                         "it was and moves what was measured relative to it")
    project_model = (ROOT / "core" / "src" / "main" / "kotlin" / "com" / "constructar" / "core" /
                     "model" / "Project.kt")
    model_text = project_model.read_text(encoding="utf-8")
    if "get() = (routes.sumOf { it.polylineLengthMeters.toDouble() } * calibrationScale).toFloat()" not in model_text:
        add(project_model, "`totalLengthMeters` no longer applies `calibrationScale`, so the room "
                           "screen and the protocol would report uncorrected metres")
    if "fun calibratedMeters(" not in model_text:
        add(project_model, "`calibratedMeters` is gone - a caller would multiply a route length by "
                           "the factor itself, which is the second definition this rule exists for")
    bom = (ROOT / "core" / "src" / "main" / "kotlin" / "com" / "constructar" / "core" / "bom" /
           "MaterialList.kt")
    if "project.calibrationScale" not in bom.read_text(encoding="utf-8"):
        add(bom, "the bill of materials ignores the project's calibration, so the order sheet "
                 "would disagree with the room screen")
    for path in kotlin_sources("core") + kotlin_sources("ar-engine") + kotlin_sources("app"):
        source = path.read_text(encoding="utf-8")
        if "calibrationScale" in source and path.name in ("Route.kt", "RouteGeometry.kt",
                                                          "TubeMeshGenerator.kt",
                                                          "DuctMeshGenerator.kt"):
            add(path, "the calibration factor has reached the geometry - it must only ever scale "
                      "reported metres, or a better calibration could not correct the project")
    for path in kotlin_sources("app") + kotlin_sources("ar-engine") + kotlin_sources("export"):
        source = path.read_text(encoding="utf-8")
        for literal in ("0.0856", "0.05398"):
            if literal in source:
                add(path, f"`{literal}` is a second copy of the credit card's edge; it belongs in "
                          f"`ReferenceCalibration` so screen and export cannot disagree")
    for folder in LOCALES:
        path = RES_DIR / folder / "strings.xml"
        if not path.exists():
            continue
        for key in ("calib_title", "calib_credit_card", "calib_zollstock", "calib_success_toast",
                    "calib_rejected_toast"):
            if f'name="{key}"' not in path.read_text(encoding="utf-8"):
                add(path, f"'{key}' is missing - the calibration dialog of one language would "
                          f"fall back to English mid-sentence")


def main() -> int:
    files = tracked_sources()
    check_headers(files)
    check_reflection()
    check_app_hardcoded_text()
    strings, plurals = check_locales()
    check_references(strings, plurals)
    check_offline_guarantees()
    check_cached_clock()
    check_ar_alpha_literals()
    check_discarded_saves()
    check_raw_exception_messages()
    check_draft_write_ordering()
    check_cross_thread_fields()
    check_performance_numbers()
    check_destructive_dialogs()
    check_folder_writes()
    check_resume_entitlement_refresh()
    check_device_code_is_copyable()
    check_counted_days_are_plurals()
    check_defect_toggle_is_unambiguous()
    check_mirrored_icons_use_automirrored()
    check_licence_diagnostics_reach_the_screen()
    check_onboarding_position_is_saved_state()
    check_ar_readiness_rechecks_on_resume()
    check_percentages_are_rounded()
    check_clock_follows_the_phone_setting()
    check_room_budget_has_one_definition()
    check_both_estimating_overrides_are_passed()
    check_order_list_states_its_waste()
    check_backup_copies_bytes()
    check_the_skipped_counts_name_the_total()
    check_the_draft_choice_is_saved()
    check_the_camera_answer_survives_a_reclaim()
    check_the_calibration_has_one_home()
    check_the_frame_solver_is_built_everywhere()
    check_the_squared_wall_keeps_its_distance()
    check_totals_are_sums_of_printed_rows()
    check_csv_headers_name_their_cells()
    check_screen_states_the_trade_defaults()
    check_failure_reporter_finds_the_pull_request()
    check_confidence_zero_is_a_reading()
    check_block_budget_claim_is_atomic()
    check_block_touch_is_locked()
    check_excluded_runs_are_recorded()
    check_live_route_is_drawn()
    check_only_posed_frames_are_placed()
    check_finished_runs_release_their_anchors()
    check_anchor_id_is_transient()
    check_one_home_for_the_room_limit()
    check_ply_layout_is_decided_once()
    check_depth_arrays_go_back_to_the_pool_by_themselves()
    check_thermal_down_sampling_counts_frames()
    check_the_dropped_frames_reach_a_screen()
    check_the_hud_state_holds_only_what_is_read()
    check_the_confidence_plane_is_pooled()
    check_the_fusion_pass_reuses_its_planes()
    check_the_protocol_notes_can_turn_the_page()
    check_the_appendix_prints_the_photos_the_screen_holds()
    check_a_failed_save_keeps_the_room()
    check_the_ellipsis_does_not_cut_a_character()
    check_the_ply_header_keeps_the_run_names()
    check_a_derived_warning_can_be_cleared()
    check_the_clip_row_states_its_defaults()
    check_the_native_fusion_is_locked()
    check_the_native_engine_is_freed_once()
    check_the_rtabmap_stubs_mirror_upstream()
    check_signature_tap()
    check_typed_fields_are_saved()
    check_pdf_accuracy_note()
    check_loop_closure_claim()
    check_native_assertion_count()
    check_readme_test_count()
    check_licence_notices()
    check_schema_version_is_read()
    check_assert_message_order()
    check_backticked_names()
    check_money_fields_are_not_rekeyed()
    check_manifest_hardening()
    check_kotlin_structure()
    check_resource_xml()
    check_licence_key()

    if findings:
        print(f"verify_sources: {len(findings)} finding(s)\n")
        for finding in findings:
            print("  -", finding)
        return 1

    for notice in notices:
        print(f"verify_sources: notice - {notice}")

    if VERBOSE:
        print(f"verify_sources: {len(files)} files checked")
        print(f"verify_sources: {len(strings)} string keys x {len(LOCALES)} locales")
    print("verify_sources: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
