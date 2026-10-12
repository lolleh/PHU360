# Clinic Reporting

Reporting and analytics module for OpenMRS 2.x, in two halves:

- a **JSON REST API** exposing patient/cohort, clinical/register and LIMS/lab reports, and
- a **web UI** (PHU360) with Clinical and Patients screens, per-programme dashboards, and a Data Exports
  workspace where named, saved exports are built, run and downloaded.

The REST API is read-only. The web UI is read-only too, with one deliberate exception: the definitions the
Data Exports workspace saves live in a single module-owned table (`clinicreporting_data_export`), created by
a liquibase changeset, and nothing else is written.

- Module id: `clinicreporting`
- REST root: `/ws/clinicreporting/v1`
- Web root: `/openmrs/module/clinicreporting/{reports,patients,programs,dashboard,exports}.htm`
- Built against OpenMRS 2.8.9, bytecode target Java 11

## Privileges

| Privilege | Grants |
|---|---|
| `View Reports` | Every REST report, and the Clinical, Patients and Programme dashboard pages. The Data Exports tab is not gated by it. |
| `Manage Report Indicators` | The Data Exports workspace: browsing and previewing allowlisted clinical tables, and saving, running and downloading named exports. |
| `Run Raw Report SQL` | The raw SQL path inside the workspace. Held independently on purpose — it runs analyst-written statements, so it belongs to fewer people than the workspace. |

`View Reports` is a superuser-visible default grant in many distributions, but the two data-export privileges
are not implied by it. They are declared in `config.xml`, so they appear in
**Administration → Manage Roles → Edit Role → Privileges** once the module has loaded. Grant them there.

Avoid copying a hand-written `role_privilege` insert between servers: the columns differ by platform.
OpenMRS 2.x keys `role` and `privilege` on `role_id`/`privilege_id` with a separate `name`, while 1.10
keys both tables directly on the name (`role`/`privilege` as the primary key) with no surrogate id at all.
The same query cannot serve both.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn -B clean install
```

Produces `omod/target/clinicreporting-1.0.0-SNAPSHOT.omod`.

`mvn -o` works once the dependencies are in the local repository. Java 11 is the compile target
(`maven.compiler.release`) because OpenMRS 2.5+ requires 11 and the module has to load on any 2.x server;
a newer JDK can run the build, but 11 is what it compiles against.

### Deploying to a running server

Copy the `.omod` into the modules directory and restart. **Do not** unpack it into `WEB-INF/modules` or drop
it into an exploded webapp directory: Tomcat's WAR re-explosion then fails, and OpenMRS can drop into
initial-setup mode with every page 404ing. A stale `target/` can also leave removed files inside the
package, so build with `clean` when packaging after deleting a JSP or resource.

```bash
docker cp omod/target/clinicreporting-1.0.0-SNAPSHOT.omod kgh-openmrs:/tmp/clinicreporting.omod
docker exec -u root kgh-openmrs cp /tmp/clinicreporting.omod \
	/openmrs/data/modules/clinicreporting-1.0.0-SNAPSHOT.omod
docker exec -u root kgh-openmrs chown 1001:root \
	/openmrs/data/modules/clinicreporting-1.0.0-SNAPSHOT.omod
