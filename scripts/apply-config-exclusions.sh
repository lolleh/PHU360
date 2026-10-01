#!/usr/bin/env bash
# Deletes every path listed in content/exclusions.txt from a materialized config
# tree.
#
# Usage: scripts/apply-config-exclusions.sh <backend_configuration_dir>
#
# Why this is its own script and not a block inside the seed script: the seed
# script is skipped on every build after the first (build-distro.sh only
# re-copies the tracked delta over content/build/). Anything that has to be true
# of the *built* config - not just of a freshly seeded one - has to be applied
# from build-distro.sh too. When exclusions were only read at seed time, adding a
# path to exclusions.txt changed the config on a machine that re-seeded and
# silently did nothing on every other machine, which is the worst failure mode a
# "this file is deliberately not shipped" list can have.
#
# The seed script re-applies the delta then calls this; build-distro.sh does the
# same. Idempotent: a path already absent is not an error.
set -euo pipefail

EXCLUSIONS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/content/exclusions.txt"
CONFIG_DIR="${1:?usage: apply-config-exclusions.sh <backend_configuration_dir>}"

[ -d "$CONFIG_DIR" ] || { echo "ERROR: $CONFIG_DIR is not a directory" >&2; exit 1; }
[ -f "$EXCLUSIONS" ] || { echo "ERROR: $EXCLUSIONS missing" >&2; exit 1; }

removed=0
missing=0
while IFS= read -r rel || [[ -n "$rel" ]]; do
  # skip blanks and comments
  [[ -z "${rel// /}" || "$rel" == \#* ]] && continue
  if [ -e "$CONFIG_DIR/$rel" ]; then
    rm -f "$CONFIG_DIR/$rel"
    removed=$((removed + 1))
  else
    missing=$((missing + 1))
  fi
done < "$EXCLUSIONS"

# On a rebuild the files are already gone, so "removed 0" is the normal steady
# state, not a signal. The one broken case worth a warning is an exclusions
# file with no usable lines at all, which means every excluded form is silently
# shipping again.
if [ "$removed" -eq 0 ] && [ "$missing" -eq 0 ]; then
  echo "    WARNING: exclusions.txt yielded no paths - nothing was excluded." >&2
fi

# prune directories left empty by the deletions
find "$CONFIG_DIR" -type d -empty -delete 2>/dev/null || true

echo "    applied exclusions: removed $removed, already absent $missing"
