#!/usr/bin/env bash
# Brand the printed patient ID card / ID label with the MOH + HEAP logos.
#
# The card/label ZPL template (ZplCardTemplate) is compiled inside the pihcore
# module (lib/pihcore-api-*.jar). The logos are the same PNGs the EMR already
# renders (content/configuration/backend_configuration/pih/logo), rasterized
# to ZPL ^GFA graphics. This script:
#   1. rasterizes the logos into a Java constant  (branding/zpl/rasterize.py)
#   2. compiles the branded replacement ZplCardTemplate against the distro jars
#   3. swaps the class into pihcore-api-*.jar inside the pihcore omod
#
# Run AFTER scripts/build-distro.sh and BEFORE docker compose build openmrs.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB_DIR="$ROOT_DIR/distro/target/distro/web"
OMOD="$WEB_DIR/openmrs_modules/pihcore-2.2.0-SNAPSHOT.omod"
API_JAR_NAME="pihcore-api-2.2.0-SNAPSHOT.jar"
BUILD="$ROOT_DIR/.build/zpl"

if [[ ! -f "$OMOD" ]]; then
  echo "ERROR: $OMOD not found; run scripts/build-distro.sh first" >&2
  exit 1
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/lib" "$BUILD/classes" "$BUILD/gen-src" "$BUILD/emrapi"

echo "==> Rasterize logos -> GeneratedLogos.java"
python3 "$ROOT_DIR/branding/zpl/rasterize.py" --out-java "$BUILD/gen-src/GeneratedLogos.java"

echo "==> Prepare compile classpath"
unzip -o -q "$WEB_DIR/openmrs_core/openmrs.war" 'WEB-INF/lib/*.jar' -d "$BUILD/war"
cp "$BUILD"/war/WEB-INF/lib/*.jar "$BUILD/lib/"
unzip -o -q -j "$WEB_DIR/openmrs_modules/emrapi-3.5.1.omod" \
  'lib/emrapi-api-3.5.1.jar' -d "$BUILD/emrapi-jar"
(cd "$BUILD/emrapi" && unzip -o -q "$BUILD/emrapi-jar/emrapi-api-3.5.1.jar")
CP="$BUILD/lib/*:$BUILD/emrapi"

echo "==> Compile replacement ZplCardTemplate"
javac --release 8 -nowarn \
  -cp "$CP" \
  -d "$BUILD/classes" \
  "$BUILD/gen-src/GeneratedLogos.java" \
  "$ROOT_DIR/branding/zpl/java/org/openmrs/module/pihcore/printer/template/ZplCardTemplate.java"

echo "==> Patch $OMOD"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$OMOD" -d "$TMP"
API="$TMP/lib/$API_JAR_NAME"
if [[ ! -f "$API" ]]; then
  echo "ERROR: $API_JAR_NAME not found inside pihcore omod" >&2
  exit 1
fi
WD="$(mktemp -d)"
trap 'rm -rf "$TMP" "$WD"' EXIT
unzip -q "$API" -d "$WD"
cp "$BUILD/classes/org/openmrs/module/pihcore/printer/template/ZplCardTemplate.class" \
   "$WD/org/openmrs/module/pihcore/printer/template/ZplCardTemplate.class"
( cd "$WD" && rm -f "$API" && "$(command -v jar)" cf "$API" . )
( cd "$TMP" && rm -f "$OMOD" && "$(command -v zip)" -q -r -X "$OMOD" . )

echo "==> Patched pihcore omod with branded ZplCardTemplate"