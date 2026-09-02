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
# helpers.sh — shared utilities sourced by every feature run.sh
#
# Expected env vars set by the workflow before sourcing:
#   IKANOS_IMAGE      ghcr.io/naftiko/ikanos:latest
#   IKANOS_CONTAINER  container name (unique per feature run)
#   SECRETS_DIR       absolute path to .github/e2e/resources/shared
#   KC_CLIENT_SECRET  Keycloak client secret (default: test-secret)
#   MCP_SERVER_TOKEN  static fallback bearer token

# ── Counters (accumulated across tests within one run.sh) ─────────────────────
PASSED=0
FAILED=0
SKIPPED=0

# ── Container lifecycle ────────────────────────────────────────────────────────
cleanup_ikanos() {
  docker stop "$IKANOS_CONTAINER" 2>/dev/null || true
  docker rm   "$IKANOS_CONTAINER" 2>/dev/null || true
}

# start_ikanos CAPABILITY_FILE
# Starts the Ikanos container. All exposed ports are reachable on the host
# immediately via --network host — no -p mapping needed.
# Follow with one assert_ready call per exposed surface.
start_ikanos() {
  local CAP_FILE="$1"
  cleanup_ikanos
  docker run -d \
    --name "$IKANOS_CONTAINER" \
    --network host \
    -v "${CAP_FILE}:/app/test.capability.yaml" \
    -v "${SECRETS_DIR}:/app/shared" \
    "$IKANOS_IMAGE" \
    serve /app/test.capability.yaml
}

# ── Internal readiness pollers (use assert_ready, not these directly) ──────────
_wait_for_mcp() {
  local PORT="$1"
  local TOKEN="$2"
  echo "  Waiting for MCP server on port $PORT..."
  for j in $(seq 1 30); do
    RESP=$(curl -s "http://localhost:$PORT" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $TOKEN" \
      -d '{"jsonrpc":"2.0","id":0,"method":"tools/list","params":{}}' \
      2>/dev/null || true)
    if echo "$RESP" | jq -e '.result.tools' > /dev/null 2>&1; then
      echo "  MCP server is ready on port $PORT"; return 0
    fi
    sleep 2
  done
  echo "  MCP server on port $PORT did not become ready in time"; return 1
}

_wait_for_rest() {
  local PORT="$1"
  # NOT named PATH: that would shadow the shell's command-lookup path for the
  # whole dynamic scope of this function, leaving curl and sleep below resolvable
  # only through bash's command hash.
  local ENDPOINT_PATH="${2:-/}"
  echo "  Waiting for REST server on port $PORT..."
  for j in $(seq 1 30); do
    if curl -sf "http://localhost:$PORT$ENDPOINT_PATH" > /dev/null 2>&1; then
      echo "  REST server is ready on port $PORT"; return 0
    fi
    sleep 2
  done
  echo "  REST server on port $PORT did not become ready in time"; return 1
}

# ── Startup ────────────────────────────────────────────────────────────────────

# assert_ready TYPE PORT [TOKEN_OR_PATH]
# Waits for one exposed port to be ready, exits 1 with docker logs on timeout.
# Call once per exposed surface after start_ikanos.
#
#   TYPE  mcp   → polls tools/list with the bearer TOKEN (3rd arg)
#         rest  → polls GET PORT/PATH (3rd arg, default "/")
#
# Examples:
#   assert_ready mcp  3001 "$TOKEN"
#   assert_ready rest 3001
#   assert_ready rest 3001 "/health"
assert_ready() {
  local TYPE="$1"
  local PORT="$2"
  local ARG="${3:-}"

  local OK=false
  case "$TYPE" in
    mcp)  _wait_for_mcp  "$PORT" "$ARG"      && OK=true ;;
    rest) _wait_for_rest "$PORT" "${ARG:-/}" && OK=true ;;
    *)    echo "  assert_ready: unknown type '$TYPE' (use mcp or rest)"; exit 1 ;;
  esac

  if [ "$OK" != "true" ]; then
    echo "  FAIL: $TYPE port $PORT did not become ready"
    docker logs "$IKANOS_CONTAINER"
    cleanup_ikanos
    exit 1
  fi
}

# ── OAuth2 token minting ───────────────────────────────────────────────────────
mint_token() {
  curl -sf \
    -d "grant_type=client_credentials" \
    -d "client_id=ikanos-e2e" \
    -d "client_secret=${KC_CLIENT_SECRET}" \
    "http://localhost:8180/realms/e2e-test/protocol/openid-connect/token" \
    | jq -r '.access_token'
}

# ── Test runners ───────────────────────────────────────────────────────────────

