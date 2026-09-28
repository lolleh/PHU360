#!/usr/bin/env python3
"""Build the concept dictionary entries and UUIDs for all three register forms.

Walks every section of every form in register_spec.FORMS and emits one concept
per field, one per non Yes/No answer, and one N/A group concept per repeated
block. The result is merged into concept-uuids.json.

UUIDs are of two kinds and the distinction matters:

  * The 131 concepts from the first pass were minted by create_concepts.py
    against a running OpenMRS and already exist in the database, under the
    observations and encounters recorded so far. Their UUIDs are read from
    concept-uuids.json and never recomputed.
  * The new concepts get uuid5(NS, name), which needs no running server and is
    stable across machines and regenerations.

If a name is ever present in both files the existing UUID wins, so re-running
this can never move a concept that data already points at.
"""
import json
import os

import mnr_spec
import register_spec as R
import source_spec as S
from generate_concepts_spec import sanitize

HERE = os.path.dirname(os.path.abspath(__file__))
UUIDS = os.path.join(HERE, "concept-uuids.json")
# The first pass's concepts are pinned here so pruning can never remove one
# that existing observations reference.
FIRST_PASS = os.path.join(HERE, "concept-uuids.firstpass.json")
OUT = os.path.join(HERE, "concepts-to-create.json")

# This dictionary has no "Numeric" concept class (it runs Anatomy..Workflow), so
# numeric questions and answers are classed Misc, matching the standard OpenMRS
# numeric concepts such as Weight and Height.
DATATYPE = {
    "yn": "Coded",
    "ynna": "Coded",
    "choice": "Coded",
    "dropdown": "Coded",
    "number": "Numeric",
    "text": "Text",
    "date": "Date",
    "time": "Time",
}

# Keyed by datatype, not by field type: a field type can map to only one
# datatype, but the reverse is not true, and looking one up by the other
# silently falls through to Misc for everything.
CONCEPT_CLASS = {
    "Coded": "Misc",
    "Numeric": "Misc",
    "Text": "Misc",
    "Date": "Misc",
    "Time": "Misc",
    "N/A": "Misc",
}

# Yes/No fields reuse the stock dictionary concepts rather than duplicating them.
# A Boolean checkbox can only ever record "yes", which loses the explicit No the
# register needs to count.
STOCK_ANSWER_UUIDS = {
    "yn": mnr_spec.STD_YES_NO,
    "ynna": mnr_spec.STD_YES_NO_NA,
}


def field_name(f):
    return sanitize(f"MNR {f['code']} {f['label']}")


def option_name(f, opt):
    return sanitize(f"MNR {f['code']} {f['label']} :: {opt}")


def answer_uuids_for(f):
    """Stock answer concept UUIDs for a Yes/No field, else the created answers."""
    if f["type"] in STOCK_ANSWER_UUIDS:
        return STOCK_ANSWER_UUIDS[f["type"]]
    return None


def collect():
    """Every concept the three forms need, in form and section order."""
    concepts = []
    seen = set()

    def add(name, datatype, section, form_key, note=None):
        if name in seen:
            return
        seen.add(name)
        entry = {
            "name": name,
            "datatype": datatype,
            "conceptClass": CONCEPT_CLASS[datatype],
            "section": section,
            "form": form_key,
        }
        if note:
            entry["note"] = note
        concepts.append(entry)

    for form in R.FORMS:
        for section in form["sections"]:
            for f in section["fields"]:
                add(field_name(f), DATATYPE[f["type"]], section["title"],
                    form["key"])
                if f["type"] not in STOCK_ANSWER_UUIDS and f.get("options"):
                    for opt in f["options"]:
                        add(option_name(f, opt), "N/A", section["title"],
                            form["key"])
            if section.get("repeat"):
                # Each repeated block is wrapped in an <obsgroup> keyed by its
                # own grouping concept, so these are referenced by the form and
                # must exist in the dictionary.
                group_fn = section.get("group_name")
                if group_fn is None:
                    group_fn = mnr_spec.baby_group_name
                for i in range(1, section["repeat"] + 1):
                    add(group_fn(i), "N/A", section["title"], form["key"],
                        note="separates one repeated block from the next")
        for f in form["facility_fields"]:
            add(field_name(f), DATATYPE[f["type"]], "Facility", form["key"])

    return concepts


