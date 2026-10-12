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
 * Filter for patient and cohort listings.
 */
public class PatientReportFilter extends ReportFilter {
	
	/** Free text matched against preferred patient name and any active identifier. */
	private String q;
	
	/** M or F, case-insensitive. Null means any. */
	private String gender;
	
	private Integer minAgeMonths;
	
	private Integer maxAgeMonths;
	
	/** Restrict to patients actively enrolled in this program. */
	private String programUuid;
	
	/**
	 * Restrict to members of a saved cohort. Core OpenMRS ships the {@code cohort} and
	 * {@code cohort_member} tables, so this needs no cohort module.
	 */
	private String cohortUuid;
	
	/** Restrict to patients whose latest visit falls in the date range. */
	private Boolean seenInRange;
	
	/** Set when a location filter was requested but resolved to a location with no descendants. */
	private Boolean hasNoMatchingLocation;
	
	public String getQ() {
		return q;
	}
	
	public void setQ(String q) {
		this.q = q;
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
	
	public String getCohortUuid() {
		return cohortUuid;
	}
	
	public void setCohortUuid(String cohortUuid) {
		this.cohortUuid = cohortUuid;
	}
	
	public String getProgramUuid() {
		return programUuid;
	}
	
	public void setProgramUuid(String programUuid) {
		this.programUuid = programUuid;
	}
	
	public Boolean getSeenInRange() {
		return seenInRange;
	}
	
	public void setSeenInRange(Boolean seenInRange) {
		this.seenInRange = seenInRange;
	}
	
	public Boolean getHasNoMatchingLocation() {
		return hasNoMatchingLocation;
	}
	
	public void setHasNoMatchingLocation(Boolean hasNoMatchingLocation) {
		this.hasNoMatchingLocation = hasNoMatchingLocation;
	}
	
	@Override
	public PatientReportFilter validate() {
		super.validate();
		if (minAgeMonths != null && minAgeMonths < 0) {
			throw new org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException(
			        "minAgeMonths must not be negative");
		}
		if (maxAgeMonths != null && maxAgeMonths < 0) {
			throw new org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException(
			        "maxAgeMonths must not be negative");
		}
		if (minAgeMonths != null && maxAgeMonths != null && minAgeMonths > maxAgeMonths) {
			throw new org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException(
			        "minAgeMonths must not be greater than maxAgeMonths");
		}
		if (gender != null && !gender.trim().isEmpty()) {
			String normalised = gender.trim().toUpperCase();
			if (!"M".equals(normalised) && !"F".equals(normalised) && !"U".equals(normalised)) {
				throw new org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException(
				        "gender must be one of M, F or U");
			}
			gender = normalised;
		} else {
			gender = null;
		}
		if (q != null && q.trim().isEmpty()) {
			q = null;
		}
		if (programUuid != null && programUuid.trim().isEmpty()) {
			programUuid = null;
		}
		if (cohortUuid != null && cohortUuid.trim().isEmpty()) {
			cohortUuid = null;
		}
		return this;
	}
}
