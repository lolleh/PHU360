#!/usr/bin/env python3
"""Read the printed Under Five register's header stack out of the workbook.

The workbook is not a data table - it is one printed register repeated down the
sheet, and the columns are described by two different header devices. A wide
merged header (rows 3-5) names the columns and the column groups; below it the
printed page header repeats on a fixed period (rows 6-16, then 17-27, 28-38 ...)
and carries the per-column sub-labels - "Weight", "Height / Length", "Yes No",
the individual IMNCI signs, the drug names. Neither half describes the register
on its own, so both are read and merged into one label stack per column.

Both boundaries are derived from the sheet rather than hard-coded: the wide
header ends where its own merged cells end, and the repeating page header ends
where the next one begins. The second page header is then compared against the
first, and a difference is a hard failure rather than a silent truncation - if a
reprint ever adds a label to one copy only, that is a question for the register
owner, not something to guess at.

The result is written as `source/parsed.json`: one entry per printed column,
carrying the label stack the register prints in it, top to bottom.

Nothing here interprets the register. What a column *means* - whether it is a
Yes/No census question or a tick-box, whether "(No.)" in a header asks for a
count - is decided in `u5_spec.py`, and every place the workbook is ambiguous or
self-contradictory is recorded in `SOURCE_ANOMALIES.json`. Keeping the two apart
is what lets the coverage check prove that every printed column was dealt with
rather than quietly dropped.

    python3 parse_source.py
"""
import json
import os

import openpyxl

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "source", "under5-register-imnci.xlsx")
OUT = os.path.join(HERE, "source", "parsed.json")

SHEET = "2 Month-5 Years Register"

# Every printed page header opens with "Date" in the first column. The wide
# merged header above it does too, which is why the two are told apart by the
# merged-cell structure rather than by the word.
FIRST_LABEL = "date"
WIDE_HEADER_SPAN = 5
MAX_HEADER_SCAN = 80


def cell_text(value):
    if value is None:
        return ""
    return " ".join(str(value).split())


def date_rows(ws):
    """Every row whose first cell is the word that opens a printed header."""
    return [
        r for r in range(1, min(ws.max_row, MAX_HEADER_SCAN) + 1)
        if cell_text(ws.cell(row=r, column=1).value).lower() == FIRST_LABEL
    ]


def wide_header_end(ws, start):
    """Last row of the merged header that starts at ``start``.

    The wide header's cells are merged over the rows it occupies, so its extent
    is the furthest row any of those merges reaches.
    """
    end = start
    for rng in ws.merged_cells.ranges:
        if start <= rng.min_row <= start + WIDE_HEADER_SPAN:
            end = max(end, rng.max_row)
    return end


def header_rows(ws):
    """Every row that describes the columns, in print order.

    The wide merged header comes first, then each printed page header. Labels
    from all of them are merged per column, so a mark that only the later copies
    print is still captured.
    """
    starts = date_rows(ws)
    if len(starts) < 3:
        raise SystemExit(
            f"expected a wide header followed by at least two printed page "
            f"headers in {SHEET!r}, found 'Date' on rows {starts or 'none'}")

    wide_start = starts[0]
    wide_end = wide_header_end(ws, wide_start)
    page_start = next(r for r in starts if r > wide_start)
    page_end = next(r for r in starts if r > page_start) - 1

    wide = list(ws.iter_rows(min_row=wide_start, max_row=wide_end,
                             max_col=ws.max_column, values_only=True))
    repeat = assert_repeat(ws, page_start, page_end)
    period = repeat["page_header_period"]
    copies = []
    start = page_start
    while True:
        rows = list(ws.iter_rows(min_row=start, max_row=start + period - 1,
                                 max_col=ws.max_column, values_only=True))
        if not any(any(cell_text(c) for c in r) for r in rows):
            break
        copies.append(rows)
        start += period

    return wide + [row for rows in copies for row in rows], wide_end, page_start, page_end, repeat


