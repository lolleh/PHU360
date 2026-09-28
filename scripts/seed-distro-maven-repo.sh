#!/usr/bin/env bash
# One-time seed of the local Maven repository (~/.m2) with every artifact the
# OpenMRS SDK 6.8.0 build-distro needs to assemble the distribution, so the
# distro build can run fully offline (-o).
#
# Everything is derived from the pinned stock PIH SL image
# (partnersinhealth/pihsl-emr:latest) plus the tracked repo overlays, with no
# dependency on the dead mavenrepo.openmrs.org or any other remote snapshot
# repository.
#
# It also materializes the full OpenMRS configuration into content/build/
# (gitignored): stock image config + tracked configuration/backend_configuration
# delta - the exclusions listed in content/exclusions.txt. This reproduces the
# served Sierra Leone EMR config byte-for-byte (stock 647 - 15 removed
# dataexports + 411 delta = 643 files + the newer labs theme CSS).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

STOCK_IMAGE="partnersinhealth/pihsl-emr:latest"
SL_GROUP="org.sl.openmrs"
SL_VERSION="1.0.0-SNAPSHOT"
WAR_GROUP="org.openmrs.web"
WAR_ARTIFACT="openmrs-webapp"
WAR_VERSION="2.8.9"
FRONTEND_GROUP="org.pih.openmrs"
FRONTEND_ARTIFACT="openmrs-frontend-pihemr"
FRONTEND_VERSION="1.43.0-SNAPSHOT"
OWA_GROUP="org.openmrs.owa"
OWA_ARTIFACT="labworkflow"
OWA_VERSION="2.0.0-SNAPSHOT"

WORK_DIR="$ROOT_DIR/distro/template"
CONFIG_BUILD="$ROOT_DIR/content/build"
DELTA_DIR="$ROOT_DIR/content/configuration/backend_configuration"
PATCHED_PIHCORE="$ROOT_DIR/openmrs-image/pihcore-2.2.0-SNAPSHOT.omod"
PATCHED_PHU360REPORTING="$ROOT_DIR/openmrs-image/phu360reporting-1.0.0-SNAPSHOT.omod"
PATCHED_REPORTINGUI="$ROOT_DIR/openmrs-image/reportingui-1.15.0-SNAPSHOT.omod"
PATCHED_COREAPPS="$ROOT_DIR/openmrs-image/coreapps-4.0.0-SNAPSHOT.omod"
EXCLUSIONS="$ROOT_DIR/content/exclusions.txt"

# The OpenMRS SDK asks interactively for anonymous usage stats on first run,
# which stalls automated builds. Disable it up front.
mkdir -p "$HOME/.openmrs"
printf 'statsEnabled=false\n' > "$HOME/.openmrs/sdk-stats.properties"

echo "==> Extracting stock distribution from $STOCK_IMAGE"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
CID="$(docker create "$STOCK_IMAGE")"
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT
docker cp "$CID:/openmrs/distribution/openmrs_core/openmrs.war" "$WORK_DIR/openmrs.war"
docker cp "$CID:/openmrs/distribution/openmrs_modules" "$WORK_DIR/modules"
docker cp "$CID:/openmrs/distribution/openmrs_owas/labworkflow.owa" "$WORK_DIR/labworkflow.owa"
docker cp "$CID:/openmrs/distribution/openmrs_spa" "$WORK_DIR/spa"
docker cp "$CID:/openmrs/distribution/openmrs_config" "$WORK_DIR/config"
docker rm -f "$CID"
trap - EXIT

