package org.openmrs.module.phu360reporting.report;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.text.SimpleDateFormat;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.NativeQuery;
import org.openmrs.api.context.Context;

/**
 * Computes the dashboard's indicators from the OpenMRS schema and emits them as
 * {@link Fact}s, one per day per health center per dimension.
 *
 * <p>This is the query half of what used to be {@code IndicatorReport.build()},
 * which aggregated the whole requested range in one pass and produced the JSON
 * body directly. Facts are the shared currency between the two ways the
 * dashboard gets its numbers: {@link MapFactSink} folds them into the JSON body,
 * and {@link IndicatorTableStore} writes them into the per-report-type tables.
 * The tables and a live query therefore agree by construction rather than by
 * two implementations of the same arithmetic.
 *
 * <p>Everything emitted is an aggregate - no patient-level data leaves the
 * database.
 */
public final class IndicatorFacts {

    public static final String DIM_KPI = "kpi";
    public static final String DIM_TYPE = "type";
    public static final String DIM_SEX = "sex";
    public static final String DIM_AGE = "age";
    public static final String DIM_LOCATION = "location";

    public static final String KEY_ENCOUNTERS = "encounters";
    /**
     * Distinct patients on each day, summed over the range - so a patient seen
     * on three days counts three times. The daily tables cannot hold a
     * range-wide distinct count, and storing patient identifiers to rebuild one
     * would defeat the point of storing aggregates; the label says "visits" for
     * the same reason.
     */
    public static final String KEY_PATIENTS_SEEN = "patientsSeen";
    public static final String KEY_NEW_REGISTRATIONS = "newRegistrations";
    public static final String KEY_CONDITIONS = "conditions";

    public static final String LABEL_ENCOUNTERS = "Encounters";
    public static final String LABEL_PATIENTS_SEEN = "Patient visits";
    public static final String LABEL_NEW_REGISTRATIONS = "New registrations";
    public static final String LABEL_CONDITIONS = "Conditions recorded";

    /**
     * Encounter types kept in the {@code type} dimension for a report that does
     * not restrict them itself. The dashboard only ever charts the busiest
     * dozen, and "All Encounter" would otherwise pin a row per day for each of
     * the hundred-odd encounter types this database has.
     */
    public static final int MAX_UNFILTERED_TYPES = 25;

    /** Group every fact by the encounter's own location rather than a bucket. */
    public static final int PER_LOCATION = Integer.MIN_VALUE;

    private IndicatorFacts() {
    }

    /**
     * Emits every indicator fact for the range.
     *
     * @param from          inclusive lower bound
     * @param to            exclusive upper bound
     * @param locationIds    health centers to restrict to, or null for all
     * @param filter        the report's restriction on the encounter set, or null
     * @param typeWhitelist encounter type ids for the type dimension, or null to
     *                      keep the busiest {@link #MAX_UNFILTERED_TYPES}
     * @param bucketId      {@link #PER_LOCATION} to group each fact by the
     *                      encounter's own location, or an id to collapse
     *                      everything into that one bucket
     * @param sink          receives the facts
     */
    public static void compute(Date from, Date to, Set<Integer> locationIds, ReportFilter filter, List<Integer> typeWhitelist,
            int bucketId, FactSink sink) {
        boolean perLocation = bucketId == PER_LOCATION;
        encounters(from, to, locationIds, filter, perLocation, bucketId, sink);
        registrations(from, to, locationIds, filter, perLocation, bucketId, sink);
        conditions(from, to, locationIds, filter, perLocation, bucketId, sink);
        byType(from, to, locationIds, filter, typeWhitelist, perLocation, bucketId, sink);
        bySex(from, to, locationIds, filter, perLocation, bucketId, sink);
        byAge(from, to, locationIds, filter, perLocation, bucketId, sink);
    }

