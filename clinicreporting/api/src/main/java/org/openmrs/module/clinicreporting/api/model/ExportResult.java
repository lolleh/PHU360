/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of running a saved data export: the rows it pulled, the statement it ran, and whether
 * the row limit cut in. It is a bounded result - a preview is bounded by the screen, a download by
 * the export's own row limit, and both by the module's absolute ceiling - so an export can be run
 * often enough that its owner can see what it would return, without ever being a statement that
 * dumps the table.
 */
public final class ExportResult {
	
	/** How the rows were produced, so the UI can label the statement accordingly. */
	public enum Mode {
		/** The allowlisted source, columns, steps and filters of a definition. */
		TABLE,
		/** An analyst-written statement that passed {@code RawQueryGuard}. */
		RAW
	}
	
	private final Mode mode;
	
	private final String sql;
	
	private final List<String> columns;
	
	private final List<String> labels;
	
	private final List<List<String>> rows;
	
	private final boolean truncated;
	
	private final long elapsedMillis;
	
	public ExportResult(Mode mode, String sql, List<String> columns, List<String> labels, List<List<String>> rows,
	    boolean truncated, long elapsedMillis) {
		this.mode = mode;
		this.sql = sql;
		this.columns = Collections.unmodifiableList(new ArrayList<String>(columns));
		this.labels = Collections.unmodifiableList(new ArrayList<String>(labels));
		List<List<String>> copied = new ArrayList<List<String>>();
		for (List<String> row : rows) {
			copied.add(Collections.unmodifiableList(new ArrayList<String>(row)));
		}
		this.rows = Collections.unmodifiableList(copied);
		this.truncated = truncated;
		this.elapsedMillis = elapsedMillis;
	}
	
	public Mode getMode() {
		return mode;
	}
	
	/** @return the statement that ran, with parameters left as placeholders */
	public String getSql() {
		return sql;
	}
	
	public List<String> getColumns() {
		return columns;
	}
	
	/** @return the display label for each column, in the same order as {@link #getColumns()} */
	public List<String> getLabels() {
		return labels;
	}
	
	public List<List<String>> getRows() {
		return rows;
	}
	
	/** @return true when the row limit was reached, so the result is a sample rather than the whole */
	public boolean isTruncated() {
		return truncated;
	}
	
	public long getElapsedMillis() {
		return elapsedMillis;
	}
	
	/**
	 * Not {@code isEmpty()}: "empty" is a reserved EL keyword, so a getter by that name cannot be
	 * referenced from a JSP and the page would fail to compile.
	 */
	public boolean isEmptyResult() {
		return rows.isEmpty();
	}
	
	public int getRowCount() {
		return rows.size();
	}
}
