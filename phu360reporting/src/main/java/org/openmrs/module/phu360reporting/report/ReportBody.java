package org.openmrs.module.phu360reporting.report;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Assembles the dashboard body for one report, preferring the report's own
 * indicator table and computing the range live when the table cannot answer it.
 *
 * <p>The fallback is not optional. A report's table is filled by a background
 * refresh over a rolling window, so a range reaching past that window has to be
 * answered from the database - returning zeros for "no rows yet" would be
 * indistinguishable from a real answer.
 *
 * <p>A request is answered from one bucket: the all-centers figures when no
 * health center is selected, or that center's own figures when one is. The
 * center's bucket was computed over its whole subtree, so a report narrowed to
 * "Falaba CHC" shows the center and the wards and pharmacy under it, and it
 * shows the same figure the live query would.
 *
 * <p>The two paths - a bucket's stored rows, and a live computation - are held
 * to agreeing exactly for the same range, so that which one answered is only a
 * performance question. That is what the per-day facts are for: a range is a sum
 * of days on both sides, and the dimensions are all summed the same way.
 */
public final class ReportBody {

    private static final Log log = LogFactory.getLog(ReportBody.class);

    private ReportBody() {
    }

    /**
     * @param from          inclusive
     * @param toExclusive   exclusive upper bound
     * @param nominalTo     the end date as the user wrote it
     * @param locationIds   the selected health center's subtree, or null for all
     * @param locationName  the selected center's name, or null for all
     * @param typeWhitelist the report's encounter type ids, or null to keep the
     *                      busiest types for a report that names none
     */
    public static Map<String, Object> build(ReportCatalog.Report report, Date from, Date toExclusive, Date nominalTo,
            Set<Integer> locationIds, String locationName, ReportFilter filter, List<Integer> typeWhitelist) {
        boolean allCenters = locationIds == null || locationIds.isEmpty();
        int bucket = allCenters ? Fact.ALL_LOCATIONS : IndicatorRefresher.centerFor(locationIds);
        // A selection that is not one of the buckets the refresh writes has no
        // rows of its own, and reading some other bucket's rows would answer a
        // facility's question with the district's figures.
        boolean stored = allCenters || bucket != Fact.ALL_LOCATIONS;
        Date prevFrom = previousFrom(from, toExclusive);
        Date trendFrom = trendFrom(nominalTo);
        if (stored && IndicatorTableStore.exists(report.getKey()) && IndicatorTableStore.covers(from, toExclusive)
                && IndicatorTableStore.covers(prevFrom, from) && IndicatorTableStore.covers(trendFrom, toExclusive)) {
            MapFactSink sink = new MapFactSink(nominalTo, true);
            for (Fact f : IndicatorTableStore.read(report.getKey(), from, toExclusive, bucket, false)) {
                sink.add(f);
            }
            for (Fact f : IndicatorTableStore.read(report.getKey(), prevFrom, from, bucket, true)) {
                sink.addPrevious(f);
            }
            for (String[] point : IndicatorTableStore.readTrend(report.getKey(), trendFrom, toExclusive, bucket)) {
                sink.add(point[0], Long.parseLong(point[1]), Long.parseLong(point[2]));
            }
            log.info("Reporting: " + report.getKey() + " " + fmt(from) + ".." + fmt(nominalTo) + " served from "
                    + ReportTables.forReport(report.getKey()) + " bucket " + bucket);
            return decorate(sink.build(), from, nominalTo, report, locationName);
        }
        log.info("Reporting: " + report.getKey() + " " + fmt(from) + ".." + fmt(nominalTo)
                + " is outside the stored window, computed live");
        return live(report, from, toExclusive, nominalTo, locationIds, locationName, filter, typeWhitelist);
    }

    private static Map<String, Object> live(ReportCatalog.Report report, Date from, Date toExclusive, Date nominalTo,
            Set<Integer> locationIds, String locationName, ReportFilter filter, List<Integer> typeWhitelist) {
        Date prevFrom = previousFrom(from, toExclusive);
        MapFactSink sink = new MapFactSink(nominalTo, true);
        // The same bucket the table would have answered with: one figure over the
        // whole selection rather than one per encounter location. The daily facts
        // for the selection's own locations are added up by the sink, which is
        // what the stored rows for that bucket are, so both paths agree.
        // The same whitelist the refresh used, so the type chart is the same
        // whichever path answered.
        IndicatorFacts.compute(from, toExclusive, locationIds, filter, typeWhitelist, Fact.ALL_LOCATIONS, sink);
        IndicatorFacts.byLocation(from, toExclusive, locationIds, filter, Fact.ALL_LOCATIONS, sink);
        // The previous period is measured over the same selection, so the delta
        // compares like with like.
        IndicatorFacts.compute(prevFrom, from, locationIds, filter, typeWhitelist, Fact.ALL_LOCATIONS,
                new PreviousSink(sink));
        IndicatorFacts.monthTrend(toExclusive, locationIds, filter, sink);
        return decorate(sink.build(), from, nominalTo, report, locationName);
    }

    /** The equally long window immediately before the requested one. */
    private static Date previousFrom(Date from, Date toExclusive) {
        return new Date(from.getTime() - (toExclusive.getTime() - from.getTime()));
    }

    /** The start of the twelve-month trend window that ends at {@code nominalTo}. */
    static Date trendFrom(Date nominalTo) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(nominalTo);
        cal.add(Calendar.MONTH, -12);
        cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTime();
    }

    private static Map<String, Object> decorate(Map<String, Object> body, Date from, Date nominalTo,
            ReportCatalog.Report report, String locationName) {
        body.put("from", fmt(from));
        body.put("to", fmt(nominalTo));
        body.put("nominalTo", fmt(nominalTo));
        body.put("locationName", locationName);
        body.put("report", report.getKey());
        body.put("reportName", report.getLabel());
        body.put("reportMapped", Boolean.TRUE);
        return body;
    }

    private static String fmt(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd").format(d);
    }

    /** Feeds a second computation run into the sink's previous-period slot. */
    private static final class PreviousSink implements FactSink {
        private final MapFactSink target;

        PreviousSink(MapFactSink target) {
            this.target = target;
        }

        public void add(Fact fact) {
            target.addPrevious(fact);
        }
    }
}
