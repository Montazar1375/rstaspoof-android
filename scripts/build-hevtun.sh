#!/usr/bin/env bash
# Build libhev-socks5-tunnel.so for Android (arm64-v8a by default).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEV_DIR="$ROOT/hev-socks5-tunnel"
JNI_OUT="$ROOT/app/src/main/jniLibs"
ABI="${HEV_ABI:-arm64-v8a}"

if [[ -z "${NDK_HOME:-}" ]]; then
  if [[ -f "$ROOT/local.properties" ]]; then
    SDK_DIR="$(grep '^sdk.dir=' "$ROOT/local.properties" | cut -d= -f2- | sed 's/\\:/:/g')"
    if [[ -n "$SDK_DIR" && -d "$SDK_DIR/ndk" ]]; then
      NDK_HOME="$(ls -d "$SDK_DIR/ndk/"* 2>/dev/null | sort -V | tail -1)"
      export NDK_HOME
    fi
  fi
fi

if [[ -z "${NDK_HOME:-}" || ! -d "$NDK_HOME" ]]; then
  echo "NDK_HOME not set and could not detect NDK. Set NDK_HOME or install NDK via Android Studio."
  echo "Skipping hev build; download a prebuilt libhev-socks5-tunnel.so into app/src/main/jniLibs/arm64-v8a/"
  exit 0
fi

if [[ ! -d "$HEV_DIR" ]]; then
  echo "hev-socks5-tunnel submodule missing. Run: git submodule update --init hev-socks5-tunnel"
  exit 1
fi

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

mkdir -p "$TMPDIR/jni"
echo 'include $(call all-subdir-makefiles)' > "$TMPDIR/jni/Android.mk"
ln -sf "$HEV_DIR" "$TMPDIR/jni/hev-socks5-tunnel"

pushd "$TMPDIR" >/dev/null
"$NDK_HOME/ndk-build" \
  NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=jni/Android.mk \
  "APP_ABI=$ABI" \
  APP_PLATFORM=android-24 \
  NDK_LIBS_OUT="$TMPDIR/libs" \
  NDK_OUT="$TMPDIR/obj" \
  "APP_CFLAGS=-O3 -DPKGNAME=com/v2ray/ang/service" \
  "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"

mkdir -p "$JNI_OUT/$ABI"
cp -f "$TMPDIR/libs/$ABI/libhev-socks5-tunnel.so" "$JNI_OUT/$ABI/"
popd >/dev/null

echo "Built $JNI_OUT/$ABI/libhev-socks5-tunnel.so"
