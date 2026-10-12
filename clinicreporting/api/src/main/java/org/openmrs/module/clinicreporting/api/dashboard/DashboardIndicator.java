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
import java.util.Locale;

/**
 * One indicator on a programme dashboard: what to measure, how to present it, and whether a target
 * has been set for it.
 * <p>
 * Instances are immutable and are built by {@link ProgramIndicatorCatalog} from the definition
 * file, so a dashboard can never see a half-populated indicator.
 */
public class DashboardIndicator {
	
	/**
	 * Chart kinds the page renderer understands. Anything else in the file is rejected at load time
	 * rather than silently falling back, because a typo in a chart name would otherwise produce a table
	 * with no chart and nothing to indicate why.
	 */
	private static final List<String> CHART_KINDS = Collections
	        .unmodifiableList(java.util.Arrays.asList("none", "bar", "hbar", "line", "doughnut"));
	
	private final String id;
	
	private final String label;
	
	private final String description;
	
	private final IndicatorMetric metric;
	
	private final String chartKind;
	
	private final Double target;
	
	private final boolean higherIsBetter;
	
	private final List<String> conceptUuids;
	
	private final List<String> conceptNames;
	
	private final List<String> denominatorConceptUuids;
	
	private final List<String> denominatorConceptNames;
	
	/**
	 * The numeric value {@link IndicatorMetric#LOW_VALUE_COUNT} counts below, or null for every other
	 * measurement.
	 */
	private final Double valueBelow;
	
	DashboardIndicator(String id, String label, String description, IndicatorMetric metric, String chartKind, Double target,
	    boolean higherIsBetter, List<String> conceptUuids, List<String> conceptNames, List<String> denominatorConceptUuids,
	    List<String> denominatorConceptNames, Double valueBelow) {
		this.valueBelow = valueBelow;
		this.id = id;
		this.label = label;
		this.description = description;
		this.metric = metric;
		this.chartKind = chartKind;
		this.target = target;
		this.higherIsBetter = higherIsBetter;
		this.conceptUuids = copy(conceptUuids);
		this.conceptNames = copy(conceptNames);
		this.denominatorConceptUuids = copy(denominatorConceptUuids);
		this.denominatorConceptNames = copy(denominatorConceptNames);
	}
	
	/**
	 * @return the stable identifier used as the table and chart element id in the page
	 */
	public String getId() {
		return id;
	}
	
	/**
	 * @return the title shown to a clinician
	 */
	public String getLabel() {
		return label;
	}
	
	/**
	 * @return a sentence explaining what is counted and over which period, or null
	 */
	public String getDescription() {
		return description;
	}
	
	public IndicatorMetric getMetric() {
		return metric;
	}
	
	/**
	 * @return one of {@code none}, {@code bar}, {@code hbar}, {@code line} or {@code doughnut}
	 */
	public String getChartKind() {
		return chartKind;
	}
	
	/**
	 * @return the target this indicator is measured against, or null when no target is set
	 */
	public Double getTarget() {
		return target;
	}
	
	/**
	 * @return true when a larger figure is the better result, which decides whether the page colours
	 *         progress against the target green or amber
	 */
	public boolean isHigherIsBetter() {
		return higherIsBetter;
	}
	
	/**
	 * @return the concept UUIDs the measurement applies to, never null
	 */
	public List<String> getConceptUuids() {
		return conceptUuids;
	}
	
	/**
	 * @return the concept names the measurement applies to, never null
	 *         <p>
	 *         Names exist because a concept UUID differs between OpenMRS distributions while the name
	 *         of, say, "HIV Status" does not. A UUID is tried first and names are the fallback.
	 */
	public List<String> getConceptNames() {
		return conceptNames;
	}
	
	public List<String> getDenominatorConceptUuids() {
		return denominatorConceptUuids;
	}
	
	/**
	 * @return the ceiling for {@link IndicatorMetric#LOW_VALUE_COUNT}, or null when this measurement
	 *         does not use one
	 */
	public Double getValueBelow() {
		return valueBelow;
	}
	
	public List<String> getDenominatorConceptNames() {
		return denominatorConceptNames;
	}
	
	/**
	 * @return true when the indicator names no concept at all
	 */
	public boolean hasNoConceptReference() {
		return conceptUuids.isEmpty() && conceptNames.isEmpty();
	}
	
	/**
	 * @return true when the indicator names no denominator concept
	 */
	public boolean hasNoDenominatorReference() {
		return denominatorConceptUuids.isEmpty() && denominatorConceptNames.isEmpty();
	}
	
	/**
	 * @return the CSS modifier describing whether the target is being met, for the tile
	 */
	public String getTargetState(Double value) {
		if (target == null || target <= 0 || value == null) {
			return "none";
		}
		boolean met = higherIsBetter ? value >= target : value <= target;
		return met ? "met" : "missed";
	}
	
	/**
	 * @return the value rendered as a percentage, or null when the indicator is not a percentage
	 */
	public static Double asPercent(Long numerator, Long denominator) {
		if (numerator == null || denominator == null || denominator <= 0) {
			return null;
		}
		return (numerator * 100.0) / denominator;
	}
	
	/**
	 * @return the label as a message-code-safe token, so a hand-edited definition cannot inject markup
	 *         into the page
	 */
	public String getDomId() {
		return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
	}
	
	private static List<String> copy(List<String> values) {
		if (values == null || values.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> cleaned = new java.util.ArrayList<>(values.size());
		for (String value : values) {
			if (value != null && !value.trim().isEmpty()) {
				cleaned.add(value.trim());
			}
		}
		return Collections.unmodifiableList(cleaned);
	}
	
	static boolean isSupportedChartKind(String chartKind) {
		return chartKind != null && CHART_KINDS.contains(chartKind.toLowerCase(Locale.ROOT));
	}
	
	static List<String> supportedChartKinds() {
		return CHART_KINDS;
	}
}
