#!/bin/sh
set -eu

case "${1:-plain}" in
  plain)
    if [ "${EXAMPLE_MESSAGE-}" = "doppler-example" ] && [ "${EXAMPLE_NUMBER-}" = "42" ]; then
      printf '%s\n' 'PASS: both Doppler example variables reached the container.'
      exit 0
    fi
    ;;
  custom)
    if [ "${APP_MESSAGE-}" = "doppler-example" ] && [ "${APP_NUMBER-}" = "42" ] &&
       [ "${EXAMPLE_MESSAGE+x}" != x ] && [ "${EXAMPLE_NUMBER+x}" != x ]; then
      printf '%s\n' 'PASS: both mapped variables reached the container without their original names.'
      exit 0
    fi
    ;;
  *)
    printf '%s\n' 'FAIL: use plain or custom mode.' >&2
    exit 2
    ;;
esac

printf '%s\n' 'FAIL: example variables are missing, unexpected, or still present under their original names.' >&2
exit 1