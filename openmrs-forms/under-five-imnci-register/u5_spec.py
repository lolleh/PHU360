#!/usr/bin/env python3
"""Field specification for the UNDER FIVE IMNCI REGISTER (2 months - 5 years).

Digitised from the printed register
`source/under5-register-imnci.xlsx`, sheet "2 Month-5 Years Register", which
`parse_source.py` reads into `source/parsed.json`. Every section here names the
printed columns it came from, so `verify_source_coverage.py` can prove that each
printed column was either captured, deliberately taken from the patient record,
or recorded as an ambiguity in `SOURCE_ANOMALIES.json`.

What the register prints and what it means are kept apart on purpose. The
workbook says "Screened for disability (Y/N)" and "Drug(s) Provided (No.)"; this
module is where the decision is recorded that the first is a census question
needing an explicit No and the second asks for a quantity, with the reasoning.
The workbook's own wording is never edited to make the form tidier.

A concept name already in the dictionary is stated, never derived: the register
is live, its observations are recorded against concept UUIDs, and a concept
renamed by a later generation would orphan them. `register_concepts.py` is what
turns these names into the UUIDs, and it refuses to move one that already has
data behind it.

Field types:

    boolean    a tick box - the register prints a box to tick, and only the
               positive answer is possible
    yn / yna   a Yes/No (or Yes/No/Not applicable) question the register asks
               every child, answered from the register's own answer concepts
    coded      one question with the register's own list of answers
    number     a measurement or a count
    text       free text
    datetime   a date, with a time when the register prints one
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PARSED = os.path.join(HERE, "source", "parsed.json")
ANOMALIES = os.path.join(HERE, "SOURCE_ANOMALIES.json")

SOURCE_WORKBOOK = "Under5 Register-IMNCI Final Print Review.xlsx"
SOURCE_SHEET = "2 Month-5 Years Register"

# The register is already published and filling encounters, so the form's own
# identity is fixed. Changing either of these orphans the encounters recorded
# against them: the dashboard addresses the form by formUuid, and every encounter
# carries the encounter type id.
FORM = dict(
    key="under_five_imnci",
    name="Under Five IMNCI Register",
    title="Under Five IMNCI Register (age 2 months - 5 years)",
    uuid="b359d6b5-cdc2-5244-b6b2-bf78ae2891d0",
    encounter_name="Under Five IMNCI",
    encounter_uuid="efbac517-5a27-58b6-acf1-61a8f23b4586",
    form_version="1.1",
    age_range="2 months to 5 years",
)

# The register's own Yes / No / Not applicable answers, shared by every census
# question in it. They are not the dictionary's "Yes": a register that asks its
# own question with its own printed answer marks should record those answers, and
# the reporting dashboard resolves them by the same names.
YES = dict(label="Yes", concept="U5 Yes")
NO = dict(label="No", concept="U5 No")
NOT_APPLICABLE = dict(label="Not applicable", concept="U5 Not applicable")
YES_NO = [YES, NO]
YES_NO_NA = [YES, NO, NOT_APPLICABLE]


def boolean(label, concept, **kw):
    return dict(label=label, concept=concept, **dict(type="boolean", **kw))


def number(label, concept, **kw):
    return dict(label=label, concept=concept, **dict(type="number", **kw))


def text(label, concept, **kw):
    return dict(label=label, concept=concept, **dict(type="text", **kw))


def coded(label, concept, options, **kw):
    return dict(label=label, concept=concept, options=options,
                **dict(type="coded", **kw))


def yn(label, concept, **kw):
    return dict(label=label, concept=concept, options=YES_NO,
                **dict(type="yn", **kw))


def yna(label, concept, **kw):
    return dict(label=label, concept=concept, options=YES_NO_NA,
                **dict(type="yna", **kw))


def note(label):
    """An explanatory line that records no observation."""
    return dict(label=label, type="note")


SECTIONS = [
    dict(
        key="visit",
        cols="uf-2col",
        title="Visit",
        source=["A", "O"],
        fields=[
            dict(label="Date", type="encounterDate"),
            dict(label="Provider", type="encounterProvider"),
            coded("Type of visit", "U5 Type of visit", cls="Misc", options=[
                dict(label="New", concept="U5 New"),
                dict(label="Follow up", concept="U5 Follow up"),
            ]),
        ],
    ),
    dict(
        key="child",
        title="Child",
        source=["B", "C", "D"],
        fields=[
            note("Name, date of birth and sex are taken from the patient record "
                 "selected on the patient dashboard. The register's own age in "
                 "months and chiefdom or village are captured below."),
            number("Age (months)", "U5 Age in months", cls="Misc",
                   note="The register spans 2 months to 5 years and its "
                        "MUAC and WAZ bands are read off age in months, which "
                        "the patient header only carries in years."),
            text("Address / Chiefdom / Village", "U5 Address chiefdom or village",
                 cls="Misc"),
        ],
    ),
    dict(
        key="disability",
        title="Disability screening",
        source=["E", "F", "G"],
        fields=[
            yn("Screened for disability", "U5 Screened for disability", cls="Misc"),
            yna("Disability", "U5 Has disability", cls="Misc"),
            coded("Disability type", "U5 Type of disability", cls="Misc", options=[
                dict(label="Physical", concept="U5 Physical"),
                dict(label="Visual", concept="U5 Visual"),
                dict(label="Speech", concept="U5 Speech"),
                dict(label="Hearing", concept="U5 Hearing"),
                dict(label="Learning", concept="U5 Learning"),
                dict(label="Multiple", concept="U5 Multiple"),
            ]),
        ],
    ),
    dict(
        key="anthropometry",
        cols="uf-2col",
        title="Anthropometry",
        source=["H", "I", "J", "K"],
        fields=[
            number("Temperature (C)", "U5 Temperature (C)"),
            number("Weight (kg)", "U5 Weight (kg)"),
            number("Height / length (cm)", "U5 Height or length (cm)"),
            number("MUAC (cm)", "U5 MUAC (cm)"),
            number("WHZ", "U5 WHZ"),
            number("WAZ", "U5 WAZ"),
        ],
    ),
    dict(
        key="feeding",
        title="Feeding and presenting problem",
        source=["L", "M", "N", "Z"],
        fields=[
            yna("Exclusive breastfeeding (child < 6 months)",
                "U5 Exclusive breastfeeding under 6 months"),
            yna("Continued breastfeeding (child 6-23 months)",
                "U5 Continued breastfeeding 6 to 23 months"),
            text("Presenting complaint (the child's problems)",
                 "U5 Presenting complaint", style="textarea", cols="uf-full"),
            text("Other problems", "U5 Other problems", style="textarea", cols="uf-full"),
        ],
    ),
    dict(
        key="danger_signs",
        title="General danger signs (check all)",
        source=["P"],
        fields=[
            boolean("Unable to drink or breastfeed", "U5 Unable to drink or breastfeed"),
            boolean("Vomiting everything", "U5 Vomiting everything"),
            boolean("History of convulsion", "U5 History of convulsion"),
            boolean("Convulsing now", "U5 Convulsing now"),
            boolean("Lethargic or unconscious", "U5 Lethargic or unconscious"),
        ],
    ),
    dict(
        key="cough",
        title="Cough or difficult breathing",
        source=["Q"],
        fields=[
            boolean("Cough or difficult breathing", "U5 Cough or difficult breathing"),
            number("Duration of cough / breathing problem (days)",
                   "U5 Duration of cough or breathing problem (days)"),
            number("Breathing rate (/minute)", "U5 Breathing rate (per minute)"),
            boolean("Fast breathing", "U5 Fast breathing"),
            boolean("Chest indrawing", "U5 Chest indrawing"),
            boolean("Stridor", "U5 Stridor"),
            number("Oxygen saturation (%)", "U5 Oxygen saturation (percent)"),
        ],
    ),
    dict(
        key="diarrhoea",
        title="Diarrhoea",
        source=["R"],
        fields=[
            boolean("Diarrhoea", "U5 Diarrhoea"),
            number("Duration of diarrhoea (days)", "U5 Duration of diarrhoea (days)"),
            boolean("Blood in stool", "U5 Blood in stool"),
            boolean("Restless / irritable", "U5 Restless or irritable"),
            boolean("Sunken eyes", "U5 Sunken eyes"),
            boolean("Weak to drink", "U5 Weak to drink"),
            boolean("Eager / thirsty", "U5 Eager or thirsty"),
            boolean("Skin pinch going back", "U5 Skin pinch going back"),
        ],
    ),
    dict(
        key="fever",
        title="Fever",
        source=["S"],
        fields=[
            boolean("Fever", "U5 Fever"),
            number("Duration of fever (days)", "U5 Duration of fever (days)"),
            boolean("Measles in the last three months",
                    "U5 Measles in the last three months"),
            boolean("Stiff neck / bulging fontanelle",
                    "U5 Stiff neck or bulging fontanelle"),
            boolean("Generalised rash of measles", "U5 Generalised rash of measles"),
            boolean("Cough / runny nose / red eyes",
                    "U5 Cough or runny nose or red eyes"),
            boolean("Mouth ulcer / discharging eyes / corneal clouding",
                    "U5 Mouth ulcer or discharging eyes or corneal clouding"),
        ],
    ),
    dict(
        key="malaria_tests",
        title="Malaria tests",
        source=["S", "AC", "AD"],
        fields=[
            boolean("RDT: not done", "U5 Malaria RDT not done"),
            boolean("RDT: negative", "U5 Malaria RDT negative"),
            boolean("RDT: positive", "U5 Malaria RDT positive"),
            boolean("Microscopy: not done", "U5 Blood film not done"),
            boolean("Microscopy: negative", "U5 Blood film negative"),
            boolean("Microscopy: positive", "U5 Blood film positive"),
            boolean("Malaria treated within 24 hrs of onset",
                    "U5 Malaria treated within 24 hours of onset"),
            boolean("Malaria treated after 24 hrs of onset",
                    "U5 Malaria treated after 24 hours of onset"),
        ],
    ),
    dict(
        key="ear",
        title="Ear problem",
        source=["T"],
        fields=[
            boolean("Ear problem", "U5 Ear problem"),
            boolean("Ear pain", "U5 Ear pain"),
            number("Duration of ear problem (days)",
                   "U5 Duration of ear problem (days)"),
            boolean("Ear discharge", "U5 Ear discharge"),
        ],
    ),
    dict(
        key="malnutrition",
        title="Malnutrition or anaemia (check all)",
        source=["U"],
        fields=[
            boolean("Oedema both feet (< 6 months)",
                    "U5 Pitting oedema both feet under 6 months"),
            boolean("Oedema both feet (>= 6 months)",
                    "U5 Pitting oedema both feet 6 months and over"),
            boolean("Visible severe wasting", "U5 Visible severe wasting"),
            boolean("MUAC < 11.5 cm", "U5 MUAC below 11.5 cm"),
            boolean("MUAC 11.5 - 12.5 cm", "U5 MUAC 11.5 to 12.5 cm"),
            boolean("MUAC >= 12.5 cm", "U5 MUAC 12.5 cm and above"),
            boolean("WHZ < -3", "U5 WHZ below minus 3"),
            boolean("WHZ -3 to -2", "U5 WHZ between minus 3 and minus 2"),
            boolean("WHZ >= -2", "U5 WHZ minus 2 and above"),
            boolean("Pneumonia / fever", "U5 Pneumonia or fever"),
            boolean("Watery diarrhoea / dysentery", "U5 Watery diarrhoea or dysentery"),
            boolean("Appetite test: pass", "U5 Appetite test passed"),
            boolean("Appetite test: fail", "U5 Appetite test failed"),
            boolean("Palmar pallor", "U5 Palmar pallor"),
            boolean("Severe palmar pallor", "U5 Severe palmar pallor"),
        ],
    ),
    dict(
        key="hiv",
        title="HIV / AIDS (RDT)",
        source=["V"],
        fields=[
            boolean("Mother: positive", "U5 Mother HIV RDT positive"),
            boolean("Mother: negative", "U5 Mother HIV RDT negative"),
            boolean("Mother: unknown", "U5 Mother HIV RDT unknown"),
            boolean("Child: positive", "U5 Child HIV RDT positive"),
            boolean("Child: negative", "U5 Child HIV RDT negative"),
            boolean("Child: unknown", "U5 Child HIV RDT unknown"),
        ],
    ),
    dict(
        key="immunisation",
        cols="uf-2col",
        title="Immunisation status (2 years and under)",
        source=["W"],
        fields=[
            boolean("Completed", "U5 Immunisation completed"),
            boolean("Up to date", "U5 Immunisation up to date"),
            boolean("Not up to date", "U5 Immunisation not up to date"),
            boolean("Not started", "U5 Immunisation not started"),
        ],
    ),
    dict(
        key="vitamin_a",
        cols="uf-2col",
        title="Vitamin A (6 months and over) and Albendazole (2 years and over)",
        source=["Y"],
        fields=[
            yn("Vitamin A in last 6 months (6-11m)",
               "U5 Vitamin A received in last 6 months age 6 to 11 months"),
            yn("Vitamin A in last 6 months (12-59m)",
               "U5 Vitamin A received in last 6 months age 12 to 59 months"),
            yn("Albendazole in last 6 months (12-23m)",
               "U5 Albendazole received in last 6 months age 12 to 23 months"),
            yn("Albendazole in last 6 months (24-59m)",
               "U5 Albendazole received in last 6 months age 24 to 59 months"),
        ],
    ),
    dict(
        key="classification",
        title="Classification, treatment and outcome",
        source=["AA", "AB", "AE", "AF", "AG", "AH", "AI"],
        fields=[
            coded("Disease classification", "U5 Primary classification", options=[
                dict(label="Severe acute malnutrition",
                     concept="U5 Classified as severe acute malnutrition"),
                dict(label="Pneumonia or severe respiratory illness",
                     concept="U5 Classified as pneumonia or severe respiratory illness"),
                dict(label="Diarrhoea", concept="U5 Classified as diarrhoea"),
                dict(label="Malaria", concept="U5 Classified as malaria"),
                dict(label="Fever", concept="U5 Classified as fever"),
                dict(label="Ear problem", concept="U5 Classified as ear problem"),
                dict(label="Measles", concept="U5 Classified as measles"),
                dict(label="Other", concept="U5 Classified as other"),
            ]),
            text("Treatment given (medicine)", "U5 Treatment given", style="textarea",
                 cols="uf-full"),
            boolean("Counsel mother: on feeding", "U5 Counselled on feeding"),
            boolean("Counsel mother: on fluids", "U5 Counselled on fluids"),
            boolean("Counsel mother: on when to return",
                    "U5 Counselled on when to return"),
            boolean("Referred", "U5 Referred to health facility"),
            text("If referred, name of health facility",
                 "U5 Name of referral health facility", style="textarea", cls="Misc"),
            text("If referred, pre-referral treatment",
                 "U5 Pre-referral treatment provided", style="textarea"),
            dict(label="Follow up date", concept="U5 Follow up date",
                 type="datetime"),
            coded("Outcome", "U5 Outcome", cls="Misc", options=[
                dict(label="Improved", concept="U5 Improved"),
                dict(label="The same", concept="U5 Unchanged"),
                dict(label="Worse", concept="U5 Worse"),
                dict(label="Died", concept="U5 Died"),
            ]),
        ],
    ),
    dict(
        key="drugs",
        title="Drugs provided (No.)",
        source=["AJ", "AK", "AL", "AM", "AN", "AO", "AP", "AQ", "AR", "AS", "AT"],
        fields=[
            number("Albendazole 400mg", "U5 Albendazole 400mg"),
            number("Amoxicillin 250mg", "U5 Amoxicillin 250mg"),
            number("ACT (AL-6 / ASAQ-3, 2-11 mos)",
                   "U5 ACT AL-6 or ASAQ 3 for 2 to 11 months"),
            number("ACT (AL-12 / ASAQ-3, 1-5 yrs)",
                   "U5 ACT AL-12 or ASAQ 3 for 1 to 5 years"),
            number("ORS sachet", "U5 ORS sachet"),
            number("Paracetamol 100mg", "U5 Paracetamol 100mg"),
            number("Zinc sulphate 20mg", "U5 Zinc sulphate 20mg"),
            number("Rectal artesunate suppository", "U5 Rectal artesunate suppository"),
            number("Artesunate 60mg/ml inj, 1ml vial",
                   "U5 Artesunate 60mg per ml injection"),
            number("Oxygen therapy", "U5 Oxygen therapy"),
            text("Other drug (specify)", "U5 Other drug", style="textarea",
                 cols="uf-full"),
        ],
    ),
    dict(
        key="remarks",
        title="Remarks",
        source=["AU"],
        fields=[text("Remarks", "U5 Remarks", style="textarea", cls="Misc",
                    cols="uf-full")],
    ),
]

# ---------------------------------------------------------------------------
# The banner every register prints on its first two rows. It describes the
# encounter - which facility, which reporting month - rather than the child, and
# OpenMRS already records both: the location on the encounter and the reporting
# month as the encounter's own date. Capturing it per row would let the printed
# banner and the encounter disagree, and there is no column left for it besides
# the register's header.
BANNER_COMPONENTS = [
    "district", "facility name", "facility type", "chiefdom/zone", "year", "month",
    "in facility", "outreach",
]

# The register's banner names ownership nowhere, unlike the Above Five register,
# so there is nothing to map it to and nothing to omit.
BANNER_NOT_PRINTED = ["ownership"]

# Read from the patient record rather than captured, so a user does not look for
# it on the form and so the printed register cannot contradict the patient header.
FROM_PATIENT_RECORD = {
    "B": ["name"],
    "D": ["sex"],
}