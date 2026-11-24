#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=3.5.9
checksum=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
abi="${1:-arm64-v8a}"
case "$abi" in
    arm64-v8a) platform=android-arm64 ;;
    armeabi-v7a) platform=android-arm ;;
    x86) platform=android-x86 ;;
    x86_64) platform=android-x86_64 ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
esac
output="$PWD/third-party/openssl/$abi"
if [[ -s "$output/lib/libcrypto.a" && -s "$output/lib/libssl.a" && -f "$output/.revision" ]] &&
    [[ "$(cat "$output/.revision")" == "$revision" ]]; then
    exit 0
fi
ndk="${ANDROID_NDK_ROOT:-${ANDROID_HOME:?Android SDK required}/ndk/28.2.13676358}"
export ANDROID_NDK_ROOT="$ndk"
host=linux-x86_64
if [[ "$(uname -s)" == Darwin ]]; then host=darwin-x86_64; fi
export PATH="$ndk/toolchains/llvm/prebuilt/$host/bin:$PATH"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
archive="$work/openssl.tar.gz"
curl --fail --location --retry 3 --silent --show-error --max-time 120 \
    "https://github.com/openssl/openssl/releases/download/openssl-$revision/openssl-$revision.tar.gz" --output "$archive"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum --check
tar -xzf "$archive" -C "$work"
cd "$work/openssl-$revision"
perl ./Configure "$platform" -D__ANDROID_API__=29 -fPIC no-shared no-tests no-apps no-docs no-module \
    --prefix="$output" --libdir=lib
make -j "$(getconf _NPROCESSORS_ONLN)" build_libs
make install_dev
printf '%s\n' "$revision" > "$output/.revision"
