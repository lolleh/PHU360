#!/usr/bin/env bash
# Builds the merged PHU360 reporting module (clinicreporting) into an omod the
# distro can ship, into openmrs-image/clinicreporting-<version>.omod (the same
# path the seed script installs to the local Maven repo).
#
# clinicreporting is a standard Maven api+omod module, so this is a Maven build
# rather than the old javac-against-the-WAR assembly that built phu360reporting.
# The one thing Maven does not do for us is carry the tracked appframework app
# definitions: see the apps/ fold below.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOD_DIR="$ROOT_DIR/clinicreporting"
VERSION="1.0.0-SNAPSHOT"
OMOD="$MOD_DIR/omod/target/clinicreporting-${VERSION}.omod"
OUT="$ROOT_DIR/openmrs-image/clinicreporting-${VERSION}.omod"

# The module targets Java 11 and its parent pom disallows newer releases; the
# default JDK on this machine is newer, so pin a Java 17 toolchain for the build.
JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME="$JAVA_HOME_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> Building clinicreporting (Maven api + omod) with $JAVA_HOME"
mvn -B -q -DskipTests -f "$MOD_DIR/pom.xml" clean package
[[ -f "$OMOD" ]] || { echo "ERROR: expected omod not built: $OMOD" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
cp -a "$OMOD" "$OUT"

# The appframework only reads app definitions from the classpath
# (classpath*:/apps/*app.json and classpath*:/apps/*extension.json), and the
# config package is installed as a plain directory under
# /openmrs/data/configuration - not on the classpath. Without this fold every
# appframework/*.json in the config is inert: no home links, no patient-dashboard
# register buttons, no Program Dashboards entries, no reportingui DASHBOARDS
# section. Carrying them in this module is what makes the tracked config
# authoritative; the config copies stay as the reviewed source of truth.
#
# phu360reporting used to carry these; clinicreporting carries them now. File
# names are load-bearing and already match the two patterns
# (`*_app.json` -> app map, `*_extension.json` -> free-standing extension).
APPS_SRC="$ROOT_DIR/content/configuration/backend_configuration/appframework"
if compgen -G "$APPS_SRC/*.json" >/dev/null; then
  STAGE="$(mktemp -d)"
  mkdir -p "$STAGE/apps"
  cp -a "$APPS_SRC"/./*.json "$STAGE/apps/"
  ( cd "$STAGE" && zip -q -g "$OUT" apps/*.json )
  rm -rf "$STAGE"
  echo "==> Carried $(ls -1 "$APPS_SRC"/*.json | wc -l) app definition file(s) into apps/"
  ls -1 "$APPS_SRC"/*.json | xargs -n1 basename | sed 's/^/      apps\//'
fi

echo "==> Done: $OUT ($(du -h "$OUT" | cut -f1))"
