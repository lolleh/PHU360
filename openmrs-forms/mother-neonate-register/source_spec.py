#!/usr/bin/env python3
"""Field specifications for the source tabs that were not in the first pass.

`parse_source.py` turns the workbook into `source/parsed.json`. This module maps
those blocks onto register codes, following the conventions already established
by `mnr_spec.py`:

  * one concept per field, named "MNR <code> <label>";
  * answer concepts for non Yes/No choices, named "MNR <code> <label> :: <option>";
  * Yes/No fields reuse the stock Yes / No concepts.

Code series (agreed with the register owner):

  M1-M20    Back tab, MOTHER block. The source prints these as D29-D48, which
            collides with the Front tab's baby block (see SOURCE_ANOMALIES.json),
            so they are renumbered into their own series.
  PR1-PR16  Back tab, MOTHER'S Postnatal Care.
  NN1-NN26  Back tab, BABY'S Postnatal Care.
  FP1-FPn   Family Planning tab.
  DR1-DRn   Delivery Register tab.

The two postnatal blocks are repeated once per visit window, mirroring how the
baby block is repeated for Baby 1 / Baby 2.

Concept UUIDs are derived with uuid5 from a fixed namespace and the concept
name, so they are stable across machines and regenerations and do not depend on
a running OpenMRS to mint them.
"""
import json
import re
import os
import uuid

