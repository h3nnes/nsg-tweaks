#!/bin/sh
# Builds the nsgmod-diagd helper binaries for both ABIs and copies them into
# the module assets. Requires an Android NDK; adjust NDK below if needed.
#
# Licensing: rrc_csi.c/.h and rrc_nr_gen.c/.h are vendored from
# qcom-band-menu-diag-dci (GPL-3); including them makes the helper binary a
# GPL-3 work (the LSPosed module itself remains MIT — the helper ships as a
# separate asset binary).
set -e
NDK="${NDK:-$HOME/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin}"
DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$DIR/../app/src/main/assets/helper"
SRC="$DIR/nsgmod-diagd.c $DIR/rrc_csi.c $DIR/rrc_nr_gen.c"

# bionic names the tm_gmtoff global 'timezone' (glibc: '_timezone')
"$NDK/aarch64-linux-android29-clang" -O2 -Wall -D_timezone=timezone -o "$DIR/nsgmod-diagd-arm64" $SRC -lm
"$NDK/armv7a-linux-androideabi29-clang" -O2 -Wall -D_timezone=timezone -o "$DIR/nsgmod-diagd-arm32" $SRC -lm

mkdir -p "$ASSETS/arm64-v8a" "$ASSETS/armeabi-v7a"
cp "$DIR/nsgmod-diagd-arm64" "$ASSETS/arm64-v8a/nsgmod-diagd"
cp "$DIR/nsgmod-diagd-arm32" "$ASSETS/armeabi-v7a/nsgmod-diagd"
echo "helper binaries updated in $ASSETS"
