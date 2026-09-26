package org.openmrs.module.phu360reporting.report;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.NativeQuery;
import org.openmrs.api.context.Context;

/**
 * Computes facility reporting indicators straight from the OpenMRS MySQL
 * schema (core tables: encounter, person, location, encounter_type,
 * conditions). Everything emitted is an aggregate - no patient-level data.
 */
public class IndicatorReport {

    private final java.util.Date from;
    private final java.util.Date to;
    private final java.util.Set<Integer> locationIds;
    private final Integer encounterTypeId;

    public IndicatorReport(java.util.Date from, java.util.Date to, java.util.Set<Integer> locationIds, Integer encounterTypeId) {
        this.from = from;
        this.to = to;
        this.locationIds = locationIds;
        this.encounterTypeId = encounterTypeId;
    }

    public Map<String, Object> build() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("from", fmt(from));
        out.put("to", fmt(to));

        long periodMs = to.getTime() - from.getTime();
        java.util.Date prevFrom = new java.util.Date(from.getTime() - periodMs);
        java.util.Date prevTo = new java.util.Date(to.getTime() - periodMs);

        out.put("kpis", kpis(prevFrom, prevTo));
        out.put("monthly", monthly());
        out.put("byType", byType());
        /* Always report the per-facility breakdown: with a location filter active it
           resolves to the selected center, which the dashboard shows in place of the
           all-centers ranking. */
        out.put("byLocation", byLocation());
        out.put("sex", sex());
        out.put("age", age());
        return out;
    }

    private Map<String, Object> kpis(java.util.Date prevFrom, java.util.Date prevTo) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        list.add(kpi("encounters", "Encounters", countEncounterRange(from, to), countEncounterRange(prevFrom, prevTo)));
        list.add(kpi("patientsSeen", "Patients seen", countEncounterRangeDistinct(from, to), countEncounterRangeDistinct(prevFrom, prevTo)));
        list.add(kpi("newRegistrations", "New registrations", countRegistrations(from, to), countRegistrations(prevFrom, prevTo)));
        list.add(kpi("conditions", "Conditions recorded", countConditions(from, to), countConditions(prevFrom, prevTo)));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("items", list);
        return out;
    }

    private Map<String, Object> kpi(String key, String label, Number value, Number previous) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value.longValue());
        double p = previous == null ? 0 : previous.doubleValue();
        double cur = value == null ? 0 : value.doubleValue();
        double delta = (p == 0) ? (cur > 0 ? 100.0 : 0.0) : ((cur - p) / p * 100.0);
        m.put("delta", Math.round(delta * 10.0) / 10.0);
        return m;
    }

    private Session session() {
        SessionFactory sf = Context.getRegisteredComponent("sessionFactory", SessionFactory.class);
        return sf.openSession();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> query(Session sess, String sql, ParamSetter setter) {
        NativeQuery q = sess.createNativeQuery(sql);
        setter.apply(q);
        return q.list();
    }

    /** Unwraps a scalar-vs-Object[] row (single-column COUNT selects come back as scalars). */
    private Object firstRow(Object row) {
        return (row instanceof Object[]) ? ((Object[]) row)[0] : row;
    }

    /** Base WHERE clause for encounters within the range, honoring filters. */
    private String encounterBase(java.util.Date f, java.util.Date t, boolean includeType) {
        StringBuilder sb = new StringBuilder("e.voided=0 AND e.encounter_datetime >= :from AND e.encounter_datetime < :to");
        if (locationIds != null && !locationIds.isEmpty()) {
            sb.append(" AND e.location_id IN (:locIds)");
        }
        if (includeType && encounterTypeId != null) {
            sb.append(" AND e.encounter_type = :etid");
        }
        return sb.toString();
    }

    private long countEncounterRange(java.util.Date f, java.util.Date t) {
        long cnt = 0;
        Session sess = session();
        try {
            StringBuilder sql = new StringBuilder("SELECT COUNT(e.encounter_id) FROM encounter e WHERE ");
            sql.append(encounterBase(f, t, true));
            List<Object[]> rows = query(sess, sql.toString(), q -> setRange(q, f, t, true));
            return ((Number) firstRow(rows.get(0))).longValue();
        } finally {
            sess.close();
        }
    }

    private long countEncounterRangeDistinct(java.util.Date f, java.util.Date t) {
        return countEncounterRangeDistinct(f, t, true);
    }

    private long countEncounterRangeDistinct(java.util.Date f, java.util.Date t, boolean includeType) {
        Session sess = session();
        try {
            StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT e.patient_id) FROM encounter e WHERE ");
            sql.append(encounterBase(f, t, includeType));
            List<Object[]> rows = query(sess, sql.toString(), q -> setRange(q, f, t, includeType));
            return ((Number) firstRow(rows.get(0))).longValue();
        } finally {
            sess.close();
        }
    }

    private long countRegistrations(java.util.Date f, java.util.Date t) {
        Session sess = session();
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM patient pt JOIN person p ON p.person_id=pt.patient_id "
                + "WHERE p.voided=0 AND p.date_created >= :from AND p.date_created < :to");
            if ((locationIds != null && !locationIds.isEmpty()) || encounterTypeId != null) {
                sql.append(" AND EXISTS (SELECT 1 FROM encounter e WHERE e.patient_id=p.person_id AND e.voided=0");
                if (locationIds != null && !locationIds.isEmpty()) sql.append(" AND e.location_id IN (:locIds)");
                if (encounterTypeId != null) sql.append(" AND e.encounter_type = :etid");
                sql.append(")");
            }
            List<Object[]> rows = query(sess, sql.toString(), q -> setRange(q, f, t, true));
            return ((Number) firstRow(rows.get(0))).longValue();
        } finally {
            sess.close();
        }
    }

    private long countConditions(java.util.Date f, java.util.Date t) {
        Session sess = session();
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM conditions c WHERE c.voided=0 "
                + "AND COALESCE(c.onset_date, c.date_created) >= :from AND COALESCE(c.onset_date, c.date_created) < :to");
            if ((locationIds != null && !locationIds.isEmpty()) || encounterTypeId != null) {
                sql.append(" AND EXISTS (SELECT 1 FROM encounter e WHERE e.patient_id=c.patient_id AND e.voided=0");
                if (locationIds != null && !locationIds.isEmpty()) sql.append(" AND e.location_id IN (:locIds)");
                if (encounterTypeId != null) sql.append(" AND e.encounter_type = :etid");
                sql.append(")");
            }
            List<Object[]> rows = query(sess, sql.toString(), q -> setRange(q, f, t, true));
            return ((Number) firstRow(rows.get(0))).longValue();
        } finally {
            sess.close();
        }
    }

    private List<Map<String, Object>> monthly() {
        Session sess = session();
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT DATE_FORMAT(e.encounter_datetime,'%Y-%m') ym, COUNT(e.encounter_id) cnt, COUNT(DISTINCT e.patient_id) pats "
                + "FROM encounter e WHERE ").append(encounterBase(from, to, true))
                .append(" GROUP BY DATE_FORMAT(e.encounter_datetime,'%Y-%m') ORDER BY ym");
            List<Object[]> rows = query(sess, sql.toString(), q -> setRange(q, from, to, true));

            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            for (int i = 11; i >= 0; i--) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(to);
                cal.add(java.util.Calendar.MONTH, -i);
                String ym = new SimpleDateFormat("yyyy-MM").format(cal.getTime());
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("ym", ym);
                m.put("encounters", 0L);
                m.put("patients", 0L);
                for (Object[] r : rows) {
                    if (ym.equals(String.valueOf(r[0]))) {
                        m.put("encounters", ((Number) r[1]).longValue());
                        m.put("patients", ((Number) r[2]).longValue());
                        break;
                    }
                }
                list.add(m);
            }
            return list;
        } finally {
            sess.close();
        }
    }

    private List<Map<String, Object>> byType() {
        Session sess = session();
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT et.name, COUNT(e.encounter_id) cnt FROM encounter e "
                + "JOIN encounter_type et ON et.encounter_type_id=e.encounter_type WHERE ")
                .append(encounterBase(from, to, false))
                .append(" GROUP BY et.name ORDER BY cnt DESC LIMIT 12");
            List<Object[]> rows = query(sess, sql.toString(), q -> setRange(q, from, to, false));
            return labelCounts(rows);
        } finally {
            sess.close();
        }
    }

    private List<Map<String, Object>> byLocation() {
        Session sess = session();
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT l.name, COUNT(e.encounter_id) cnt FROM encounter e "
                + "JOIN location l ON l.location_id=e.location_id WHERE ")
                .append(encounterBase(from, to, true))
                .append(" GROUP BY l.name ORDER BY cnt DESC LIMIT 12");
            List<Object[]> rows = query(sess, sql.toString(), q -> setRange(q, from, to, true));
            return labelCounts(rows);
        } finally {
            sess.close();
        }
    }

    private List<Map<String, Object>> sex() {
        Session sess = session();
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT p.gender, COUNT(DISTINCT e.patient_id) cnt FROM encounter e "
                + "JOIN person p ON p.person_id=e.patient_id WHERE ")
                .append(encounterBase(from, to, true))
                .append(" GROUP BY p.gender");
            List<Object[]> rows = query(sess, sql.toString(), q -> setRange(q, from, to, true));
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            for (Object[] r : rows) {
                String g = String.valueOf(r[0]);
                String label = "F".equals(g) ? "Female" : ("M".equals(g) ? "Male" : "Not recorded");
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("label", label);
                m.put("count", ((Number) r[1]).longValue());
                list.add(m);
            }
            return list;
        } finally {
            sess.close();
        }
    }

    private List<Map<String, Object>> age() {
        Session sess = session();
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT CASE "
                + "WHEN p.birthdate IS NULL THEN 'Unknown' "
                + "WHEN p.birthdate > :to THEN '0-4' "
                + "WHEN TIMESTAMPDIFF(YEAR, p.birthdate, :to) < 5 THEN '0-4' "
                + "WHEN TIMESTAMPDIFF(YEAR, p.birthdate, :to) < 10 THEN '5-9' "
                + "WHEN TIMESTAMPDIFF(YEAR, p.birthdate, :to) < 15 THEN '10-14' "
                + "WHEN TIMESTAMPDIFF(YEAR, p.birthdate, :to) < 20 THEN '15-19' "
                + "WHEN TIMESTAMPDIFF(YEAR, p.birthdate, :to) < 25 THEN '20-24' "
                + "WHEN TIMESTAMPDIFF(YEAR, p.birthdate, :to) < 35 THEN '25-34' "
                + "WHEN TIMESTAMPDIFF(YEAR, p.birthdate, :to) < 50 THEN '35-49' "
                + "ELSE '50+' END age, COUNT(DISTINCT e.patient_id) cnt "
                + "FROM encounter e JOIN person p ON p.person_id=e.patient_id WHERE ")
                .append(encounterBase(from, to, true))
                .append(" GROUP BY age");
            List<Object[]> rows = query(sess, sql.toString(), q -> setRange(q, from, to, true));
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            for (Object[] r : rows) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("label", String.valueOf(r[0]));
                m.put("count", ((Number) r[1]).longValue());
                list.add(m);
            }
            return list;
        } finally {
            sess.close();
        }
    }

    private List<Map<String, Object>> labelCounts(List<Object[]> rows) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("label", String.valueOf(r[0]));
            m.put("count", ((Number) r[1]).longValue());
            list.add(m);
        }
        return list;
    }

    private void setRange(NativeQuery q, java.util.Date f, java.util.Date t, boolean includeType) {
        q.setParameter("from", f).setParameter("to", t);
        if (locationIds != null && !locationIds.isEmpty()) {
            q.setParameterList("locIds", locationIds);
        }
        if (includeType && encounterTypeId != null) {
            q.setParameter("etid", encounterTypeId);
        }
    }

    private String fmt(java.util.Date d) {
        return new SimpleDateFormat("yyyy-MM-dd").format(d);
    }

    interface ParamSetter {
        void apply(NativeQuery q);
    }
}