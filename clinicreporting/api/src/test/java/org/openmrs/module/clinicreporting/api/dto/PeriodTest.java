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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Calendar;
import java.util.Date;
import java.util.Map;

import org.junit.Test;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;

public class PeriodTest {
	
	private static Date date(int year, int month, int day) {
		Calendar calendar = Calendar.getInstance();
		calendar.clear();
		calendar.set(year, month - 1, day);
		return calendar.getTime();
	}
	
	@Test
	public void truncate_shouldMoveToTheStartOfTheBucket() {
		assertEquals(date(2024, 3, 1).getTime(), Period.MONTH.truncate(date(2024, 3, 15)).getTime());
		assertEquals(date(2024, 1, 1).getTime(), Period.YEAR.truncate(date(2024, 3, 15)).getTime());
		// Quarter 2 of 2024 starts in April, i.e. month index 3.
		assertEquals(date(2024, 4, 1).getTime(), Period.QUARTER.truncate(date(2024, 5, 20)).getTime());
		assertEquals(date(2024, 7, 1).getTime(), Period.QUARTER.truncate(date(2024, 8, 2)).getTime());
	}
	
	@Test
	public void label_shouldProduceSortableKeys() {
		assertEquals("2024-03-15", Period.DAY.label(date(2024, 3, 15)));
		assertEquals("2024-03", Period.MONTH.label(date(2024, 3, 15)));
		assertEquals("2024", Period.YEAR.label(date(2024, 3, 15)));
		assertEquals("2024-Q2", Period.QUARTER.label(date(2024, 5, 15)));
	}
	
	@Test
	public void expand_shouldProduceADenseSeriesWithNoGaps() {
		Map<String, TimeSeriesPoint> series = Period.MONTH.expand(date(2024, 1, 1), date(2024, 6, 30));
		assertEquals(6, series.size());
		assertTrue(series.containsKey("2024-01"));
		assertTrue(series.containsKey("2024-06"));
	}
	
	@Test
	public void expand_shouldStartAtTheStartOfTheContainingBucket() {
		// A range beginning mid-month still gets a point for that month, otherwise the first
		// period of a report would silently drop data.
		Map<String, TimeSeriesPoint> series = Period.MONTH.expand(date(2024, 3, 17), date(2024, 4, 2));
		assertEquals(2, series.size());
		assertTrue(series.containsKey("2024-03"));
		assertTrue(series.containsKey("2024-04"));
	}
	
	@Test
	public void expand_shouldFillMissingPeriodsWithZero() {
		Map<String, TimeSeriesPoint> series = Period.MONTH.expand(date(2024, 1, 1), date(2024, 3, 31));
		for (TimeSeriesPoint point : series.values()) {
			assertEquals(0L, point.getCount());
		}
	}
	
	@Test
	public void expand_shouldHandleLeapYears() {
		Map<String, TimeSeriesPoint> series = Period.DAY.expand(date(2024, 2, 28), date(2024, 3, 1));
		assertEquals(3, series.size());
		assertTrue(series.containsKey("2024-02-29"));
	}
	
	@Test
	public void expand_shouldReturnASinglePointForASingleDay() {
		assertEquals(1, Period.DAY.expand(date(2024, 5, 5), date(2024, 5, 5)).size());
	}
	
	@Test
	public void parse_shouldAcceptCaseAndWhitespace() {
		assertEquals(Period.MONTH, Period.parse("month", Period.MONTH));
		assertEquals(Period.WEEK, Period.parse("  WEEK  ", Period.MONTH));
	}
	
	@Test
	public void parse_shouldUseTheFallbackWhenAbsent() {
		assertEquals(Period.DAY, Period.parse(null, Period.DAY));
		assertEquals(Period.DAY, Period.parse("  ", Period.DAY));
	}
	
	@Test
	public void parse_shouldRejectAnUnknownPeriod() {
		try {
			Period.parse("fortnight", Period.MONTH);
			fail("Expected an InvalidReportFilterException");
		}
		catch (InvalidReportFilterException e) {
			assertTrue(e.getMessage().contains("fortnight"));
		}
	}
}
