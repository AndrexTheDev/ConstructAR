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

Exit code 0 means "no finding". It does **not** mean "compiles" — only Gradle can say that.

Usage:  python3 scripts/verify_sources.py [--verbose]
"""

from __future__ import annotations

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
