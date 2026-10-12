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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A two-dimensional aggregate laid out for display: rows and columns of labels, a grid of counts,
 * row and column totals, and a grand total.
 * <p>
 * Built from the flat {@link CrossTabRow} list a DAO returns. Only the busiest rows and columns are
 * kept; the remainder are folded into a single "Other" row and/or column, so the totals continue to
 * describe the whole of the data even though the grid shows only its head. That is what makes the
 * figures on screen add up: the alternative, dropping the tail, would show a matrix whose totals
 * silently disagreed with the report it came from.
 */
public class CrossTab {
	
	/** Rows and columns shown before the tail is folded into "Other". */
	public static final int DEFAULT_MAX_ROWS = 10;
	
	public static final int DEFAULT_MAX_COLUMNS = 8;
	
	private static final String OTHER = "Other";
	
	private final List<BreakdownRow> rows;
	
	private final List<BreakdownRow> columns;
	
	/** {@code cells.get(row).get(column)}, parallel to {@link #rows} and {@link #columns}. */
	private final List<List<Long>> cells;
	
	private final long grandTotal;
	
	private final boolean truncated;
	
	private CrossTab(List<BreakdownRow> rows, List<BreakdownRow> columns, List<List<Long>> cells, long grandTotal,
	    boolean truncated) {
		this.rows = rows;
		this.columns = columns;
		this.cells = cells;
		this.grandTotal = grandTotal;
		this.truncated = truncated;
	}
	
	/**
	 * Lays out the flat pairs as a grid.
	 *
	 * @param pairs the DAO's rows, one per non-empty intersection
	 * @param maxRows the most row labels to show; the rest are folded into an "Other" row
	 * @param maxColumns the most column labels to show; the rest are folded into an "Other" column
	 * @return the grid, never null
	 */
	public static CrossTab build(List<CrossTabRow> pairs, int maxRows, int maxColumns) {
		int rowLimit = maxRows < 1 ? DEFAULT_MAX_ROWS : maxRows;
		int columnLimit = maxColumns < 1 ? DEFAULT_MAX_COLUMNS : maxColumns;
		
		Map<String, String> rowLabels = new LinkedHashMap<>();
		Map<String, Long> rowTotals = new TreeMap<>();
		Map<String, String> columnLabels = new LinkedHashMap<>();
		Map<String, Long> columnTotals = new TreeMap<>();
		Map<String, Map<String, Long>> intersections = new LinkedHashMap<>();
		long grandTotal = 0L;
		
		if (pairs != null) {
			for (CrossTabRow pair : pairs) {
				if (pair == null) {
					continue;
				}
				rowLabels.putIfAbsent(pair.getRowKey(), label(pair.getRowLabel(), pair.getRowKey()));
				columnLabels.putIfAbsent(pair.getColumnKey(), label(pair.getColumnLabel(), pair.getColumnKey()));
				rowTotals.merge(pair.getRowKey(), pair.getCount(), Long::sum);
				columnTotals.merge(pair.getColumnKey(), pair.getCount(), Long::sum);
				intersections.computeIfAbsent(pair.getRowKey(), key -> new LinkedHashMap<>()).merge(pair.getColumnKey(),
				    pair.getCount(), Long::sum);
				grandTotal += pair.getCount();
			}
		}
		
		List<String> rowOrder = busiestFirst(rowTotals, rowLabels);
		List<String> columnOrder = busiestFirst(columnTotals, columnLabels);
		List<String> shownRows = rowOrder.subList(0, Math.min(rowLimit, rowOrder.size()));
		List<String> shownColumns = columnOrder.subList(0, Math.min(columnLimit, columnOrder.size()));
		
		boolean otherRow = shownRows.size() < rowOrder.size();
		boolean otherColumn = shownColumns.size() < columnOrder.size();
		
		List<BreakdownRow> outRows = new ArrayList<>();
		List<List<Long>> outCells = new ArrayList<>();
		long shownRowTotal = 0L;
		long shownColumnTotal = 0L;
		long shownIntersectionTotal = 0L;
		for (String rowKey : shownRows) {
			long countedAcrossShownColumns = 0L;
			List<Long> rowCells = new ArrayList<>();
			for (String columnKey : shownColumns) {
				long value = intersection(intersections, rowKey, columnKey);
				rowCells.add(value);
				countedAcrossShownColumns += value;
				shownIntersectionTotal += value;
			}
			// Whatever this row holds outside the shown columns belongs in the "Other" column, and
			// is computed by subtraction so the row still adds up to its true total.
			if (otherColumn) {
				rowCells.add(rowTotals.get(rowKey) - countedAcrossShownColumns);
			}
			long rowTotal = rowTotals.get(rowKey);
			outRows.add(new BreakdownRow(rowKey, rowLabels.get(rowKey), rowTotal));
			outCells.add(rowCells);
			shownRowTotal += rowTotal;
		}
		for (String columnKey : shownColumns) {
			shownColumnTotal += columnTotals.get(columnKey);
		}
		
		List<BreakdownRow> outColumns = new ArrayList<>();
		for (String columnKey : shownColumns) {
			outColumns.add(new BreakdownRow(columnKey, columnLabels.get(columnKey), columnTotals.get(columnKey)));
		}
		if (otherColumn) {
			outColumns.add(new BreakdownRow("__other__", OTHER, grandTotal - shownColumnTotal));
		}
		
		if (otherRow) {
			long otherRowTotal = grandTotal - shownRowTotal;
			List<Long> otherCells = new ArrayList<>();
			for (String columnKey : shownColumns) {
				long countedInShownRows = 0L;
				for (String rowKey : shownRows) {
					countedInShownRows += intersection(intersections, rowKey, columnKey);
				}
				otherCells.add(columnTotals.get(columnKey) - countedInShownRows);
			}
			if (otherColumn) {
				// The corner: everything not already accounted for by the shown rows or columns.
				otherCells.add(grandTotal - shownRowTotal - shownColumnTotal + shownIntersectionTotal);
			}
			outRows.add(new BreakdownRow("__other__", OTHER, otherRowTotal));
			outCells.add(otherCells);
		}
		
		return new CrossTab(outRows, outColumns, outCells, grandTotal, otherRow || otherColumn);
	}
	
