#!/usr/bin/env bash

set -o pipefail

gradle_log="$(mktemp "$RUNNER_TEMP/gradle.XXXXXX")"
trap 'rm -f "$gradle_log"' EXIT

printf 'Running: ./gradlew'
printf ' %q' "$@"
printf '\n'

for attempt in 1 2 3; do
  if ./gradlew "$@" 2>&1 | tee "$gradle_log"; then
    exit 0
  fi
  if ! grep -Fq 'Received status code 429 from server: Too Many Requests' "$gradle_log"; then
    echo "Gradle failed for a reason other than HTTP 429 Too Many Requests; not retrying."
    exit 1
  fi
  if [[ "$attempt" -eq 3 ]]; then
    exit 1
  fi
  echo "Received HTTP 429 Too Many Requests; retrying in $((attempt * 30)) seconds."
  sleep "$((attempt * 30))"
done
