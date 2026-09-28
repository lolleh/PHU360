#!/usr/bin/env python3
"""Assembles the three register forms from the two field specifications.

`mnr_spec.py` holds the Mother and Neonate Health Register as transcribed in the
first pass. `source_spec.py` holds the fields added for the Back tab of that same
register plus the Delivery Register and Family Planning sheets, read from the
workbook rather than retyped. This module turns both into the three forms that
get generated, and owns the form and encounter-type identities.

Form identity is split so that the Mother and Neonate Register keeps the UUID it
was already published under - changing it would orphan the observations and
encounters already recorded against it - while the two new forms get UUIDs
derived from a fixed namespace so they are stable across machines.
"""
import uuid

import mnr_spec
import source_spec as S

# Concept UUIDs are uuid5(NAMESPACE, concept_name) in source_spec. Form and
# encounter-type identities get their own namespaces so that a form UUID can
# never collide with a concept UUID derived from the same string.
CONCEPT_NAMESPACE = S.NAMESPACE
FORM_NAMESPACE = uuid.UUID("1d5f6c0a-3b8e-4a71-9f42-6c0d2e5b8a13")
ENCOUNTER_NAMESPACE = uuid.UUID("8b2e4d71-6a05-4c93-b1f8-72d3e9c5a046")


def _uuid(namespace, name):
    return str(uuid.uuid5(namespace, name))


# ---------------------------------------------------------------------------
# Front tab, row 4: the part of the client header that is not on the patient
# record. The sheet does not number these columns, so they are given a C series.
# ---------------------------------------------------------------------------
CLIENT_IDENTIFIER_FIELDS = [
    dict(code="CLNO", label="Client Number", type="text",
         note="Register's own client number, kept alongside the patient record so "
              "a client can still be matched to the paper register."),
    dict(code="NIN", label="NIN", type="text",
         note="National Identification Number."),
    dict(code="RESP", label="Name and Contact of Responsible Person", type="text",
         note="Guardian or next of kin. Not held on the patient header, so it is "
              "captured here."),
]


def client_identifier_fields():
    return [dict(f) for f in CLIENT_IDENTIFIER_FIELDS]


# ---------------------------------------------------------------------------
# Obs groups that separate repeated blocks. Without a group, two visits' answers
# to the same question are indistinguishable in a report.
# ---------------------------------------------------------------------------
def mother_visit_group_name(i):
    return f"MNR PNCM{i} Postnatal Mother Visit {i}"


def baby_visit_group_name(i):
    return f"MNR PNCB{i} Postnatal Baby Visit {i}"


def mnr_sections():
    """Mother and Neonate Health Register: first pass, then the Back tab."""
    sections = list(mnr_spec.SECTIONS)

    sections.append(dict(
        key="client_identifiers",
        title="Client Identifiers",
        fields=client_identifier_fields(),
    ))

    sections.append(dict(
        key="mother_check",
        title="Mother - Check Within 24 hrs of Delivery",
        fields=S.mother_fields(),
    ))

    pn = S.postnatal_fields()
    sections.append(dict(
        key="postnatal_mother",
        title=pn["postnatal_mother"]["title"],
        repeat=pn["postnatal_mother"]["repeat"],
        repeat_label=pn["postnatal_mother"]["repeat_label"],
        visit_windows=pn["postnatal_mother"]["visit_windows"],
        group_name=mother_visit_group_name,
        fields=pn["postnatal_mother"]["fields"],
    ))
    sections.append(dict(
        key="postnatal_baby",
        title=pn["postnatal_baby"]["title"],
        repeat=pn["postnatal_baby"]["repeat"],
        repeat_label=pn["postnatal_baby"]["repeat_label"],
        visit_windows=pn["postnatal_baby"]["visit_windows"],
        group_name=baby_visit_group_name,
        fields=pn["postnatal_baby"]["fields"],
    ))
    return sections


# ---------------------------------------------------------------------------
# Delivery Register: the sheet is a header row of groups over a row of
# sub-fields, so the sections are the groups, in the sheet's own left-to-right
# order. Fields the sheet prints without a group heading are collected into a
# single section rather than each becoming its own.
# ---------------------------------------------------------------------------
def delivery_sections():
    """Group the sheet's own headings into sections, in left-to-right order.

    The sheet interleaves headed blocks with standalone columns, which would
    otherwise produce several stub sections sharing one heading. The standalone
    columns are therefore collected into a single leading section and the headed
    blocks follow in the order the sheet prints them.
    """
    fields = S.delivery_fields()
    detail = dict(key="dr_detail", title="Client and Labour Details", fields=[])
    sections = [detail]
    index = {}
    for f in fields:
        title = f.get("group")
        if not title:
            detail["fields"].append(f)
            continue
        if title not in index:
            index[title] = dict(key=f"dr_{len(sections)}", title=title, fields=[])
            sections.append(index[title])
        index[title]["fields"].append(f)
    return [s for s in sections if s["fields"]]


