/*
 * DHMT KPI Dashboard — Falaba & Karena
 * Scorecard structure mirrors KPI Dashboard spreadsheet:
 *   Program | Indicator | Baseline | Target | To Date | 12 monthly
 *   performance columns | Trends | Source | Comments
 *
 * Each KPI: { id, label, category, district, actual, target, baseline, unit,
 *             direction, source, comments }
 * Values are illustrative sample data — replace with the real DHMT figures.
 * monthly[] series are 12 values (Jan...Dec); last value = "To Date".
 */

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

const KPI_DEFS = [
  /* ================= 1. Impact / Health Outcome KPIs ================= */
  {
    id: "mmr", label: "Maternal Mortality Ratio",
    category: "Impact & Health Outcomes", district: "all",
    actual: 354, target: 347, baseline: 1120, unit: "per 100k live births", direction: "lower",
    source: "DHIS2 / Reproductive Health", comments: "Declining from 2017 baseline (1,120); MTNDP 2024-2030 target: 347",
    monthly: [370, 367, 364, 361, 359, 358, 356, 355, 353, 352, 350, 354]
  },
  {
    id: "u5mr", label: "Under-5 Mortality Rate",
    category: "Impact & Health Outcomes", district: "all",
    actual: 156, target: 95, baseline: 161, unit: "per 1,000 live births", direction: "lower",
    source: "DHIS2 / Child Health", comments: "Far above target — requires priority review",
    monthly: [168, 166, 164, 162, 160, 159, 157, 156, 156, 155, 156, 156]
  },
  {
    id: "imr", label: "Infant Mortality Rate",
    category: "Impact & Health Outcomes", district: "all",
    actual: 92, target: 50, baseline: 95, unit: "per 1,000 live births", direction: "lower",
    source: "DHIS2 / Child Health", comments: "Slow decline; acceleration needed",
    monthly: [100, 99, 97, 96, 95, 94, 93, 93, 92, 92, 92, 92]
  },
  {
    id: "hiv", label: "HIV Prevalence (15-49)",
    category: "Impact & Health Outcomes", district: "all",
    actual: 1.5, target: 1.5, baseline: 1.6, unit: "%", direction: "lower",
    source: "National HIV Survey", comments: "At target — stabilised",
    monthly: [1.6, 1.6, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5]
  },

  /* ================= 2. RMNCAH / Service Delivery KPIs ================= */
  {
    id: "sba", label: "Skilled Birth Attendance",
    category: "RMNCAH & Service Delivery", district: "all",
    actual: 78, target: 100, baseline: 54, unit: "%", direction: "higher",
    source: "Maternity registers", comments: "Penta/maternity data reconciled monthly",
    monthly: [71, 72, 73, 74, 75, 75, 76, 77, 77, 78, 78, 78]
  },
  {
    id: "anc", label: "ANC4+ Coverage",
    category: "RMNCAH & Service Delivery", district: "all",
    actual: 76, target: 90, baseline: 52, unit: "%", direction: "higher",
    source: "ANC registers", comments: "Falaba behind Karena",
    monthly: [70, 71, 72, 72, 73, 74, 75, 75, 76, 77, 78, 76]
  },
  {
    id: "delivery", label: "Institutional Delivery Rate",
    category: "RMNCAH & Service Delivery", district: "all",
    actual: 71, target: 90, baseline: 50, unit: "%", direction: "higher",
    source: "Maternity registers / WB PDO", comments: "Tracked in World Bank project PDOs",
    monthly: [64, 66, 67, 68, 69, 70, 70, 71, 71, 72, 73, 71]
  },
  {
    id: "cpr", label: "Contraceptive Prevalence Rate",
    category: "RMNCAH & Service Delivery", district: "all",
    actual: 26, target: 30, baseline: 16, unit: "%", direction: "higher",
    source: "National survey / FP registers", comments: "2022: 21% (15-19y), 26% (20-49y)",
    monthly: [20, 21, 22, 22, 23, 23, 24, 25, 25, 26, 26, 26]
  },
  {
    id: "immun", label: "Full Immunization (<1 yr)",
    category: "RMNCAH & Service Delivery", district: "all",
    actual: 58, target: 100, baseline: 48, unit: "%", direction: "higher",
    source: "EPI tally sheets", comments: "BCG 96% / DTP3 81% / Measles 75% reported nationally in 2022",
    monthly: [52, 53, 54, 55, 55, 56, 57, 57, 58, 59, 60, 58]
  },

  /* ================= 3. Health System / Service Readiness ================= */
  {
    id: "cemonc", label: "Facilities Meeting CEmONC/BEmONC Standards",
    category: "Health System & Readiness", district: "all",
    actual: 35, target: 70, baseline: 12, unit: "%", direction: "higher",
    source: "Health facility assessment", comments: "2015 assessment: no facility met all criteria",
    monthly: [28, 29, 30, 31, 32, 33, 34, 35, 35, 36, 36, 35]
  },
  {
    id: "hrh", label: "Health Worker Density",
    category: "Health System & Readiness", district: "all",
    actual: 3.0, target: 4.5, baseline: 2.1, unit: "physicians per 100k", direction: "higher",
    source: "HRH database", comments: "National: 3 per 100k physicians; 4,000+ workers recruited post-Ebola",
    monthly: [2.6, 2.7, 2.7, 2.8, 2.8, 2.9, 2.9, 3.0, 3.0, 3.0, 3.1, 3.0]
  },
  {
    id: "watsan", label: "Facilities with Functional WATSAN & Power",
    category: "Health System & Readiness", district: "all",
    actual: 64, target: 90, baseline: 40, unit: "%", direction: "higher",
    source: "Facility infrastructure audit", comments: "WATSAN, electricity, lab diagnostics, referral systems",
    monthly: [58, 59, 60, 61, 62, 62, 63, 64, 64, 65, 66, 64]
  },
  {
    id: "qoc", label: "Quality of Care Service Readiness Score",
    category: "Health System & Readiness", district: "all",
    actual: 58, target: 80, baseline: 35, unit: "%", direction: "higher",
    source: "QoC dashboard (DHIS2)", comments: "One of 4 PDO targets for Quality Essential Health Services project",
    monthly: [52, 53, 54, 55, 56, 57, 57, 58, 59, 60, 60, 58]
  },

  /* ================= 4. Surveillance, Disease Control & Data ================= */
  {
    id: "eidsr_complete", label: "eIDSR Reporting Completeness",
    category: "Surveillance & Data", district: "all",
    actual: 72, target: 95, baseline: 55, unit: "%", direction: "higher",
    source: "eIDSR weekly reports", comments: "Weekly electronic surveillance from 1,500+ facilities / 16 districts",
    monthly: [64, 65, 66, 67, 68, 69, 70, 71, 72, 74, 76, 72]
  },
  {
    id: "eidsr_timely", label: "eIDSR Reporting Timeliness",
    category: "Surveillance & Data", district: "all",
    actual: 64, target: 90, baseline: 40, unit: "%", direction: "higher",
    source: "eIDSR weekly reports", comments: "Below target — risk of silent outbreaks",
    monthly: [58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 64]
  },
  {
    id: "ipc", label: "IPC Performance Score",
    category: "Surveillance & Data", district: "all",
    actual: 78, target: 90, baseline: 58, unit: "%", direction: "higher",
    source: "IPC assessment grades", comments: "National unit moved 58% → 78% (2021-2023)",
    monthly: [70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 78]
  },
  {
    id: "malaria", label: "Malaria Test Positivity Rate",
    category: "Surveillance & Data", district: "all",
    actual: 24, target: 30, baseline: 34, unit: "%", direction: "lower",
    source: "Lab registers", comments: "Ending malaria as public health threat by 2030",
    monthly: [30, 29, 28, 27, 27, 26, 26, 25, 25, 24, 24, 24]
  }
];

