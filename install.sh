#!/bin/sh
# ============================================================
#  Copyright 2026 Naftiko
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
# ============================================================
# Ikanos CLI installer.
#
# Usage:
#   curl -fsSL https://naftiko.github.io/ikanos/install.sh | sh
#
# To pin the *installer script itself* to a specific release (protects a
# scripted/automated install from a future change to this script,
# fetch it from the frozen per-tag path instead of
# the "latest" alias above:
#   curl -fsSL https://naftiko.github.io/ikanos/v1.0.0/install.sh | sh
#
# This is independent from IKANOS_VERSION below, which pins the *ikanos
# package* being installed — the two can be combined, e.g. to reproduce an
# install exactly as it happened at v1.0.0 (same installer logic, same
# package):
#   curl -fsSL https://naftiko.github.io/ikanos/v1.0.0/install.sh | IKANOS_VERSION=v1.0.0 sh
#
# Env overrides:
#   IKANOS_VERSION   Install a specific release tag (e.g. "v1.0.0-beta2").
#                     Defaults to the latest GitHub release, including
#                     pre-releases.
#   IKANOS_HOME      Install root. Defaults to "$HOME/.ikanos".
#   IKANOS_NO_MODIFY_RC=1   Skip editing the shell rc file (PATH must then
#                           be configured manually — see printed instructions).
#   IKANOS_BASE_URL  Override the base download URL (asset and checksum are
#                     fetched from "$IKANOS_BASE_URL/<asset>"). Defaults to
#                     the real GitHub Releases URL for $IKANOS_VERSION.
#
# What it does:
#   1. Detects OS/arch and downloads the matching jlink-runtime + jar
#      tarball from a GitHub Release (naftiko/ikanos).
#   2. Verifies its SHA-256 against the release's per-asset checksum file.
#   3. Extracts it to a versioned directory under $IKANOS_HOME/versions/
#      and symlinks $IKANOS_HOME/current to it.
#   4. Writes a thin wrapper script at $IKANOS_HOME/bin/ikanos.
#   5. Idempotently appends a PATH export to the caller's shell rc file.
#
# Nothing is written outside $IKANOS_HOME and the shell rc file — no /opt,
# no /usr/local, no admin/root privileges are ever required, and every
# account on a shared machine gets its own independent, fully isolated
# install (unlike Homebrew, which uses one shared, single-owner prefix).
# ============================================================

set -eu

REPO="naftiko/ikanos"
IKANOS_HOME="${IKANOS_HOME:-$HOME/.ikanos}"
IKANOS_VERSION="${IKANOS_VERSION:-}"
IKANOS_NO_MODIFY_RC="${IKANOS_NO_MODIFY_RC:-0}"
IKANOS_BASE_URL="${IKANOS_BASE_URL:-}"

info()  { printf '\033[1;34m==>\033[0m %s\n' "$1"; }
warn()  { printf '\033[1;33m[warn]\033[0m %s\n' "$1"; }
fail()  { printf '\033[1;31m[error]\033[0m %s\n' "$1" >&2; exit 1; }

# ------------------------------------------------------------------
# 1. Detect OS/arch and map to a release asset name.
# ------------------------------------------------------------------
os_raw=$(uname -s)
arch_raw=$(uname -m)

case "$os_raw" in
  Darwin) os="macos" ;;
  Linux)  os="linux" ;;
  *) fail "Unsupported OS: $os_raw. Windows support is not available yet." ;;
esac

case "$arch_raw" in
  arm64|aarch64) arch="arm64" ;;
  x86_64|amd64)  arch="amd64" ;;
  *) fail "Unsupported architecture: $arch_raw" ;;
esac

asset="ikanos-cli-${os}-${arch}.tar.gz"
checksum="$asset.sha256"

