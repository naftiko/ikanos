<#
.SYNOPSIS
    Computes the resulting-file line numbers for findings in a PR diff (Steps 3 + 4).

.DESCRIPTION
    Reads the diff saved by pr-context.ps1 and applies the hunk counter algorithm
    defined in the pr-review skill. For every added or context line that matches
    the given pattern in the given file, prints:

        L<n> [+|-] <line content>

    Use the printed line numbers directly in pr-submit-review.ps1.

.PARAMETER Pr
    The pull request number (used to locate $env:TEMP\pr<N>.diff).

.PARAMETER File
    A substring or regex pattern matched against the diff "b/<path>" header.
    Example: "Capability.java"  or  "EngineFieldThreadSafetyTest"

.PARAMETER Pattern
    A regex pattern matched against the content of added/context lines.
    Example: "getDeclaredFields"  or  "List\.add\(new"

.PARAMETER DiffFile
    Optional explicit path to a diff file. Overrides the default $env:TEMP\pr<N>.diff.

.EXAMPLE
    .\.agents\skills\pr-review\pr-find-lines.ps1 -Pr 425 -File "Capability.java" -Pattern "List\.add\(new"
    .\.agents\skills\pr-review\pr-find-lines.ps1 -Pr 425 -File "EngineFieldThreadSafetyTest" -Pattern "getDeclaredFields|isVolatile"
#>
param(
    [Parameter(Mandatory)]
    [int]$Pr,

    [Parameter(Mandatory)]
    [string]$File,

    [Parameter(Mandatory)]
    [string]$Pattern,

    [string]$DiffFile = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $DiffFile) {
    $DiffFile = "$env:TEMP\pr$Pr.diff"
}

if (-not (Test-Path $DiffFile)) {
    Write-Error "Diff file not found: $DiffFile — run pr-context.ps1 -Pr $Pr first."
    exit 1
}

$lines = Get-Content $DiffFile
$inFile = $false
$inHunk = $false
$lineNum = 0
$hits = 0

foreach ($line in $lines) {
    # New file section
    if ($line -match "^diff --git") {
        $inFile = ($line -match [regex]::Escape($File)) -or ($line -match $File)
        $inHunk = $false
        $lineNum = 0
    }

    if ($inFile) {
        # Hunk header — reset counter
        if ($line -match "^@@") {
            $inHunk = $true
            $m = [regex]::Match($line, '\+(\d+)')
            if ($m.Success) { $lineNum = [int]$m.Groups[1].Value - 1 }
        }
        elseif ($inHunk -and $line -match "^\+") {
            $lineNum++
            if ($line -match $Pattern) {
                Write-Host ("L{0} [+] {1}" -f $lineNum, $line.Substring(1))
                $hits++
            }
        }
        elseif ($inHunk -and $line -match "^ ") {
            $lineNum++
            if ($line -match $Pattern) {
                Write-Host ("L{0} [ ] {1}" -f $lineNum, $line.Substring(1))
                $hits++
            }
        }
        # Lines starting with - : do not increment, cannot be targeted
    }
}

if ($hits -eq 0) {
    Write-Warning "No lines matched pattern '$Pattern' in file '$File'. Check spelling or use pr-context.ps1 to inspect the diff."
} else {
    Write-Host "`n$hits match(es) found. Use the L<n> values above as 'line' in pr-submit-review.ps1." -ForegroundColor Green
}