# --------------------------------------------------------------------------
# Anomalies found in the three tabs added in this pass, and how each was
# resolved. Merged with mnr_spec.ANOMALIES when SOURCE_ANOMALIES.json is
# generated, so neither list can overwrite the other.
# --------------------------------------------------------------------------
ANOMALIES =     [
        {
            "where": "Back tab, MOTHER block, D29-D48",
            "problem": "The Back tab numbers the mother's immediate post-partum check D29-D48, but the Front tab already uses D29-D32 for the first baby's postnatal check, so the two blocks share codes.",
            "resolution": "Renumbered into their own M1-M20 series, which keeps the Back tab's reading order while removing the collision. Recorded against the register owner, who agreed the renumbering. The three inline prompts in D37 (actual BP), D39 (temperature) and D48 (name of person conducting the check) become M9v1, M11v1 and M20v1, giving 23 mother fields from 20 source columns."
        },
        {
            "where": "Back tab, MOTHER block, D42",
            "problem": "The label reads 'Number of Td doses taken so far' but the cells printed beside it are Yes and No, not a number.",
            "resolution": "Captured as Yes/No, matching the cells the sheet actually prints rather than the wording of the label, so the register keeps the shape its users know. Flagged to the register owner as a likely wording error in the source; if it should count doses, the field type changes to numeric and the Yes/No answers are dropped."
    },
    {
            "where": "Back tab, PR1-PR16 and NN1-NN26",
            "problem": "A single 'Y / N' header spans the whole postnatal block, so each individual column is not marked as yes/no in the workbook.",
            "resolution": "Treated every PR and NN column as Yes/No, matching the block header. They reuse the stock Yes / No concepts rather than creating per-column answers. Each block is repeated for the three visit windows '<24 hrs', '2-7 days' and '8-42 days'."
        },
        {
            "where": "Family Planning, Q3-U5",
            "problem": "'Post Partum Family Planning' is printed as a Client Type tick-box, but the three cells to its right on row 5 ('<48 hours after delivery', '49 hours-6 weeks', '7 weeks-1 year') are its sub-choices. Read left to right they look like additional Client Type values.",
            "resolution": "Parsed as a subgroup rather than flattened, and emitted as its own field 'Client Type - Post Partum Family Planning'. Client Type keeps its four genuine values."
        },
        {
            "where": "Family Planning, M4 and AW3",
            "problem": "Two printed labels contain spelling errors: 'Speeech and Language Impairment' and 'Rererred Out to other facility ( Y / N )'.",
            "resolution": "Corrected to 'Speech and Language Impairment' and 'Referred out to other facility'. The corrections are applied in parse-time so the source text is never retyped, and the coverage check reports each one."
        },
        {
            "where": "Delivery Register, AN4, AP3, AO3 and BC4-BI4",
            "problem": "Several labels print their own answer marker into the text: 'Was Parto graph/Labour care Guide used? (Y / N)', 'Heat - stable Carbetocin (HSC) Y/N', 'Uterotonic given immediately after birth? (Oxytocin / Misoprostol) ( Y / N )', and similar across the newborn and family-planning rows. 'Parto graph' is also split across two lines.",
            "resolution": "The '( Y / N )' marker is stripped from labels rendered as Yes/No fields, which reuse the stock Yes / No concepts. The split word is joined and 'Heat-stable Carbetocin (HSC)' is spelled correctly. Recorded in DR_LABEL_FIXES in source_spec.py."
        },
        {
            "where": "Delivery Register, N3-BN4 and Family Planning, Q3-W5",
            "problem": "Many columns are headed only by a bare sub-word such as 'Date', 'Time', 'From', 'To' or 'Method Used', which is meaningless without the group heading above it.",
            "resolution": "Each such label is prefixed with its group, so the Delivery Register renders 'Admission - Date', 'Delivery - Time', 'Referred - From', 'Safe Termination of Pregnancy - Method Used' and so on. The group headings themselves are carried on the fields as 'group' and are checked as consumed by verify_source_coverage.py."
        },
        {
            "where": "Delivery Register, G3 and AS4",
            "problem": "'Marital Status (S/M/W/D)' and 'Sex (M/F)' print their choices inside the label instead of as tick-boxes beneath it, unlike the equivalent Family Planning columns.",
            "resolution": "Read as choices with options S/M/W/D and M/F, and the redundant marker removed from the label."
        },
        {
            "where": "All four tabs, row 2",
            "problem": "Each register repeats a banner - Facility Name, Type, Ownership, Chiefdom/Zone, Year, Month, In Facility, Outreach. The components differ per tab: the Front and Back MNR sheets print no Ownership line, and the Delivery Register prints no In Facility or Outreach.",
            "resolution": "Mapped to encounter/location metadata rather than captured per client: OpenMRS already records the location and encounter date for every visit, and duplicating them per row would let the printed banner disagree with the encounter. The existing In Facility / Outreach concepts are already on the MNR form. The coverage check records which components each tab actually prints."
        },
        {
            "where": "Front tab, row 4",
            "problem": "The client header mixes data the patient record already holds (Client Name, Age, Address) with the register's own identifiers (Client Number, NIN) and one column covered by neither: 'Name and Contact of Responsible Person'.",
            "resolution": "Name, age and address are read from the OpenMRS patient header per the register owner's decision, and Client Number and NIN stay as form fields so a client can still be matched to the paper register. 'Name and Contact of Responsible Person' is captured as a field of its own, since it is not on the patient header."
        }
    ]


HERE = os.path.dirname(os.path.abspath(__file__))
PARSED = os.path.join(HERE, "source", "parsed.json")

BACK = "Mother & Neonate (Back)"
FAMILY = "Family Planning"
DELIVERY = "Delivery Register"
FRONT = "Mother & Neonate (Front)"

# --------------------------------------------------------------------------
# Every register repeats the same banner on row 2. It describes the encounter
# (location, reporting month) rather than the client, so it is not captured as
# form fields: OpenMRS already records the location and encounter date for every
# visit, and duplicating them per client would let the banner disagree with the
# encounter. Listed here so the coverage check can prove every component is
# accounted for rather than skipped silently.
FACILITY_HEADER_COMPONENTS = [
    "facility name", "type", "ownership", "chiefdom", "year", "month",
    "in facility", "outreach",
]

# Front tab, row 4. Name, age and address are read from the OpenMRS patient
# header; the register's own numbers are kept as form fields so a client can be
# matched against the paper register even when the patient record is incomplete.
FRONT_FROM_PATIENT = ["Client Name", "Age", "Address"]
FRONT_CAPTURED = ["Client Number", "NIN", "Name and Contact of Responsible Person"]

PREFIX = "MNR"

