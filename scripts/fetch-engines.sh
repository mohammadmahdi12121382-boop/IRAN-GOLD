#!/usr/bin/env bash
# Prepares native engines for the Android build:
#   Xray  -> app/src/main/jniLibs/arm64-v8a/libxray.so   (official release binary, pinned + SHA-256 verified)
#   hev   -> app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so + app/libs/hev-classes.jar
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
source engines.lock
JNI=app/src/main/jniLibs/arm64-v8a
mkdir -p "$JNI" app/libs
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

check() { echo "$2  $1" | sha256sum -c - >/dev/null || { echo "SHA-256 MISMATCH for $1 — aborting" >&2; exit 1; }; }

echo "[xray] downloading $XRAY_TAG"
curl -fsSL --retry 3 -o "$tmp/xray.zip" "$XRAY_URL"
check "$tmp/xray.zip" "$XRAY_SHA256"
unzip -oq "$tmp/xray.zip" xray -d "$tmp"
install -m 0755 "$tmp/xray" "$JNI/libxray.so"

echo "[hev] extracting vendored AAR"
check "$HEV_AAR" "$HEV_SHA256"
unzip -oq "$HEV_AAR" classes.jar 'jni/arm64-v8a/*' -d "$tmp/hev"
install -m 0755 "$tmp/hev/jni/arm64-v8a/libhev-socks5-tunnel.so" "$JNI/"
install -m 0644 "$tmp/hev/classes.jar" app/libs/hev-classes.jar
echo "done"
