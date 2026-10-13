#!/usr/bin/env python3
"""The register's concepts: names to UUIDs, and the dictionary rows they ship as.

Walks `u5_spec.SECTIONS` and emits one concept per question, one per answer that
is not a shared Yes/No/Not applicable, and nothing for anything else. Two files
come out of it:

    concept-uuids.json        name -> uuid, plus the Initializer name uuid. The
                              identity of every concept in the register, pinned.
    concepts-to-create.json   name, datatype, concept class and the section that
                              asked for it, in the order the form shows them.

UUIDs come from two places, and the difference matters. The concepts the first
digitisation of this register shipped are already in the dictionary with
observations and encounters recorded against them, so their UUIDs are pinned in
concept-uuids.json and never recomputed - a UUID derived from a name would move
the concept the moment the name was reworded, and every obs would be left
pointing at nothing. Concepts added since are uuid5 of their name, which needs no
running server and is identical on every machine.

`--seed` is how the pinned file was created: it reads the concepts CSV the
register already ships and pins every UUID in it. It is run once and is refused
afterwards if the pinned file already exists, because re-seeding from a
regenerated CSV would silently pin whatever the last generation happened to
produce.

    python3 register_concepts.py --seed     # once, from the shipped CSV
    python3 register_concepts.py           # after any edit to u5_spec.py
"""
import argparse
import csv
import json
import os
import sys
import uuid

import u5_spec

HERE = os.path.dirname(os.path.abspath(__file__))
UUIDS = os.path.join(HERE, "concept-uuids.json")
OUT = os.path.join(HERE, "concepts-to-create.json")
SHIPPED_CSV = os.path.join(
    HERE, "..", "..", "content", "configuration", "backend_configuration",
    "concepts", "underFiveIMNCIConcepts.csv")

# Namespaces for concepts added after the first digitisation. Fixed values, not
# generated: regenerating them on every machine is the whole point, and a random
# namespace would give every machine a different dictionary.
CONCEPT_NAMESPACE = uuid.UUID("3c9a17f4-6d2b-4e58-9c07-1a5f83b2d6e9")
NAME_NAMESPACE = uuid.UUID("b7e40c19-2f6a-4d83-a5c1-9e0374b62d58")

# The dictionary entry each field type needs. A field type maps to one datatype;
# the reverse does not hold, so this is looked up by type and never by datatype.
DATATYPE = {
    "boolean": "Boolean",
    "yn": "Coded",
    "yna": "Coded",
    "coded": "Coded",
    "number": "Numeric",
    "text": "Text",
    "datetime": "Datetime",
}

# A measurement or a sign is a Finding; who the child is and how the visit was
# handled is not. The class is stated per field where it is the second thing.
DEFAULT_CLASS = "Finding"
CLASSES = {"Misc"}

# A question that asks for a count or a measurement is read to a decimal place
# when it is written down, a count is not.
ALLOW_DECIMALS = {
    "U5 Temperature (C)", "U5 Weight (kg)", "U5 Height or length (cm)",
    "U5 MUAC (cm)", "U5 WHZ", "U5 WAZ", "U5 Breathing rate (per minute)",
    "U5 Oxygen saturation (percent)",
    "U5 Duration of cough or breathing problem (days)",
    "U5 Duration of diarrhoea (days)", "U5 Duration of ear problem (days)",
    "U5 Duration of fever (days)",
}

# The shared Yes / No / Not applicable answers are reached through every question
# that offers them, so they are collected once rather than per field.
SHARED_ANSWERS = {"U5 Yes", "U5 No", "U5 Not applicable"}


def concept_class(field):
    return "Misc" if field.get("cls") in CLASSES else DEFAULT_CLASS


def concept_datatype(field):
    return DATATYPE[field["type"]]


def allow_decimals(name):
    return "true" if name in ALLOW_DECIMALS else ""


