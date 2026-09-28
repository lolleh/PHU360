#!/usr/bin/env python3
"""Extract the register's field structure from the source workbook.

The four paper tabs are laid out as code | label | tick-boxes grids whose column
geometry differs per tab, so each tab gets its own reader below. The output is a
plain JSON structure that the *_spec.py files mirror; it exists so the
transcription can be reviewed against the workbook instead of being taken on
trust from hand-written Python.

    python3 parse_source.py            # writes source/parsed.json
"""
import json
import os
import re

import openpyxl
from openpyxl.utils import get_column_letter

HERE = os.path.dirname(os.path.abspath(__file__))
BOOK = os.path.join(HERE, "source", "mother-and-neonate-register.xlsx")
OUT = os.path.join(HERE, "source", "parsed.json")

FRONT = "Mother & Neonate (Front)"
BACK = "Mother & Neonate (Back)"
DELIVERY = "Delivery Register"
FAMILY = "Family Planning"

# A tick-box is printed as "Label o" (letter o) in the source. Anything ending in
# that suffix is a choice, not a free-text value.
TICK = re.compile(r"^(?P<label>.*?)\s*o$")

# Inline value prompts such as "Temp (C):" or "Actual BP:" are free-text entry
# points, not tick-boxes, so they are collected as companion fields instead.
PROMPT = re.compile(r"^(?P<label>.+?):\s*$")

# The Back tab's Yes/No columns print bare "Y"/"N" with no tick glyph, unlike the
# Front tab's "Yes o". Anything else without a tick glyph is a format hint
# (e.g. "sys/dia" next to "Actual BP:") and is kept out of the option list.
BARE = {"y", "n", "yes", "no"}


def clean(value):
    if value is None:
        return ""
    return " ".join(str(value).split()).strip()


def cell(ws, row, col):
    return clean(ws.cell(row, col).value)


def choices(ws, row, first_col, last_col):
    """Tick-boxes printed to the right of a label on the same row.

    Inline "Prompt:" cells become companion free-text fields, and any other
    unrecognised cell is preserved as a hint rather than dropped.
    """
    options, prompts, hints = [], [], []
    for col in range(first_col, last_col + 1):
        text = cell(ws, row, col)
        if not text:
            continue
        p = PROMPT.match(text)
        if p:
            prompts.append(p.group("label").strip())
            continue
        m = TICK.match(text)
        if m:
            options.append(m.group("label").strip())
        elif text.lower() in BARE:
            options.append(text)
        else:
            hints.append(text)
    return options, prompts, hints


def field_type(options, prompts):
    if options and is_yes_no(options):
        return "yn"
    if options:
        return "choice"
    return "text"


# The postnatal blocks are answered through a single Y/N grid whose tick header
# sits above the block (row 6) rather than on each field row, so the tick-boxes
# are not on the field rows at all. Field type is therefore read from the label:
# a trailing "?" is a yes/no observation, and the remaining labels name a value
# to record instead. Every inference here is listed in SOURCE_ANOMALIES.json.
PNC_ENTRY_TYPES = {
    "PR1": "yn", "PR2": "date", "PR3": "text", "PR4": "number", "PR5": "number",
    "PR16": "date",
    "NN1": "yn", "NN2": "date", "NN3": "text", "NN4": "yn", "NN6": "number",
    "NN8": "number", "NN9": "date", "NN14": "text", "NN16": "text", "NN26": "date",
}


def pnc_type(code, label):
    if code in PNC_ENTRY_TYPES:
        return PNC_ENTRY_TYPES[code]
    if label.endswith("?"):
        return "yn"
    return "text"


def is_yes_no(options):
    norm = {o.strip().lower() for o in options}
    return bool(options) and norm <= {"y", "n", "yes", "no"}


