#!/usr/bin/env bash
# Patch the stock appframework omod so a "require" expression that evaluates to
# JavaScript null cannot crash every forms request.
#
# Why a patch: pihcore/PihFormRestController.getPatientForms serves the O3 forms
# widget from AppFrameworkService.getExtensionsForCurrentUser(visitActions). The
# context it builds has extra vars (patient, visit, ...) only when they exist, so
# a visit action whose require reads an absent variable evaluates to JS null:
#
#     (visit && patient && daysBetweenDates(...) ...)
#
# The stock checkRequireExpressionWithContext then runs
# eval(script).equals(Boolean.TRUE), and eval() returning null is a
# NullPointerException, not a "no". Opening a patient with no active visit
# therefore 500s the whole widget - every form disappears along with whatever
# the require was for. There is no config lever for this: the crash is in the
# service implementation.
#
# The patch makes both eval() sites null-safe (Boolean.TRUE.equals(eval(...))),
# so an expression that cannot be answered false leaves the extension hidden -
# which is what "a visit action without a visit" already meant.
#
# AppFrameworkServiceImpl is decompiled with CFR, rewritten, and recompiled
# against the distro's own classpath (this is the same technique as
# scripts/patch-pihcore-visit-actions.sh). The result is written to
# openmrs-image/appframework-<version>.omod, which seed-distro-maven-repo.sh
# and build-distro.sh install in place of the stock artifact.
#
# Bumping appframework means re-checking the two assertions below: the fresh
# decompile must still contain exactly the two unpatched eval() sites and the
# raw-generics fix-ups this script applies.
set -euo pipefail
export LC_ALL=C

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_ID="appframework"
VERSION="2.20.0-SNAPSHOT"
STAGE="$ROOT_DIR/distro/template/modules"
OUT="$ROOT_DIR/openmrs-image/${MODULE_ID}-${VERSION}.omod"
API_JAR_NAME="appframework-api-${VERSION}.jar"
API_JAR_IN_OMOD="lib/$API_JAR_NAME"
CLASS="org/openmrs/module/appframework/service/AppFrameworkServiceImpl.class"
TOOLS="$ROOT_DIR/.build/tools"
CFR_VERSION="0.152"
CFR="$TOOLS/cfr-$CFR_VERSION.jar"

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

mkdir -p "$TOOLS"
if [[ ! -f "$CFR" ]]; then
  echo "==> Fetching CFR $CFR_VERSION"
  curl -fsSL -o "$CFR" \
    "https://repo1.maven.org/maven2/org/benf/cfr/$CFR_VERSION/cfr-$CFR_VERSION.jar"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Extracting $STOCK"
unzip -q "$STOCK" -d "$WORK/omod"
[[ -f "$WORK/omod/$API_JAR_IN_OMOD" ]] || {
  echo "ERROR: $API_JAR_IN_OMOD not found in the stock omod; appframework layout changed" >&2
  exit 1
}
unzip -o -q "$WORK/omod/$API_JAR_IN_OMOD" "$CLASS" -d "$WORK/api"
sync

echo "==> Decompiling $CLASS"
java -jar "$CFR" "$WORK/api/$CLASS" --outputdir "$WORK/src" >/dev/null
SRC="$WORK/src/${CLASS%.class}.java"
[[ -f "$SRC" ]] || { echo "ERROR: decompile produced no source" >&2; exit 1; }

echo "==> Rewriting require evaluation to be null-safe"
python3 - "$SRC" "$MODULE_ID" "$VERSION" <<'PY'
import re, sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()

old = "this.javascriptEngine.get().eval(script).equals(Boolean.TRUE)"
new = "Boolean.TRUE.equals(this.javascriptEngine.get().eval(script))"
n_old = src.count(old)
n_new = src.count(new)
if n_old == 2:
    src = src.replace(old, new)
elif n_new == 2:
    print("    note: source already null-safe (re-running after a seed)")
else:
    sys.exit("ERROR: expected exactly two eval(script).equals(Boolean.TRUE) "
             "sites, found %d unpatched and %d patched; appframework was likely "
             "bumped. Re-check this patch against upstream." % (n_old, n_new))

