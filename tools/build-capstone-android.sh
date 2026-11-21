#!/usr/bin/env bash
# Rebuilds android/src/main/jniLibs/arm64-v8a/libcapstone.so.
#
# The Capstone build shipped inside the Java binding's jar is linked against
# glibc, which Android's bionic cannot load, so the app carries its own build.
# The result is committed, meaning CI needs no NDK; run this only to move to a
# new Capstone version.
#
# Version matters: the binding speaks the 4.x ABI, and the structures it maps
# are not the same in 5.x.
set -euo pipefail

VERSION="${VERSION:-4.0.2}"
ABI="${ABI:-arm64-v8a}"
API="${API:-26}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/27.2.12479018}"
CMAKE="${CMAKE:-${ANDROID_HOME:-$HOME/Android/Sdk}/cmake/3.22.1/bin/cmake}"
NINJA="${NINJA:-${ANDROID_HOME:-$HOME/Android/Sdk}/cmake/3.22.1/bin/ninja}"

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

[ -d "$NDK" ] || { echo "No NDK at $NDK (set ANDROID_NDK_HOME)" >&2; exit 1; }

git clone --quiet --depth 1 --branch "$VERSION" \
  https://github.com/capstone-engine/capstone.git "$work/capstone"

# AArch64 only: the tool cannot be asked to disassemble anything else, and every
# other architecture is dead weight in the APK.
"$CMAKE" -S "$work/capstone" -B "$work/build" -GNinja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCAPSTONE_BUILD_SHARED=ON -DCAPSTONE_BUILD_STATIC=OFF \
  -DCAPSTONE_BUILD_TESTS=OFF -DCAPSTONE_BUILD_CSTOOL=OFF \
  -DCAPSTONE_ARM64_SUPPORT=ON \
  -DCAPSTONE_ARM_SUPPORT=OFF -DCAPSTONE_M68K_SUPPORT=OFF -DCAPSTONE_MIPS_SUPPORT=OFF \
  -DCAPSTONE_PPC_SUPPORT=OFF -DCAPSTONE_SPARC_SUPPORT=OFF -DCAPSTONE_SYSZ_SUPPORT=OFF \
  -DCAPSTONE_XCORE_SUPPORT=OFF -DCAPSTONE_X86_SUPPORT=OFF -DCAPSTONE_TMS320C64X_SUPPORT=OFF \
  -DCAPSTONE_M680X_SUPPORT=OFF -DCAPSTONE_EVM_SUPPORT=OFF > /dev/null

"$CMAKE" --build "$work/build" > /dev/null

out="$root/android/src/main/jniLibs/$ABI/libcapstone.so"
mkdir -p "$(dirname "$out")"
cp "$work/build/libcapstone.so" "$out"

echo "Wrote $out"
readelf -d "$out" | grep -E 'NEEDED|SONAME'
echo "If any NEEDED line says libc.so.6, that is a glibc build and will not load on Android."
