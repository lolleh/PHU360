#!/usr/bin/env python3
"""Check the deployed register forms against the running OpenMRS.

generate_form.py proves the generated XML is internally consistent; this proves
the other half - that the database the forms will actually open in contains
every concept, form, and encounter type they reference.

    python3 verify_deployed.py
"""
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

import register_spec as R

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("OPENMRS_URL", "http://127.0.0.1:8090/openmrs/ws/rest/v1")
USER = os.environ.get("OPENMRS_USER", "admin")
PASS = os.environ.get("OPENMRS_PASS", "Admin123")

problems = []


def api(path):
    req = urllib.request.Request(BASE + path)
    req.add_header("Accept", "application/json")
    req.add_header("Authorization",
                   "Basic " + base64.b64encode(f"{USER}:{PASS}".encode()).decode())
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, None
    except urllib.error.URLError as e:
        print(f"FATAL: cannot reach {BASE}: {e}")
        sys.exit(2)


def form_concept_refs(path):
    """Every concept the form points at, from obs and obsgroup alike."""
    root = ET.parse(path).getroot()
    ids = []
    for obs in root.iter("obs"):
        ids.append(obs.get("conceptId"))
        ids += (obs.get("answerConceptIds") or "").split(",")
    for group in root.iter("obsgroup"):
        ids.append(group.get("groupingConceptId"))
    return [i for i in (x.strip() for x in ids) if i]


def main():
    with open(os.path.join(HERE, "concept-uuids.json"), encoding="utf-8") as fh:
        uuids = json.load(fh)
    by_uuid = {v: k for k, v in uuids.items()}

    total_refs = 0
    for form in R.FORMS:
        path = os.path.join(HERE, form["out_xml"])
        label = form["name"]

        # 1. the form itself is published under the expected uuid
        st, meta = api(f"/form/{form['uuid']}")
        if st != 200:
            problems.append(f"{label}: form {form['uuid']} not found (HTTP {st})")
        else:
            if not meta.get("published"):
                problems.append(f"{label}: form is not published")
            if not meta.get("edit") and meta.get("retired"):
                problems.append(f"{label}: form is retired")

        # 2. its encounter type exists
        st, enc = api(f"/encountertype/{form['encounter_uuid']}")
        if st != 200:
            problems.append(f"{label}: encounter type {form['encounter_uuid']} "
                            f"missing (HTTP {st})")
        elif enc.get("name") != form["encounter_name"]:
            problems.append(f"{label}: encounter type is named "
                            f"{enc.get('name')!r}, expected "
                            f"{form['encounter_name']!r}")

        # 3. every concept it references resolves, to the right name
        refs = form_concept_refs(path)
        total_refs += len(refs)
        bad = []
        for uuid in dict.fromkeys(refs):
            st, c = api(f"/concept/{uuid}")
            if st != 200 or c is None:
                bad.append((uuid, f"HTTP {st}"))
                continue
            want = by_uuid.get(uuid)
            got = c.get("name", {}).get("display")
            if want and got != want:
                bad.append((uuid, f"named {got!r}, registry says {want!r}"))
            elif c.get("retired"):
                bad.append((uuid, "retired"))
        if bad:
            problems.append(f"{label}: {len(bad)} of {len(set(refs))} concept "
                            f"references do not resolve")
            for uuid, why in bad[:5]:
                problems.append(f"    {uuid}  {why}")
        print(f"  {label:32} {len(set(refs)):4} concept refs  ok"
              if not bad else f"  {label:32} {len(set(refs)):4} concept refs  FAILED")

    print(f"\n  {total_refs} references checked across "
          f"{len(R.FORMS)} forms")

    if problems:
        print("\nPROBLEMS:")
        for p in problems:
            print("  " + p)
        sys.exit(1)
    print("\nOK: every deployed form reference resolves")


if __name__ == "__main__":
    main()
