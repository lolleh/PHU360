#!/usr/bin/env python3
"""Check the generated form against the generated dictionary.

The form and `underFiveIMNCIConcepts.csv` are produced by different scripts and
consumed by different parts of the system, and nothing at runtime stops them
disagreeing: htmlformentry renders a `<obs>` by looking the concept up and, if the
lookup fails, quietly records nothing. So the failure mode is not an error - it is
a register that looks complete and is missing a third of its answers.

These are the ways they can disagree, all of which have been real somewhere in
this register's history:

  * a concept named by the form that the dictionary does not have;
  * a control drawn as a tick box for a concept the dictionary calls Numeric, or
    a number field for a Coded one - the value would be saved as the wrong type,
    and a report reading it would get null rather than an error;
  * a question offered an answer it does not have;
  * a concept in the dictionary the form never asks for, which is either a field
    that quietly fell out of the spec or a leftover nobody retired;
  * the form's own identity drifting from the spec, which would point the
    dashboard at a form the encounters were never recorded against.

    python3 verify_form.py
"""
import csv
import os
import re
import sys
import xml.etree.ElementTree as ET

import u5_spec
from register_concepts import SHIPPED_CSV

HERE = os.path.dirname(os.path.abspath(__file__))
FORM = os.environ.get("U5_FORM_OUT", os.path.join(
    HERE, "..", "..", "content", "configuration", "backend_configuration",
    "pih", "htmlforms", "underFiveIMNCIRegister.xml"))

# The control htmlformentry can save each datatype into. A concept drawn as
# anything else either loses its value or saves it as the wrong type.
CONTROL_FOR = {
    "Boolean": {"checkbox"},
    "Coded": {"dropdown"},
    "Numeric": {"number"},
    "Text": {"text", "textarea"},
    "Datetime": {"datetime"},
    "N/A": {"text"},  # only ever an answer, never a question of its own
}


def load_dictionary():
    by_uuid = {}
    with open(SHIPPED_CSV, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            by_uuid[row["uuid"]] = row
    return by_uuid


def main():
    with open(FORM, encoding="utf-8") as fh:
        text = fh.read()
    problems = []

    try:
        root = ET.fromstring(text)
    except ET.ParseError as e:
        print(f"the form is not well-formed XML: {e}")
        return 1

    # Identity. A changed formUuid is a new form and the old encounters are left
    # behind; a changed encounter type sends the register's visits somewhere the
    # report does not read.
    for attr, expected in (("formUuid", u5_spec.FORM["uuid"]),
                           ("formEncounterType", u5_spec.FORM["encounter_uuid"]),
                           ("formVersion", u5_spec.FORM["form_version"]),
                           ("formName", u5_spec.FORM["name"])):
        actual = root.get(attr)
        if actual != expected:
            problems.append(
                f"{attr} is {actual!r}, the spec says {expected!r}")

    dictionary = load_dictionary()
    by_name = {row["fully specified name:en"]: row for row in dictionary.values()}

    referenced = set()
    for obs in root.iter("obs"):
        concept_id = obs.get("conceptId")
        row = dictionary.get(concept_id)
        if row is None:
            name = next((n for n, r in by_name.items() if r["uuid"] == concept_id), concept_id)
            problems.append(
                f"the form records {name} ({concept_id}), which the concepts CSV "
                f"does not have - the question would render and record nothing")
            continue
        referenced.add(concept_id)

        name = row["fully specified name:en"]
        datatype = row["data type"]
        style = obs.get("style")
        allowed = CONTROL_FOR.get(datatype)
        if allowed is not None and style not in allowed:
            problems.append(
                f"{name} is {datatype} in the dictionary but the form draws it as "
                f"{style!r}, which saves {datatype} values as something else")

        answers = obs.get("answerConceptIds")
        if datatype == "Coded":
            if not answers:
                problems.append(f"{name} is Coded but the form offers no answers")
                continue
            if style != "dropdown":
                continue
            for answer_id in answers.split(","):
                referenced.add(answer_id)
                answer = dictionary.get(answer_id)
                if answer is None:
                    problems.append(
                        f"{name} offers answer {answer_id}, which the concepts CSV "
                        f"does not have")
                elif answer["data type"] != "N/A":
                    problems.append(
                        f"{name} offers {answer['fully specified name:en']} as an "
                        f"answer, but that concept is {answer['data type']} - an "
                        f"answer has to be N/A")
        elif answers:
            problems.append(f"{name} is {datatype} but the form offers answers to it")

    # The spec's questions must all be on the form. A generator that silently
    # dropped a section would otherwise look like a deliberate omission.
    for section in u5_spec.SECTIONS:
        for field in section["fields"]:
            name = field.get("concept")
            if not name:
                continue
            row = by_name.get(name)
            if row is None:
                problems.append(f"the spec asks {name!r}, which the CSV does not have")
            elif row["uuid"] not in referenced:
                problems.append(
                    f"the spec asks {name!r} in section {section['title']!r} but "
                    f"the form does not record it")

    for uuid, row in sorted(dictionary.items(), key=lambda kv: kv[1]["fully specified name:en"]):
        if uuid not in referenced:
            problems.append(
                f"{row['fully specified name:en']} is in the dictionary but the "
                f"form never asks it - either a field fell out of the spec or it "
                f"should be retired deliberately")

    observations = len(list(root.iter("obs")))
    sections = len(root.findall(".//div[@class='uf-section']"))
    print(f"{os.path.relpath(FORM, HERE)}: {observations} obs across "
          f"{sections} sections, {len(dictionary)} concepts in the CSV")
    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print("  every question the spec asks is on the form, and the form and the "
          "dictionary agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())