# ---------------------------------------------------------------------------
# Family Planning: every column that carries its own tick-boxes is a method, so
# it becomes its own section. The remaining columns are client detail.
# ---------------------------------------------------------------------------
def family_planning_sections():
    sections = []
    index = {}
    for f in S.family_planning_fields():
        title = f.get("section") or S.FP_DETAIL_SECTION
        if title not in index:
            index[title] = dict(key=f"fp_{len(sections)}", title=title, fields=[])
            sections.append(index[title])
        index[title]["fields"].append(f)
    return sections


# ---------------------------------------------------------------------------
# The three forms.
# ---------------------------------------------------------------------------
FORMS = [
    dict(
        key="mnr",
        name="Mother and Neonate Health Register",
        # Published already as form id 186 with this UUID; must not change.
        uuid="3f2b7c14-9d6a-4e58-b0c3-71a4d9e25f86",
        encounter_name="Maternity and Delivery Register",
        encounter_uuid="9cc89b83-e32f-410a-947d-aeb3bda37571",
        out_xml="motherAndNeonateRegister.xml",
        facility_fields=mnr_spec.FACILITY_FIELDS,
        sections=mnr_sections(),
        # The sheet prints the client header inline; note what is read from the
        # patient record so a user does not look for those fields on the form.
        # Client number, NIN and the responsible person are captured in their own
        # section below, so they are deliberately not listed here.
        client_note=("Name, age and address are taken from the patient record "
                     "selected on the patient dashboard. Client number, NIN and "
                     "the responsible person are captured below."),
    ),
    dict(
        key="delivery",
        name="Delivery Register",
        uuid=_uuid(FORM_NAMESPACE, "form:delivery"),
        encounter_name="Delivery Register",
        encounter_uuid=_uuid(ENCOUNTER_NAMESPACE, "encounter_type:delivery"),
        out_xml="deliveryRegister.xml",
        facility_fields=[],
        sections=delivery_sections(),
        client_note=("Name, age and address are taken from the patient record "
                     "selected on the patient dashboard."),
    ),
    dict(
        key="family_planning",
        name="Family Planning Register",
        uuid=_uuid(FORM_NAMESPACE, "form:family_planning"),
        encounter_name="Family Planning Register",
        encounter_uuid=_uuid(ENCOUNTER_NAMESPACE, "encounter_type:family_planning"),
        out_xml="familyPlanningRegister.xml",
        facility_fields=[],
        sections=family_planning_sections(),
        client_note=("Name, address, contact details, sex and age are taken from "
                     "the patient record selected on the patient dashboard."),
    ),
]


def form_by_key(key):
    for form in FORMS:
        if form["key"] == key:
            return form
    raise KeyError(key)


def repeat_sections(form):
    """Sections of ``form`` that are rendered once per visit window."""
    return [s for s in form["sections"] if s.get("repeat")]


def check_unique_uuids():
    """Fail if two forms or encounter types would share an identity."""
    problems = []
    for kind, field in (("form", "uuid"), ("encounter type", "encounter_uuid")):
        seen = {}
        for form in FORMS:
            value = form[field]
            if value in seen:
                problems.append(f"{kind} uuid {value} used by both "
                                f"{seen[value]!r} and {form['key']!r}")
            seen[value] = form["key"]
    return problems


if __name__ == "__main__":
    problems = check_unique_uuids()
    for p in problems:
        print("DUPLICATE:", p)
    for form in FORMS:
        total = sum(len(s["fields"]) * s.get("repeat", 1) for s in form["sections"])
        repeats = len(repeat_sections(form))
        print(f"{form['name']}")
        print(f"  form uuid   : {form['uuid']}")
        print(f"  encounter    : {form['encounter_name']} ({form['encounter_uuid']})")
        print(f"  sections     : {len(form['sections'])}"
              + (f" ({repeats} repeated)" if repeats else ""))
        print(f"  obs fields   : {total}")
        for s in form["sections"]:
            n = len(s["fields"]) * s.get("repeat", 1)
            flag = f" x{s['repeat']}" if s.get("repeat") else ""
            print(f"    {s['title']:<46} {n:>3}{flag}")
    if problems:
        raise SystemExit(1)
