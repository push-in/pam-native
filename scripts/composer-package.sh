#!/usr/bin/env bash

set -euo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
package_name=pushinbr/pam-native
package_path=packages/native

fail() {
    printf 'composer-package: %s\n' "$*" >&2
    exit 1
}

validate_tag() {
    local release_tag=$1

    [[ ${release_tag} =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] ||
        fail "release tag must use stable SemVer with a v prefix"

    local workspace_version
    workspace_version=$(
        sed -n 's/^version = "\([^"]*\)"$/\1/p' "${repository_root}/Cargo.toml" |
            head -n 1
    )
    [[ v${workspace_version} == "${release_tag}" ]] ||
        fail "${release_tag} does not match Cargo.toml ${workspace_version}"

    local sdk_version
    sdk_version=$(
        sed -n "s/.*SDK_VERSION = '\\([^']*\\)'.*/\\1/p" \
            "${repository_root}/${package_path}/src/Protocol.php"
    )
    [[ v${sdk_version} == "${release_tag}" ]] ||
        fail "${release_tag} does not match PHP SDK ${sdk_version}"
}

split_package() {
    local source_ref=$1

    git -C "${repository_root}" rev-parse --verify "${source_ref}^{commit}" >/dev/null
    git -C "${repository_root}" subtree split \
        --prefix="${package_path}" \
        "${source_ref}"
}

verify_split() (
    local split_ref=$1
    local temporary_directory
    temporary_directory=$(mktemp -d)
    trap 'rm -rf -- "${temporary_directory}"' EXIT

    git -C "${repository_root}" archive "${split_ref}" |
        tar -x -C "${temporary_directory}"

    [[ -f ${temporary_directory}/composer.json ]] ||
        fail "split is missing composer.json"
    [[ -f ${temporary_directory}/LICENSE ]] ||
        fail "split is missing LICENSE"
    [[ -f ${temporary_directory}/README.md ]] ||
        fail "split is missing README.md"
    [[ $(jq -er '.name' "${temporary_directory}/composer.json") == "${package_name}" ]] ||
        fail "split declares the wrong Composer package"

    composer validate \
        --strict \
        --no-check-lock \
        --no-interaction \
        "${temporary_directory}/composer.json" >/dev/null

    if find "${temporary_directory}" \
        \( -name vendor -o -name .env -o -name .git \) \
        -print -quit |
        grep -q .; then
        fail "split contains generated or private files"
    fi
)

case "${1:-}" in
    validate-tag)
        [[ $# -eq 2 ]] || fail "usage: $0 validate-tag <vX.Y.Z>"
        validate_tag "$2"
        ;;
    split)
        [[ $# -eq 2 ]] || fail "usage: $0 split <git-ref>"
        split_package "$2"
        ;;
    verify-split)
        [[ $# -eq 2 ]] || fail "usage: $0 verify-split <git-ref>"
        verify_split "$2"
        ;;
    *)
        fail "usage: $0 {validate-tag|split|verify-split}"
        ;;
esac
