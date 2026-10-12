#!/usr/bin/env bash
# Builds the merged PHU360 reporting module (clinicreporting) into an omod the
# distro can ship, into openmrs-image/clinicreporting-<version>.omod (the same
# path the seed script installs to the local Maven repo).
#
# clinicreporting is a standard Maven api+omod module, so this is a Maven build
# rather than the old javac-against-the-WAR assembly that built phu360reporting.
#
# Appframework app/extension definitions are NOT carried in the omod. This distro
# sets loadAppsFromClasspath=false (pihcore appconfig/appframework-config.json),
# so classpath*:/apps/*.json in a module is never read. Home links and dashboard
# entries are delivered as JSON in the tracked config directory instead:
#   content/configuration/backend_configuration/appframework/
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

echo "==> Done: $OUT ($(du -h "$OUT" | cut -f1))"
