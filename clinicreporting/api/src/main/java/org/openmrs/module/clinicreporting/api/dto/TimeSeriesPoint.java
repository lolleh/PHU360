/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dto;

/**
 * A single point on a report time series. {@code period} is the bucket label produced by
 * {@link Period#label(java.util.Date)}.
 */
public class TimeSeriesPoint {
	
	private String period;
	
	private long count;
	
	private Double secondaryCount;
	
	public TimeSeriesPoint() {
	}
	
	public TimeSeriesPoint(String period, long count) {
		this.period = period;
		this.count = count;
	}
	
	public String getPeriod() {
		return period;
	}
	
	public void setPeriod(String period) {
		this.period = period;
	}
	
	public long getCount() {
		return count;
	}
	
	public void setCount(long count) {
		this.count = count;
	}
	
	/**
	 * Optional companion figure for the same bucket, used for ratios such as "positives out of tests".
	 * Null when the report does not produce one.
	 */
	public Double getSecondaryCount() {
		return secondaryCount;
	}
	
	public void setSecondaryCount(Double secondaryCount) {
		this.secondaryCount = secondaryCount;
	}
	
	public void add(long increment) {
		this.count += increment;
	}
	
	@Override
	public String toString() {
		return period + "=" + count;
	}
}
