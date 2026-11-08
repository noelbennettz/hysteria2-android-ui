#!/usr/bin/env bash
#
# Manage the Hysteria core (`app/src/main/jniLibs/*/libhysteria.so`).
#
#   scripts/core.sh fetch                  install the pinned core if missing/stale (Gradle calls this)
#   scripts/core.sh latest                 resolve the newest upstream release
#   scripts/core.sh upgrade [tag|latest]   install a specific (default: newest) core
#   scripts/core.sh version                pinned version + whether the binaries match
#   scripts/core.sh verify                 verify the binaries against core.properties (CI)
#   scripts/core.sh hashes                 print the sha256 of the binaries on disk
#
# The binaries are downloaded from the upstream release
# (`hysteria-android-<arch>`) and verified against that release's `hashes.txt`.
# They are NOT committed to git; `core/core.properties` pins the version plus
# the checksums and is exposed to the app as `BuildConfig.CORE_VERSION`.
#
# Env:
#   HY2_CORE_TAG   override the pinned version (e.g. app/v2.12.3)
#   GITHUB_TOKEN   optional, raises the GitHub API rate limit when resolving "latest"
set -euo pipefail

HY2_REPO="apernet/hysteria"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$ROOT/core/core.properties"
LIBDIR="$ROOT/app/src/main/jniLibs"

# jniLibs ABI -> upstream asset suffix (hysteria-android-<suffix>)
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)

TMP=""
trap 'rm -rf "${TMP:-}"' EXIT

# Release assets occasionally 5xx (GitHub CDN hiccups) — retry instead of
# failing the whole CI build on a transient error.
CURL=(curl --fail --silent --show-error --location
      --retry 5 --retry-delay 3 --retry-all-errors
      --connect-timeout 20)

# Upstream has been renamed once already (apernet -> HyNetworks); resolve the
# canonical owner/repo from the web redirect so asset URLs are always current.
canonical_repo() {
  local eff=""
  eff="$("${CURL[@]}" -o /dev/null -w '%{url_effective}' --max-time 20 \
        "https://github.com/$HY2_REPO" 2>/dev/null || true)"
  eff="${eff%/}"
  case "$eff" in
    https://github.com/*/*) printf '%s\n' "${eff#https://github.com/}" ;;
    *) printf '%s\n' "$HY2_REPO" ;;
  esac
}

suffix() {
  case "$1" in
    arm64-v8a)   echo arm64 ;;
    armeabi-v7a) echo armv7 ;;
    x86_64)      echo amd64 ;;
    x86)         echo 386 ;;
    *) echo "unknown ABI: $1" >&2; return 1 ;;
  esac
}

sha_of() { sha256sum "$1" | cut -d' ' -f1; }

prop() { sed -n "s/^$1=//p" "$PROPS" | head -n1; }

normalize_tag() {
  case "$1" in
    app/*) printf '%s\n' "$1" ;;
    v*)    printf 'app/%s\n' "$1" ;;
    *)     printf 'app/v%s\n' "$1" ;;
  esac
}

resolve_latest() {
  local tag
  # Follow the /releases/latest redirect: no REST API needed, and it keeps
  # working when upstream renames or moves the repository.
  tag="$("${CURL[@]}" -o /dev/null -w '%{url_effective}' --max-time 20 \
        "https://github.com/$HY2_REPO/releases/latest" | sed -n 's#.*/tag/##p' | head -n1)"
  case "$tag" in
    app/v*) echo "$tag"; return 0 ;;
  esac
  local auth=()
  [ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  tag="$(curl -fsSL --max-time 20 "${auth[@]}" -H 'Accept: application/vnd.github+json' \
        "https://api.github.com/repos/$HY2_REPO/releases/latest" \
        | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -n1)"
  case "$tag" in
    app/v*) echo "$tag" ;;
    *) echo "could not resolve the latest release of $HY2_REPO" >&2; return 1 ;;
  esac
}

binaries_match_manifest() {
  local abi want got
  for abi in "${ABIS[@]}"; do
    want="$(prop "sha256.$abi")";            [ -n "$want" ] || return 1
    [ -f "$LIBDIR/$abi/libhysteria.so" ] || return 1
    got="$(sha_of "$LIBDIR/$abi/libhysteria.so")"
    [ "$want" = "$got" ] || return 1
  done
}

# Upstream embeds the version in different shapes across releases (older builds
# carry a bare "v2.5.1", newer ones e.g. "app/version?v2.13.0"), so just look
# for the recorded version inside the binary.
core_contains_version() {
  [ -n "${1:-}" ] || return 1
  # Grep the binary directly: `strings | grep -q` would SIGPIPE under pipefail.
  grep -qaF -- "$1" "$LIBDIR/x86_64/libhysteria.so" 2>/dev/null
}

