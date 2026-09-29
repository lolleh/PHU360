package org.openmrs.module.phu360reporting.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reports offered by the REPORT TYPE filter.
 *
 * <p>Each report's data mapping lives in {@code module/reportmappings/} in the
 * omod and is read by {@link ReportMappings}; this class is only the in-memory
 * view of it. That is the whole reason the mapping is data: adding an encounter
 * type to a report, or giving one of the HF summaries a definition, is an edit
 * to a file in this repository rather than a change to a Java array that has to
 * be recompiled before anyone can see it.
 *
 * <p>Nothing in a mapping is hardcoded to a row id where a name will do:
 * encounter types and obs concepts are resolved by name at request time, so the
 * same mapping works across the different concept/encounter id sets that the
 * various PIH data packages load.
 *
 * <p>A report is only listed as {@link #isMapped()} when its data mapping is
 * actually defined. Selecting an unmapped report is reported back to the UI as
 * such, rather than quietly returning every encounter - an indicator that looks
 * like it is filtering but is not is worse than one that says it has nothing
 * configured yet. A mapping that exists here but not in the running database
 * (the Above Five morbidity concepts, which were never exported) counts as
 * unmapped too; see {@link ReportFilter#obsConceptsMissing}.
 */
public final class ReportCatalog {

    private ReportCatalog() {
    }

    public static final class Report {
        private final String key;
        private final String label;
        private final String table;
        private final String[] encounterTypeNames;
        private final int[] obsConceptIds;
        private final int obsValueId;
        private final boolean mapped;

        Report(String key, String label, boolean mapped, List<String> encounterTypeNames, List<Integer> obsConceptIds,
                int obsValueId, String table) {
            this.key = key;
            this.label = label;
            this.mapped = mapped;
            this.encounterTypeNames = encounterTypeNames == null ? new String[0]
                    : encounterTypeNames.toArray(new String[0]);
            this.obsConceptIds = obsConceptIds == null ? new int[0] : toIntArray(obsConceptIds);
            this.obsValueId = obsValueId;
            this.table = table;
        }

        private static int[] toIntArray(List<Integer> ids) {
            int[] out = new int[ids.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = ids.get(i).intValue();
            }
            return out;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        /** The table this report's figures are read from and refreshed into. */
        public String getTable() {
            return table;
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

        /**
         * This report's encounter type ids, resolved against the running
         * database. Names are the catalog's unit so one catalog works across
         * installations whose encounter type ids differ.
         *
         * <p>Empty for a report that does not restrict encounter types, which is
         * a different thing from resolving to an empty list because none of the
         * names were found - the first is "no restriction", the second would
         * filter on nothing and quietly return no encounters.
         */
        public List<Integer> getEncounterTypeIds(ReportFilter.NameResolver resolver) {
            List<Integer> ids = new ArrayList<Integer>();
            for (String name : getEncounterTypeNames()) {
                Integer id = resolver.encounterTypeId(name);
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids;
        }
    }

    private static Map<String, Report> index;

    private static synchronized Map<String, Report> index() {
        if (index == null) {
            Map<String, Report> m = new LinkedHashMap<String, Report>();
            for (Report r : ReportMappings.load()) {
                if (m.put(r.getKey(), r) != null) {
                    throw new IllegalStateException("Report mapping listed twice: " + r.getKey());
                }
            }
            if (!m.containsKey("all-encounter")) {
                // byKey() falls back to it for an unknown or absent report key,
                // so a catalog without it has no defined behaviour.
                throw new IllegalStateException("reportmappings/index.xml has no all-encounter report to fall back on");
            }
            index = Collections.unmodifiableMap(m);
        }
        return index;
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
