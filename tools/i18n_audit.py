#!/usr/bin/env python3
# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
"""
Do the five translations actually translate anything?

`scripts/verify_sources.py` proves the six locale files have the same *keys* and the same
placeholders. It cannot prove they have different *words*: a file that was copied from English
and never touched passes every structural check, and the user sees the English string on a
German phone. That is the defect this audit hunts.

The rule is deliberately narrow, because the obvious version of it produces nothing but noise:

    a value in a translated locale that is character-for-character the English value is a
    finding, unless the *word itself* is correct in that language.

"Export" is German, "Surface" and "Type" and "Note" are French, "Manual" and "Cable" are
Spanish, "FAQ" is all four, "Offline" is three of them. Those are listed once, per locale, in
`REVIEWED_SAME_AS_ENGLISH` below with the reason in the comment — so adding a new allowlist
entry is a decision somebody made, not a silencer. Everything else must differ.

Usage:  python3 tools/i18n_audit.py [--verbose]
Exit code 0 means "every string that should be translated is".
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
VERBOSE = "--verbose" in sys.argv

LOCALES = {"de": "values-de", "es": "values-es", "fr": "values-fr", "it": "values-it", "pl": "values-pl"}

# Three or more letters that are not part of a format placeholder. A string made only of
# `%1$.2f m` carries no language at all and is not compared.
WORD = re.compile(r"[A-Za-zÀ-ÿ]{3,}")
PLACEHOLDER = re.compile(r"%\d+\$[sdf]|%[sdf]|%%")

findings: list[str] = []


def add(where: str, message: str) -> None:
    findings.append(f"{where}: {message}")


def has_words(value: str) -> bool:
    return bool(WORD.search(PLACEHOLDER.sub("", value)))


# ---------------------------------------------------------------------------------------------
# Reviewed on 2026-11-01: each of these matches English because it is *right* in that language.
# A key that is not in this list and still matches English is either untranslated or belongs
# here with a reason.
# ---------------------------------------------------------------------------------------------
REVIEWED_SAME_AS_ENGLISH: dict[str, set[str]] = {
    # "Export", "Material", "Status", "Offline", "Scan", "Version", "Pro" are German words too.
    "de": {
        "app_name", "ar_offline_badge", "developer_name", "export_glb", "export_title",
        "help_version", "home_export", "paywall_title", "pdf_column_material",
        "pdf_column_status", "pdf_label_material", "plan_pro", "ready_scan",
    },
    # "Manual", "Cable", "Total", "h", "min" are Spanish words too.
    "es": {
        "app_name", "developer_name", "export_glb", "field_time_total", "hours_minutes",
        "minutes_only", "paywall_title", "pdf_column_material", "pdf_label_material",
        "plan_pro", "routing_cable", "tier_manual",
    },
    # "Surface", "Type", "Note", "Date", "Page", "Photo", "net", "Important", "Material",
    # "Version", "Scan", "FAQ" are French words too.
    "fr": {
        "app_name", "developer_name", "export_glb", "field_time_total", "help_licence_title",
        "help_version", "hours_minutes", "materials_net", "minutes_only", "paywall_title",
        "pdf_column_date", "pdf_column_net_meters", "pdf_column_note", "pdf_column_type",
        "pdf_page_label", "plan_pro", "ready_photo", "ready_scan", "severity_medium",
        "surface_unknown",
        # "point" is spelled the same in the singular and the plural in French.
        "ar_points[one]", "ar_points[other]",
    },
    # Italian writes "h" and "min", and "Offline" and "Scan" are borrowed as-is.
    "it": {
        "app_name", "ar_offline_badge", "developer_name", "export_glb", "hours_minutes",
        "minutes_only", "paywall_title", "plan_pro",
    },
    # Polish writes "min" and borrows "Offline" and "Status".
    "pl": {
        "app_name", "ar_offline_badge", "developer_name", "export_glb", "minutes_only",
        "paywall_title", "pdf_column_status", "plan_pro",
    },
}

# The site uses its own key set; same rule, same reasoning.
REVIEWED_SITE: dict[str, set[str]] = {
    "de": {"acc_situation", "cmp_col_us", "nav_faq", "nav_repo", "stat4l"},
    "es": {"cmp_col_us", "mock_cable", "nav_repo", "stat2v", "stat4l"},
    "fr": {"acc_situation", "cmp_col_us", "foot_contact", "nav_faq", "nav_repo", "stat4l"},
    "it": {"cmp_col_us", "nav_faq", "nav_repo", "stat4l"},
    "pl": {"cmp_col_us", "nav_repo", "stat4l"},
}


def load(folder: str) -> dict[str, str]:
    values: dict[str, str] = {}
    root = ElementTree.parse(RES / folder / "strings.xml").getroot()
    for node in root:
        if node.tag == "string":
            values[node.attrib["name"]] = node.text or ""
        elif node.tag == "plurals":
            for item in node:
                values[f"{node.attrib['name']}[{item.attrib['quantity']}]"] = item.text or ""
    return values


def check_app() -> None:
    english = load("values")
    for lang, folder in LOCALES.items():
        translated = load(folder)
        reviewed = REVIEWED_SAME_AS_ENGLISH[lang]
        for key, value in sorted(translated.items()):
            base = english.get(key)
            if base is None or value.strip() != base.strip():
                continue
            if not has_words(value) or key in reviewed:
                continue
            add(f"values-{lang}/strings.xml", f"`{key}` is still the English text ({value!r})")
        if VERBOSE:
            print(f"  app {lang}: {len(translated)} strings, {len(reviewed)} reviewed as shared")


def check_site() -> None:
    sys.path.insert(0, str(ROOT / "tools"))
    import build_site  # noqa: E402  (imported here so `--help` style runs stay cheap)

    english = build_site.CONTENT["en"]
    for lang in LOCALES:
        content = build_site.CONTENT[lang]
        reviewed = REVIEWED_SITE[lang]
        for key, value in sorted(content.items()):
            base = english.get(key)
            if base is None or value.strip() != base.strip():
                continue
            if not has_words(value) or key in reviewed:
                continue
            add(f"tools/site_translations.py [{lang}]", f"`{key}` is still the English text ({value!r})")
        if VERBOSE:
            print(f"  site {lang}: {len(content)} keys, {len(reviewed)} reviewed as shared")


def check_no_language_was_forgotten() -> None:
    """A locale whose file is a copy of English would pass every other check silently."""
    english = load("values")
    for lang, folder in LOCALES.items():
        translated = load(folder)
        shared = sum(
            1
            for key, value in translated.items()
            if has_words(value) and english.get(key, "").strip() == value.strip()
        )
        share = shared / max(1, len(translated))
        if share > 0.35:
            add(f"values-{lang}/strings.xml", f"{share:.0%} of the strings are identical to English")


def main() -> int:
    check_app()
    check_site()
    check_no_language_was_forgotten()

    if findings:
        print(f"i18n_audit: {len(findings)} finding(s)\n")
        for finding in findings:
            print(f"  - {finding}")
        return 1
    print("i18n_audit: OK - six app locales and six site locales, nothing left in English")
    return 0


if __name__ == "__main__":
    sys.exit(main())
