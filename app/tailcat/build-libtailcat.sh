#!/usr/bin/env bash
# Builds libtailcat.so (the C API of https://github.com/tailscale/tailcat, built by Go as a shared
# library: Go has no static archive mode on Android) and libtailcat_jni.so (the app's JNI bridge,
# tailcat_jni.c, linked against it) for every Android ABI the app ships. android_interfaces.go is
# compiled into libtailcat to let Tailscale's network monitor start inside the app sandbox.
#
# The output goes to app/tailcat/jniLibs/<abi>/, which the app and automotive modules package. It is
# not committed: without it the app builds and runs normally, and Tailcat pairing reports that the
# feature is not available in this build.
#
# Requirements: the Android NDK (r25 or newer) and the Go toolchain required by tailcat's go.mod.
#
# Usage: app/tailcat/build-libtailcat.sh [output directory]
#   TAILCAT_SRC      use an existing tailcat checkout instead of cloning TAILCAT_COMMIT
#   TAILCAT_COMMIT   tailcat commit to build (defaults to the pinned one below)
#   TAILCAT_ABIS     space separated subset of: arm64-v8a armeabi-v7a x86_64 x86
#   ANDROID_NDK_HOME NDK to use (defaults to the newest one in $ANDROID_HOME/ndk)

set -euo pipefail

TAILCAT_REPOSITORY="https://github.com/tailscale/tailcat.git"
TAILCAT_COMMIT="${TAILCAT_COMMIT:-e00cdde6ff787f801361ff85ed085775c775d4c5}"
TAILCAT_ABIS="${TAILCAT_ABIS:-arm64-v8a armeabi-v7a x86_64}"
# Matches androidSdk-min in gradle/libs.versions.toml.
ANDROID_API=24

HERE="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${1:-$HERE/jniLibs}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

if ! command -v go >/dev/null; then
    echo "error: the Go toolchain is required (https://go.dev/dl/)" >&2
    exit 1
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
    if [ -d "$SDK/ndk" ]; then
        ANDROID_NDK_HOME="$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -n 1)"
    fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ]; then
    echo "error: the Android NDK is required; set ANDROID_NDK_HOME" >&2
    exit 1
fi
TOOLCHAIN="$(ls -d "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/* | head -n 1)/bin"

if [ -n "${TAILCAT_SRC:-}" ]; then
    SRC="$TAILCAT_SRC"
else
    SRC="$WORK_DIR/tailcat"
    git init -q "$SRC"
    git -C "$SRC" fetch -q --depth 1 "$TAILCAT_REPOSITORY" "$TAILCAT_COMMIT"
    git -C "$SRC" checkout -q FETCH_HEAD
fi

TAGS="$(tr -d '\n' <"$SRC/build-tags.txt")"
# Adds android_interfaces.go to cmd/libtailcat without touching the tailcat sources.
OVERLAY="$WORK_DIR/overlay.json"
printf '{"Replace":{"%s":"%s"}}\n' \
    "$(cd "$SRC" && pwd)/cmd/libtailcat/zz_android_interfaces.go" "$HERE/android_interfaces.go" >"$OVERLAY"
# 16 KB ELF alignment: required on Android 15+ devices with 16 KB memory pages.
LINK_FLAGS="-Wl,-z,max-page-size=16384"

# build <abi> <GOARCH> <clang target triple>
build() {
    local abi="$1" goarch="$2" triple="$3"
    local cc="$TOOLCHAIN/${triple}${ANDROID_API}-clang"
    local goarm=""
    if [ "$goarch" = "arm" ]; then
        goarm=7
    fi
    echo "==> Building libtailcat for $abi"
    mkdir -p "$WORK_DIR/$abi" "$OUTPUT_DIR/$abi"
    (
        cd "$SRC"
        GOOS=android GOARCH="$goarch" GOARM="$goarm" CGO_ENABLED=1 CC="$cc" \
            go build -buildmode=c-shared -trimpath -tags "$TAGS" -overlay "$OVERLAY" \
            -ldflags "-s -w -extldflags '$LINK_FLAGS -Wl,-soname,libtailcat.so'" \
            -o "$OUTPUT_DIR/$abi/libtailcat.so" ./cmd/libtailcat
    )
    rm -f "$OUTPUT_DIR/$abi/libtailcat.h"
    "$cc" -shared -fPIC -O2 -Wall -Wextra -Werror \
        -I"$SRC/cmd/libtailcat" \
        -o "$OUTPUT_DIR/$abi/libtailcat_jni.so" \
        "$HERE/tailcat_jni.c" \
        -L"$OUTPUT_DIR/$abi" -ltailcat \
        $LINK_FLAGS -Wl,-soname,libtailcat_jni.so
    "$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUTPUT_DIR/$abi/libtailcat_jni.so"
}

for abi in $TAILCAT_ABIS; do
    case "$abi" in
    arm64-v8a) build "$abi" arm64 aarch64-linux-android ;;
    armeabi-v7a) build "$abi" arm armv7a-linux-androideabi ;;
    x86_64) build "$abi" amd64 x86_64-linux-android ;;
    x86) build "$abi" 386 i686-linux-android ;;
    *)
        echo "error: unknown ABI '$abi'" >&2
        exit 1
        ;;
    esac
done

cp "$SRC/LICENSE" "$OUTPUT_DIR/LICENSE-tailcat"

echo "==> Built libtailcat in $OUTPUT_DIR from tailcat $TAILCAT_COMMIT"
du -sh "$OUTPUT_DIR"/*/*.so
