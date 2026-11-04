#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
test -f android/keystore.properties || { echo "Missing android/keystore.properties; see docs/PLAY_STORE.md" >&2; exit 1; }
bash scripts/build-native.sh
for abi in arm64-v8a x86_64; do
  test -s "android/app/src/main/jniLibs/$abi/libinkstone.so" || { echo "Missing native library for $abi" >&2; exit 1; }
done
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT/.toolchain/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.toolchain/gradle}"
./android/gradlew -p android bundleRelease --no-daemon
mkdir -p releases
VERSION="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" android/app/build.gradle)"
test -n "$VERSION"
# Never overwrite an earlier build: pick the next free name (-2, -3, ...).
OUT="releases/inkstone-$VERSION-play.aab"
n=2
while test -e "$ROOT/$OUT"; do OUT="releases/inkstone-$VERSION-play-$n.aab"; n=$((n+1)); done
cp android/app/build/outputs/bundle/release/app-release.aab "$OUT"
echo "Bundle: $ROOT/$OUT"
