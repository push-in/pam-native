#!/usr/bin/env bash

set -euo pipefail

readonly PHP_VERSION="8.4.23"
readonly PHP_ARCHIVE="php-${PHP_VERSION}.tar.xz"
readonly PHP_URL="https://www.php.net/distributions/${PHP_ARCHIVE}"
readonly PHP_SHA256="1ab9f52008414e43bb2427ffa288eff2a4de39e1a830f957e800ba368d887a72"
readonly NDK_VERSION="27.1.12297006"

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [[ -z "${ANDROID_SDK}" ]]; then
    echo "ANDROID_HOME must point to an Android SDK installation." >&2
    exit 1
fi

readonly NDK_ROOT="${ANDROID_SDK}/ndk/${NDK_VERSION}"
readonly TOOLCHAIN="${NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -x "${TOOLCHAIN}/bin/llvm-ar" ]]; then
    echo "Android NDK ${NDK_VERSION} was not found in ${ANDROID_SDK}." >&2
    exit 1
fi

for command in curl sha256sum tar patch make; do
    command -v "${command}" >/dev/null || {
        echo "Required build command is missing: ${command}" >&2
        exit 1
    }
done

BUILD_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pam-php-android.XXXXXX")"
trap 'rm -rf -- "${BUILD_ROOT}"' EXIT

curl --fail --location --retry 3 "${PHP_URL}" --output "${BUILD_ROOT}/${PHP_ARCHIVE}"
echo "${PHP_SHA256}  ${BUILD_ROOT}/${PHP_ARCHIVE}" | sha256sum --check --strict
tar -xf "${BUILD_ROOT}/${PHP_ARCHIVE}" -C "${BUILD_ROOT}"

readonly SOURCE="${BUILD_ROOT}/php-${PHP_VERSION}"
patch --directory "${SOURCE}" --strip 1 \
    < "${SCRIPT_DIR}/patches/0001-android-fd-limit.patch"

build_abi() {
    local android_abi="$1"
    local host="$2"
    local clang_prefix="$3"
    local build="${BUILD_ROOT}/build-${android_abi}"
    local install="${BUILD_ROOT}/install-${android_abi}"
    local destination="${NATIVE_ROOT}/runtime/android/${android_abi}"

    mkdir -p "${build}"
    (
        cd "${build}"
        CC="${TOOLCHAIN}/bin/${clang_prefix}26-clang" \
        CXX="${TOOLCHAIN}/bin/${clang_prefix}26-clang++" \
        AR="${TOOLCHAIN}/bin/llvm-ar" \
        RANLIB="${TOOLCHAIN}/bin/llvm-ranlib" \
        STRIP="${TOOLCHAIN}/bin/llvm-strip" \
        CFLAGS="-O2 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections" \
        CPPFLAGS="-D__ANDROID__ -DANDROID" \
        ac_cv_c_bigendian_php=no \
        ac_cv_func_getentropy=no \
        ac_cv_func_arc4random_buf=no \
        ac_cv_func_getrandom=no \
        php_cv_sizeof_intmax_t=8 \
        "${SOURCE}/configure" \
            --build=x86_64-pc-linux-gnu \
            --host="${host}" \
            --prefix="${install}" \
            --with-pic \
            --enable-embed=static \
            --disable-cli \
            --disable-cgi \
            --disable-phpdbg \
            --disable-fpm \
            --disable-all \
            --enable-ctype \
            --enable-filter \
            --enable-session \
            --enable-tokenizer \
            --enable-phar \
            --without-pear \
            --without-iconv \
            --without-libxml \
            --without-openssl \
            --without-zlib \
            --without-curl \
            --without-sqlite3 \
            --without-pdo-sqlite \
            --disable-opcache \
            --with-pcre-jit=no

        # PHP's cross checks see part of Android's private resolver surface and
        # register dns_get_* even though the implementation requires APIs that
        # bionic does not expose. Network access is provided by Pam's native
        # HTTP module, so keep those unsupported resolver hooks out entirely.
        sed -i \
            -e '/^#define HAVE_RES_SEARCH 1$/d' \
            -e '/^#define HAVE_RES_NSEARCH 1$/d' \
            -e '/^#define HAVE_DN_SKIPNAME 1$/d' \
            -e '/^#define HAVE_RES_NDESTROY 1$/d' \
            main/php_config.h

        make -j"${PAM_BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN)}"
        make install
    )

    mkdir -p "${destination}/lib"
    rm -rf -- "${destination}/include"
    cp -a "${install}/include" "${destination}/include"
    cp "${install}/lib/libphp.a" "${destination}/lib/libphp.a"
    "${TOOLCHAIN}/bin/llvm-strip" --strip-debug "${destination}/lib/libphp.a"

    printf '%s\n' \
        '{' \
        '    "schemaVersion": 1,' \
        "    \"phpVersion\": \"${PHP_VERSION}\"," \
        "    \"sourceUrl\": \"${PHP_URL}\"," \
        "    \"sourceSha256\": \"${PHP_SHA256}\"," \
        "    \"androidAbi\": \"${android_abi}\"," \
        '    "androidApi": 26,' \
        "    \"ndkVersion\": \"${NDK_VERSION}\"" \
        '}' > "${destination}/runtime.json"
    echo "Built verified PHP ${PHP_VERSION} runtime for ${android_abi}."
}

case "${1:-all}" in
    all)
        build_abi "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android"
        build_abi "x86_64" "x86_64-linux-android" "x86_64-linux-android"
        ;;
    arm64-v8a)
        build_abi "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android"
        ;;
    x86_64)
        build_abi "x86_64" "x86_64-linux-android" "x86_64-linux-android"
        ;;
    *)
        echo "Usage: $0 [all|arm64-v8a|x86_64]" >&2
        exit 64
        ;;
esac
