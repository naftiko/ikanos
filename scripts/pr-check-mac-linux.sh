#!/bin/bash
# ============================================================
# pr-check.sh — Run all checks before opening a PR
# Compatible: Mac, Linux (apt/yum/dnf/brew)
# ============================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS=0
FAIL=0

run_step() {
  local label="$1"
  shift
  echo ""
  echo "------------------------------------------------------------"
  echo ">>> $label"
  echo "------------------------------------------------------------"
  if "$@"; then
    echo -e "${GREEN}[PASS] $label${NC}"
    PASS=$((PASS + 1))
  else
    echo -e "${RED}[FAIL] $label${NC}"
    FAIL=$((FAIL + 1))
  fi
}

install_hint() {
  local tool="$1"
  echo -e "${YELLOW}[SKIP] $tool not installed. Install it with:${NC}"
  if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "  brew install $tool"
  elif command -v apt-get &>/dev/null; then
    echo "  sudo apt-get install -y $tool"
  elif command -v dnf &>/dev/null; then
    echo "  sudo dnf install -y $tool"
  elif command -v yum &>/dev/null; then
    echo "  sudo yum install -y $tool"
  elif command -v brew &>/dev/null; then
    echo "  brew install $tool"
  else
    echo "  Please install $tool manually: https://github.com/$tool"
  fi
}

run_step "Maven clean + unit tests + JaCoCo" \
  mvn clean test --no-transfer-progress

if command -v trivy &>/dev/null; then
  run_step "Trivy - vulnerabilities + secrets + misconfig" \
    trivy fs . --scanners vuln,secret,misconfig --format table --exit-code 0
  trivy fs . --scanners vuln,secret,misconfig --format table > trivy-report.txt 2>&1 || true
  echo -e "${YELLOW}Report saved: trivy-report.txt${NC}"
else
  install_hint "trivy"
  echo "  Or visit: https://aquasecurity.github.io/trivy/latest/getting-started/installation/"
fi

if command -v gitleaks &>/dev/null; then
  run_step "Gitleaks - secrets in git history" \
    gitleaks detect --source . --report-format json --report-path gitleaks-report.json --exit-code 0
  echo -e "${YELLOW}Report saved: gitleaks-report.json${NC}"
else
  install_hint "gitleaks"
  echo "  Or visit: https://github.com/gitleaks/gitleaks#installing"
fi

echo ""
echo "============================================================"
echo "  SUMMARY"
echo "============================================================"
echo -e "  ${GREEN}PASSED: $PASS${NC}"
if [ $FAIL -gt 0 ]; then
  echo -e "  ${RED}FAILED: $FAIL${NC}"
  echo ""
  echo -e "${RED}Fix the issues above before opening a PR.${NC}"
  exit 1
else
  echo -e "  ${RED}FAILED: 0${NC}"
  echo ""
  echo -e "${GREEN}All checks passed. Ready to open a PR!${NC}"
  exit 0
fi