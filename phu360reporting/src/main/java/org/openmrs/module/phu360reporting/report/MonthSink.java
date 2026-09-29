package org.openmrs.module.phu360reporting.report;

/**
 * Receives one point of the twelve-month trend.
 *
 * <p>The trend is fed separately from the {@link Fact} stream because it is the
 * one series that is bucketed by month rather than by the requested range: the
 * window is the twelve months ending at the range's end, whatever range was
 * asked for.
 */
public interface MonthSink {

    void add(String yearMonth, long encounters, long patients);
}
