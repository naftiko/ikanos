#!/bin/bash

# Validate an Ikanos capability YAML file against:
#   1. The Ikanos JSON Schema (ikanos-schema.json)
#   2. The Ikanos Polychro Rules (ikanos-rules.yml)
#
# Usage: ./lint-capability.sh <path-to-capability.yml>

set -euo pipefail

# ── Arguments ────────────────────────────────────────────────────
if [[ $# -eq 0 ]]; then
  echo "Error: Missing argument" >&2
  echo "Usage: $(basename "$0") <path-to-capability.yml>" >&2
  exit 1
fi

CAPABILITY_FILE="$1"

if [[ ! -f "$CAPABILITY_FILE" ]]; then
  echo "Error: File not found: $CAPABILITY_FILE" >&2
  exit 1
fi

# ── Locate project resources ────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../../" && pwd)"

SCHEMA_FILE="$PROJECT_ROOT/ikanos-spec/src/main/resources/schemas/ikanos-schema.json"
RULES_FILE="$PROJECT_ROOT/ikanos-spec/src/main/resources/rules/ikanos-rules.yml"

if [[ ! -f "$SCHEMA_FILE" ]]; then
  echo "Error: JSON Schema file not found: $SCHEMA_FILE" >&2
  exit 1
fi

if [[ ! -f "$RULES_FILE" ]]; then
  echo "Error: Spectral rules file not found: $RULES_FILE" >&2
  exit 1
fi

EXIT_CODE=0

# ── Step 1: JSON Schema validation ──────────────────────────────
# Uses ajv-cli v5, same as .github/workflows/validate-schemas.yml
echo "==> Validating against JSON Schema (ikanos-schema.json)..."

if ! npx -y ajv-cli@5 validate \
  -s "$SCHEMA_FILE" \
  -d "$CAPABILITY_FILE" \
  --spec=draft2020 \
  --strict-schema=false; then
  echo "  JSON Schema validation failed." >&2
  EXIT_CODE=1
else
  echo "  JSON Schema validation passed."
fi

# ── Step 2: Spectral linting ────────────────────────────────────
echo "==> Running Spectral rules (ikanos-rules.yml)..."

if command -v spectral &> /dev/null; then
  spectral lint "$CAPABILITY_FILE" --ruleset "$RULES_FILE" || EXIT_CODE=1
else
  npx -y @stoplight/spectral-cli lint "$CAPABILITY_FILE" --ruleset "$RULES_FILE" || EXIT_CODE=1
fi

exit "$EXIT_CODE"
