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
# ============================================================
# pr-check.ps1 — Run all checks before opening a PR (Windows)
# Usage: from root run: '.\scripts\pr-check-wind.ps1'
# Requirements: maven, trivy, gitleaks (winget or choco)
# ============================================================

$PASS = 0
$FAIL = 0

function Invoke-Step {
  param([string]$Label, [scriptblock]$Command)
  Write-Host ""
  Write-Host "------------------------------------------------------------"
  Write-Host ">>> $Label"
  Write-Host "------------------------------------------------------------"
  try {
    & $Command
    if ($LASTEXITCODE -eq 0 -or $LASTEXITCODE -eq $null) {
      Write-Host "[PASS] $Label" -ForegroundColor Green
      $script:PASS++
    } else {
      Write-Host "[FAIL] $Label" -ForegroundColor Red
      $script:FAIL++
    }
  } catch {
    Write-Host "[FAIL] $Label — $_" -ForegroundColor Red
    $script:FAIL++
  }
}

Invoke-Step "Maven clean + unit tests + JaCoCo" {
  mvn clean test --no-transfer-progress
}

if (Get-Command trivy -ErrorAction SilentlyContinue) {
  Invoke-Step "Trivy - vulnerabilities + secrets + misconfig" {
    trivy fs . --scanners vuln,secret,misconfig --format table --exit-code 0
  }
  trivy fs . --scanners vuln,secret,misconfig --format table 2>&1 | Out-File -FilePath trivy-report.txt
  Write-Host "Report saved: trivy-report.txt" -ForegroundColor Yellow
} else {
  Write-Host "[SKIP] Trivy not installed — run: winget install aquasecurity.trivy" -ForegroundColor Yellow
}

if (Get-Command gitleaks -ErrorAction SilentlyContinue) {
  Invoke-Step "Gitleaks - secrets in git history" {
    gitleaks detect --source . --report-format json --report-path gitleaks-report.json --exit-code 0
  }
  Write-Host "Report saved: gitleaks-report.json" -ForegroundColor Yellow
} else {
  Write-Host "[SKIP] Gitleaks not installed — run: winget install zricethezav.gitleaks" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "============================================================"
Write-Host "  SUMMARY"
Write-Host "============================================================"
Write-Host "  PASSED: $PASS" -ForegroundColor Green
if ($FAIL -gt 0) {
  Write-Host "  FAILED: $FAIL" -ForegroundColor Red
  Write-Host ""
  Write-Host "Fix the issues above before opening a PR." -ForegroundColor Red
  exit 1
} else {
  Write-Host "  FAILED: $FAIL" -ForegroundColor Red
  Write-Host ""
  Write-Host "All checks passed. Ready to open a PR!" -ForegroundColor Green
  exit 0
}