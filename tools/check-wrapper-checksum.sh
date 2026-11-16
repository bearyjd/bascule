#!/usr/bin/env bash
# Fails unless the Gradle wrapper pins a distributionSha256Sum, so a swapped
# distribution zip is rejected instead of silently executed.
set -euo pipefail

props="$(dirname "$0")/../gradle/wrapper/gradle-wrapper.properties"
sum="$(sed -n 's/^distributionSha256Sum=//p' "$props")"

if [[ ! "$sum" =~ ^[0-9a-f]{64}$ ]]; then
  echo "FAIL: $props has no valid distributionSha256Sum (got '${sum}')" >&2
  exit 1
fi
echo "ok: distributionSha256Sum is pinned"
