#!/usr/bin/env bash
set -euo pipefail

mode=${1:-release}
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
target_dir=${CARGO_TARGET_DIR:-"${root}/target"}
benchmark="${target_dir}/performance/examples/benchmark"

find_llvm_profdata() {
  if command -v llvm-profdata >/dev/null 2>&1; then
    command -v llvm-profdata
    return
  fi
  local sysroot
  sysroot=$(rustc --print sysroot)
  find "${sysroot}" -type f -name llvm-profdata -perm -u+x -print -quit
}

build_engine() {
  cargo build --locked --profile performance --manifest-path "${root}/Cargo.toml" \
    --package pam-native-engine --example benchmark
}

case "${mode}" in
  release)
    build_engine
    ;;
  pgo)
    profdata=$(find_llvm_profdata)
    if test -z "${profdata}"; then
      echo 'llvm-profdata is required; install the Rust llvm-tools-preview component.' >&2
      exit 69
    fi
    profile_root=$(mktemp -d "${TMPDIR:-/tmp}/pam-native-pgo.XXXXXX")
    trap 'rm -rf -- "${profile_root}"' EXIT
    LLVM_PROFILE_FILE="${profile_root}/native-%m-%p.profraw" \
      RUSTFLAGS="-Cprofile-generate=${profile_root}" build_engine
    "${benchmark}" --check
    "${profdata}" merge -o "${profile_root}/native.profdata" "${profile_root}"/*.profraw
    RUSTFLAGS="-Cprofile-use=${profile_root}/native.profdata -Cllvm-args=-pgo-warn-missing-function" \
      build_engine
    ;;
  *)
    echo 'usage: scripts/build-performance.sh [release|pgo]' >&2
    exit 64
    ;;
esac
