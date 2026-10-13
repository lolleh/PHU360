#!/usr/bin/env python3
"""Generate the Under Five IMNCI Register's HTML FormEntry document.

Reads `u5_spec.SECTIONS` and `concept-uuids.json` and writes the form into the
distribution's configuration, where pihcore's HtmlFormSetup loads it on boot and
the patient dashboard's register buttons address it by its `formUuid`.

Every `<obs>` can only name a concept that exists, so the build fails if a
question's UUID is not in the pinned file rather than shipping a form that loads
and records nothing. The layout is the same visual language as the other PHU360
registers (navy section bands, cream field cells, a responsive flex grid) so the
five register buttons look like one family.

The form's UUID, its encounter type and every concept name already in the
dictionary are inputs, not outputs of this script. They are what the encounters
recorded so far point at.
"""
import html
import json
import os
import re
import sys

import u5_spec
from register_concepts import SHARED_ANSWERS, concept_class, concept_datatype

HERE = os.path.dirname(os.path.abspath(__file__))
UUIDS = os.path.join(HERE, "concept-uuids.json")
# Overridable so the generated form can be written beside the shipped one and
# diffed against it without touching the distribution.
OUT = os.environ.get("U5_FORM_OUT", os.path.join(
    HERE, "..", "..", "content", "configuration", "backend_configuration",
    "pih", "htmlforms", "underFiveIMNCIRegister.xml"))

# How each field type is drawn. A coded question needs its answers by uuid, so it
# is a select; a boolean is the register's tick box; a measurement is a number the
# browser will not let anyone type a word into.
STYLE = {
    "boolean": "checkbox",
    "yn": "dropdown",
    "yna": "dropdown",
    "coded": "dropdown",
    "number": "number",
    "text": "text",
    "datetime": "datetime",
}

COLUMNS = {
    "boolean": "uf-3col",
    "yn": "uf-3col",
    "yna": "uf-3col",
    "coded": "uf-3col",
    "number": "uf-3col",
    "text": "uf-3col",
    "datetime": "uf-3col",
}

NULL_OPTION = "[Choose]"

# htmlformentry preselects the first answer of a dropdown that does not say
# otherwise, so a question left alone is saved as if it had been answered that
# way. On this register that would record every visit as a New visit, every
# disability as Physical and every outcome as Improved, without the user touching
# anything - so every select names its empty option explicitly.

CSS = """        #who-when-where { margin-bottom: 12px; }
        #who-when-where p { display: inline-block; margin: 0 24px 8px 0; }
        #who-when-where label { font-weight: bold; }

        .uf-section { background: #ffffff; border: 1px solid #d9d2c4; border-radius: 6px; margin-bottom: 14px; overflow: hidden; }
        .uf-section h4.uf-title { margin: 0; padding: 8px 14px; background: #1d3d5c; color: #ffffff; font-size: 1em; letter-spacing: 0.3px; }
        .uf-grid { display: flex; flex-wrap: wrap; padding: 12px 12px 4px; }

        .uf-field { background: #faf7f2; border: 1px solid #ece6dc; border-radius: 4px;
                    flex: 1 1 300px; box-sizing: border-box; padding: 8px 10px; margin: 0 6px 8px 0; }
        .uf-field .flabel { display: block; font-weight: bold; color: #444; margin-bottom: 4px; font-size: 0.92em; }
        .uf-field .fnote { display: block; color: #8a6d3b; font-size: 0.78em; font-style: italic; margin-top: 3px; }
        .uf-field select { max-width: 100%; }
        .uf-field input[type="text"] { box-sizing: border-box; }
        .uf-field input[type="checkbox"] { width: 16px; height: 16px; }
        .uf-field input[type="number"] { width: 8em; }

        .uf-2col { flex: 1 1 300px; max-width: 48%; }
        .uf-3col { flex: 1 1 220px; max-width: 32%; }
        .uf-full { flex: 1 1 100%; max-width: 100%; }
        .uf-full input[type="text"] { width: 100%; }

        .field-error { color: #ff6666; font-size: 1.1em; display: block; }
"""


def load_uuids():
    with open(UUIDS, encoding="utf-8") as fh:
        return json.load(fh)


def resolve(name, uuids):
    ids = uuids.get(name)
    if not ids:
        raise SystemExit(
            f"no uuid pinned for {name!r} - run register_concepts.py, and check "
            f"the concept is still wanted by u5_spec.py")
    return ids["uuid"]


