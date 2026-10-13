#!/usr/bin/env python3
"""Create the register's concepts in a running OpenMRS, keyed by their pinned UUIDs.

`concept-uuids.json` is the identity of every concept in this register, and the
concepts CSV in `content/configuration` is how a clean distro build gets them.
This script exists for the other case: a server you are testing the form against,
where the concepts CSV has not been redeployed yet, so the form would render with
three questions pointing at concepts that do not exist.

The REST API honours a client-supplied uuid on POST, so the pinned values are used
verbatim rather than letting the server invent new ones. If that ever stops
holding, the mismatch is reported rather than accepted - a concept minted fresh
under a new uuid is a form full of questions that record nothing, discovered later.

Idempotent and safe to re-run. A concept already present is never modified.

Twelve of this register's concepts have changed datatype since the last
digitisation: ten drug booleans became counts and two disability booleans became
questions. OpenMRS will not change a concept's datatype under observations that
already point at it, and this script will not try to work around that. Those are
reported by name and left alone; they need a dictionary migration on the server,
which is a deliberate operation and not something a form generator should do to
somebody's data.

    python3 load_concepts.py            # or OPENMRS_URL=... python3 load_concepts.py
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = os.path.join(HERE, "concepts-to-create.json")
UUIDS = os.path.join(HERE, "concept-uuids.json")

BASE = os.environ.get("OPENMRS_URL", "http://127.0.0.1:8090/openmrs/ws/rest/v1")
USER = os.environ.get("OPENMRS_USER", "admin")
PASS = os.environ.get("OPENMRS_PASS", "Admin123")

# Short, stable delay: the point is to avoid a burst, not to be gentle for minutes.
DELAY = 0.05

# The datatypes the register's live concepts already have, from the dictionary as
# it was last shipped. Anything else for these names means the server still holds
# the older shape and cannot be taught the new one without a migration.
SHIPPED_DATATYPES = {
    "U5 Screened for disability": "Boolean",
    "U5 Has disability": "Boolean",
    "U5 Albendazole 400mg": "Boolean",
    "U5 Amoxicillin 250mg": "Boolean",
    "U5 ACT AL-6 or ASAQ 3 for 2 to 11 months": "Boolean",
    "U5 ACT AL-12 or ASAQ 3 for 1 to 5 years": "Boolean",
    "U5 ORS sachet": "Boolean",
    "U5 Paracetamol 100mg": "Boolean",
    "U5 Zinc sulphate 20mg": "Boolean",
    "U5 Rectal artesunate suppository": "Boolean",
    "U5 Artesunate 60mg per ml injection": "Boolean",
    "U5 Oxygen therapy": "Boolean",
}


def api(method, path, payload=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Accept", "application/json")
    req.add_header("Authorization",
                   "Basic " + base64.b64encode(f"{USER}:{PASS}".encode()).decode())
    if payload is not None:
        req.add_header("Content-Type", "application/json")
        req.data = json.dumps(payload).encode()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read().decode()
            return resp.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, body[:300]
    except urllib.error.URLError as e:
        print(f"FATAL: cannot reach {BASE}: {e}")
        sys.exit(2)


def find_by_name(name):
    st, data = api("GET", "/concept?q=" + urllib.parse.quote(name) + "&v=full")
    if st != 200 or not data:
        return None
    for c in data.get("results", []):
        if c.get("display") == name:
            return c
    return None


def description_for(concept):
    return (f"{concept.get('section', 'Register')} field on the Under Five "
            f"IMNCI register form: {concept['name']}")


def main():
    with open(SPEC, encoding="utf-8") as fh:
        concepts = json.load(fh)
    with open(UUIDS, encoding="utf-8") as fh:
        uuids = json.load(fh)

    created = reused = 0
    conflicts, failed, stale = [], [], []

    for i, c in enumerate(concepts, 1):
        name = c["name"]
        want = uuids.get(name)
        if not want:
            failed.append((name, "no uuid in concept-uuids.json", ""))
            continue

        existing = find_by_name(name)
        if existing:
            if existing.get("uuid") != want["uuid"]:
                conflicts.append((name, want["uuid"], existing.get("uuid")))
                print(f"[{i}/{len(concepts)}] CONFLICT {name}")
            else:
                reused += 1
                shipped = SHIPPED_DATATYPES.get(name)
                have = ((existing.get("datatype") or {}).get("display")
                        or existing.get("datatype"))
                if shipped and have and have != c["datatype"]:
                    # The concept exists under the uuid we want but with the old
                    # datatype. Changing it under existing observations is a
                    # migration, not a create, and this script will not attempt it.
                    stale.append((name, have, c["datatype"]))
                    print(f"[{i}/{len(concepts)}] NEEDS MIGRATION {name}: "
                          f"{have} -> {c['datatype']}")
            continue

        payload = {
            "uuid": want["uuid"],
            "names": [{
                "name": name, "locale": "en", "localePreferred": True,
                "conceptNameType": "FULLY_SPECIFIED",
            }],
            "descriptions": [{
                "description": description_for(c), "locale": "en",
            }],
            "datatype": c["datatype"],
            "conceptClass": c["conceptClass"],
            "set": False,
        }
        st, data = api("POST", "/concept", payload)
        got = data.get("uuid") if isinstance(data, dict) else None
        if st in (200, 201) and got == want["uuid"]:
            created += 1
            if i % 25 == 0 or i == len(concepts):
                print(f"[{i}/{len(concepts)}] created={created} reused={reused}")
        else:
            failed.append((name, st, data))
            print(f"[{i}/{len(concepts)}] FAILED {name} -> HTTP {st}: {data}")
        time.sleep(DELAY)

    print(f"\ncreated={created} reused={reused} conflicts={len(conflicts)} "
          f"needs-migration={len(stale)} failed={len(failed)}")

    if conflicts:
        print("\nUUID CONFLICTS (name exists under a different uuid):")
        for name, want, got in conflicts:
            print(f"  {name}\n    expected {want}\n    in db   {got}")
    if stale:
        print("\nNEEDS A DICTIONARY MIGRATION (datatype differs under the same "
              "uuid):")
        for name, have, want in stale:
            print(f"  {name}: {have} -> {want}")
        print("  OpenMRS will not change a concept's datatype under existing "
              "observations, so the")
        print("  server's dictionary needs migrating before this form's questions "
              "render as written.")
    if failed:
        print("\nFAILURES:")
        for name, st, data in failed[:20]:
            print(f"  {name}: HTTP {st} {data}")
    return 1 if (conflicts or failed) else 0


if __name__ == "__main__":
    sys.exit(main())