# Fixed namespace for concept-name -> UUID derivation. Changing this re-mints
# every generated concept, so it must never be edited once concepts exist.
NAMESPACE = uuid.UUID("6f2b7c14-9d6a-4e58-b0c3-71a4d9e25f86")

VISIT_WINDOWS = ["<24 hrs", "2-7 days", "8-42 days"]

# Labels carried in the workbook with obvious spelling errors. Corrected in the
# generated concept names because they are user-facing in the rendered form;
# recorded in SOURCE_ANOMALIES.json.
TYPO_FIXES = {
    "Speeech and Language Impairment": "Speech and Language Impairment",
    "Rererred Out to other facility ( Y / N )": "Referred out to other facility (Y/N)",
    "In Communiity": "In Community",
    "Was Parto\ngraph/Labour care Guide used? (Y / N)": "Partograph / Labour Care Guide used? (Y/N)",
}


def fix(text):
    """Apply the documented source-typo corrections, case- and space-insensitively."""
    if text in TYPO_FIXES:
        return TYPO_FIXES[text]
    flat = " ".join((text or "").split()).strip().lower()
    for original, corrected in TYPO_FIXES.items():
        if " ".join(original.split()).strip().lower() == flat:
            return corrected
    return text


# The workbook prints yes/no markers and inline choice lists into label text,
# and splits a few words across lines. Labels are cleaned once here so the
# rendered form reads correctly; each correction is recorded in
# SOURCE_ANOMALIES.json.
# The workbook prints the yes/no marker into the label text of some columns, and
# splits a few words across lines. Both are corrected here so the rendered form
# reads cleanly; recorded in SOURCE_ANOMALIES.json.
DR_LABEL_FIXES = {
    "was parto graph/labour care guide used?": "Partograph / Labour Care Guide used?",
    "heat - stable carbetocin (hsc)": "Heat-stable Carbetocin (HSC)",
}
YN_SUFFIX = re.compile(
    r"\s*[(]?\s*(?:y\s*/\s*n|y\s*n|yes\s*/\s*no)\s*[)]?\s*$", re.IGNORECASE)

def concept_uuid(name):
    return str(uuid.uuid5(NAMESPACE, name))


def _load():
    with open(PARSED, encoding="utf-8") as fh:
        return json.load(fh)


def _with_prompts(field, series):
    """Expand a parsed field into the spec's field dicts, prompts become fields."""
    out = [{
        "code": field["code"],
        "label": fix(field["label"]),
        "type": field["type"],
        "series": series,
    }]
    if field.get("options"):
        out[0]["options"] = [fix(o) for o in field["options"]]
    for i, prompt in enumerate(field.get("prompts") or [], 1):
        out.append({
            "code": f"{field['code']}v{i}",
            "label": fix(prompt["label"]),
            "type": prompt["type"],
            "series": series,
        })
    return out


# --------------------------------------------------------------------------
# Back tab
# --------------------------------------------------------------------------
# Renumbering the MOTHER block from D29-D48 to M1-M20 leaves the printed labels'
# own cross-references pointing at codes that no longer exist, and one label ends
# in a stray dash. Recorded in SOURCE_ANOMALIES.json.
MOTHER_LABEL_FIXES = {
    "State of the Perineum -": "State of the Perineum",
    "If Tear in D30": "If Tear in M2",
}

# The inline value cells printed after a question ("Temp (C):") are typed from
# what the sheet actually collects, not from the Yes/No printed beside the parent.
MOTHER_PROMPT_TYPES = {
    "Temp (C)": "number",
}


def mother_fields():
    """M1-M20, from the source's D29-D48 MOTHER block."""
    fields = []
    for i, raw in enumerate(_load()[BACK]["mother"], 1):
        code = f"M{i}"
        label = fix(raw["label"])
        label = MOTHER_LABEL_FIXES.get(label, label)
        entry = {"code": code, "label": label, "source_code": raw["code"],
                 "type": raw["type"], "series": "mother"}
        if raw.get("options"):
            entry["options"] = [fix(o) for o in raw["options"]]
        fields.append(entry)
        for j, prompt in enumerate(raw.get("prompts") or [], 1):
            plabel = fix(prompt["label"])
            fields.append({"code": f"M{i}v{j}", "label": plabel,
                           "type": MOTHER_PROMPT_TYPES.get(plabel, "text"),
                           "series": "mother"})
    return fields


