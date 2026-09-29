package org.openmrs.module.phu360reporting.report;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Fills the per-report-type indicator tables.
 *
 * <p>One run per report type, emitting two kinds of rows: a bucket per health
 * center, and a single {@link Fact#ALL_LOCATIONS} bucket holding the
 * all-centers figures. The all-centers bucket is not derivable from the
 * per-center rows - the per-patient indicators count a person once per center
 * they were seen at, so summing would double count - and the dashboard reads
 * one or the other, never a mixture.
 *
 * <p>A run writes each day by deleting that day's rows and inserting the new
 * ones, so a refresh that is interrupted or a day whose data was corrected
 * settles on the right numbers the next time round rather than accumulating
 * stale ones.
 */
public final class IndicatorRefresher {

    private static final Log log = LogFactory.getLog(IndicatorRefresher.class);

    /** How often the tables are brought up to date. */
    public static final long REFRESH_INTERVAL_MINUTES = 15;

    /**
     * How far back a refresh keeps facts. A year of days covers the
     * twelve-month trend and a year of deltas, and stays bounded as the database
     * accumulates. A range reaching further back is computed live.
     */
    public static final int REFRESH_WINDOW_DAYS = 400;

    private IndicatorRefresher() {
    }

    /**
     * Refreshes every report type's table over the rolling window.
     *
     * <p>Errors are logged per report rather than thrown, so one report's
     * missing concepts cannot stop the other nine from refreshing.
     */
    public static void refreshAll() {
        Date to = tomorrow();
        Date from = daysBack(to, REFRESH_WINDOW_DAYS);
        for (ReportCatalog.Report report : ReportCatalog.all()) {
            if (!report.isMapped()) {
                // Nothing would ever read this table: the servlet reports a
                // report with no mapping as unavailable rather than serving
                // figures for it. Any rows a build from before the mapping was
                // removed are cleared, so an empty table is the only state that
                // table is ever in.
                int cleared = IndicatorTableStore.clear(report.getKey());
                log.info("Reporting: " + report.getKey() + " has no data mapping, cleared "
                        + cleared + " rows from its table");
                continue;
            }
            if (!IndicatorTableStore.exists(report.getKey())) {
                log.info("Reporting: no table for " + report.getKey() + ", skipping refresh");
                continue;
            }
            try {
                refresh(report, from, to);
            } catch (RuntimeException e) {
                log.warn("Reporting: refresh failed for " + report.getKey(), e);
            }
        }
    }

    /** Refreshes one report type over {@code [from, to)}. */
    public static void refresh(ReportCatalog.Report report, Date from, Date to) {
        ReportFilter.NameResolver resolver = new ContextResolver();
        if (ReportFilter.obsConceptsMissing(report, resolver)) {
            log.info("Reporting: " + report.getKey() + " has no obs concepts in this database, leaving its table empty");
            return;
        }
        ReportFilter filter = ReportFilter.resolve(report, resolver);
        List<Integer> whitelist = report.getEncounterTypeIds(resolver);

        for (Bucket bucket : LocationHierarchy.healthCenters().isEmpty()
                ? java.util.Collections.singletonList(new Bucket(Fact.ALL_LOCATIONS, null))
                : buckets()) {
            List<Fact> facts = new ArrayList<Fact>();
            Sink sink = new Sink(facts);
            IndicatorFacts.compute(from, to, bucket.locationIds, filter, whitelist, bucket.bucketId, sink);
            IndicatorFacts.byLocation(from, to, bucket.locationIds, filter, bucket.bucketId, sink);
            int written = IndicatorTableStore.write(report.getKey(), facts, bucket.bucketId, from, to);
            log.info("Reporting: refreshed " + report.getKey() + " bucket " + bucket.bucketId + " with " + written + " rows");
        }
    }

    /**
     * The buckets a refresh writes: one per health center the dashboard offers,
     * plus the all-centers bucket.
     *
     * <p>Each health center is a bucket of its whole subtree - the center plus the
     * wards and pharmacy under it - so a request narrowed to a center reads one
     * bucket's rows and gets the same figure the live query would produce.
     */
    static List<Bucket> buckets() {
        List<Bucket> out = new ArrayList<Bucket>();
        for (Integer center : LocationHierarchy.healthCenters()) {
            Set<Integer> subtree = LocationHierarchy.subtree(center.intValue());
            out.add(new Bucket(center.intValue(), subtree));
        }
        out.add(new Bucket(Fact.ALL_LOCATIONS, null));
        return out;
    }

    /**
     * The health center a set of locations belongs to, which is the bucket its
     * figures are stored under.
     *
     * <p>The dashboard filter offers the health centers, so a request's subtree is
     * that center plus its descendants and the center is the one that is both in
     * the set and an offered bucket. Returns {@link Fact#ALL_LOCATIONS} if it is
     * not, so an unexpected selection is answered from the all-centers bucket
     * rather than from nothing.
     */
    static int centerFor(Set<Integer> locationIds) {
        for (Integer center : LocationHierarchy.healthCenters()) {
            if (locationIds.contains(center)) {
                return center.intValue();
            }
        }
        return Fact.ALL_LOCATIONS;
    }

    static final class Bucket {
        final int bucketId;
        final Set<Integer> locationIds;

        Bucket(int bucketId, Set<Integer> locationIds) {
            this.bucketId = bucketId;
            this.locationIds = locationIds;
        }
    }

    private static final class Sink implements FactSink {
        private final List<Fact> facts;

        Sink(List<Fact> facts) {
            this.facts = facts;
        }

        public void add(Fact fact) {
            facts.add(fact);
        }
    }

    /**
     * Resolves a catalog report's names and ids against the live database.
     *
     * <p>Plain SQL rather than the encounter and concept services: the refresh
     * runs on a background thread with no authenticated user, and the services
     * refuse to answer without one. These are lookups by name and by id, which
     * is what the services would end up doing anyway.
     */
    static final class ContextResolver implements ReportFilter.NameResolver {
        public Integer encounterTypeId(String name) {
            org.hibernate.Session sess = IndicatorFacts.session();
            try {
                List<?> rows = sess.createNativeQuery(
                        "SELECT encounter_type_id FROM encounter_type WHERE name = :name AND retired = 0"
                                + " ORDER BY encounter_type_id LIMIT 1")
                        .setParameter("name", name).list();
                return rows.isEmpty() ? null : Integer.valueOf(((Number) rows.get(0)).intValue());
            } finally {
                sess.close();
            }
        }

        public boolean conceptExists(int conceptId) {
            org.hibernate.Session sess = IndicatorFacts.session();
            try {
                List<?> rows = sess.createNativeQuery("SELECT concept_id FROM concept WHERE concept_id = :id")
                        .setParameter("id", Integer.valueOf(conceptId)).list();
                return !rows.isEmpty();
            } finally {
                sess.close();
            }
        }
    }

    static Date tomorrow() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTime();
    }

    static Date daysBack(Date from, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        cal.add(Calendar.DAY_OF_MONTH, -days);
        return cal.getTime();
    }

}
