# PHU360

Peripheral Health Unit 360 — a reproducible **PIH Sierra Leone OpenMRS distribution**: OpenMRS core 2.8.9, the PIH SL module set, Initializer 2.12, the MOH-branded O3 SPA, and the Sierra Leone (`sierraLeone`) config — built fully **offline** by the OpenMRS SDK and run with Docker Compose.

## Quick Start

Requirements: Docker, Maven 3.9+, and a JDK 17+. `install-prereqs.sh` detects what you already have and installs only the missing pieces (Debian/Ubuntu and macOS).

```bash
scripts/install-prereqs.sh         # optional: install missing Docker/Maven/JDK
scripts/seed-distro-maven-repo.sh  # one-time per machine: seeds ~/.m2 + config
scripts/build-distro.sh            # offline build -> distro/target/distro/web
docker compose up -d --build       # first boot: 20-45 min (Initializer)
```

Open [http://localhost:8090/openmrs](http://localhost:8090/openmrs) (O3 SPA at `/openmrs/spa`), log in `admin` / `Admin123`, pick a location (e.g. KGH).

> First build on a fresh machine: run `scripts/build-distro.sh --online` once (downloads the Maven plugin set), then offline builds thereafter.

## Repository layout

```
pom.xml / content/ / distro/   Maven parent + content package + SDK distro (see below)
scripts/                       install-prereqs.sh, seed-distro-maven-repo.sh, build-distro.sh,
                               patch-reportingui-module.sh, patch-coreapps-module.sh,
                               build-phu360reporting-module.sh, prune-runtime-config.sh
docker-compose.yml, .env.example   openmrs + MySQL 5.7 stack
openmrs-image/                 provenance inputs: distro manifest baseline, branded SPA overlay, patched pihcore/reportingui/coreapps omods
openmrs-forms/                 register form sources + generators (mother-neonate-register/), legacy patched-WAR tooling — provenance only
```

## How it's assembled

- **`content/`** — the content package. `seed-distro-maven-repo.sh` materializes the full config into the gitignored `content/build/`: the stock PIH SL image config overlaid with the tracked `configuration/backend_configuration/` delta (MOH branding, themes, htmlforms, registers, reports, `-mongo`/`-falaba`/`-sinkunia` site profiles) minus `content/exclusions.txt` (15 stock data-export descriptors). The SDK installs it as `openmrs_config`.
- **`distro/openmrs-distro.properties`** — the distribution manifest: `omod.*` pins identical to the stock PIH SL baseline plus the branded SPA coordinates and `content.phu360-content`. `build-distro.sh` cleans `distro/target/distro` before each SDK run so stale output never masks edits.
- **`distro/Dockerfile`** — pins `openmrs/openmrs-core:2.8.9` and copies the six distribution outputs from `target/distro/web`.

## Reports page: the DASHBOARDS category

`/openmrs/reportingui/reportsapp/home.page` lists the dashboards under a
**DASHBOARDS** heading of their own, above Overview Reports and Data Exports:

| Section | Contents |
|---------|----------|
| DASHBOARDS | DHMT KPI Dashboard, PHU360 Reporting Dashboard |
| Overview Reports | Registration Overview, Check-in Overview (stock PIH SL) |
| Data Exports | stock PIH SL data exports |

reportingui renders that page from a hand-written GSP whose section headings
are hardcoded, and its extension points are fixed at module build time, so a
new category is not a config-only change. `scripts/patch-reportingui-module.sh`
therefore ships a patched reportingui omod:

- **Links** — the two entries in
  `content/configuration/backend_configuration/appframework/program_dashboards_extension.json`
  bound to `org.openmrs.module.reportingui.reports.dashboards` (ids
  `reportsDashboard.*`; the same two dashboards are also bound to
  `pih.app.programSummaryList.apps` under ids `programDashboard.*`, so the file
  holds both bindings rather than duplicating the definitions across two files).
- **Extension point** — declared in `openmrs-image/reportingui/apps/reports_app.json`
  alongside the stock overview/dataquality/dataexport points.
- **Page** — `openmrs-image/reportingui/web/module/pages/reportsapp/home.gsp`
  is a full copy of the upstream page with one extra `reportBox`; every stock
  section is unchanged.
- **Resolution** — `omod.reportingui.type=omod` in `distro/openmrs-distro.properties`
  makes the SDK pick the patched omod up from `~/.m2`. Without it the SDK
  resolves the stock omod (staged as a `.jar`) and the patch is silently lost.

Bumping reportingui means re-checking the two overlay files against the new
upstream page; the patch script fails if the page is not the one it expects.

## Where app definitions have to live

The appframework reads app definitions **only from the classpath**
(`AppConfigurationLoaderFactory` scans `classpath*:/apps/*app.json`,
`classpath*:/apps/*extension.json` and `classpath*:/apps/*AppTemplates.json`).
`content/configuration/backend_configuration/appframework/*.json` is installed
as a plain directory under `/openmrs/data/configuration` — *not* on the
classpath — and there is no `appframework` Initializer domain, so a file sitting
there is inert. Editing those files alone changes nothing at runtime; a link
added to `program_dashboards_extension.json` simply never appears.

`scripts/build-phu360reporting-module.sh` therefore copies
`content/configuration/backend_configuration/appframework/*.json` into the
phu360reporting omod's `apps/` directory as part of assembling the module, which
is what makes the tracked config authoritative. File names are load-bearing and
already match the loader's two patterns (`*_app.json`, `*_extension.json`).

Carrying them in a module is also what makes the register buttons work: they are
`patientDashboard.overallActions` / `patientDashboard.visitActions` free-standing
extensions, and coreapps' patient dashboard only renders what the appframework
knows about.

One caveat: the 11 entries in `home_extension.json` are bound to
`org.openmrs.referenceapplication.homepageLink`, a point owned by the
`referenceapplication` module, which is not part of this distribution. They load
without error and stay invisible; nothing in PIH SL reads that point.

## Program Dashboards page: hiding the per-program tiles

`/openmrs/coreapps/applist/appList.page?app=pih.app.programSummaryList` starts out
with a tile per Program — AYFS, Family Planning, Gynecology, PMTCT, Pregnancy
Mental Health, Pregnancy, Infant, Mental Health, NCD. PHU360 wants only the
DHMT KPI and PHU360 Reporting dashboards, so the page ends up with two tiles.

Those nine are not config, so no appframework JSON can remove them. pihcore
builds them in Java at startup: `CustomAppLoaderUtil.addToProgramSummaryListPage()`
adds a `<program app id>.appLink` extension, pointed at
`pih.app.programSummaryList.apps`, to **each per-program app descriptor**. The
program app ids themselves end in `.programSummary.dashboard`, so the extension
ids end in `.programSummary.dashboard.appLink` — visible in the page HTML, where
the GSP turns dots into dashes (`pih-app-<uuid>-programSummary-dashboard-appLink-app`).

`Phu360ReportingActivator.started()` walks `AppFrameworkService.getAllApps()` and
drops every extension whose extension point is `pih.app.programSummaryList.apps`
and whose id ends in `.programSummary.dashboard.appLink`, leaving the two
`programDashboard.*` links from `program_dashboards_extension.json` untouched.
Two details make this work:

- The extensions hang off the per-program apps, not off
  `pih.app.programSummaryList`, so every app has to be visited. Pruning the
  programSummaryList descriptor alone matches nothing and silently does nothing.
- Editing the descriptors is enough, because apps live only in memory (there is
  no app/extension table) and `AppFrameworkServiceImpl.getAllEnabledExtensions()`
  re-reads `app.getExtensions()` on every page render.

`phu360reporting/module/config.xml` therefore requires **both** pihcore (so its
activator has finished registering apps first) and appframework (module
classloaders do not see each other's APIs otherwise, and the activator dies with
`NoClassDefFoundError: AppFrameworkService`). `require_module` only takes effect
inside a `<require_modules>` wrapper, and the version must match the installed
module's version string exactly — pihcore is `2.2.0-SNAPSHOT`, not `2.2.0`.

Two operational notes: the module build has to be re-run and the container
restarted for the change to take effect (`docker compose up -d --build`; the omod
is copied into the image, not bind-mounted), and the module's own log lines do
not reach `openmrs.log` because the module classloader's logger context is not
attached to the webapp's — verify the page HTML, not the log.

## Under Five, Above Five & Mother/Neonate registers

The patient dashboard ships three register buttons (Overall + Visit Actions), all
addressed by `formUuid`:

| Button | htmlform | `formUuid` |
|--------|----------|------------|
| Mother and Neonate Health Register | `motherAndNeonateRegister.xml` | `3f2b7c14-9d6a-4e58-b0c3-71a4d9e25f86` |
| Delivery Register | `deliveryRegister.xml` | `dc288387-522e-5506-9607-ef2d20fdd7ef` |
| Family Planning Register | `familyPlanningRegister.xml` | `6ed8a9e5-1341-5193-9882-c1e0600f1d48` |

- **Links** — `content/configuration/backend_configuration/appframework/patientdashboard_registers_extension.json`.
  Every button uses `formUuid` rather than `htmlFormId`: a numeric id depends on
  how many forms the database has already seen, so an `htmlFormId` link breaks
  as soon as the load order changes or a form row is purged.
- **Above Five / Under Five were removed.** The four buttons pointing at
  `aboveFiveTreatmentRegister.xml` and `underFiveRegister.xml` are gone, along
  with both XMLs. They were already unusable: the Under Five form references 80
  numeric concept ids of which 8 existed in the database, and Above Five
  references 120 concept UUIDs of which none existed — neither form's concepts
  were ever shipped. Both are recoverable from git history if the concepts are
  ever exported.
- **Forms** — pihcore's `HtmlFormSetup` loads every XML in
  `pih/htmlforms/` on boot via `saveHtmlFormFromXml`, which keys on the
  `formUuid` attribute, so re-running it is idempotent.
 - **Register forms** — three forms share one generator in
   `openmrs-forms/mother-neonate-register/`, and all three are addressed by
   `formUuid` on the patient dashboard (the numeric-id reasoning above applies
   to each of them):

   | Form | `formUuid` | Encounter type | Fields |
   | --- | --- | --- | --- |
   | Mother and Neonate Health Register | `3f2b7c14-9d6a-4e58-b0c3-71a4d9e25f86` | Maternity and Delivery Register | 266 |
   | Delivery Register | `dc288387-522e-5506-9607-ef2d20fdd7ef` | Delivery Register | 47 |
   | Family Planning Register | `6ed8a9e5-1341-5193-9882-c1e0600f1d48` | Family Planning Register | 24 |

   The Mother and Neonate form is an **extension** of the one already in
   production: every previously rendered code/concept pair is still present, so
   existing encounters keep displaying. The Delivery and Family Planning forms
   are new.

   Regenerate, in order, after editing any spec or source file:

   ```
   cd openmrs-forms/mother-neonate-register
   python3 register_spec.py        # sections, form/encounter UUIDs
   python3 register_concepts.py    # concepts-to-create.json + concept-uuids.json
   python3 generate_form.py        # the three form XMLs (asserts 0 missing concepts)
   python3 export_initializer_concepts.py
   ```

   `verify_source_coverage.py` and `parse_source.py` check the forms against the
   source spreadsheet and should be run first if the mapping changed.

 - **Register concepts** — the three forms' 348 concepts ship as Initializer
   metadata in `concepts/registerConcepts.csv` (named for all three registers
   because it replaced the earlier MNR-only `mnrRegisterConcepts.csv`). The
   Yes/No/Not applicable answer concepts are reused from the base dictionary
   rather than redeclared. Two header conventions in that file are load-bearing
   and fail silently if you get them wrong:
   - Concept-name columns are `<name type>:<locale>` — `fully specified name:en`,
     **not** `name:en:fully specified name`. `ConceptLineProcessor` only treats a
     header as a name when the first `:`-segment starts with `fully specified
     name`/`short name`/`synonym`/`index term`, so a mis-ordered header is
     ignored and every row fails with `At least one non-empty name is required`.
     The `preferred` and `uuid` columns hang off that same base as a third
     segment.
   - The description column must be locale-decorated (`description:en`). A bare
   `description` header parses to a `LocalizedHeader` with an empty locale set,
     so descriptions are dropped without any error.
 - **Concept UUIDs are derived, not minted** — `register_concepts.py` keeps
   `concept-uuids.json` as the source of truth. The 133 concepts created before
   the registry existed keep their original UUIDs (observations point at them);
   everything added since is `uuid5`-derived from the concept name, so the same
   name yields the same UUID on any machine. `load_concepts.py` pushes them to a
   running instance and honours a supplied `uuid` on POST, so it is safe to re-run
   and will report a name that exists under a different UUID rather than silently
   accepting it. Do not introduce a loader that lets the server mint UUIDs: it
   would overwrite the registry and the forms would stop matching it.
 - **Concept names avoid `&`, `<`, `>`** — the REST API HTML-escapes what it
   stores, so a name containing `&` is persisted as the literal text `&amp;`.
   `generate_concepts_spec.sanitize()` substitutes words instead, and the form
   still displays the original label.

- **Legacy tooling** — `openmrs-forms/above-five-treatment-register/` standalone
  sources & patched-WAR build scripts target an older referenceapplication-based
  OpenMRS and are not consumed by this 2.8.9 build.

## Configuration

Copy `.env.example` to `.env` and adjust:

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENMRS_IMAGE` | `phu360:latest` | Locally-built image name |
| `OPENMRS_PIH_CONFIG` | `sierraLeone,sierraLeone-falaba` | PIH site config chain; unsupported profiles fail startup with `HTTP Status 500` / `Error loading PIH config` |
| `OPENMRS_USERNAME` / `OPENMRS_PASSWORD` | `admin` / `Admin123` | Admin user |
| `OPENMRS_DB_*` | `openmrs` / `Admin123` | MySQL credentials |

### Patient EMR IDs

- The legacy `Driver's License` identifier type is renamed **`EMR ID`** in
  `content/configuration/backend_configuration/patientidentifiertypes/identifierTypes.csv`
  (uuid `c09a1d24-7162-11eb-8aa6-0242ac110002`; also the queue/registration
  display labels via `messageproperties/messages-sl_en.properties`).
- pihcore 2.2.0 hardcodes a `KGH Primary Identifier Source`
  (`idgen_seq_id_gen`, prefix `'KGH'yyMM` → e.g. `KGH26090001`) for Sierra
  Leone and re-applies it on every boot. This distro targets the Falaba PHU, so
  the generator is held on the **FAL** prefix:
  - The OpenMRS db service runs with `--event-scheduler=ON`, and
    `scripts/emr-id-falaba-guard.sql` installs three scheduler events: two
    re-assert the `'FAL'yyMM` prefix (and source name) every 15s, and a third
    (`emr_id_reg_facility_guard`, every 30s) normalizes each EMR ID's location
    up to its top-level CHC — so the patient-search **Reg Facility** column only
    ever shows one of the three health centers (Falaba CHC, Mongo Bendugu CHC,
    Sinkunia CHC) even when staff register from a sub-location (Clinic/Triage).
    Apply against a fresh DB once (the running volume already has it):
    `docker exec -i phu360-openmrs-db-1 mysql -uroot -pAdmin123 openmrs < scripts/emr-id-falaba-guard.sql`.

## Running in production

The compose stack starts `admin`/`Admin123` with `-test` profiles and well-known passwords — fine for evaluation, not for a live site. Before you go live:

**Secrets & profiles**
- `cp .env.example .env` and set strong values for `OPENMRS_PASSWORD`, `OPENMRS_DB_PASSWORD`, `OPENMRS_DB_ROOT_PASSWORD` (`.env` is gitignored).
- Ditch the test profile: set `OPENMRS_PIH_CONFIG` to your real site chain (e.g. `sierraLeone,sierraLeone-kgh`), not `sierraLeone-kgh-test`.
- Prefer a managed/external MySQL 8 over the bundled `mysql:5.7` (5.7 is EOL). Point `OMRS_DB_HOSTNAME` etc. at it and drop the `openmrs-db` service; if you keep the bundled DB, the `3307` port must stay `127.0.0.1`-bound (or remove the mapping so only the compose network reaches it).

**TLS / exposure**
- `8090` is also `127.0.0.1`-bound by default — put a TLS-terminating reverse proxy (Caddy, nginx, Traefik) in front of it. OpenMRS itself stays on plain HTTP inside the stack.
- Remove the `3307` host-port mapping unless an operator really needs it.

**Backups** (practice this before you need it):
```bash
docker compose exec -T openmrs-db sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" openmrs' > "openmrs-$(date +%F).sql"
```
Also snapshot the `phu360-data` volume (modules, Lucene index, complex obs, runtime properties). Restoring = restore both, then `docker compose up -d`.

**Updates**
- Upgrade in a maintenance window with a fresh backup. Pull the new config, then `scripts/build-distro.sh && docker compose up -d --build`; OpenMRS runs DB migrations automatically on boot (`OMRS_AUTO_UPDATE_DATABASE=true`), and the first boot of a new image can take a while (Initializer).
- Add `restart: unless-stopped` to both services so the stack survives host reboots/crashes.

**Monitoring & sizing**
- The compose healthcheck already probes `/openmrs/ws/rest/v1/session` — wire it into an uptime monitor, and watch `docker compose logs -f openmrs`.
- The image defaults to `-Xms512m -Xmx4g`; raise `-Xmx` with expected concurrent load and give MySQL its own 4-8 GB of host RAM.

## Rebuilding after edits

```bash
scripts/build-distro.sh && docker compose up -d --build
```

`build-distro.sh` re-applies `content/configuration/backend_configuration` over
the materialized `content/build/` config, rebuilds the phu360reporting omod and
the patched reportingui omod, and clears `distro/target/distro` before the SDK
run — so config, module and page edits all reach the image.

`docker compose up -d --build` alone does **not** re-materialize config: the
image is built from `distro/target/distro/web/openmrs_config`, which only
`build-distro.sh` refreshes. After editing anything under
`content/configuration/`, re-run the build script or the image keeps shipping
the previously materialized config.

The overlay is a `cp -a`, which overlays but never deletes. **Renaming or
removing a file under `content/configuration/` leaves the old copy behind in
`content/build/`**, and the build keeps shipping it. That is how a stale
`concepts/mnrRegisterConcepts.csv` ended up beside `registerConcepts.csv` — two
CSVs declaring the same concept names. When you remove a config file, delete it
from `content/build/configuration/backend_configuration/` too, or re-run
`scripts/seed-distro-maven-repo.sh` to re-materialize from scratch.

## Keeping a running install in sync

`content/build/` is only the build tree. The *live* config lives in the
`phu360-data` Docker volume, and the image entrypoint extracts
`openmrs_config` into it with a plain `cp -R` on every boot — it never deletes.
So a file removed from the config stays in the volume and keeps being served,
and pihcore's `HtmlFormSetup` re-loads every XML it finds in
`pih/htmlforms/` on each boot, which puts "deleted" forms back into the form
list. The `form` / `form_resource` / `htmlformentry_html_form` rows pihcore
saved for them stay behind too, keyed on the XML's `formUuid`.

`scripts/prune-runtime-config.sh` reconciles a stopped install with the built
config and drops those orphan rows:

```bash
docker compose stop openmrs
scripts/prune-runtime-config.sh --dry-run   # lists what would go
scripts/prune-runtime-config.sh
docker compose up -d openmrs
```

It deletes every file present in the volume but absent from
`distro/target/distro/web/openmrs_config` (as of this writing 179: 172 HTML
form XMLs, 5 under `pih/htmlforms/retired/`, plus `concepts/mnrRegisterConcepts.csv`
and `globalproperties/gp_labonfhir.xml`), and purges form rows that no kept
`formUuid` claims. The `KEEP` array at the top of the script is the authoritative
list of the 15 forms PHU360 keeps and is verified against the built XMLs on
every run — a form listed there but missing from the build is a hard error.
Forms that any encounter still points at are never purged and are listed first.

Re-running the seed script does not substitute for this: the stale files are in
the volume, not in the build tree.

## Troubleshooting

- **Config edit has no effect** — run `scripts/build-distro.sh` before
  `docker compose up -d --build`; see the note above. To confirm which config is
  actually live: `head -1 distro/target/distro/web/openmrs_config/concepts/*.csv`.
- **`<file>.csv ('<domain>' domain) was processed and N out of N entities were not saved`**
  — an Initializer error summary in `docker compose logs openmrs`. It names the
  file and prints the offending CSV rows; the actual cause is logged just above
  it as `An OpenMRS object could not be constructed or saved from the following
  CSV line`. Initializer still records a checksum for the file, so the domain
  will not be retried until either the file changes or the checksum under
  `/openmrs/data/configuration_checksums/` is removed and the server restarted.

- **A deleted config file is still in use** — the running install keeps its own
  copy in the `phu360-data` volume; `build-distro.sh` cannot reach it. See
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