def collect():
    """Every concept the form needs, in the order the form shows them."""
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

    for section in u5_spec.SECTIONS:
        for field in section["fields"]:
            name = field.get("concept")
            if not name:
                continue
            datatype = concept_datatype(field)
            cls = concept_class(field)
            add(name, datatype, cls, section["title"])
            if datatype == "Coded":
                for option in field["options"]:
                    # A shared answer is one concept whichever question offers it,
                    # so it is added under the first section that asks for it.
                    add(option["concept"], "N/A", "Misc", section["title"])
    return concepts


def seed(shipped_csv):
    """Pin every UUID the register already ships."""
    if os.path.exists(UUIDS):
        raise SystemExit(
            f"{UUIDS} already exists - re-seeding would pin whatever the last "
            f"generation produced instead of what is deployed. Delete it "
            f"deliberately if that is what you want.")
    if not os.path.exists(shipped_csv):
        raise SystemExit(f"nothing to seed from: {shipped_csv} does not exist")

    pinned = {}
    with open(shipped_csv, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            pinned[row["fully specified name:en"]] = {
                "uuid": row["uuid"],
                "name_uuid": row["fully specified name:en:uuid"],
            }
    with open(UUIDS, "w", encoding="utf-8") as fh:
        json.dump(pinned, fh, indent=2, ensure_ascii=False, sort_keys=True)
        fh.write("\n")
    print(f"pinned {len(pinned)} concepts from {os.path.relpath(shipped_csv, HERE)}")
    return pinned


def load_pinned():
    if not os.path.exists(UUIDS):
        raise SystemExit(
            f"{UUIDS} is missing. Run `python3 register_concepts.py --seed` once "
            f"to pin the concepts this register already ships.")
    with open(UUIDS, encoding="utf-8") as fh:
        return json.load(fh)


def assign(concepts, pinned):
    """The UUID for every concept, minting one for anything not pinned."""
    added = []
    for concept in concepts:
        name = concept["name"]
        if name not in pinned:
            pinned[name] = {
                "uuid": str(uuid.uuid5(CONCEPT_NAMESPACE, name)),
                "name_uuid": str(uuid.uuid5(NAME_NAMESPACE, name)),
            }
            added.append(name)

    by_uuid = {}
    for name, ids in pinned.items():
        clash = by_uuid.setdefault(ids["uuid"], name)
        if clash != name:
            raise SystemExit(
                f"two concepts share uuid {ids['uuid']}: {clash!r} and {name!r}. "
                f"A concept UUID has to be unique or one of them is unreachable.")
    return added


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", action="store_true",
                        help="pin the UUIDs in the shipped concepts CSV, then exit")
    parser.add_argument("--csv", default=SHIPPED_CSV,
                        help="the concepts CSV to seed from")
    args = parser.parse_args()

    if args.seed:
        seed(os.path.abspath(args.csv))
        return 0

    concepts = collect()
    pinned = load_pinned()
    added = assign(concepts, pinned)

    with open(UUIDS, "w", encoding="utf-8") as fh:
        json.dump(pinned, fh, indent=2, ensure_ascii=False, sort_keys=True)
        fh.write("\n")
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(concepts, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    referenced = {c["name"] for c in concepts}
    orphans = sorted(name for name in pinned if name not in referenced)
    print(f"{len(concepts)} concepts -> {os.path.relpath(OUT, HERE)}")
    print(f"  uuid file     : {os.path.relpath(UUIDS, HERE)} ({len(pinned)} pinned)")
    print(f"  new           : {len(added)}" + (f" -> {', '.join(added)}" if added else ""))
    if orphans:
        print(f"  pinned but unused: {len(orphans)}")
        for name in orphans:
            print(f"      {name}")
        print("  A pinned concept the form no longer asks for is left in place on "
              "purpose: it may already have observations against it. Retire it "
              "deliberately, do not let it fall out of the file by accident.")
    return 0


if __name__ == "__main__":
    sys.exit(main())