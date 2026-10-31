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

set -uo pipefail

LOG="${1:-/tmp/step.log}"
TITLE="${2:-build failed}"
CODE="${3:-1}"

if [ ! -f "$LOG" ]; then
  echo "::error title=${TITLE}::no log at ${LOG}"
  exit "${CODE}"
fi

extract() {
  if grep -qE '^e: ' "$LOG"; then
    grep -E '^(e|w): ' "$LOG" | head -n 40
  fi
  if grep -q '^FAILURE:' "$LOG"; then
    # The summary only: "What went wrong" + "Where", cut off before the Java stack trace.
    sed -n '/^FAILURE:/,$p' "$LOG" | sed -n '1,/^\* Exception is:/p' | head -n 60
  fi
  if grep -qiE '^.*(error|exception|what went wrong).*$' "$LOG"; then
    grep -iE '(^error|error:|exception|what went wrong)' "$LOG" | head -n 15
  fi
  tail -n 25 "$LOG"
}

MSG=$(extract \
  | awk '!seen[$0]++' \
  | head -n 55 \
  | sed -e 's/%/%25/g' -e 's/\r//g' -e 's/\t/  /g' \
  | awk '{print $0 "%0A"}')

echo "::error title=${TITLE}::${MSG}"

# The whole log still goes to stdout for a normal (non-restricted) job view.
tail -n 200 "$LOG"
exit "${CODE}"
