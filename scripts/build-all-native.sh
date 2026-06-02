#!/usr/bin/env bash
# Build or fetch all native artifacts required for a full VPN build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

"$ROOT/scripts/build-libv2ray.sh"
"$ROOT/scripts/build-android.sh"

if ! "$ROOT/scripts/build-hevtun.sh"; then
  echo "hev ndk-build skipped or failed; trying fetch-libhev.sh…"
  "$ROOT/scripts/fetch-libhev.sh" || true
fi

echo "Native deps:"
ls -la "$ROOT/app/libs/libv2ray.aar" 2>/dev/null || echo "  missing libv2ray.aar"
ls -la "$ROOT/app/src/main/jniLibs/arm64-v8a/" 2>/dev/null || echo "  jniLibs/arm64-v8a empty"
