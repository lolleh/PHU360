#!/usr/bin/env bash
# Drop the retired-program visit actions from the pihcore CustomAppLoaderFactory.
#
# Why a patch: these actions are not declared in any app JSON. pihcore builds
# them in Java, in CustomAppLoaderFactory.getExtensions(), which is compiled
# into pihcore-api inside the pihcore omod. Removing the forms, the encounter
# types or the config CSVs does not touch them -- they are emitted regardless of
# whether the form they link to exists, so the buttons survive a full config
# prune and then link to a missing formUuid.
#
# Each removed action was gated by a require expression, which is why they were
# not always visible. Some also required a privilege that no shipped role holds
# any more. The location tags cannot be untangled from the live workflows
# instead: "Consult Note Location" also gates consult notes, prescriptions,
# TB, VCT, PrEP, nurse consult, socio-economics and waiting-for-consult, and
# "Mental Health Location" also gates drugRehab.xml, which still ships. Editing
# CustomAppLoaderFactory removes exactly the retired actions and nothing else.
#
# Which actions go is not hardcoded here: it is derived from
# content/exclusions.txt, so a form added to the exclusion list is retired from
# the visit UI on the next build. Registrations for forms that were merely never
# shipped are left alone -- pihcore carries those on purpose and the visit UI
# already tolerates them.
#
# The replacement source is a CFR decompile of the class with those
# registrations removed, kept under openmrs-image/pihcore-src/. Regenerate it
# with scripts/regenerate-pihcore-app-loader-src.sh after a pihcore bump and
# commit the result.
#
# The checks below fail loudly rather than shipping a bad omod: the source must
# not reference a retired form, the class in the omod must reference at least
# one (otherwise pihcore was bumped and this patch is a no-op), the patched omod
# must reference none, and a set of registrations that must survive must still
# be there.
#
# scripts/build-distro.sh runs this once the omod is in place, so there is
# normally no reason to run it by hand.
set -euo pipefail
export LC_ALL=C

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB_DIR="$ROOT_DIR/distro/target/distro/web"
OMOD="$WEB_DIR/openmrs_modules/pihcore-2.2.0-SNAPSHOT.omod"
API_JAR_NAME="pihcore-api-2.2.0-SNAPSHOT.jar"
API_JAR_IN_OMOD="lib/$API_JAR_NAME"
CLASS_IN_API="org/openmrs/module/pihcore/apploader/CustomAppLoaderFactory.class"
EXCLUSIONS="$ROOT_DIR/content/exclusions.txt"
SRC="$ROOT_DIR/openmrs-image/pihcore-src/CustomAppLoaderFactory.java"
BUILD="$ROOT_DIR/.build/pihcore-visit-actions"

# Registrations that must survive the patch. They cover each kind of statement
# the class emits, and the country branches whose forms this distro does ship.
LIVE_IDS=(
  "pih.form.ancIntake"            # visit action, Liberia branch
  "pih.form.delivery"             # visit action id shared with a retired form
  "pih.form.pacuFollowup"         # visit action, MCOE component
  "pih.form.vitalsWithGlucose"    # visit action, Sierra Leone branch
  "pih.form.drugRehab"            # visit action on a shipped form
  "pih.form.labResults"           # overall action
  "pih.haiti.deathCertificate"    # overall action
  "pih.header.deathCertificate"   # fragment extension
)
LIVE_FORMS=(
  "anc-initial.xml"
  "anc-followup.xml"
  "anc-delivery.xml"
  "peds.xml"
  "pacuFollowup.xml"
  "vitalsWithGlucose.xml"
  "drugRehab.xml"
)

for path in "$OMOD" "$SRC" "$EXCLUSIONS"; do
  if [[ ! -f "$path" ]]; then
    echo "ERROR: $path not found" >&2
    case "$path" in
      "$OMOD") echo "       run scripts/build-distro.sh first" >&2 ;;
      "$SRC")  echo "       run scripts/regenerate-pihcore-app-loader-src.sh" >&2 ;;
    esac
    exit 1
  fi
done

# The retired forms, as paths relative to the htmlforms directory.
RETIRED_FORMS=()
while IFS= read -r form; do
  RETIRED_FORMS+=("$form")
done < <(sed -n 's#^pih/htmlforms/\(.*\.xml\)$#\1#p' "$EXCLUSIONS" | sort -u)

if [[ "${#RETIRED_FORMS[@]}" -eq 0 ]]; then
  echo "ERROR: no htmlforms found in $EXCLUSIONS; refusing to patch" >&2
  exit 1
fi

