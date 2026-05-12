<#
.SYNOPSIS
    Resolve GitHub diff line numbers for a batch of findings in one call.

.DESCRIPTION
    Reads a JSON file containing a list of findings (each with an id, file glob,
    and text pattern), locates each pattern in the saved diff, and prints the
    confirmed line number for every match.

    Input JSON format  ($env:TEMP\findings-<PR>.json):
    [
      { "id": 1, "file": "Foo.java",   "pattern": "methodName|otherMethod" },
      { "id": 2, "file": "pom.xml",    "pattern": "jacoco\\.halt" },
      { "id": 3, "file": "Bar*.java",  "pattern": "someText" }
    ]

    Output (one line per match):
    [1] L42  [+] void methodName() {
    [2] L92  [+]         <jacoco.halt>true</jacoco.halt>
    [3] WARNING: no match for id=3 (Bar*.java / someText)

.PARAMETER Pr
    The pull request number.

.PARAMETER InputFile
    Path to the JSON findings file. Defaults to $env:TEMP\findings-<Pr>.json.

.EXAMPLE
    .\.agents\skills\pr-review\pr-find-lines-batch.ps1 -Pr 454
    .\.agents\skills\pr-review\pr-find-lines-batch.ps1 -Pr 454 -InputFile "$env:TEMP\my-findings.json"
#>
param(
    [Parameter(Mandatory)][int]    $Pr,
    [Parameter()][string]          $InputFile = "$env:TEMP\findings-$Pr.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Locate diff ──────────────────────────────────────────────────────────────
$diffPath = "$env:TEMP\pr$Pr.diff"
if (-not (Test-Path $diffPath)) {
    Write-Error "Diff not found at $diffPath. Run pr-context.ps1 -Pr $Pr first."
    exit 1
}

# ── Load findings ─────────────────────────────────────────────────────────────
if (-not (Test-Path $InputFile)) {
    Write-Error "Findings file not found: $InputFile"
    exit 1
}
$findings = Get-Content $InputFile -Raw -Encoding utf8 | ConvertFrom-Json

# ── Parse diff into a searchable structure ────────────────────────────────────
# Each entry: @{ file = "path/to/File.java"; line = 42; marker = "+"; text = "..." }
$diffLines = [System.Collections.Generic.List[hashtable]]::new()

$currentFile = $null
$lineCounter = 0

foreach ($raw in (Get-Content $diffPath -Encoding utf8)) {
    # New file header
    if ($raw -match '^\+\+\+ b/(.+)$') {
        $currentFile = $Matches[1]
        $lineCounter = 0
        continue
    }
    # Hunk header — reset counter
    if ($raw -match '^@@ -\d+(?:,\d+)? \+(\d+)') {
        $lineCounter = [int]$Matches[1] - 1
        continue
    }
    # Skip diff metadata lines
    if ($raw -match '^(---|diff |index |new file|deleted file|Binary|@@)') { continue }

    if ($null -eq $currentFile) { continue }

    $marker = $raw.Substring(0, [Math]::Min(1, $raw.Length))
    if ($marker -eq '+') {
        $lineCounter++
        $diffLines.Add(@{ file = $currentFile; line = $lineCounter; marker = '+'; text = $raw.Substring(1) })
    } elseif ($marker -eq ' ') {
        $lineCounter++
        $diffLines.Add(@{ file = $currentFile; line = $lineCounter; marker = ' '; text = $raw.Substring(1) })
    }
    # '-' lines: do not increment, do not index
}

# ── Resolve each finding ──────────────────────────────────────────────────────
$anyMiss = $false

foreach ($f in $findings) {
    $id      = $f.id
    $glob    = $f.file
    $pattern = $f.pattern

    # Convert glob to regex: * → .*
    $fileRegex = '^' + [regex]::Escape($glob).Replace('\*', '.*') + '$'

    $matches_ = $diffLines | Where-Object {
        $_.file -match $fileRegex -and $_.text -match $pattern
    }

    if (-not $matches_ -or @($matches_).Count -eq 0) {
        Write-Host "[$id] WARNING: no match for id=$id ($glob / $pattern)" -ForegroundColor Yellow
        $anyMiss = $true
        continue
    }

    foreach ($m in @($matches_)) {
        $marker = if ($m.marker -eq '+') { '[+]' } else { '[ ]' }
        Write-Host "[$id] L$($m.line)  $marker $($m.text)"
    }
}

if ($anyMiss) { exit 2 }
exit 0