def postnatal_fields():
    """PR and NN blocks, each repeated once per postnatal visit window."""
    data = _load()[BACK]
    out = {}
    for key, series in (("postnatal_mother", "postnatal_mother"),
                        ("postnatal_baby", "postnatal_baby")):
        base = []
        for raw in data[key]:
            base.extend(_with_prompts(raw, series))
        out[series] = {
            "title": ("MOTHER'S Postnatal Care" if series == "postnatal_mother"
                      else "BABY'S Postnatal Care"),
            "repeat": len(VISIT_WINDOWS),
            "repeat_label": "Visit",
            "visit_windows": VISIT_WINDOWS,
            "fields": base,
        }
    return out


# --------------------------------------------------------------------------
# Family Planning
# --------------------------------------------------------------------------
# Captured from the OpenMRS patient record instead of duplicated as form fields,
# matching the decision taken for the Front tab's client header row. Serial
# number is the form's own row order and is not stored.
FP_FROM_PATIENT = ["Name of Client", "Present Address", "Contact Details", "Sex", "Age"]
FP_SKIP = ["S. No"]

# The decision that these columns are read from the patient record rather than
# captured. Held separately from the list above so that dropping a column from it
# is a failure the coverage check catches, not a silent change.
FP_PATIENT_REQUIRED = ["Name of Client", "Present Address", "Contact Details",
                       "Sex", "Age"]

# Labels with no tick-boxes printed beneath them are themselves the tick-box.
FP_SELF_TICKED = {"Emergency contraceptive", "Lactation Amenorhea Method (LAM)",
                  "Method Switch"}

# Columns AA..AP of the Family Planning sheet are the contraceptive method
# blocks, each of which becomes its own section on the form. Everything to the
# left of them is client detail, so the two are not lumped together.
FP_METHOD_COLUMNS = {"AA", "AC", "AE", "AH", "AJ", "AM", "AO", "AP"}
FP_DETAIL_SECTION = "Client and Visit"

FP_TYPE_OVERRIDES = {
    "Reg. No": "text",
    "Date (yyyy-mm-dd)": "date",
    "Marital Status (S/M/D/W)": "choice",
    "Occupation": "text",
    "Blood Pressure": "text",
    "Weight (kg)": "number",
    "Height": "number",
    "Other FP method specify": "text",
    "Date of next Appointment (yyyy-mm-dd)": "date",
    "Referred in from CHW/Facility ( Y / N )": "yn",
    "Rererred Out to other facility ( Y / N )": "yn",
}


def _strip_choices(label):
    """Drop a trailing "(S/M/W/D)"-style choices marker from a printed label."""
    return re.sub(r"\s*\([^)]*[A-Za-z][^)]*\)\s*$", "", label).strip()


def _clean_label(raw, inline_choices=False):
    """Normalise a printed workbook label into the text shown on the form.

    Order matters: spelling corrections are keyed on the label exactly as printed,
    including its trailing "( Y / N )", so they are applied before that marker is
    stripped from the label.
    """
    text = " ".join((raw or "").split()).strip()
    if text.lower() in DR_LABEL_FIXES:
        text = DR_LABEL_FIXES[text.lower()]
    else:
        text = fix(text)
    text = YN_SUFFIX.sub("", text).strip()
    return _strip_choices(text) if inline_choices else text


def _label_keys(text):
    """Normalised lookup variants of a printed label, for matching override keys.

    An override may be keyed on the label as printed (typo and all, marker
    included) or on the cleaned form, so both are indexed.
    """
    if not text:
        return set()
    fixed = fix(" ".join(str(text).split()).strip())
    return {fixed.lower(), YN_SUFFIX.sub("", fixed).strip().lower()}


