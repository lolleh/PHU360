package org.openmrs.module.phu360reporting.report;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The physical table each REPORT TYPE's indicators live in.
 *
 * <p>One table per report type, created by the module's liquibase.xml, and each
 * holding only the indicators of its own report: a report's numbers are the
 * product of its definition, so an Under Five row and a Mother and Neonate row
 * for the same day and health center are different facts and live in different
 * tables.
 *
 * <p>The names are looked up here rather than derived from the report key, so
 * that a key arriving from a request can never be interpolated into SQL.
 */
public final class ReportTables {

    private static final Map<String, String> TABLES = new LinkedHashMap<String, String>();

    static {
        TABLES.put("all-encounter", "phu360_report_all_encounter");
        TABLES.put("above-five-morbidity", "phu360_report_above_five_morbidity");
        TABLES.put("above-five-patient-list", "phu360_report_above_five_patient_list");
        TABLES.put("under-five-register", "phu360_report_under_five_register");
        TABLES.put("hf1-summary", "phu360_report_hf1_summary");
        TABLES.put("hf2-summary", "phu360_report_hf2_summary");
        TABLES.put("hf3-summary", "phu360_report_hf3_summary");
        TABLES.put("hf5-summary", "phu360_report_hf5_summary");
        TABLES.put("hf12-summary", "phu360_report_hf12_summary");
        TABLES.put("mother-and-neonate", "phu360_report_mother_and_neonate");
    }

    private ReportTables() {
    }

    /** The table for a report key, or null when the key has no table. */
    public static String forReport(String reportKey) {
        return TABLES.get(reportKey);
    }

    public static Map<String, String> all() {
        return Collections.unmodifiableMap(TABLES);
    }
}
