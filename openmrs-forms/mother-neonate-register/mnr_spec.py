#!/usr/bin/env python3
"""
Field specification for the MOTHER AND NEONATE HEALTH REGISTER.

Transcribed from the source register:
  https://docs.google.com/spreadsheets/d/1qv9Uuw5a3cXYraohneogu4KL8RMM14vj

Section codes follow the source sheet exactly (H*, PP*, D*). Where the source
sheet is internally inconsistent, the resolution is recorded in ANOMALIES below
and echoed in the generated form so the correction is auditable.
"""

# ---------------------------------------------------------------------------
# Anomalies found in the source spreadsheet and how they were resolved
# ---------------------------------------------------------------------------
ANOMALIES = [
    dict(
        where="Past Pregnancy History, row 15",
        problem="H8 has no label at all - only the word 'Number' in the value column.",
        resolution=(
            "Kept as a numeric field labelled 'H8 (unlabelled in source register)'. "
            "A placeholder label is preferable to dropping the column, because the "
            "source sheet clearly allocates a cell to it."
        ),
    ),
    dict(
        where="Present Pregnancy, row 7",
        problem=(
            "'LMP' and 'EDD' appear above the numbering and carry no PP code, so the "
            "PP series starts at PP1 = 'Gestational age (weeks)'. The sheet implies "
            "LMP/EDD should be PP1/PP2."
        ),
        resolution=(
            "PP numbering left exactly as in the source (PP1 = Gestational age). "
            "LMP and EDD are captured as separate, separately-labelled date fields so "
            "no existing PP code is silently shifted."
        ),
    ),
    dict(
        where="Baby block, rows 39-42",
        problem=(
            "'D29' is used twice: once for 'Neverapine syrup administered to baby' "
            "and again for 'Partograph/Labour Care Guide was used'. That makes 20 "
            "items in a 19-slot D13-D31 range."
        ),
        resolution=(
            "Partograph has Baby 1 and Baby 2 columns in the source, so it belongs to "
            "the baby block. The block is renumbered sequentially D13-D32; only "
            "Partograph, Kangaroo Mother Care and Skin to Skin shift (D30/D31/D32). "
            "D13-D29 are unchanged."
        ),
    ),
]

# ---------------------------------------------------------------------------
# Reusable option sets
# ---------------------------------------------------------------------------
# The register is a census: every client is recorded as Yes or No, so the
# Yes/No fields are Coded (not Boolean). A Boolean checkbox can only ever
# record "this happened", which silently loses the explicit No - and the
# Mother and Neonate dashboard needs to count both. The standard OpenMRS
# dictionary concepts are reused rather than duplicated.
YES_NO = ["Yes", "No"]
YES_NO_NA = ["Yes", "No", "N/A"]

STD_YES_UUID = "3cd6f600-26fe-102b-80cb-0017a47871b2"
STD_NO_UUID = "3cd6f86c-26fe-102b-80cb-0017a47871b2"
STD_NA_UUID = "3cd7b72a-26fe-102b-80cb-0017a47871b2"
STD_YES_NO = [STD_YES_UUID, STD_NO_UUID]
STD_YES_NO_NA = [STD_YES_UUID, STD_NO_UUID, STD_NA_UUID]

# The register has a Baby 1 / Baby 2 column pair (the second is for twins).
# Both columns record the same concepts, so each is wrapped in its own obs
# group; without that a report cannot tell a twin's weight from the first
# baby's weight. These are the group concepts (datatype N/A).
BABY_GROUPS = [
    ("baby1", "Baby 1"),
    ("baby2", "Baby 2"),
]


def baby_group_name(i: int) -> str:
    """Concept name for the obs group wrapping baby column ``i`` (1-based)."""
    return f"MNR BABY{i} Baby {i}"

BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"]

DELIVERY_CONDUCTED_BY = [
    "Doctor", "Midwife", "CHO", "CHA", "SACHO", "BSN", "SECHN",
    "MCHA", "TBA", "Other", "SRN", "NS",
]

DELIVERED_AT = ["PHU", "Hospital", "Community"]

DELIVERY_TYPE = ["Normal", "Assisted (vacuum)", "Elective CS", "Emergency CS"]

ANC_STAGES = [str(i) for i in range(1, 9)]

