/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.web.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.Calendar;
import java.util.Date;

import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dto.PatientReportFilter;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;

/**
 * Tests the query-parameter parsing that every controller depends on.
 */
public class BaseReportControllerTest {
	
	@Test
	public void parseDate_shouldReturnMidnightForIsoDate() {
		Date parsed = BaseReportController.parseDate("2024-03-15", "dateFrom");
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(parsed);
		assertEquals(2024, calendar.get(Calendar.YEAR));
		assertEquals(Calendar.MARCH, calendar.get(Calendar.MONTH));
		assertEquals(15, calendar.get(Calendar.DAY_OF_MONTH));
	}
	
	@Test
	public void parseDate_shouldReturnNullForBlankInput() {
		assertNull(BaseReportController.parseDate(null, "dateFrom"));
		assertNull(BaseReportController.parseDate("   ", "dateFrom"));
	}
	
	@Test
	public void parseDate_shouldRejectImpossibleDateRatherThanRollingItOver() {
		// A lenient SimpleDateFormat would turn this into 2024-02-14, silently returning
		// data for the wrong day.
		try {
			BaseReportController.parseDate("2024-13-45", "dateFrom");
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			assertEquals(true, e.getMessage().contains("dateFrom"));
		}
	}
	
	@Test
	public void parseInteger_shouldReturnNullForBlankAndParseDigits() {
		assertNull(BaseReportController.parseInteger(null, "size"));
		assertNull(BaseReportController.parseInteger("", "size"));
		assertEquals(Integer.valueOf(42), BaseReportController.parseInteger("42", "size"));
		assertEquals(Integer.valueOf(-7), BaseReportController.parseInteger(" -7 ", "size"));
	}
	
	@Test
	public void parseInteger_shouldRejectNonNumericValue() {
		try {
			BaseReportController.parseInteger("many", "size");
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			assertEquals(true, e.getMessage().contains("size"));
		}
	}
	
	@Test
	public void parseBoolean_shouldAcceptCommonSpellings() {
		assertEquals(Boolean.TRUE, BaseReportController.parseBoolean("true", "flag"));
		assertEquals(Boolean.TRUE, BaseReportController.parseBoolean("TRUE", "flag"));
		assertEquals(Boolean.TRUE, BaseReportController.parseBoolean("1", "flag"));
		assertEquals(Boolean.FALSE, BaseReportController.parseBoolean("false", "flag"));
		assertEquals(Boolean.FALSE, BaseReportController.parseBoolean("0", "flag"));
		assertNull(BaseReportController.parseBoolean(null, "flag"));
	}
	
	@Test
	public void parseBoolean_shouldRejectOtherValues() {
		try {
			BaseReportController.parseBoolean("yes", "flag");
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			assertEquals(true, e.getMessage().contains("flag"));
		}
	}
	
	@Test
	public void parseText_shouldTrimAndBlankToNull() {
		assertEquals("abc", BaseReportController.parseText("  abc  "));
		assertNull(BaseReportController.parseText("   "));
		assertNull(BaseReportController.parseText(null));
	}
	
	@Test
	public void parseLimit_shouldFallBackWhenAbsent() {
		assertEquals(20, BaseReportController.parseLimit(null, 20));
		assertEquals(5, BaseReportController.parseLimit("5", 20));
	}
	
	@Test
	public void parsePage_shouldDefaultToFirstPage() {
		assertEquals(1, BaseReportController.parsePage(null));
		assertEquals(1, BaseReportController.parsePage(""));
		assertEquals(3, BaseReportController.parsePage("3"));
	}
	
	@Test
	public void withRange_shouldPopulateTheFilter() {
		PatientReportFilter filter = new PatientReportFilter();
		BaseReportController.withRange(filter, "2024-01-01", "2024-01-31");
		assertEquals(true, filter.getDateFrom() != null);
		assertEquals(true, filter.getDateTo() != null);
	}
	
	@Test
	public void missingDateRange_shouldBeRejectedByValidate() {
		PatientReportFilter filter = new PatientReportFilter();
		try {
			filter.validate();
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			assertEquals(true, e.getMessage().contains("dateFrom"));
		}
	}
}
