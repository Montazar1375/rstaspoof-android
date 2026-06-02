#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
JNI_DIR="app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="app/src/main/assets"
mkdir -p "$JNI_DIR" "$ASSETS_DIR"
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -ldflags="-s -w" -o "$JNI_DIR/librstaspoof.so" rstaspoof.go
cp "$JNI_DIR/librstaspoof.so" "$ASSETS_DIR/rstaspoof"
echo "Built $JNI_DIR/librstaspoof.so"
echo "Copied to $ASSETS_DIR/rstaspoof (codeCache fallback)"
file "$JNI_DIR/librstaspoof.so" 2>/dev/null || true
