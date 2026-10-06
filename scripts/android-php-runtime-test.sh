#!/usr/bin/env bash
#
# Runs PHP test scripts inside PAM's Android PHP runtime on a connected device
# or emulator: builds a tiny embed-SAPI host (android-php-runtime-test/runner.c)
# against the runtime's libphp.a for the device ABI, pushes the PHP SDK sources
# and runs each script there. Default: tests/device/mbstring_runtime.php, which
# proves the mb_* polyfill works on a runtime without ext-mbstring.
#
#   ANDROID_SERIAL=emulator-5554 scripts/android-php-runtime-test.sh [script...]
#
# PAM_HOME selects the PAM installation whose runtime/android is tested
# (default: the one `pam` runs from). Everything pushed is removed afterwards.

set -euo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
android_sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z ${android_sdk_root} ]]; then
    echo "ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK." >&2
    exit 1
fi
ndk_root=${ANDROID_NDK_HOME:-"${android_sdk_root}/ndk/27.1.12297006"}
case "$(uname -s)" in
    Linux) ndk_bin="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin" ;;
    Darwin) ndk_bin="${ndk_root}/toolchains/llvm/prebuilt/darwin-x86_64/bin" ;;
    *) echo "Unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
adb="${android_sdk_root}/platform-tools/adb"

pam_home=${PAM_HOME:-}
if [[ -z ${pam_home} ]]; then
    pam_binary=$(readlink -f "$(command -v pam)")
    pam_home="$(dirname "${pam_binary}")/../share/pam"
fi
runtime_id=$(python3 - "${pam_home}/runtime/catalog.json" <<'PY'
import json
import sys

catalog = json.load(open(sys.argv[1], encoding="utf-8"))
print(catalog["channels"][catalog["default"]])
PY
)

abi=$("${adb}" shell getprop ro.product.cpu.abi | tr -d '\r')
case "${abi}" in
    arm64-v8a) clang="${ndk_bin}/aarch64-linux-android26-clang" ;;
    x86_64) clang="${ndk_bin}/x86_64-linux-android26-clang" ;;
    *) echo "PAM's Android runtime supports arm64-v8a and x86_64, not ${abi}." >&2; exit 1 ;;
esac
runtime="${pam_home}/runtime/android/${runtime_id}/${abi}"
test -f "${runtime}/lib/libphp.a"

work=$(mktemp -d)
remote=/data/local/tmp/pam-php-runtime-test
cleanup() {
    rm -rf -- "${work}"
    "${adb}" shell rm -rf "${remote}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

includes=()
for directory in "" /main /Zend /TSRM /sapi/embed; do
    includes+=("-I${runtime}/include/php${directory}")
done
"${clang}" -O2 -fPIE -pie -DHAVE_CONFIG_H "${includes[@]}" \
    "${repository_root}/scripts/android-php-runtime-test/runner.c" \
    "${runtime}/lib/libphp.a" -lm -ldl -llog -o "${work}/pam-php"

mkdir -p "${work}/payload/packages/native"
cp -R "${repository_root}/packages/native/src" "${work}/payload/packages/native/src"
mkdir -p "${work}/payload/packages/native/tests"
cp -R "${repository_root}/packages/native/tests/Fixtures" "${repository_root}/packages/native/tests/device" \
    "${work}/payload/packages/native/tests/"

"${adb}" shell rm -rf "${remote}"
"${adb}" shell mkdir -p "${remote}"
"${adb}" push "${work}/payload/packages" "${remote}/" >/dev/null
"${adb}" push "${work}/pam-php" "${remote}/pam-php" >/dev/null
"${adb}" shell chmod 755 "${remote}/pam-php"

scripts=("$@")
if [[ ${#scripts[@]} -eq 0 ]]; then
    scripts=(packages/native/tests/device/mbstring_runtime.php)
fi
device=$("${adb}" shell getprop ro.product.model | tr -d '\r')
api=$("${adb}" shell getprop ro.build.version.sdk | tr -d '\r')
failed=0
for script in "${scripts[@]}"; do
    output=$("${adb}" shell "cd ${remote} && TMPDIR=${remote} ./pam-php ${script}; echo PAM_EXIT=\$?" | tr -d '\r')
    echo "${output}"
    if [[ ${output} != *"PAM_EXIT=0"* ]]; then
        failed=1
    fi
done
echo "Android PHP runtime ${runtime_id} (${abi}) on ${device}, API ${api}: $([[ ${failed} -eq 0 ]] && echo passed || echo FAILED)"
exit "${failed}"
