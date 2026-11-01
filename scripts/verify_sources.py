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


def check_headers(files: list[Path]) -> None:
    for path in files:
        if path.name in {"LICENSE"}:
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
