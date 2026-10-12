/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dao.hibernate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openmrs.module.clinicreporting.api.dao.AbstractReportDao;
import org.openmrs.module.clinicreporting.api.dao.TransformDao;
import org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;

/**
 * Runs sandbox previews through the reporting Hibernate session.
 * <p>
 * Both paths go out over JDBC rather than through Hibernate's native-query API, because three of
 * the controls the sandbox needs are only reliably available there: a statement timeout, a row cap
 * that applies to a query the analyst wrote themselves, and the column names the database reports
 * for a result set nobody described in advance.
 * <ul>
 * <li>The builder names its parameters {@code :p0}, {@code :p1} and so on and those are rewritten
 * to positional placeholders here, so the statement the database receives contains no caller text
 * at all.</li>
 * <li>The connection is marked read-only for the duration, so a statement that somehow slipped past
 * {@code RawQueryGuard} still cannot write. A pool or driver that ignores the hint simply costs the
 * guard nothing, which is why the guard is the primary control and this is the second.</li>
 * <li>A query timeout and a row cap are set on every statement. The generated pipeline SQL already
 * carries a {@code LIMIT}; this is the backstop for the raw path, whose author writes their
 * own.</li>
 * </ul>
 */
public class HibernateTransformDao extends AbstractReportDao implements TransformDao {
	
	/** Requested ceiling on a single preview statement. */
	private static final int TIMEOUT_SECONDS = 30;
	
	/** Backstop row cap, applied whatever the statement says. */
	private static final int MAX_ROWS = TransformRequest.MAX_ROWS;
	
	private static final String DISPLAY_TIMESTAMP = "yyyy-MM-dd HH:mm:ss";
	
	/** Rendered in place of a null so "no value" is distinguishable from "column not selected". */
	private static final String NO_VALUE = "—";
	
	/** Longest cell rendered, so one wide text column cannot dominate the preview table. */
	private static final int MAX_CELL = 300;
	
	/**
	 * Matches the placeholders the builder emits. The lookahead keeps {@code :p1} from being matched
	 * inside {@code :p10}.
	 */
	private static final Pattern NAMED_PARAM = Pattern.compile(":p(\\d+)(?![0-9])");
	
	@Override
	public List<List<String>> runReadOnly(String sql, Map<String, Object> params) {
		Map<String, Object> values = params == null ? new LinkedHashMap<String, Object>() : params;
		List<Integer> order = new ArrayList<Integer>();
		String statement = positional(sql, values, order);
		int cap = capOf(statement, MAX_ROWS);
		
		return execute(statement, values, order, cap, false);
	}
	
	@Override
	public RawResult runRawReadOnly(String sql) {
		return executeRaw(sql, capOf(sql, MAX_ROWS), false);
	}
	
	@Override
	public List<List<String>> runExportReadOnly(String sql, Map<String, Object> params, int backstop) {
		Map<String, Object> values = params == null ? new LinkedHashMap<String, Object>() : params;
		List<Integer> order = new ArrayList<Integer>();
		String statement = positional(sql, values, order);
		int cap = capOf(statement, Math.max(1, backstop));
		
		return execute(statement, values, order, cap, true);
	}
	
	@Override
	public RawResult runExportRawReadOnly(String sql, int backstop) {
		return executeRaw(sql, capOf(sql, Math.max(1, backstop)), true);
	}
	
	@Override
	public Map<String, Set<String>> availableColumns() {
		Map<String, Set<String>> found = new LinkedHashMap<String, Set<String>>();
		for (String table : ReportSourceRegistry.all().keySet()) {
			// columnsOf returns lower-cased names and caches per table for the life of the JVM.
			Set<String> columns = columnsOf(table);
			if (columns != null) {
				found.put(table, columns);
			}
		}
		return found;
	}
	
	// ---------------------------------------------------------------- pipeline path
	
	private List<List<String>> execute(final String sql, final Map<String, Object> params, final List<Integer> order,
	        final int cap, final boolean fullCells) {
		return session().doReturningWork(connection -> {
			markReadOnly(connection, true);
			PreparedStatement statement = null;
			ResultSet rs = null;
			try {
				statement = connection.prepareStatement(sql);
				applyLimits(statement, cap);
				for (int i = 0; i < order.size(); i++) {
					bind(statement, i + 1, params.get("p" + order.get(i)));
				}
				rs = statement.executeQuery();
				return readRows(rs, null, fullCells);
			}
			finally {
				closeQuietly(rs);
				closeQuietly(statement);
				markReadOnly(connection, false);
			}
		});
	}
	
	// ---------------------------------------------------------------- raw path
	
	private RawResult executeRaw(final String sql, final int cap, final boolean fullCells) {
		return session().doReturningWork(connection -> {
			markReadOnly(connection, true);
			Statement statement = null;
			ResultSet rs = null;
			try {
				statement = connection.createStatement();
				applyLimits(statement, cap);
				rs = statement.executeQuery(sql);
				List<String> columns = new ArrayList<String>();
				ResultSetMetaData meta = rs.getMetaData();
				int width = meta.getColumnCount();
				for (int i = 1; i <= width; i++) {
					String label = meta.getColumnLabel(i);
					columns.add(label == null || label.trim().isEmpty() ? "col" + i : label.trim());
				}
				List<List<String>> rows = readRows(rs, width, fullCells);
				// +1 so that hitting the cap exactly is reported as truncated rather than as "and there
				// may be more".
				return new RawResult(columns, rows, rows.size() >= cap);
			}
			catch (SQLException e) {
				throw new IllegalStateException("Sandbox query failed: " + e.getMessage(), e);
			}
			finally {
				closeQuietly(rs);
				closeQuietly(statement);
				markReadOnly(connection, false);
			}
		});
	}
	
