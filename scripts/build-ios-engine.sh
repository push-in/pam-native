#!/usr/bin/env bash
# usage: scripts/build-ios-engine.sh <version> <output-dir>
#
# Builds PamNativeEngine.xcframework (iOS arm64 device, arm64 + x86_64
# simulator) from this checkout and packs it as the release asset
#   pam-native-ios-engine-<version>-<protocol>-<engine>.zip (+ .sha256)
# The protocol and engine fingerprints are the ones `pam mobile` checks, so a
# project downloads exactly the engine its pam-native sources describe.
set -euo pipefail

version=$1
output=$(mkdir -p "$2" && cd "$2" && pwd)
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "${root}"

sha() { shasum -a 256 "$@"; }
protocol=$(sha crates/pam-native-protocol/src/lib.rs | cut -c1-16)
engine=$(find crates/pam-native-engine/Cargo.toml crates/pam-native-engine/src \
    crates/pam-native-protocol/Cargo.toml crates/pam-native-protocol/src -type f \
    | LC_ALL=C sort | xargs shasum -a 256 | shasum -a 256 | cut -c1-16)

for target in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios; do
    rustup target add "${target}" >/dev/null
    cargo build --locked --release -p pam-native-engine --target "${target}"
done

work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT
mkdir -p "${work}/simulator"
lipo -create \
    target/aarch64-apple-ios-sim/release/libpam_native_engine.a \
    target/x86_64-apple-ios/release/libpam_native_engine.a \
    -output "${work}/simulator/libpam_native_engine.a"
mkdir -p "${work}/headers"
cp native/pam_native_engine.h "${work}/headers/"
xcodebuild -create-xcframework \
    -library target/aarch64-apple-ios/release/libpam_native_engine.a -headers "${work}/headers" \
    -library "${work}/simulator/libpam_native_engine.a" -headers "${work}/headers" \
    -output "${work}/PamNativeEngine.xcframework"
printf '%s\nprotocol=%s\nengine=%s\n' "${version}" "${protocol}" "${engine}" \
    > "${work}/PamNativeEngine.xcframework/pam-native-engine-version"

asset="pam-native-ios-engine-${version}-${protocol}-${engine}.zip"
(cd "${work}" && ditto -c -k --keepParent --norsrc PamNativeEngine.xcframework "${output}/${asset}")
(cd "${output}" && sha "${asset}" > "${asset}.sha256")
echo "${output}/${asset}"
