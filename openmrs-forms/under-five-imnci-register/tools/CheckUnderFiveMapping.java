package org.openmrs.module.clinicreporting.facility.report;

import java.util.List;

/**
 * Reads the shipped report mappings off the classpath and checks the condition
 * list came through whole, with the three counting rules the under-five register
 * uses.
 *
 * <p>This exists because the mapping is the one part of the register that names
 * concepts as strings, and a parse failure there surfaces as a report that quietly
 * omits a section rather than as an error. Run against the omod the build produces.
 *
 * In this package because {@code ReportMappings.load()} is package-private, which
 * is itself deliberate: the mapping is read off the classpath by the module, not
 * by anything that should be reaching in from outside it.
 *
 * <pre>
 *   javac -cp ... -d /tmp/harness tools/CheckUnderFiveMapping.java
 *   java  -cp ...:/tmp/harness:/path/to/clinicreporting.omod \
 *         org.openmrs.module.clinicreporting.facility.report.CheckUnderFiveMapping
 * </pre>
 */
public class CheckUnderFiveMapping {

    public static void main(String[] args) {
        List<ReportCatalog.Report> reports = ReportMappings.load();
        ReportCatalog.Report underFive = null;
        for (ReportCatalog.Report report : reports) {
            if ("under-five-register".equals(report.getKey())) {
                underFive = report;
            }
        }
        if (underFive == null) {
            throw new IllegalStateException("under-five-register is not in the catalogue");
        }
        ReportLayout layout = underFive.getLayout();
        List<ReportLayout.Condition> conditions = layout.getConditionConcepts();

        int yes = 0;
        int recorded = 0;
        int answered = 0;
        for (ReportLayout.Condition condition : conditions) {
            if (condition.isRecorded()) {
                recorded++;
            } else if (condition.isAnswer()) {
                answered++;
                if (condition.getAnswers().isEmpty()) {
                    throw new IllegalStateException(
                            condition.getName() + " is kind=answer but names no answer");
                }
            } else {
                yes++;
            }
        }

        // The three numbers are the point of the change: drugs counted by value,
        // the disability census counted by its own printed Yes, everything else by
        // the dictionary's "Yes".
        System.out.println("under-five-register: " + conditions.size() + " conditions"
                + " (yes=" + yes + ", recorded=" + recorded + ", answer=" + answered + ")");
        if (recorded == 0) {
            throw new IllegalStateException("no recorded conditions: the drug counts would not be reported");
        }
        if (answered == 0) {
            throw new IllegalStateException(
                    "no answer-kind conditions: the disability census would not be reported");
        }
        System.out.println("mapping parsed, every counting rule present");
    }
}