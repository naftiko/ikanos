#!/usr/bin/env bash
# pr-context.sh — Steps 1 + 2 of the pr-review skill (Linux / macOS)
#
# Usage: ./.agents/skills/pr-review/pr-context.sh <pr-number> [owner/repo]
#
# Fetches PR metadata, changed files, existing reviews, existing inline
# comments, and saves the diff to /tmp/pr<N>.diff.

set -euo pipefail

PR="${1:?Usage: pr-context.sh <pr-number> [owner/repo]}"
REPO="${2:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
DIFF_FILE="/tmp/pr${PR}.diff"

echo ""
echo "=== PR #${PR} — ${REPO} ==="

echo ""
echo "--- Metadata ---"
gh pr view "$PR" --repo "$REPO" \
  --json number,title,state,author,headRefName,additions,deletions,changedFiles \
  | python3 -c "
import json, sys
d = json.load(sys.stdin)
for k, v in d.items():
    print(f'  {k}: {v}')
"

echo ""
echo "--- Changed Files ---"
gh pr view "$PR" --repo "$REPO" --json files \
  | python3 -c "
import json, sys
files = json.load(sys.stdin)['files']
print(f'  {'path':<70} {'add':>5} {'del':>5} {'type':<10}')
print(f'  {'-'*70} {'---':>5} {'---':>5} {'-'*10}')
for f in files:
    print(f\"  {f['path']:<70} {f['additions']:>5} {f['deletions']:>5} {f['changeType']:<10}\")
"

echo ""
echo "--- Saving diff to ${DIFF_FILE} ---"
gh pr diff "$PR" --repo "$REPO" > "$DIFF_FILE"
echo "  Diff saved: ${DIFF_FILE} ($(wc -l < "$DIFF_FILE") lines)"

echo ""
echo "--- Existing Reviews ---"
gh api "repos/${REPO}/pulls/${PR}/reviews" --paginate \
  --jq '["id","state","submitted_at","user"], (.[] | [.id, .state, .submitted_at, .user.login]) | @tsv' \
  | column -t

echo ""
echo "--- Existing Inline Comments ---"
gh api "repos/${REPO}/pulls/${PR}/comments" --paginate \
  --jq '["id","review_id","path","line","user","outdated"], (.[] | [.id, .pull_request_review_id, .path, (.line // "null"), .user.login, (if .position == null then "true" else "false" end)]) | @tsv' \
  | column -t

echo ""
echo "=== Context ready. Diff at: ${DIFF_FILE} ==="