/* District-level values (falaba vs karena) */
const DISTRICT_ACTUALS = {
  mmr:             { falaba: 360, karena: 348 },
  u5mr:            { falaba: 160, karena: 152 },
  imr:             { falaba: 95,  karena: 89 },
  hiv:             { falaba: 1.6, karena: 1.4 },
  sba:             { falaba: 75,  karena: 81 },
  anc:             { falaba: 73,  karena: 79 },
  delivery:        { falaba: 68,  karena: 74 },
  cpr:             { falaba: 24,  karena: 28 },
  immun:           { falaba: 55,  karena: 61 },
  cemonc:          { falaba: 30,  karena: 40 },
  hrh:             { falaba: 2.8, karena: 3.2 },
  watsan:          { falaba: 60,  karena: 68 },
  qoc:             { falaba: 55,  karena: 62 },
  eidsr_complete:  { falaba: 69,  karena: 75 },
  eidsr_timely:    { falaba: 61,  karena: 67 },
  ipc:             { falaba: 74,  karena: 82 },
  malaria:         { falaba: 26,  karena: 22 }
};
const DISTRICT_LABELS = ["Falaba", "Karena"];

/* Status colours (matches spreadsheet legend tab) */
const LEGEND = [
  { color: "#0f8a3d", label: "Target achieved or surpassed" },
  { color: "#b9b1a0", label: "Unverified data" },
  { color: "#4a4235", label: "KPI did not exist this month" },
  { color: "#c9962a", label: "Still collecting / calculating KPI" },
  { color: "#d43f2f", label: "Data inconsistencies" }
];

