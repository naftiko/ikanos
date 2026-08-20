# ============================================================
# Ikanos CLI installer for Windows.
#
# Usage (run in PowerShell):
#   irm https://naftiko.github.io/ikanos/install.ps1 | iex
#
# To pin the *installer script itself* to a specific release (protects a
# scripted/automated install from a future change to this script,
# fetch it from the frozen per-tag path instead of
# the "latest" alias above:
#   irm https://naftiko.github.io/ikanos/v1.0.0/install.ps1 | iex
#
# This is independent from IKANOS_VERSION below, which pins the *ikanos
# package* being installed — the two can be combined, e.g. to reproduce an
# install exactly as it happened at v1.0.0 (same installer logic, same
# package):
#   $env:IKANOS_VERSION = "v1.0.0"; irm https://naftiko.github.io/ikanos/v1.0.0/install.ps1 | iex
#
# Env overrides (set as environment variables before running, or
# $env:IKANOS_VERSION = "v1.0.0-beta2" in the same session):
#   IKANOS_VERSION        Install a specific release tag. Defaults to the
#                         latest release, including pre-releases.
#   IKANOS_HOME           Install root. Defaults to "$env:USERPROFILE\.ikanos".
#   IKANOS_NO_MODIFY_PATH Set to "1" to skip editing the user PATH (must then
#                         be configured manually — see printed instructions).
#   IKANOS_BASE_URL       Override the base download URL (asset and checksum
#                         are fetched from "$IKANOS_BASE_URL/<asset>"). Defaults
#                         to the real GitHub Releases URL for $IkanosVersion.
#
# What it does:
#   1. Detects CPU arch and downloads the matching jlink-runtime + jar zip
#      from a GitHub Release (naftiko/ikanos).
#   2. Verifies its SHA-256 against the release's per-asset checksum file.
#   3. Extracts it to a versioned directory under $IKANOS_HOME\versions\
#      and repoints $IKANOS_HOME\current at it (junction, no admin needed —
#      unlike a symbolic link, which requires elevation or Developer Mode).
#   4. Writes a thin wrapper at $IKANOS_HOME\bin\ikanos.cmd.
#   5. Idempotently prepends $IKANOS_HOME\bin to the *user* PATH
#      (HKCU, not HKLM/Machine) — this never requires an elevated prompt.
#
# Nothing is written outside $IKANOS_HOME and the user's own PATH registry
# value — no Program Files, no admin/UAC prompt, and every account on a
# shared machine gets its own independent, fully isolated install (same
# model as docs/install.sh on macOS/Linux).
# ============================================================

#Requires -Version 5.1
$OriginalErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Stop"

$OriginalProgressPreference = $ProgressPreference
$ProgressPreference = "SilentlyContinue"

$Repo = "naftiko/ikanos"
$IkanosHome = if ($env:IKANOS_HOME) { $env:IKANOS_HOME } else { Join-Path $env:USERPROFILE ".ikanos" }
$IkanosVersion = $env:IKANOS_VERSION
$NoModifyPath = $env:IKANOS_NO_MODIFY_PATH -eq "1"
$BaseUrlOverride = $env:IKANOS_BASE_URL

