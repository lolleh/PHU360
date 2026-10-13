#!/usr/bin/env python3
"""Write the register's concepts back out as the Initializer CSV the distro ships.

`register_concepts.py` says what each concept is; this says what row it becomes in
`underFiveIMNCIConcepts.csv`. Same 8 columns, same flat convention - a coded
question and its answers are separate rows, and the form's `answerConceptIds` are
what tie them together at render time. The other four register forms in this
distribution already work that way, so this keeps to it.

The UUIDs are read from the pinned file, never minted here, so a regenerated CSV
always reloads onto the concepts that are already in the dictionary.

    python3 generate_concepts_csv.py
"""
import csv
import json
import os
import sys

import register_concepts
from register_concepts import allow_decimals, concept_datatype

HERE = os.path.dirname(os.path.abspath(__file__))
UUIDS = os.path.join(HERE, "concept-uuids.json")
SPEC = os.path.join(HERE, "concepts-to-create.json")
OUT = register_concepts.SHIPPED_CSV

HEADER = [
    "uuid",
    "fully specified name:en",
    "fully specified name:en:preferred",
    "fully specified name:en:uuid",
    "description:en",
    "data class",
    "data type",
    "allow decimals",
]

DESCRIPTION = "Under Five IMNCI register"


def row_for(concept, uuids):
    name = concept["name"]
    ids = uuids.get(name)
    if not ids:
        raise SystemExit(
            f"no uuid pinned for {name!r} - run register_concepts.py first")
    return {
        "uuid": ids["uuid"],
        "fully specified name:en": name,
        "fully specified name:en:preferred": "true",
        "fully specified name:en:uuid": ids["name_uuid"],
        "description:en": DESCRIPTION,
        "data class": concept["conceptClass"],
        "data type": concept["datatype"],
        "allow decimals": allow_decimals(name),
    }


def main():
    if not os.path.exists(SPEC):
        raise SystemExit(
            f"{os.path.relpath(SPEC, HERE)} is missing. Run "
            f"`python3 register_concepts.py` first, so the concepts and their "
            f"uuids are settled before they are written out.")
    with open(SPEC, encoding="utf-8") as fh:
        concepts = json.load(fh)
    with open(UUIDS, encoding="utf-8") as fh:
        uuids = json.load(fh)

    rows = [row_for(c, uuids) for c in concepts]
    # Sorted by name, as the concepts CSVs this distribution already ships are:
    # the loader does not care about order, but a reviewer of the diff does, and a
    # CSV whose rows jump around with the form's layout cannot be reviewed at all.
    rows.sort(key=lambda r: r["fully specified name:en"])

    seen = {}
    for row in rows:
        clash = seen.setdefault(row["uuid"], row["fully specified name:en"])
        if clash != row["fully specified name:en"]:
            raise SystemExit(
                f"uuid {row['uuid']} is claimed by both {clash!r} and "
                f"{row['fully specified name:en']!r}")
        seen.setdefault("name:" + row["fully specified name:en"], row["uuid"])

    with open(OUT, "w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=HEADER, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)

    types = {}
    for row in rows:
        types[row["data type"]] = types.get(row["data type"], 0) + 1
    print(f"{len(rows)} concepts -> {os.path.relpath(OUT, HERE)}")
    print("  by data type: " + ", ".join(f"{k} {v}" for k, v in sorted(types.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())