# The edited source must not still link to a retired form.
for form in "${RETIRED_FORMS[@]}"; do
  if grep -qF "\"$form\"" "$SRC"; then
    echo "ERROR: $SRC still links to retired form $form" >&2
    echo "       re-run scripts/regenerate-pihcore-app-loader-src.sh" >&2
    exit 1
  fi
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Read the class's string constants straight out of its constant pool. "strings"
# is not usable here: it merges neighbouring pool entries into one line, so a
# retired form name can come back with trailing junk and slip past the checks.
constants() {
  javap -v -p -cp "$1" org.openmrs.module.pihcore.apploader.CustomAppLoaderFactory \
    | sed -n 's/^ *#[0-9]* = Utf8  *//p' | sort -u
}

# Sanity check against the class actually shipping in the omod: it must still
# carry the retired registrations, otherwise this patch targets a class that
# has moved and the build would silently ship unpatched.
unzip -o -q -j "$OMOD" "$API_JAR_IN_OMOD" -d "$TMP"
sync
unzip -o -q "$TMP/$API_JAR_NAME" "$CLASS_IN_API" -d "$TMP/x"
sync
constants "$TMP/x" > "$TMP/before.constants"

FOUND=0
for form in "${RETIRED_FORMS[@]}"; do
  if grep -qxF "$form" "$TMP/before.constants"; then
    FOUND=$((FOUND + 1))
  fi
done
if [[ "$FOUND" -eq 0 ]]; then
  echo "ERROR: the pihcore in $OMOD links to none of the retired forms;" >&2
  echo "       pihcore was likely bumped. Re-check this patch against upstream." >&2
  exit 1
fi
echo "==> $FOUND/${#RETIRED_FORMS[@]} retired forms present in the omod (expected to be removed)"

echo "==> Prepare compile classpath"
rm -rf "$BUILD"
mkdir -p "$BUILD/lib" "$BUILD/mods" "$BUILD/classes"
unzip -o -q "$WEB_DIR/openmrs_core/openmrs.war" 'WEB-INF/lib/*.jar' -d "$BUILD/lib"
sync
for m in "$WEB_DIR"/openmrs_modules/*.omod; do
  unzip -o -q -j "$m" 'lib/*.jar' -d "$BUILD/mods" 2>/dev/null || true
done
sync
CP="$(find "$BUILD/lib" "$BUILD/mods" -name '*.jar' | tr '\n' ':')"

echo "==> Compile patched CustomAppLoaderFactory"
javac --release 8 -g -nowarn -proc:none -cp "$CP" -d "$BUILD/classes" "$SRC"
sync

echo "==> Patch $OMOD"
unzip -q "$OMOD" -d "$TMP/omod"
sync
WD="$TMP/api"
unzip -q "$TMP/$API_JAR_NAME" -d "$WD"
sync
for c in CustomAppLoaderFactory.class 'CustomAppLoaderFactory$ReportCategory.class'; do
  cp "$BUILD/classes/org/openmrs/module/pihcore/apploader/$c" \
     "$WD/org/openmrs/module/pihcore/apploader/$c"
done
( cd "$WD" && rm -f "$TMP/$API_JAR_NAME" && "$(command -v jar)" cf "$TMP/$API_JAR_NAME" . )
cp "$TMP/$API_JAR_NAME" "$TMP/omod/$API_JAR_IN_OMOD"
( cd "$TMP/omod" && rm -f "$OMOD" && "$(command -v zip)" -q -r -X "$OMOD" . )
sync

echo "==> Verify the patched omod"
unzip -o -q -j "$OMOD" "$API_JAR_IN_OMOD" -d "$TMP/check"
sync
unzip -o -q "$TMP/check/$API_JAR_NAME" "$CLASS_IN_API" -d "$TMP/check/x"
sync
constants "$TMP/check/x" > "$TMP/after.constants"

LEFT=0
for form in "${RETIRED_FORMS[@]}"; do
  if grep -qxF "$form" "$TMP/after.constants"; then
    echo "ERROR: patched class still links to retired form $form" >&2
    LEFT=$((LEFT + 1))
  fi
done
if [[ "$LEFT" -gt 0 ]]; then
  exit 1
fi

# A patch that drops more than the retired registrations would be a regression,
# so check that the registrations which must survive are still in the class.
for id in "${LIVE_IDS[@]}"; do
  if ! grep -qxF "$id" "$TMP/after.constants"; then
    echo "ERROR: patched class lost registration $id" >&2
    exit 1
  fi
done
for form in "${LIVE_FORMS[@]}"; do
  if ! grep -qxF "$form" "$TMP/after.constants"; then
    echo "ERROR: patched class lost form link $form" >&2
    exit 1
  fi
done

REMOVED=$(comm -23 "$TMP/before.constants" "$TMP/after.constants" | grep -c . || true)
echo "==> Patched omod drops $REMOVED string constants:"
comm -23 "$TMP/before.constants" "$TMP/after.constants" | sed 's/^/      - /'
echo "==> pihcore omod patched: visit actions for retired forms removed"