def family_planning_fields():
    overrides = {}
    for key, value in FP_TYPE_OVERRIDES.items():
        for variant in _label_keys(key):
            overrides[variant] = value
    fields = []

    def kind_for(label, printed, options):
        for variant in _label_keys(printed) | _label_keys(label):
            if variant in overrides:
                return overrides[variant]
        if options:
            return "yn" if {o.lower() for o in options} <= {"y", "n", "yes", "no"} else "choice"
        if label in FP_SELF_TICKED:
            return "yn"
        return "text"

    def add(label, printed, options, series_label=None, section=None):
        kind = kind_for(label, printed, options)
        entry = {"code": f"FP{len(fields) + 1}",
                 "label": label, "type": kind, "series": "family_planning",
                 "section": section or FP_DETAIL_SECTION}
        if series_label:
            entry["group"] = series_label
        if options and kind in ("yn", "choice"):
            entry["options"] = options
        fields.append(entry)

    for raw in _load()[FAMILY]:
        if raw["label"] in FP_SKIP:
            continue
        printed = raw["label"]
        label = _clean_label(printed)
        if label in FP_FROM_PATIENT or printed in FP_FROM_PATIENT:
            continue
        # A method column heads its own section; anything else is client detail.
        section = label if raw.get("column") in FP_METHOD_COLUMNS else None
        add(label, printed, [fix(o) for o in raw.get("options") or []],
            section=section)
        # A row-4 label expanded by row-5 cells is a question in its own right,
        # not another tick of its parent (e.g. Client Type "Post Partum Family
        # Planning" refined into how long after delivery).

        for sub in raw.get("subgroups") or []:
            add(f"{label} - {sub['label']}", None,
                [fix(o) for o in sub["options"]], series_label=label,
                section=label)
    return fields


def family_planning_consumed():
    """Every printed Family Planning cell the spec consumes, for the audit.

    Returned separately from the field list so the coverage check compares the
    workbook against exactly what the spec read, including subgroup labels and
    patient-sourced columns, rather than re-deriving them with different rules.
    """
    labels, options, subgroups, from_patient, skipped = [], [], [], [], []
    for raw in _load()[FAMILY]:
        printed = raw["label"]
        label = _clean_label(printed)
        if printed in FP_SKIP:
            skipped.append(printed)
            continue
        if printed in FP_FROM_PATIENT or label in FP_FROM_PATIENT:
            from_patient.append(printed)
            continue
        labels.append(printed)
        options.extend(raw.get("options") or [])
        for sub in raw.get("subgroups") or []:
            subgroups.append(sub["label"])
            options.extend(sub["options"])
    return {"labels": labels, "options": options, "subgroups": subgroups,
            "from_patient": from_patient, "skipped": skipped}


# --------------------------------------------------------------------------
# Delivery Register
# --------------------------------------------------------------------------
# The Delivery Register is a header row of groups over a row of sub-fields, which
# a flat parse cannot represent, so each leaf field is declared here by the
# workbook cells its label and options come from. Labels are read from the
# workbook at build time rather than retyped, so they cannot drift, and every
# declared cell is recorded in CONSUMED_CELLS for the coverage check.
_ABORT_METHODS = ["Miso", "Combo (Miso + Mife)", "MVA", "Surgical (D & E)"]
_DELIVERY_TYPE = ["Normal", "Assisted (vacuum)", "CS (Elective)", "CS (Emergency)"]
_DELIVERY_OUTCOME = ["Alive", "FSB", "MSB"]


def _book():
    import openpyxl
    return openpyxl.load_workbook(os.path.join(HERE, "source",
                                               "mother-and-neonate-register.xlsx"),
                                  data_only=True)