write_props() {
  local tag="$1" abi
  {
    echo "# Managed by scripts/core.sh — DO NOT hand-edit the sha256 values."
    echo "# Pin a core with:  scripts/core.sh upgrade app/vX.Y.Z  (or HY2_CORE_TAG)"
    echo "# Check integrity:  scripts/core.sh verify"
    echo "HYSTERIA_REPO=$HY2_REPO"
    echo "HYSTERIA_VERSION=${tag#app/}"
    echo "HYSTERIA_INSTALLED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    for abi in "${ABIS[@]}"; do
      echo "sha256.$abi=$(sha_of "$LIBDIR/$abi/libhysteria.so")"
    done
  } > "$PROPS"
}

install_tag() {
  local tag; tag="$(normalize_tag "$1")"
  local repo; repo="$(canonical_repo)"
  local base="https://github.com/$repo/releases/download/$tag"
  TMP="$(mktemp -d)"
  echo "==> downloading $repo $tag"
  "${CURL[@]}" "$base/hashes.txt" -o "$TMP/hashes.txt" \
    || { echo "could not download $base/hashes.txt (retried); upstream CDN problem?" >&2; return 1; }
  local abi name want got
  for abi in "${ABIS[@]}"; do
    name="hysteria-android-$(suffix "$abi")"
    "${CURL[@]}" "$base/$name" -o "$TMP/$name" \
      || { echo "could not download $base/$name (retried); upstream CDN problem?" >&2; return 1; }
    want="$(awk -v n="build/$name" '$2 == n { print $1 }' "$TMP/hashes.txt")"
    [ -n "$want" ] || { echo "no upstream checksum for $name in $tag" >&2; return 1; }
    got="$(sha_of "$TMP/$name")"
    [ "$want" = "$got" ] || { echo "checksum mismatch for $name: expected $want got $got" >&2; return 1; }
    install -m 0755 "$TMP/$name" "$LIBDIR/$abi/libhysteria.so"
    printf '  %-12s <- %s (sha256 verified)\n' "$abi" "$name"
  done
  write_props "$tag"
  echo "==> core ${tag#app/} installed"
}

cmd_fetch() {
  local want="${HY2_CORE_TAG:-$(prop HYSTERIA_VERSION)}"
  [ -n "$want" ] || { echo "core/core.properties has no HYSTERIA_VERSION" >&2; return 1; }
  local tag; tag="$(normalize_tag "$want")"
  if [ "$(prop HYSTERIA_VERSION)" = "${tag#app/}" ] && binaries_match_manifest; then
    echo "core ${tag#app/} already installed"
    return 0
  fi
  install_tag "$tag"
}

cmd_latest() {
  local tag; tag="$(resolve_latest)" || return 1
  echo "${tag#app/}"
}

cmd_upgrade() {
  local want="${1:-latest}"
  if [ "$want" = "latest" ] && [ -n "${HY2_CORE_TAG:-}" ]; then want="$HY2_CORE_TAG"; fi
  local tag
  if [ "$want" = "latest" ]; then tag="$(resolve_latest)"; else tag="$(normalize_tag "$want")"; fi
  install_tag "$tag"
  cmd_version
}

cmd_version() {
  local ver; ver="$(prop HYSTERIA_VERSION)"
  local state="unknown"
  if [ -n "$ver" ]; then
    if core_contains_version "$ver"; then state="matches manifest"; else state="DOES NOT match manifest"; fi
  fi
  echo "recorded core : ${ver:-<missing>}  ($HY2_REPO)"
  echo "binary        : ${state}"
}

cmd_hashes() {
  local abi
  for abi in "${ABIS[@]}"; do
    printf '%-12s %s\n' "$abi" "$(sha_of "$LIBDIR/$abi/libhysteria.so")"
  done
}

cmd_verify() {
  local bad=0 abi f want got
  for abi in "${ABIS[@]}"; do
    f="$LIBDIR/$abi/libhysteria.so"
    if [ ! -f "$f" ]; then echo "MISSING $f (run: scripts/core.sh fetch)" >&2; bad=1; continue; fi
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
  local ver; ver="$(prop HYSTERIA_VERSION)"
  if [ -n "$ver" ] && ! core_contains_version "$ver"; then
    echo "MISMATCH version: core.properties=$ver but the binary does not contain it" >&2; bad=1
  fi
  if [ "$bad" -ne 0 ]; then
    echo "core verification FAILED — run 'scripts/core.sh fetch' or 'scripts/core.sh upgrade <tag>'." >&2
    return 1
  fi
  echo "core verification passed (${ver:-unknown})"
}

case "${1:-}" in
  fetch)   cmd_fetch ;;
  latest)  cmd_latest ;;
  upgrade) shift; cmd_upgrade "${1:-latest}" ;;
  version) cmd_version ;;
  verify)  cmd_verify ;;
  hashes)  cmd_hashes ;;
  *)
    sed -n '3,10p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
