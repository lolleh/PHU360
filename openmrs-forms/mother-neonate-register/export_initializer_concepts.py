#!/usr/bin/env python3
"""Export the register's concepts to an Initializer concepts CSV.

The form references its concepts by uuid. Those concepts were originally
created over the REST API (create_concepts.py), which only affects whichever
database happened to be running - a fresh Initializer boot would find the form
pointing at concepts that do not exist. This writes them out as Initializer
metadata instead, so a clean build recreates the dictionary from the config.

Only the concepts in concept-uuids.json are exported. The dictionary on a
development box also holds earlier Boolean-flavoured variants of the same fields
and the voided remains of a concept whose name had to be corrected; no form and no
preserved observation points at those, so they stay out.

Reads the registry for the concept ids and the REST API for the definitions, so
the CSV is a faithful mirror of the database the forms were verified against:

    python3 export_initializer_concepts.py
"""
import base64
import csv
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(
    HERE,
    "..", "..", "content", "configuration", "backend_configuration",
    "concepts", "registerConcepts.csv",
)

BASE = os.environ.get("OPENMRS_URL", "http://127.0.0.1:8090/openmrs/ws/rest/v1")
USER = os.environ.get("OPENMRS_USER", "admin")
PASS = os.environ.get("OPENMRS_PASS", "Admin123")

# Yes / No / Not applicable ship with the base OpenMRS dictionary; re-declaring
# them here would be redundant.
STOCK_CONCEPTS = {
    "3cd6f600-26fe-102b-80cb-0017a47871b2",  # Yes
    "3cd6f86c-26fe-102b-80cb-0017a47871b2",  # No
    "3cd7b72a-26fe-102b-80cb-0017a47871b2",  # Not applicable
}

# Initializer's ConceptsCsvParser only treats a header as a concept name when the
# first colon-delimited segment starts with "fully specified name" / "short name"
# / "synonym" / "index term" (see ConceptLineProcessor.getConceptNameHeaders).
# The name type therefore has to come FIRST: "fully specified name:en", not
# "name:en:fully specified name". The preferred flag and name uuid hang off that
# same base header as a third segment.
FSN = "fully specified name:en"
PREFERRED = FSN + ":preferred"
NAME_UUID = FSN + ":uuid"
# A bare "description" header parses to a LocalizedHeader with an empty locale
# set, so ConceptLineProcessor never adds a description. The locale has to be
# spelled out for the descriptions to land at all.
DESCRIPTION = "description:en"


def api(path):
    req = urllib.request.Request(BASE + path, method="GET")
    req.add_header("Accept", "application/json")
    req.add_header(
        "Authorization", "Basic " + base64.b64encode(f"{USER}:{PASS}".encode()).decode()
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        print(f"FATAL: GET {path} -> HTTP {e.code}: {e.read().decode()[:300]}")
        sys.exit(2)
    except urllib.error.URLError as e:
        print(f"FATAL: cannot reach {BASE}: {e}")
        sys.exit(2)


def concept_ids():
    """The registry's concept ids, in a stable order.

    concept-uuids.json is the canonical dictionary list: it holds every concept
    the forms reference, plus BABY1/BABY2, which two preserved historical
    observations still point at even though the form renders those blocks as
    plain fieldsets. generate_form.py separately asserts that the forms reference
    nothing outside this list, so reading the registry here cannot let a form
    reference slip past the CSV unnoticed.
    """
    with open(os.path.join(HERE, "concept-uuids.json"), encoding="utf-8") as fh:
        registry = json.load(fh)
    seen, ids = set(), []
    for name in sorted(registry):
        uuid = registry[name]
        if uuid not in seen:
            seen.add(uuid)
            ids.append(uuid)
    return ids


def name_uuid(concept, name):
    for n in concept.get("names", []):
        if n.get("display") == name and n.get("conceptNameType") == "FULLY_SPECIFIED":
            return n.get("uuid", "")
    return ""


def main():
    out_path = os.path.normpath(OUT)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    ids = [c for c in concept_ids() if c not in STOCK_CONCEPTS]
    rows = []
    for i, cid in enumerate(ids, 1):
        c = api(f"/concept/{cid}?v=full")
        name = c["name"]["display"]
        rows.append({
            "uuid": c["uuid"],
            FSN: name,
            PREFERRED: "true",
            NAME_UUID: name_uuid(c, name),
            # Voided remnants of a corrected concept can still match a name
            # prefix; a retired concept is never the one a form points at.
            DESCRIPTION: next((d.get("description", "") for d in
                               (c.get("descriptions") or []) if d.get("description")),
                              ""),
            "data class": c.get("conceptClass", {}).get("display", ""),
            "data type": c.get("datatype", {}).get("display", ""),
        })
        print(f"[{i}/{len(ids)}] {name}")

    header = ["uuid", FSN, PREFERRED, NAME_UUID, DESCRIPTION,
              "data class", "data type"]
    with open(out_path, "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=header, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)

    print(f"\n{len(rows)} concepts -> {out_path}")


if __name__ == "__main__":
    main()
