#!/usr/bin/env bash
# Idempotent dev-environment bootstrap for Navi.
# Does the bare minimum to get the latest Node.js, then hands off to bootstrap.cljs (nbb) for everything else.
# Windows: run from Git Bash. winget/msiexec raise their own UAC prompts; nothing else is elevated.
# Linux: sudo is used only to install Node.js into /usr/local.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log()  { echo "==> $*"; }
warn() { echo "!!  $*" >&2; }
die()  { echo "!!  $*" >&2; exit 1; }
command_exists() { command -v "$1" >/dev/null 2>&1; }

# DETECT OPERATING SYSTEM ----------------------------------------------------
detected_os="Unknown"
case "$(uname -s 2>/dev/null)" in
  Linux*)               detected_os="Linux" ;;
  Darwin*)              detected_os="macOS" ;;
  MINGW*|MSYS*|CYGWIN*) detected_os="Windows" ;;
  *) [[ "${OS:-}" == "Windows_NT" ]] && detected_os="Windows" ;;
esac
log "Detected OS: $detected_os"

latest_node_version() {
  curl -fsSL https://nodejs.org/dist/index.json | grep -o '"version": *"v[0-9.]*"' | head -n1 | grep -o 'v[0-9.]*'
}

# INSTALL NODE.JS (LATEST) ---------------------------------------------------
install_node_windows() {
  command_exists winget || die "winget not found. Install \"App Installer\" from the Microsoft Store and re-run."

  if winget list --id OpenJS.NodeJS --exact --accept-source-agreements --disable-interactivity >/dev/null 2>&1; then
    log "Upgrading Node.js via winget if a newer version exists..."
    winget upgrade --id OpenJS.NodeJS --exact --silent \
      --accept-source-agreements --accept-package-agreements --disable-interactivity || true
  elif command_exists node; then
    log "Node.js $(node --version) already installed outside winget; leaving it alone."
  else
    log "Installing Node.js via winget (a UAC prompt will appear)..."
    winget install --id OpenJS.NodeJS --exact --silent \
      --accept-source-agreements --accept-package-agreements --disable-interactivity
  fi

  # Git Bash won't see PATH changes made by the installer until it restarts.
  if ! command_exists node && [[ -x "/c/Program Files/nodejs/node.exe" ]]; then
    export PATH="/c/Program Files/nodejs:$PATH"
  fi
}

install_node_macos() {
  command_exists brew || die "Homebrew not found. Install it from https://brew.sh and re-run."
  if brew list node >/dev/null 2>&1; then
    log "Upgrading Node.js via Homebrew if a newer version exists..."
    brew upgrade node || true
  elif command_exists node; then
    log "Node.js $(node --version) already installed outside Homebrew; leaving it alone."
  else
    log "Installing Node.js via Homebrew..."
    brew install node
  fi
}

install_node_linux() {
  command_exists curl || die "curl not found; install it and re-run."
  local latest current arch tarball
  latest="$(latest_node_version)"
  [[ -n "$latest" ]] || die "Could not determine the latest Node.js version."
  current="$(node --version 2>/dev/null || true)"

  if [[ "$current" == "$latest" ]]; then
    log "Node.js $current is already the latest."
    return
  fi

  case "$(uname -m)" in
    x86_64)        arch="x64" ;;
    aarch64|arm64) arch="arm64" ;;
    *) die "Unsupported CPU architecture '$(uname -m)' for Node.js binaries." ;;
  esac

  tarball="$(mktemp "${TMPDIR:-/tmp}/node-XXXXXX.tar.xz")"
  log "Installing Node.js $latest to /usr/local (sudo)${current:+, replacing $current}..."
  curl -fsSL -o "$tarball" "https://nodejs.org/dist/${latest}/node-${latest}-linux-${arch}.tar.xz"
  sudo tar -xJf "$tarball" -C /usr/local --strip-components=1 \
    --exclude CHANGELOG.md --exclude LICENSE --exclude README.md
  rm -f "$tarball"
  hash -r
}

case "$detected_os" in
  Windows) install_node_windows ;;
  macOS)   install_node_macos ;;
  Linux)   install_node_linux ;;
  *)       die "Unknown OS; cannot continue." ;;
esac

command_exists node || die "node is still not on PATH. Open a new terminal and re-run."
log "Using Node.js $(node --version)"

# HAND OFF TO NBB ------------------------------------------------------------
cljs_script="$script_dir/bootstrap.cljs"
if [[ "$detected_os" == "Windows" ]]; then
  cljs_script="$(cygpath -w "$cljs_script")"
fi

log "Running bootstrap.cljs via nbb..."
exec npx --yes nbb@latest "$cljs_script" "$@"
