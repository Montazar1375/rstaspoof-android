#!/usr/bin/env bash
# Build libv2ray.aar from AndroidLibXrayLite submodule, or download release if build tools missing.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$ROOT/app/libs"
SUBMODULE="$ROOT/AndroidLibXrayLite"
VERSION="${LIBV2RAY_VERSION:-v26.6.1}"

mkdir -p "$LIBS_DIR"

if [[ -f "$LIBS_DIR/libv2ray.aar" ]] && [[ "${FORCE_LIBV2RAY_BUILD:-0}" != "1" ]]; then
  echo "libv2ray.aar already present at $LIBS_DIR/libv2ray.aar"
  exit 0
fi

if command -v gomobile >/dev/null 2>&1 && [[ -d "$SUBMODULE" ]]; then
  echo "Building libv2ray.aar from AndroidLibXrayLite..."
  pushd "$SUBMODULE" >/dev/null
  mkdir -p assets data
  if [[ -f gen_assets.sh ]]; then
    bash gen_assets.sh download || true
    cp -f data/*.dat assets/ 2>/dev/null || true
  fi
  gomobile init 2>/dev/null || true
  go mod tidy
  gomobile bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' -o "$LIBS_DIR/libv2ray.aar" ./
  popd >/dev/null
  echo "Built $LIBS_DIR/libv2ray.aar"
  exit 0
fi

echo "gomobile not available; downloading release $VERSION..."
curl -fsSL -o "$LIBS_DIR/libv2ray.aar" \
  "https://github.com/2dust/AndroidLibXrayLite/releases/download/${VERSION}/libv2ray.aar"
echo "Downloaded $LIBS_DIR/libv2ray.aar"
