#!/usr/bin/env bash
# Patch the stock coreapps omod so the home page patient search results table
# (coreapps PatientSearchWidget) grows a filter button on the Gender and
# Reg Facility column headers.
#
# Why a patch: PatientSearchWidget builds its columns from server-side config
# (findPatientColumnConfig in pih-config-sierraLeone-falaba.json) and exposes no
# extension point for column headers -- the only one it has,
# coreapps.patientSearch.extension, renders fragments inside the search form,
# not into the results table. The table itself is a DataTables 1.9.4 instance
# built entirely in JavaScript, and the DataTables API is reachable from the
# DOM, so the feature is added as an enhancement layer instead of forking the
# widget: this script only inserts two ui.include lines into the fragment GSP
# and ships the two resources they point at. patientSearchWidget.js itself is
# untouched, and a future bump of coreapps only has to re-apply the insertion.
#
# The new resources are tracked under openmrs-image/coreapps/; the GSP edit is
# a marker-guarded insertion so re-running this stays idempotent and the patch
# itself is visible in the two inserted lines rather than in a 200-line overlay.
#
# The result is written to openmrs-image/coreapps-<version>.omod, which
# seed-distro-maven-repo.sh and build-distro.sh install in place of the stock
# artifact.
#
# Bumping coreapps means re-checking that the sanity check below still matches
# the upstream fragment; it fails loudly if it does not.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_ID="coreapps"
VERSION="4.0.0-SNAPSHOT"
STAGE="$ROOT_DIR/distro/template/modules"
OUT="$ROOT_DIR/openmrs-image/${MODULE_ID}-${VERSION}.omod"
OVERLAY="$ROOT_DIR/openmrs-image/$MODULE_ID"

MARKER="patientSearchFilters"
GSP="web/module/fragments/patientsearch/patientSearchWidget.gsp"
ADD_JS='    ui.includeJavascript("coreapps", "patientsearch/patientSearchFilters.js")'
ADD_CSS='    ui.includeCss("coreapps", "patientsearch/patientSearchFilters.css")'
RESOURCES=(
  "web/module/resources/scripts/patientsearch/patientSearchFilters.js"
  "web/module/resources/styles/patientsearch/patientSearchFilters.css"
)

STOCK="$STAGE/${MODULE_ID}-${VERSION}.omod"
if [[ ! -f "$STOCK" ]]; then
  # The seed script is skipped once content/build exists, so distro/template
  # may not have been materialized yet. Fall back to whatever ~/.m2 holds.
  STOCK="$(find "$HOME/.m2/repository/org/openmrs/module/${MODULE_ID}-omod" \
    -name "${MODULE_ID}-omod-${VERSION}.omod" -print -quit 2>/dev/null || true)"
fi
if [[ -z "$STOCK" || ! -f "$STOCK" ]]; then
  echo "ERROR: stock ${MODULE_ID}-${VERSION}.omod not found in $STAGE or ~/.m2;" >&2
  echo "       run scripts/seed-distro-maven-repo.sh first" >&2
  exit 1
fi

for f in "${RESOURCES[@]}"; do
  [[ -f "$OVERLAY/$f" ]] || { echo "ERROR: missing overlay $OVERLAY/$f" >&2; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Extracting $STOCK"
( cd "$WORK" && unzip -q -o "$STOCK" )

PAGE="$WORK/$GSP"
[[ -f "$PAGE" ]] || { echo "ERROR: $GSP not found in the stock omod" >&2; exit 1; }

# Guard against patching a coreapps the overlay was not written against.
if ! grep -q 'patientSearchWidget.js' "$PAGE"; then
  echo "ERROR: $GSP does not look like the coreapps patient search fragment." >&2
  echo "       Re-check openmrs-image/$MODULE_ID against coreapps $VERSION." >&2
  exit 1
fi

if grep -q "$MARKER" "$PAGE"; then
  echo "    note: source is already patched, refreshing the includes"
else
  echo "==> Inserting filter includes into $GSP"
  # Anchor on the line that pulls in the widget's own JavaScript so the new
  # stylesheet loads before it and the script loads with the rest of the widget.
  python3 - "$PAGE" "$ADD_CSS" "$ADD_JS" <<'PY'
import re, sys

path, add_css, add_js = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, encoding='utf-8') as fh:
    lines = fh.read().split('\n')

anchor = '    ui.includeJavascript("coreapps", "patientsearch/patientSearchWidget.js")'
if anchor not in lines:
    sys.exit('ERROR: could not find the widget include anchor in %s' % path)

insert_at = lines.index(anchor)
lines[insert_at:insert_at] = [add_css, add_js]

with open(path, 'w', encoding='utf-8') as fh:
    fh.write('\n'.join(lines))
print('    inserted after line %d' % (insert_at + 1))
PY
fi

echo "==> Adding filter resources -> $MODULE_ID-$VERSION.omod"
for f in "${RESOURCES[@]}"; do
  mkdir -p "$WORK/$(dirname "$f")"
  cp "$OVERLAY/$f" "$WORK/$f"
done

rm -f "$OUT"
mkdir -p "$(dirname "$OUT")"
# Normalize mtimes and feed the entries in a stable order so repeated runs
# produce a byte-identical omod (otherwise every rebuild churns the committed
# binary for content that did not change).
find "$WORK" -exec touch -h -t 197001010000 {} +
( cd "$WORK" && find . -print | LC_ALL=C sort | zip -q -X -@ "$OUT" )

echo "==> Done: $OUT ($(stat -c%s "$OUT") bytes)"
