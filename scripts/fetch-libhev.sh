#!/usr/bin/env bash
# Extract libhev-socks5-tunnel.so from a published v2rayNG APK (arm64-v8a).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
VERSION="${V2RAYNG_VERSION:-2.2.1}"

mkdir -p "$JNI_OUT"
if [[ -f "$JNI_OUT/libhev-socks5-tunnel.so" ]] && [[ "${FORCE_HEV_FETCH:-0}" != "1" ]]; then
  echo "libhev-socks5-tunnel.so already present"
  exit 0
fi

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

APK="$TMPDIR/v2rayng.apk"
TAG="$VERSION"
TAG="${TAG#v}"

# Try common release asset names (fdroid arm64 / universal).
for name in \
  "v2rayNG_${TAG}-fdroid_arm64-v8a.apk" \
  "v2rayNG_${TAG}_universal.apk" \
  "v2rayNG_${TAG}_arm64-v8a.apk"; do
  url="https://github.com/2dust/v2rayNG/releases/download/${TAG}/${name}"
  if curl -fsSL -o "$APK" "$url"; then
    echo "Downloaded $url"
    break
  fi
  rm -f "$APK"
done

if [[ ! -f "$APK" ]]; then
  echo "Could not download v2rayNG APK for release ${TAG}."
  echo "Set V2RAYNG_VERSION (e.g. 2.2.1) or run ./scripts/build-hevtun.sh with NDK_HOME."
  exit 1
fi

unzip -jo "$APK" "lib/arm64-v8a/libhev-socks5-tunnel.so" -d "$JNI_OUT"
echo "Extracted $JNI_OUT/libhev-socks5-tunnel.so"