# ------------------------------------------------------------------
# 2. Resolve the version/tag to install.
# ------------------------------------------------------------------
if [ -z "$IKANOS_VERSION" ]; then
  # Note: /releases (unlike /releases/latest) includes pre-releases — this is
  # intentional while the project is entirely pre-1.0.0.
  info "Resolving latest release of $REPO (including pre-releases)..."
  IKANOS_VERSION=$(curl -fsSL "https://api.github.com/repos/$REPO/releases" \
    | grep '"tag_name"' | head -n1 | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')
  [ -n "$IKANOS_VERSION" ] || fail "Could not resolve the latest release tag from the GitHub API."
fi

info "Installing ikanos $IKANOS_VERSION ($os/$arch) into $IKANOS_HOME"

if [ -n "$IKANOS_BASE_URL" ]; then
  base_url="$IKANOS_BASE_URL"
else
  base_url="https://github.com/$REPO/releases/download/$IKANOS_VERSION"
fi

# ------------------------------------------------------------------
# 3. Download + verify checksum in a scratch temp dir.
# ------------------------------------------------------------------
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

info "Downloading $asset..."
curl -fsSL "$base_url/$asset" -o "$tmp_dir/$asset" \
  || fail "Download failed: $base_url/$asset (does this version/platform combination exist?)"

info "Verifying checksum..."
curl -fsSL "$base_url/$checksum" -o "$tmp_dir/$checksum" \
  || fail "Could not download $checksum for verification."

expected_sha=$(grep "  ${asset}\$" "$tmp_dir/$checksum" | awk '{print $1}')
[ -n "$expected_sha" ] || fail "No checksum entry found for $asset in $checksum."

if command -v shasum >/dev/null 2>&1; then
  actual_sha=$(shasum -a 256 "$tmp_dir/$asset" | awk '{print $1}')
elif command -v sha256sum >/dev/null 2>&1; then
  actual_sha=$(sha256sum "$tmp_dir/$asset" | awk '{print $1}')
else
  fail "Neither shasum nor sha256sum is available to verify the download."
fi

[ "$expected_sha" = "$actual_sha" ] \
  || fail "Checksum mismatch for $asset (expected $expected_sha, got $actual_sha). Aborting."

# ------------------------------------------------------------------
# 4. Extract to a versioned directory and symlink "current".
# ------------------------------------------------------------------
version_dir=${IKANOS_VERSION#v}
case "$version_dir" in
 ""|"."|".."|*[!A-Za-z0-9._+-]*) fail "Invalid IKANOS_VERSION: $IKANOS_VERSION" ;;
esac
install_dir="$IKANOS_HOME/versions/$version_dir"
rm -rf "$install_dir"
mkdir -p "$install_dir"
tar xzf "$tmp_dir/$asset" -C "$install_dir"

mkdir -p "$IKANOS_HOME/bin"
ln -sfn "$install_dir" "$IKANOS_HOME/current"

# ------------------------------------------------------------------
# 5. Write the launcher wrapper.
# ------------------------------------------------------------------
cat > "$IKANOS_HOME/bin/ikanos" <<'WRAPPER'
#!/bin/sh
# Generated by ikanos's install.sh — do not edit by hand.
IKANOS_HOME="${IKANOS_HOME:-$HOME/.ikanos}"
exec "$IKANOS_HOME/current/jre/bin/java" -jar "$IKANOS_HOME/current/ikanos.jar" "$@"
WRAPPER
chmod +x "$IKANOS_HOME/bin/ikanos"

info "Installed ikanos $IKANOS_VERSION -> $install_dir"

# ------------------------------------------------------------------
# 6. Wire up PATH (idempotent).
# ------------------------------------------------------------------
bin_dir="$IKANOS_HOME/bin"
marker="# >>> ikanos-cli PATH (managed by install.sh, safe to remove) >>>"
marker_end="# <<< ikanos-cli PATH <<<"

detect_rc_file() {
  case "${SHELL:-}" in
    */zsh)  echo "$HOME/.zshrc" ;;
    */bash) [ -f "$HOME/.bash_profile" ] && echo "$HOME/.bash_profile" || echo "$HOME/.bashrc" ;;
    */fish) echo "$HOME/.config/fish/config.fish" ;;
    *)      echo "$HOME/.profile" ;;
  esac
}

rc_file=$(detect_rc_file)
if [ "$rc_file" = "$HOME/.config/fish/config.fish" ]; then
  path_line="set -gx PATH \"$bin_dir\" \$PATH"
else
  path_line="export PATH=\"$bin_dir:\$PATH\""
fi

if [ "$IKANOS_NO_MODIFY_RC" = "1" ]; then
  warn "IKANOS_NO_MODIFY_RC=1 set — skipping shell rc update."
  info "Add this to your shell config manually: $path_line"
else
  if [ -f "$rc_file" ] && grep -qF "$marker" "$rc_file" 2>/dev/null && grep -qF "$marker_end" "$rc_file" 2>/dev/null; then
    # A marker block exists, but it may have been written by a previous
    # install with a different IKANOS_HOME (and therefore a different
    # bin_dir/path_line). Compare the block's actual content, not just the
    # marker's presence, before declaring the PATH already configured —
    # otherwise a reinstall into a new IKANOS_HOME silently leaves the old
    # bin directory on PATH while reporting success.
    existing_path_line=$(awk -v m="$marker" -v me="$marker_end" '
      $0==m {inblock=1; next}
      $0==me {inblock=0}
      inblock {print; exit}
    ' "$rc_file")
    if [ "$existing_path_line" = "$path_line" ]; then
      info "PATH already configured in $rc_file"
    else
      info "Updating ikanos PATH entry in $rc_file (IKANOS_HOME changed)"
      tmp_rc=$(mktemp)
      awk -v m="$marker" -v me="$marker_end" -v newline="$path_line" '
        $0==m {print; print newline; inblock=1; next}
        $0==me {print; inblock=0; next}
        inblock {next}
        {print}
      ' "$rc_file" > "$tmp_rc"
      cat "$tmp_rc" > "$rc_file"
      rm -f "$tmp_rc"
    fi
  else
    info "Adding ikanos to PATH in $rc_file"
    mkdir -p "$(dirname "$rc_file")"
    {
      printf '\n%s\n' "$marker"
      printf '%s\n' "$path_line"
      printf '%s\n' "$marker_end"
    } >> "$rc_file"
  fi
fi

echo ""
info "Done. Open a new shell, or run:"
echo "    $path_line"
echo "Then verify with:"
echo "    ikanos --version"
