/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests the CSV document writer: RFC 4180 quoting, whole-cell fidelity (an export never elides),
 * and the formula guard that keeps a cell like {@code =B5+1} data rather than an expression.
 */
public class CsvTest {
	
	@Test
	public void format_shouldWritePlainRowsAsLowerCase() {
		String csv = Csv.format(Arrays.asList("a", "b"), Arrays.asList(Arrays.asList("1", "x")));
		assertEquals("a,b\r\n1,x\r\n", csv);
	}
	
	@Test
	public void format_shouldQuoteCommasQuotesAndNewlines() {
		String csv = Csv.format(null, Arrays.asList(Arrays.asList("a,b", "say \"hi\"", "two\nlines")));
		assertTrue(csv.contains("\"a,b\""));
		assertTrue(csv.contains("\"say \"\"hi\"\"\""));
		assertTrue(csv.contains("\"two\nlines\""));
	}
	
	@Test
	public void format_shouldReportNullsAsEmptyCells() {
		String csv = Csv.format(Arrays.asList("a", "b"), Arrays.asList(Arrays.asList("x", null), Arrays.asList(null, "y")));
		assertTrue(csv.contains("x,\r\n"));
		assertTrue(csv.contains(",y\r\n"));
	}
	
	@Test
	public void format_shouldGuardFormulaHeadedCellsAndLeaveNegativesAlone() {
		String csv = Csv.format(null, Collections.singletonList(Arrays.asList("=cmd", "+1", "@ref", "-5", "plain")));
		assertTrue(csv.contains("'=cmd"));
		assertTrue(csv.contains("'+1"));
		assertTrue(csv.contains("'@ref"));
		// A negative number is data, not a formula, and must stay as typed.
		assertTrue(csv.contains("-5"));
		assertFalse(csv.contains("'-5"));
		assertTrue(csv.endsWith("plain\r\n"));
	}
	
	@Test
	public void format_shouldNotDoubleQuoteNumbersOrLabelsTwice() {
		List<String> headers = Arrays.asList("count", "value_numeric");
		String csv = Csv.format(headers, Arrays.asList(Arrays.asList("6", "1.50")));
		assertEquals("count,value_numeric\r\n6,1.50\r\n", csv);
	}
}
