#!/usr/bin/env bash
# run.sh — E2E tests for the exposed-oauth2 feature
#
# Called by the workflow with these env vars already set:
#   IKANOS_IMAGE, IKANOS_CONTAINER, SECRETS_DIR, KC_CLIENT_SECRET, MCP_SERVER_TOKEN
#
# The workflow starts ikanos before calling this script and stops it after.
# Exit code: 0 = all passed, 1 = at least one failure

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../_lib/helpers.sh
source "$SCRIPT_DIR/../_lib/helpers.sh"

OAUTH2_PORT=3001
BEARER_PORT=3002

echo "================================================================"
echo "Feature: exposed-oauth2 (OAuth2.1 on :$OAUTH2_PORT + bearer on :$BEARER_PORT)"
echo "================================================================"

# ── 1. Obtain credentials ─────────────────────────────────────────────────────
# OAuth2 port: two independent Keycloak JWTs
VALID_TOKEN=$(mint_token)
if [ -z "$VALID_TOKEN" ] || [ "$VALID_TOKEN" = "null" ]; then
  echo "  FAIL: could not mint OAuth2.1 token from Keycloak"
  exit 1
fi
echo "  VALID_TOKEN minted (Keycloak)"

REFRESHED_TOKEN=$(mint_token)
echo "  REFRESHED_TOKEN minted (independent JWT)"

# Bearer port: static token. The workflow has already written it into
# secrets.yaml before starting the container — do NOT write it here. Ikanos
# resolves file:// binds once, synchronously, at capability construction
# (BindingResolver.resolve), so a write issued after start_ikanos only lands in
# time by luck of JVM startup being slower than sed.
STATIC_TOKEN="${MCP_SERVER_TOKEN}"

# ── 2. Wait for readiness ─────────────────────────────────────────────────────
assert_ready mcp "$OAUTH2_PORT" "$VALID_TOKEN"
assert_ready mcp "$BEARER_PORT" "$STATIC_TOKEN"

# ── OAuth2.1 surface (port 3001) — JWKS validation ───────────────────────────
run_mcp_test "oauth2-list-ships" "list-ships" "$OAUTH2_PORT" "{}" "$VALID_TOKEN" \
  'type == "array"' \
  'length > 0' \
  '.[0] | has("imo")' \
  '.[0] | has("name")' \
  '.[0] | has("status")'

run_mcp_test "oauth2-list-ships-filter-active" "list-ships" "$OAUTH2_PORT" '{"status":"active"}' "$VALID_TOKEN" \
  'type == "array"' \
  'length > 0' \
  'all(.[]; .status == "active")'

run_mcp_test "oauth2-get-ship" "get-ship" "$OAUTH2_PORT" '{"imo":"IMO-9321483"}' "$VALID_TOKEN" \
  'type == "object"' \
  'has("imo")' 'has("name")' 'has("type")' 'has("flag")' 'has("status")' \
  '.imo == "IMO-9321483"'

run_auth_test "oauth2-valid-token-200"     "list-ships" "$OAUTH2_PORT" "{}" "$VALID_TOKEN"               200
run_auth_test "oauth2-refreshed-token-200" "list-ships" "$OAUTH2_PORT" "{}" "$REFRESHED_TOKEN"           200
run_auth_test "oauth2-invalid-token-401"   "list-ships" "$OAUTH2_PORT" "{}" "this-is-not-a-valid-token"  401

# ── Bearer surface (port 3002) — static token validation ─────────────────────
run_auth_test "bearer-valid-token-200"   "list-ships" "$BEARER_PORT" "{}" "$STATIC_TOKEN"             200
run_auth_test "bearer-invalid-token-401" "list-ships" "$BEARER_PORT" "{}" "this-is-not-a-valid-token"  401

print_summary

[ "$FAILED" -gt 0 ] && exit 1 || exit 0