echo "==> Materializing configuration -> $CONFIG_BUILD/configuration/backend_configuration"
rm -rf "$CONFIG_BUILD"
mkdir -p "$CONFIG_BUILD/configuration/backend_configuration"
cp -a "$WORK_DIR/config/." "$CONFIG_BUILD/configuration/backend_configuration/"
cp -a "$DELTA_DIR/." "$CONFIG_BUILD/configuration/backend_configuration/"
REMOVED=0
while IFS= read -r rel || [[ -n "$rel" ]]; do
  [[ -z "$rel" || "$rel" == \#* ]] && continue
  rm -f "$CONFIG_BUILD/configuration/backend_configuration/$rel"
  REMOVED=$((REMOVED + 1))
done < "$EXCLUSIONS"
echo "    removed $REMOVED excluded files"

echo "==> Branding SPA (stock openmrs_spa + openmrs-image/spa overlay)"
SPA_DIR="$WORK_DIR/frontend/${FRONTEND_ARTIFACT}-${FRONTEND_VERSION}"
mkdir -p "$SPA_DIR"
cp -a "$WORK_DIR/spa/." "$SPA_DIR/"
cp -a "$ROOT_DIR/openmrs-image/spa/." "$SPA_DIR/"
python3 - "$WORK_DIR" "$SPA_DIR" <<'PY'
import os, sys, zipfile
work, spa_dir = sys.argv[1], sys.argv[2]
pkg = os.path.basename(spa_dir.rstrip('/'))
out = os.path.join(work, pkg + ".zip")
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for dp, _dn, fns in os.walk(spa_dir):
        for fn in sorted(fns):
            f = os.path.join(dp, fn)
            z.write(f, os.path.join(pkg, os.path.relpath(f, spa_dir)))
print("    wrote", out)
PY

echo "==> Building PHU360 Reporting module (phu360reporting omod)"
bash "$ROOT_DIR/scripts/build-phu360reporting-module.sh"

echo "==> Patching reportingui module (DASHBOARDS section on the reports page)"
bash "$ROOT_DIR/scripts/patch-reportingui-module.sh"

echo "==> Patching coreapps module (Gender / Reg Facility filters on patient search)"
bash "$ROOT_DIR/scripts/patch-coreapps-module.sh"

install_file() {
  local file="$1" group="$2" artifact="$3" version="$4" packaging="$5"
  echo "    seeding $group:$artifact:$version:$packaging"
  mvn -q org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
    -Dfile="$file" -DgroupId="$group" -DartifactId="$artifact" \
    -Dversion="$version" -Dpackaging="$packaging" -DgeneratePom=true
}

echo "==> Seeding local Maven repository (~/.m2)"
echo "  - OpenMRS webapp WAR"
install_file "$WORK_DIR/openmrs.war" "$WAR_GROUP" "$WAR_ARTIFACT" "$WAR_VERSION" "war"

echo "  - 50 OpenMRS modules"
# Maven install:install-file derives the stored artifact extension from the
# source file's extension, not from -Dpackaging. The SDK resolves modules with
# type=jar (except serialization.xstream, type=omod), so each omod must be
# staged under the .jar extension in the local repo for offline resolution.
MODULES_STAGED="$WORK_DIR/staged-modules"
mkdir -p "$MODULES_STAGED"
OMODS=()
OVERRIDES=()
while IFS= read -r line; do
  [[ "$line" == omod.* ]] || continue
  KEY="${line#omod.}"
  NAME="${KEY%%=*}"
  case "$NAME" in
    *.groupId|*.type) OVERRIDES+=("$NAME=${line##*=}") ;;
    *) OMODS+=("$NAME=${line##*=}") ;;
  esac
done < "$ROOT_DIR/distro/openmrs-distro.properties"

declare -A GID TYPE
for o in "${OVERRIDES[@]}"; do
  key="${o%%=*}"; val="${o##*=}"
  name="${key%.groupId}"; name="${name%.type}"
  case "$key" in
    *.groupId) GID["$name"]="$val" ;;
    *.type)    TYPE["$name"]="$val" ;;
  esac
done

for m in "${OMODS[@]}"; do
  name="${m%%=*}"; version="${m##*=}"
  group="${GID[$name]:-org.openmrs.module}"
  type="${TYPE[$name]:-jar}"
  file="$WORK_DIR/modules/${name}-${version}.omod"
  if [[ "$name" == "pihcore" && -f "$PATCHED_PIHCORE" ]]; then
    file="$PATCHED_PIHCORE"
  fi
  if [[ "$name" == "phu360reporting" && -f "$PATCHED_PHU360REPORTING" ]]; then
    file="$PATCHED_PHU360REPORTING"
  fi
  if [[ "$name" == "reportingui" && -f "$PATCHED_REPORTINGUI" ]]; then
    file="$PATCHED_REPORTINGUI"
  fi
  if [[ "$name" == "coreapps" && -f "$PATCHED_COREAPPS" ]]; then
    file="$PATCHED_COREAPPS"
  fi
  if [[ "$type" == "jar" ]]; then
    ext="jar"
    staged="$MODULES_STAGED/${name}-omod-${version}.jar"
    cp "$file" "$staged"
    file="$staged"
  else
    ext="omod"
  fi
  install_file "$file" "$group" "${name}-omod" "$version" "$ext"
done

echo "  - OpenMRS SPA (branded)"
install_file "$WORK_DIR/${FRONTEND_ARTIFACT}-${FRONTEND_VERSION}.zip" \
  "$FRONTEND_GROUP" "$FRONTEND_ARTIFACT" "$FRONTEND_VERSION" "zip"

echo "  - OpenMRS OWA"
install_file "$WORK_DIR/labworkflow.owa" "$OWA_GROUP" "$OWA_ARTIFACT" "$OWA_VERSION" "zip"

echo "==> Done. All distro artifacts are seeded; content/ is materialized."
echo "    Next: scripts/build-distro.sh"