# Each leaf field is declared by the workbook cells its label and options come
# from. Labels are read from the workbook at build time rather than retyped, so
# they cannot drift, and every declared cell is recorded in DR_CONSUMED for the
# coverage check. "display" overrides the workbook text where the printed label
# is only meaningful with its group heading.
_DELIVERY_DECL = [
    {"code": "DR1",  "label": (3, 2)},
    {"code": "DR2",  "label": (3, 3)},
    {"code": "DR3",  "label": (3, 7),  "type": "choice", "inline": "S/M/W/D"},
    {"code": "DR4",  "label": (3, 8),  "type": "text"},
    {"code": "DR5",  "label": (3, 9),  "type": "choice",
     "options": [(4, 9), (4, 10), (4, 11), (4, 12), (4, 13)]},
    {"code": "DR6",  "label": (4, 14), "type": "date",  "group": "Admission"},
    {"code": "DR7",  "label": (4, 15), "type": "text",  "group": "Admission"},
    {"code": "DR8",  "label": (4, 16), "type": "text",  "group": "Referred"},
    {"code": "DR9",  "label": (4, 17), "type": "text",  "group": "Referred"},
    {"code": "DR10", "label": (3, 18), "type": "number"},
    {"code": "DR11", "label": (3, 19), "type": "number"},
    {"code": "DR12", "label": (3, 20), "type": "number"},
    {"code": "DR13", "label": (4, 21), "type": "choice",
     "options": [(5, 21), (5, 22), (5, 23), (5, 24)],
     "group": "Safe Termination of Pregnancy"},
    {"code": "DR14", "label": (4, 25), "type": "choice",
     "options": [(5, 25), (5, 26), (5, 27), (5, 28)],
     "group": "Post-Abortion Care (PAC)"},
    {"code": "DR15", "label": (3, 29), "type": "text"},
    {"code": "DR16", "label": (3, 30), "type": "text"},
    {"code": "DR17", "label": (4, 31), "type": "date",  "group": "Delivery"},
    {"code": "DR18", "label": (4, 32), "type": "text",  "group": "Delivery"},
    {"code": "DR19", "label": (4, 33), "type": "choice",
     "options": [(5, 33), (5, 34), (5, 35), (5, 36)], "group": "Delivery"},
    {"code": "DR20", "label": (4, 37), "type": "choice",
     "options": [(5, 37), (5, 38), (5, 39)], "group": "Delivery"},
    {"code": "DR21", "label": (4, 40), "type": "yn", "group": "Delivery",
     "display": "Partograph / Labour Care Guide used?"},
    {"code": "DR22", "label": (3, 41), "type": "yn"},
    {"code": "DR23", "label": (3, 42), "type": "yn",
     "display": "Heat-stable Carbetocin (HSC)"},
    {"code": "DR24", "label": (3, 43), "type": "yn"},
    {"code": "DR25", "label": (4, 44), "type": "yn", "group": "New born condition",
     "display": "New born condition - Alive"},
    {"code": "DR26", "label": (4, 45), "type": "choice", "inline": "M/F",
     "display": "New born condition - Sex"},
    {"code": "DR27", "label": (4, 46), "type": "number", "group": "New born condition"},
    {"code": "DR28", "label": (4, 47), "type": "number", "group": "New born condition"},
    {"code": "DR29", "label": (4, 48), "type": "yn", "group": "New born condition"},
    {"code": "DR30", "label": (4, 49), "type": "yn", "group": "New born condition"},
    {"code": "DR31", "label": (4, 50), "type": "yn", "group": "New born condition"},
    {"code": "DR32", "label": (4, 51), "type": "yn", "group": "New born condition"},
    {"code": "DR33", "label": (4, 52), "type": "yn", "group": "New born condition"},
    {"code": "DR34", "label": (4, 53), "type": "text", "group": "Maternal diagnosis"},
    {"code": "DR35", "label": (4, 54), "type": "text", "group": "Maternal diagnosis"},
    {"code": "DR36", "label": (4, 55), "type": "yn", "group": "Oxygen Therapy",
     "display": "Oxygen given?"},
    {"code": "DR37", "label": (4, 56), "type": "yn",
     "group": "Post Abortion Family Planning"},
    {"code": "DR38", "label": (4, 57), "type": "yn",
     "group": "Post Abortion Family Planning"},
    {"code": "DR39", "label": (4, 58), "type": "text",
     "group": "Post Abortion Family Planning"},
    {"code": "DR40", "label": (4, 59), "type": "yn",
     "group": "Post-Partum Family Planning"},
    {"code": "DR41", "label": (4, 60), "type": "yn",
     "group": "Post-Partum Family Planning"},
    {"code": "DR42", "label": (4, 61), "type": "text",
     "group": "Post-Partum Family Planning"},
    {"code": "DR43", "label": (4, 62), "type": "date", "group": "Maternal outcome"},
    {"code": "DR44", "label": (4, 63), "type": "text", "group": "Maternal outcome"},
    {"code": "DR45", "label": (4, 64), "type": "text",
     "group": "Delivery conducted by"},
    {"code": "DR46", "label": (4, 65), "type": "text",
     "group": "Delivery conducted by"},
    {"code": "DR47", "label": (4, 66), "type": "choice",
     "options": [(4, 66), (4, 67), (4, 68)], "display": "Place of delivery",
     "group": "Place of delivery"},
]

