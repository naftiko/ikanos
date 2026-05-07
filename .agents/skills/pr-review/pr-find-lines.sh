#!/usr/bin/env bash
# pr-find-lines.sh — Steps 3 + 4 of the pr-review skill (Linux / macOS)
#
# Usage: ./.agents/skills/pr-review/pr-find-lines.sh <pr-number> <file-pattern> <line-pattern>
#
# Reads the diff saved by pr-context.sh and prints L<n> [+] <line> for every
# added or context line matching <line-pattern> in files matching <file-pattern>.
#
# Examples:
#   ./pr-find-lines.sh 425 "EngineFieldThreadSafetyTest" "getDeclaredFields\|isVolatile"
#   ./pr-find-lines.sh 425 "Capability.java" "List\.add(new"

set -euo pipefail

PR="${1:?Usage: pr-find-lines.sh <pr-number> <file-pattern> <line-pattern>}"
FILE_PATTERN="${2:?Missing file pattern}"
LINE_PATTERN="${3:?Missing line pattern}"
DIFF_FILE="/tmp/pr${PR}.diff"

if [ ! -f "$DIFF_FILE" ]; then
  echo "ERROR: Diff file not found: ${DIFF_FILE} — run pr-context.sh ${PR} first." >&2
  exit 1
fi

HITS=$(awk -v fp="$FILE_PATTERN" -v lp="$LINE_PATTERN" '
  /^diff --git/ {
    in_file = ($0 ~ fp)
    in_hunk = 0
    line_num = 0
  }
  in_file && /^@@/ {
    if (match($0, /\+[0-9]+/))
      line_num = substr($0, RSTART + 1, RLENGTH - 1) - 1
    in_hunk = 1
  }
  in_file && in_hunk && /^\+/ {
    line_num++
    if ($0 ~ lp) { printf "L%d [+] %s\n", line_num, substr($0,2); hits++ }
  }
  in_file && in_hunk && /^ / {
    line_num++
    if ($0 ~ lp) { printf "L%d [ ] %s\n", line_num, substr($0,2); hits++ }
  }
  END { print hits+0 > "/dev/stderr" }
' "$DIFF_FILE" 2>&1 >/dev/tty; echo $?)

# Re-run cleanly to capture hit count
HITS=0
while IFS= read -r line; do
  echo "$line"
  HITS=$((HITS + 1))
done < <(awk -v fp="$FILE_PATTERN" -v lp="$LINE_PATTERN" '
  /^diff --git/ { in_file = ($0 ~ fp); in_hunk = 0; line_num = 0 }
  in_file && /^@@/ {
    if (match($0, /\+[0-9]+/)) line_num = substr($0, RSTART+1, RLENGTH-1) - 1
    in_hunk = 1
  }
  in_file && in_hunk && /^\+/ { line_num++; if ($0 ~ lp) printf "L%d [+] %s\n", line_num, substr($0,2) }
  in_file && in_hunk && /^ /  { line_num++; if ($0 ~ lp) printf "L%d [ ] %s\n", line_num, substr($0,2) }
' "$DIFF_FILE")

echo ""
if [ "$HITS" -eq 0 ]; then
  echo "WARNING: No lines matched pattern '${LINE_PATTERN}' in file '${FILE_PATTERN}'." >&2
else
  echo "${HITS} match(es) found. Use the L<n> values above as 'line' in pr-submit-review.sh."
fi
