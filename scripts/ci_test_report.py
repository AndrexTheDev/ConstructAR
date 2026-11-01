#!/usr/bin/env python3
# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
"""
Turn JUnit XML into a check-run annotation that names the failing test.

A Gradle failure says *that* `:core:test` failed and points at an HTML report inside the runner
— which is precisely the artefact a phone, a restricted network or the check-runs API cannot
reach. The XML that report is built from is on disk, so the assertion message is extracted here
and pushed through the annotation channel instead, in the same shape the build log uses.

Usage:  python3 scripts/ci_test_report.py [--max 12]
"""

from __future__ import annotations

import glob
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIMIT = 12

if "--max" in sys.argv:
    LIMIT = int(sys.argv[sys.argv.index("--max") + 1])

patterns = [
    "*/build/test-results/*/*.xml",
    "*/build/test-results/*/*/*.xml",
]

failures: list[tuple[str, str, str]] = []
total = 0

for pattern in patterns:
    for name in glob.glob(str(ROOT / pattern)):
        path = Path(name)
        try:
            tree = ElementTree.parse(path)
        except ElementTree.ParseError:
            continue
        for case in tree.getroot().iter("testcase"):
            total += 1
            for tag in ("failure", "error"):
                problem = case.find(tag)
                if problem is None:
                    continue
                message = (problem.get("message") or problem.text or "").strip()
                # The first three lines are the assertion and the two stack frames that matter.
                lines = [line.strip() for line in message.splitlines() if line.strip()][:3]
                failures.append((case.get("classname", "?"), case.get("name", "?"), " | ".join(lines)))
                break


def emit(title: str, message: str) -> None:
    encoded = message.replace("%", "%25").replace("\n", "%0A")
    print(f"::error title={title}::{encoded[:20000]}")


if failures:
    summary = "; ".join(
        f"{classname.split('.')[-1]}.{name}: {message[:160]}" for classname, name, message in failures
    )
    emit(f"{len(failures)} failing test(s) of {total}", summary)
    for classname, name, message in failures[:LIMIT]:
        emit(f"FAILED {classname.split('.')[-1]}.{name}", message)
    print(f"ci_test_report: {len(failures)} failure(s) out of {total} test(s)")
    for classname, name, message in failures[:LIMIT]:
        print(f"  - {classname}#{name}: {message}")
    sys.exit(0)  # reporting never masks the build's own exit code
print(f"ci_test_report: {total} test(s), no failures in the XML on disk")
