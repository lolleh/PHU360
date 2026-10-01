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

- **`content/`** — the content package. `seed-distro-maven-repo.sh` materializes the full config into the gitignored `content/build/`: the stock PIH SL image config overlaid with the tracked `configuration/backend_configuration/` delta (MOH branding, themes, htmlforms, registers, reports, `-mongo`/`-falaba`/`-sinkunia` site profiles) minus `content/exclusions.txt` (15 stock data-export descriptors, 10 htmlforms, 9 documentation CSVs). The SDK installs it as `openmrs_config`.
- **`distro/openmrs-distro.properties`** — the distribution manifest: `omod.*` pins identical to the stock PIH SL baseline plus the branded SPA coordinates and `content.phu360-content`. `build-distro.sh` cleans `distro/target/distro` before each SDK run so stale output never masks edits.
- **`distro/Dockerfile`** — pins `openmrs/openmrs-core:2.8.9` and copies the six distribution outputs from `target/distro/web`.

## Reporting dashboard: REPORT TYPE

`/openmrs/moduleResources/phu360reporting/index.html` reads its options from
`ReportCatalog` (`phu360reporting/src/main/java/.../report/ReportCatalog.java`),
which loads them from `phu360reporting/module/reportmappings/` — one XML file per
REPORT TYPE, plus an `index.xml` that lists them in filter order. A report is a
set of encounter type names and/or obs concept ids; `ReportFilter` resolves them
against the running database and emits one SQL fragment (`encounter_type IN (...)`
and/or an `EXISTS` on `obs`) that is ANDed onto the KPI, trend and breakdown
queries, so one selection filters every number on the page.

