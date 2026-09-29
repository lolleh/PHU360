package org.openmrs.module.phu360reporting.report;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Folds {@link Fact}s into the JSON body the dashboard renders.
 *
 * <p>Facts arrive one row per day, so everything here is a sum. Summing is also
 * what makes the same sink work for both sources: fed by a live query or read
 * back out of a report's table, it produces the same body for the same range.
 */
public final class MapFactSink implements FactSink, MonthSink {

    /** KPI order on the dashboard, and the previous-period value each delta is measured against. */
    private static final String[][] KPIS = {
        { IndicatorFacts.KEY_ENCOUNTERS, IndicatorFacts.LABEL_ENCOUNTERS },
        { IndicatorFacts.KEY_PATIENTS_SEEN, IndicatorFacts.LABEL_PATIENTS_SEEN },
        { IndicatorFacts.KEY_NEW_REGISTRATIONS, IndicatorFacts.LABEL_NEW_REGISTRATIONS },
        { IndicatorFacts.KEY_CONDITIONS, IndicatorFacts.LABEL_CONDITIONS }
    };

    private static final int MAX_LABELLED = 12;

    private final Map<String, Long> current = new LinkedHashMap<String, Long>();
    private final Map<String, Long> previous = new LinkedHashMap<String, Long>();
    private final Map<String, Long> types = new LinkedHashMap<String, Long>();
    private final Map<String, Long> locations = new LinkedHashMap<String, Long>();
    private final Map<String, Long> sex = new LinkedHashMap<String, Long>();
    private final Map<String, Long> age = new LinkedHashMap<String, Long>();
    private final Map<String, long[]> months = new TreeMap<String, long[]>();

    private final java.util.Date to;
    private final boolean withPrevious;

    /**
     * @param to            the last day of the range, used to lay out the twelve
     *                      trend months
     * @param withPrevious  true when previous-period facts have been added, so
     *                      the deltas can be computed
     */
    public MapFactSink(java.util.Date to, boolean withPrevious) {
        this.to = to;
        this.withPrevious = withPrevious;
    }

    public void add(Fact fact) {
        if (IndicatorFacts.DIM_KPI.equals(fact.getDimension())) {
            addTo(current, fact.getKey(), fact.getValue());
        } else if (IndicatorFacts.DIM_TYPE.equals(fact.getDimension())) {
            addTo(types, fact.getDimensionValue(), fact.getValue());
        } else if (IndicatorFacts.DIM_SEX.equals(fact.getDimension())) {
            addTo(sex, fact.getDimensionValue(), fact.getValue());
        } else if (IndicatorFacts.DIM_AGE.equals(fact.getDimension())) {
            addTo(age, fact.getDimensionValue(), fact.getValue());
        } else if (IndicatorFacts.DIM_LOCATION.equals(fact.getDimension())) {
            addTo(locations, fact.getDimensionValue(), fact.getValue());
        }
    }

    /**
     * Adds the previous period's KPI values, so the deltas can be measured
     * against them. Kept apart from {@link #add} because the two windows are read
     * from different places - a second live query, or a second range out of the
     * same report table.
     */
    public void addPrevious(Fact fact) {
        if (IndicatorFacts.DIM_KPI.equals(fact.getDimension())) {
            previous.put(fact.getKey(), fact.getValue());
        }
    }

    public void add(String yearMonth, long encounters, long patients) {
        months.put(yearMonth, new long[] { encounters, patients });
    }

    public Map<String, Object> build() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("kpis", kpis());
        out.put("monthly", monthly());
        out.put("byType", topN(types));
        out.put("byLocation", byLocation());
        out.put("sex", labelled(sex));
        out.put("age", labelled(age));
        return out;
    }

    private Map<String, Object> kpis() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (String[] kpi : KPIS) {
            String key = kpi[0];
            long value = value(current, key);
            long before = withPrevious ? value(previous, key) : 0L;
            items.add(item(key, kpi[1], value, before));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("items", items);
        return out;
    }

    private Map<String, Object> item(String key, String label, long value, long before) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        m.put("delta", delta(value, before));
        return m;
    }

    private double delta(long value, long before) {
        if (before == 0L) {
            return value > 0 ? 100.0 : 0.0;
        }
        return Math.round(((value - before) / (double) before) * 1000.0) / 10.0;
    }

    /**
     * The twelve months ending at {@code to}, which is how the dashboard's trend
     * has always been drawn - a fixed window rather than one point per month in
     * the selected range.
     */
    private List<Map<String, Object>> monthly() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM");
        for (int i = 11; i >= 0; i--) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(to);
            cal.add(Calendar.MONTH, -i);
            String ym = fmt.format(cal.getTime());
            long[] pair = months.get(ym);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ym", ym);
            m.put("encounters", pair == null ? 0L : pair[0]);
            m.put("patients", pair == null ? 0L : pair[1]);
            list.add(m);
        }
        return list;
    }



    /**
     * With a health center selected the breakdown resolves to that center's own
     * locations, which the dashboard shows in place of the all-centers ranking;
     * the label it carries is what makes that explicit rather than looking like
     * an all-centers list. Either way the rows are ranked, so the chart shows the
     * busiest locations of whatever was selected.
     */
    private List<Map<String, Object>> byLocation() {
        return topN(locations);
    }

    private List<Map<String, Object>> labelled(Map<String, Long> counts) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("label", e.getKey());
            m.put("count", e.getValue());
            list.add(m);
        }
        return list;
    }

    private List<Map<String, Object>> topN(Map<String, Long> counts) {
        List<Map.Entry<String, Long>> entries = new ArrayList<Map.Entry<String, Long>>(counts.entrySet());
        java.util.Collections.sort(entries, new java.util.Comparator<Map.Entry<String, Long>>() {
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                int byCount = Long.compare(b.getValue(), a.getValue());
                return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
            }
        });
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < entries.size() && i < MAX_LABELLED; i++) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("label", entries.get(i).getKey());
            m.put("count", entries.get(i).getValue());
            list.add(m);
        }
        return list;
    }

    private void addTo(Map<String, Long> target, String key, long value) {
        Long existing = target.get(key);
        target.put(key, existing == null ? value : existing + value);
    }

    private long value(Map<String, Long> counts, String key) {
        Long v = counts.get(key);
        return v == null ? 0L : v;
    }

}