# Row-3 group headings, each carried on its member fields as "group".
GROUP_HEADERS = sorted(
    {d["group"] for d in _DELIVERY_DECL if d.get("group")}
    | {"Place of delivery"})

DR_CONSUMED = sorted({c for d in _DELIVERY_DECL
                      for c in (d["label"], *d.get("options", []))})

# Captured from the OpenMRS patient record rather than duplicated, and the serial
# number is the form's own row order.
DR_FROM_PATIENT = [(3, 4), (3, 5), (3, 6)]
DR_SKIP = [(3, 1)]

# As for Family Planning: the columns that must be read from the patient record.
# The Delivery Register is addressed by workbook cell, so the labels are derived
# from those cells rather than retyped, keeping the two lists from drifting.
DR_PATIENT_REQUIRED = ["Name of Client", "Age", "Address"]

def delivery_label(cell):
    """The printed label at a Delivery Register cell, for the coverage audit."""
    value = _book()[DELIVERY].cell(cell[0], cell[1]).value
    return " ".join(str(value or "").split()).strip()


def delivery_fields():
    ws = _book()[DELIVERY]

    def text(rc):
        return " ".join(str(ws.cell(rc[0], rc[1]).value or "").split()).strip()

    out = []
    for d in _DELIVERY_DECL:
        kind = d.get("type", "text")
        group = d.get("group")
        if "display" in d:
            label = d["display"]
        else:
            label = _clean_label(text(d["label"]))
            # Sub-header cells read as bare "Date"/"Time"/"From"; prefix the
            # group so the label is unambiguous outside its section heading.
            if group and label and group.lower() not in label.lower():
                label = f"{group} - {label}"
        if "inline" in d:
            options = [c.strip() for c in d["inline"].split("/")]
            label = re.sub(r"\s*\([^)]*\)", "", label).strip()
        else:
            options = [fix(text(c)) for c in d.get("options", []) if text(c)]
        entry = {"code": d["code"], "label": label, "type": kind,
                 "series": "delivery"}
        if group:
            entry["group"] = group
        if options and kind in ("yn", "choice"):
            entry["options"] = options
        out.append(entry)
    return out

def main():
    print(f"parsed json      : {PARSED}")
    mother = mother_fields()
    print(f"M1-M20           : {len(mother)} fields")
    pn = postnatal_fields()
    print(f"PR1-PR16         : {len(pn['postnatal_mother']['fields'])} fields "
          f"x{pn['postnatal_mother']['repeat']} visits")
    print(f"NN1-NN26         : {len(pn['postnatal_baby']['fields'])} fields "
          f"x{pn['postnatal_baby']['repeat']} visits")
    fp = family_planning_fields()
    print(f"FP               : {len(fp)} fields")
    dr = delivery_fields()
    print(f"DR               : {len(dr)} fields")
    total = (len(mother) + len(pn["postnatal_mother"]["fields"]) * 3
             + len(pn["postnatal_baby"]["fields"]) * 3 + len(fp) + len(dr))
    print(f"total new fields : {total}")


if __name__ == "__main__":
    main()
