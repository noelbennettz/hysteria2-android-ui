#!/usr/bin/env bash
#
# Manage the bundled Hysteria core (`app/src/main/jniLibs/*/libhysteria.so`).
#
#   scripts/core.sh version            show the recorded core version
#   scripts/core.sh verify             check the committed binaries against core.properties (CI)
#   scripts/core.sh upgrade app/v2.13.0 download + checksum-verify + install a new core
#   scripts/core.sh hashes             print the sha256 of the binaries currently on disk
#
# Upstream publishes ready-made Android executables for every release, together
# with a `hashes.txt`, so an upgrade is fully reproducible and verifiable.
# The binaries are committed to the repo (no build-time download), which keeps
# CI and offline builds deterministic; the version + checksums live in
# `core/core.properties` and are surfaced as `BuildConfig.CORE_VERSION`.
set -euo pipefail

HY2_REPO="apernet/hysteria"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$ROOT/core/core.properties"
LIBDIR="$ROOT/app/src/main/jniLibs"

# jniLibs ABI -> upstream asset suffix (hysteria-android-<suffix>)
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
asset_suffix() {
  case "$1" in
    arm64-v8a)     echo arm64 ;;
    armeabi-v7a)   echo armv7 ;;
    x86_64)        echo amd64 ;;
    x86)           echo 386 ;;
    *) echo "unknown ABI: $1" >&2; exit 1 ;;
  esac
}

sha_of() { sha256sum "$1" | cut -d' ' -f1; }

prop() { sed -n "s/^$1=//p" "$PROPS" | head -n1; }

# Upstream embeds the version in different shapes across releases (older builds
# carry a bare "v2.5.1" string, newer ones carry e.g. "app/version?v2.13.0"), so
# simply look for the recorded version inside the binary.
core_contains_version() {
  local ver="$1"
  [ -n "$ver" ] || return 1
  # Grep the binary directly: `strings | grep -q` would SIGPIPE under pipefail.
  grep -qaF -- "$ver" "$LIBDIR/x86_64/libhysteria.so" 2>/dev/null
}

cmd_version() {
  local ver; ver="$(prop HYSTERIA_VERSION)"
  local state="unknown"
  if [ -n "$ver" ]; then
    if core_contains_version "$ver"; then state="matches manifest"; else state="DOES NOT match manifest"; fi
  fi
  echo "recorded core : ${ver:-<missing>}  (${HY2_REPO})"
  echo "binary        : ${state}"
}

cmd_hashes() {
  for abi in "${ABIS[@]}"; do
    printf '%-12s %s\n' "$abi" "$(sha_of "$LIBDIR/$abi/libhysteria.so")"
  done
}

cmd_verify() {
  local bad=0
  for abi in "${ABIS[@]}"; do
    local f="$LIBDIR/$abi/libhysteria.so"
    if [ ! -f "$f" ]; then echo "MISSING $f" >&2; bad=1; continue; fi
    local want got
    want="$(prop "sha256.$abi")"
    got="$(sha_of "$f")"
    if [ -z "$want" ]; then
      echo "MISSING sha256.$abi in core/core.properties" >&2; bad=1
    elif [ "$want" != "$got" ]; then
      echo "MISMATCH $abi: recorded=$want actual=$got" >&2; bad=1
    else
      echo "ok $abi"
    fi
  done

  local ver
  ver="$(prop HYSTERIA_VERSION)"
  if [ -n "$ver" ] && ! core_contains_version "$ver"; then
    echo "MISMATCH version: core.properties=$ver but the binary does not contain it" >&2; bad=1
  fi

  if [ "$bad" -ne 0 ]; then
    echo "core verification FAILED — run 'scripts/core.sh hashes' after a manual swap," >&2
    echo "or 'scripts/core.sh upgrade <tag>' to install a release properly." >&2
    return 1
  fi
  echo "core verification passed (${ver:-unknown})"
}

cmd_upgrade() {
  local tag="${1:-}"
  if [ -z "$tag" ]; then
    echo "usage: scripts/core.sh upgrade app/vX.Y.Z" >&2
    return 2
  fi
  case "$tag" in
    app/*) ;;
    v*) tag="app/$tag" ;;
    *)  tag="app/v$tag" ;;
  esac

  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp:-}"' EXIT
  local base="https://github.com/$HY2_REPO/releases/download/$tag"

  echo "==> fetching $HY2_REPO $tag"
  curl -fsSL "$base/hashes.txt" -o "$tmp/hashes.txt"

  for abi in "${ABIS[@]}"; do
    local name="hysteria-android-$(asset_suffix "$abi")"
    curl -fsSL "$base/$name" -o "$tmp/$name"
    local want got
    want="$(awk -v n="build/$name" '$2 == n { print $1 }' "$tmp/hashes.txt")"
    if [ -z "$want" ]; then
      echo "no upstream checksum for $name (see $base/hashes.txt)" >&2
      return 1
    fi
    got="$(sha_of "$tmp/$name")"
    if [ "$want" != "$got" ]; then
      echo "upstream checksum mismatch for $name: expected $want got $got" >&2
      return 1
    fi
    printf '  %-12s <- %s (sha256 verified)\n' "$abi" "$name"
    install -m 0755 "$tmp/$name" "$LIBDIR/$abi/libhysteria.so"
  done

  {
    echo "# Managed by scripts/core.sh — DO NOT hand-edit the sha256 values."
    echo "# Bump the bundled Hysteria core with:  scripts/core.sh upgrade app/vX.Y.Z"
    echo "# Check integrity with:                 scripts/core.sh verify"
    echo "HYSTERIA_REPO=$HY2_REPO"
    echo "HYSTERIA_VERSION=${tag#app/}"
    echo "HYSTERIA_UPGRADED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    for abi in "${ABIS[@]}"; do
      echo "sha256.$abi=$(sha_of "$LIBDIR/$abi/libhysteria.so")"
    done
  } > "$PROPS"

  echo "==> updated $(realpath --relative-to="$ROOT" "$PROPS")"
  cmd_version
  echo
  echo "Next: review the diff, then commit core/core.properties + the four .so files."
}

case "${1:-}" in
  version) cmd_version ;;
  verify)  cmd_verify ;;
  upgrade) shift; cmd_upgrade "${1:-}" ;;
  hashes)  cmd_hashes ;;
  *)
    sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
