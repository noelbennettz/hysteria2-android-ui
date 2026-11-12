#!/usr/bin/env bash
#
# Rebuild the bundled TUN <-> SOCKS5 bridge
# (`app/src/main/jniLibs/*/libtun2socks.so`) from a pinned hev-socks5-tunnel
# release plus the patches in `native/tun2socks/`.
#
#   scripts/tun2socks.sh build [abi...]   clone, patch, ndk-build, install
#   scripts/tun2socks.sh verify           check the committed .so files (used by CI)
#   scripts/tun2socks.sh source           prepare the patched tree only (prints its path)
#   scripts/tun2socks.sh info             pinned upstream version + patch list
#
# Why the patches exist: hev-socks5-tunnel has no per-destination rules and
# Hysteria 2 cannot accelerate UDP, so QUIC sent over UDP/443 through the tunnel
# behaves badly on lossy networks. `misc.block-tcp` / `misc.block-udp` let the
# app refuse (TCP RST) or drop (UDP) matching flows before they reach the core,
# which makes clients fall back to TCP.
#
# The built .so files are committed (they are the app's JNI contract), so a
# normal build and CI never need the NDK — run this script only when a patch or
# the pinned upstream version changes.
#
# Env:
#   ANDROID_NDK_HOME   NDK to build with; otherwise the first NDK found under
#                      <workspace>/tools/android-ndk-* or $ANDROID_SDK_ROOT/ndk/*
#   TUN2SOCKS_ABIS     space separated ABI list (default: all four)
#   TUN2SOCKS_BUILD    keep the patched tree at this path (default: temp dir)
set -euo pipefail

HEV_REPO="https://github.com/heiher/hev-socks5-tunnel"
HEV_TAG="2.19.3"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBDIR="$ROOT/app/src/main/jniLibs"
PATCHDIR="$ROOT/native/tun2socks"
PATCHES=("$PATCHDIR"/00*.patch)

# The JNI symbols of the .so are bound to this class, see the README.
JNI_PKGNAME="us/leaf3stones/hy2droid/proxy"
JNI_CLSNAME="Hysteria2VpnService"

ALL_ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

TMP=""
trap 'rm -rf "${TMP:-}"' EXIT

die() { echo "tun2socks: $*" >&2; exit 1; }

