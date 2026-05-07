#!/usr/bin/env bash
# pr-submit-review.sh — Step 5 Branch B of the pr-review skill (Linux / macOS)
#
# Usage: ./.agents/skills/pr-review/pr-submit-review.sh <pr-number> <input-json> [owner/repo]
#
# Submits a GitHub PR review from a JSON input file and verifies the result.
# Blocks submission if a review from the current user already exists, to prevent
# irrecoverable duplicates (GitHub returns HTTP 422 on DELETE for submitted reviews).
#
# JSON input format:
# {
#   "event": "REQUEST_CHANGES",
#   "body": "Overall summary.",
#   "comments": [
#     { "path": "src/.../Foo.java", "line": 42, "body": "Comment text." }
#   ]
# }
#
# Pass --force as third argument to skip the duplicate check.

set -euo pipefail

PR="${1:?Usage: pr-submit-review.sh <pr-number> <input-json> [owner/repo|--force]}"
INPUT_FILE="${2:?Missing input JSON file}"
FORCE=false
REPO=""

for arg in "${@:3}"; do
  if [ "$arg" = "--force" ]; then FORCE=true
  else REPO="$arg"
  fi
done

if [ -z "$REPO" ]; then
  REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
fi

if [ ! -f "$INPUT_FILE" ]; then
  echo "ERROR: Input file not found: ${INPUT_FILE}" >&2
  exit 1
fi

EVENT=$(python3 -c "import json,sys; print(json.load(sys.stdin)['event'])" < "$INPUT_FILE")
COMMENT_COUNT=$(python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('comments', [])))" < "$INPUT_FILE")

echo ""
echo "=== Submitting review for PR #${PR} (${REPO}) ==="
echo "  event    : ${EVENT}"
echo "  comments : ${COMMENT_COUNT}"

# --- Duplicate check ---
if [ "$FORCE" = false ]; then
  CURRENT_USER=$(gh api user -q .login)
  EXISTING=$(gh api "repos/${REPO}/pulls/${PR}/reviews" --paginate \
    --jq "[.[] | select(.user.login == \"${CURRENT_USER}\" and (.state == \"CHANGES_REQUESTED\" or .state == \"COMMENTED\" or .state == \"APPROVED\"))] | length")

  if [ "$EXISTING" -gt 0 ]; then
    LAST_STATE=$(gh api "repos/${REPO}/pulls/${PR}/reviews" --paginate \
      --jq "[.[] | select(.user.login == \"${CURRENT_USER}\")] | last | .state")
    LAST_ID=$(gh api "repos/${REPO}/pulls/${PR}/reviews" --paginate \
      --jq "[.[] | select(.user.login == \"${CURRENT_USER}\")] | last | .id")
    echo "WARNING: A review from '${CURRENT_USER}' already exists on PR #${PR} (state: ${LAST_STATE}, id: ${LAST_ID})." >&2
    echo "WARNING: Submitting again will create an irrecoverable duplicate (GitHub HTTP 422 on DELETE)." >&2
    echo "WARNING: Pass --force to override this check only if you are certain a new review is intended." >&2
    exit 1
  fi
fi

# --- Submit ---
RESULT=$(gh api "repos/${REPO}/pulls/${PR}/reviews" --method POST --input "$INPUT_FILE")
REVIEW_ID=$(echo "$RESULT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['id'])")
REVIEW_STATE=$(echo "$RESULT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['state'])")
REVIEW_DATE=$(echo "$RESULT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['submitted_at'])")

echo ""
echo "Review submitted:"
echo "  id           : ${REVIEW_ID}"
echo "  state        : ${REVIEW_STATE}"
echo "  submitted_at : ${REVIEW_DATE}"

# --- Verify ---
echo ""
echo "--- Verifying inline comments ---"
ACTUAL_COUNT=$(gh api "repos/${REPO}/pulls/${PR}/comments" --paginate \
  --jq "[.[] | select(.pull_request_review_id == ${REVIEW_ID})] | length")

echo "  Expected : ${COMMENT_COUNT}"
echo "  Actual   : ${ACTUAL_COUNT}"

if [ "$ACTUAL_COUNT" -lt "$COMMENT_COUNT" ]; then
  MISSING=$((COMMENT_COUNT - ACTUAL_COUNT))
  echo "WARNING: ${MISSING} comment(s) were silently dropped by GitHub (wrong line number or path outside the diff)." >&2
  echo ""
  echo "Posted comments:"
  gh api "repos/${REPO}/pulls/${PR}/comments" --paginate \
    --jq "[.[] | select(.pull_request_review_id == ${REVIEW_ID})] | .[] | \"  \(.path):\(.line) — \(.body[:60])\"" 
  echo ""
  echo "Compare against your input file to identify the rejected entry."
else
  echo "All ${ACTUAL_COUNT} comment(s) confirmed on GitHub."
  gh api "repos/${REPO}/pulls/${PR}/comments" --paginate \
    --jq "[.[] | select(.pull_request_review_id == ${REVIEW_ID})] | .[] | \"  \(.path):\(.line) — \(.body[:70])\""
fi
