#!/usr/bin/env bash
# pr-find-lines-batch.sh — resolve diff line numbers for a batch of findings
#
# Usage:
#   bash .agents/skills/pr-review/pr-find-lines-batch.sh <PR> [input-file]
#
# Input JSON file  (/tmp/findings-<PR>.json by default):
#   [
#     { "id": 1, "file": "Foo.java",  "pattern": "methodName|otherMethod" },
#     { "id": 2, "file": "pom.xml",   "pattern": "jacoco\\.halt" },
#     { "id": 3, "file": "Bar*.java", "pattern": "someText" }
#   ]
#
# Output (one line per match):
#   [1] L42  [+] void methodName() {
#   [2] L92  [+]         <jacoco.halt>true</jacoco.halt>
#   [3] WARNING: no match for id=3 (Bar*.java / someText)
#
# Requires: jq, bash 4+

set -euo pipefail

PR="${1:-}"
INPUT_FILE="${2:-/tmp/findings-${PR}.json}"
DIFF_FILE="/tmp/pr${PR}.diff"

if [[ -z "$PR" ]]; then
    echo "Usage: $0 <PR> [input-file]" >&2
    exit 1
fi

if [[ ! -f "$DIFF_FILE" ]]; then
    echo "ERROR: diff not found at $DIFF_FILE. Run pr-context.sh $PR first." >&2
    exit 1
fi

if [[ ! -f "$INPUT_FILE" ]]; then
    echo "ERROR: findings file not found: $INPUT_FILE" >&2
    exit 1
fi

# Validate jq availability
if ! command -v jq &>/dev/null; then
    echo "ERROR: jq is required but not installed." >&2
    exit 1
fi

# ── Parse diff into a TSV: file<TAB>line<TAB>marker<TAB>text ─────────────────
PARSED_DIFF=$(python3 - "$DIFF_FILE" <<'PYEOF'
import sys, re

diff_path = sys.argv[1]
current_file = None
line_counter = 0

with open(diff_path, encoding='utf-8', errors='replace') as fh:
    for raw in fh:
        raw = raw.rstrip('\n')
        m = re.match(r'^\+\+\+ b/(.+)$', raw)
        if m:
            current_file = m.group(1)
            line_counter = 0
            continue
        m = re.match(r'^@@ -\d+(?:,\d+)? \+(\d+)', raw)
        if m:
            line_counter = int(m.group(1)) - 1
            continue
        if re.match(r'^(---|diff |index |new file|deleted file|Binary|@@)', raw):
            continue
        if current_file is None or not raw:
            continue
        marker = raw[0]
        text   = raw[1:]
        if marker == '+':
            line_counter += 1
            print(f"{current_file}\t{line_counter}\t+\t{text}")
        elif marker == ' ':
            line_counter += 1
            print(f"{current_file}\t{line_counter}\t \t{text}")
        # '-' lines: skip
PYEOF
)

# ── Process each finding ──────────────────────────────────────────────────────
any_miss=0
count=$(jq 'length' "$INPUT_FILE")

for (( i=0; i<count; i++ )); do
    id=$(jq -r ".[$i].id"      "$INPUT_FILE")
    glob=$(jq -r ".[$i].file"  "$INPUT_FILE")
    pattern=$(jq -r ".[$i].pattern" "$INPUT_FILE")

    # Convert glob to awk-compatible ERE: * → .*
    file_regex=$(echo "$glob" | sed 's/\./\\./g; s/\*/.*/g')

    matched=0
    while IFS=$'\t' read -r f l mk txt; do
        if echo "$f" | grep -qE "^${file_regex}$" && echo "$txt" | grep -qE "$pattern"; then
            marker="[+]"
            [[ "$mk" == " " ]] && marker="[ ]"
            printf "[%s] L%-4s %s %s\n" "$id" "$l" "$marker" "$txt"
            matched=1
        fi
    done <<< "$PARSED_DIFF"

    if [[ $matched -eq 0 ]]; then
        printf "[%s] WARNING: no match for id=%s (%s / %s)\n" "$id" "$id" "$glob" "$pattern" >&2
        any_miss=1
    fi
done

[[ $any_miss -eq 1 ]] && exit 2
exit 0
