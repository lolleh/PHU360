package org.openmrs.module.phu360reporting.report;

/** Receives the facts an {@link IndicatorFacts} run produces. */
public interface FactSink {

    void add(Fact fact);
}