    /**
     * The "encounters by location" chart, which is the only series that is about
     * locations and so cannot be derived from any of the other dimensions.
     *
     * <p>Each fact carries the bucket's own location id, not the location it was
     * counted at, because the bucket is what the row is selected by. The location
     * counted at is the fact's {@code dimensionValue}: the chart shows a name
     * against a count and buckets do not overlap, so names cannot collide inside
     * one.
     */
    public static void byLocation(Date from, Date to, Set<Integer> locationIds, ReportFilter filter, int bucketId, FactSink sink) {
        String sql = "SELECT DATE(e.encounter_datetime) d, l.name, COUNT(e.encounter_id) n"
                + " FROM encounter e JOIN location l ON l.location_id = e.location_id WHERE "
                + encounterWhere(from, to, locationIds, filter) + " GROUP BY d, l.name";
        for (Object[] r : query(sql, from, to, locationIds, filter, null)) {
            String name = str(r[1]);
            sink.add(new Fact(date(r[0]), bucketId, DIM_LOCATION, name, name, name, number(r[2])));
        }
    }

    /**
     * The twelve-month trend ending at {@code to}.
     *
     * <p>Windowed on {@code to} rather than on the requested range because that
     * is what the dashboard has always drawn, and it is the only series that does
     * not follow the selected dates.
     */
    public static void monthTrend(Date to, Set<Integer> locationIds, ReportFilter filter, MonthSink sink) {
        Calendar end = Calendar.getInstance();
        end.setTime(to);
        Calendar start = Calendar.getInstance();
        start.setTime(to);
        start.add(Calendar.MONTH, -12);
        start.add(Calendar.DAY_OF_MONTH, 1);
        SimpleDateFormat ym = new SimpleDateFormat("yyyy-MM");

        Map<String, long[]> byMonth = new TreeMap<String, long[]>();
        for (int i = 0; i < 12; i++) {
            Calendar m = Calendar.getInstance();
            m.setTime(to);
            m.add(Calendar.MONTH, -11 + i);
            byMonth.put(ym.format(m.getTime()), new long[2]);
        }

        // Counted per day and then added up per month, which is what the tables
        // hold: a month in the trend chart is the sum of its days. Grouping by
        // month directly would give a month-level distinct patient count that
        // no sum of daily facts can reproduce, so the live path and the stored
        // path would disagree about the same range.
        String sql = "SELECT DATE(e.encounter_datetime) d, COUNT(e.encounter_id) enc,"
                + " COUNT(DISTINCT e.patient_id) pats FROM encounter e WHERE e.voided = 0"
                + " AND e.encounter_datetime >= :from AND e.encounter_datetime < :to";
        if (locationIds != null && !locationIds.isEmpty()) {
            sql += " AND e.location_id IN (:locIds)";
        }
        if (filter != null) {
            sql += filter.sqlFragment();
        }
        sql += " GROUP BY d";
        for (Object[] r : query(sql, start.getTime(), end.getTime(), locationIds, filter, null)) {
            String ymOfDay = new SimpleDateFormat("yyyy-MM").format(date(r[0]));
            long[] slot = byMonth.get(ymOfDay);
            if (slot != null) {
                slot[0] += longValue(r[1]);
                slot[1] += longValue(r[2]);
            }
        }
        for (Map.Entry<String, long[]> e : byMonth.entrySet()) {
            sink.add(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
    }

    private static void encounters(Date from, Date to, Set<Integer> locationIds, ReportFilter filter, boolean perLocation,
            int bucketId, FactSink sink) {
        String sql = "SELECT DATE(e.encounter_datetime) d, " + locationColumn(perLocation, bucketId) + " loc, "
                + "COUNT(e.encounter_id) enc, COUNT(DISTINCT e.patient_id) pats FROM encounter e WHERE "
                + encounterWhere(from, to, locationIds, filter)
                + groupByDay(perLocation);
        // No whitelist: the type dimension is the one place the busiest-N cut is
        // applied, so the totals stay the totals the filter asked for.
        for (Object[] r : query(sql, from, to, locationIds, filter, null)) {
            sink.add(kpi(r[0], r[1], KEY_ENCOUNTERS, LABEL_ENCOUNTERS, r[2]));
            sink.add(kpi(r[0], r[1], KEY_PATIENTS_SEEN, LABEL_PATIENTS_SEEN, r[3]));
        }
    }

    /**
     * Registrations are counted by the date the person was created.
     *
     * <p>The all-centers bucket counts people with no encounter requirement at
     * all, matching the unfiltered live query. The per-center buckets have to
     * join encounters to learn where the person was seen, and count the person
     * once per center; a person seen at two centers therefore contributes to
     * both per-center rows and to the single all-centers row, which is why the
     * dashboard never sums the per-center rows back into the total.
     *
     * <p>A single-center bucket is not a per-location bucket: it is one bucket
     * over the center's whole subtree, so a person registered in the period and
     * seen in the center and its pharmacy is still counted once.
     */
    private static void registrations(Date from, Date to, Set<Integer> locationIds, ReportFilter filter, boolean perLocation,
            int bucketId, FactSink sink) {
        StringBuilder sql = new StringBuilder("SELECT DATE(p.date_created) d, ").append(locationColumn(perLocation, bucketId))
                .append(" loc, COUNT(DISTINCT p.person_id) n FROM patient pt JOIN person p ON p.person_id = pt.patient_id ");
        if (perLocation) {
            // The encounter join is what makes e.location_id addressable in the
            // select list. It carries no date restriction, so a person is counted
            // for every center they were seen at, once each.
            sql.append("JOIN encounter e ON e.patient_id = p.person_id AND e.voided = 0");
            if (locationIds != null && !locationIds.isEmpty()) {
                sql.append(" AND e.location_id IN (:locIds)");
            }
            if (filter != null) {
                sql.append(filter.sqlFragment());
            }
        }
        sql.append(" WHERE p.voided = 0 AND p.date_created >= :from AND p.date_created < :to");
        if (!perLocation) {
            // A single bucket still has to be restricted to the selection it
            // stands for: a health center's bucket is that center's subtree, so
            // its registrations are the people registered in the period who were
            // seen in that subtree under the report's filter, and the all-centers
            // bucket of a filtered report is only the registrations of people the
            // filter selects.
            sql.append(seenIn("p.person_id", locationIds, filter));
        }
        sql.append(groupByDay(perLocation));
        for (Object[] r : query(sql.toString(), from, to, locationIds, filter, null)) {
            sink.add(kpi(r[0], r[1], KEY_NEW_REGISTRATIONS, LABEL_NEW_REGISTRATIONS, r[2]));
        }
    }

    /** Conditions recorded, dated by onset where the clinician gave one. */
    private static void conditions(Date from, Date to, Set<Integer> locationIds, ReportFilter filter, boolean perLocation,
            int bucketId, FactSink sink) {
        String when = "COALESCE(c.onset_date, c.date_created)";
        StringBuilder sql = new StringBuilder("SELECT DATE(").append(when).append(") d, ").append(locationColumn(perLocation, bucketId))
                .append(" loc, COUNT(DISTINCT c.condition_id) n FROM conditions c ");
        if (perLocation) {
            // The encounter join is what makes e.location_id addressable in the
            // select list, so it is only worth paying for when a fact is being
            // attributed to a location. Patient-level facts carry no location and
            // the per-patient filter is applied as an EXISTS instead, which keeps
            // the location restriction a plain WHERE either way.
            sql.append("JOIN encounter e ON e.patient_id = c.patient_id AND e.voided = 0");
            if (locationIds != null && !locationIds.isEmpty()) {
                sql.append(" AND e.location_id IN (:locIds)");
            }
            if (filter != null) {
                sql.append(filter.sqlFragment());
            }
        }
        sql.append(" WHERE c.voided = 0 AND ").append(when).append(" >= :from AND ").append(when).append(" < :to");
        if (!perLocation) {
            // The same restriction as for registrations, and for the same reason:
            // a bucket stands for a selection, and the selection reaches people
            // through their encounters.
            sql.append(seenIn("c.patient_id", locationIds, filter));
        }
        sql.append(groupByDay(perLocation));
        for (Object[] r : query(sql.toString(), from, to, locationIds, filter, null)) {
            sink.add(kpi(r[0], r[1], KEY_CONDITIONS, LABEL_CONDITIONS, r[2]));
        }
    }

    private static void byType(Date from, Date to, Set<Integer> locationIds, ReportFilter filter, List<Integer> whitelist,
            boolean perLocation, int bucketId, FactSink sink) {
        StringBuilder sql = new StringBuilder("SELECT DATE(e.encounter_datetime) d, ").append(locationColumn(perLocation, bucketId))
                .append(" loc, et.name, COUNT(e.encounter_id) n FROM encounter e JOIN encounter_type et "
                        + "ON et.encounter_type_id = e.encounter_type WHERE ")
                .append(encounterWhere(from, to, locationIds, filter));
        String busiestFrom = "SELECT e.encounter_type FROM encounter e"
                + " JOIN encounter_type t ON t.encounter_type_id = e.encounter_type WHERE "
                + encounterWhere(from, to, locationIds, filter) + " GROUP BY e.encounter_type ORDER BY COUNT(*) DESC"
                + " LIMIT " + MAX_UNFILTERED_TYPES;
        String subquery = " AND e.encounter_type IN (SELECT encounter_type FROM (" + busiestFrom + ") busiest)";
        if (whitelist != null && !whitelist.isEmpty()) {
            // A report that names its encounter types restricts the chart to
            // exactly those, rather than to the busiest of them.
            subquery = " AND e.encounter_type IN (:whitelist)";
        }
        sql.append(subquery);
        sql.append(groupByDay(perLocation)).append(perLocation ? ", e.location_id, et.name" : ", et.name");
        for (Object[] r : query(sql.toString(), from, to, locationIds, filter, whitelist)) {
            String name = str(r[2]);
            sink.add(new Fact(date(r[0]), number(r[1]), DIM_TYPE, name, name, name, number(r[3])));
        }
    }

    private static void bySex(Date from, Date to, Set<Integer> locationIds, ReportFilter filter, boolean perLocation,
            int bucketId, FactSink sink) {
        String sql = "SELECT DATE(e.encounter_datetime) d, " + locationColumn(perLocation, bucketId) + " loc, p.gender, "
                + "COUNT(DISTINCT e.patient_id) n FROM encounter e JOIN person p ON p.person_id = e.patient_id WHERE "
                + encounterWhere(from, to, locationIds, filter) + groupByDay(perLocation)
                + (perLocation ? ", e.location_id, p.gender" : ", p.gender");
        for (Object[] r : query(sql, from, to, locationIds, filter, null)) {
            String label = sexLabel(r[2]);
            sink.add(new Fact(date(r[0]), number(r[1]), DIM_SEX, label, label, label, number(r[3])));
        }
    }

    private static void byAge(Date from, Date to, Set<Integer> locationIds, ReportFilter filter, boolean perLocation,
            int bucketId, FactSink sink) {
        // The band is taken as of the encounter day, not as of the end of the
        // range: someone who turns 5 during a twelve-month range was under five
        // for part of it, and a chart that silently re-buckets them hides that.
        String day = "DATE(e.encounter_datetime)";
        String years = "TIMESTAMPDIFF(YEAR, p.birthdate, " + day + ")";
        String band = "CASE WHEN p.birthdate IS NULL THEN 'Unknown' "
                + "WHEN p.birthdate > " + day + " THEN '0-4' "
                + "WHEN " + years + " < 5 THEN '0-4' "
                + "WHEN " + years + " < 10 THEN '5-9' "
                + "WHEN " + years + " < 15 THEN '10-14' "
                + "WHEN " + years + " < 20 THEN '15-19' "
                + "WHEN " + years + " < 25 THEN '20-24' "
                + "WHEN " + years + " < 35 THEN '25-34' "
                + "WHEN " + years + " < 50 THEN '35-49' "
                + "ELSE '50+' END";
        String sql = "SELECT " + day + " d, " + locationColumn(perLocation, bucketId) + " loc, " + band + " band, "
                + "COUNT(DISTINCT e.patient_id) n FROM encounter e JOIN person p ON p.person_id = e.patient_id WHERE "
                + encounterWhere(from, to, locationIds, filter) + groupByDay(perLocation)
                + (perLocation ? ", e.location_id, band" : ", band");
        for (Object[] r : query(sql, from, to, locationIds, filter, null)) {
            String label = str(r[2]);
            sink.add(new Fact(date(r[0]), number(r[1]), DIM_AGE, label, label, label, number(r[3])));
        }
    }

    public static String sexLabel(Object gender) {
        String g = gender == null ? "" : String.valueOf(gender);
        if ("F".equals(g)) {
            return "Female";
        }
        return "M".equals(g) ? "Male" : "Not recorded";
    }

    private static Fact kpi(Object day, Object location, String key, String label, Object value) {
        return new Fact(date(day), number(location), DIM_KPI, null, key, label, number(value));
    }

    /**
     * The WHERE clause shared by the encounter-driven queries. The REPORT TYPE
     * selection is applied here so it reaches every dimension alike, and the
     * health-center restriction is the subtree the dashboard resolved for the
     * selected center - which is what makes "Falaba CHC" include the wards and
     * the pharmacy under it.
     */
    private static String encounterWhere(Date from, Date to, Set<Integer> locationIds, ReportFilter filter) {
        StringBuilder sb = new StringBuilder("e.voided = 0 AND e.encounter_datetime >= :from AND e.encounter_datetime < :to");
        if (locationIds != null && !locationIds.isEmpty()) {
            sb.append(" AND e.location_id IN (:locIds)");
        }
        if (filter != null) {
            sb.append(filter.sqlFragment());
        }
        return sb.toString();
    }

    /**
     * The restriction that reaches a patient-level fact through the person's
     * encounters, as an EXISTS.
     *
     * <p>Needed whenever the fact is collapsed into a single bucket: a
     * registration or a condition is attributed to no location of its own, so
     * the health center it belongs to, and the report it counts towards, are
     * both read off the encounters behind it. Empty when neither a health center
     * nor a report restricts anything, which is All Encounter unfiltered - the
     * one case where the old query counted every person created in the period
     * regardless of whether they had been seen at all.
     */
    private static String seenIn(String patientColumn, Set<Integer> locationIds, ReportFilter filter) {
        if ((locationIds == null || locationIds.isEmpty()) && filter == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" AND EXISTS (SELECT 1 FROM encounter e WHERE e.voided = 0");
        sb.append(" AND e.patient_id = ").append(patientColumn);
        if (locationIds != null && !locationIds.isEmpty()) {
            sb.append(" AND e.location_id IN (:locIds)");
        }
        if (filter != null) {
            sb.append(filter.sqlFragment());
        }
        return sb.append(")").toString();
    }

    private static String locationColumn(boolean perLocation, int bucketId) {
        return perLocation ? "e.location_id" : Integer.toString(bucketId);
    }

    private static String groupByDay(boolean perLocation) {
        return " GROUP BY d" + (perLocation ? ", e.location_id" : "");
    }

    private static List<Object[]> query(String sql, Date from, Date to, Set<Integer> locationIds, ReportFilter filter,
            List<Integer> whitelist) {
        Session sess = session();
        try {
            NativeQuery q = sess.createNativeQuery(sql);
            q.setParameter("from", from);
            q.setParameter("to", to);
            // Only bind what the query actually mentions: a query that needs no
            // location restriction has no :locIds, and binding one anyway is an
            // error rather than a no-op.
            if (sql.contains(":locIds") && locationIds != null && !locationIds.isEmpty()) {
                q.setParameterList("locIds", locationIds);
            }
            if (filter != null) {
                filter.bind(sql, q);
            }
            if (whitelist != null && !whitelist.isEmpty()) {
                q.setParameterList("whitelist", whitelist);
            }
            List<?> raw = q.list();
            List<Object[]> out = new ArrayList<Object[]>(raw.size());
            for (Object r : raw) {
                out.add((Object[]) r);
            }
            return out;
        } finally {
            sess.close();
        }
    }

    static Session session() {
        return sessionFactory().openSession();
    }

    static SessionFactory sessionFactory() {
        return Context.getRegisteredComponent("sessionFactory", SessionFactory.class);
    }

    static Date date(Object o) {
        return o instanceof Date ? (Date) o : null;
    }

    static int number(Object o) {
        return o == null ? Fact.ALL_LOCATIONS : ((Number) o).intValue();
    }

    static long longValue(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