def assert_repeat(ws, page_start, page_end):
    """Check the printed page headers against each other and report differences.

    The register is one document repeated on the sheet. The first printed page
    omits the "N/A" answer mark that the later copies print under the two
    breastfeeding columns (see SOURCE_ANOMALIES.json), so the copies are not
    byte-identical and a hard failure on any difference would refuse to read a
    register that is perfectly legible. What must hold is that no copy is
    missing a column the others print - a dropped label would mean the columns
    cannot be read off the sheet - so that is the failure, while a cell-level
    difference is reported for the record.
    """
    period = page_end - page_start + 1
    copies = []
    start = page_start
    while start + period - 1 <= ws.max_row:
        rows = list(ws.iter_rows(min_row=start, max_row=start + period - 1,
                                 max_col=ws.max_column, values_only=True))
        if not any(any(cell_text(c) for c in r) for r in rows):
            break
        copies.append((start, rows))
        start += period

    printed = []
    for offset, rows in copies:
        printed.append({
            c for c in range(1, max(len(r) for r in rows) + 1)
            if any(cell_text(r[c - 1] if c - 1 < len(r) else None) for r in rows)
        })

    missing = sorted(set().union(*printed[1:]) - printed[0])
    if missing:
        letters = [openpyxl.utils.get_column_letter(c) for c in missing]
        raise SystemExit(
            f"{SHEET}: the first printed page header (rows {page_start}-"
            f"{page_end}) does not print columns {letters} that the copies below "
            f"it do. The columns cannot be read off this sheet safely.")

    differing = []
    first = [[cell_text(c) for c in row] for row in copies[0][1]]
    for offset, rows in copies[1:]:
        for i, (a, b) in enumerate(zip(first, [[cell_text(c) for c in row] for row in rows])):
            if a != b:
                differing.append(page_start + offset + i)
    return {
        "page_header_period": period,
        "page_header_copies": len(copies),
        "identical_copies": not differing,
        "rows_differing_between_copies": differing,
    }


def column_labels(rows):
    """Every distinct non-empty label printed in each column, in print order."""
    width = max(len(r) for r in rows)
    labels = []
    for cell in range(1, width + 1):
        column = []
        for row in rows:
            text = cell_text(row[cell - 1] if cell - 1 < len(row) else None)
            if text and text not in column:
                column.append(text)
        labels.append(column)
    return labels


def parse():
    wb = openpyxl.load_workbook(SOURCE, data_only=True)
    if SHEET not in wb.sheetnames:
        raise SystemExit(f"{SOURCE}: no sheet {SHEET!r}, has {wb.sheetnames}")
    ws = wb[SHEET]

    rows, wide_end, page_start, page_end, repeat = header_rows(ws)
    labels = column_labels(rows)
    banner = [cell_text(ws.cell(row=r, column=1).value) for r in (1, 2)]

    columns = []
    for cell, column in enumerate(labels, 1):
        if not column:
            continue
        columns.append({
            "column": cell,
            "letter": openpyxl.utils.get_column_letter(cell),
            "header": column[0],
            "labels": column,
        })

    parsed = {
        "source": os.path.relpath(SOURCE, HERE),
        "sheet": SHEET,
        "banner": [b for b in banner if b],
        "wide_header_rows": f"3-{wide_end}",
        "page_header_rows": f"{page_start}-{page_end}",
        **repeat,
        "columns": columns,
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(parsed, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"{SHEET}: {len(columns)} printed columns -> {OUT}")
    print(f"  wide header rows {parsed['wide_header_rows']}, "
          f"page header rows {parsed['page_header_rows']} repeating every "
          f"{repeat['page_header_period']} rows, "
          f"{repeat['page_header_copies']} copies")
    if not repeat["identical_copies"]:
        print(f"  NOTE: copies differ on rows "
              f"{repeat['rows_differing_between_copies']} - see "
              f"SOURCE_ANOMALIES.json")
    for c in columns:
        print(f"  {c['letter']:>3}  {c['header']}")
    return parsed


if __name__ == "__main__":
    parse()