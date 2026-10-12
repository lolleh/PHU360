/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dao;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes sandbox previews.
 * <p>
 * The interface takes a finished statement plus its bound parameters rather than a request object,
 * so that the SQL reaching this layer has already been through {@code TransformRequest.build} or
 * {@code RawQueryGuard}. Nothing that arrives here is concatenated further.
 */
public interface TransformDao {
	
	/**
	 * Runs a read-only statement and collects the result.
	 *
	 * @param sql a statement known to be safe, already carrying its row cap
	 * @param params the values to bind, may be empty. The builder emits {@code :p0}, {@code :p1} style
	 *            placeholders so that no caller text reaches the statement.
	 * @return the rows, each already rendered as display strings
	 */
	List<List<String>> runReadOnly(String sql, Map<String, Object> params);
	
	/**
	 * Runs an analyst-written statement and reports what it returned, including the column names.
	 * <p>
	 * The raw path gets its own method rather than reusing {@link #runReadOnly} because only the
	 * database knows what an arbitrary {@code SELECT} is going to be called; the pipeline path already
	 * knows its own output columns.
	 *
	 * @param sql a statement that has been through {@code RawQueryGuard.reject} and
	 *            {@code RawQueryGuard.wrap}, so it is read-only and row-capped
	 * @return the column names as the database reports them, the rendered rows, and whether the row cap
	 *         was reached
	 */
	RawResult runRawReadOnly(String sql);
	
	/**
	 * Runs a read-only export statement, returning cells whole.
	 * <p>
	 * Unlike {@link #runReadOnly}, which renders a server-side preview, an export is written to a file
	 * someone opens in a spreadsheet: a null is reported as an empty cell rather than as a dash, and a
	 * long value is not elided. Everything else is shared with the preview path - the statement is
	 * still allowlist-built, every value is still bound, and the connection is still read-only.
	 *
	 * @param sql a statement known to be safe, already carrying its row cap
	 * @param params the values to bind, may be empty
	 * @param backstop the largest row count the statement may return regardless of its own LIMIT
	 * @return the rows, each cell rendered without elision
	 */
	List<List<String>> runExportReadOnly(String sql, Map<String, Object> params, int backstop);
	
	/**
	 * Runs an analyst-written export statement, reporting columns and rows intact.
	 *
	 * @param sql a statement that has been through {@code RawQueryGuard.reject} and
	 *            {@code RawQueryGuard.wrap}
	 * @param backstop the largest row count the statement may return regardless of its own LIMIT
	 * @return the column names as the database reports them, the intact rows, and whether the row cap
	 *         was reached
	 */
	RawResult runExportRawReadOnly(String sql, int backstop);
	
	/**
	 * Reports which columns this database actually has, keyed by table.
	 * <p>
	 * This is used to keep the picker honest rather than to decide what may be read. OpenMRS core
	 * tables are not schema-identical across versions and distributions, so an allowlist that was
	 * correct on one server can name a column another server does not have. Anything this method cannot
	 * confirm is treated as absent.
	 *
	 * @return table name to its column names, or an empty map when the metadata could not be read
	 */
	Map<String, Set<String>> availableColumns();
	
	/**
	 * A raw query's result: what it returned, and under what names.
	 */
	final class RawResult {
		
		private final List<String> columns;
		
		private final List<List<String>> rows;
		
		private final boolean truncated;
		
		public RawResult(List<String> columns, List<List<String>> rows, boolean truncated) {
			this.columns = columns;
			this.rows = rows;
			this.truncated = truncated;
		}
		
		public List<String> getColumns() {
			return columns;
		}
		
		public List<List<String>> getRows() {
			return rows;
		}
		
		public boolean isTruncated() {
			return truncated;
		}
	}
}
