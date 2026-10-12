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
 * One row of a patient listing.
 */
public class PatientSummaryRow {
	
	private Integer patientId;
	
	private String patientUuid;
	
	private String display;
	
	private String identifier;
	
	private String gender;
	
	private java.util.Date birthdate;
	
	private Integer ageInMonths;
	
	private String locationName;
	
	private String programName;
	
	private java.util.Date enrollmentDate;
	
	private java.util.Date lastVisit;
	
	public Integer getPatientId() {
		return patientId;
	}
	
	public void setPatientId(Integer patientId) {
		this.patientId = patientId;
	}
	
	public String getPatientUuid() {
		return patientUuid;
	}
	
	public void setPatientUuid(String patientUuid) {
		this.patientUuid = patientUuid;
	}
	
	public String getDisplay() {
		return display;
	}
	
	public void setDisplay(String display) {
		this.display = display;
	}
	
	public String getIdentifier() {
		return identifier;
	}
	
	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}
	
	public String getGender() {
		return gender;
	}
	
	public void setGender(String gender) {
		this.gender = gender;
	}
	
	public java.util.Date getBirthdate() {
		return birthdate;
	}
	
	public void setBirthdate(java.util.Date birthdate) {
		this.birthdate = birthdate;
	}
	
	public Integer getAgeInMonths() {
		return ageInMonths;
	}
	
	public void setAgeInMonths(Integer ageInMonths) {
		this.ageInMonths = ageInMonths;
	}
	
	public String getLocationName() {
		return locationName;
	}
	
	public void setLocationName(String locationName) {
		this.locationName = locationName;
	}
	
	public String getProgramName() {
		return programName;
	}
	
	public void setProgramName(String programName) {
		this.programName = programName;
	}
	
	public java.util.Date getEnrollmentDate() {
		return enrollmentDate;
	}
	
	public void setEnrollmentDate(java.util.Date enrollmentDate) {
		this.enrollmentDate = enrollmentDate;
	}
	
	public java.util.Date getLastVisit() {
		return lastVisit;
	}
	
	public void setLastVisit(java.util.Date lastVisit) {
		this.lastVisit = lastVisit;
	}
}
