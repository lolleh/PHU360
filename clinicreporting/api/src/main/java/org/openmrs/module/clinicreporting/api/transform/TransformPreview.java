/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of one sandbox preview: a bounded sample of rows plus the statement that produced it.
 * <p>
 * Nothing here is persisted. This is the sandbox phase, where an administrator checks that a
 * cleaning rule produces what they expected before any of it is wired into a dashboard indicator.
 */
public final class TransformPreview {
	
	/** Which path produced this preview, so the UI can label the two differently. */
	public enum Mode {
		/** The declarative pipeline over an allowlisted table. */
		PIPELINE,
		/** The analyst-written statement, which required the raw SQL privilege. */
		RAW
	}
	
	private final Mode mode;
	
	private final String sql;
	
	private final List<String> columns;
	
	private final List<String> labels;
	
	private final List<List<String>> rows;
	
	private final boolean truncated;
	
	private final long elapsedMillis;
	
	public TransformPreview(Mode mode, String sql, List<String> columns, List<String> labels, List<List<String>> rows,
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
	
	/** @return the statement that was run, with parameters left as placeholders */
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
	
	/** @return true when the row cap was reached, so the sample is not the whole result */
	public boolean isTruncated() {
		return truncated;
	}
	
	public long getElapsedMillis() {
		return elapsedMillis;
	}
	
	/**
	 * Not {@code isEmpty()}: "empty" is a reserved EL keyword, so a getter by that name cannot be
	 * referenced from a JSP as ${preview.empty} and the page fails to compile.
	 */
	public boolean isEmptyResult() {
		return rows.isEmpty();
	}
	
	public int getRowCount() {
		return rows.size();
	}
}