# ---------------------------------------------------------------------------
# Sections
# ---------------------------------------------------------------------------
SECTIONS = [
    dict(
        key="past_pregnancy_history",
        title="Past Pregnancy History",
        fields=[
            dict(code="H1",  label="Sickler", type="yn"),
            dict(code="H2",  label="Age 18-35 yrs", type="yn"),
            dict(code="H3",  label="Height <150 cm or 5 ft", type="yn"),
            dict(code="H4",  label="Gravida", type="number"),
            dict(code="H5",  label="Parity", type="number"),
            dict(code="H6",  label="Number of children Alive", type="number"),
            dict(code="H7",  label="EVD Survivor", type="yn"),
            dict(code="H8",  label="(unlabelled in source register)",
                 type="number", note="Source sheet has no label for H8, only 'Number'."),
            dict(code="H9",  label="Multiple Delivery", type="yn"),
            dict(code="H10", label="Still Birth", type="yn"),
            dict(code="H11", label="Miscarriages", type="yn"),
            dict(code="H12", label="Induced Abortion", type="yn"),
            dict(code="H13", label="Previous C/S", type="yn"),
            dict(code="H14", label="Prolong/Obstructed Labour", type="yn"),
            dict(code="H15", label="APH", type="yn"),
            dict(code="H16", label="PPH", type="yn"),
            dict(code="H17", label="Retained Placenta", type="yn"),
            dict(code="H18", label="Eclamptic FIT", type="yn"),
            dict(code="H19", label="Death of Child within 7 days", type="yn"),
            dict(code="H20", label="Cough above 4 weeks", type="yn"),
            dict(code="H21", label="High Puerperial Fever", type="yn"),
            dict(code="H22", label="Breech Delivery", type="yn"),
            dict(code="H23", label="Ectopic Pregnancy", type="yn"),
            dict(code="H24", label="Diabetes", type="yn"),
        ],
    ),
    dict(
        key="client_blood_group",
        title="Client",
        fields=[
            dict(code="BG", label="Client Blood Group",
                 type="dropdown", options=BLOOD_GROUPS),
        ],
    ),
    dict(
        key="present_pregnancy",
        title="Present Pregnancy (ANC)",
        fields=[
            dict(code="LMP", label="LMP", type="date",
                 note="Unnumbered in the source sheet; captured separately so the "
                      "PP1..PP32 codes are not shifted."),
            dict(code="EDD", label="EDD", type="date",
                 note="Unnumbered in the source sheet; captured separately so the "
                      "PP1..PP32 codes are not shifted."),
            dict(code="PP1",  label="Gestational age (weeks)", type="number"),
            dict(code="PP2",  label="Date of ANC Contact", type="date"),
            dict(code="PP3",  label="Stage of ANC Contact", type="dropdown",
                 options=ANC_STAGES),
            dict(code="PP4",  label="Screen with BP (if 130/90 and above refer)", type="yn"),
            dict(code="PP5",  label="Oedema", type="yn"),
            dict(code="PP6",  label="Pitting Oedema", type="yn"),
            dict(code="PP7",  label="Urine Albumin", type="yn"),
            dict(code="PP8",  label="Disability", type="yn"),
            dict(code="PP9",  label="Unusually thin Patient", type="yn"),
            dict(code="PP10", label="Unusually large Abdomen", type="yn"),
            dict(code="PP11", label="Severe Pallor / Anaemia", type="yn"),
            dict(code="PP12", label="Vaginal Bleeding", type="yn"),
            dict(code="PP13", label="Transverse / Oblique Lie", type="yn"),
            dict(code="PP14", label="Mutiple Pregnancy", type="yn"),
            dict(code="PP15", label="Foetal Movements", type="yn"),
            dict(code="PP16", label="Foetal Heartbeats (Indicate Number)", type="number"),
            dict(code="PP17", label="Iron and Folic Acid", type="yn"),
            dict(code="PP18", label="Multiple Micronutrient Suplement (MMS)", type="yn"),
            dict(code="PP19", label="Intermittent preventive therapy (IPTp) malaria", type="yn"),
            dict(code="PP20", label="Screen with Ultrasound Scan", type="yn"),
            dict(code="PP21", label="Weight (kg)", type="number"),
            dict(code="PP22", label="Height (cm)", type="number"),
            dict(code="PP23", label="Mother's Delivery Kit", type="yn"),
            dict(code="PP24", label="LLINs given at ANC", type="yn"),
            dict(code="PP25", label="Albendazole", type="yn"),
            dict(code="PP26", label="Tested for Syphilis", type="yn"),
            dict(code="PP27", label="Tested for HIV", type="yn"),
            dict(code="PP27DT", label="HIV Test date", type="date"),
            dict(code="PP28", label="HIV results Received", type="ynna"),
            dict(code="PP29", label="Refer for eMTCT", type="ynna"),
            dict(code="PP30", label="ARV Treatment Started", type="ynna"),
            dict(code="PP31", label="Haemoglobin (<12g/dl)", type="ynna"),
            dict(code="PP32", label="Screen for Hepatitis B", type="ynna"),
        ],
    ),
    dict(
        key="labour_delivery",
        title="Labour / Delivery",
        fields=[
            dict(code="D1",  label="Normal Duration (0-12hrs)", type="yn"),
            dict(code="D2",  label="Cephalic Presentation", type="yn"),
            dict(code="D3",  label="Date of Labour onset", type="date"),
            dict(code="D4",  label="Time of Labour onset (HH:MM)", type="text"),
            dict(code="D5",  label="Date of Delivery", type="date"),
            dict(code="D6",  label="Time of Delivery (HH:MM)", type="text"),
            dict(code="D7",  label="Delivery Type", type="dropdown",
                 options=DELIVERY_TYPE),
            dict(code="D8",  label="Bleeding (500mls or more)", type="yn"),
            dict(code="D9",  label="Breech Delivery", type="yn"),
            dict(code="D10", label="Delivery Conducted By", type="dropdown",
                 options=DELIVERY_CONDUCTED_BY),
            dict(code="D11", label="Delivered at", type="dropdown",
                 options=DELIVERED_AT),
            dict(code="D12", label="Mother Survived Delivery", type="yn"),
        ],
    ),
    dict(
        key="baby",
        title="Baby (Neonate)",
        # Rendered twice, once per baby, exactly as the source sheet does.
        repeat=2,
        repeat_label="Baby",
        fields=[
            dict(code="D13", label="Live Birth", type="yn"),
            dict(code="D14", label="(If NO to D13) Macerated still birth", type="yn"),
            dict(code="D15", label="(If NO to D13) Fresh still birth", type="yn"),
            dict(code="D16", label="State of baby - normal?", type="yn"),
            dict(code="D17", label="Gestational age 36wks or less", type="yn"),
            dict(code="D18", label="Multiple birth", type="yn"),
            dict(code="D19", label="Sex of Baby", type="dropdown",
                 options=["Male", "Female"]),
            dict(code="D20", label="Apgar score at 5 min after birth", type="number"),
            dict(code="D21", label="Birth weight under 2.5kg", type="yn"),
            dict(code="D22", label="Actual weight (kg)", type="number"),
            dict(code="D23", label="Delayed crying", type="yn"),
            dict(code="D24", label="Difficult Breathing", type="yn"),
            dict(code="D25", label="If YES at D23/D24 new born resuscitated?", type="yn"),
            dict(code="D26", label="Live born breastfed within 1 hrs.", type="yn"),
            dict(code="D27", label="Baby alive after 24 hrs?", type="yn"),
            dict(code="D28", label="Baby referred to Doctor", type="yn"),
            dict(code="D29", label="Neverapine syrup administered to baby - if HIV exposed",
                 type="yn"),
            dict(code="D30", label="Partograph/Labour Care Guide was used", type="yn",
                 note="Labelled D29 twice in the source sheet; renumbered to keep the "
                      "baby block sequential."),
            dict(code="D31", label="Initiation to Kangaroo Mother Care", type="yn"),
            dict(code="D32", label="Immediate Skin to Skin Care", type="yn"),
        ],
    ),
]

# Facility / period fields. These describe the register as a whole (facility +
# month), not the individual client, so they are stored once per encounter and
# repeated on the printed register.
FACILITY_FIELDS = [
    dict(code="INFAC", label="In Facility", type="yn"),
    dict(code="OUTRCH", label="Outreach", type="yn"),
]


if __name__ == "__main__":
    total = sum(len(s["fields"]) * s.get("repeat", 1) for s in SECTIONS)
    dd = sum(len([f for f in s["fields"] if f["type"] == "dropdown"]) for s in SECTIONS)
    print(f"sections      : {len(SECTIONS)}")
    print(f"data points   : {total} obs fields (baby block x2)")
    print(f"dropdowns     : {dd} coded selects")
    print(f"anomalies     : {len(ANOMALIES)}")
    for s in SECTIONS:
        n = len(s["fields"]) * s.get("repeat", 1)
        print(f"  {s['title']:<28} {n:>3} fields")
