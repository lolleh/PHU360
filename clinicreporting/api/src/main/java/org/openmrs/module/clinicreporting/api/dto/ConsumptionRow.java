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

import java.util.Date;

/**
 * Quantity of one item consumed (dispensed or issued) within a reporting period.
 */
public class ConsumptionRow {
	
	private String drugName;
	
	private String period;
	
	private Double quantity;
	
	private Date periodStart;
	
	public ConsumptionRow() {
	}
	
	public ConsumptionRow(String drugName, String period, Double quantity) {
		this.drugName = drugName;
		this.period = period;
		this.quantity = quantity;
	}
	
	public String getDrugName() {
		return drugName;
	}
	
	public void setDrugName(String drugName) {
		this.drugName = drugName;
	}
	
	public String getPeriod() {
		return period;
	}
	
	public void setPeriod(String period) {
		this.period = period;
	}
	
	public Double getQuantity() {
		return quantity;
	}
	
	public void setQuantity(Double quantity) {
		this.quantity = quantity;
	}
	
	public Date getPeriodStart() {
		return periodStart;
	}
	
	public void setPeriodStart(Date periodStart) {
		this.periodStart = periodStart;
	}
}
