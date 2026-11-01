#!/usr/bin/env bash
# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
#
# Turns a captured build log into a GitHub check-run annotation.
#
# Why this exists: a failed Gradle step is otherwise only visible as "exit code 1" in the
# check-runs API when raw job logs are not reachable (restricted networks, cached proxies,
# audit-only access). The check-runs API *is* reachable, so the interesting part of the log is
# pushed through it as an ::error annotation.
#
# Priority order of what gets reported:
#   1. Kotlin/native diagnostics     (lines starting with "e: " or "e: file:")
#   2. Gradle's "FAILURE: / What went wrong" block
#   3. the last lines of the log
#
# Usage: ci_annotate.sh <log-file> <title> <exit-code>
#
# Emits three annotations (diagnostics, what-went-wrong, tail) instead of one blob: GitHub
# truncates a single annotation at 64 KB, and the interesting `e: file:...: error:` line is
# almost never in the last 25 lines of a Gradle log.

set -uo pipefail

LOG="${1:-/tmp/step.log}"
TITLE="${2:-build failed}"
CODE="${3:-1}"

if [ ! -f "$LOG" ]; then
  echo "::error title=${TITLE}::no log at ${LOG}"
  exit "${CODE}"
fi

# Gradle colours its output; every anchor (`^e: `, `^FAILURE:`) misses when an escape sequence
# sits in front of the line. Stripping them first is what makes the extraction reliable.
ANSI='s/\x1b\[[0-9;]*[A-Za-z]//g'
CLEAN=/tmp/step.clean.log
# A carriage return is a *line separator* here, not noise: Gradle's rich console and any
# progress output write over the same line, and deleting the \r glues a whole failed task into
# one unreadable line - which is exactly how a real failure once arrived as a 61-character
# annotation containing only a deprecation warning.
sed -e 's/\r\n/\n/g' -e 's/\r/\n/g' -e "$ANSI" "$LOG" > "$CLEAN" || cp "$LOG" "$CLEAN"
# Progress lines repeat: keep the last of each identical prefix.
awk '!(/^> / && seen[$0]++)' "$CLEAN" > "$CLEAN.2" && mv "$CLEAN.2" "$CLEAN"

emit() {
  local title="$1" file="$2"
  [ -s "$file" ] || return 0
  local msg
  msg=$(awk '!seen[$0]++' "$file" | head -n 40 \
    | sed -e 's/%/%25/g' -e 's/\t/  /g' | awk '{print $0 "%0A"}')
  echo "::error title=${title}::${msg}"
}

# 1. Kotlin diagnostics - the only lines that name a file, a line and a reason.
grep -E '(^|: )(e|w): (file:|⚠)' "$CLEAN" | head -n 40 > /tmp/ann_kotlin.txt
grep -E '^e: ' "$CLEAN" | head -n 40 >> /tmp/ann_kotlin.txt

# 2. What went wrong: the FAILURE block and the failed task header.
sed -n '/^FAILURE:/,/^\* Exception is:/p' "$CLEAN" | head -n 40 > /tmp/ann_failure.txt
grep -E "^> Task .* FAILED" "$CLEAN" | head -n 20 >> /tmp/ann_failure.txt
grep -E '(^|[[:space:]])(error:|Caused by:)' "$CLEAN" | head -n 20 >> /tmp/ann_failure.txt

# 3. The tail, always - it is the only thing left when nothing above matched.
tail -n 40 "$CLEAN" > /tmp/ann_tail.txt

emit "${TITLE} - diagnostics" /tmp/ann_kotlin.txt
emit "${TITLE} - what went wrong" /tmp/ann_failure.txt
emit "${TITLE} - log tail" /tmp/ann_tail.txt

{
  echo "## ${TITLE} (exit ${CODE})"
  echo ""
  echo '```'
  cat /tmp/ann_kotlin.txt /tmp/ann_failure.txt /tmp/ann_tail.txt | awk '!seen[$0]++' | head -n 150
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

# The whole log still goes to stdout for a normal (non-restricted) job view.
tail -n 200 "$LOG"
exit "${CODE}"
