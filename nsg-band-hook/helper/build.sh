#!/bin/sh
# Builds the nsgmod-diagd helper binaries for both ABIs and copies them into
# the module assets. Requires an Android NDK; adjust NDK below if needed.
set -e
NDK="${NDK:-$HOME/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin}"
DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$DIR/../app/src/main/assets/helper"

"$NDK/aarch64-linux-android29-clang" -O2 -Wall -Wextra -o "$DIR/nsgmod-diagd-arm64" "$DIR/nsgmod-diagd.c" -lm
"$NDK/armv7a-linux-androideabi29-clang" -O2 -Wall -Wextra -o "$DIR/nsgmod-diagd-arm32" "$DIR/nsgmod-diagd.c" -lm

mkdir -p "$ASSETS/arm64-v8a" "$ASSETS/armeabi-v7a"
cp "$DIR/nsgmod-diagd-arm64" "$ASSETS/arm64-v8a/nsgmod-diagd"
cp "$DIR/nsgmod-diagd-arm32" "$ASSETS/armeabi-v7a/nsgmod-diagd"
echo "helper binaries updated in $ASSETS"