function Write-Info  { param($Message) Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Warn  { param($Message) Write-Host "[warn] $Message" -ForegroundColor Yellow }
function Write-Fail  { param($Message) Write-Error "[error] $Message" -ErrorAction Stop }

try {
  # ------------------------------------------------------------------
  # 1. Detect arch and map to a release asset name. Windows-only script,
  #    so the OS component of the asset name is fixed at "windows".
  # ------------------------------------------------------------------
  $archRaw = $env:PROCESSOR_ARCHITECTURE
  switch -Regex ($archRaw) {
    "ARM64"        { $arch = "arm64" }
    "AMD64|x86_64" { $arch = "amd64" }
    default        { Write-Fail "Unsupported architecture: $archRaw" }
  }

  $asset = "ikanos-cli-windows-$arch.zip"
  $checksum = "$asset.sha256"

  # ------------------------------------------------------------------
  # 2. Resolve the version/tag to install.
  # ------------------------------------------------------------------
  if (-not $IkanosVersion) {
    # Note: /releases (unlike /releases/latest) includes pre-releases — this is
    # intentional while the project is entirely pre-1.0.0.
    Write-Info "Resolving latest release of $Repo (including pre-releases)..."
    $releases = Invoke-RestMethod -UseBasicParsing "https://api.github.com/repos/$Repo/releases"
    $IkanosVersion = $releases[0].tag_name
    if (-not $IkanosVersion) { Write-Fail "Could not resolve the latest release tag from the GitHub API." }
  }

  Write-Info "Installing ikanos $IkanosVersion (windows/$arch) into $IkanosHome"

  $baseUrl = if ($BaseUrlOverride) { $BaseUrlOverride } else { "https://github.com/$Repo/releases/download/$IkanosVersion" }

  # ------------------------------------------------------------------
  # 3. Download + verify checksum in a scratch temp dir.
  # ------------------------------------------------------------------
  $tmpDir = Join-Path $env:TEMP ("ikanos-install-" + [System.Guid]::NewGuid().ToString("N"))
  New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
  try {
    $assetPath = Join-Path $tmpDir $asset
    Write-Info "Downloading $asset..."
    try {
      Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/$asset" -OutFile $assetPath
    } catch {
      Write-Fail "Download failed: $baseUrl/$asset (does this version/platform combination exist?)"
    }

    Write-Info "Verifying checksum..."
    $checksumsPath = Join-Path $tmpDir $checksum
    try {
      Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/$checksum" -OutFile $checksumsPath
    } catch {
      Write-Fail "Could not download $checksum for verification."
    }

    $checksumLine = Select-String -Path $checksumsPath -Pattern "  $([regex]::Escape($asset))$" | Select-Object -First 1
    if (-not $checksumLine) { Write-Fail "No checksum entry found for $asset in $checksum." }
    $expectedSha = ($checksumLine.Line -split "\s+")[0]

    $actualSha = (Get-FileHash -Path $assetPath -Algorithm SHA256).Hash.ToLower()
    if ($expectedSha.ToLower() -ne $actualSha) {
      Write-Fail "Checksum mismatch for $asset (expected $expectedSha, got $actualSha). Aborting."
    }

    # ------------------------------------------------------------------
    # 4. Extract to a versioned directory and repoint "current".
    # ------------------------------------------------------------------
    $versionDir = $IkanosVersion -replace '^v', ''
    $installDir = Join-Path $IkanosHome "versions\$versionDir"
    if ([string]::IsNullOrWhiteSpace($versionDir) -or $versionDir -in @(".", "..") -or $versionDir -notmatch "^[A-Za-z0-9._+-]+$") {
      Write-Fail "Invalid IKANOS_VERSION: $IkanosVersion"
    }
    if (Test-Path $installDir) { Remove-Item -Recurse -Force $installDir }
    New-Item -ItemType Directory -Path $installDir -Force | Out-Null

    Expand-Archive -Path $assetPath -DestinationPath $installDir -Force

    $binDir = Join-Path $IkanosHome "bin"
    New-Item -ItemType Directory -Path $binDir -Force | Out-Null

    $currentLink = Join-Path $IkanosHome "current"
    if (Test-Path $currentLink) { Remove-Item -Recurse -Force $currentLink }
    # A directory junction (not a symbolic link) is used deliberately: on
    # Windows, creating a symbolic link requires either an elevated prompt
    # or Developer Mode enabled, while a junction needs neither — it is the
    # only link type NTFS allows any unprivileged user to create.
    New-Item -ItemType Junction -Path $currentLink -Target $installDir | Out-Null

    # ------------------------------------------------------------------
    # 5. Write the launcher wrapper.
    # ------------------------------------------------------------------
    $wrapperPath = Join-Path $binDir "ikanos.cmd"
    $wrapperContent = @"
@echo off
rem Generated by ikanos's install.ps1 — do not edit by hand.
if not defined IKANOS_HOME set "IKANOS_HOME=%USERPROFILE%\.ikanos"
"%IKANOS_HOME%\current\jre\bin\java.exe" -jar "%IKANOS_HOME%\current\ikanos.jar" %*
"@
    [System.IO.File]::WriteAllText($wrapperPath, $wrapperContent, (New-Object System.Text.UTF8Encoding($false)))

    Write-Info "Installed ikanos $IkanosVersion -> $installDir"

    # ------------------------------------------------------------------
    # 6. Wire up PATH (idempotent, user-scope only — no admin required).
    # ------------------------------------------------------------------
    if ($NoModifyPath) {
      Write-Warn "IKANOS_NO_MODIFY_PATH=1 set — skipping PATH update."
      Write-Info "Add this directory to your PATH manually: $binDir"
    } else {
      $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
      $pathEntries = @()
      if ($userPath) { $pathEntries = $userPath -split ";" | Where-Object { $_ -ne "" } }

      if ($pathEntries -contains $binDir) {
        Write-Info "PATH already configured (user scope)"
      } else {
        Write-Info "Adding $binDir to the user PATH"
        $newPath = if ($userPath) { "$binDir;$userPath" } else { $binDir }
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
      }

      # Update the current session's PATH too, regardless of whether the
      # persistent user-scope PATH needed changing above — a prior install
      # may have updated the registry after this terminal session started,
      # so "already configured (user scope)" does not imply this process's
      # $env:Path already has it. Idempotent: skip if already present.
      $sessionPaths = $env:Path -split ";" | Where-Object { $_ -ne "" }
      if ($sessionPaths -notcontains $binDir) {
        $env:Path = "$binDir;$env:Path"
      }
    }

    Write-Host ""
    if ($NoModifyPath) {
      Write-Warn "Neither the persistent user PATH nor this terminal session's PATH were updated."
      Write-Info "To use ikanos in this session, run:"
      Write-Host "    `$env:Path = `"$binDir;`$env:Path`""
      Write-Info "To use it permanently, add $binDir to your PATH manually."
    } else {
      Write-Info "Done. This session's PATH already includes ikanos; new terminals will pick it up too."
    }
    Write-Host "Verify with:"
    Write-Host "    ikanos --version"
  } finally {
    Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue
  }
} finally {
  if ($OriginalProgressPreference) { $ProgressPreference = $OriginalProgressPreference }
  $ErrorActionPreference = $OriginalErrorActionPreference
}
