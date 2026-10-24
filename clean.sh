#!/usr/bin/env bash
# List build artifacts; pass --apply to delete only ignored local outputs.
set -euo pipefail
cd "$(dirname "$0")"
for path in .gradle .kotlin build app/build probe/build; do
  [ -e "$path" ] || continue
  git check-ignore -q "$path/" || { echo "Not ignored: $path" >&2; exit 1; }
  du -sh "$path"
  if [ "${1:-}" = --apply ]; then rm -rf -- "$path"; fi
done
