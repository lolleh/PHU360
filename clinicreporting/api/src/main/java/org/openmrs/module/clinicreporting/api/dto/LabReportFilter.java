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

import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;

/**
 * Filter for LIMS and lab result reports.
 */
public class LabReportFilter extends ReportFilter {
	
	private Period groupBy = Period.MONTH;
	
	/** Restrict to orders of this order type, e.g. the lab order type. */
	private String orderTypeUuid;
	
	/** Restrict to orders for this concept (the test). */
	private String conceptUuid;
	
	/** Restrict to results matching this text or numeric value. */
	private String resultValue;
	
	/** Restrict to results from the given observation status, e.g. FINAL. */
	private String obsStatus;
	
	private String gender;
	
	private Integer minAgeMonths;
	
	private Integer maxAgeMonths;
	
	public Period getGroupBy() {
		return groupBy;
	}
	
	public void setGroupBy(Period groupBy) {
		this.groupBy = groupBy;
	}
	
	public String getOrderTypeUuid() {
		return orderTypeUuid;
	}
	
	public void setOrderTypeUuid(String orderTypeUuid) {
		this.orderTypeUuid = orderTypeUuid;
	}
	
	public String getConceptUuid() {
		return conceptUuid;
	}
	
	public void setConceptUuid(String conceptUuid) {
		this.conceptUuid = conceptUuid;
	}
	
	public String getResultValue() {
		return resultValue;
	}
	
	public void setResultValue(String resultValue) {
		this.resultValue = resultValue;
	}
	
	public String getObsStatus() {
		return obsStatus;
	}
	
	public void setObsStatus(String obsStatus) {
		this.obsStatus = obsStatus;
	}
	
	public String getGender() {
		return gender;
	}
	
	public void setGender(String gender) {
		this.gender = gender;
	}
	
	public Integer getMinAgeMonths() {
		return minAgeMonths;
	}
	
	public void setMinAgeMonths(Integer minAgeMonths) {
		this.minAgeMonths = minAgeMonths;
	}
	
	public Integer getMaxAgeMonths() {
		return maxAgeMonths;
	}
	
	public void setMaxAgeMonths(Integer maxAgeMonths) {
		this.maxAgeMonths = maxAgeMonths;
	}
	
	@Override
	public LabReportFilter validate() {
		super.validate();
		if (groupBy == null) {
			groupBy = Period.MONTH;
		}
		if (minAgeMonths != null && maxAgeMonths != null && minAgeMonths > maxAgeMonths) {
			throw new InvalidReportFilterException("minAgeMonths must not be greater than maxAgeMonths");
		}
		if (gender != null && !gender.trim().isEmpty()) {
			String normalised = gender.trim().toUpperCase();
			if (!"M".equals(normalised) && !"F".equals(normalised) && !"U".equals(normalised)) {
				throw new InvalidReportFilterException("gender must be one of M, F or U");
			}
			gender = normalised;
		} else {
			gender = null;
		}
		blankToNull();
		return this;
	}
	
	private void blankToNull() {
		if (orderTypeUuid != null && orderTypeUuid.trim().isEmpty()) {
			orderTypeUuid = null;
		}
		if (conceptUuid != null && conceptUuid.trim().isEmpty()) {
			conceptUuid = null;
		}
		if (resultValue != null && resultValue.trim().isEmpty()) {
			resultValue = null;
		}
		if (obsStatus != null && obsStatus.trim().isEmpty()) {
			obsStatus = null;
		}
	}
}
