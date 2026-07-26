#!/usr/bin/env bash

set -euo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
runtime_tag=${PAM_RUNTIME_RELEASE_TAG:-v0.1.32}
runtime_repository=${PAM_RUNTIME_REPOSITORY:-push-in/pam}

if [[ ! ${runtime_tag} =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "PAM_RUNTIME_RELEASE_TAG must be a stable vMAJOR.MINOR.PATCH tag." >&2
    exit 1
fi

for command in cargo curl rustup sha256sum tar; do
    if ! command -v "${command}" >/dev/null 2>&1; then
        echo "Missing required command: ${command}" >&2
        exit 1
    fi
done

android_sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z ${android_sdk_root} ]]; then
    echo "ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK." >&2
    exit 1
fi

ndk_root=${ANDROID_NDK_HOME:-"${android_sdk_root}/ndk/27.0.12077973"}
if [[ ! -d ${ndk_root} ]]; then
    echo "Android NDK 27.0.12077973 was not found at ${ndk_root}." >&2
    exit 1
fi

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) ndk_host=linux-x86_64 ;;
    Darwin-x86_64) ndk_host=darwin-x86_64 ;;
    Darwin-arm64) ndk_host=darwin-x86_64 ;;
    *)
        echo "Unsupported Android build host: $(uname -s)-$(uname -m)" >&2
        exit 1
        ;;
esac

temporary_directory=$(mktemp -d)
trap 'rm -rf -- "${temporary_directory}"' EXIT

asset="pam-android-runtime.tar.gz"
release_url="https://github.com/${runtime_repository}/releases/download/${runtime_tag}"

curl --proto '=https' --tlsv1.2 --fail --silent --show-error --location \
    --output "${temporary_directory}/${asset}" \
    "${release_url}/${asset}"
curl --proto '=https' --tlsv1.2 --fail --silent --show-error --location \
    --output "${temporary_directory}/${asset}.sha256" \
    "${release_url}/${asset}.sha256"

(
    cd "${temporary_directory}"
    sha256sum --check "${asset}.sha256"
)

while IFS= read -r archive_path; do
    if [[ ${archive_path} = /* || ${archive_path} == ".." || ${archive_path} == ../* || ${archive_path} == */../* ]]; then
        echo "Unsafe path in runtime archive: ${archive_path}" >&2
        exit 1
    fi
done < <(tar -tzf "${temporary_directory}/${asset}")

tar -xzf "${temporary_directory}/${asset}" -C "${repository_root}"

rustup target add aarch64-linux-android x86_64-linux-android

ndk_bin="${ndk_root}/toolchains/llvm/prebuilt/${ndk_host}/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${ndk_bin}/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="${ndk_bin}/x86_64-linux-android26-clang"

cargo build \
    --locked \
    --manifest-path "${repository_root}/Cargo.toml" \
    --package pam-native-engine \
    --release \
    --target aarch64-linux-android
cargo build \
    --locked \
    --manifest-path "${repository_root}/Cargo.toml" \
    --package pam-native-engine \
    --release \
    --target x86_64-linux-android

test -f "${repository_root}/runtime/android/arm64-v8a/lib/libphp.a"
test -f "${repository_root}/runtime/android/x86_64/lib/libphp.a"
test -f "${repository_root}/target/aarch64-linux-android/release/libpam_native_engine.a"
test -f "${repository_root}/target/x86_64-linux-android/release/libpam_native_engine.a"

echo "Android runtime ${runtime_tag} and native engine are ready."
