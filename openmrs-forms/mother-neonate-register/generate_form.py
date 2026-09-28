#!/usr/bin/env python3
"""Generate the OpenMRS HTML FormEntry documents for the register forms.

Reads register_spec.FORMS (which assembles mnr_spec.py and source_spec.py) plus
concept-uuids.json, and emits one HTML form per entry. Every obs tag can only
reference a concept that already exists, so the build fails if any reference is
unresolved.

UI is deliberately the same visual language as the Above Five / Under Five
registers (pih/htmlforms/aboveFiveTreatmentRegister.xml): navy .uf-section
headers, cream .uf-field cells, responsive flex grid.

The Mother and Neonate Register keeps its published form UUID and encounter type;
the Delivery Register and Family Planning Register get their own.
"""
import html
import json
import os
import re

import mnr_spec
import register_spec as R
from register_concepts import STOCK_ANSWER_UUIDS, field_name, option_name

HERE = os.path.dirname(os.path.abspath(__file__))
UUIDS = os.path.join(HERE, "concept-uuids.json")
FORM_VERSION = "1.0"

SOURCE_URL = ("https://docs.google.com/spreadsheets/d/"
              "1qv9Uuw5a3cXYraohneogu4KL8RMM14vj")

# Grid sizing per field type: yes/no selects are narrow, dates/times wider.
COLS = {"yn": "uf-4col", "ynna": "uf-4col", "choice": "uf-3col",
        "dropdown": "uf-3col", "number": "uf-3col", "date": "uf-3col",
        "time": "uf-3col", "text": "uf-3col"}

STYLE = {
    "yn": "dropdown",
    "ynna": "dropdown",
    "choice": "dropdown",
    "dropdown": "dropdown",
    "number": "number",
    "date": "date",
    "time": "time",
    "text": "text",
}

# The stock Yes/No/N-A concepts are not created by this project, so they are
# allowed to be referenced without appearing in concept-uuids.json.
STOCK_CONCEPTS = {
    mnr_spec.STD_YES_UUID,
    mnr_spec.STD_NO_UUID,
    mnr_spec.STD_NA_UUID,
}

NULL_OPTION = "[Choose]"

CSS = """
        #who-when-where { margin-bottom: 12px; }
        #who-when-where p { display: inline-block; margin: 0 24px 8px 0; }
        #who-when-where label { font-weight: bold; }

        .uf-section { background: #ffffff; border: 1px solid #d9d2c4; border-radius: 6px; margin-bottom: 14px; overflow: hidden; }
        .uf-section h4.uf-title { margin: 0; padding: 8px 14px; background: #1d3d5c; color: #ffffff; font-size: 1em; letter-spacing: 0.3px; }
        .uf-grid { display: flex; flex-wrap: wrap; padding: 12px 12px 4px; }

        .uf-field { background: #faf7f2; border: 1px solid #ece6dc; border-radius: 4px;
                    flex: 1 1 300px; box-sizing: border-box; padding: 8px 10px; margin: 0 6px 8px 0; }
        .uf-field .flabel { display: block; font-weight: bold; color: #444; margin-bottom: 4px; font-size: 0.92em; }
        .uf-field .fcode { display: inline-block; min-width: 2.6em; color: #1d3d5c; font-weight: 700; }
        .uf-field .fnote { display: block; color: #8a6d3b; font-size: 0.78em; font-style: italic; margin-top: 3px; }
        .uf-field select { max-width: 100%; }
        .uf-field input[type="text"] { box-sizing: border-box; }
        .uf-field input[type="checkbox"] { width: 16px; height: 16px; }

        .uf-2col { flex: 1 1 300px; max-width: 48%; }
        .uf-3col { flex: 1 1 220px; max-width: 32%; }
        .uf-4col { flex: 1 1 150px; max-width: 24%; }
        .uf-full { flex: 1 1 100%; max-width: 100%; }
        .uf-full input[type="text"] { width: 100%; }
        .uf-sub { color: #1d3d5c; font-size: 0.85em; letter-spacing: 0.4px; text-transform: uppercase; font-weight: 700; }

        .field-error { color: #ff6666; font-size: 1.1em; display: block; }

"""


def load_uuids():
    with open(UUIDS, encoding="utf-8") as fh:
        return json.load(fh)


def resolve(name, uuids):
    try:
        return uuids[name]
    except KeyError:
        raise SystemExit(
            f"missing concept uuid for {name!r} - run register_concepts.py first")


def field_obs(f, uuids):
    cid = resolve(field_name(f), uuids)
    style = STYLE[f["type"]]
    if f["type"] in STOCK_ANSWER_UUIDS:
        ids = ",".join(STOCK_ANSWER_UUIDS[f["type"]])
        return (f'<obs conceptId="{cid}" style="{style}" '
                f'nullOption="{NULL_OPTION}" answerConceptIds="{ids}"/>')
    if f.get("options") and f["type"] in ("choice", "dropdown"):
        ids = ",".join(resolve(option_name(f, o), uuids) for o in f["options"])
        return (f'<obs conceptId="{cid}" style="{style}" '
                f'nullOption="{NULL_OPTION}" answerConceptIds="{ids}"/>')
    return f'<obs conceptId="{cid}" style="{style}"/>'