docker restart kgh-openmrs
```

Startup takes about three minutes on the PIH image. Confirm the module loaded by requesting a page while
authenticated, or `GET /ws/clinicreporting/v1/ping` without the privilege.

## Layout

| Path | Contents |
|---|---|
| `api/` | DTOs, DAOs, services, programme dashboard definitions and queries, transform allowlist/guard/builder, `moduleApplicationContext.xml` |
| `omod/` | REST controllers, page controllers, JSPs, exception handler, `config.xml`, `apps/`, packaging |

## Web reports

Four report pages plus the exports workspace, sharing one chrome (page header and tab strip) and one stylesheet:

| Page | Path | Gate | Shows |
|---|---|---|---|
| Clinical | `reports.htm` | `View Reports` | Encounters, obs trends, encounter types, top concepts, programme enrolment, register summary. |
| Patients | `patients.htm` | `View Reports` | Patient and cohort listings with gender, age-band and programme breakdowns. |
| Programmes | `programs.htm` | `View Reports` | Picker for the programme dashboards, listing only programmes this server has. |
| Programme dashboard | `dashboard.htm?program=<uuid>` | `View Reports` | One programme's figures over a period, against the period before — see below. |
| Data exports | `exports.htm` | `Manage Report Indicators` | The saved exports workspace — see below. |

The Data Exports tab only appears in the chrome for a user who holds `Manage Report Indicators`, and the
handler checks the privilege itself before it loads a single source or runs a single query. The
privilege is not implied by `View Reports`, and a user without it gets a plain refusal page rather than a
partly-rendered workspace.

Filters are shared across the Clinical and Patients pages: date range, location, free-text search, group-by period.
Series are dense (empty periods are filled with zero rather than vanishing from a chart), and long ranges
are bounded for the same reason the API bounds them.

The app definition in `omod/src/main/resources/apps/clinicreporting_app.json` adds a home-page link. It is
a `legacyui` page controller, so the link only appears on distributions that honour the
`org.openmrs.referenceapplication.homepageLink` extension point; the PIH distribution ignores it, which is
why the pages are reached by URL there.

## Data exports

`exports.htm` is the saved-reports workspace. A name, a source table, a column picker, per-column cleaning
steps, row filters and a row limit make up a definition; saving stores it, and a save, run or download
executes that stored definition through the same allowlist as the preview. The old `indicators.htm` URL
still resolves here, so a stale bookmark lands on a live page.

The shipped list (23 definitions over allowlisted tables, seeded the first time the page is visited) is
defined in `DataExportDefaults` and is offered as a starting point. Seeding is idempotent. Three further
names on the list (`Users Data`, `User Roles`, `User Logins`) read tables the allowlist withholds, so they
are never seeded; the page still lists them marked *Not available*, so the workspace advertises the whole
shipped list rather than three names fewer than the Reports page.

A definition produces a `SELECT` in two ways:

1. **Declarative pipeline.** Pick an allowlisted table, tick columns, and add typed steps per column:
   `TRIM`, `UPPER`, `LOWER`, `BLANK_TO`, `RECODE`, `BUCKET`, `ROUND`, `YEAR`, `MONTH`, `RENAME`, plus row
   filters (comparisons and blank/not-blank) with bind parameters, so values are never concatenated into
   the statement. An export is a **single table**: the allowlist deliberately offers no joins.
2. **Raw SQL.** An analyst-written statement, saved and run as the export's definition, for the cases a
   fixed set of steps cannot express. It is re-authorized on every save, run and download, and every
   execution is logged.

The on-screen preview is bounded (200 rows) so a long result does not become a long page; the CSV download
is served with the export's own row limit, capped at the module's absolute ceiling
(`TransformRequest.MAX_EXPORT_ROWS`, 20000). Each download is a fresh read-only run, so a saved export is
always the current data. CSV cells that would open as spreadsheet formulae (`=`, `+`, `@`, control
characters) are escaped with a leading quote, and a byte-order mark is prepended so Excel detects UTF-8.

### Security model

The design point is that **the allowlist is the security boundary, not the live metadata**.

- `ReportSourceRegistry` enumerates every table and column an export may read. Withheld entirely:
  `users`, `user_password`, `user_role`, `user_property`, `login_failure_count`,
  `authentication_event_log` — joining `users.user_id` to `person.person_id` re-identifies the clinician
  behind a record. Also withheld: `person_name`, `person_address`, `patient_identifier`,
  `provider.name`, `provider.identifier`, and free-text columns (`obs.comments`, `note.text`,
  `patient_appointment.comments`, `patient_appointment.tele_health_video_link`,
  `queue_entry.priority_comment`), because text typed by a clinician about a patient is exactly where
  the withheld names and addresses reappear. Everything not explicitly listed is withheld too, which on
  the reference server covers the remaining ~130 tables belonging to bed management, the legacy EMR
  modules and other modules that distribution carries unused (203 base tables in total, 73 allowlisted).
- Live database metadata only *narrows* the allowlist, so a column that exists on the server but is not
  allowlisted stays invisible. A column you ask for that is not offered is reported by name (the withheld
  chips on the builder) rather than silently dropped.
- What this does **not** do is stop someone counting rows in `patient` or reading `obs` through an export.
  That is inherent to reporting on clinical data at all, and it is why the workspace sits behind its own
  privilege rather than being treated as a safe read.
- Raw SQL is a single `SELECT`/`WITH` statement. No comments, and no `;` other than a single optional
  trailing one, so a second statement cannot be smuggled in behind a semicolon. No data-changing or
  session-changing keywords, no system schemas (`mysql`, `information_schema`, `pg_catalog`, …), no
  locking, no file or network access. The guard is a keyword scanner, not a SQL parser — that is why it
  sits behind its own privilege.
- Accepted raw SQL is wrapped (`SELECT * FROM (…) cr_raw_preview LIMIT n`) so the caller's row cap cannot
  be removed by writing a larger `LIMIT`, and executed on a connection marked read-only, with a query
  timeout and `setMaxRows`. A bill of constraints in the builder shows the same keywords to the person
  writing the statement, so what the guard rejects is legible in advance.
- Raw SQL execution is logged at `WARN` with username, statement, row count and elapsed time. `WARN`
  deliberately: a default OpenMRS log configuration records `WARN` and above, and an execution record that
  vanishes under normal configuration is not a record. Refusals are logged too.
- The raw privilege is checked on top of the page privilege at every step: preview, save, run and CSV all
  ask for `Run Raw Report SQL` again, so a user who can open the workspace cannot author a raw statement
  they could never run. A raw definition is refused at save time too, so it is never stored as though it
  were a report.

### Known limitations

- The audit trail for raw runs is the server log, not a queryable table.
- Exports are single-table only: the allowlist never joins two tables, so a "delivery form includes the
  clinician and the location" request needs the raw path.
- On the PIH distribution an account without the data-export privileges cannot complete login at all,
  because login itself requires `Get Providers` (held by the top privilege levels). Authorization is
  covered by controller tests rather than a live low-privilege login on that distribution.

## Access control (REST)

Every report endpoint requires the `View Reports` privilege. The check is performed explicitly in
`BaseReportController.requireViewReportsPrivilege()` rather than with `@Authorized`, because core's
authorization advice is installed in the service context, not in the DispatcherServlet context that
serves these controllers. A caller without the privilege gets `403` before any service call, so no data
is touched.

Two endpoints are deliberately open:

- `GET /ws/clinicreporting/v1/ping` and `GET /ws/clinicreporting/v1/metadata` — they expose no clinical
  data, so they can be used to confirm the module loaded without the privilege.
Both are safe to expose because neither returns clinical data.

## Common query parameters

| Parameter | Type | Notes |
|---|---|---|
| `dateFrom` | `yyyy-MM-dd` | **Required.** Strictly parsed; `2024-13-45` is rejected rather than rolled over. |
| `dateTo` | `yyyy-MM-dd` | **Required.** Inclusive of the whole day (normalised to 23:59:59.999). |
| `locationId` | integer | Location internal id. Descendant locations are included. |
| `page` | integer | 1-based. Defaults to `1`. |
| `size` | integer | Defaults to 50, capped at 1000. |
| `groupBy` | enum | `DAY`, `WEEK`, `MONTH`, `QUARTER` or `YEAR`. Defaults to the server's configured period, else `MONTH`. |
| `limit` | integer | Row cap for breakdowns. Defaults to 20. |
| `q` | string | Free text; patients match name or identifier. |
| `gender` | enum | `M`, `F` or `U` (case-insensitive, normalised to upper case). |
| `minAgeMonths` / `maxAgeMonths` | integer | Age window in months. Both must be non-negative, and min must not exceed max. |
| `encounterTypeUuid` | uuid | Restrict to one encounter type. |
| `conceptUuid` | uuid | Restrict to one concept / test. |
| `programUuid` | uuid | Restrict to one patient program. |

Booleans accept `true`/`false`/`1`/`0`. Blank parameters are treated as absent.

Ranges are bounded: a request spanning more than 2000 days is rejected. Long-running scans of the
encounter and obs tables are the usual cause of an overloaded database, and reports are read-only, so an
unbounded range is refused outright.

### Response envelope

Every report echoes the filter **as it was actually applied after normalisation**, so a client can always
tell what the server did with its request:

```json
{
  "filters": { "dateFrom": "...", "dateTo": "...", "locationId": 1, "...": "..." },
  "patients": [],
  "totalCount": 0,
  "page": 1,
  "size": 50
}
```

Paged endpoints (`/patients`, `/lab/orders`) add `totalCount`, `page` and `size`.

## Errors

Failures return a consistent body:

```json
{ "error": { "code": "invalid_filter", "message": "dateTo must not be earlier than dateFrom" } }
```

| Status | `code` | Cause |
|---|---|---|
| 400 | `invalid_filter` | Bad or missing filter parameter (`InvalidReportFilterException`). |
| 400 | `invalid_request` | Malformed argument (`IllegalArgumentException`). |
| 403 | `insufficient_privilege` | Caller lacks `View Reports`. |
| 500 | `report_failed` | Anything else. The message is generic on purpose — an unexpected exception can carry identifiers or SQL — detail goes to the server log. |

## System

Base path `/ws/clinicreporting/v1`. Open (no privilege).

| Method | Path | Description |
|---|---|---|
| GET | `/ping` | `{"status":"ok"}`. |
| GET | `/metadata` | `moduleId`, `namespace`, `privilege`, `defaultPageSize`, `maxPageSize`, `maxRangeDays`, `groupByOptions`. |

## Patient and cohort reports

Base path `/ws/clinicreporting/v1/patients`.

| Method | Path | Description |
|---|---|---|
| GET | `/` | Page of matching patients. Params: `q`, `gender`, `minAgeMonths`, `maxAgeMonths`, `locationId`, `programUuid`, `cohortUuid`, `seenInRange`, `dateFrom`, `dateTo`, `page`, `size`. |
| GET | `/summary` | Same filters; returns breakdowns under `byGender` and `byAgeBand`. |

- `cohortUuid` restricts to members of a saved cohort. Core OpenMRS 2.8.9 ships `cohort` and
  `cohort_member`, so this needs no cohort module. Membership is evaluated against the requested range,
  honouring `start_date`/`end_date` and voided flags.
- `seenInRange` restricts to patients whose latest visit falls inside the range.
- `programUuid` restricts to patients actively enrolled in a program.

## Clinical and register reports

Base path `/ws/clinicreporting/v1/clinical`.

| Method | Path | Description | Extra params |
|---|---|---|---|
| GET | `/encounters` | Encounter counts per period, as a dense series. | `groupBy`, `encounterTypeUuid` |
| GET | `/obs-trend` | Observation counts per period, dense series. | `groupBy`, `encounterTypeUuid`, `conceptUuid` |
| GET | `/encounter-types` | Encounters grouped by encounter type. | |
| GET | `/concepts` | Most frequently recorded concepts. | `conceptUuid`, `encounterTypeUuid`, `limit` (default 20) |
| GET | `/program-enrollment` | Patients enrolled per program. | `programUuid` |
| GET | `/register-summary` | `encounterCount`, `distinctPatients` and the encounter series. | `encounterTypeUuid` |

Series are **dense**: the database only returns buckets that contain data, so the service fills in empty
buckets with zero rather than letting periods vanish from a chart. The database does not know the
calendar, so bucketing is deliberately done in Java.

## Lab and LIMS reports

Base path `/ws/clinicreporting/v1/lab`.

| Method | Path | Description | Extra params |
|---|---|---|---|
| GET | `/orders` | Page of orders with results, including `orderNumber` and `accessionNumber`. | `orderTypeUuid`, `conceptUuid`, `resultValue`, `obsStatus`, `gender`, `minAgeMonths`, `maxAgeMonths`, `page`, `size` |
| GET | `/trends` | Order and result counts per period. | `groupBy`, `orderTypeUuid`, `conceptUuid`, `obsStatus` |
| GET | `/by-test` | Results grouped by test. | `limit` (default 20) |
| GET | `/by-result-status` | Results grouped by observation status — how a register separates pending from finalised results. | `orderTypeUuid` |
| GET | `/turnaround` | Turnaround time between an order and its result. | `orderTypeUuid`, `maxSamples` (default 5000) |

- Order timestamps use `COALESCE(o.date_activated, o.date_created)`: the `orders` table has no
  `order_datetime` column.
- Results link to orders via `obs.order_id`, and the result status lives in `obs.status`.
- `resultValue` matches numeric values as well as coded concept names, not just raw text.
- Median and P90 use linear interpolation between samples rather than picking an actual observation.
- `/turnaround` measures a bounded sample (`maxSamples`, default 5000) rather than the whole history, so
  the cost of the report does not grow without limit.

## Programme dashboards

`programs.htm` lists the programmes this module can report on, and `dashboard.htm?program=<uuid>` shows one
of them. They are separate pages from OpenMRS's own programme list on purpose: that list links to
enrolment forms, which is a different job from seeing how a programme is doing.

Each dashboard is filtered by an explicit date range (`dateFrom`, `dateTo`, both `yyyy-MM-dd`; leaving them
off defaults to the last six months ending today). It is computed over that range and also over the range
immediately before it, so every tile carries the change as well as the figure. The previous range is the
same span shifted back to end where the chosen one starts, so the two are always the same length whatever
was picked. Both ranges are read from the same definition, so a change cannot be explained by the two sides
having been configured differently.

### Where the definitions live

The dashboards are configured by a JSON file, in this order of precedence:

1. `<webapp>/module-config/clinicreporting/program-indicators.json` — an administrator's edited copy.
2. The `clinicreporting.programIndicatorDefinitions` global property, when it names a file.
3. `api/src/main/resources/program-indicators.json`, bundled in the jar as the starting point.

The file is re-read when it changes, so saving it and reloading the page is enough; no rebuild and no
restart. A file that has been made invalid is reported rather than silently ignored, because carrying on
with the previous definitions would show figures from a file that no longer exists.

```json
{
  "programs": [
    {
      "uuid": "2e9a4c7d-8257-4e1d-ae8f-51b29e3e7cfa",
      "name": "Prevention of Mother to Child Transmission",
      "indicators": [
        {
          "id": "active",
          "label": "Active clients",
          "description": "Clients holding an unvoided enrolment during this period.",
          "metric": "activeEnrollments",
          "chart": "none",
          "target": 500
        },
        {
          "id": "bp-controlled",
          "label": "Blood pressure controlled",
          "metric": "positiveRate",
          "target": 60,
          "concepts": [{ "name": "Controlled blood pressure" }],
          "denominatorConcepts": [{ "name": "Blood pressure" }]
        }
      ]
    }
  ]
}
```

### Measurements

`metric` must be one of:

| Metric | Figure | Needs |
|---|---|---|
| `activeEnrollments` | Patients holding an enrolment overlapping the period | — |
| `newEnrollments` | Patients whose enrolment began inside the period | — |
| `completedEnrollments` | Patients whose enrolment was completed inside the period | — |
| `enrolledByGender` | Active enrolment split by recorded gender | — |
| `enrolledByAgeBand` | Active enrolment split into age bands, with an `Unknown` band | — |
| `enrollmentsByMonth` | New enrolments per month, for a trend line | — |
| `encounters` | Encounters belonging to the programme's patients | — |
| `patientsWithObservation` | Patients with at least one recorded observation | `concepts` |
| `observationCount` | Observation rows | `concepts` |
| `lowValueCount` | Observation rows whose numeric value is under the limit | `concepts`, `valueBelow` |
| `averageValue` | Mean numeric value | `concepts` |
| `valueDistribution` | Observations split by their coded answer | `concepts` |
| `positiveRate` | Numerator patients divided by denominator patients | `concepts`, `denominatorConcepts` |
| `enrolledCoverageRate` | Numerator patients divided by the programme's active enrolment | `concepts` |

The vocabulary is closed. Every value maps onto one hand-written parameterised statement, so the file can
choose a measurement and name the concepts to apply it to, but cannot contribute SQL, a table name or a
fragment of one. Concepts are named rather than given as UUIDs, because a concept's UUID differs between
OpenMRS distributions while its name does not; `{"uuid": "..."}` is accepted where the server is known.

Three of these distinctions exist because the obvious alternative is quietly wrong:

- `lowValueCount` requires `valueBelow`. Counting every recorded value under the label "low birth weight"
  would report every infant who was weighed.
- `enrolledCoverageRate` divides by the enrolment. Naming the same concepts on both sides of
  `positiveRate` divides a number by itself and reports 100% whatever the data says.
- `patientsWithObservation` counts people, `observationCount` counts rows. A patient seen eight times is
  one of the first and eight of the second.

### When a figure cannot be produced

The honest answers are rarely zero, so the dashboard distinguishes them:

- A concept the definition names that this server does not have is shown as **not available here**, naming
  the concept — not as a count of zero.
- A rate whose denominator came out empty shows the counts it does have and no percentage.
- A failure in one indicator is reported against that indicator alone, so a broken definition cannot empty
  the rest of the page. The detail goes to the server log; the page does not repeat SQL or identifiers.
- A comparison with a period that had no figure shows no change, because "no data last period" is not
  "no change".

### Diagnoses by treatment

A cross-tab of coded diagnoses against the drug treatments ordered on the same visit is offered as a
dashboard of its own: **Diagnoses by Treatment** appears in the picker beside the programmes, whatever
programmes the server has, and is computed over the whole clinic rather than one programme. It is not an
indicator — a grid cannot be expressed as one figure — so it is built in code rather than described in
`program-indicators.json`, because that file describes programmes and every indicator in it is scoped to a
programme's patients. The tile is addressed by a reserved UUID, so a link to it behaves like a link to any
other dashboard.

A treatment counts toward a diagnosis only when both share an encounter, so a drug ordered at one visit is
never paired with a diagnosis made at another. The cell is a count of drug orders, which makes the cells
additive: only the busiest ten diagnoses and eight treatments are shown, and everything else is folded into
an "Other" row and column, so the totals still describe the whole clinic rather than the shown corner of
it. On a server without the `encounter_diagnosis` or `drug_order` table the card reports no data rather than
failing.

## Configuration

Global properties, all optional:

| Key | Default |
|---|---|---|
| `clinicreporting.defaultPageSize` | 50 |
| `clinicreporting.maxPageSize` | 1000 |
| `clinicreporting.defaultGroupBy` | `MONTH` |
| `clinicreporting.programIndicatorDefinitions` | _(unset: use the module-config file, else the bundled file)_ |
| `clinicreporting.maxExportRows` | 5000 |

Unreadable or non-numeric values fall back to the default rather than failing the report. An export's
download can ask for anything the absolute ceiling allows; `clinicreporting.maxExportRows` is only the
default a new export is offered, so it is bounded by `TransformRequest.MAX_EXPORT_ROWS` (20000).

## Schema notes

Verified against OpenMRS 2.8.9 core:

- `patient.patient_id` is the joined-subclass link to `person.person_id`. The join is
  `pe.person_id = p.patient_id`; there is no `patient.person_id` column.
- `obs` uses `person_id`, not `patient_id`.
- `orders` has no `order_datetime`; use `COALESCE(date_activated, date_created)`.
- `cohort` and `cohort_member` (with `start_date`/`end_date`) ship with core.
- `program.uuid` and `patient_program.program_id` carry the programme dashboards; enrolments are matched
  on the UUID, so a definition naming a UUID this server does not have reports nothing rather than
  reporting the wrong programme.
- No inventory schema ships with core.

The exports workspace was additionally verified live against a KGH/PHD server. That
server runs the same core (2.8.9) but with local schema deviations, which matter when hand-writing SQL
against it:

| Core 2.8.9 has | That server has instead |
|---|---|
| `visit.start_datetime` / `stop_datetime` | `visit.date_started` / `date_stopped` |
| `location.address` | `location.address1` … `address15` |
| `condition` | `conditions` |
| `obs_group` | absent |
| `drug_order_dose`, `drug_order_dose_*` | absent — dosing is columns on `drug_order` itself |


None of this breaks the workspace: it discovers columns from live metadata at runtime, and the allowlist —
not the schema — decides what may be read. It does mean a hand-written query written against core column
names fails there with "unknown column", which is a broken export rather than wrong data, but worth
checking before porting SQL.

## Implementation notes

- All native SQL uses bind parameters, never string concatenation.
- Paging is done through the Hibernate API, not `LIMIT :limit` in SQL, which is not portable across the
  databases OpenMRS supports.
- Allowlisted table and column existence is probed once and cached, because it cannot change while the server runs.
- `LIKE` patterns escape `!`.
- Bucketing is done in Java; only `CAST(... AS DATE)` is used in SQL.
- Preview and export runs use the session's own JDBC connection via `session().doReturningWork(...)` rather
  than opening a second one, and mark it read-only for the duration.
- Data export definitions are stored as JSON in one module-owned table (`clinicreporting_data_export`,
  created by the `clinicreporting-create-data-export-table` changeset). A definition typed today, typed in
  an earlier version, and read back out of the table all pass through the same allowlist and validation, so
  the stored copy is never trusted more than a freshly typed one.
- Page controllers live in `web.page`, outside core's `web.controller` component scan, and are picked up
  by `webModuleApplicationContext.xml`. They are kept out of `web.controller` because a JSP view cannot be
  resolved by the REST DispatcherServlet, which has no JSP view resolver.
- Page controllers must not let a runtime exception escape as a stack trace: they sit outside the
  `web.controller` exception handler, so validation failures are caught and shown as a form error.

## Tests

```bash
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn -B clean package
```

256 tests in `api/` and 68 in `omod/`, all offline. (`mvn test` on its own hits a dependency-unpacking
failure in this build; `package` is the command that works.)

DAO SQL is exercised against H2 through `NativeSqlTestBase`, a mapping-free Hibernate `SessionFactory`
with a thread-bound session, a minimal schema (`report-schema.sql`) and per-test rollback. The tests
cover patient/cohort, clinical, lab and programme dashboard queries against real SQL rather than mocks,
because the bugs worth catching here are join and column mistakes that a mocked DAO cannot surface. The
dashboard fixtures are built to be wrong in the ways that produce plausible numbers: re-enrolments,
voided enrolments, and patients whose observations belong to a programme they never enrolled in.

The dashboards are tested at three levels, because each one fails differently:

- `ProgramDashboardSqlTest` runs the queries and checks the figures.
- `ProgramDashboardServiceImplTest` covers what happens when a measurement cannot be made — a concept this
  server has not got, an empty denominator, one indicator failing — with a stubbed DAO.
- `ProgramIndicatorCatalogTest` covers the definition file: validation, and the nine programme UUIDs the
  dashboards were asked for. It also covers an administrator editing the file, since "editable JSON" is
  only true if a saved change is picked up.

The exports workspace is tested at its boundaries rather than through a database: the allowlist and raw SQL
guard are covered directly, `TransformRequestTest` asserts the generated SQL and its bind parameters
(including the export cap and filter binding), `DataExportTest` and `DataExportDefaultsTest` cover the
stored definition and the shipped seed list, `DataExportServiceImplTest` covers the save/retire/run/seeding
behaviour, `CsvTest` covers the download formatting and formula guard, and
`DataExportsControllerTest` drives the handler with a stubbed service to prove the privilege gate
(including the raw re-checks before preview, save, run and download), withheld-column reporting, row-limit
clamping, step validation and the CSV response. The handler accepts a `javax.servlet.http.HttpServletRequest`
proxy rather than Spring's `MockHttpServletRequest`, because the module's test classpath resolves
`servlet-api` 2.4, which predates the `AsyncContext` that mock is compiled against.

## License

Mozilla Public License 2.0. OpenMRS is a registered trademark and the OpenMRS graphic logo is a trademark
of OpenMRS Inc.

Chart.js v4.4.1 (MIT) is vendored at `omod/src/main/webapp/resources/js/chart.umd.min.js` for the report
charts, so a deployment without internet access still renders them.
