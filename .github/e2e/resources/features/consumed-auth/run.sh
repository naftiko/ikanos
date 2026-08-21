#!/usr/bin/env bash
# run.sh — E2E tests for consumed-auth (basic, apikey, digest)
#
# Called by the workflow with these env vars already set:
#   IKANOS_IMAGE, IKANOS_CONTAINER, SECRETS_DIR, KC_CLIENT_SECRET, MCP_SERVER_TOKEN
# The workflow starts ikanos before calling this script and stops it after.
#
# Upstream is Microcks behind the authgw reverse proxy — :8090 basic,
# :8091 apikey, :8092 digest. See authgw/httpd.conf.
#
# Each auth type is tested as a pair: the same operation with correct
# credentials (must succeed) and with wrong ones (must be rejected). Neither
# half proves anything alone — the pair is the assertion.
#
# ⚠️ THE DIGEST BLOCK FAILS until HttpClientAdapter answers the
# 401 challenge. See the header of capability.yml. The failure surfaces as
# "Unexpected character ('<')" — that is Apache's HTML 401 page reaching the
# JSON parser, not a flake.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../_lib/helpers.sh"

PORT=3003

echo "================================================================"
echo "Feature: consumed-auth (basic + apikey + digest)"
echo "================================================================"

# ── 1. Obtain credentials ─────────────────────────────────────────────
# Only the MCP surface needs a token here — the consumed-side credentials live
# in secrets.yaml and are read by the capability itself. The workflow already
# injected this token into secrets.yaml before starting the container; writing
# it here would race the JVM, since binds are resolved once and for all at
# capability construction.
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
  "basic-bad-credentials" "$PORT" '{}' "$TOKEN"

echo ""
echo "── apikey ───────────────────────────────────────────────────────"

run_mcp_test "apikey-header-list-ships" "apikey-header-list-ships" "$PORT" '{}' "$TOKEN" \
  'type == "array"' 'length > 0' '.[0] | has("imo")'

run_mcp_test "apikey-header-list-ships-filtered" "apikey-header-list-ships" "$PORT" '{"status":"active"}' "$TOKEN" \
  'type == "array"' 'all(.[]; .status == "active")'

run_mcp_test "apikey-query-get-ship" "apikey-query-get-ship" "$PORT" '{"imo":"IMO-9321483"}' "$TOKEN" \
  'type == "object"' '.imo == "IMO-9321483"' '.name | length > 0'

run_mcp_expect_error "apikey-rejects-wrong-key" \
  "apikey-bad-key" "$PORT" '{}' "$TOKEN"

#RED : regression test for the apikey/Authorization bug.
# Restlet reserves the Authorization header and refuses the write with a WARN
run_mcp_test "apikey-authorization-header-list-ships" "apikey-authorization-list-ships" "$PORT" '{}' "$TOKEN" \
  'type == "array"' 'length > 0' '.[0] | has("imo")'

echo ""
echo "── digest (now red : see capability.yml) ────────────────────"

run_mcp_test "digest-list-ships" "digest-list-ships" "$PORT" '{}' "$TOKEN" \
  'type == "array"' 'length > 0' '.[0] | has("imo")'

run_mcp_test "digest-list-ships-filtered" "digest-list-ships" "$PORT" '{"status":"active"}' "$TOKEN" \
  'type == "array"' 'all(.[]; .status == "active")'

run_mcp_test "digest-get-ship" "digest-get-ship" "$PORT" '{"imo":"IMO-9321483"}' "$TOKEN" \
  'type == "object"' '.imo == "IMO-9321483"' '.name | length > 0'

# NOTE: this one currently passes for the wrong reason — every digest call
# fails, so "rejected" is indistinguishable from "broken". It regains its
# discriminating power only once the handshake works. Do not read it as
# coverage until then.
run_mcp_expect_error "digest-rejects-wrong-credentials" \
  "digest-bad-credentials" "$PORT" '{}' "$TOKEN"

# ── 4. Summary ────────────────────────────────────────────────────────
print_summary
[ "$FAILED" -gt 0 ] && exit 1 || exit 0