find_ndk() {
  local candidate
  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    return 0
  fi
  for candidate in \
      "$ROOT/../../tools"/android-ndk-* \
      "${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"/ndk/* \
      "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/*; do
    [[ -x "$candidate/ndk-build" ]] && { printf '%s\n' "$candidate"; return 0; }
  done
  return 1
}

prepare_source() {
  local dest="$1"
  rm -rf "$dest"
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$HEV_TAG" \
    --recurse-submodules --shallow-submodules "$HEV_REPO" "$dest"
  local patch
  for patch in "${PATCHES[@]}"; do
    echo "==> applying $(basename "$patch")"
    git -C "$dest" apply --verbose "$patch" >&2
  done
  printf '%s\n' "$dest"
}

cmd_info() {
  echo "hev-socks5-tunnel $HEV_TAG"
  local patch
  for patch in "${PATCHES[@]}"; do
    echo "  $(basename "$patch")"
  done
  echo "installed:"
  local abi
  for abi in "${ALL_ABIS[@]}"; do
    local so="$LIBDIR/$abi/libtun2socks.so"
    if [[ -f "$so" ]]; then
      echo "  $abi $(stat -c %s "$so") bytes"
    else
      echo "  $abi (missing)"
    fi
  done
  # The bundled Go core is downloaded, not built here: report its page alignment
  # so a bump that breaks 16 KB kernels is visible immediately.
  local core="$LIBDIR/arm64-v8a/libhysteria.so"
  if [[ -f "$core" ]] && command -v readelf >/dev/null; then
    echo "core LOAD alignment: $(readelf -lW "$core" | awk '$1 == "LOAD" {print $NF}' | sort -u | tr '
' ' ')"
  fi
}

cmd_verify() {
  local abi missing=0
  for abi in "${ALL_ABIS[@]}"; do
    local so="$LIBDIR/$abi/libtun2socks.so"
    if [[ ! -s "$so" ]]; then
      echo "tun2socks: missing $so" >&2
      missing=1
      continue
    fi
    if command -v readelf >/dev/null; then
      # grep -c (not -q) so readelf is never killed by SIGPIPE under pipefail.
      if [[ "$(readelf -sW "$so" | grep -Ec 'JNI_OnLoad|Java_')" -eq 0 ]]; then
        echo "tun2socks: $so exports no JNI entry point" >&2
        missing=1
      fi
      # Android 15+ maps native libraries on 16 KB-page kernels; a LOAD segment
      # aligned to 4 KB cannot be mapped there.
      local worst
      worst="$(readelf -lW "$so" | awk '$1 == "LOAD" {print $NF}' | sort -u | head -1)"
      if [[ "$worst" != "0x4000" && "$worst" != "0x10000" ]]; then
        echo "tun2socks: $so LOAD segments are aligned to $worst, need 0x4000" >&2
        missing=1
      fi
    fi
    echo "ok $abi $(stat -c %s "$so") bytes"
  done
  return "$missing"
}

cmd_source() {
  TMP="$(mktemp -d)"
  local src="$TMP/hev-socks5-tunnel"
  prepare_source "$src" >/dev/null
  printf '%s\n' "$src"
  echo "prepared (no NDK needed; run 'make static' there for the host test)" >&2
  TMP=""
}

cmd_build() {
  local abis=("$@")
  [[ ${#abis[@]} -gt 0 ]] || read -r -a abis <<<"${TUN2SOCKS_ABIS:-${ALL_ABIS[*]}}"

  local ndk
  ndk="$(find_ndk)" || die "no NDK found; set ANDROID_NDK_HOME (tested with r25c)"
  [[ -x "$ndk/ndk-build" ]] || die "$ndk does not look like an NDK"

  local work
  if [[ -n "${TUN2SOCKS_BUILD:-}" ]]; then
    work="$TUN2SOCKS_BUILD"
  else
    TMP="$(mktemp -d)"
    work="$TMP"
  fi
  local src="$work/hev-socks5-tunnel"
  prepare_source "$src"

  local abi_list
  abi_list="$(IFS=, ; echo "${abis[*]}")"
  echo "==> ndk-build ($abi_list)"
  ( cd "$src" && "$ndk/ndk-build" -j"$(nproc)" \
      NDK_PROJECT_PATH="$src" \
      APP_BUILD_SCRIPT="$src/Android.mk" \
      NDK_APPLICATION_MK="$src/Application.mk" \
      APP_ABI="$abi_list" \
      APP_CFLAGS="-O3 -DPKGNAME=$JNI_PKGNAME -DCLSNAME=$JNI_CLSNAME" \
      APP_LDFLAGS="-Wl,-z,max-page-size=16384" \
      NDK_OUT="$work/obj" \
      NDK_LIBS_OUT="$work/libs" ) >&2

  local abi
  for abi in "${abis[@]}"; do
    local built="$work/libs/$abi/libhev-socks5-tunnel.so"
    [[ -f "$built" ]] || die "ndk-build produced no $abi library"
    if command -v readelf >/dev/null; then
      if [[ "$(readelf -sW "$built" | grep -Ec 'JNI_OnLoad')" -eq 0 ]]; then
        die "$abi library has no JNI_OnLoad"
      fi
    fi
    mkdir -p "$LIBDIR/$abi"
    install -m 755 "$built" "$LIBDIR/$abi/libtun2socks.so"
    echo "==> $abi -> app/src/main/jniLibs/$abi/libtun2socks.so ($(stat -c %s "$LIBDIR/$abi/libtun2socks.so") bytes)"
  done
}

case "${1:-}" in
  build)  shift; cmd_build "$@" ;;
  verify) cmd_verify ;;
  source) cmd_source ;;
  info)   cmd_info ;;
  *)
    sed -n '3,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
