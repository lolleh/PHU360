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
 * Filter for clinical and register aggregate reports.
 */
public class ClinicalReportFilter extends ReportFilter {
	
	/** Bucket size for time series results. */
	private Period groupBy = Period.MONTH;
	
	/** Restrict to a single encounter type. */
	private String encounterTypeUuid;
	
	/** Restrict to a single concept, e.g. the concept whose observations are being counted. */
	private String conceptUuid;
	
	/** Restrict to patients enrolled in this program. */
	private String programUuid;
	
	public Period getGroupBy() {
		return groupBy;
	}
	
	public void setGroupBy(Period groupBy) {
		this.groupBy = groupBy;
	}
	
	public String getEncounterTypeUuid() {
		return encounterTypeUuid;
	}
	
	public void setEncounterTypeUuid(String encounterTypeUuid) {
		this.encounterTypeUuid = encounterTypeUuid;
	}
	
	public String getConceptUuid() {
		return conceptUuid;
	}
	
	public void setConceptUuid(String conceptUuid) {
		this.conceptUuid = conceptUuid;
	}
	
	public String getProgramUuid() {
		return programUuid;
	}
	
	public void setProgramUuid(String programUuid) {
		this.programUuid = programUuid;
	}
	
	@Override
	public ClinicalReportFilter validate() {
		super.validate();
		if (groupBy == null) {
			groupBy = Period.MONTH;
		}
		// Bucketing happens in Java over a dense range, so an interval that would produce
		// an enormous number of points is rejected instead of exhausting memory.
		long buckets = getRangeDays();
		switch (groupBy) {
			case DAY:
				break;
			case WEEK:
				buckets = buckets / 7 + 1;
				break;
			case MONTH:
			case QUARTER:
				buckets = buckets / 30 + 1;
				break;
			case YEAR:
			default:
				buckets = buckets / 365 + 1;
				break;
		}
		if (buckets > 5000) {
			throw new InvalidReportFilterException(
			        "Date range is too wide to bucket by " + groupBy + "; use a coarser period");
		}
		blankToNull();
		return this;
	}
	
	private void blankToNull() {
		if (encounterTypeUuid != null && encounterTypeUuid.trim().isEmpty()) {
			encounterTypeUuid = null;
		}
		if (conceptUuid != null && conceptUuid.trim().isEmpty()) {
			conceptUuid = null;
		}
		if (programUuid != null && programUuid.trim().isEmpty()) {
			programUuid = null;
		}
	}
}
