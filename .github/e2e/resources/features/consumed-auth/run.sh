#!/usr/bin/env bash
# run.sh — E2E tests for consumed-auth (basic, apikey)
#
# Env set by the workflow: IKANOS_IMAGE, IKANOS_CONTAINER, SECRETS_DIR,
# KC_CLIENT_SECRET, MCP_SERVER_TOKEN. The workflow starts and stops ikanos.
# Upstream: Microcks behind authgw (:8090 basic, :8091 and :8093 apikey).
# Each type is a pair, correct and wrong credentials: neither half proves
# anything alone. Digest is not covered until the engine answers the 401 challenge.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../_lib/helpers.sh"

PORT=3003

echo "================================================================"
echo "Feature: consumed-auth (basic + apikey)"
echo "================================================================"

# ── 1. Obtain credentials ─────────────────────────────────────────────
# Only the MCP surface needs a token. The workflow already injected it into
# secrets.yaml: binds are resolved once, so writing it here would race the JVM.
TOKEN="${MCP_SERVER_TOKEN}"

# ── 2. Wait for readiness ─────────────────────────────────────────────
assert_ready mcp "$PORT" "$TOKEN"

# ── 3. Tests ──────────────────────────────────────────────────────────

echo ""
echo "── basic ────────────────────────────────────────────────────────"

run_mcp_test "basic-list-ships" "basic-list-ships" "$PORT" '{}' "$TOKEN" \
  'type == "array"' 'length > 0' '.[0] | has("imo")'

run_mcp_test "basic-list-ships-filtered" "basic-list-ships" "$PORT" '{"status":"active"}' "$TOKEN" \
  'type == "array"' 'all(.[]; .status == "active")'

run_mcp_test "basic-get-ship" "basic-get-ship" "$PORT" '{"imo":"IMO-9321483"}' "$TOKEN" \
  'type == "object"' '.imo == "IMO-9321483"' '.name | length > 0'

run_mcp_expect_error "basic-rejects-wrong-credentials" \
  "basic-bad-credentials" "$PORT" '{}' "$TOKEN" "401"

# #707: no `password` field. The upstream account has an empty password, so this
# only passes if the engine sends an empty one.
run_mcp_test "basic-omitted-password-sends-empty-password" "basic-nopass-list-ships" "$PORT" '{}' "$TOKEN" \
  'type == "array"' 'length > 0' '.[0] | has("imo")'

echo ""
echo "── apikey ───────────────────────────────────────────────────────"

run_mcp_test "apikey-header-list-ships" "apikey-header-list-ships" "$PORT" '{}' "$TOKEN" \
  'type == "array"' 'length > 0' '.[0] | has("imo")'

run_mcp_test "apikey-header-list-ships-filtered" "apikey-header-list-ships" "$PORT" '{"status":"active"}' "$TOKEN" \
  'type == "array"' 'all(.[]; .status == "active")'

run_mcp_test "apikey-query-get-ship" "apikey-query-get-ship" "$PORT" '{"imo":"IMO-9321483"}' "$TOKEN" \
  'type == "object"' '.imo == "IMO-9321483"' '.name | length > 0'

run_mcp_expect_error "apikey-rejects-wrong-key" \
  "apikey-bad-key" "$PORT" '{}' "$TOKEN" "401"

# #698 / #716: raw token in the Authorization header.
run_mcp_test "apikey-authorization-header-list-ships" "apikey-authorization-list-ships" "$PORT" '{}' "$TOKEN" \
  'type == "array"' 'length > 0' '.[0] | has("imo")'

# ── 4. Summary ────────────────────────────────────────────────────────
print_summary
[ "$FAILED" -gt 0 ] && exit 1 || exit 0
