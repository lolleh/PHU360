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
 * Descriptive statistics on the interval between an order being placed and its result being
 * recorded. Computed in Java because date arithmetic in SQL is not portable across the databases
 * OpenMRS supports.
 */
public class TurnaroundSummary {
	
	private long completedOrders;
	
	private Double averageHours;
	
	private Double medianHours;
	
	private Double p90Hours;
	
	private Double minHours;
	
	private Double maxHours;
	
	public long getCompletedOrders() {
		return completedOrders;
	}
	
	public void setCompletedOrders(long completedOrders) {
		this.completedOrders = completedOrders;
	}
	
	public Double getAverageHours() {
		return averageHours;
	}
	
	public void setAverageHours(Double averageHours) {
		this.averageHours = averageHours;
	}
	
	public Double getMedianHours() {
		return medianHours;
	}
	
	public void setMedianHours(Double medianHours) {
		this.medianHours = medianHours;
	}
	
	public Double getP90Hours() {
		return p90Hours;
	}
	
	public void setP90Hours(Double p90Hours) {
		this.p90Hours = p90Hours;
	}
	
	public Double getMinHours() {
		return minHours;
	}
	
	public void setMinHours(Double minHours) {
		this.minHours = minHours;
	}
	
	public Double getMaxHours() {
		return maxHours;
	}
	
	public void setMaxHours(Double maxHours) {
		this.maxHours = maxHours;
	}
}