	private static List<String> busiestFirst(Map<String, Long> totals, Map<String, String> labels) {
		List<String> keys = new ArrayList<>(totals.keySet());
		// Ties broken by label so the same data always lays out the same way; otherwise the order
		// of an unordered map would decide which diagnoses made the cut.
		Collections.sort(keys, new Comparator<String>() {
			
			@Override
			public int compare(String left, String right) {
				int byTotal = Long.compare(totals.get(right), totals.get(left));
				if (byTotal != 0) {
					return byTotal;
				}
				return labels.get(left).compareToIgnoreCase(labels.get(right));
			}
		});
		return keys;
	}
	
	private static long intersection(Map<String, Map<String, Long>> intersections, String rowKey, String columnKey) {
		Map<String, Long> row = intersections.get(rowKey);
		if (row == null) {
			return 0L;
		}
		Long value = row.get(columnKey);
		return value == null ? 0L : value;
	}
	
	private static String label(String preferred, String fallback) {
		return preferred == null || preferred.isEmpty() ? fallback : preferred;
	}
	
	public List<BreakdownRow> getRows() {
		return rows;
	}
	
	public List<BreakdownRow> getColumns() {
		return columns;
	}
	
	public List<List<Long>> getCells() {
		return cells;
	}
	
	public long getGrandTotal() {
		return grandTotal;
	}
	
	/** @return true when rows or columns were folded into an "Other" bucket */
	public boolean isTruncated() {
		return truncated;
	}
	
	/** @return true when there is nothing to show */
	public boolean isEmpty() {
		return rows.isEmpty() || columns.isEmpty();
	}
}
