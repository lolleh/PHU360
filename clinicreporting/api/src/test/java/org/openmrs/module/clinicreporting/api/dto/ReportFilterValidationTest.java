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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Calendar;

import org.junit.Test;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;

/**
 * Filter validation is the module's first line of defence against expensive or nonsensical queries,
 * so its edge cases are worth pinning down.
 */
public class ReportFilterValidationTest {
	
	/** @param order "y" for an ordered range, "n" for an inverted one, null to omit that bound */
	private static PatientReportFilter filter(String order, String omit) {
		Calendar start = Calendar.getInstance();
		start.clear();
		start.set(2024, Calendar.JANUARY, 1);
		Calendar end = Calendar.getInstance();
		end.clear();
		end.set(2024, Calendar.DECEMBER, 31);
		if ("n".equals(order)) {
			// Swap the bounds so the range really is inverted.
			Calendar swap = Calendar.getInstance();
			swap.setTimeInMillis(start.getTimeInMillis());
			start.setTimeInMillis(end.getTimeInMillis());
			end.setTimeInMillis(swap.getTimeInMillis());
		}
		PatientReportFilter filter = new PatientReportFilter();
		filter.setDateFrom("from".equals(omit) ? null : start.getTime());
		filter.setDateTo("to".equals(omit) ? null : end.getTime());
		return filter;
	}
	
	@Test
	public void validate_shouldNormaliseTheRangeToWholeDays() {
		PatientReportFilter filter = filter("y", null);
		filter.validate();
		Calendar from = Calendar.getInstance();
		from.setTime(filter.getDateFrom());
		assertEquals(0, from.get(Calendar.HOUR_OF_DAY));
		assertEquals(0, from.get(Calendar.MINUTE));
		Calendar to = Calendar.getInstance();
		to.setTime(filter.getDateTo());
		// The end day must be fully included, otherwise a single-day report returns nothing.
		assertEquals(23, to.get(Calendar.HOUR_OF_DAY));
		assertEquals(59, to.get(Calendar.MINUTE));
	}
	
	@Test
	public void validate_shouldRejectAMissingRange() {
		try {
			filter("y", "from").validate();
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			assertEquals("dateFrom is required", e.getMessage());
		}
	}
	
	@Test
	public void validate_shouldRejectAnInvertedRange() {
		try {
			filter("n", null).validate();
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			assertEquals("dateTo must not be earlier than dateFrom", e.getMessage());
		}
	}
	
	@Test
	public void validate_shouldAcceptASingleDayRange() {
		Calendar day = Calendar.getInstance();
		day.clear();
		day.set(2024, Calendar.JUNE, 15);
		PatientReportFilter filter = new PatientReportFilter();
		filter.setDateFrom(day.getTime());
		filter.setDateTo(day.getTime());
		filter.validate();
		assertEquals(0L, filter.getRangeDays());
	}
	
	@Test
	public void validate_shouldRejectAnExcessiveRange() {
		Calendar start = Calendar.getInstance();
		start.clear();
		start.set(2000, Calendar.JANUARY, 1);
		PatientReportFilter filter = new PatientReportFilter();
		filter.setDateFrom(start.getTime());
		filter.setDateTo(start.getTime());
		filter.validate();
		// A 20-year span is refused, otherwise a single request could scan a lifetime of data.
		try {
			Calendar later = Calendar.getInstance();
			later.clear();
			later.set(2024, Calendar.JANUARY, 1);
			filter.setDateFrom(start.getTime());
			filter.setDateTo(later.getTime());
			filter.validate();
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			// expected
		}
	}
	
	@Test
	public void validate_shouldNormaliseGenderAndBlankText() {
		PatientReportFilter filter = filter("y", null);
		filter.setGender(" f ");
		filter.setQ("   ");
		filter.setProgramUuid("");
		filter.validate();
		assertEquals("F", filter.getGender());
		assertEquals(null, filter.getQ());
		assertEquals(null, filter.getProgramUuid());
	}
	
	@Test
	public void validate_shouldRejectAnUnknownGender() {
		try {
			PatientReportFilter filter = filter("y", null);
			filter.setGender("X");
			filter.validate();
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			// expected
		}
	}
	
	@Test
	public void validate_shouldRejectAnInvertedAgeRange() {
		try {
			PatientReportFilter filter = filter("y", null);
			filter.setMinAgeMonths(100);
			filter.setMaxAgeMonths(10);
			filter.validate();
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			// expected
		}
	}
	
	@Test
	public void getOffset_shouldBeZeroBased() {
		PatientReportFilter filter = filter("y", null);
		filter.setPage(1);
		filter.setSize(25);
		assertEquals(0, filter.getOffset());
		filter.setPage(3);
		assertEquals(50, filter.getOffset());
	}
	
	@Test
	public void validate_shouldRejectAPageBelowOne() {
		try {
			PatientReportFilter filter = filter("y", null);
			filter.setPage(0);
			filter.validate();
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			// expected
		}
	}
}
