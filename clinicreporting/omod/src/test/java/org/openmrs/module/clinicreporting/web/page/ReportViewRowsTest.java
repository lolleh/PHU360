/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.web.page;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.Date;

import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dto.PatientSummaryRow;
import org.openmrs.module.clinicreporting.web.page.PatientReportsController.PatientRow;

/**
 * Tests the presentation wrappers the patient views render.
 * <p>
 * These are pure functions over plain DTOs, so they are tested without a Spring context. The cases
 * that matter clinically are the ones where the underlying data is missing: a null age or a null
 * last-visit date must render as something a clinician can read, never as {@code null} or a raw
 * zero.
 */
public class ReportViewRowsTest {
	
	@Test
	public void ageDisplay_shouldUseMonthsBelowTwoYears() {
		assertEquals("0m", PatientReportsController.ageDisplay(0));
		assertEquals("1m", PatientReportsController.ageDisplay(1));
		assertEquals("23m", PatientReportsController.ageDisplay(23));
	}
	
	@Test
	public void ageDisplay_shouldUseYearsAndMonthsFromTwoYears() {
		assertEquals("2y 0m", PatientReportsController.ageDisplay(24));
		assertEquals("3y 4m", PatientReportsController.ageDisplay(40));
		assertEquals("18y 0m", PatientReportsController.ageDisplay(216));
	}
	
	@Test
	public void ageDisplay_shouldReturnDashWhenAgeIsUnknown() {
		// A patient with no recorded birthdate must not appear to be newborn.
		assertEquals("-", PatientReportsController.ageDisplay(null));
	}
	
	@Test
	public void ageDisplay_shouldReturnDashForNegativeAge() {
		assertEquals("-", PatientReportsController.ageDisplay(-1));
	}
	
	@Test
	public void normaliseGender_shouldAcceptMaleAndFemaleInAnyCase() {
		assertEquals("M", PatientReportsController.normaliseGender("M"));
		assertEquals("F", PatientReportsController.normaliseGender("f"));
		assertEquals("M", PatientReportsController.normaliseGender(" m "));
	}
	
	@Test
	public void normaliseGender_shouldFallBackToNoFilterRatherThanError() {
		// "O" was once offered in the dropdown but the report filter rejects it, so it produced an
		// error page instead of a register. Unrecognised values must quietly mean "any gender".
		assertNull(PatientReportsController.normaliseGender("O"));
		assertNull(PatientReportsController.normaliseGender("U"));
		assertNull(PatientReportsController.normaliseGender("X"));
		assertNull(PatientReportsController.normaliseGender(""));
		assertNull(PatientReportsController.normaliseGender(null));
	}
	
	@Test
	public void patientRow_shouldRenderNullDatesAsDash() {
		PatientRow row = PatientRow.of(new PatientSummaryRow());
		assertEquals("-", row.getBirthdateDisplay());
		assertEquals("-", row.getLastVisitDisplay());
		assertEquals("-", row.getAgeDisplay());
	}
	
	@Test
	public void patientRow_shouldFormatRecordedDates() {
		PatientSummaryRow source = new PatientSummaryRow();
		source.setBirthdate(date(2024, Calendar.MARCH, 15));
		source.setLastVisit(date(2025, Calendar.JULY, 1));
		
		PatientRow row = PatientRow.of(source);
		assertEquals("2024-03-15", row.getBirthdateDisplay());
		assertEquals("2025-07-01", row.getLastVisitDisplay());
	}
	
	private static Date date(int year, int month, int day) {
		Calendar calendar = Calendar.getInstance();
		calendar.clear();
		calendar.set(year, month, day);
		return calendar.getTime();
	}
}
