#!/usr/bin/env bash
# Make an already-initialized install's data volume match the built config again,
# and drop the form rows pihcore created for forms that are gone.
#
# Why this is needed: the image ships only the files under
# distro/target/distro/web/openmrs_config, but the docker entrypoint extracts
# them into the persistent `phu360-data` volume with a plain `cp -R` on every
# boot and never deletes anything that used to be there. Two things then go
# stale:
#
#   * a file deleted or renamed under content/configuration/ keeps being served
#     to the running server (the same overlay trap the README documents for
#     content/build/), and pihcore's HtmlFormSetup re-loads every XML it finds
#     in configuration/pih/htmlforms on every boot, so a "deleted" form is still
#     in the form list;
#   * the form / form_resource / htmlformentry_html_form rows pihcore saved for
#     those forms stay behind, keyed on the XML's formUuid.
#
# Re-running scripts/seed-distro-maven-repo.sh does not help: the stale files
# live in the volume, not in the build tree.
#
# Safety: the server must be stopped, so the result is never half-applied. A
# form that any encounter points at is never purged, and is listed first so it
# can be checked. Run with --dry-run to see the plan.
set -euo pipefail

cd "$(dirname "$0")/.."

SERVICE=openmrs
DB_SERVICE=openmrs-db
CONFIG_DIR=/openmrs/data/configuration
BUILT_DIR=distro/target/distro/web/openmrs_config

# The forms PHU360 keeps. Must stay in sync with
# content/configuration/backend_configuration/pih/htmlforms and with
# content/build/.../pih/htmlforms (see README).
KEEP=(
  patientRegistration
  patientRegistration-contact
  patientRegistration-localAddress
  patientRegistration-rs
  patientRegistration-social
  triage
  dispensing
  labResults
  labResults_v1.0
  section-lab-order
  checkin
  checkinMaternal
  motherAndNeonateRegister
  deliveryRegister
  familyPlanningRegister
)

DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }
[ -d "$BUILT_DIR" ] || { echo "ERROR: $BUILT_DIR missing - run scripts/build-distro.sh first" >&2; exit 1; }

if docker compose ps --status running --services 2>/dev/null | grep -qx "$SERVICE"; then
  echo "ERROR: the $SERVICE container is running; stop it first:" >&2
  echo "         docker compose stop $SERVICE" >&2
  exit 1
fi

# The data volume is referenced by name, not by its host path, because
# /var/lib/docker is not readable by an unprivileged docker-group user.
# `compose run` is not used either: it rebuilds the image and floods stdout.
CONTAINER=$(docker compose ps -aq "$SERVICE")
VOLUME=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/openmrs/data"}}{{.Name}}{{end}}{{end}}' "$CONTAINER")
IMAGE=$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")
[ -n "$VOLUME" ] && docker volume inspect "$VOLUME" >/dev/null 2>&1 || {
  echo "ERROR: could not resolve the /openmrs/data volume of the $SERVICE service" >&2; exit 1; }

DATA_DIR=/openmrs/data
in_container() {
  docker run --rm -e KEEPS="$KEEPS" -e CONFIG_DIR="$CONFIG_DIR" \
    -v "$VOLUME:$DATA_DIR" -v "$PWD/$BUILT_DIR:/built:ro" \
    --entrypoint sh "$IMAGE" -c "$1"
}

KEEPS=$(printf '%s ' "${KEEP[@]}")

# formUuid of every kept form, read from the built config, so the DB purge can
# never drift from the XML set that is actually deployed.
uuids=$(
  for f in "${KEEP[@]}"; do
    xml="$BUILT_DIR/pih/htmlforms/$f.xml"
    [ -f "$xml" ] || { echo "ERROR: $xml is missing from the built config" >&2; exit 1; }
    grep -oE 'formUuid="[0-9a-fA-F-]+"' "$xml" | head -1 | cut -d'"' -f2
  done
)
uuid_list=$(printf '%s\n' "$uuids" | sed "s/.*/'&'/" | paste -sd, -)

# `encounter.form_id` is NULL for the encounters created without a form, and a
# single NULL inside a NOT IN subquery makes the whole predicate never true.
protected="SELECT DISTINCT form_id FROM encounter WHERE form_id IS NOT NULL"

