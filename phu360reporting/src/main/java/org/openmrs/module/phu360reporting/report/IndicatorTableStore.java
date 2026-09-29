package org.openmrs.module.phu360reporting.report;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.NativeQuery;

/**
 * Reads and writes the per-report-type indicator tables.
 *
 * <p>Writing replaces a range's rows wholesale, so a refresh can be interrupted
 * and re-run without doubling anything up. Reading sums the stored days, which
 * is what lets the dashboard answer an arbitrary date range: a range that starts
 * mid-month is a sum of days, not a rounded month.
 *
 * <p>Every read is scoped to one bucket - either the all-centers bucket or one
 * health center's - because a report's table does not hold a set of per-center
 * rows that can legitimately be added up. The per-patient indicators count a
 * person once per center they were seen at, so their per-center figures are not
 * additive; the all-centers figure is computed and stored as its own bucket.
 */
public final class IndicatorTableStore {

    private IndicatorTableStore() {
    }

    /** True when the table for a report exists - the module may be mid-upgrade. */
    public static boolean exists(String reportKey) {
        String table = ReportTables.forReport(reportKey);
        if (table == null) {
            return false;
        }
        Session sess = IndicatorFacts.session();
        try {
            List<?> rows = sess.createNativeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = :t")
                    .setParameter("t", table).list();
            return IndicatorFacts.longValue(scalar(rows.get(0))) > 0L;
        } finally {
            sess.close();
        }
    }

