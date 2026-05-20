#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# pr-comments.sh — Fetch all unresolved review comments on a pull request
#
# Unlike the VS Code "currentActivePullRequest" tool, which can miss comments
# from automated reviewers (Copilot, bots), this script queries the REST API
# directly via `gh api` and captures every inline comment.
#
# Usage:
#   bash .agents/skills/pr-review/pr-comments.sh <PR> [author] [--no-outdated]
#
# Examples:
#   bash .agents/skills/pr-review/pr-comments.sh 503
#   bash .agents/skills/pr-review/pr-comments.sh 503 Copilot
#   bash .agents/skills/pr-review/pr-comments.sh 503 "" --no-outdated
# ---------------------------------------------------------------------------
set -euo pipefail

PR="${1:?Usage: pr-comments.sh <PR> [author] [--no-outdated]}"
AUTHOR="${2:-}"
NO_OUTDATED="${3:-}"
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)

echo ""
echo "=== PR #$PR Comments — $REPO ==="

# --- Build jq filter ---
JQ_FILTER='.'
if [[ -n "$AUTHOR" ]]; then
    JQ_FILTER="[.[] | select(.user.login == \"$AUTHOR\")]"
fi
if [[ "$NO_OUTDATED" == "--no-outdated" ]]; then
    JQ_FILTER="[$JQ_FILTER[] | select(.position != null)]"
fi

# --- Fetch reviews for state mapping ---
REVIEWS=$(gh api "repos/$REPO/pulls/$PR/reviews" --paginate)

# --- Fetch all inline comments ---
COMMENTS=$(gh api "repos/$REPO/pulls/$PR/comments" --paginate | jq "$JQ_FILTER")

COUNT=$(echo "$COMMENTS" | jq 'length')
echo "  Matching comments: $COUNT"

if [[ "$COUNT" -eq 0 ]]; then
    echo "  No comments match the filter criteria."
    echo "=== Done ==="
    exit 0
fi

# --- Group and display ---
echo ""
echo "--- Comments by file ---"
echo "$COMMENTS" | jq -r '
    group_by(.path) | .[] |
    "  File: \(.[0].path)\n" +
    (to_entries | map(
        "    [\(.key + 1)] L\(.value.line) @\(.value.user.login)" +
        (if .value.position == null then " [OUTDATED]" else "" end) +
        "\n        " +
        (.value.body | split("\n") | first | if length > 120 then .[:120] + "…" else . end)
    ) | join("\n")) + "\n"
'

# --- Save structured JSON ---
JSON_PATH="/tmp/pr${PR}-comments.json"
echo "$COMMENTS" | jq --argjson reviews "$REVIEWS" '
    ($reviews | map({(.id | tostring): .state}) | add // {}) as $rmap |
    [.[] | {
        id: .id,
        file: .path,
        line: .line,
        author: .user.login,
        outdated: (.position == null),
        review_state: ($rmap[.pull_request_review_id | tostring] // ""),
        body: .body,
        created_at: .created_at,
        in_reply_to: .in_reply_to_id
    }]
' > "$JSON_PATH"
echo "Structured JSON saved: $JSON_PATH"

echo ""
echo "=== Done. $COUNT comment(s) ready for review. ==="
