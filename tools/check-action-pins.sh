#!/usr/bin/env bash
# Fails if any `uses:` in a workflow references a mutable tag or branch.
# A job that holds signing secrets must run only code pinned to a commit SHA.
#
#   ./tools/check-action-pins.sh [workflow-dir]   (default .github/workflows)

set -euo pipefail

dir="${1:-.github/workflows}"
[ -d "$dir" ] || { echo "no such dir: $dir" >&2; exit 2; }
bad=$(grep -rnE '^\s*(-\s+)?uses:' "$dir" \
  | grep -vE 'uses:\s+(\./|docker://)' \
  | grep -vE 'uses:\s+[^@[:space:]]+@[0-9a-f]{40}(\s|$)' || true)

if [ -n "$bad" ]; then
  echo "Actions not pinned to a full commit SHA:" >&2
  echo "$bad" >&2
  exit 1
fi
echo "all actions pinned to commit SHAs"
