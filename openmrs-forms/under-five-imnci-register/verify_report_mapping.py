#!/usr/bin/env python3
"""Check the under-five report mapping against the form it reports on.

The report names concepts by string, and OpenMRS resolves a name to an id at
render time. A name the database does not have is dropped and shown as
"conceptsMissing" - so the report still renders, just quietly smaller than it
looks, and nothing on the page says a section is incomplete.

This is what stops that being invisible at the point it is introduced. Every
concept and answer name in `under-five-register.xml` has to be a concept
`u5_spec.py` actually asks about, and every counting rule has to be one the
report's code implements:

  * a `recorded` condition must be a number the form asks for, or it is counted
    by a number the form never writes;
  * an `answer` condition must be a coded question whose named answers are among
    its options;
  * a plain condition must be a tick box, for the dictionary's "Yes" to be the
    affirmative answer;
  * indicators and sections must name concepts the form asks about too.

Run it after editing either file. It reads nothing from the database, so it can
run anywhere.

    python3 verify_report_mapping.py
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

import u5_spec

HERE = os.path.dirname(os.path.abspath(__file__))
MAPPING = os.path.join(
    HERE, "..", "..", "clinicreporting", "api", "src", "main", "resources",
    "reportmappings", "under-five-register.xml")
UUIDS = os.path.join(HERE, "concept-uuids.json")

KINDS = {"yes", "recorded", "answer"}


def spec_index():
    """Every concept the form asks about, and what it asks of it."""
    questions = {}
    for section in u5_spec.SECTIONS:
        for field in section["fields"]:
            name = field.get("concept")
            if not name:
                continue
            questions[name] = {
                "type": field["type"],
                "section": section["key"],
                "answers": {o["concept"] for o in field.get("options") or []},
            }
    return questions


def check(kind, where, problems, condition):
    if not condition:
        problems.append(f"{where}: {kind}")


def main():
    with open(UUIDS, encoding="utf-8") as fh:
        uuids = json.load(fh)
    questions = spec_index()
    tree = ET.parse(MAPPING)
    root = tree.getroot()

    problems = []
    counter = [0]

    # Every concept name in the file, wherever it appears, has to be a question
    # the form asks. This is the check that used to be missing.
    def resolves(name, where):
        counter[0] += 1
        if name not in uuids:
            problems.append(
                f"{where}: {name!r} is not in the register's dictionary")
            return False
        if name not in questions:
            problems.append(
                f"{where}: {name!r} is in the dictionary but the form never "
                f"asks it, so no visit can ever answer it")
            return False
        return True

    for indicator in root.iter("indicator"):
        key = indicator.findtext("key", "?")
        concept = indicator.findtext("concept")
        if concept and resolves(concept, f"indicator {key}"):
            answers = [a.text for a in indicator.findall("answer")]
            q = questions[concept]
            for answer in answers:
                counter[0] += 1
                if answer not in uuids:
                    problems.append(
                        f"indicator {key}: answer {answer!r} is not in the "
                        f"register's dictionary")
                elif answer not in q["answers"]:
                    problems.append(
                        f"indicator {key}: {concept!r} is not offered the answer "
                        f"{answer!r}, so the indicator can never match")

    for section in root.iter("section"):
        concept = section.findtext("concept")
        if concept:
            # A breakdown reads the answers of its own concept, so the concept
            # existing is all there is to check - but it still has to exist.
            resolves(concept, f"section {section.findtext('key', '?')}")

    for element in root.iter("conditionConcept"):
        name = (element.findtext("name") or element.text or "").strip()
        where = f"conditionConcept {name!r}"
        kind = (element.findtext("kind") or "yes").strip()
        answers = [(a.text or "").strip() for a in element.findall("answer")]

        check("unknown kind", where, problems, kind in KINDS)
        if not resolves(name, where):
            continue
        q = questions[name]

        if kind == "yes":
            check(
                "is not a tick box, so the dictionary's \"Yes\" is not how it "
                f"is recorded (it is {q['type']})", where, problems,
                q["type"] == "boolean")
            check("names an answer a yes-kind condition never uses", where,
                  problems, not answers)
        elif kind == "recorded":
            check(
                "is not a number the form asks for", where, problems,
                q["type"] == "number")
            check("names an answer a recorded condition never uses", where,
                  problems, not answers)
        elif kind == "answer":
            check(
                "is not a question with answers, so no answer can match it",
                where, problems, bool(q["answers"]))
            check("names no <answer>, so it could never be counted", where,
                  problems, bool(answers))
            for answer in answers:
                counter[0] += 1
                if answer not in uuids:
                    problems.append(
                        f"{where}: answer {answer!r} is not in the register's "
                        f"dictionary")
                elif answer not in q["answers"]:
                    problems.append(
                        f"{where}: {name!r} is not offered the answer "
                        f"{answer!r}, so the bar would always be empty")

    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print(f"  {problem}")
        return 1

    print(f"checked {counter[0]} concept and answer names against "
          f"{len(questions)} questions on the form")
    print("  all resolve, and every counting rule matches how the form records it")
    return 0


if __name__ == "__main__":
    sys.exit(main())