# --------------------------------------------------------------------------
# Back tab: MOTHER block (D29-D48), MOTHER'S Postnatal Care (PR1-PR16),
# BABY'S Postnatal Care (NN1-NN26).
# --------------------------------------------------------------------------
def read_back(ws):
    mother = []
    for row in range(4, 26):
        code = cell(ws, row, 1)
        label = cell(ws, row, 2)
        if not code.startswith("D") or not label:
            continue
        options, prompts, hints = choices(ws, row, 4, 9)
        mother.append(
            {"code": code, "label": label, "type": field_type(options, prompts),
             "options": options,
             "prompts": [{"label": p, "type": "text"} for p in prompts],
             "hints": hints}
        )

    def coded(col_code, col_label, first, last, opts_from, opts_to):
        out = []
        for row in range(first, last + 1):
            code = cell(ws, row, col_code)
            label = cell(ws, row, col_label)
            if not code or not label:
                continue
            options, prompts, hints = choices(ws, row, opts_from, opts_to)
            kind = pnc_type(code, label) if options is not None and not options else field_type(options, prompts)
            out.append(
                {"code": code, "label": label, "type": kind,
                 "options": options,
                 "prompts": [{"label": p, "type": "text"} for p in prompts],
                 "hints": hints}
            )
        return out

    return {
        "mother": mother,
        "postnatal_mother": coded(17, 18, 7, 22, 19, 22),
        "postnatal_baby": coded(17, 18, 27, 52, 19, 19),
        "visit_windows": [cell(ws, 5, c) for c in (19, 21, 23) if cell(ws, 5, c)],
    }


# --------------------------------------------------------------------------
# Family Planning: header rows 3 (label) + 4/5 (options), no code column.
#
# Row 4 holds a group's tick-boxes, but one row-4 label ("Post Partum Family
# Planning") is itself expanded by row 5 cells sitting to its right. Flattening
# that into the parent group would make the three post-partum timings look like
# Client Type values, so the nesting is recorded as a subgroup instead.
# --------------------------------------------------------------------------
def read_family(ws):
    groups = []
    current = None
    consumed_to = 0
    for col in range(1, ws.max_column + 1):
        if col <= consumed_to:
            continue
        label = cell(ws, 3, col)
        mid = cell(ws, 4, col)
        low = cell(ws, 5, col)
        if mid in ("(please tick)",):
            mid = ""
        if low in ("(please tick)",):
            low = ""

        if label:
            current = {"label": label, "column": get_column_letter(col), "options": []}
            if mid:
                current["options"].append(mid)
            if low:
                current["options"].append(low)
            groups.append(current)
            continue

        if not mid and not low:
            continue
        if current is None:
            continue

        if mid:
            # A row-4 label with no row-3 parent: either another tick of the
            # current group, or a subgroup expanded by row-5 cells. The first
            # child can sit in the same column as its parent label, so the whole
            # row-5 run is collected rather than testing this column alone.
            children = []
            end = col - 1
            for ahead in range(col, ws.max_column + 1):
                if ahead != col and (cell(ws, 3, ahead) or cell(ws, 4, ahead)):
                    break
                value = cell(ws, 5, ahead)
                if value and value != "(please tick)":
                    children.append(value)
                    end = ahead
            if children:
                current.setdefault("subgroups", []).append(
                    {"label": mid, "column": get_column_letter(col),
                     "options": children})
                consumed_to = end
            else:
                current["options"].append(mid)
        else:
            current["options"].append(low)
    return groups


# --------------------------------------------------------------------------
# Delivery Register: label row 3, option rows 4/5, no code column.
# --------------------------------------------------------------------------
def read_delivery(ws):
    groups = []
    for col in range(1, ws.max_column + 1):
        label = cell(ws, 3, col)
        sub = [cell(ws, 4, col), cell(ws, 5, col)]
        sub = [s for s in sub if s]
        if not label and not sub:
            continue
        if label:
            groups.append({"label": label, "column": get_column_letter(col), "options": list(sub)})
        elif groups:
            groups[-1]["options"].extend(sub)
    return groups


def main():
    wb = openpyxl.load_workbook(BOOK, data_only=True)
    parsed = {
        BACK: read_back(wb[BACK]),
        FAMILY: read_family(wb[FAMILY]),
        DELIVERY: read_delivery(wb[DELIVERY]),
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(parsed, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print(f"wrote {OUT}")
    print(f"  mother            : {len(parsed[BACK]['mother'])}")
    print(f"  postnatal mother  : {len(parsed[BACK]['postnatal_mother'])}")
    print(f"  postnatal baby    : {len(parsed[BACK]['postnatal_baby'])}")
    print(f"  visit windows     : {parsed[BACK]['visit_windows']}")
    print(f"  family planning   : {len(parsed[FAMILY])}")
    print(f"  delivery register : {len(parsed[DELIVERY])}")


if __name__ == "__main__":
    main()
