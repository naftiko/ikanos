<#
.SYNOPSIS
    Fetches all unresolved review comments on a pull request via the GitHub
    REST API and groups them by file for systematic remediation.

.DESCRIPTION
    Unlike the VS Code "currentActivePullRequest" tool, which can miss comments
    from automated reviewers (Copilot, bots), this script queries the REST API
    directly via `gh api` and captures every inline comment.

    For each comment the script outputs: comment ID, file path, line number,
    author, whether the comment is outdated, a body preview, and the review
    state (CHANGES_REQUESTED, COMMENTED, etc.).

    Output is grouped by file so the agent can process fixes file-by-file.

.PARAMETER Pr
    The pull request number.

.PARAMETER Repo
    The GitHub repository in owner/repo format. Defaults to the current git remote.

.PARAMETER Author
    Optional. Filter comments to a specific author (e.g. "Copilot", "jlouv").

.PARAMETER ExcludeOutdated
    When set, omit comments whose position is null (outdated / no longer in diff).

.EXAMPLE
    .\.agents\skills\pr-review\pr-comments.ps1 -Pr 503
    .\.agents\skills\pr-review\pr-comments.ps1 -Pr 503 -Author Copilot
    .\.agents\skills\pr-review\pr-comments.ps1 -Pr 503 -ExcludeOutdated
#>
param(
    [Parameter(Mandatory)]
    [int]$Pr,

    [string]$Repo = "",

    [string]$Author = "",

    [switch]$ExcludeOutdated
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Resolve repo from git remote if not provided
if (-not $Repo) {
    $Repo = gh repo view --json nameWithOwner -q .nameWithOwner
}

Write-Host "`n=== PR #$Pr Comments — $Repo ===" -ForegroundColor Cyan

# --- Fetch reviews (to map review_id → state) ---
$reviews = @(gh api "repos/$Repo/pulls/$Pr/reviews" --paginate | ConvertFrom-Json)
$reviewMap = @{}
foreach ($r in $reviews) {
    $reviewMap[$r.id] = @{
        state = $r.state
        user  = $r.user.login
    }
}

# --- Fetch all inline comments ---
$allComments = @(gh api "repos/$Repo/pulls/$Pr/comments" --paginate | ConvertFrom-Json)

Write-Host "  Total inline comments: $($allComments.Count)" -ForegroundColor Gray

# --- Filter ---
$filtered = $allComments

if ($Author) {
    $filtered = @($filtered | Where-Object { $_.user.login -eq $Author })
    Write-Host "  After author filter ($Author): $($filtered.Count)" -ForegroundColor Gray
}

if ($ExcludeOutdated) {
    $filtered = @($filtered | Where-Object { $null -ne $_.position })
    Write-Host "  After excluding outdated: $($filtered.Count)" -ForegroundColor Gray
}

if ($filtered.Count -eq 0) {
    Write-Host "`n  No comments match the filter criteria." -ForegroundColor Yellow
    Write-Host "`n=== Done ===" -ForegroundColor Cyan
    exit 0
}

# --- Group by file ---
$grouped = $filtered | Group-Object -Property path | Sort-Object Name

Write-Host "`n--- Comments by file ($($filtered.Count) across $($grouped.Count) file(s)) ---`n" -ForegroundColor Yellow

$index = 0
foreach ($group in $grouped) {
    Write-Host "  File: $($group.Name)" -ForegroundColor White
    foreach ($c in ($group.Group | Sort-Object line)) {
        $index++
        $outdated = if ($null -eq $c.position) { " [OUTDATED]" } else { "" }
        $reviewState = ""
        if ($c.pull_request_review_id -and $reviewMap.ContainsKey($c.pull_request_review_id)) {
            $reviewState = " ($($reviewMap[$c.pull_request_review_id].state))"
        }

        # Truncate body for preview
        $bodyLines = $c.body -split "`n"
        $preview = $bodyLines[0]
        if ($preview.Length -gt 120) {
            $preview = $preview.Substring(0, 120) + "…"
        }
        if ($bodyLines.Count -gt 1) {
            $preview += " [+$($bodyLines.Count - 1) lines]"
        }

        Write-Host "    [$index] L$($c.line) @$($c.user.login)$reviewState$outdated" -ForegroundColor Cyan
        Write-Host "        $preview" -ForegroundColor Gray
        Write-Host "        id=$($c.id)" -ForegroundColor DarkGray
    }
    Write-Host ""
}

# --- Save structured JSON for agent consumption ---
$jsonPath = "$env:TEMP\pr$Pr-comments.json"
$structured = $filtered | ForEach-Object {
    $reviewState = ""
    if ($_.pull_request_review_id -and $reviewMap.ContainsKey($_.pull_request_review_id)) {
        $reviewState = $reviewMap[$_.pull_request_review_id].state
    }
    $replyTo = $null
    if ($_ | Get-Member -Name in_reply_to_id -MemberType NoteProperty) {
        $replyTo = $_.in_reply_to_id
    }
    [PSCustomObject]@{
        id           = $_.id
        file         = $_.path
        line         = $_.line
        author       = $_.user.login
        outdated     = ($null -eq $_.position)
        review_state = $reviewState
        body         = $_.body
        created_at   = $_.created_at
        in_reply_to  = $replyTo
    }
}
$structured | ConvertTo-Json -Depth 5 | Out-File -FilePath $jsonPath -Encoding utf8
Write-Host "Structured JSON saved: $jsonPath" -ForegroundColor Green

Write-Host "`n=== Done. $($filtered.Count) comment(s) ready for review. ===" -ForegroundColor Cyan
