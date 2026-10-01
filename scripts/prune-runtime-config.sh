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
#     those forms stay behind, keyed on the XML's formName and formVersion.
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

# The identity of a form row is (form.name, form.version), read from each kept
# XML's formName/formVersion - NOT the XML's formUuid.
#
# This is the load-bearing detail of the whole script, and the previous version
# got it wrong twice over. It compared form_resource.uuid against the XML
# formUuids, but pihcore's HtmlFormSetup creates the formEngine FormResource with
# a freshly generated uuid ("formResource" + "formEngine" + the handler class
# name, per its bytecode); nothing ever writes the XML's formUuid there. And
# switching to htmlformentry_html_form.uuid does not rescue it either:
# htmlformentry's saveHtmlFormFromXml only reuses the XML's formUuid when a row
# with that uuid already exists, otherwise it mints a new one. Measured on the
# running install, 1 of 174 shipped formUuids appears in that table at all.
#
# So the predicate matched almost nothing that is kept and everything else, and
# the dry run reported 167 of 170 form_resource rows as purgeable. (Today the
# volume and the image are already in step, so there is genuinely nothing to
# purge - but the old script could not tell "nothing to purge" from "purge
# everything" and would have deleted the registers' rows on a fresh run.)
#
# (form.name, form.version) is what OpenMRS itself keys a form on, it is what
# HtmlFormSetup writes, and it is what the form picker shows, so a row that does
# not match a shipped XML is a row for a form this install no longer offers.
# Read out of the built config, so it cannot drift from what is deployed.
#
# Paths are relative to pih/htmlforms, without the .xml suffix, and include
# pih/htmlforms/retired/ - those XMLs ship too, so their form rows are kept
# rather than deleted and re-created by pihcore on the next boot.
#
# PRUNE_KEEP_FORMS pins an explicit subset instead (space-separated, same
# relative-path form). A form named there but missing from the build is a hard
# error, so a typo fails loudly instead of silently pruning something.
if [ -n "${PRUNE_KEEP_FORMS:-}" ]; then
  # shellcheck disable=SC2206 # deliberate word splitting: PRUNE_KEEP_FORMS is a list
  KEEP=( ${PRUNE_KEEP_FORMS} )
  KEEP_SOURCE="PRUNE_KEEP_FORMS"
else
  KEEP=()
  while IFS= read -r xml; do
    rel=${xml#"$BUILT_DIR/pih/htmlforms/"}
    KEEP+=( "${rel%.xml}" )
  done < <(find "$BUILT_DIR/pih/htmlforms" -name '*.xml' | sort)
  KEEP_SOURCE="$BUILT_DIR/pih/htmlforms"
fi

# A build with no htmlforms in it means the config was materialized wrongly.
# Without this the script would read an empty KEEP, build an empty IN () list
# and purge every form row in the database.
[ "${#KEEP[@]}" -gt 0 ] || {
  echo "ERROR: no htmlform XMLs under $BUILT_DIR/pih/htmlforms" >&2
  echo "       refusing to prune; re-run scripts/build-distro.sh" >&2
  exit 1
}

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
  docker run --rm -e CONFIG_DIR="$CONFIG_DIR" \
    -v "$VOLUME:$DATA_DIR" -v "$PWD/$BUILT_DIR:/built:ro" \
    --entrypoint sh "$IMAGE" -c "$1"
}

# (name, version) of every kept form, read from the built config, so the DB purge
# can never drift from the XML set that is actually deployed. Built in this
# shell rather than a command substitution so that a bad entry aborts the script
# instead of quietly yielding a short IN () list.
#
# A form's version is part of its identity in OpenMRS, so both halves matter:
# `NCD Followup` and `NCD Followup (Part 1)` are different forms, and two
# revisions of one form are two rows. Quoting is doubled-escaped for SQL, which
# no shipped formName currently needs.
keep_list=()
for f in "${KEEP[@]}"; do
  xml="$BUILT_DIR/pih/htmlforms/$f.xml"
  [ -f "$xml" ] || { echo "ERROR: $xml is missing from the built config" >&2; exit 1; }
  name=$(grep -oE 'formName="[^"]*"' "$xml" | head -1 | cut -d'"' -f2)
  ver=$(grep -oE 'formVersion="[^"]*"' "$xml" | head -1 | cut -d'"' -f2)
  # An XML with no formName is not a form, so it has no rows to protect. Carrying
  # on would emit a ('','') pair that matches nothing and silently widens the
  # purge.
  [ -n "$name" ] || { echo "ERROR: $xml declares no formName" >&2; exit 1; }
  [ -n "$ver" ] || { echo "ERROR: $xml declares no formVersion" >&2; exit 1; }
  keep_list+=("('${name//\'/\'\'}','${ver//\'/\'\'}')")
