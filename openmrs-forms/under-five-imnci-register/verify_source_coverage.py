#!/usr/bin/env python3
"""Check that every printed column of the register is accounted for.

The workbook is a print layout, not a schema: one printed column can carry
several questions, and one question can span two columns. `u5_spec.py` says which
sections came from which printed columns; this walks both lists and fails on the
two mistakes that are easy to make and invisible on the form:

  * a printed column no section claims - something the register asks for that the
    form never captures, and nobody notices because the form looks complete;
  * a section claiming a column the sheet does not have - a typo, or a column
    that has since been deleted from the workbook.

A column the register prints but which is deliberately not captured is not a
failure, but it has to be declared in `SOURCE_ANOMALIES.json` with a reason, so
that "we decided not to capture this" is a recorded decision rather than an
oversight. This only checks that such a column is declared - that the reason is
sensible is a judgement for whoever reads it.

    python3 verify_source_coverage.py
"""
import json
import os
import sys

import u5_spec

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    with open(u5_spec.PARSED, encoding="utf-8") as fh:
        parsed = json.load(fh)
    if not os.path.exists(u5_spec.ANOMALIES):
        print(f"{os.path.relpath(u5_spec.ANOMALIES, HERE)} is missing, so nothing can be "
              f"declared as deliberately uncaptured")
        return 1
    with open(u5_spec.ANOMALIES, encoding="utf-8") as fh:
        anomalies = json.load(fh)

    printed = {c["letter"]: c for c in parsed["columns"]}

    claimed = {}
    for section in u5_spec.SECTIONS:
        for letter in section.get("source", []):
            claimed.setdefault(letter, []).append(section["title"])

    # `columns` says which printed columns an anomaly is about - a decision about
    # how a column was captured still counts as captured. `uncapturedColumns` is
    # the one that gives up on a column, and only that one may.
    declared_uncaptured = set()
    for anomaly in anomalies["anomalies"]:
        for letter in anomaly.get("uncapturedColumns", []):
            declared_uncaptured.add(letter)

    problems = []

    for letter, section in sorted(printed.items(), key=lambda kv: kv[1]["column"]):
        if letter in claimed:
            if letter in declared_uncaptured:
                problems.append(
                    f"{letter} ({section['header']}) is both captured by "
                    f"{', '.join(claimed[letter])} and declared uncaptured")
            continue
        if letter in declared_uncaptured:
            continue
        problems.append(
            f"{letter} ({section['header']}) is printed by the register but no "
            f"section of the form claims it. Add it to a section's source= or "
            f"declare it in SOURCE_ANOMALIES.json with a reason.")

    for letter, sections in sorted(claimed.items()):
        if letter not in printed:
            problems.append(
                f"{', '.join(sections)} claims printed column {letter}, which "
                f"the sheet does not have")

    for letter in sorted(declared_uncaptured):
        if letter not in printed and letter not in claimed:
            problems.append(
                f"SOURCE_ANOMALIES.json declares column {letter} uncaptured, but "
                f"the sheet has no such column - the declaration is stale")

    captured = sorted(claimed)
    print(f"{len(printed)} printed columns, {len(captured)} captured by "
          f"{len(u5_spec.SECTIONS)} sections")
    print(f"  deliberately not captured: {len(declared_uncaptured & set(printed))}")
    for letter in sorted(declared_uncaptured & set(printed)):
        print(f"      {letter} ({printed[letter]['header']})")
    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print("  every printed column is captured or declared, and every section "
          "claims a column that exists")
    return 0


if __name__ == "__main__":
    sys.exit(main())