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
- `distro/` + `content/` built by OpenMRS SDK; `phu360reporting/` is a custom module
- Identifier types added: `PHU360 District ID`, `PHU360 Sync Origin`
