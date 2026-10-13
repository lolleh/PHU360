#!/usr/bin/env python3
"""Generate the three clinicreporting REPORT TYPE mappings for the registers.

The dashboard's REPORT TYPE filter is fed by one XML file per report under
clinicreporting/api/src/main/resources/reportmappings/. The Mother and Neonate,
Delivery and Family Planning registers each have an encounter type and a
concept dictionary, but no mapping, so the dashboard cannot offer them. This
script writes those three mappings from the same register_spec the forms are
generated from, so a concept name here is by construction a concept the form
asks about.

What it emits, per register:

  * the volume indicators every register report carries - encounters, distinct
    clients, mean age - plus a small curated set of register-specific figures
    (ANC stage 4+, live births, uterotonic coverage, newborn alive, mean birth
    weight / APGAR, new family planning clients, emergency contraception);
  * an age-band breakdown;
  * one codedBreakdown per enumerated question (blood group, delivery type,
    method, ...), which is the answer distribution the register prints; and
  * a conditionBreakdown over every Yes/No and Yes/No/N-A tick box.

Concept names come from register_concepts.field_name / option_name, which is
what created the concepts in the first place, so the mapping and the deployed
dictionary cannot drift apart the way a hand-written list would.

Run from this directory:

    python3 generate_report_mappings.py

then rebuild the omod. The output is deterministic, so a diff is a change in
the forms or the curated indicators, never noise.
"""
import os

import register_concepts as RC
import register_spec as R

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join(
    REPO, "clinicreporting", "api", "src", "main", "resources", "reportmappings")

# Yes/No tick boxes record the dictionary's own affirmative answer, which is
# what ReportIndicators counts for a condition (see ReportIndicators.yesIds).
YES = "Yes"

# Forms whose ticks are plain Yes/No, so a condition needs no answer of its own.
TICK_TYPES = ("yn", "ynna")

# The licence/comment header the other mappings carry. Kept literal so a
# regenerated file diffs only where the mapping actually changed.
HEADER = """<?xml version="1.0" encoding="UTF-8"?>
<!--

    This Source Code Form is subject to the terms of the Mozilla Public License,
    v. 2.0. If a copy of the MPL was not distributed with this file, You can
    obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
    the terms of the Healthcare Disclaimer located at http://openmrs.org/license.

    Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
    graphic logo is a trademark of OpenMRS Inc.

-->
"""


