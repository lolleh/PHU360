#!/usr/bin/env python3
"""Create the register forms' concepts in OpenMRS, keyed by the derived UUIDs.

concept-uuids.json is the single source of truth for concept identity, produced by
register_concepts.py. The first 133 entries were minted by an earlier REST call
and are preserved as-is; the rest are uuid5-derived so they are identical on every
machine. This script loads whichever of them the database is missing.

The REST API honours a client-supplied uuid on POST, so the derived values are
used verbatim rather than letting the server invent new ones. If that ever stops
holding, the mismatch is reported instead of silently accepted.

Idempotent and safe to re-run. A concept already in the database is never
modified; a name found under a different uuid is a hard failure, because the form
would then point at a concept other than the one described here.

    python3 load_concepts.py
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
            return c.get("uuid")
    return None


def description_for(concept):
    form = concept.get("form", "mnr")
    return (f"{concept.get('section', 'Register')} field on the "
            f"{form} register form: {concept['name']}")


def main():
    with open(SPEC, encoding="utf-8") as fh:
        concepts = json.load(fh)
    with open(UUIDS, encoding="utf-8") as fh:
        uuids = json.load(fh)

    created = reused = 0
    conflicts, failed = [], []

    for i, c in enumerate(concepts, 1):
        name = c["name"]
        want = uuids.get(name)
        if not want:
            failed.append((name, "no uuid in concept-uuids.json", ""))
            continue

        existing = find_by_name(name)
        if existing:
            if existing != want:
                conflicts.append((name, want, existing))
                print(f"[{i}/{len(concepts)}] CONFLICT {name}")
            else:
                reused += 1
            continue

        payload = {
            "uuid": want,
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
        if st in (200, 201) and got == want:
            created += 1
            if i % 25 == 0 or i == len(concepts):
                print(f"[{i}/{len(concepts)}] created={created} reused={reused}")
        else:
            failed.append((name, st, data))
            print(f"[{i}/{len(concepts)}] FAILED {name} -> HTTP {st}: {data}")
        time.sleep(DELAY)

    print(f"\ncreated={created} reused={reused} "
          f"conflicts={len(conflicts)} failed={len(failed)}")

    if conflicts:
        print("\nUUID CONFLICTS (name exists under a different uuid):")
        for name, want, got in conflicts:
            print(f"  {name}\n    expected {want}\n    in db   {got}")
    if failed:
        print("\nFAILURES:")
        for name, st, data in failed[:20]:
            print(f"  {name}: HTTP {st} {data}")
    if conflicts or failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