def field_html(f, uuids, extra_cls=""):
    cls = (COLS[f["type"]] + " " + extra_cls).strip()
    note = ""
    if f.get("note"):
        note = f'<span class="fnote">{html.escape(f["note"])}</span>'
    return (
        f'            <div class="uf-field {cls}">'
        f'<span class="flabel"><span class="fcode">{html.escape(f["code"])}</span>'
        f'{html.escape(f["label"])}</span>'
        f'{field_obs(f, uuids)}{note}</div>'
    )


def repeat_label(section, i):
    """Label a repeated block, naming the visit window where one applies."""
    label = f"{section.get('repeat_label', 'Repeat')} {i}"
    windows = section.get("visit_windows")
    if windows and i <= len(windows):
        label = f"{label} ({windows[i - 1]})"
    return label


def section_html(sec, uuids):
    out = [
        '        <div class="uf-section">',
        f'            <h4 class="uf-title">{html.escape(sec["title"])}</h4>',
        '            <div class="uf-grid">',
    ]
    if sec.get("repeat"):
        group_fn = sec.get("group_name") or mnr_spec.baby_group_name
        for i in range(1, sec["repeat"] + 1):
            gid = resolve(group_fn(i), uuids)
            out.append(f'            <div class="uf-full"><span class="uf-sub">'
                       f'{html.escape(repeat_label(sec, i))}</span></div>')
            out.append(f'            <obsgroup groupingConceptId="{gid}">')
            for f in sec["fields"]:
                out.append(field_html(f, uuids))
            out.append('            </obsgroup>')
    else:
        for f in sec["fields"]:
            out.append(field_html(f, uuids))
    out += ['            </div>', '        </div>']
    return "\n".join(out)


def client_section(form):
    return "\n".join([
        '        <div class="uf-section">',
        '            <h4 class="uf-title">Client &amp; Provider</h4>',
        '            <div class="uf-grid">',
        '            <div class="uf-field uf-3col">'
        '<span class="flabel">Provider</span> '
        '<encounterProvider default="currentUser" required="true"/></div>',
        '            <div class="uf-field uf-3col">'
        '<span class="flabel">Encounter date</span> '
        '<encounterDate default="now" required="true"/></div>',
        '            <div class="uf-field uf-3col">'
        '<span class="flabel">Client</span> '
        f'<span class="fnote">{html.escape(form["client_note"])}</span>',
        '</div>',
        '            </div>',
        '        </div>',
    ])


def build_form(form, uuids):
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!--',
        f'  {form["name"].upper()} - OpenMRS HTML FormEntry',
        '',
        f'  SOURCE      : {SOURCE_URL}',
        f'  ENCOUNTER   : {form["encounter_name"]} ({form["encounter_uuid"]})',
        '  CONCEPTS    : generated by register_concepts.py; every conceptId below',
        '                exists in the dictionary (see concept-uuids.json).',
        '  UI          : same visual language as the Above Five / Under Five',
        '                registers (navy section bands, cream field cells).',
        '-->',
        f'<htmlform formUuid="{form["uuid"]}" formName="{form["name"]}" '
        f'formEncounterType="{form["encounter_uuid"]}" formVersion="{FORM_VERSION}">',
        '    <style type="text/css">' + CSS + '    </style>',
        f'    <h3>{html.escape(form["name"])}</h3>',
        '',
    ]
    if form["facility_fields"]:
        parts.append(section_html(
            dict(title="Facility / Reporting Period", fields=form["facility_fields"]),
            uuids))

    parts.append(client_section(form))
    for sec in form["sections"]:
        parts.append(section_html(sec, uuids))
    parts += ['', '    <submit id="submit"/>', '</htmlform>', '']
    return "\n".join(parts)


def referenced_concepts(xml):
    refs = set(re.findall(r'conceptId="([0-9a-f-]{36})"', xml))
    refs |= {u for group in re.findall(r'answerConceptIds="([^"]+)"', xml)
             for u in group.split(",")}
    return refs


def main():
    uuids = load_uuids()
    failures = []

    for form in R.FORMS:
        xml = build_form(form, uuids)
        out = os.path.join(HERE, form["out_xml"])
        with open(out, "w", encoding="utf-8") as fh:
            fh.write(xml)

        refs = referenced_concepts(xml)
        missing = refs - set(uuids.values()) - STOCK_CONCEPTS
        obs = xml.count("<obs ")
        print(f"{form['name']}")
        print(f"  -> {out}")
        print(f"  bytes             : {len(xml)}")
        print(f"  obs               : {obs}")
        print(f"  form uuid         : {form['uuid']}")
        print(f"  encounter type    : {form['encounter_uuid']}")
        print(f"  distinct concepts : {len(refs)}  missing: {len(missing)}")
        if missing:
            failures.append((form["key"], sorted(missing)))

    if failures:
        for key, missing in failures:
            print(f"\nMISSING CONCEPTS in {key}:")
            for uuid_value in missing[:20]:
                print(f"  {uuid_value}")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
