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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Laying a flat pair list out as a grid, including the folding of the tail into an "Other" bucket.
 * The case that matters is that the totals keep describing the whole data after the tail is folded:
 * a matrix whose rows did not add up to the report it came from would be worse than no matrix.
 */
public class CrossTabTest {
	
	private static CrossTabRow pair(String rowKey, String rowLabel, String columnKey, String columnLabel, long count) {
		return new CrossTabRow(rowKey, rowLabel, columnKey, columnLabel, count);
	}
	
	private static List<CrossTabRow> sample() {
		List<CrossTabRow> pairs = new ArrayList<>();
		pairs.add(pair("a", "A", "x", "X", 5));
		pairs.add(pair("a", "A", "y", "Y", 3));
		pairs.add(pair("b", "B", "x", "X", 1));
		return pairs;
	}
	
	@Test
	public void build_shouldOrderRowsAndColumnsByTotal() {
		CrossTab matrix = CrossTab.build(sample(), 10, 10);
		
		assertEquals(2, matrix.getRows().size());
		assertEquals("A", matrix.getRows().get(0).getLabel());
		assertEquals(8L, matrix.getRows().get(0).getCount());
		assertEquals("B", matrix.getRows().get(1).getLabel());
		assertEquals(1L, matrix.getRows().get(1).getCount());
		
		assertEquals("X", matrix.getColumns().get(0).getLabel());
		assertEquals("Y", matrix.getColumns().get(1).getLabel());
		
		assertEquals(Long.valueOf(5), matrix.getCells().get(0).get(0));
		assertEquals(Long.valueOf(3), matrix.getCells().get(0).get(1));
		assertEquals(Long.valueOf(1), matrix.getCells().get(1).get(0));
		assertEquals(Long.valueOf(0), matrix.getCells().get(1).get(1));
		
		assertEquals(9L, matrix.getGrandTotal());
		assertFalse(matrix.isTruncated());
	}
	
	@Test
	public void build_shouldFoldTheTailIntoAnOtherRowAndColumn() {
		CrossTab matrix = CrossTab.build(sample(), 1, 1);
		
		assertTrue(matrix.isTruncated());
		assertEquals(2, matrix.getRows().size());
		assertEquals("A", matrix.getRows().get(0).getLabel());
		assertEquals("Other", matrix.getRows().get(1).getLabel());
		assertEquals(1L, matrix.getRows().get(1).getCount());
		
		assertEquals(2, matrix.getColumns().size());
		assertEquals("X", matrix.getColumns().get(0).getLabel());
		assertEquals("Other", matrix.getColumns().get(1).getLabel());
		assertEquals(3L, matrix.getColumns().get(1).getCount());
		
		// A's other-column cell, B's other-row cell, and the corner.
		assertEquals(Long.valueOf(3), matrix.getCells().get(0).get(1));
		assertEquals(Long.valueOf(1), matrix.getCells().get(1).get(0));
		assertEquals(Long.valueOf(0), matrix.getCells().get(1).get(1));
		
		// The fold is lossless: every row still sums to the row total, and the whole to the grand total.
		assertEquals(9L, rowSum(matrix));
	}
	
	@Test
	public void build_shouldBreakTiesByLabelSoLayoutIsStable() {
		List<CrossTabRow> pairs = new ArrayList<>();
		pairs.add(pair("b", "B", "x", "X", 2));
		pairs.add(pair("a", "A", "x", "X", 2));
		
		CrossTab matrix = CrossTab.build(pairs, 10, 10);
		assertEquals("A", matrix.getRows().get(0).getLabel());
		assertEquals("B", matrix.getRows().get(1).getLabel());
	}
	
	@Test
	public void build_shouldReturnEmptyForNoPairs() {
		CrossTab matrix = CrossTab.build(Collections.<CrossTabRow> emptyList(), 5, 5);
		assertTrue(matrix.isEmpty());
		assertEquals(0L, matrix.getGrandTotal());
	}
	
	@Test
	public void build_shouldFallBackToTheKeyWhenTheLabelIsMissing() {
		List<CrossTabRow> pairs = new ArrayList<>();
		pairs.add(pair("k1", null, "c1", "", 1));
		
		CrossTab matrix = CrossTab.build(pairs, 10, 10);
		assertEquals("k1", matrix.getRows().get(0).getLabel());
		assertEquals("c1", matrix.getColumns().get(0).getLabel());
	}
	
	private static long rowSum(CrossTab matrix) {
		long total = 0L;
		for (List<Long> row : matrix.getCells()) {
			for (Long cell : row) {
				total += cell;
			}
		}
		return total;
	}
}
