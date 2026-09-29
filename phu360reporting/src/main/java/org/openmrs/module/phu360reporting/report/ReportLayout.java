package org.openmrs.module.phu360reporting.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How one REPORT TYPE presents itself, declared alongside its data mapping in
 * {@code module/reportmappings/}.
 *
 * <p>Until this existed the dashboard drew the same four KPI cards and the same
 * five charts for every report, and a REPORT TYPE only changed which encounters
 * were counted. That made the page actively misleading for a register: an MNR
 * page whose only headline number was "Encounters" says nothing about live
 * births or low birth weight, which is what the register exists to record.
 *
 * <p>A layout is therefore a list of indicators and a list of sections, and each
 * one names the obs concept it is computed from. The generic renderer draws
 * whatever the selected report declares, so giving a report its own page is an
 * edit to its mapping file rather than a new view in JavaScript.
 *
 * <p>Concepts are named, not numbered, for the same reason encounter types are:
 * the concept ids a data package assigns differ between installations, while the
 * names the forms were built with do not.
 */
public final class ReportLayout {

    /**
     * Count of the encounters a report selects - the volume figure every report
     * has, and the one indicator that needs no concept.
     */
    public static final String KIND_ENCOUNTER_COUNT = "encounterCount";

    /** Encounters that recorded the concept with a coded answer. */
    public static final String KIND_CODED_COUNT = "codedCount";

    /**
     * Share of the encounters that recorded the concept which gave a particular
     * coded answer, as a percentage. "Did they answer at all" is the denominator
     * rather than all encounters, because a register row where the field was
     * left blank is not a "no" and counting it as one would understate the rate.
     */
    public static final String KIND_CODED_RATE = "codedRate";

    /** Encounters carrying a numeric value for the concept. */
    public static final String KIND_NUMERIC = "numeric";

    /**
     * The mean of the numeric values recorded for the concept, to one decimal
     * place. A separate kind from {@link #KIND_NUMERIC} because a register
     * question is usually "how heavy were the babies", not "how often was a
     * weight written down", and the two figures are read as different things.
     */
    public static final String KIND_NUMERIC_MEAN = "numericMean";

    /**
     * Distinct patients among the encounters the report selects. A register is
     * about people, and a child seen three times in a month is one child - which
     * is the opposite of the "patient visits" figure the shared KPIs count, and
     * deliberate: the two answer different questions.
     */
    public static final String KIND_DISTINCT_PATIENTS = "distinctPatients";

    /**
     * Mean age of the patients seen, in months, to one decimal place. Computed
     * at the encounter rather than today, so an under-five register reads the
     * age the child actually was when seen.
     */
    public static final String KIND_MEAN_AGE_MONTHS = "meanAgeMonths";

    /** A bar of the coded answers to one concept. */
    public static final String KIND_CODED_BREAKDOWN = "codedBreakdown";

    /**
     * A bar of every condition concept the report's mapping names, counted
     * where the answer was affirmative. This is the morbidity shape a treatment
     * register needs, and it needs the mapping's concept list rather than one
     * concept.
     */
    public static final String KIND_CONDITION_BREAKDOWN = "conditionBreakdown";

    /**
     * A bar per under-five age band, the grouping the paediatric register uses:
     * neonatal, then each quarter to a year, then each year to five. Fixed
     * rather than configurable because it is the one banding a Sierra Leone
     * under-five register is read by, and a per-report band list would be a way
     * for a register to quietly renumber the thing it is compared against.
     */
    public static final String KIND_AGE_BREAKDOWN = "ageBreakdown";

    /** A single headline number. */
    public static final class Indicator {
        private final String key;
        private final String label;
        private final String kind;
        private final String concept;
        private final String answer;
        private final boolean inverted;

        Indicator(String key, String label, String kind, String concept, String answer, boolean inverted) {
            this.key = key;
            this.label = label;
            this.kind = kind;
            this.concept = concept;
            this.answer = answer;
            this.inverted = inverted;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public String getKind() {
            return kind;
        }

        /** The obs concept this indicator reads, or null when it needs none. */
        public String getConcept() {
            return concept;
        }

        /** The coded answer that counts, or null when the kind does not use one. */
        public String getAnswer() {
            return answer;
        }

        /**
         * True when a "no" is the good outcome, so the figure is reported as the
         * share that was <em>not</em> the counted answer. A register that records
         * "Post-Partum Haemorrage?" needs the share without it; presenting the
         * share with it next to a green tick would invite exactly the wrong
         * reading.
         */
        public boolean isInverted() {
            return inverted;
        }
    }

    /** A chart, drawn by the kind the layout names. */
    public static final class Section {
        private final String key;
        private final String label;
        private final String kind;
        private final String concept;
        private final int limit;

        Section(String key, String label, String kind, String concept, int limit) {
            this.key = key;
            this.label = label;
            this.kind = kind;
            this.concept = concept;
            this.limit = limit;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public String getKind() {
            return kind;
        }

        public String getConcept() {
            return concept;
        }

        /**
         * How many bars to keep. A treatment register names over a hundred
         * conditions, which is not a chart; the limit is applied per section so
         * a register can show its leading conditions and the rest in a table.
         */
        public int getLimit() {
            return limit;
        }
    }

    private final List<Indicator> indicators;
    private final List<Section> sections;
    private final List<String> conditionConcepts;

    ReportLayout(List<Indicator> indicators, List<Section> sections, List<String> conditionConcepts) {
        this.indicators = Collections.unmodifiableList(new ArrayList<Indicator>(indicators));
        this.sections = Collections.unmodifiableList(new ArrayList<Section>(sections));
        this.conditionConcepts = Collections.unmodifiableList(new ArrayList<String>(conditionConcepts));
    }

    /**
     * The layout every report gets when it declares none of its own: the one
     * figure that needs no data package, and no sections. The encounter-shape
     * charts (type, location, age, sex) are drawn for every report regardless of
     * layout, so a report with no layout of its own is not left without charts -
     * it is left with the charts every report has.
     */
    public static ReportLayout standard() {
        List<Indicator> indicators = new ArrayList<Indicator>();
        indicators.add(new Indicator("encounters", "Encounters", KIND_ENCOUNTER_COUNT, null, null, false));
        return new ReportLayout(indicators, new ArrayList<Section>(), new ArrayList<String>());
    }

    public List<Indicator> getIndicators() {
        return indicators;
    }

    public List<Section> getSections() {
        return sections;
    }

    /**
     * The obs concept names the report's {@code conditionBreakdown} section
     * counts, being the register's own list of conditions.
     *
     * <p>Names, and declared by the layout rather than read from the mapping's
     * {@code obsConcepts}, because those are ids from the data package that
     * exported the report. A register that has since been re-exported under
     * different ids would break a breakdown keyed on the old ones, and would
     * break it silently: the ids would match nothing and the chart would come
     * back empty.
     */
    public List<String> getConditionConcepts() {
        return conditionConcepts;
    }
}
