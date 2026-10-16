#!/usr/bin/env bash
# Copyright 2025-2026 Naftiko
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.
#
# Lint the capability YAML files of this repository with Polychro: JSON Schema
# (ikanos-schema.json) and ruleset (ikanos-rules.yml) in one pass, on one file
# list, so both checks always cover exactly the same files.
#
# Usage:
#   scripts/lint-capabilities.sh            # lint every capability file
#   scripts/lint-capabilities.sh --list     # only print the files that would be linted
#
# Polychro is taken from $POLYCHRO, else from `polychro` on the PATH.
# Exit code: 0 when no file has an error (warnings are reported, not fatal), 1 otherwise.
cd "$(dirname "$0")/.." || exit 1

SCHEMA="modules/ikanos-spec/src/main/resources/schemas/ikanos-schema.json"
RULESET="modules/ikanos-spec/src/main/resources/rules/ikanos-rules.yml"

# Capability files: tutorial, schema examples and test fixtures.
INCLUDE='^modules/(ikanos-docs/tutorial|ikanos-spec/src/main/resources/schemas|ikanos-spec/src/test/resources|ikanos-engine/src/test/resources)/.*\.ya?ml$'
# Not capabilities, or fixtures that must fail on purpose:
# - secrets / sample data, OpenAPI documents, partial import sources
# - capability-spurious-name.yml: invalid on purpose (schema test)
# - ikanos-spec/src/test/resources/rules/: ruleset test fixtures, some must fail
#   (checked by IkanosPolychroRulesetTest instead)
EXCLUDE='(secrets|sample-users|/openapi/|/imports/|/capability-spurious-name\.yml$|^modules/ikanos-spec/src/test/resources/rules/)'

if ! TRACKED=$(git ls-files); then
  echo "Could not list the repository files with git." >&2
  exit 1
fi
FILES=$(printf "%s\n" "$TRACKED" | grep -E "$INCLUDE" | grep -Ev "$EXCLUDE" | sort)
# An empty list means the patterns or the checkout are wrong: never report that as a pass.
if [ -z "$FILES" ]; then
  echo "No capability files found to lint." >&2
  exit 1
fi

if [ "$1" = "--list" ]; then
  printf "%s\n" "$FILES"
  exit 0
fi

POLYCHRO="${POLYCHRO:-polychro}"
if ! command -v "$POLYCHRO" >/dev/null 2>&1; then
  echo "Polychro not found. Install it or set POLYCHRO=/path/to/polychro." >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required." >&2
  exit 1
fi

# On GitHub Actions, also emit annotations so findings show on the PR diff.
annotate() {
  [ -n "$GITHUB_ACTIONS" ] || return 0
  jq -r --arg f "$1" '.[]
    | "::\(if .severity == "error" then "error" else "warning" end) file=\($f),line=\(.range.startLine // 1)::[\(.code)] \(.message)"' <<<"$2"
}

# Polychro's stderr is kept apart so stdout stays clean for jq, and is shown
# only when Polychro itself fails.
err=$(mktemp) || exit 1
trap 'rm -f "$err"' EXIT

files=0; failed=0; errors=0; warnings=0
for f in $FILES; do
  files=$((files + 1))
  # --schema and --ruleset are passed as flags on purpose, never through
  # --config: Polychro ignores them when --config is set (naftiko/polychro#98).
  out=$("$POLYCHRO" lint --format json --schema "$SCHEMA" --ruleset "$RULESET" "$f" 2>"$err")
  code=$?
  # Polychro exits 0 (clean), 1 (warnings only) or 2 (errors). Anything else,
  # or output that is not JSON, means Polychro itself failed: never treat that as clean.
  if [ "$code" -gt 2 ] || ! jq -e 'type == "array"' >/dev/null 2>&1 <<<"$out"; then
    echo "FAIL  $f (Polychro exited with $code)"
    sed 's/^/      /' "$err"
    failed=$((failed + 1))
    continue
  fi
  e=$(jq '[.[] | select(.severity == "error")] | length' <<<"$out")
  w=$(jq '[.[] | select(.severity != "error")] | length' <<<"$out")
  errors=$((errors + e)); warnings=$((warnings + w))
  if [ "$e" -gt 0 ]; then
    echo "FAIL  $f"
    failed=$((failed + 1))
  elif [ "$w" -gt 0 ]; then
    echo "WARN  $f"
  fi
  jq -r '.[] | "      \(.severity | ascii_upcase) line \(.range.startLine // "?") [\(.code)] \(.message)"' <<<"$out"
  annotate "$f" "$out"
done

echo
echo "$files files linted: $errors error(s), $warnings warning(s), $failed file(s) failed."
[ "$failed" -eq 0 ]