/* =============== Detail Reporting dashboard ===============
 * Monthly Reporting Form — data extracted from the facility's ODS
 * form. Rows = months (Jan-Dec). Columns are grouped under the
 * form's four program sections (ANC / Deliveries / Family
 * Planning / TD), with the recorded Jan-Jun figures. */

/* Facility that this monthly reporting form belongs to.
 * Selecting any other facility shows a blank (no data). */
const FORM_FACILITY = "Mongo Bendugu CHC";

const FACILITIES = [
  { name: "Mongo Bendugu CHC", district: "falaba" },
  { name: "Bendugu CHP", district: "falaba" },
  { name: "Sikunia CHC", district: "falaba" },
  { name: "Falaba Town CHC", district: "falaba" },
  { name: "Kamakwie CHC", district: "karena" },
  { name: "Bumban CHC", district: "karena" },
  { name: "Gbanti CHP", district: "karena" }
];

const FORM_GROUPS = [
  { name: "Antenatal Clinic (ANC Visits)", wrap: true, subs: [{ name: "In Facility", cols: ["1st Contact", "<19 Years", "≥19 Years", "<12 Weeks", "4th Contact", "8th Contact"] }, { name: "Outreach", cols: ["1st Contact", "<19 Years", "≥19 Years", "<12 Weeks", "4th Contact", "8th Contact"] }] },
  { name: "Deliveries", wrap: true, subs: [{ name: "", cols: ["Total Deliveries", "SVD", "AVD", "Live Birth", "Low Birth Weight", "FSB", "MSB", "PAC", "Referrals", "Maternal Death"] }] },
  { name: "Family Planning", subs: [{ name: "Oral Pills", cols: ["New Clients", "Sub Visits"] }, { name: "Injectable", cols: ["New Clients", "Sub Visits"] }, { name: "Implants", cols: ["New Clients", "Sub Visits"] }, { name: "Condom", cols: ["New Clients", "Sub Visits"] }, { name: "IUD/Coil", cols: ["New Clients", "Sub Visits"] }, { name: "Postpartum", cols: ["New Clients", "Sub Visits"] }] },
  { name: "TD Given", wrap: true, subs: [{ name: "", cols: ["TD 1", "TD 2", "TD 3", "TD 4", "TD 5"] }] },
];

const FORM_MONTHS = [
  { m: "Jan", c: [22, null, null, 3, 11, 0, 0, 0, 0, 0, 0, 0, 30, 30, 0, 28, 4, 0, 2, 1, 1, 0, 7, 4, 3, 2, 7, 10, 18, 18, 0, 0, 0, 3, 1, 4, 3, 3, null] },
  { m: "Feb", c: [24, null, null, 2, 24, 1, 0, 0, 0, 0, 0, 0, 17, 17, 0, 16, 1, 1, 0, 0, 8, 0, 1, 1, 2, 4, 16, 21, 6, 8, 0, 0, 0, 4, 10, 5, 5, 5, null] },
  { m: "Mar", c: [22, null, null, 1, 13, 0, 3, null, null, 0, 3, 0, 22, 22, 0, 22, 0, 0, 0, 1, 12, 0, 10, 3, 11, 9, 12, 0, 17, 1, 0, 0, 0, 15, 1, 4, 1, 2, null] },
  { m: "Apr", c: [19, 1, 18, 2, 12, 1, 5, 1, 4, 0, 0, 0, 26, 26, 0, 26, 1, 0, 0, 0, 9, 1, 1, 5, 5, 2, 8, 4, 10, 3, 0, 0, 0, 8, 6, 3, 6, 4, null] },
  { m: "May", c: [34, 3, 31, 6, 16, 0, 0, 0, 0, 0, 3, 0, 19, 19, 0, 19, 0, 0, 0, 1, 3, 0, 4, 3, 14, 1, 16, 1, 32, 0, 0, 0, 0, 21, 11, 3, 6, 1, null] },
  { m: "Jun", c: [18, 2, 17, 2, 18, 2, 0, 0, 0, 0, 3, 0, 23, 23, 0, 23, 0, 0, 0, 0, 5, 0, 0, 0, 1, 0, 12, 7, 11, 7, 0, 0, 1, 12, 10, 2, 2, 1, null] },
  { m: "Jul", c: [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null] },
  { m: "Aug", c: [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null] },
  { m: "Sep", c: [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null] },
  { m: "Oct", c: [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null] },
  { m: "Nov", c: [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null] },
  { m: "Dec", c: [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null] },
];