#!/usr/bin/env python3
"""Emit concepts-to-create.json for the Mother and Neonate Register from mnr_spec.py.

Naming convention (keeps the dictionary readable and collision-free):
    field   ->  "MNR <code> <label>"          e.g. "MNR H1 Sickler"
    option  ->  "MNR <code> <label> :: <opt>" e.g. "MNR D7 Delivery Type :: Normal"

The baby block is rendered twice in the form (Baby 1 / Baby 2) but shares one set
of concepts, matching the source sheet's two Baby columns.
"""
import json
import os

from mnr_spec import (
    SECTIONS, FACILITY_FIELDS, ANOMALIES,
    YES_NO, YES_NO_NA, STD_YES_NO, STD_YES_NO_NA, baby_group_name,
)
from source_spec import ANOMALIES as SOURCE_ANOMALIES

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "concepts-to-create.json")

# Yes/No fields are Coded, not Boolean: the register is a census, so an
# explicit "No" is a data point the dashboard must be able to count. A Boolean
# checkbox can only record "yes", which loses that. Answer concepts are the
# standard OpenMRS dictionary concepts, reused rather than duplicated.
DATATYPE = {
    "yn": "Coded",
    "ynna": "Coded",
    "number": "Numeric",
    "text": "Text",
    "date": "Date",
    "time": "Time",
    "dropdown": "Coded",
}
CONCEPT_CLASS = {
    "yn": "Misc",
    "ynna": "Misc",
    # This dictionary has no "Numeric" concept class (Anatomy..Workflow), so
    # numeric questions/answers are classed Misc, matching standard OpenMRS
    # numeric concepts such as Weight and Height.
    "number": "Misc",
    "text": "Misc",
    "date": "Misc",
    "time": "Misc",
    "dropdown": "Misc",
}

# answer concept uuids per field type, used by generate_form.py
ANSWER_UUIDS = {
    "yn": STD_YES_NO,
    "ynna": STD_YES_NO_NA,
}

ANSWER_LABELS = {
    "yn": YES_NO,
    "ynna": YES_NO_NA,
}


def sanitize(name):
    """Keep HTML-significant characters out of concept names.

    The REST API HTML-escapes what it stores, so a name containing '<' or '>'
    comes back as '&lt;' / '&gt;' and no longer matches on the next run; '&' is
    stored as the literal text '&amp;'. Substituting words keeps the stored name
    equal to the intended one. The form still displays the original label with
    the symbols intact, so nothing is lost for the user.
    """
    return (name.replace("<", "below")
                .replace(">", "above")
                .replace("&", "and"))


def field_name(f):
    return sanitize(f"MNR {f['code']} {f['label']}")


def option_name(f, opt):
    return sanitize(f"MNR {f['code']} {f['label']} :: {opt}")


def main():
    concepts = []
    seen = set()

    def add(name, datatype, cls, section):
        if name in seen:
            return
        seen.add(name)
        concepts.append({
            "name": name,
            "datatype": datatype,
            "conceptClass": cls,
            "section": section,
        })

    for sec in SECTIONS:
        for f in sec["fields"]:
            add(field_name(f), DATATYPE[f["type"]], CONCEPT_CLASS[f["type"]], sec["title"])
            if f["type"] == "dropdown":
                for opt in f["options"]:
                    add(option_name(f, opt), "N/A", "Misc", sec["title"])

    # obs groups separating Baby 1 from Baby 2 (twins)
    for i in (1, 2):
        add(baby_group_name(i), "N/A", "Misc", "Baby (Neonate)")

    for f in FACILITY_FIELDS:
        add(field_name(f), DATATYPE[f["type"]], CONCEPT_CLASS[f["type"]], "Facility")

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(concepts, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    # Written from both spec modules so that regenerating from either one cannot
    # silently discard the other's anomalies.
    anomalies = ANOMALIES + SOURCE_ANOMALIES
    with open(os.path.join(HERE, "SOURCE_ANOMALIES.json"), "w", encoding="utf-8") as fh:
        json.dump(anomalies, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    by_dt = {}
    for c in concepts:
        by_dt[c["datatype"]] = by_dt.get(c["datatype"], 0) + 1
    print(f"concepts -> {OUT}")
    print(f"  total      : {len(concepts)}")
    for k, v in sorted(by_dt.items()):
        print(f"  {k:<9} : {v}")
    print(f"anomalies  -> SOURCE_ANOMALIES.json ({len(ANOMALIES) + len(SOURCE_ANOMALIES)})")


if __name__ == "__main__":
    main()