def esc(text):
    return (str(text).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;"))


def field_by_code(form, code):
    """The field dict with this code, or KeyError naming the form and code."""
    for section in form["sections"]:
        for f in section["fields"]:
            if f["code"] == code:
                return f
    raise KeyError(f"{form['key']}: no field {code}")


def coded_fields(form):
    """Enumerated questions in form order, deduplicated, that have options.

    A choice/dropdown with no printed options cannot be a breakdown - there is
    nothing to put on the axis - so it is skipped rather than emitted empty.
    """
    out, seen = [], set()
    for section in form["sections"]:
        for f in section["fields"]:
            if f["type"] in ("choice", "dropdown") and f.get("options"):
                if f["code"] not in seen:
                    seen.add(f["code"])
                    out.append(f)
    return out


def tick_fields(form):
    """Yes/No and Yes/No/N-A tick boxes in form order, deduplicated by code."""
    out, seen = [], set()
    for section in form["sections"]:
        for f in section["fields"]:
            if f["type"] in TICK_TYPES and f["code"] not in seen:
                seen.add(f["code"])
                out.append(f)
    return out


def indicator_xml(key, label, kind, concept=None, answers=(), inverted=False):
    lines = ["        <indicator>",
             f"            <key>{esc(key)}</key>",
             f"            <label>{esc(label)}</label>",
             f"            <kind>{esc(kind)}</kind>"]
    if concept is not None:
        lines.append(f"            <concept>{esc(concept)}</concept>")
    if inverted:
        lines.append("            <inverted>true</inverted>")
    for answer in answers:
        lines.append(f"            <answer>{esc(answer)}</answer>")
    lines.append("        </indicator>")
    return "\n".join(lines)


def indicators_for(form):
    """The curated indicator block for one register, in the order shown."""
    key = form["key"]
    ind = [
        indicator_xml("encounters", "Visits recorded", "encounterCount"),
        indicator_xml("clients", "Clients seen", "distinctPatients"),
        indicator_xml("meanAge", "Mean age (months)", "meanAgeMonths"),
    ]
    if key == "mnr":
        pp3 = field_by_code(form, "PP3")
        # ANC 4+ contacts: stages 4 to 8 of the register's ANC contact column.
        stages = [RC.option_name(pp3, str(n)) for n in range(4, 9)]
        d13 = field_by_code(form, "D13")
        d21 = field_by_code(form, "D21")
        ind += [
            indicator_xml("anc4", "ANC 4+ contacts", "codedRateAnyOf",
                          RC.field_name(pp3), stages),
            indicator_xml("liveBirths", "Live births", "codedCount",
                          RC.field_name(d13), [YES]),
            indicator_xml("lowBirthWeight", "Birth weight under 2.5 kg",
                          "codedRate", RC.field_name(d21), [YES]),
        ]
    elif key == "delivery":
        dr22 = field_by_code(form, "DR22")
        dr25 = field_by_code(form, "DR25")
        dr27 = field_by_code(form, "DR27")
        dr28 = field_by_code(form, "DR28")
        ind += [
            indicator_xml("uterotonic", "Uterotonic given after birth",
                          "codedRate", RC.field_name(dr22), [YES]),
            indicator_xml("newbornAlive", "Newborn alive", "codedRate",
                          RC.field_name(dr25), [YES]),
            indicator_xml("meanBirthWeight", "Mean birth weight (kg)",
                          "numericMean", RC.field_name(dr27)),
            indicator_xml("meanApgar", "Mean APGAR", "numericMean",
                          RC.field_name(dr28)),
        ]
    elif key == "family_planning":
        fp6 = field_by_code(form, "FP6")
        fp17 = field_by_code(form, "FP17")
        ind += [
            indicator_xml("newClients", "New clients", "codedCount",
                          RC.field_name(fp6),
                          [RC.option_name(fp6, "New Client (First time user)")]),
            indicator_xml("emergency", "Emergency contraceptive",
                          "codedRate", RC.field_name(fp17), [YES]),
        ]
    return "\n".join(ind)


def sections_for(form):
    """Age band, one breakdown per enumerated question, then the tick boxes."""
    lines = ["        <section>",
             "            <key>ageBands</key>",
             "            <label>Clients by age band</label>",
             "            <kind>ageBreakdown</kind>",
             "        </section>"]
    for f in coded_fields(form):
        lines += ["        <section>",
                  f"            <key>{esc(f['code'].lower())}</key>",
                  f"            <label>{esc(f['label'])}</label>",
                  "            <kind>codedBreakdown</kind>",
                  f"            <concept>{esc(RC.field_name(f))}</concept>",
                  "        </section>"]
    lines += ["        <section>",
              "            <key>conditions</key>",
              "            <label>Findings and services recorded</label>",
              "            <kind>conditionBreakdown</kind>",
              "        </section>"]
    return "\n".join(lines)


def condition_xml(form):
    # The concept name carries the "MNR <code>" heading; the bar wants the
    # register's own text, so every condition names its label.
    blocks = []
    for f in tick_fields(form):
        blocks.append("\n".join([
            "        <conditionConcept>",
            f"            <name>{esc(RC.field_name(f))}</name>",
            f"            <label>{esc(f['label'])}</label>",
            "        </conditionConcept>",
        ]))
    return "\n".join(blocks)


def mapping_for(form, report_key, table, label):
    lines = [HEADER, "<reportMapping>", ""]
    lines += [
        "    <!-- The key the reportApi request carries and this file's name. -->",
        f"    <key>{esc(report_key)}</key>",
        f"    <label>{esc(label)}</label>",
        f"    <table>{esc(table)}</table>",
        "",
        "    <mapped>true</mapped>",
        "",
        "    <!-- The register's own encounter type, resolved to an id per",
        "         installation by ReportCatalog. -->",
        "    <encounterTypes>",
        f"        <encounterType>{esc(form['encounter_name'])}</encounterType>",
        "    </encounterTypes>",
        "",
        "    <indicators>",
        indicators_for(form),
        "    </indicators>",
        "",
        "    <sections>",
        sections_for(form),
        "    </sections>",
        "",
        "    <conditionConcepts>",
        condition_xml(form),
        "    </conditionConcepts>",
        "",
        "</reportMapping>",
    ]
    return "\n".join(lines) + "\n"


# report key -> (form key, table, dashboard label)
MAPPINGS = {
    "mother-and-neonate-register": ("mnr", "phu360_report_mother_and_neonate",
                                    "Mother and Neonate Register"),
    "delivery-register": ("delivery", "phu360_report_delivery",
                          "Delivery Register"),
    "family-planning-register": ("family_planning",
                                 "phu360_report_family_planning",
                                 "Family Planning Register"),
}


def main():
    forms = {f["key"]: f for f in R.FORMS}
    for key, (form_key, table, label) in MAPPINGS.items():
        xml = mapping_for(forms[form_key], key, table, label)
        path = os.path.join(OUT_DIR, f"{key}.xml")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(xml)
        print(f"wrote {os.path.relpath(path, REPO)}")


if __name__ == "__main__":
    main()
