#!/usr/bin/env bash
# Regenerate openmrs-image/pihcore-src/CustomAppLoaderFactory.java from the
# pihcore omod, dropping the visit actions whose forms content/exclusions.txt
# retires.
#
# Run this after bumping pihcore, then commit the regenerated source together
# with the omod bump: patch-pihcore-visit-actions.sh compiles this file into
# pihcore-api, so a stale regeneration ships an unpatched omod.
#
# The deletions are derived from content/exclusions.txt rather than a hardcoded
# list of action ids, so adding a form to the exclusion list is enough to retire
# its visit action. A registration is dropped when the form it links to is
# excluded; registrations for forms that are merely absent from the built
# htmlforms directory are left alone, because pihcore carries those on purpose
# and the visit UI already tolerates them.
#
# Requires network access on first run to fetch the CFR decompiler.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OMOD="$ROOT_DIR/openmrs-image/pihcore-2.2.0-SNAPSHOT.omod"
API_JAR_NAME="pihcore-api-2.2.0-SNAPSHOT.jar"
EXCLUSIONS="$ROOT_DIR/content/exclusions.txt"
DEST="$ROOT_DIR/openmrs-image/pihcore-src"
CFR_VERSION="0.152"
TOOLS="$ROOT_DIR/.build/tools"
CFR="$TOOLS/cfr-$CFR_VERSION.jar"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [[ ! -f "$OMOD" ]]; then
  echo "ERROR: $OMOD not found" >&2
  exit 1
fi
if [[ ! -f "$EXCLUSIONS" ]]; then
  echo "ERROR: $EXCLUSIONS not found" >&2
  exit 1
fi

mkdir -p "$TOOLS"
if [[ ! -f "$CFR" ]]; then
  echo "==> Fetching CFR $CFR_VERSION"
  curl -fsSL -o "$CFR" \
    "https://repo1.maven.org/maven2/org/benf/cfr/$CFR_VERSION/cfr-$CFR_VERSION.jar"
fi

echo "==> Decompiling CustomAppLoaderFactory"
unzip -o -q -j "$OMOD" "lib/$API_JAR_NAME" -d "$WORK"
sync
unzip -o -q -j "$WORK/$API_JAR_NAME" \
  'org/openmrs/module/pihcore/apploader/CustomAppLoaderFactory*.class' -d "$WORK/classes"
sync
java -jar "$CFR" "$WORK/classes/CustomAppLoaderFactory.class" --outputdir "$WORK/src" >/dev/null
sync

DECOMPILED="$WORK/src/org/openmrs/module/pihcore/apploader/CustomAppLoaderFactory.java"
if [[ ! -f "$DECOMPILED" ]]; then
  echo "ERROR: decompile produced no CustomAppLoaderFactory.java" >&2
  exit 1
fi

echo "==> Dropping visit actions that link to a retired form"
python3 "$ROOT_DIR/scripts/retire-pihcore-visit-actions.py" \
  "$DECOMPILED" "$EXCLUSIONS"

mkdir -p "$DEST"
cp "$DECOMPILED" "$DEST/CustomAppLoaderFactory.java"
echo "==> Wrote $DEST/CustomAppLoaderFactory.java"
