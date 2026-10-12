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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a built, parameterised export statement into the plain statement the reporting module can
 * run.
 * <p>
 * {@link TransformRequest#build()} emits every user-supplied value as a {@code :pN} bind
 * placeholder, which is exactly right for a JDBC PreparedStatement but is more than a report
 * definition can carry: reporting's {@code SqlDataSetDefinition} hands its text to a query builder
 * that substitutes {@code $param} names, not positional JDBC binds. Rather than pretend the text is
 * one or the other, this mirrors the same document with each known bind written back as an escaped
 * SQL literal. The values are already typed (a number parses as a number, a date as a date, text is
 * text and is always quoted), so inlining them does not relax the security the sandbox stands on:
 * {@link TransformRequest} already decided these tokens, never a caller, and text is quoted and its
 * quotes doubled, which is the standard, dialect-safe way to keep a literal from becoming syntax.
 */
public final class DataExportReportingSql {
	
	/** A generated bind placeholder, e.g. {@code :p0}. */
	private static final Pattern BIND = Pattern.compile(":p([0-9]+)");
	
	private static final SimpleDateFormat ISO_DATE = new SimpleDateFormat("yyyy-MM-dd");
	
	private DataExportReportingSql() {
	}
	
	/**
	 * @param sql the statement from a {@code TransformRequest.Built}
	 * @param params the bound values keyed by their generated names
	 * @return the same statement with every bind written as a SQL literal
	 * @throws IllegalStateException when the statement names a bind the caller did not provide, which
	 *             would mean the caller and the builder disagreed about the query
	 */
	public static String literalize(String sql, Map<String, Object> params) {
		if (sql == null || sql.trim().isEmpty()) {
			return "";
		}
		if (params == null || params.isEmpty()) {
			return sql;
		}
		Matcher binds = BIND.matcher(sql);
		StringBuilder out = new StringBuilder(sql.length() + 32);
		int cursor = 0;
		while (binds.find()) {
			out.append(sql, cursor, binds.start());
			Object value = params.get("p" + binds.group(1));
			if (value == null) {
				throw new IllegalStateException(
				        "The statement asks for :p" + binds.group(1) + " but no value was bound for it.");
			}
			out.append(literal(value));
			cursor = binds.end();
		}
		out.append(sql, cursor, sql.length());
		return out.toString();
	}
	
	/**
	 * Renders one bound value as SQL. Numbers and booleans are written bare, dates and text are quoted,
	 * and a quote inside text is doubled so it stays data.
	 */
	private static String literal(Object value) {
		if (value instanceof Boolean) {
			return ((Boolean) value).booleanValue() ? "TRUE" : "FALSE";
		}
		if (value instanceof Number) {
			return value.toString();
		}
		if (value instanceof Date) {
			return "'" + ISO_DATE.format((Date) value) + "'";
		}
		return "'" + value.toString().replace("'", "''") + "'";
	}
}