# run_mcp_test NAME TOOL PORT ARGS VALID_TOKEN [JQ_VALIDATIONS...]
# Calls tools/call, runs jq validations, increments PASSED/FAILED.
run_mcp_test() {
  local NAME="$1" TOOL="$2" PORT="$3" ARGS="$4" TOKEN="$5"
  shift 5
  local VALIDATIONS=("$@")

  echo ""
  echo "  Test: $NAME  (type: mcp, tool: $TOOL, port: $PORT)"

  # `|| true` is required: run.sh scripts set -e, and a transient curl failure
  # (connection refused, reset) would otherwise abort the whole script from
  # inside this assignment, losing the run instead of counting one failed test.
  local RESPONSE IS_ERROR DATA
  RESPONSE=$(curl -s "http://localhost:$PORT" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$TOOL\",\"arguments\":$ARGS}}" || true)

  IS_ERROR=$(echo "$RESPONSE" | jq -r '.result.isError // false')
  DATA=$(echo "$RESPONSE" | jq -r '.result.content[0].text // empty')

  if [ "$IS_ERROR" = "true" ]; then
    echo "  FAIL ✗ — tool returned an error: $DATA"
    FAILED=$((FAILED + 1)); return
  fi

  if echo "$DATA" | head -c 1 | grep -q "<"; then
    DATA=$(echo "$DATA" | jq -R .)
  fi

  if ! echo "$DATA" | jq . > /dev/null 2>&1; then
    echo "  FAIL ✗ — invalid JSON. Raw: $RESPONSE"
    FAILED=$((FAILED + 1)); return
  fi

  local RULE TEST_PASSED=true
  for RULE in "${VALIDATIONS[@]}"; do
    if ! echo "$DATA" | jq -e "$RULE" > /dev/null 2>&1; then
      echo "  FAIL ✗ — validation failed: $RULE"
      echo "    Data: $(echo "$DATA" | jq -c .)"
      TEST_PASSED=false; break
    fi
  done

  if [ "$TEST_PASSED" = "true" ]; then
    echo "  PASS ✓ — $NAME"
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
  fi
}

# run_auth_test NAME TOOL PORT ARGS TOKEN EXPECTED_HTTP_STATUS
# Sends a raw MCP request and asserts on HTTP status code only.
run_auth_test() {
  local NAME="$1" TOOL="$2" PORT="$3" ARGS="$4" TOKEN="$5" EXPECTED_STATUS="$6"

  echo ""
  echo "  Test: $NAME  (type: auth, tool: $TOOL, port: $PORT, expected: HTTP $EXPECTED_STATUS)"

  # See run_mcp_test for why `|| true` is required here.
  local HTTP_STATUS
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "http://localhost:$PORT" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$TOOL\",\"arguments\":$ARGS}}" || true)

  if [ "$HTTP_STATUS" = "$EXPECTED_STATUS" ]; then
    echo "  PASS ✓ — $NAME (HTTP $HTTP_STATUS)"
    PASSED=$((PASSED + 1))
  else
    echo "  FAIL ✗ — expected HTTP $EXPECTED_STATUS, got $HTTP_STATUS"
    FAILED=$((FAILED + 1))
  fi
}

# run_rest_test NAME METHOD PORT PATH BODY TOKEN EXPECTED_STATUS [JQ_VALIDATIONS...]
# Makes a raw REST call, checks HTTP status, then optionally runs jq on the response body.
# Pass "" for BODY on GET requests. Pass "" for TOKEN to send no Authorization header.
run_rest_test() {
  local NAME="$1" METHOD="$2" PORT="$3" ENDPOINT="$4" BODY="$5" TOKEN="$6" EXPECTED_STATUS="$7"
  shift 7
  local VALIDATIONS=("$@")

  echo ""
  echo "  Test: $NAME  (type: rest, $METHOD http://localhost:$PORT$ENDPOINT, expected: HTTP $EXPECTED_STATUS)"

  local CURL_ARGS=(-s -o /tmp/rest_body -w "%{http_code}"
    -X "$METHOD"
    -H "Content-Type: application/json"
    "http://localhost:$PORT$ENDPOINT")

  # Written as `if` rather than `[ -n "$X" ] && ...`: as a top-level statement
  # that form evaluates to 1 whenever the variable is empty, which under the
  # `set -e` used by every run.sh aborts the script — on exactly the empty
  # TOKEN and empty BODY this helper documents as supported.
  if [ -n "$TOKEN" ]; then CURL_ARGS+=(-H "Authorization: Bearer $TOKEN"); fi
  if [ -n "$BODY"  ]; then CURL_ARGS+=(-d "$BODY"); fi

  # See run_mcp_test for why `|| true` is required here.
  local HTTP_STATUS
  HTTP_STATUS=$(curl "${CURL_ARGS[@]}" || true)
  local DATA
  DATA=$(cat /tmp/rest_body 2>/dev/null || true)

  if [ "$HTTP_STATUS" != "$EXPECTED_STATUS" ]; then
    echo "  FAIL ✗ — expected HTTP $EXPECTED_STATUS, got $HTTP_STATUS"
    echo "    Body: $DATA"
    FAILED=$((FAILED + 1)); return
  fi

  if [ "${#VALIDATIONS[@]}" -eq 0 ]; then
    echo "  PASS ✓ — $NAME (HTTP $HTTP_STATUS)"
    PASSED=$((PASSED + 1)); return
  fi

  if echo "$DATA" | head -c 1 | grep -q "<"; then
    DATA=$(echo "$DATA" | jq -R .)
  fi

  local RULE TEST_PASSED=true
  for RULE in "${VALIDATIONS[@]}"; do
    if ! echo "$DATA" | jq -e "$RULE" > /dev/null 2>&1; then
      echo "  FAIL ✗ — validation failed: $RULE"
      echo "    Data: $(echo "$DATA" | jq -c .)"
      TEST_PASSED=false; break
    fi
  done

  if [ "$TEST_PASSED" = "true" ]; then
    echo "  PASS ✓ — $NAME (HTTP $HTTP_STATUS)"
    PASSED=$((PASSED + 1))
  else
    FAILED=$((FAILED + 1))
  fi
}

# ── Summary ────────────────────────────────────────────────────────────────────
print_summary() {
  echo ""
  echo "  passed: $PASSED | failed: $FAILED | skipped: $SKIPPED"
}