stale=$(in_container '
  cd "$CONFIG_DIR" && find . -type f | sed "s|^\./||" | sort > /tmp/live.txt
  cd /built        && find . -type f | sed "s|^\./||" | sort > /tmp/built.txt
  echo "MISSING_FROM_IMAGE:"; comm -23 /tmp/live.txt /tmp/built.txt
  echo "NOT_YET_EXTRACTED:";  comm -13 /tmp/live.txt /tmp/built.txt
')
missing=$(printf '%s\n' "$stale" | sed -n '/^MISSING_FROM_IMAGE:$/,/^NOT_YET_EXTRACTED:$/p' | sed '1d;$d' | grep -c . || true)
pending=$(printf '%s\n' "$stale" | sed -n '/^NOT_YET_EXTRACTED:$/p' | sed '1d' | grep -c . || true)

echo "==> Config files in the data volume that the image no longer ships: ${missing:-0}"
printf '%s\n' "$stale" | sed -n '/^MISSING_FROM_IMAGE:$/,/^NOT_YET_EXTRACTED:$/p' | sed '1d;$d' | sed 's/^/      /'
[ "${pending:-0}" -gt 0 ] && echo "==> Files in the image not yet extracted (they arrive on the next boot): $pending"
echo "==> Kept htmlforms (${#KEEP[@]}): ${KEEPS% }"

echo "==> Forms referenced by encounters (never purged):"
docker compose exec -T "$DB_SERVICE" sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N openmrs -e \
   \"SELECT fr.form_id, fr.uuid, COUNT(e.encounter_id)
       FROM form_resource fr JOIN encounter e ON e.form_id=fr.form_id
      GROUP BY fr.form_id, fr.uuid;\"" 2>/dev/null || true

orphans=$(docker compose exec -T "$DB_SERVICE" sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N openmrs -e \
   \"SELECT COUNT(*) FROM form_resource
     WHERE uuid NOT IN ($uuid_list)
       AND form_id NOT IN ($protected);\"" 2>/dev/null | tr -d ' \r')
echo "==> Orphan form_resource rows to purge: ${orphans:-?}"

if [ "$DRY_RUN" -eq 1 ]; then
  echo
  echo "dry run - nothing changed"
  exit 0
fi

echo
echo "==> Deleting the stale config files from the data volume"
in_container '
  cd "$CONFIG_DIR" && find . -type f | sed "s|^\./||" | sort > /tmp/live.txt
  cd /built        && find . -type f | sed "s|^\./||" | sort > /tmp/built.txt
  n=0
  comm -23 /tmp/live.txt /tmp/built.txt | while read -r f; do
    rm -f -- "$CONFIG_DIR/$f" && echo "    removed $f"
  done
  # pihcore also sweeps these; a retired/ dir left behind is itself stale
  find "$CONFIG_DIR" -type d -empty -delete 2>/dev/null || true
  cd "$CONFIG_DIR" && echo "    config files left: $(find . -type f | wc -l)"
'

echo "==> Purging orphaned form rows"
docker compose exec -T "$DB_SERVICE" sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" openmrs -e \"
     DELETE FROM htmlformentry_html_form
      WHERE form_id IN (SELECT form_id FROM form_resource
                         WHERE uuid NOT IN ($uuid_list)
                           AND form_id NOT IN ($protected));
     DELETE FROM form_resource
      WHERE uuid NOT IN ($uuid_list)
        AND form_id NOT IN ($protected);
     DELETE ff FROM form_field ff
      WHERE ff.form_id NOT IN (SELECT form_id FROM form_resource)
        AND ff.form_id NOT IN ($protected);
     DELETE FROM form
      WHERE form_id NOT IN (SELECT form_id FROM form_resource)
        AND form_id NOT IN ($protected);\"" 2>/dev/null

docker compose exec -T "$DB_SERVICE" sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N openmrs -e \"
     SELECT (SELECT COUNT(*) FROM form),
            (SELECT COUNT(*) FROM form_resource),
            (SELECT COUNT(*) FROM htmlformentry_html_form);\"" 2>/dev/null |
  awk '{printf "    left: form=%s form_resource=%s htmlformentry_html_form=%s\n", $1, $2, $3}'

echo
echo "==> Done. Start the server again:  docker compose up -d $SERVICE"
