# Under Five IMNCI Register (2 months - 5 years)

The register the printed workbook `source/under5-register-imnci.xlsx` (sheet
`2 Month-5 Years Register`) describes, digitised: a field specification, the
concept dictionary it needs, and the htmlformentry document pihcore loads on
boot.

This register is published and filling encounters, so nothing here may move a
concept that already has data behind it. The form UUID, the encounter type and
every concept UUID are inputs, pinned in `concept-uuids.json` and read from the
dictionary as it already ships - not outputs of these scripts.

## Regenerating

```sh
./generate_all.sh              # concepts, concepts CSV, form, then verify
./generate_all.sh --check      # verify only
```

The steps are ordered because the concepts settle first: the CSV is what loads
them, and the form is what records against them.

| Step | Script | Writes |
| --- | --- | --- |
| Read the workbook | `parse_source.py` | `source/parsed.json` |
| Settle concept identity | `register_concepts.py` | `concept-uuids.json`, `concepts-to-create.json` |
| Write the dictionary rows | `generate_concepts_csv.py` | `content/.../concepts/underFiveIMNCIConcepts.csv` |
| Write the form | `generate_form.py` | `content/.../pih/htmlforms/underFiveIMNCIRegister.xml` |
| Check the register | `verify_source_coverage.py`, `verify_form.py`, `verify_report_mapping.py` | nothing |

`load_concepts.py` is separate and optional: it creates the concepts on a running
server over REST, for testing the form before a distro build has been redeployed.
It never modifies a concept that already exists.

## The files

- `u5_spec.py` - the field specification. Sections, their printed columns, and
  each question's type and concept. This is the register, as code.
- `register_concepts.py` - concept identity. Walks the spec and pins every UUID,
  minting one only for a concept the dictionary has never had.
- `generate_concepts_csv.py` / `generate_form.py` - the two outputs.
- `SOURCE_ANOMALIES.json` - what the workbook says that the form does not do, and
  why. Read this before changing `u5_spec.py`: it is the record of the decisions.
- `parse_source.py` / `source/parsed.json` - the workbook's printed columns.
- `concept-uuids.json` - the identity of every concept in the register. Never
  regenerate this; `register_concepts.py --seed` is what created it, from the CSV
  as it shipped, and re-seeding from a regenerated CSV would pin whatever the last
  generation happened to produce.

## What the workbook prints and what the form asks

Three places where the printed column and the first digitisation of it did not
agree, all resolved in `SOURCE_ANOMALIES.json`:

- **`Screened for disability (Y/N)` and `Disability (Y/N/NA)`** were tick boxes.
  A tick box can only say yes, so a child screened and found not disabled and a
  child not screened at all recorded identically, and the denominator of every
  disability figure was unknowable. Both are now questions with the register's
  own printed answer marks.
- **`Drug(s) Provided (No.)`** and the ten drugs under it were tick boxes. The
  heading says `(No.)`; the drugs are counts now. A course of treatment is
  several doses, and a tick records only that something was given.
- **`Appetite test`** had only a `pass` tick. A failed appetite test is one of the
  criteria for classifying severe acute malnutrition and cannot be inferred from
  the absence of a pass, so `fail` was added.

`Age (Months)` is captured rather than read from the patient header, because the
header carries age in years and this register's own bands are read off months -
its title is "AGE 2 MONTH-5 YEARS", its MUAC column "6M-59M", its WHZ column
"2M-59M". `Name` and `Sex` are the reverse case and are read from the patient
record, so the printed register and the patient header cannot disagree about who
the row is about.

Every other printed column is captured as the register prints it. The 52 sign,
condition and service tick boxes stay tick boxes.

## A question left alone is not the same as an answer

Every dropdown names its empty option explicitly. htmlformentry preselects the
first answer of a dropdown that does not say otherwise, so a question the user
never touched would have been saved as if they had chosen the first answer - on
this register, every visit as a New visit, every disability as Physical, every
outcome as Improved. Nothing on the form would have shown it.

## The report reads this form

`clinicreporting/api/src/main/resources/reportmappings/under-five-register.xml` names this
register's concepts by string, and a name the database does not have is dropped
and shown as `conceptsMissing` rather than as an error. `verify_report_mapping.py`
is what keeps that from happening quietly: it checks every name in the mapping is
a concept this form asks about, and that each condition's counting rule matches
how the form records it.

Three counting rules, because three columns of this register are not tick boxes:

| Rule | Counts | Used for |
| --- | --- | --- |
| `yes` (default) | this concept answered with the dictionary's "Yes" | the 41 sign, condition and service tick boxes |
| `recorded` | any value above zero | the 10 drug counts |
| `answer` | one of a named set of answers to its own question | `U5 Has disability` answered `U5 Yes` |

## Checking the report mapping against a built module

`tools/CheckUnderFiveMapping.java` reads the shipped mappings off the module
classpath and checks the condition list came through whole, with all three
counting rules present. It is in the module's own package because
`ReportMappings.load()` is package-private, which is itself deliberate.

```sh
bash scripts/build-clinicreporting-module.sh
CP=clinicreporting/api/target/classes:$(ls clinicreporting/lib/*.jar | tr '\n' ':')
javac --release 11 -cp "$CP" \
    -d /tmp/opencode/harness openmrs-forms/under-five-imnci-register/tools/CheckUnderFiveMapping.java
java -cp "$CP:/tmp/opencode/harness:$PWD/openmrs-image/clinicreporting-1.0.0-SNAPSHOT.omod" \
    org.openmrs.module.clinicreporting.facility.report.CheckUnderFiveMapping
```



## What the generator cannot decide for you

Twelve concepts changed datatype in this generation, and OpenMRS will not change
a concept's datatype under observations that already point at it. A server
carrying the previous dictionary therefore needs a migration before these
questions render as written; `load_concepts.py` reports which concepts are in that
state by name and refuses to touch them. That is deliberate: migrating somebody's
dictionary is an operation to be chosen, not a side effect of regenerating a form.