done
keep_pairs=$(IFS=,; echo "${keep_list[*]}")
# Not all 174 XMLs are 174 forms: a form and its _v1.0 revision, or a form and
# its retired/ twin, are the same (name, version) to OpenMRS. Duplicates in the
# IN list are harmless, but the count is reported and should be the honest one.
keep_distinct=$(printf '%s\n' "${keep_list[@]}" | sort -u | wc -l)

# The rows the purge may touch: a form no kept XML claims, that no encounter
# points at. `encounter.form_id` is NULL for the encounters created without a
# form, and a single NULL inside a NOT IN subquery makes the whole predicate
# never true, hence the IS NOT NULL.
#
# Two spellings, because MySQL will not let a statement read the table it
# deletes from: the child tables (error 1093 otherwise) take the form_id
# subquery, and the `form` delete carries the same predicate inline.
stale_pair="(name, version) NOT IN ($keep_pairs)"
not_claimed="form_id NOT IN (SELECT DISTINCT form_id FROM encounter WHERE form_id IS NOT NULL)"
orphan="SELECT form_id FROM form WHERE $stale_pair AND $not_claimed"

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
echo "==> Kept htmlforms: ${#KEEP[@]} XMLs, $keep_distinct distinct (form name, version) pairs (read from $KEEP_SOURCE)"
[ "$KEEP_SOURCE" = "PRUNE_KEEP_FORMS" ] && \
  echo "    NOTE: PRUNE_KEEP_FORMS is pinning a subset; every other shipped form is fair game"

echo "==> Forms referenced by encounters (never purged):"
docker compose exec -T "$DB_SERVICE" sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N openmrs -e \
   \"SELECT f.form_id, f.name, f.version, COUNT(e.encounter_id)
       FROM form f JOIN encounter e ON e.form_id = f.form_id
      GROUP BY f.form_id, f.name, f.version;\"" 2>/dev/null || true

echo "==> Form rows to purge (no shipped XML claims them, no encounter points at them):"
docker compose exec -T "$DB_SERVICE" sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N openmrs -e \
   \"SELECT f.form_id, f.name, f.version FROM form f WHERE $stale_pair AND f.$not_claimed;\"" 2>/dev/null |
  sed 's/^/      /'
orphans=$(docker compose exec -T "$DB_SERVICE" sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N openmrs -e \
   \"SELECT COUNT(*) FROM form WHERE form_id IN ($orphan);\"" 2>/dev/null | tr -d ' \r')
echo "==> Orphan forms to purge: ${orphans:-?}"

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

# Every delete is scoped to the same orphan form_id set, and in child-before-parent
# order. The previous version instead deleted by form_resource.uuid (see the note
# above) and reached form_field/form through "form_id NOT IN (SELECT form_id FROM
# form_resource)" - which a NULL form_id in form_resource turns into "delete
# every field of every form", kept forms included.
echo "==> Purging orphaned form rows"
docker compose exec -T "$DB_SERVICE" sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" openmrs -e \"
     DELETE FROM htmlformentry_html_form WHERE form_id IN ($orphan);
     DELETE FROM form_field                WHERE form_id IN ($orphan);
     DELETE FROM form_resource              WHERE form_id IN ($orphan);
     DELETE FROM form WHERE $stale_pair AND $not_claimed;\"" 2>/dev/null

docker compose exec -T "$DB_SERVICE" sh -c \
  "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N openmrs -e \"
     SELECT (SELECT COUNT(*) FROM form),
            (SELECT COUNT(*) FROM form_resource),
            (SELECT COUNT(*) FROM form_field),
            (SELECT COUNT(*) FROM htmlformentry_html_form);\"" 2>/dev/null |
  awk '{printf "    left: form=%s form_resource=%s form_field=%s htmlformentry_html_form=%s\n", $1, $2, $3, $4}'

echo
echo "==> Done. Start the server again:  docker compose up -d $SERVICE"