    /**
     * True when a range falls inside the rolling window the refresh keeps facts
     * for.
     *
     * <p>This is what decides whether the table can answer a request, rather than
     * "are there any rows in it": a period with no encounters legitimately stores
     * no rows, and treating that as missing data would send every quiet period
     * back to a live query - and report a real zero as an error.
     */
    public static boolean covers(Date from, Date to) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        Calendar end = Calendar.getInstance();
        end.setTime(today.getTime());
        end.add(Calendar.DAY_OF_MONTH, 1);
        Calendar start = Calendar.getInstance();
        start.setTime(end.getTime());
        start.add(Calendar.DAY_OF_MONTH, -IndicatorRefresher.REFRESH_WINDOW_DAYS);
        return !from.before(start.getTime()) && !to.after(end.getTime());
    }

    /**
     * Replaces one bucket's facts in {@code [from, to)} for a report's table.
     *
     * <p>The delete is scoped to the bucket as well as the range: a refresh writes
     * the health centers and then the all-centers bucket, and an unscoped delete
     * would have each one erase the previous one's rows.
     *
     * @return how many rows were written
     */
    public static int write(String reportKey, List<Fact> facts, int bucketId, Date from, Date to) {
        String table = ReportTables.forReport(reportKey);
        if (table == null) {
            return 0;
        }
        Date computedAt = new Date();
        // One transaction per bucket: the range is replaced as a unit, so a
        // refresh interrupted part way leaves either the old rows or the new ones,
        // never a half-written mixture. These are aggregates, so there is no
        // patient-level rollback to lose either way.
        Session sess = IndicatorFacts.session();
        Transaction tx = sess.beginTransaction();
        try {
            // The delete runs even when there is nothing to write: an empty
            // result means nothing happened in that period, and the rows a
            // previous refresh left for it are now wrong.
            sess.createNativeQuery("DELETE FROM " + table
                    + " WHERE period_start >= :from AND period_start < :to AND location_id = :bucket")
                    .setParameter("from", from).setParameter("to", to)
                    .setParameter("bucket", Integer.valueOf(bucketId)).executeUpdate();
            int written = 0;
            for (Fact f : facts) {
                NativeQuery q = sess.createNativeQuery("INSERT INTO " + table
                        + " (period_start, location_id, dimension, dimension_value, indicator_key, indicator_label,"
                        + " indicator_value, computed_at)"
                        + " VALUES (:day, :loc, :dim, :dimValue, :key, :label, :value, :at)");
                q.setParameter("day", f.getDay());
                q.setParameter("loc", Integer.valueOf(f.getLocationId()));
                q.setParameter("dim", f.getDimension());
                q.setParameter("dimValue", f.getDimensionValue());
                q.setParameter("key", f.getKey());
                q.setParameter("label", f.getLabel());
                q.setParameter("value", Long.valueOf(f.getValue()));
                q.setParameter("at", computedAt);
                written += q.executeUpdate();
            }
            tx.commit();
            return written;
        } catch (RuntimeException e) {
            tx.rollback();
            throw e;
        } finally {
            sess.close();
        }
    }

    /** Empties a report's table, for a report whose mapping has gone away. */
    public static int clear(String reportKey) {
        String table = ReportTables.forReport(reportKey);
        if (table == null) {
            return 0;
        }
        Session sess = IndicatorFacts.session();
        Transaction tx = sess.beginTransaction();
        try {
            int deleted = sess.createNativeQuery("DELETE FROM " + table).executeUpdate();
            tx.commit();
            return deleted;
        } catch (RuntimeException e) {
            tx.rollback();
            throw e;
        } finally {
            sess.close();
        }
    }

    /**
     * Reads a bucket's facts for a range, summed over the stored days.
     *
     * <p>An empty result is a real answer - nothing happened in that period and
     * for that bucket - and is returned as such. The caller decides what the
     * table can answer from {@link #covers}.
     *
     * @param bucketId   {@link Fact#ALL_LOCATIONS} or one health center's id
     * @param kpisOnly   restrict to the KPI dimension, for the previous period
     */
    public static List<Fact> read(String reportKey, Date from, Date to, int bucketId, boolean kpisOnly) {
        String table = ReportTables.forReport(reportKey);
        if (table == null) {
            return null;
        }
        StringBuilder sql = new StringBuilder("SELECT dimension, dimension_value, indicator_key, indicator_label,"
                + " SUM(indicator_value) v FROM " + table
                + " WHERE period_start >= :from AND period_start < :to AND location_id = :bucket");
        if (kpisOnly) {
            sql.append(" AND dimension = 'kpi'");
        }
        sql.append(" GROUP BY dimension, dimension_value, indicator_key, indicator_label");

        List<Fact> out = new ArrayList<Fact>();
        Session sess = IndicatorFacts.session();
        try {
            NativeQuery q = sess.createNativeQuery(sql.toString());
            q.setParameter("from", from);
            q.setParameter("to", to);
            q.setParameter("bucket", Integer.valueOf(bucketId));
            for (Object o : (List<?>) q.list()) {
                Object[] r = (Object[]) o;
                out.add(new Fact(from, bucketId, IndicatorFacts.str(r[0]), IndicatorFacts.str(r[1]),
                        IndicatorFacts.str(r[2]), IndicatorFacts.str(r[3]), IndicatorFacts.longValue(r[4])));
            }
        } finally {
            sess.close();
        }
        return out;
    }

    /**
     * Reads the trend for a bucket, summed by calendar month.
     *
     * <p>Only the months with rows come back; the sink fills in the rest with
     * zeroes, which is what the months with no encounters are worth.
     */
    public static List<String[]> readTrend(String reportKey, Date from, Date to, int bucketId) {
        String table = ReportTables.forReport(reportKey);
        if (table == null) {
            return null;
        }
        String sql = "SELECT DATE_FORMAT(period_start, '%Y-%m') ym,"
                + " SUM(CASE WHEN indicator_key = 'encounters' THEN indicator_value ELSE 0 END) enc,"
                + " SUM(CASE WHEN indicator_key = 'patientsSeen' THEN indicator_value ELSE 0 END) pats FROM " + table
                + " WHERE dimension = 'kpi' AND period_start >= :from AND period_start < :to"
                + " AND location_id = :bucket GROUP BY ym ORDER BY ym";

        List<String[]> out = new ArrayList<String[]>();
        Session sess = IndicatorFacts.session();
        try {
            NativeQuery q = sess.createNativeQuery(sql);
            q.setParameter("from", from);
            q.setParameter("to", to);
            q.setParameter("bucket", Integer.valueOf(bucketId));
            for (Object o : (List<?>) q.list()) {
                Object[] r = (Object[]) o;
                out.add(new String[] { IndicatorFacts.str(r[0]), String.valueOf(IndicatorFacts.longValue(r[1])),
                        String.valueOf(IndicatorFacts.longValue(r[2])) });
            }
        } finally {
            sess.close();
        }
        return out;
    }

    /** How many days the store has rows for, for diagnostics. */
    public static long rowCount(String reportKey) {
        String table = ReportTables.forReport(reportKey);
        if (table == null) {
            return 0L;
        }
        Session sess = IndicatorFacts.session();
        try {
            List<?> rows = sess.createNativeQuery("SELECT COUNT(*) FROM " + table).list();
            return IndicatorFacts.longValue(scalar(rows.get(0)));
        } finally {
            sess.close();
        }
    }

    private static Object scalar(Object o) {
        return o instanceof Object[] ? ((Object[]) o)[0] : o;
    }
}