# The decompile emits raw generic declarations that javac rejects. These four
# fix-ups restore the declarations the original code had.
fixes = [
    ("HashMap mapProperties = new HashMap();",
     "HashMap<Object, Object> mapProperties = new HashMap<Object, Object>();"),
    ("Map map = (Map)entry.getValue();",
     "Map<Object, Object> map = (Map<Object, Object>)entry.getValue();"),
    ("List locations = this.locationService.getLocationsByTag(supportsLogin);",
     "List<Location> locations = this.locationService.getLocationsByTag(supportsLogin);"),
    ("List filters = Context.getRegisteredComponents(LoginLocationFilter.class);",
     "List<LoginLocationFilter> filters = Context.getRegisteredComponents(LoginLocationFilter.class);"),
]
for needle, replacement in fixes:
    if src.count(needle) != 1:
        sys.exit("ERROR: expected exactly one '%s' in the decompiled source "
                 "to fix up; appframework was likely bumped. Re-check this "
                 "patch against upstream." % needle)
    src = src.replace(needle, replacement)

open(path, "w", encoding="utf-8").write(src)
PY

echo "==> Preparing compile classpath"
WEB_DIR="$ROOT_DIR/distro/target/distro/web"
if [[ -f "$WEB_DIR/openmrs_core/openmrs.war" ]]; then
  WAR="$WEB_DIR/openmrs_core/openmrs.war"
  MODS=("$WEB_DIR"/openmrs_modules/*.omod)
else
  # First build after a fresh seed: the SDK has not run yet, so use the
  # template artifacts the seed script materialized.
  WAR="$ROOT_DIR/distro/template/openmrs.war"
  MODS=("$ROOT_DIR"/distro/template/modules/*.omod)
fi
BUILD="$WORK/build"
mkdir -p "$BUILD/lib" "$BUILD/mods" "$BUILD/classes"
unzip -o -q "$WAR" 'WEB-INF/lib/*.jar' -d "$BUILD/lib"
sync
for m in "${MODS[@]}"; do
  unzip -o -q -j "$m" 'lib/*.jar' -d "$BUILD/mods" 2>/dev/null || true
done
sync
cp "$WORK/omod/$API_JAR_IN_OMOD" "$BUILD/mods/"
CP="$(find "$BUILD/lib" "$BUILD/mods" -name '*.jar' | tr '\n' ':')"

echo "==> Compiling patched $CLASS"
javac --release 8 -g -nowarn -proc:none -cp "$CP" -d "$BUILD/classes" "$SRC"
sync

echo "==> Patching the api jar and the omod"
mkdir -p "$WORK/jc/${CLASS%/*}"
cp "$BUILD/classes/$CLASS" "$WORK/jc/$CLASS"
# Deterministic entry: pin the class mtime like the surrounding entries.
touch -h -t 197001010000 "$WORK/jc/$CLASS"
( cd "$WORK/jc" && zip -q -X "$WORK/omod/$API_JAR_IN_OMOD" "$CLASS" )
sync
cp -f "$WORK/omod/$API_JAR_IN_OMOD" "$WORK/api-check.jar"

echo "==> Repacking -> $OUT"
rm -f "$OUT"
mkdir -p "$(dirname "$OUT")"
find "$WORK/omod" -exec touch -h -t 197001010000 {} +
( cd "$WORK/omod" && find . -print | LC_ALL=C sort | zip -q -X -@ "$OUT" )
sync

echo "==> Verifying the patched omod"
unzip -o -q -j "$OUT" "$API_JAR_IN_OMOD" -d "$WORK/check"
sync
unzip -o -q "$WORK/check/$API_JAR_NAME" "$CLASS" -d "$WORK/check/api"
sync
java -jar "$CFR" "$WORK/check/api/$CLASS" --outputdir "$WORK/check/src" >/dev/null
CHECK_SRC="$WORK/check/src/${CLASS%.class}.java"
if grep -q "this.javascriptEngine.get().eval(script).equals(Boolean.TRUE)" "$CHECK_SRC"; then
  echo "ERROR: patched class still contains an unpatched eval().equals(Boolean.TRUE)" >&2
  exit 1
fi
if [[ "$(grep -c "Boolean.TRUE.equals(this.javascriptEngine.get().eval(script))" "$CHECK_SRC")" -ne 2 ]]; then
  echo "ERROR: patched class does not contain both null-safe eval() sites" >&2
  exit 1
fi

echo "==> Done: $OUT ($(stat -c%s "$OUT") bytes)"