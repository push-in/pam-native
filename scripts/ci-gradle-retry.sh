#!/usr/bin/env bash
# usage: scripts/ci-gradle-retry.sh <gradle command...>
# Runs a Gradle command and retries only when the failure is a transient
# repository error (Maven Central rate limits, timeouts), never on test or
# build failures.
set -uo pipefail
log=$(mktemp)
trap 'rm -f "$log"' EXIT
for attempt in 1 2 3 4; do
  "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  [[ $status == 0 ]] && exit 0
  if [[ $attempt == 4 ]] || ! grep -Eq 'status code (429|5[0-9][0-9]) from server|Too Many Requests|Read timed out|Connect timed out|Could not GET .*(Connection reset|timed out)' "$log"; then
    exit "$status"
  fi
  echo "Transient repository error; retrying in $((attempt * 30)) seconds (retry $attempt of 3)" >&2
  sleep $((attempt * 30))
done
