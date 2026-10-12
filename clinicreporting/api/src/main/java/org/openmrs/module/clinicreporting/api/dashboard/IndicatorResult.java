/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dashboard;

import java.util.Collections;
import java.util.List;

import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;

/**
 * One indicator as the page needs it: the definition, plus whatever figures were computed for it.
 * <p>
 * An indicator can fail to produce figures without the dashboard failing with it. The commonest
 * cause is a named concept that this server does not have - a distribution installs its own
 * dictionary, so "HIV Status" may be absent or spelled differently - and the honest thing to show
 * in that case is {@code unavailable} with the reason, never a zero. A zero is a finding; "this
 * concept is not on this server" is a configuration gap, and the two must not look the same.
 */
public class IndicatorResult {
	
	private final DashboardIndicator definition;
	
	private final boolean available;
	
	private final String unavailableReason;
	
	private final Double value;
	
	private final Long numerator;
	
	private final Long denominator;
	
	private final List<BreakdownRow> breakdown;
	
	private final List<TimeSeriesPoint> series;
	
	private final Double change;
	
	private IndicatorResult(DashboardIndicator definition, boolean available, String unavailableReason, Double value,
	    Long numerator, Long denominator, List<BreakdownRow> breakdown, List<TimeSeriesPoint> series, Double change) {
		this.definition = definition;
		this.available = available;
		this.unavailableReason = unavailableReason;
		this.value = value;
		this.numerator = numerator;
		this.denominator = denominator;
		this.breakdown = breakdown == null ? Collections.<BreakdownRow> emptyList() : breakdown;
		this.series = series == null ? Collections.<TimeSeriesPoint> emptyList() : series;
		this.change = change;
	}
	
	/**
	 * @return an indicator whose figures were computed
	 */
	public static IndicatorResult of(DashboardIndicator definition, Double value, Long numerator, Long denominator,
	        List<BreakdownRow> breakdown, List<TimeSeriesPoint> series) {
		return new IndicatorResult(definition, true, null, value, numerator, denominator, breakdown, series, null);
	}
	
	/**
	 * @param reason a sentence saying why there are no figures, shown in place of the chart
	 * @return an indicator this server cannot compute
	 */
	public static IndicatorResult unavailable(DashboardIndicator definition, String reason) {
		return new IndicatorResult(definition, false, reason, null, null, null, null, null, null);
	}
	
	/**
	 * @param change the difference between this period's figure and the one before it, or null when
	 *            either is missing
	 * @return a copy of this result carrying that change, for the page to show as a trend
	 */
	public IndicatorResult withChange(Double change) {
		return new IndicatorResult(definition, available, unavailableReason, value, numerator, denominator, breakdown,
		        series, change);
	}
	
	public DashboardIndicator getDefinition() {
		return definition;
	}
	
	/**
	 * @return true when figures were computed for this indicator
	 */
	public boolean isAvailable() {
		return available;
	}
	
	/**
	 * @return why the indicator could not be computed, or null when it was
	 */
	public String getUnavailableReason() {
		return unavailableReason;
	}
	
	/**
	 * @return the headline figure, or null for a breakdown or an unavailable indicator
	 */
	public Double getValue() {
		return value;
	}
	
	/**
	 * @return the counted part of a rate, or null
	 */
	public Long getNumerator() {
		return numerator;
	}
	
	/**
	 * @return the population a rate was taken over, or null
	 */
	public Long getDenominator() {
		return denominator;
	}
	
	/**
	 * @return the labelled rows a chart or table is drawn from, never null
	 */
	public List<BreakdownRow> getBreakdown() {
		return breakdown;
	}
	
	/**
	 * @return the time series a trend line is drawn from, never null
	 */
	public List<TimeSeriesPoint> getSeries() {
		return series;
	}
	
	/**
	 * @return true when the indicator renders as rows rather than a single figure
	 */
	public boolean isTabular() {
		return definition.getMetric().producesBreakdown();
	}
	
	/**
	 * @return the value as a whole number, for the count tiles
	 */
	public Long getWholeValue() {
		if (value == null) {
			return null;
		}
		return Long.valueOf(Math.round(value.doubleValue()));
	}
	
	/**
	 * @return the value as a percentage with one decimal place, or null when there is no rate
	 */
	public String getPercentDisplay() {
		if (!available || numerator == null || denominator == null || denominator <= 0) {
			return null;
		}
		return String.format(java.util.Locale.ROOT, "%.1f%%", numerator * 100.0 / denominator);
	}
	
	/**
	 * @return the change against the preceding period, or null when it cannot be computed
	 */
	public Double getChange() {
		return change;
	}
	
	/**
	 * @return the change rendered for display, e.g. {@code +12}, or a dash when there is none
	 */
	public String getChangeDisplay() {
		if (!available || change == null) {
			return "-";
		}
		double rounded = Math.round(change);
		if (rounded == 0) {
			return "0";
		}
		return (rounded > 0 ? "+" : "") + Long.toString((long) rounded);
	}
	
	/**
	 * @return "up", "down" or "flat", so the page can colour the change. For an indicator where a lower
	 *         figure is the better result, {@code up} still means "went up" - the CSS decides whether
	 *         that is good news, and the decision belongs with the indicator's own definition rather
	 *         than with the number's sign.
	 */
	public String getChangeDirection() {
		if (!available || change == null || change == 0) {
			return "flat";
		}
		return change > 0 ? "up" : "down";
	}
	
	/**
	 * @return "met", "missed" or "none" for the target tile
	 */
	public String getTargetState() {
		return definition.getTargetState(value);
	}
	
	/**
	 * @return how far along the target this indicator is, as a percentage capped at 100, or null when
	 *         there is no target. Capped so a far-exceeded target does not overflow its bar.
	 */
	public Integer getTargetProgress() {
		Double target = definition.getTarget();
		if (!available || target == null || target <= 0 || value == null) {
			return null;
		}
		double ratio = definition.isHigherIsBetter() ? value / target : target / value;
		if (ratio < 0) {
			return Integer.valueOf(0);
		}
		return Integer.valueOf((int) Math.min(100, Math.round(ratio * 100)));
	}
}