def escape(text):
    return html.escape(str(text), quote=False)


def field_obs(field, uuids):
    """The obs control for one field, or the encounter control it asks for."""
    kind = field["type"]
    if kind == "encounterDate":
        return "<encounterDate/>"
    if kind == "encounterProvider":
        return '<encounterProvider default="currentUser" required="true"/>'

    concept = resolve(field["concept"], uuids)
    style = STYLE[kind]
    if kind == "text" and field.get("style") == "textarea":
        style = "textarea"
    if kind in ("yn", "yna", "coded"):
        answers = ",".join(resolve(o["concept"], uuids) for o in field["options"])
        return (f'<obs conceptId="{concept}" style="{style}" '
                f'nullOption="{NULL_OPTION}" answerConceptIds="{answers}"/>')
    return f'<obs conceptId="{concept}" style="{style}"/>'


def field_html(field, uuids, section):
    if field["type"] == "note":
        return (f'            <div class="uf-field uf-full">'
                f'<span class="fnote">{escape(field["label"])}</span></div>')
    # A section sets the width its questions are drawn at, because the register
    # prints them side by side in one block; a field overrides it where the
    # register prints a full line of prose instead.
    cols = field.get("cols") or section.get("cols") or COLUMNS.get(field["type"])
    note = ""
    if field.get("note"):
        note = f'<span class="fnote">{escape(field["note"])}</span>'
    return (f'            <div class="uf-field {cols}">'
            f'<span class="flabel">{escape(field["label"])}</span> '
            f'{field_obs(field, uuids)}{note}</div>')


def section_html(section, uuids):
    out = [
        '    <div class="uf-section">',
        f'        <h4 class="uf-title">{escape(section["title"])}</h4>',
        '        <div class="uf-grid">',
    ]
    for field in section["fields"]:
        out.append(field_html(field, uuids, section))
    out += ['        </div>', '    </div>', '']
    return "\n".join(out)


def header(form):
    return "\n".join([
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!--',
        f'     {form["name"]}, digitised from the printed register',
        f'     "{u5_spec.SOURCE_WORKBOOK}", sheet',
        f'     "{u5_spec.SOURCE_SHEET}". Age range {form["age_range"]}.',
        '-->',
        f'<htmlform formUuid="{form["uuid"]}" formName="{form["name"]}" '
        f'formEncounterType="{form["encounter_uuid"]}" '
        f'formVersion="{form["form_version"]}">',
        '    <style type="text/css">',
        CSS.rstrip("\n"),
        '    </style>',
        '',
        f'    <h3>{escape(form["title"])}</h3>',
        '',
    ])


def footer():
    return "\n".join([
        '    <div id="buttons">',
        '        <submit submitClass="confirm right" submitCode="general.save"/>',
        '        <button type="button" class="cancel">'
        '<uimessage code="general.cancel"/></button>',
        '    </div>',
        '</htmlform>',
        '',
    ])


def build(uuids):
    form = u5_spec.FORM
    parts = [header(form)]
    for section in u5_spec.SECTIONS:
        parts.append(section_html(section, uuids))
    parts.append(footer().rstrip("\n"))
    return "\n".join(parts) + "\n"


def referenced(xml):
    refs = set(re.findall(r'conceptId="([0-9a-f-]{36})"', xml))
    refs |= {u for group in re.findall(r'answerConceptIds="([^"]+)"', xml)
             for u in group.split(",")}
    return refs


def main():
    uuids = load_uuids()
    xml = build(uuids)

    refs = referenced(xml)
    known = {ids["uuid"] for ids in uuids.values()}
    missing = sorted(refs - known)
    if missing:
        for value in missing:
            name = next((n for n, i in uuids.items() if i["uuid"] == value), value)
            print(f"MISSING: {name} ({value})")
        raise SystemExit(f"{len(missing)} concepts in the form have no pinned uuid")

    with open(OUT, "w", encoding="utf-8") as fh:
        fh.write(xml)

    print(f"{u5_spec.FORM['name']}")
    print(f"  -> {os.path.relpath(OUT, HERE)}")
    print(f"  bytes              : {len(xml)}")
    print(f"  sections           : {len(u5_spec.SECTIONS)}")
    print(f"  obs                : {xml.count('<obs ')}")
    print(f"  distinct concepts  : {len(refs)}  missing: {len(missing)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())