def merge_uuids(concepts):
    """Add UUIDs for the new concepts, keeping every existing UUID unchanged.

    The first pass minted its UUIDs against a running OpenMRS, so they are not
    reproducible from the name and must never be recomputed. Names already
    present keep their UUID; only genuinely new names get a derived one.
    """
    with open(UUIDS, encoding="utf-8") as fh:
        existing = json.load(fh)
    before = dict(existing)

    for concept in concepts:
        name = concept["name"]
        if name not in existing:
            existing[name] = S.concept_uuid(name)

    # The invariant that matters: no concept that already had a UUID lost it or
    # was given a different one. Existing observations point at these values.
    changed = {name: (value, existing.get(name))
               for name, value in before.items()
               if existing.get(name) != value}
    reused = sorted({c["name"] for c in concepts} & set(before))

    # Names the fields no longer produce - for instance after a concept name is
    # corrected - would otherwise linger in the registry forever. They are pruned
    # and reported so a corresponding database cleanup can be made; the first
    # pass's concepts are never pruned, since observations may point at them.
    wanted = {c["name"] for c in concepts}
    first_pass = set(json.load(open(FIRST_PASS, encoding="utf-8"))) \
        if os.path.exists(FIRST_PASS) else set()
    stale = sorted(n for n in existing
                   if n not in wanted and n not in first_pass
                   and n not in set(reused))
    for name in stale:
        del existing[name]

    with open(UUIDS, "w", encoding="utf-8") as fh:
        json.dump(existing, fh, indent=2, ensure_ascii=False, sort_keys=True)
        fh.write("\n")

    return existing, changed, reused, stale, [n for n in concepts
                                             if n["name"] not in before]


def main():
    concepts = collect()
    uuids, changed, reused, stale, fresh = merge_uuids(concepts)

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(concepts, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    by_form = {}
    by_dt = {}
    for c in concepts:
        by_form[c["form"]] = by_form.get(c["form"], 0) + 1
        by_dt[c["datatype"]] = by_dt.get(c["datatype"], 0) + 1

    print(f"concepts -> {OUT}")
    print(f"  total       : {len(concepts)}")
    for form in R.FORMS:
        print(f"  {form['key']:<16} {by_form.get(form['key'], 0)}")
    for k, v in sorted(by_dt.items()):
        print(f"  {k:<11} {v}")
    print(f"uuids     -> {UUIDS} ({len(uuids)} total)")
    print(f"  new       : {len(fresh)} given a derived uuid this run")
    print(f"  reused    : {len(reused)} kept their existing uuid")
    # The registry can legitimately be larger than what the forms produce: a
    # concept no field renders but a preserved observation still points at is
    # kept, so the Initializer CSV and the database stay in step.
    kept = sorted(set(uuids) - {c["name"] for c in concepts})
    if kept:
        print(f"  retained  : {len(kept)} no longer in any form but kept for "
              f"existing data")
        for name in kept:
            print(f"      KEPT   {name}")
    if stale:
        print(f"  pruned    : {len(stale)} names the fields no longer produce")
        for name in stale:
            print(f"      STALE {name}")

    if changed:
        print("\nEXISTING UUIDS CHANGED (observations would be orphaned):")
        for name, (was, now) in sorted(changed.items()):
            print(f"  {name}\n    was {was}\n    now {now}")
        raise SystemExit(1)

    missing = [c["name"] for c in concepts if c["name"] not in uuids]
    if missing:
        raise SystemExit(f"concepts without a uuid: {missing[:5]}")


if __name__ == "__main__":
    main()
