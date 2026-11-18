#!/usr/bin/env bash
# usage: scripts/ci-sdkmanager.sh <package>...
# Installs Android SDK packages, retrying a corrupted or interrupted download
# ("Error reading Zip content", resets, timeouts); a release run lost its API
# 36 job to one truncated NDK zip. Any other failure is returned at once.
set -uo pipefail
log=$(mktemp)
trap 'rm -f "$log"' EXIT
for attempt in 1 2 3; do
  sdkmanager "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  # sdkmanager can exit 0 after a failed package: trust the warning too.
  if [[ $status == 0 ]] && ! grep -q 'An error occurred while preparing SDK package' "$log"; then
    exit 0
  fi
  if [[ $attempt == 3 ]] || ! grep -Eq 'Error reading Zip content|error occurred while preparing|Connection reset|timed out|Failed to download|IOException' "$log"; then
    exit "${status/#0/1}"
  fi
  echo "Transient SDK download error; retrying in $((attempt * 20)) seconds (retry $attempt of 2)" >&2
  sleep $((attempt * 20))
done
