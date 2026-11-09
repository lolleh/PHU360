# PHU360

OpenMRS distribution for Sierra Leone PHUs (Falaba, Mongo Bendugu, Sinkunia) with optional hub-and-spoke replication via a thin FHIR2 sync agent.

## What it is
- OpenMRS 2.8.9 + PIH Sierra Leone distro, built via OpenMRS SDK 6.8.0 (Java 17)
- Docker Compose for local dev; content/config loaded via Initializer
- Multi-site: per-site OpenMRS + hub OpenMRS + per-spoke Spring Boot sync agent

## Quick start (dev environment)
1. **Prereqs**
   - Docker + Docker Compose
   - Java 17 (for building sync-agent), Maven 3.9+ (if building from source)
   - Python 3 (some scripts)
   
2. **Clone and setup**
   ```bash
   cd /home/ubuntu/PHU360
   cp .env.example .env
   # edit .env as needed
   ```
   
3. **Build distro (one time or after config changes)**
   ```bash
   ./scripts/seed-distro-maven-repo.sh
   ./scripts/build-distro.sh
   ```
   
4. **Build sync agent**
   ```bash
   ./scripts/build-sync-agent.sh  # or: cd sync-agent && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn -B -DskipTests package
   ```
   
5. **Run single site (Falaba by default)**
   ```bash
   docker compose up -d --build
   # OpenMRS: http://localhost:8090/openmrs (user/pass from .env)
   # DB: localhost:3307
   ```
   
6. **Run hub + Falaba spoke (multi-site)**
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.district.yml up -d --build
   # Hub: http://localhost:8095/openmrs, Spoke: http://localhost:8090/openmrs
   ```

## How to set up on a server (production)
1. **Install prereqs**
   ```bash
   ./scripts/install-prereqs.sh  # Ubuntu; or install docker, openjdk-17-jre, maven manually
   ```
   
2. **Build artifacts on build host or on server**
   ```bash
   ./scripts/seed-distro-maven-repo.sh && ./scripts/build-distro.sh
   ./scripts/build-sync-agent.sh
   ```
   
3. **Configure environment**
   ```bash
   cp .env.example .env
   # set production values: passwords, ports, URLs, PIH profiles
   ```
   
4. **Run with hub-and-spoke** (recommended for district)
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.district.yml up -d
   ```
   
5. **Post-setup**
   - Create `sync-bot` service user on hub and each spoke with FHIR read/write perms
   - Apply EMR ID guard per site: set `@site_code` (FAL/MON/SIN) in `scripts/emr-id-falaba-guard.sql` and run against each site's DB
   - Enable `mysqld --event-scheduler=ON` (already in docker-compose)
   - Put reverse proxy (nginx/ssl) in front of OpenMRS and sync agent health endpoints

## Configuration
- `OPENMRS_PIH_CONFIG`: PIH profile chain for single-site (default `sierraLeone,sierraLeone-falaba`)
- `OPENMRS_HUB_PIH_CONFIG`: hub profile (default `sierraLeone,sierraLeone-hub`)
- Sync agent env: `SYNC_SITE_CODE`, `SYNC_LOCAL_BASE_URL`, `SYNC_HUB_BASE_URL`, credentials, `SYNC_POLL_INTERVAL_MS`, `SYNC_PAGE_SIZE`

## Dashboards: making changes and building indicators

PHU360 has two reporting dashboards:

| Dashboard | URL | How it's configured |
|-----------|-----|---------------------|
| Facility Reporting Dashboard | `moduleResources/clinicreporting/index.html` (app link: "PHU360 Reporting Dashboard") | Static HTML + JS calling the clinicreporting REST API; KPI cards, charts and tables are defined in the page and its JS |
| Program Dashboards | `module/clinicreporting/programs.htm` and `dashboard.htm?program=<uuid>` | JSON definition file (`program-indicators.json`) — editable without rebuild or restart |

### Facility Reporting Dashboard

The Facility Reporting Dashboard shows facility-wide KPIs and charts: encounters, patient visits, new registrations, conditions recorded, plus service volume trend, encounters by type/location/age/sex, and an encounters-by-type table.

**To change indicators, charts, KPI cards, or styling:**

1. Edit `clinicreporting/omod/src/main/webapp/resources/index.html` — page markup (KPI cards, chart canvas elements, table).
2. Edit `clinicreporting/omod/src/main/webapp/resources/js/app.js` — JavaScript that fetches data from the REST API and renders charts/KPIs.
3. Edit `clinicreporting/omod/src/main/webapp/resources/styles/reports.css` and `resources/css/styles.css` — styling.
4. Rebuild the module: `./scripts/build-clinicreporting-module.sh`
5. Rebuild the distro: `./scripts/build-distro.sh --online`
6. Rebuild the image and restart: `docker compose build openmrs && docker compose up -d openmrs`

**To add a new report type:** report types are driven by the clinicreporting module's REST layer, not by editing the static page. New report types require changes to the module's Java/REST controllers.

### Program Dashboards

