<#
.SYNOPSIS
    Consolidates Steps 1 and 2 of the pr-review skill into a single invocation.

.DESCRIPTION
    Fetches PR metadata, changed files, existing reviews, existing inline comments,
    and saves the diff to $env:TEMP\pr<N>.diff. Prints a structured summary for
    the agent to parse. Run this once at the start of every review session.

.PARAMETER Pr
    The pull request number to fetch context for.

.PARAMETER Repo
    The GitHub repository in owner/repo format. Defaults to the current git remote.

.EXAMPLE
    .\.agents\skills\pr-review\pr-context.ps1 -Pr 425
#>
param(
    [Parameter(Mandatory)]
    [int]$Pr,

    [string]$Repo = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Resolve repo from git remote if not provided
if (-not $Repo) {
    $Repo = gh repo view --json nameWithOwner -q .nameWithOwner
}

Write-Host "`n=== PR #$Pr — $Repo ===" -ForegroundColor Cyan

# --- Metadata ---
Write-Host "`n--- Metadata ---" -ForegroundColor Yellow
$meta = gh pr view $Pr --repo $Repo --json number,title,state,author,headRefName,additions,deletions,changedFiles | ConvertFrom-Json
$meta | Format-List

# --- Changed files ---
Write-Host "`n--- Changed Files ---" -ForegroundColor Yellow
gh pr view $Pr --repo $Repo --json files |
    ConvertFrom-Json |
    Select-Object -ExpandProperty files |
    Select-Object path, additions, deletions, changeType |
    Format-Table -AutoSize

# --- Save diff ---
$diffPath = "$env:TEMP\pr$Pr.diff"
gh pr diff $Pr --repo $Repo | Out-File -FilePath $diffPath -Encoding utf8
$lineCount = (Get-Content $diffPath).Count
Write-Host "Diff saved: $diffPath ($lineCount lines)" -ForegroundColor Green

# --- Existing reviews ---
Write-Host "`n--- Existing Reviews ---" -ForegroundColor Yellow
$reviews = @(gh api "repos/$Repo/pulls/$Pr/reviews" --paginate |
    ConvertFrom-Json)
if ($reviews.Count -eq 0) {
    Write-Host "  (none)"
} else {
    $reviews | Select-Object id, state, submitted_at, @{n="user";e={$_.user.login}}, @{n="body_preview";e={$_.body -replace "`n"," " | ForEach-Object { if ($_.Length -gt 80) { $_.Substring(0,80) + "…" } else { $_ } }}} |
        Format-Table -AutoSize
}

# --- Existing inline comments ---
Write-Host "`n--- Existing Inline Comments ---" -ForegroundColor Yellow
$comments = @(gh api "repos/$Repo/pulls/$Pr/comments" --paginate |
    ConvertFrom-Json)
if ($comments.Count -eq 0) {
    Write-Host "  (none)"
} else {
    $comments | Select-Object id, pull_request_review_id, path, line,
        @{n="outdated";e={ $_.position -eq $null }},
        @{n="user";e={$_.user.login}},
        @{n="body_preview";e={ $s = $_.body -replace "`n"," "; if ($s.Length -gt 60) { $s.Substring(0,60) + "…" } else { $s } }} |
        Format-Table -AutoSize
}

Write-Host "`n=== Context ready. Diff at: $diffPath ===" -ForegroundColor Cyan