The mappings are data, not code. Editing a report's encounter types or concepts is
an edit to its XML file — no recompile of a Java array is involved, and a file that
is malformed or missing fails loudly at startup rather than showing up as a report
that quietly returns every encounter. The same is true of what a report *shows*:
its indicators and charts are declared in the same file. See
[Report type data mappings](#report-type-data-mappings) for the file format and
[Report layouts](#report-layouts-what-each-report-type-shows) for the indicators
and sections.

| Key | Label | Defined by |
|-----|-------|------------|
| `all-encounter` | All Encounter | no restriction |
| `above-five-patient-list` | Above Five Register - Patient List | the `Visit Note` encounter type the register's form is attached to |
| `under-five-register` | Under Five Register (2 Months - 5 Years) | the `Under Five IMNCI` encounter type the register's form is attached to |
| `hf1/hf2/hf3/hf5/hf12-summary` | HF1, HF2, HF3, HF5, HF12 Summary | not mapped |

Two decisions worth knowing before editing these files:

- **Names, not ids, for encounter types.** Every encounter type name is resolved
  to an id at request time and unknown names are skipped, so a catalog entry
  survives a data package that loads a different id set. The registers are
  defined this way: each is scoped to the one encounter type its own form is
  attached to, rather than to a list of service encounter types the visit might
  plausibly have been recorded under. The old 14-type guess for
  `under-five-register` named two `Primary Care Pediatric *Consult` types that
  this install retires, which is why it showed no under-five visits at all —
  see [Outpatient, AYFS & Mental Health forms](#outpatient-ayfs--mental-health-forms-and-their-encounter-types).
- **Concept ids are checked against the database.** `ReportFilter.obsConceptsMissing()`
  reports a report as unmapped when it is defined by obs concepts and this
  database has none of them, and `resolve()` drops the ids that are absent rather
  than filtering on them. The gate applies only to a layout that actually reads
  obs (`ReportLayout.readsObs()`). `above-five-patient-list` answers from the
  encounter and patient tables, so its obs-concept ids being absent from this
  database cannot change a figure on its page. A report whose layout *does* read
  obs and has no resolvable concepts comes back with `reportMapped: false` and
  `"… has no data mapping configured yet."`, which is what the `mapped` flag in
  `action=reports` and the notice under the filter bar are for.

## Report type data mappings

`phu360reporting/module/reportmappings/` holds the data mapping of every REPORT
TYPE. One file per report, named after its key, plus an `index.xml` that lists
the keys in the order the filter shows them. `ReportMappings` reads them off the
module classpath at first use; `ReportCatalog` and `ReportTables` are in-memory
views of the result and hold no mappings of their own.

They ship inside the omod rather than under `content/configuration/` because
nothing outside this module reads them — the mapping and the code that
interprets it stay one reviewable unit, and there is no question of the
initializer picking up (or failing to pick up) a new subdirectory. Editing one is
an ordinary source edit followed by a rebuild.

XML rather than JSON because the module is already built around it
(`config.xml`, `liquibase.xml`) and the JDK parses it with nothing added to the
class path; the module otherwise has no JSON parser, writing its responses with
a hand-rolled `JsonWriter`.

```xml
<reportMapping>
    <key>above-five-patient-list</key>   <!-- request key, and this file's name -->
    <label>Above Five Register - Patient List</label>   <!-- shown in the filter -->
    <table>phu360_report_above_five_patient_list</table>
    <mapped>true</mapped>                   <!-- false ⇒ reportMapped:false, table left empty -->
    <encounterTypes>                       <!-- names, resolved per installation -->
        <encounterType>Visit Note</encounterType>
    </encounterTypes>
    <obsConcepts>                          <!-- ids, checked against the database -->
        <obsConcept>8842</obsConcept>
    </obsConcepts>
    <obsValue>8853</obsValue>              <!-- value_coded required, 0/absent = any -->
    <indicators/>                          <!-- optional; see "Report layouts" -->
    <sections/>                            <!-- optional; see "Report layouts" -->
</reportMapping>
```

`above-five-patient-list` is the one mapping that still carries an
`<obsConcepts>` block, and it is the only one that can: every other report names
its concepts and answers as *strings* in `<indicators>`/`<sections>` instead, and
those are resolved per concept name at request time
(`ReportIndicators.resolve()`). The two are not interchangeable — an id list is
what a data package exported, a name list is what a form defines — so the ids
stay where they were exported and the rest of the dashboard does not need an
export it never had.

Three rules the loader enforces:

- **A bad mapping fails at startup, loudly.** A missing file, a wrong root
  element, a non-numeric concept id or a non-`phu360_report_` table name throws
  from `ReportMappings.load()`. A report whose mapping failed to load has to be
  visible at startup, not discovered on a dashboard — a silently dropped report
  looks the same as one that was never asked for.
- **The table name is checked, not trusted.** It is interpolated into SQL, which
  is only safe because the report key arriving in a request is looked up in a map
  rather than used as an identifier. A mapping file must not be a way around
  that, so a table outside `phu360_report_*` is rejected at load.
- **The build checks index and directory agree.** `scripts/build-phu360reporting-module.sh`
  fails if `index.xml` lists a file that does not exist, or if a mapping file is
  not listed — either would be a mapping that never applies.

`mapped` is the declared state; what the API reports is the declared state
narrowed by what this database can actually resolve. `above-five-patient-list` is
`mapped: true` and the Above Five register's form, `Visit Note` encounter type and
concepts are now shipped (see
[Under Five, Above Five & Mother/Neonate registers](#under-five-above-five--motherneonate-registers)),
so its encounter type resolves and the report draws. It reads only encounters,
patients and age bands, so it stays empty until Above Five visits are recorded.

### A report that cannot be drawn says which of three things is wrong

"no data mapping configured" was the only message, and it was wrong for two of the
three cases it covered. A reader sent to look for a mapping file that is sitting
in the directory cannot act on that, so the three are now named separately, each
because it has a different fix:

| Message | Means | Fix |
|---------|-------|-----|
| `has no data mapping configured yet` | the file declares `mapped: false` | write the mapping |
| `names encounter types this database does not have` | the mapping is fine; the register's encounter types are not installed | load the data package |
| `needs a data package this database has not loaded` | the mapping is fine; its obs concepts are not installed | load the data package |

The encounter-type case is checked on its own, and it is the dangerous one to get
wrong. `ReportFilter.resolve()` returns **null** when nothing resolves, and null
means *no restriction* — so a report whose encounter types and concepts both
failed to resolve would count every encounter in the database and label the total
as that register's patients. Failing loudly is the only safe answer, and the
message has to say why.

`ReportLayout.readsObs()` decides whether absent obs concepts should block a
report at all. A layout of encounter counts, patients and age bands never
consults them, so a data package it does not touch cannot change a figure on it;
a layout with any obs indicator does, and is blocked. The check is on the layout
rather than on the report, because the same obs concepts are a filter for one
report and irrelevant to another.

## Report layouts: what each REPORT TYPE shows

A mapping also declares a **layout** — the indicators and charts that report type
draws. Without one, every report drew the same four KPI cards and the same five
charts, and a report type only changed which encounters were counted. That made
the page actively misleading for a register: an MNR page whose only headline
number was "Encounters" says nothing about live births or low birth weight, which
is what the register exists to record.

A layout is therefore an `<indicators>` list and a `<sections>` list, both
optional, and the dashboard builds the page from whatever came back. Giving a
report its own design is an edit to its mapping file, not a new view in
JavaScript — `renderLayout()` in `app.js` never needs to know what a particular
register is.

```xml
<!-- excerpt: the other elements of the mapping are shown in the section above -->
<reportMapping>
    <indicators>
        <indicator>
            <key>ancFourContacts</key>
            <label>ANC contacts of 4 or more</label>
            <kind>codedRateAnyOf</kind>
            <concept>MNR PP3 Stage of ANC Contact</concept>
            <!-- The question's answers ARE the contacts, so all of these count. -->
            <answer>MNR PP3 Stage of ANC Contact :: 4</answer>
            <answer>MNR PP3 Stage of ANC Contact :: 5</answer>
            <answer>MNR PP3 Stage of ANC Contact :: 6</answer>
        </indicator>
        <indicator>
            <key>lowBirthWeight</key>
            <label>Low birth weight (under 2.5kg)</label>
            <kind>codedRate</kind>
            <concept>MNR D21 Birth weight under 2.5kg</concept>
            <answer>Yes</answer>
        </indicator>
        <indicator>
            <key>motherDeaths</key>
            <label>Women who did not survive delivery</label>
            <kind>codedRate</kind>
            <concept>MNR D12 Mother Survived Delivery</concept>
            <answer>No</answer>
            <!-- inverted: report the share WITHOUT the "No" -->
            <inverted>true</inverted>
        </indicator>
    </indicators>
    <sections>
        <section>
            <key>deliveryType</key>
            <label>Delivery type</label>
            <kind>codedBreakdown</kind>
            <concept>MNR D7 Delivery Type</concept>
        </section>
    </sections>
</reportMapping>
```

### Indicator kinds

| Kind | Answers | Needs a concept |
|------|---------|------------------|
| `encounterCount` | encounters in the selection | no |
| `distinctPatients` | distinct patients across the whole range | no |
| `meanAgeMonths` | mean age at the encounter, one decimal | no |
| `codedCount` | encounters carrying the concept with the given answer | yes |
| `codedRate` | share of the encounters **that answered** carrying any one `<answer>` | yes |
| `codedRateAnyOf` | the same, counting any of several `<answer>`s | yes, and two or more answers |
| `numeric` | encounters carrying a numeric value for the concept | yes |
| `numericMean` | mean of the recorded numeric values, one decimal | yes |

`<inverted>true</inverted>` reports a rate whose good outcome is the *absence* of
the counted answer, as the share without it. `codedRate` with answer `No` is a
complication rate and reads as a failure; inverted, it is the share of deliveries
that were uncomplicated, which is how a clinician reads it. `"Malaria treated
with ACT within 24h"` and `"malaria treated without ACT"` are a matched pair, and
showing only one of them hides half the finding.

`codedRateAnyOf` exists for a question whose good answers are a set rather than
one. The ANC register does not ask "did she attend four contacts, yes or no" — it
asks which contact this was, and the answers are the contacts themselves. A woman
at her fifth contact answers `5` and scores zero against a mapping that expected
`4`. So the mapping names every answer from the fourth contact on, and the share
is one division over the women who answered. Summing five separate `codedRate`s in
the browser would be wrong arithmetic as well as awkward: each is a share of a
different subset. Every named answer must resolve or the figure is not computed at
all — a rate quietly built from a smaller set than the mapping asked for is wrong
with nothing on the page saying so.

### Section kinds

| Kind | Draws |
|------|-------|
| `codedBreakdown` | one bar per coded answer the field actually took |
| `conditionBreakdown` | one bar per condition the layout's `<conditionConcepts>` names, where the answer was affirmative |
| `ageBreakdown` | distinct patients per under-five age band |

`<limit>` caps a breakdown's bars. The Above Five register names 102 conditions
and a hundred-bar chart is not one, so that register's own summary capped at 15;
the section also carries `declaredConditions` so the caption can say "15 of 102
recorded" rather than letting the cap read as the register's size.

A `conditionConcept` may be a bare name, or carry a `<label>`:

```xml
<conditionConcept>
    <name>MNR FP15 IMPLANTS :: 3 yr implant (ie. levoplant)</name>
    <label>3 yr implant</label>
</conditionConcept>
```

The label is worth declaring when the concept's own name is not a label. A
treatment register's concepts read as their condition text, but the family
planning dictionary names a method as the answer to a question — `MNR FP16 CONDOM
:: Male` — which is a column heading and an answer, and no use on an axis.

A condition counts **however the register recorded the tick**: as that concept
answered yes, or as that concept as the answer to the question it belongs to. A
register with a checkbox per condition records the first; one that asks "which
method?" and offers the methods as answers records the second. Only the concept
that was actually recorded is used for the label, so the second shape is
attributed to the method and not to the question.

Two things deliberately are **not** in a condition list, both because counting
them as affirmative would invert the finding: a survival question (`MNR PR1 Mother
alive`, where the yes is the *good* outcome) and a free-text measurement. The
workbook's postnatal and family planning dashboards both show a "BP ≥130/90"
figure; blood pressure is entered as text in these registers, so there is nothing
to match two numbers against without parsing the string, and the threshold would
be a guess. Those are left out of the layouts rather than shown as zero, which
would read as a clinical claim the report cannot make.

### The rules a layout has to follow

- **Concepts are named, not numbered**, exactly as encounter types are, because
  the concept ids a data package assigns differ between installations while the
  names the forms were built with do not. Names come from the register's own field
  spec — `openmrs-forms/mother-neonate-register/concepts-to-create.json` for MNR,
  `openmrs-forms/above-five-treatment-register/concepts-to-create.json` for Above
  Five.
- **A name this database does not have is dropped, not counted as zero**, and the
  response carries `conceptsMissing`. The dashboard turns that into a banner
  rather than a wall of zeroes that looks like real epidemiological data. This is
  why the Above Five register's 102 condition names resolve to almost nothing on
  an install whose data package was never loaded, and why naming a *stock* disease
  instead of the register's own tick-box would be wrong: CIEL "Hypertension"
  exists here, so the indicator would resolve, and would then count every
  hypertension diagnosis any form on the encounter recorded — a different and much
  larger number than the register's column.
- **A rate divides by the encounters that answered**, not by all encounters the
  report selects. A register row left blank is missing data, and scoring it as a
  "no" would understate the rate in proportion to how well the register is being
  filled in.
- **A mean over nothing is `null`, not `0`.** `numericMean` and `meanAgeMonths`
  send null when no value was recorded, and the card shows the same dash it uses
  before any data has loaded. A mean birth weight of 0.0kg is a claim about the
  babies; the absence of weights is a claim about the register.
- **A missing `key`, `label` or `kind` throws at load**, as it does for the rest
  of the mapping: a layout that silently loses one of its indicators is a report
  quietly showing fewer figures than it declares.

### Which reports have a layout

| Report key | Layout |
|------------|--------|
| `all-encounter` | volume only — it is the unfiltered fallback view, so the shared KPIs are the point |
| `under-five-register` | 12 indicators (volume, children seen, mean age, new/follow-up split, mean weight/MUAC/temperature, and the four IMNCI coverage checks) and 6 sections: visits by type, disease classification, outcome, disability type, age bands, and the 52 sign/condition/service tick boxes. Every concept and answer is named rather than numbered, and every name is checked against the database — see [Under Five, Above Five & Mother/Neonate registers](#under-five-above-five--motherneonate-registers) for why the names are generated from the same field spec that generates the form |
| `above-five-patient-list` | encounters, patients seen, age bands. No conditions, deliberately: the register's 102 tick-boxes are a morbidity summary, and repeating them here would give one chart two names in two files that could drift. The Morbidity Summary report type has been removed from the catalog |
| `hf1/hf2/hf3/hf5/hf12-summary` | none, on purpose — `mapped: false` with no mapping, so the API short-circuits before any layout is read. There is no data to be a layout of |

`ReportLayout.standard()` — one encounter count and no sections — is what a
report gets when it declares no layout. The encounter-shape charts (type,
location, age, sex) are drawn for every report regardless, so a report with no
layout of its own is not left without charts.

## Indicator tables: what the dashboard reads

Every number on the reporting dashboard is served from a table, not computed per
request. There is one table per REPORT TYPE, created by
`phu360reporting/module/liquibase.xml` and named by each report's mapping file,
reached through the `ReportTables` lookup (a map, so a key arriving from a request
is never interpolated into SQL). A report's numbers are the product of its
definition, so an Under Five row and a Mother and Neonate row for the same day
and center are different facts and live in different tables:

| Report key | Table |
|------------|-------|
| `all-encounter` | `phu360_report_all_encounter` |
| `above-five-patient-list` | `phu360_report_above_five_patient_list` |
| `under-five-register` | `phu360_report_under_five_register` |
| `hf1-summary` … `hf12-summary` | `phu360_report_hf1_summary` … |

`Phu360ReportingActivator` refreshes all of them on a `ScheduledExecutorService`
every **15 minutes** and once at startup. OpenMRS's scheduler module is not in
this distribution, so the module schedules itself; the executor is a daemon
thread and is shut down in `stopped()`.

### The grain: one row per day, per bucket, per dimension

Each row is `period_start`, `location_id`, `dimension`
(`kpi`/`type`/`sex`/`age`/`location`), `dimension_value`, `indicator_key`,
`indicator_label`, `indicator_value`, `computed_at` — all aggregates, no
patient-level data. A request is then a sum of days, which is what lets the
dashboard answer an arbitrary range rather than only whole months.

`location_id` is the **bucket**, not the encounter's location. `IndicatorRefresher`
writes one bucket per health center the filter offers (that center plus its
wards, pharmacy and other descendants) plus a single all-centers bucket at
`-1`. The per-center buckets are not additive, so the dashboard reads one bucket
or the other and never sums them: a person seen at two centers counts once in
each center's bucket and once in the all-centers bucket, so adding the centers up
would double count them. The all-centers bucket is computed and stored
separately for the same reason.

A refresh replaces a bucket's whole range in one transaction, deleting first, so
an interrupted refresh or a corrected day settles on the right numbers next time
rather than accumulating. The delete runs even when there is nothing to write:
an empty result means nothing happened in that period, and any rows a previous
refresh left are now wrong.

### "Patient visits", not distinct patients over a range

`patientsSeen` counts **distinct patients per day, summed over the range** — a
patient seen on three days counts three times. The old code counted distinct
patients across the whole range; the daily tables cannot hold that, and rebuilding
it would mean storing patient identifiers, which is the thing the tables exist to
avoid. The label says "Patient visits" so the number is not read as a headcount.
The same applies to the sex and age breakdowns, which are also distinct-per-day
sums. `encounters`, `newRegistrations` and `conditions` are additive and did not
change.

Age bands are computed **as of each encounter day** rather than the range's end
date, so a child who turns five mid-range appears as under 5 for the days they
were under 5.

### The fallback is a performance question, not a correctness one

`ReportBody` reads the table when the range is inside the rolling **400-day**
window the refresh keeps facts for, and computes live when it is not — a range
reaching past the window has to come from the database, and answering "no rows
yet" with zeroes would be indistinguishable from a real answer.

The two paths are held to agreeing **exactly** for the same range, so which one
answered is only a performance question. The live path therefore uses the same
bucket the table would have used (`-1` or the center, never per-encounter-location
grouping), the same previous-period selection, the same type whitelist, and
sums its trend per day before adding up months. A selection that is not one of
the buckets the refresh writes — a ward, say, rather than a health center — has
no rows of its own and is computed live, rather than answered from some other
bucket.

A report with no mapping is never visualized, so its table is left empty: the
refresh skips it and clears any rows an earlier build left behind, and the API
answers with `reportMapped: false`.

`IndicatorFacts` reaches the schema through the Hibernate session factory and raw
SQL rather than the OpenMRS services, because the refresh runs on a background
thread with no authenticated user and the services refuse to answer without one.

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

The patient dashboard ships five register buttons (Overall + Visit Actions), all
addressed by `formUuid`:

| Button | htmlform | `formUuid` |
|--------|----------|------------|
| Above Five General Treatment Register | `aboveFiveTreatmentRegister.xml` | `eaad2f41-de8f-48b7-9d40-50187fb95932` |
| Under Five IMNCI Register | `underFiveIMNCIRegister.xml` | `b359d6b5-cdc2-5244-b6b2-bf78ae2891d0` |
| Mother and Neonate Health Register | `motherAndNeonateRegister.xml` | `3f2b7c14-9d6a-4e58-b0c3-71a4d9e25f86` |
| Delivery Register | `deliveryRegister.xml` | `dc288387-522e-5506-9607-ef2d20fdd7ef` |
| Family Planning Register | `familyPlanningRegister.xml` | `6ed8a9e5-1341-5193-9882-c1e0600f1d48` |

- **Links** — `content/configuration/backend_configuration/appframework/patientdashboard_registers_extension.json`.
  Every button uses `formUuid` rather than `htmlFormId`: a numeric id depends on
  how many forms the database has already seen, so an `htmlFormId` link breaks
  as soon as the load order changes or a form row is purged.
- **Both registers that were missing are now shipped.** `aboveFiveTreatmentRegister.xml`
  and `underFiveIMNCIRegister.xml` are in `pih/htmlforms/` with their dashboard
  buttons, the encounter types they are attached to (`Visit Note` and
  `Under Five IMNCI`) are added to `sierraLeoneEncounterTypes.csv`, and their
  concepts ship as `concepts/aboveFiveConcepts.csv` and
  `concepts/underFiveIMNCIConcepts.csv`. The Above Five form and its concepts
  were exported from the database the register was built on
  (`openmrs-forms/above-five-treatment-register/` is the standalone source); the
  Under Five IMNCI form is digitised from the printed register
  "Under5 Register-IMNCI Final Print Review.xlsx", sheet
  "2 Month-5 Years Register", and covers ages 2 months to 5 years — a different
  document from the newborn register, hence a different encounter type.
- **The two were unusable before because their concepts had never been loaded.**
  The old Under Five XML referenced 80 numeric concept ids of which 8 existed,
  and Above Five referenced 120 concept UUIDs of which none existed. Both now
  resolve: every `conceptId` in either form is a UUID declared in its own
  concepts CSV, and across all three shipped CSVs there are 612 names and no
  duplicate `en` name for OpenMRS to reject.
- **26 Above Five condition concepts are suffixed.** They share a name with an
  existing PHU360 diagnosis (`Tetanus`, `Leprosy`, `Hypertension`, …) and
  OpenMRS refuses a duplicate `en` name, so those are imported under a
  ` (Above Five register)` suffix: they keep the form's UUIDs, which is what the
  form addresses them by. The Under Five concepts needed no such treatment.
- **`under-five-register` reads only its own encounter type.** It used to name 14
  paediatric and newborn service types because the form did not exist, so the
  only way to see an under-five visit was to guess which other service had seen
  the child. Now that the form exists, the report reads `Under Five IMNCI` and
  its indicators are named against that form's own fields.
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

- **Legacy tooling** — `openmrs-forms/above-five-treatment-register/` also holds
  its own standalone sources & patched-WAR build scripts, which target an older
  referenceapplication-based OpenMRS and are not consumed by this 2.8.9 build.
  The tracked form (`pih/htmlforms/aboveFiveTreatmentRegister.xml`) and the
  concepts CSV were derived from what that package had loaded, not from the
  scripts.

## Outpatient, AYFS & Mental Health forms and their encounter types

None of these htmlforms are part of PHU360. Their encounter types were the only
thing still offering them, because an encounter type is what the visit UI lists,
not a form.

| Form XML (all excluded) | formName | Encounter type | uuid |
|--------------------------|----------|----------------|------|
| `outpatient-initial.xml` (+`_v1.0`) | Outpatient Initial | PHU360 Outpatient Initial | `7d5853d4-67b7-4742-8492-fcf860690ed5` |
| `outpatient-followup.xml` (+`_v1.0`) | Outpatient Followup | PHU360 Outpatient Followup | `d8a038b5-90d2-43dc-b94b-8338b76674f3` |
| `mentalHealth.xml` (+`_v1.0`) | Mental Health Intake | Mental Health Consult | `a8584ab8-cc2a-11e5-9956-625662870761` |
| `mentalHealthFollowup.xml` (+`_v1.0`) | Mental Health Follow-up | Mental Health Follow-up | `9d701a81-bb83-40ea-9efc-af50f05575f2` |
| `ayfsIntake.xml` | AYFS Initial | AYFS Initial | `1f3375af-fd9e-42a9-954e-99533ee15de9` |
| `ayfsFollowup.xml` | AYFS Followup | AYFS Followup | `73399721-644e-494b-86c9-5dd345072db3` |
| `primary-care-adult-initial.xml` (+`_v1.0`) | Outpatient Intake (Adult) | Primary Care Adult Initial Consult | `27d3a180-031b-11e6-a837-0800200c9a66` |
| `primary-care-adult-followup.xml` (+`_v1.0`) | Outpatient Followup (Adult) | Primary Care Adult Followup Consult | `27d3a181-031b-11e6-a837-0800200c9a66` |
| `primary-care-peds-initial.xml` (+`_v1.0`) | Outpatient Intake (Peds) | Primary Care Pediatric Initial Consult | `5b812660-0262-11e6-a837-0800200c9a66` |
| `primary-care-peds-followup.xml` (+`_v1.0`) | Outpatient Followup (Peds) | Primary Care Pediatric Followup Consult | `229e5160-031b-11e6-a837-0800200c9a66` |

**Two different families are called "outpatient" in the stock config.** The
`outpatient-initial`/`outpatient-followup` pair and the `primary-care-*` pair are
unrelated files that both surface under near-identical names in the form list, so
excluding only one of them leaves forms called "Outpatient Intake" still on the
pick list. Both are in `content/exclusions.txt`.

Note `outpatientConsult.xml` / `Clinical Consult` is a *different* form on the
`Consultation` encounter type and is deliberately still shipped.

They are **retired, not deleted** — the same pattern the programs already use:

- the three PHU360-ships rows are gone from
  `content/configuration/backend_configuration/encountertypes/sierraLeoneEncounterTypes.csv`;
- `sierraLeoneEncounterTypes_retired.csv` re-declares all ten with
  `Void/Retire=true`, so a fresh install retires them on first boot and an
  existing install picks them up when that file's checksum changes;
- the file has to sort **after** `encounterTypes.csv` or the stock
  `Mental Health Consult` row is (re-)created after the retirement on a fresh
  install. `sierraLeoneEncounterTypes_retired.csv` does.

Check with the REST API the visit UI reads — the default view hides retired
types, so all ten should be missing while `/encountertype?retired=true` lists
them:

```
curl -su admin:Admin123 'http://127.0.0.1:8090/openmrs/ws/rest/v1/encountertype?v=default&limit=200'
```

### Removing a form also has to touch the data volume

The image ships only the files under `distro/target/distro/web/openmrs_config`,
but the entrypoint extracts them into the persistent `phu360-data` volume with a
plain `cp -R` and never deletes anything that used to be there. pihcore's
`HtmlFormSetup` re-loads every XML it finds on every boot, so a form whose XML is
no longer shipped stays in the form list and its `form` / `form_resource` /
`htmlformentry_html_form` rows stay behind, keyed on the XML's `formName` and
`formVersion` (not its `formUuid` — see
[Keeping a running install in sync](#keeping-a-running-install-in-sync)).
Excluding a file is therefore necessary but not sufficient — the volume and those
rows have to be cleaned too.

`scripts/prune-runtime-config.sh` does this. The forms it keeps are the ones the
image ships, read out of the built config on every run, so adding a form cannot
put the script out of step.

What deliberately **stays**:

- the mental-health *subforms*. `triage.xml` embeds
  `configuration/pih/subforms/mental-health-phq2.xml`, so removing the mental
  health forms does not license removing that widget.
- the `hiv/*`, `epilepsy*.xml`, `ultrasound.xml` and similar
  `getFormResource()` bindings in `pih/scripts/visit/encounterTypeConfig.js`.
  Those are pre-existing: the file is stock config and has always referenced
  forms this distribution never shipped. It already carried 30 such refs before
  the [program-form removal](#program-forms-not-shipped) below, which is why the
  visit UI tolerates them.
- the mental-health and NCD *report descriptors* were already excluded (see
  `content/exclusions.txt`).

Both retired report definitions used to name these types, and that is what the
retirement costs:

- The Above Five Patient List definition named `PHU360 Outpatient Initial` /
  `PHU360 Outpatient Followup`. Those were never the register's types anyway —
  its form is attached to `Visit Note`, which is what the definition names now.
  `ReportFilter` resolves only un-retired encounter types, so the old names
  resolved to nothing.
- The **Under Five Register** definition named 14 service encounter types,
  including `Primary Care Pediatric Initial Consult` and `Primary Care Pediatric
  Followup Consult`. That guessing is gone: the register has a form of its own,
  so the report is scoped to the `Under Five IMNCI` encounter type and reads
  that form's fields. Nothing is narrowed by the retirement any more — there was
  nothing left to narrow. Had the old 14-type list survived, the report would
  have stayed `mapped=true` on 12 resolving types while quietly showing no
  paediatric outpatient visits, because `ReportFilter.encounterTypesMissing` only
  fires when a report resolves *none* of its types.

## Program forms not shipped

PHU360 does not run the ANC, delivery, newborn, family planning, NCD, oncology,
OVC or COVID programmes as *clinical* workflows, so the 73 forms that captured a
visit for them are excluded: the ANC / maternity family, delivery / newborn /
NICU, family planning / gynecology / OB-GYN, the NCD initial and followup parts,
the oncology / OVC / COVID set, and the whole of `pih/htmlforms/retired/`. Their
53 documentation CSVs go with them, as do the 36 clinical measurement images
(MUAC tapes, oedema grading, the cervix inspection grid) that only those forms
used. Every file is stock config, so `content/exclusions.txt` is the only lever —
there is nothing in the repo to delete.

| Family | Files | Retired encounter types |
|--------|-------|--------------------------|
| ANC / maternity | 21 | ANC Intake, ANC Followup, ANC Progress, MCH Delivery, Labor and Delivery Summary, Labour Progress, Maternal Admission, Maternal Discharge, Postnatal Followup |
| Delivery / newborn / NICU | 16 | Newborn Initial, Newborn Assessment, Newborn Daily Progress, Newborn Discharge, Ballard Score |
| FP / gynecology / OB-GYN | 7 | Family Planning Initial, Family Planning Followup, Gynecology Initial, Gynecology Followup, OB/GYN, Cervical Cancer Screening |
| NCD | 13 | NCD Initial Consult + Part 1/2, NCD Followup Consult + Part 1/2 |
| Oncology / OVC / COVID / other | 11 | Oncology Initial Consult, Oncology Consultation, Oncology Treatment Plan, Chemotheraphy treatment session, OVC Intake, OVC Follow-up, COVID-19 Admission/Progress/Discharge, Echocardiogram, Physical Rehab Evaluation |
| `pih/htmlforms/retired/` | 5 | (none — the directory held no live encounter types) |
| **Total** | **73** | **45 retired, 6 of them from the earlier outpatient removal** |

Four consequences, each of which had to be handled in the same change:

- **Four dashboard report types are gone** — `mother-and-neonate`,
  `postnatal-care`, `delivery-register` and `family-planning`. Each was defined
  entirely by encounter types that only the deleted forms record (`ANC Intake`,
  `Postnatal Followup`, `Newborn Initial`, `NICU Triage`, `Family Planning
  Initial`, …), so with no form left nothing can ever write them and all four
  would have reported a permanent, confident `0`. They are removed from
  `index.xml` rather than left to lie. Their `phu360_report_*` tables are left
  in place: dropping a table is a data migration, and the module tolerates a
  table no mapping names. The remaining 8 report types read encounter types that
  surviving forms produce.
- **45 encounter types are retired** in
  `encountertypes/sierraLeoneEncounterTypes_retired.csv`. Retiring an encounter
  type is what stops the visit UI listing a form that is no longer there — an
  encounter type is what it lists. Verified in both directions: no retired type
  still has a surviving form bound to it, and no deleted form leaves an
  encounter type active. Three of the 45 are not obvious from the form list and
  were found by diffing the surviving set against the stock config: `Maternal
  Death`, `NICU Triage` and `Postpartum progress` each had exactly one form,
  and it was one of the 73. Six other program encounter types (`Newborn
  Observations`, `Newborn Referral`, `NICU Followup`, `Maternal Follow-up Home
  Assessment`, `Maternal Post-partum Home Assessment`) were already formless in
  stock, and are left alone.
- **The 41 matching entries in `pih/scripts/visit/encounterTypeConfig.js` are
  removed.** This file is tracked and its `getFormResource()` bindings name the
  deleted XMLs, so leaving them would 404 the visit UI on 11 encounter types.
  Retiring the types already made the entries unreachable; removing them stops
  them being reachable by a stale id.
- **The three registers survive** — `motherAndNeonateRegister.xml`,
  `deliveryRegister.xml` and `familyPlanningRegister.xml` are tracked delta files
  and are *not* excluded. A register records that a visit happened; a PHU360
  visit note still records a delivery or a family planning contact. Deleting the
  clinical forms that capture a service would leave the registers with nothing
  to summarise.

`patientRegistration-rs.xml` is now a tracked delta file declaring
`formVersion="1.1"`. It previously shared `("Patient Registration", "1.0")` with
`patientRegistration.xml`, and a form's identity is that pair — the two XMLs
claimed one form, so a purge keyed on the pair could not tell them apart. The
surviving set is 101 XMLs and 101 distinct identities, with no collisions.

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
the materialized `content/build/` config, re-applies `content/exclusions.txt` via
`scripts/apply-config-exclusions.sh`, rebuilds the phu360reporting omod and
the patched reportingui omod, and clears `distro/target/distro` before the SDK
run — so config, module and page edits all reach the image.

Exclusions are re-applied on every build, not just at seed time. That matters:
`seed-distro-maven-repo.sh` is what deletes the excluded paths, and it is skipped
once `content/build/` exists, so if only the seed script read `exclusions.txt`
then an exclusion added afterwards would change the config on a machine that
re-seeded and be silently ignored everywhere else. Both scripts now call the same
`apply-config-exclusions.sh`.

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
`distro/target/distro/web/openmrs_config`, and purges the form rows that no
shipped form claims. Forms that any encounter still points at are never purged
and are listed first so they can be checked. On the running install both halves
currently report nothing to do: the volume holds 627 config files, the same 627
the image ships, and all 170 `form` rows match a shipped form.

### A form row is identified by (name, version), not by formUuid

This is the part worth knowing before changing the script. pihcore's
`HtmlFormSetup` loads each XML with `saveHtmlFormFromXml` and keys its own
uuid→filename map on `HtmlForm.getUuid()`, but it does **not** persist the XML's
`formUuid` anywhere the purge can see it:

- `form_resource.uuid` is a freshly generated uuid. `HtmlFormSetup` builds the
  `formEngine` resource with `new FormResource()` and never sets its uuid, so it
  bears no relation to the form's. Comparing that column against the XML
  formUuids — which is what this script used to do — matches nothing that is
  kept and almost everything that is not.
- `htmlformentry_html_form.uuid` is no better. htmlformentry only reuses the
  XML's `formUuid` when a row with that uuid already exists; otherwise it mints
  a new one. Measured on the running install, **1 of the 174** shipped
  formUuids appears in that table at all.

`(form.name, form.version)` is what OpenMRS keys a form on, what `HtmlFormSetup`
writes, and what the form picker shows, so that is what the script matches on:
`formName`/`formVersion` out of each shipped XML, compared against `form`. A row
matching no shipped XML is a form this install no longer offers.

That mistake had teeth. On an install that was fully in sync, the old predicate
reported **167 of 170** `form_resource` rows as purgeable — the registers
included. Its `form_field` and `form` deletes were worse: they were scoped by
`form_id NOT IN (SELECT form_id FROM form_resource)`, and the NULL `form_id` on
the encounters created without a form make that predicate never true, so they
would have deleted the fields of every form in the database. Every delete is now
scoped to one orphan `form_id` set, child tables before parent, with the
`form` delete carrying the predicate inline because MySQL refuses to read the
table it is deleting from (error 1093).

The kept list is read out of `$BUILT_DIR/pih/htmlforms` on every run rather than
kept in the script, because a form is added by overlaying a new XML onto
`content/configuration/` and a hard-coded list of things that grow on their own
is what made this script dangerous in the first place: its 15-entry `KEEP` array
dated from when 15 forms was the whole shipped set, and against 174 it proposed
purging 177 of 179 form rows. `PRUNE_KEEP_FORMS` pins an explicit subset instead,
and a form named there but missing from the build is a hard error.

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
