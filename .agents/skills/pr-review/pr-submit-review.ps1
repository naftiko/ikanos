<#
.SYNOPSIS
    Submits a GitHub PR review from a JSON input file and verifies the result.

.DESCRIPTION
    Reads a JSON file describing the review (event, body, comments[]) and posts it
    via "gh api --input -" to avoid all PowerShell quoting hazards. Immediately runs
    the post-submission GET verification and warns if the returned comment count does
    not match the submitted count.

    A duplicate review CANNOT be deleted once submitted (GitHub returns HTTP 422).
    This script prevents duplicates by checking for an existing CHANGES_REQUESTED
    or COMMENT review from the current user before posting, and aborting if one is
    found (unless -Force is passed).

.PARAMETER Pr
    The pull request number.

.PARAMETER Repo
    The GitHub repository in owner/repo format. Defaults to the current git remote.

.PARAMETER InputFile
    Path to a JSON file with the following structure:
        {
          "event": "REQUEST_CHANGES",
          "body": "Overall summary.",
          "comments": [
            { "path": "src/.../Foo.java", "line": 42, "body": "Comment text." },
            { "path": "src/.../Bar.java", "line": 17, "body": "Another comment." }
          ]
        }
    Supported event values: REQUEST_CHANGES, COMMENT, APPROVE.

.PARAMETER Force
    Skip the pre-submission duplicate check. Use only if you are certain no prior
    review exists from the current user.

.EXAMPLE
    .\.agents\skills\pr-review\pr-submit-review.ps1 -Pr 425 -InputFile "$env:TEMP\review-425.json"

.NOTES
    JSON input file example (save to $env:TEMP\review-425.json before calling):

    {
      "event": "REQUEST_CHANGES",
      "body": "Two findings — see inline comments.",
      "comments": [
        {
          "path": "src/test/java/io/naftiko/engine/EngineFieldThreadSafetyTest.java",
          "line": 68,
          "body": "This uses getDeclaredFields() via reflection..."
        },
        {
          "path": "src/main/java/io/naftiko/Capability.java",
          "line": 152,
          "body": "`this` escapes here before clientAdapters/serverAdapters are published..."
        }
      ]
    }
#>
param(
    [Parameter(Mandatory)]
    [int]$Pr,

    [Parameter(Mandatory)]
    [string]$InputFile,

    [string]$Repo = "",

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Resolve repo
if (-not $Repo) {
    $Repo = gh repo view --json nameWithOwner -q .nameWithOwner
}

# Validate input file
if (-not (Test-Path $InputFile)) {
    Write-Error "Input file not found: $InputFile"
    exit 1
}

$payload = Get-Content $InputFile -Raw | ConvertFrom-Json
$expectedCommentCount = if ($payload.comments) { @($payload.comments).Count } else { 0 }

Write-Host "`n=== Submitting review for PR #$Pr ($Repo) ===" -ForegroundColor Cyan
Write-Host "  event    : $($payload.event)"
Write-Host "  comments : $expectedCommentCount"

# --- Pre-submission duplicate check ---
if (-not $Force) {
    $currentUser = gh api user -q .login
    $existingReviews = @(gh api "repos/$Repo/pulls/$Pr/reviews" --paginate | ConvertFrom-Json |
        Where-Object { $_.user.login -eq $currentUser -and $_.state -in @("CHANGES_REQUESTED","COMMENTED","APPROVED") })

    if ($existingReviews.Count -gt 0) {
        Write-Warning "A review from '$currentUser' already exists on PR #$Pr (state: $($existingReviews[-1].state), id: $($existingReviews[-1].id))."
        Write-Warning "Submitting again will create an irrecoverable duplicate (GitHub HTTP 422 on DELETE)."
        Write-Warning "Pass -Force to override this check only if you are certain a new review is intended."
        exit 1
    }
}

# --- Submit ---
$result = Get-Content $InputFile -Raw |
    gh api "repos/$Repo/pulls/$Pr/reviews" --method POST --input - |
    ConvertFrom-Json

Write-Host "`nReview submitted:" -ForegroundColor Green
$result | Select-Object id, state, submitted_at | Format-List

# --- Verify inline comments ---
Write-Host "--- Verifying inline comments ---" -ForegroundColor Yellow
$postedComments = @(gh api "repos/$Repo/pulls/$Pr/comments" --paginate | ConvertFrom-Json |
    Where-Object { $_.pull_request_review_id -eq $result.id })

$actualCount = $postedComments.Count
Write-Host "  Expected : $expectedCommentCount"
Write-Host "  Actual   : $actualCount"

if ($actualCount -lt $expectedCommentCount) {
    $missing = $expectedCommentCount - $actualCount
    Write-Warning "$missing comment(s) were silently dropped by GitHub (wrong line number or path outside the diff)."
    Write-Host "`nPosted comments:" -ForegroundColor Yellow
    $postedComments | Select-Object path, line, @{n="body_preview";e={$_.body.Substring(0, [Math]::Min(60,$_.body.Length))}} | Format-Table -AutoSize

    Write-Host "`nSubmitted comments (from input file):" -ForegroundColor Yellow
    $payload.comments | Select-Object path, line, @{n="body_preview";e={$_.body.Substring(0, [Math]::Min(60,$_.body.Length))}} | Format-Table -AutoSize

    Write-Host "Compare the two tables above to identify the rejected entry, correct the line number or path, and report to the user." -ForegroundColor Red
} else {
    Write-Host "All $actualCount comment(s) confirmed on GitHub." -ForegroundColor Green
    $postedComments | Select-Object path, line, @{n="body_preview";e={$_.body.Substring(0, [Math]::Min(70,$_.body.Length))}} | Format-Table -AutoSize
}