	// ---------------------------------------------------------------- plumbing
	
	/**
	 * Rewrites {@code :pN} placeholders to JDBC's positional form and records the order they appeared
	 * in, so binding follows the statement rather than the map's iteration order.
	 *
	 * @return the statement with {@code ?} in place of each named parameter
	 */
	private static String positional(String sql, Map<String, Object> params, List<Integer> order) {
		Matcher matcher = NAMED_PARAM.matcher(sql);
		StringBuffer rewritten = new StringBuffer();
		while (matcher.find()) {
			int index = Integer.parseInt(matcher.group(1));
			String name = "p" + index;
			if (!params.containsKey(name)) {
				// The generated SQL and the parameter map are built together, so this cannot normally
				// happen. If it ever does, fail rather than run the statement with a hole in it.
				throw new IllegalStateException("Statement refers to unbound parameter " + name);
			}
			order.add(Integer.valueOf(index));
			matcher.appendReplacement(rewritten, "?");
		}
		matcher.appendTail(rewritten);
		return rewritten.toString();
	}
	
	private static void bind(PreparedStatement statement, int position, Object value) throws SQLException {
		if (value == null) {
			statement.setObject(position, null);
		} else if (value instanceof Number) {
			statement.setObject(position, value);
		} else if (value instanceof Boolean) {
			// setBoolean rather than setInt: the allowlist declares these as booleans, and on PostgreSQL
			// a boolean column has no integer comparison to fall back to. MySQL accepts it either way.
			statement.setBoolean(position, ((Boolean) value).booleanValue());
		} else {
			statement.setString(position, String.valueOf(value));
		}
	}
	
	private static void applyLimits(Statement statement, int cap) throws SQLException {
		statement.setQueryTimeout(TIMEOUT_SECONDS);
		statement.setMaxRows(cap);
		statement.setFetchSize(Math.min(cap, 500));
	}
	
	/**
	 * @return the row cap to enforce: the {@code LIMIT} the builder or guard already wrote, or the
	 *         backstop when there is none
	 */
	private static int capOf(String sql, int backstop) {
		Matcher matcher = Pattern.compile("\\blimit\\s+(\\d+)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE).matcher(sql.trim());
		if (matcher.find()) {
			try {
				int declared = Integer.parseInt(matcher.group(1));
				if (declared > 0) {
					return Math.min(declared, backstop);
				}
			}
			catch (NumberFormatException e) {
				// fall through to the backstop
			}
		}
		return backstop;
	}
	
	private static void markReadOnly(Connection connection, boolean readOnly) {
		try {
			connection.setReadOnly(readOnly);
		}
		catch (SQLException e) {
			// Not every pool or driver honours the hint. The guard still stands on its own, so this is
			// worth carrying on without rather than failing the preview.
		}
	}
	
	/**
	 * Reads a result set as display strings, because a preview mixes dates, numbers and concept text
	 * down a single table of results.
	 *
	 * @param expectedWidth the width to expect, or null to take it from the first row
	 * @param fullCells true for an export, where a row leaves the server as data: null is an empty cell
	 *            and nothing is elided
	 */
	private static List<List<String>> readRows(ResultSet rs, Integer expectedWidth, boolean fullCells) throws SQLException {
		int actual = rs.getMetaData().getColumnCount();
		int width = expectedWidth == null || expectedWidth.intValue() == 0 ? actual : expectedWidth.intValue();
		List<List<String>> rows = new ArrayList<List<String>>();
		while (rs.next()) {
			List<String> row = new ArrayList<String>(width);
			for (int i = 1; i <= actual; i++) {
				row.add(display(rs.getObject(i), fullCells));
			}
			rows.add(row);
		}
		return rows;
	}
	
	private static String display(Object value, boolean fullCells) {
		if (value == null) {
			return fullCells ? "" : NO_VALUE;
		}
		if (value instanceof Date) {
			SimpleDateFormat format = new SimpleDateFormat(DISPLAY_TIMESTAMP, Locale.ROOT);
			return format.format((Date) value);
		}
		if (value instanceof BigDecimal) {
			// Strip trailing zeros so a count reads as 6 rather than 6.0000.
			return ((BigDecimal) value).stripTrailingZeros().toPlainString();
		}
		if (value instanceof byte[]) {
			// A binary column would otherwise render as an array reference.
			return "[binary " + ((byte[]) value).length + " bytes]";
		}
		if (value instanceof Object[]) {
			return Arrays.deepToString((Object[]) value);
		}
		if (fullCells) {
			return String.valueOf(value);
		}
		String text = String.valueOf(value);
		return text.length() > MAX_CELL ? text.substring(0, MAX_CELL - 3) + "..." : text;
	}
	
	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		}
		catch (Exception e) {
			// nothing useful to do while closing
		}
	}
}
