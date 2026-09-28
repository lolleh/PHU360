package org.openmrs.module.phu360reporting.report;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reports offered by the REPORT TYPE filter.
 *
 * <p>Each entry declares how the report narrows the encounter set. Nothing here
 * is hardcoded to a row id where a name will do: encounter types and obs
 * concepts are resolved by name at request time, so the same catalog works
 * across the different concept/encounter id sets that the various PIH data
 * packages load.
 *
 * <p>A report is only listed as {@link #isMapped()} when its data mapping is
 * actually defined. Selecting an unmapped report is reported back to the UI as
 * such, rather than quietly returning every encounter - an indicator that looks
 * like it is filtering but is not is worse than one that says it has nothing
 * configured yet.
 */
public final class ReportCatalog {

    private ReportCatalog() {
    }

    public static final class Report {
        private final String key;
        private final String label;
        private final String[] encounterTypeNames;
        private final int[] obsConceptIds;
        private final int obsValueId;
        private final boolean mapped;

        Report(String key, String label, boolean mapped, String[] encounterTypeNames, int[] obsConceptIds, int obsValueId) {
            this.key = key;
            this.label = label;
            this.mapped = mapped;
            this.encounterTypeNames = encounterTypeNames == null ? new String[0] : encounterTypeNames;
            this.obsConceptIds = obsConceptIds == null ? new int[0] : obsConceptIds;
            this.obsValueId = obsValueId;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        /** True when this report has a data mapping and can therefore filter. */
        public boolean isMapped() {
            return mapped;
        }

        public List<String> getEncounterTypeNames() {
            return Collections.unmodifiableList(Arrays.asList(encounterTypeNames));
        }

        public int[] getObsConceptIds() {
            return obsConceptIds;
        }

        /** value_coded the obs concepts must carry, or 0 when unconstrained. */
        public int getObsValueId() {
            return obsValueId;
        }
    }

    /** Above Five morbidity concepts and the coded "Yes" value, as used by the
     *  aboveFiveMorbiditySummary / aboveFivePatientList report descriptors. */
    private static final int[] ABOVE_FIVE_MORBIDITY = {
        8842, 8840, 8838, 8839, 8844, 8774, 8851, 8848, 8849, 8852, 8850, 8841, 8846, 8843, 8845, 8847
    };

    private static final int CODED_YES = 8853;

    private static final String[] MOTHER_AND_NEONATE_TYPES = {
        // maternal
        "MCH Delivery",
        "Labor and Delivery Summary",
        "Labour Progress",
        "Maternity and Delivery Register",
        "Maternal Death",
        "Maternal Discharge",
        "Maternal Check-In",
        "PHU360 Maternal Check-in",
        "PHU360 MCH Triage",
        "ANC Intake",
        "ANC Followup",
        "ANC Progress",
        "Postnatal Followup",
        // newborn / neonatal
        "Newborn Initial",
        "Newborn Assessment",
        "Newborn Daily Progress",
        "Newborn Discharge",
        "Newborn Observations",
        "Newborn Referral",
        "NICU Followup",
        "NICU Triage",
        "SCBU Newborn Register"
    };

    private static Map<String, Report> index;

    private static synchronized Map<String, Report> index() {
        if (index == null) {
            Map<String, Report> m = new LinkedHashMap<String, Report>();
            add(m, new Report("all-encounter", "All Encounter", true, null, null, 0));
            add(m, new Report("above-five-morbidity", "Above Five Register - Morbidity Summary", true,
                    null, ABOVE_FIVE_MORBIDITY, CODED_YES));
            add(m, new Report("above-five-patient-list", "Above Five Register - Patient List", true,
                    new String[] { "PHU360 Outpatient Initial", "PHU360 Outpatient Followup" },
                    ABOVE_FIVE_MORBIDITY, CODED_YES));
            add(m, new Report("hf1-summary", "HF1 Summary", false, null, null, 0));
            add(m, new Report("hf2-summary", "HF2 Summary", false, null, null, 0));
            add(m, new Report("hf3-summary", "HF3 Summary", false, null, null, 0));
            add(m, new Report("hf5-summary", "HF5 Summary", false, null, null, 0));
            add(m, new Report("hf12-summary", "HF12 Summary", false, null, null, 0));
            add(m, new Report("mother-and-neonate", "Mother and Neonate", true, MOTHER_AND_NEONATE_TYPES, null, 0));
            index = Collections.unmodifiableMap(m);
        }
        return index;
    }

    private static void add(Map<String, Report> m, Report r) {
        m.put(r.getKey(), r);
    }

    public static List<Report> all() {
        return Collections.unmodifiableList(new java.util.ArrayList<Report>(index().values()));
    }

    /** The report for a key, falling back to "All Encounter". */
    public static Report byKey(String key) {
        if (key != null && key.length() > 0) {
            Report r = index().get(key);
            if (r != null) {
                return r;
            }
        }
        return index().get("all-encounter");
    }
}
