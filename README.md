  "Keeping a running install in sync". To see the drift:
  `scripts/prune-runtime-config.sh --dry-run`.
- **A link or button in `appframework/*.json` never appears** — those files are
  inert unless the module carries them on the classpath; see "Where app
  definitions have to live". A `did you rebuild the omod` check:
  `unzip -l distro/target/distro/web/openmrs_modules/phu360reporting-*.omod | grep apps/`.
- **The per-program tiles are still on the Program Dashboards page** — the
  activator in the omod is what removes them, so re-run
  `scripts/build-phu360reporting-module.sh`, `scripts/build-distro.sh` and
  `docker compose up -d --build`, then confirm the count in the page HTML. Do not
  wait for a log line, the module's logger does not reach `openmrs.log`. See
  "Program Dashboards page: hiding the per-program tiles".
- **`Module PHU360 Reporting cannot be started because it requires ...`** — a
  `require_module` version in `phu360reporting/module/config.xml` no longer
  matches the installed module. Check the version in the installed omod's
  `config.xml`; `2.2.0` and `2.2.0-SNAPSHOT` are not interchangeable, and the
  entries only count inside a `<require_modules>` wrapper.
- **`NoClassDefFoundError: ...appframework.service.AppFrameworkService`** — the
  appframework `require_module` is missing, so the module classloader cannot see
  appframework's API and the activator never runs.
- **`Illegal mix of collations`** — pihcore's Liquibase crashes on non-`utf8_general_ci` DBs. The compose DB passes `--character-set-server=utf8 --collation-server=utf8_general_ci`; replicate for ad-hoc MySQL.
- **`HTTP Status 500` / `Error loading PIH config`** — the `OPENMRS_PIH_CONFIG` chain references a profile the image doesn't ship. Fix `.env`, wipe the half-initialized DB, and let first boot run cleanly (`docker compose stop`, `docker volume rm <prefix>_phu360-data <prefix>_phu360-db-data`, `docker compose up -d openmrs && docker compose logs -f openmrs`).
- **Ports** — `8090` (OpenMRS) and `3307` (MySQL) are the compose defaults.

## Multi-site data sharing (hub-and-spoke)

PHU360 supports hub-and-spoke cross-facility data sharing using **fhir2 R4 + a thin per-site sync agent**. The district server hosts the hub OpenMRS instance; each health center (Falaba, Mongo Bendugu, Sinkunia) runs its own OpenMRS and a small sync agent.

### Design
- **Hub**: 4th PHU360 OpenMRS instance (`sierraLeone,sierraLeone-hub`) at the district (reuses this distro). No sync agent on the hub.
- **Agents**: Java 17/Spring Boot sync agent on each spoke only, polling the local fhir2 API with `_lastUpdated` watermarks and pushing/pulling to/from the hub via fhir2 R4. Built by `scripts/build-sync-agent.sh`.
- **Change detection**: `_lastUpdated` polling with persisted per-resource watermarks; batching via `_count` (<= paging maximum). Overlap window prevents edge misses.
- **Idempotency**: `PUT /{ResourceType}/{uuid}` preserves UUIDs across sites.
- **Provenance**: a dedicated `PHU360 Sync Origin` identifier marks records by source site (FAL/MON/SIN/HUB). Agent only pushes records whose origin is self.
- **Identity**: existing EMR IDs (FAL/MON/SIN) + hub-issued `PHU360 District ID`. Auto-link on strong IDs with a manual review queue for ambiguous matches.
- **Providers**: Practitioners are synced and provider references mapped by identifier.
- **Scope**: Patient, Person, Encounter, Observation, Condition, MedicationRequest/Dispense, ServiceRequest, Immunization, AllergyIntolerance, DiagnosticReport, Practitioner, Location, Visit (via Encounter). LWW by `meta.lastUpdated` with hub as tie-breaker.
- **Echo control**: origin identifier prevents loops when pull-applied records are pushed.

### Quick start (docker compose example)
1. **Build artifacts**: build distro and sync agent.
   - `scripts/seed-distro-maven-repo.sh && scripts/build-distro.sh`
   - `scripts/build-sync-agent.sh` (or `cd sync-agent && mvn -B -DskipTests package`)
2. **Configure**: copy `.env.example` to `.env` and set hub/spoke endpoints, credentials, site codes.
3. **Run**: for a district deployment with hub, use `docker compose -f docker-compose.yml -f docker-compose.district.yml up -d`.
   - `openmrs` = Falaba spoke (default), `openmrs-hub` = district hub (port `8095`), `sync-agent-falaba/mongo/sinkunia` run alongside spokes as needed.
4. **Prepare hub service user**: create a least-privilege OpenMRS user (e.g. `sync-bot`) with privileges to read/write FHIR resources on the hub (and on each spoke) before running agents.

### Identifier types
The config ships two new identifier types:
- `PHU360 District ID` (issued at hub)
- `PHU360 Sync Origin` (FAL/MON/SIN/HUB) — required for echo control.

### EMR ID guard
`scripts/emr-id-falaba-guard.sql` is generalized to per-site. Set `@site_code` to `FAL`, `MON`, or `SIN` for that site's DB and apply once (events re-assert on boot). Requires `--event-scheduler=ON`.

### Phase 0 validation (required before full rollout)
Before P3 clinical replication, validate on a running pair:
1. **PUT preserves UUID**: write Patient with a known UUID via `PUT /Patient/{uuid}`; read back confirms UUID preserved.
2. **`_lastUpdated` populated**: write/update each target resource; `?subject=...&_lastUpdated=gt<t0>` returns it; updated record shows later `_lastUpdated`.
3. **Echo control**: push a record with `PHU360 Sync Origin` = self; pull from hub should not cause the same record to be re-pushed.
4. **Provider mapping**: create Practitioner on hub/spoke with shared identifier; replicate Encounter referencing it; reference resolves correctly.
5. **Visit vs Encounter**: encounter carrying visit context round-trips (FHIR2 tag handling) as expected.
6. **Value fidelity**: Observation numeric/coded/text and a complex obs round-trip without loss.
7. **Pagination**: `_count=1` paginates correctly.

### Notes
- `meta.tag` is not used for origin (fhir2 uses `_tag` to distinguish Encounter vs Visit). Use the `PHU360 Sync Origin` identifier.
- Hub has no agent; relaying happens because the hub's own `_lastUpdated` advances on writes from any spoke.
- For spoke-to-spoke connectivity details (Mongo/Sinkunia), configure `MON_LOCAL_FHIR_URL` / `SIN_LOCAL_FHIR_URL` in `.env`. The provided `docker-compose.district.yml` shows one way to wire the Falaba agent to the Falaba OpenMRS in the same stack.