The Program Dashboards list programmes and show per-programme indicators over a date range, with a prior-period comparison for every tile. They are separate pages from OpenMRS's own programme list (which links to enrolment forms) — this is intentionally a different job.

**To add or edit indicators (no rebuild, no restart):**

1. Copy the bundled definition file to the module config directory:
   ```bash
   cp clinicreporting/api/src/main/resources/program-indicators.json \
     <openmrs-webapp>/WEB-INF/module-config/clinicreporting/program-indicators.json
   ```
   On the running Docker container this path is `/openmrs/data/module-config/clinicreporting/program-indicators.json`.

2. Edit the copy. Each programme entry needs a UUID (find it in **Administration → Manage Programs**) and a list of indicators:
   ```json
   {
     "programs": [
       {
         "uuid": "<program-uuid>",
         "name": "Program Name",
         "shortName": "SHORT",
         "description": "Description",
         "indicators": [
           {
             "id": "active",
             "label": "Active clients",
             "metric": "activeEnrollments",
             "chart": "none"
           }
         ]
       }
     ]
   }
   ```

3. Save the file and reload the page — the file is re-read when it changes. An invalid file is reported rather than silently ignored.

4. Available metrics:

   | Metric | Figure | Needs |
   |--------|--------|-------|
   | `activeEnrollments` | Patients holding an enrolment overlapping the period | — |
   | `newEnrollments` | Patients whose enrolment began inside the period | — |
   | `completedEnrollments` | Patients whose enrolment was completed inside the period | — |
   | `enrolledByGender` | Active enrolment split by recorded gender | — |
   | `enrolledByAgeBand` | Active enrolment split into age bands | — |
   | `enrollmentsByMonth` | New enrolments per month (trend line) | — |
   | `encounters` | Encounters belonging to the programme's patients | — |
   | `patientsWithObservation` | Patients with at least one recorded observation | `concepts` |
   | `observationCount` | Observation rows | `concepts` |
   | `lowValueCount` | Observation rows whose numeric value is under `valueBelow` | `concepts`, `valueBelow` |
   | `averageValue` | Mean numeric value | `concepts` |
   | `valueDistribution` | Observations split by coded answer | `concepts` |
   | `positiveRate` | Numerator patients ÷ denominator patients | `concepts`, `denominatorConcepts` |
   | `enrolledCoverageRate` | Numerator patients ÷ programme active enrolment | `concepts` |

   Concepts are named by name (not UUID) so definitions are portable across OpenMRS distributions. Use `{"uuid": "..."}` when the server is known. A chart may be `none`, `bar`, `hbar`, `line` or `doughnut`. `target` is optional; `higherIsBetter` defaults to `true`.

5. To add indicators for a new programme, find its UUID in **Administration → Manage Programs**, add a new entry to the `programs` array.

**To bundle changes into the image:**

Edit `clinicreporting/api/src/main/resources/program-indicators.json` (the bundled starting point), then:
```bash
./scripts/build-clinicreporting-module.sh
./scripts/build-distro.sh --online
docker compose build openmrs && docker compose up -d openmrs
```

## Sync model (B2 thin synchronizer)
- **Hub**: 4th OpenMRS instance at district (no agent). Relays via fhir2.
- **Spoke agents**: Spring Boot (Java 17), poll local fhir2 `_lastUpdated` with watermarks; `PUT /{ResourceType}/{uuid}` for idempotency.
- **Provenance**: `PHU360 Sync Origin` identifier (FAL/MON/SIN/HUB); agent pushes only origin=self.
- **Identity**: EMR IDs + hub-issued `PHU360 District ID`. Auto-link on strong IDs, manual review queue for ambiguous.
- **Providers**: Practitioners synced; provider refs mapped by identifier.
- **Scope**: Patient, Person, Encounter, Observation, Condition, MedicationRequest/Dispense, ServiceRequest, Immunization, AllergyIntolerance, DiagnosticReport, Practitioner, Location, Visit (via Encounter).

## Phase 0 validation (before full rollout)
1. PUT preserves UUID
2. `_lastUpdated` populated on write/update
3. Echo control via origin identifier
4. Practitioner mapping roundtrips
5. Visit vs Encounter handling
6. Observation value fidelity (incl. complex obs)
7. Pagination with `_count`

## Troubleshooting
- See earlier troubleshooting in this file for distro build issues
- Sync agent logs: `docker compose logs sync-agent-*`
- OpenMRS logs: `docker compose logs openmrs openmrs-hub`

## Development notes
- `sync-agent/` is a standalone Spring Boot service (not an OpenMRS module)
- `distro/` + `content/` built by OpenMRS SDK; `clinicreporting/` is a custom module
- `clinicreporting/` is the merged reporting module: it folds in the old `phu360reporting`
  (facility dashboard + per-report-type indicator tables) and is built by
  `scripts/build-clinicreporting-module.sh` (standard Maven `api` + `omod`)
- Identifier types added: `PHU360 District ID`, `PHU360 Sync Origin`
