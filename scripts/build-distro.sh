#!/usr/bin/env bash
# Assembles the OpenMRS distribution from the seeded local Maven repository.
#
#   scripts/seed-distro-maven-repo.sh   # once per machine (~/.m2)
#   scripts/build-distro.sh             # produces distro/target/distro/web/*
#
# The build is fully offline (-o): every war/omod/spa/owa/content artifact is
# resolved from ~/.m2. The first run on a fresh machine may need the Maven
# plugin cache warmed once online (see README); afterwards everything is cached.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SL_GROUP="org.sl.openmrs"
SL_ARTIFACT="phu360-content"
SL_VERSION="1.0.0-SNAPSHOT"
OFFLINE=(-o)
[[ "${1:-}" == "--online" ]] && OFFLINE=()

cd "$ROOT_DIR"

echo "==> Ensure config is materialized"
if [[ ! -d "content/build/configuration/backend_configuration" ]]; then
  echo "    materializing via seed script"
  bash scripts/seed-distro-maven-repo.sh
else
  # The seed script is skipped here, so re-apply the tracked delta over the
  # materialized config. Without this, edits to
  # content/configuration/backend_configuration never reach the content zip
  # and the distro keeps shipping the config that was seeded on this machine.
  echo "    re-applying content/configuration/backend_configuration"
  cp -a content/configuration/backend_configuration/. \
    content/build/configuration/backend_configuration/
fi

echo "==> Build + install content zip artifact"
mvn "${OFFLINE[@]}" -q -pl content package
mvn "${OFFLINE[@]}" -q org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
  -Dfile="content/target/${SL_ARTIFACT}-${SL_VERSION}.zip" \
  -DgroupId="$SL_GROUP" -DartifactId="$SL_ARTIFACT" \
  -Dversion="$SL_VERSION" -Dpackaging=zip -DgeneratePom=true

echo "==> Build PHU360 Reporting module (phu360reporting omod)"
bash "$ROOT_DIR/scripts/build-phu360reporting-module.sh"
# The SDK resolves modules from ~/.m2, not from openmrs-image/, and the seed
# script (which normally does this install) is skipped once content/build
# exists. Without this the distro keeps shipping the omod installed at seed
# time, silently ignoring every edit to the module.
mvn -q org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
  -Dfile="$ROOT_DIR/openmrs-image/phu360reporting-1.0.0-SNAPSHOT.omod" \
  -DgroupId="org.openmrs.module" -DartifactId="phu360reporting-omod" \
  -Dversion="1.0.0-SNAPSHOT" -Dpackaging=omod -DgeneratePom=true

echo "==> Patch reportingui module (DASHBOARDS section on the reports page)"
bash "$ROOT_DIR/scripts/patch-reportingui-module.sh"
mvn -q org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
  -Dfile="$ROOT_DIR/openmrs-image/reportingui-1.15.0-SNAPSHOT.omod" \
  -DgroupId="org.openmrs.module" -DartifactId="reportingui-omod" \
  -Dversion="1.15.0-SNAPSHOT" -Dpackaging=omod -DgeneratePom=true

echo "==> Patch coreapps module (Gender / Reg Facility filters on patient search)"
bash "$ROOT_DIR/scripts/patch-coreapps-module.sh"
mvn -q org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
  -Dfile="$ROOT_DIR/openmrs-image/coreapps-4.0.0-SNAPSHOT.omod" \
  -DgroupId="org.openmrs.module" -DartifactId="coreapps-omod" \
  -Dversion="4.0.0-SNAPSHOT" -Dpackaging=omod -DgeneratePom=true

echo "==> Build distro (OpenMRS SDK build-distro)"
# The SDK writes its extraction into target/distro and does NOT clean it between
# runs, so edits to the content package/config would silently not take effect.
rm -rf "$ROOT_DIR/distro/target/distro"
mvn "${OFFLINE[@]}" -q -pl distro package

echo "==> Overlay tracked SPA shell files (openmrs-image/spa -> openmrs_spa)"
# openmrs_spa is extracted from the branded SPA zip in ~/.m2, which is only
# written by the seed script. The seed script is skipped once content/build
# exists (and must stay skipped: it rm -rf's distro/template, where the
# prebuilt openmrs-esm-dispensing-app bundle lives). Without this overlay,
# edits to openmrs-image/spa/index.html - e.g. the dispensing labs theme
# <link> - are silently dropped from every build after the first seed.
cp -a "$ROOT_DIR/openmrs-image/spa/." "$ROOT_DIR/distro/target/distro/web/openmrs_spa/"

echo "==> Brand printed ID card / labels (MOH + HEAP logos)"
bash "$ROOT_DIR/scripts/brand-zpl.sh"

echo "==> Package DHMT KPI dashboard into the webapp"
bash "$ROOT_DIR/scripts/embed-dashboard-war.sh"

WEB_DIR="$ROOT_DIR/distro/target/distro/web"
echo "==> Output: $WEB_DIR"
echo "    openmrs_core/openmrs.war : $([ -f "$WEB_DIR/openmrs_core/openmrs.war" ] && stat -c%s "$WEB_DIR/openmrs_core/openmrs.war" || echo MISSING) bytes"
echo "    modules                  : $(find "$WEB_DIR/openmrs_modules" -name '*.omod' | wc -l)"
echo "    phu360reporting omod        : $([ -f "$ROOT_DIR/openmrs-image/phu360reporting-1.0.0-SNAPSHOT.omod" ] && echo present || echo MISSING)"
echo "    reportingui omod         : $([ -f "$ROOT_DIR/openmrs-image/reportingui-1.15.0-SNAPSHOT.omod" ] && echo present || echo MISSING)"
echo "    coreapps omod            : $([ -f "$ROOT_DIR/openmrs-image/coreapps-4.0.0-SNAPSHOT.omod" ] && echo present || echo MISSING)"
echo "    config files             : $(find "$WEB_DIR/openmrs_config" -type f | wc -l)"
echo "    spa                      : $(find "$WEB_DIR/openmrs_spa" -type f | wc -l)"
echo "    dispensing theme link    : $(grep -c 'dispensing-labs-theme' "$WEB_DIR/openmrs_spa/index.html" 2>/dev/null || echo MISSING)"
echo "    owas                     : $(find "$WEB_DIR/openmrs_owas" -type f | wc -l)"
echo "    openmrs-distro.properties: $([ -f "$WEB_DIR/openmrs-distro.properties" ] && echo present || echo MISSING)"