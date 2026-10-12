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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the administrator asked to preview: a source table, the columns to include, and the cleaning
 * and mapping steps to apply.
 * <p>
 * This is a request description, not a validated query. {@link #build()} is what turns it into SQL,
 * and it is the only thing that decides whether the request is acceptable, so there is one place to
 * audit for "can user input reach a statement".
 */
public final class TransformRequest {
	
	/**
	 * Upper bound on selected columns, so one preview cannot ask the server to build a 500-column row.
	 */
	public static final int MAX_COLUMNS = 25;
	
	/** Upper bound on steps, which bounds how deeply nested the generated expressions can get. */
	public static final int MAX_STEPS = 20;
	
	/** Upper bound on row conditions, which bounds how long the generated statement can get. */
	public static final int MAX_FILTERS = 4;
	
	/** Default and maximum number of preview rows. */
	public static final int MAX_ROWS = 500;
	
	/**
	 * Largest number of rows one data export may ask for. An export is written to a file rather than
	 * glanced at, so it may ask for more than a preview, but it still runs on a clinical server during
	 * working hours and so is capped rather than unbounded.
	 */
	public static final int MAX_EXPORT_ROWS = 20000;
	
	/** Shapes a filter value may take before it is bound as a number, a date or text. */
	private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,18}");
	
	private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+\\.[0-9]+");
	
	private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
	
	private final String table;
	
	private final List<String> columns;
	
	private final List<TransformStep> steps;
	
	private final List<TransformFilter> filters;
	
	private final int limit;
	
	/** The largest row count this request may put in its own {@code LIMIT}. */
	private final int rowCap;
	
	public TransformRequest(String table, List<String> columns, List<TransformStep> steps, int limit) {
		this(table, columns, steps, null, limit, MAX_ROWS);
	}
	
	public TransformRequest(String table, List<String> columns, List<TransformStep> steps, List<TransformFilter> filters,
	    int limit, int rowCap) {
		this.table = table;
		this.columns = columns == null ? Collections.<String> emptyList()
		        : Collections.unmodifiableList(new ArrayList<String>(columns));
		this.steps = steps == null ? Collections.<TransformStep> emptyList()
		        : Collections.unmodifiableList(new ArrayList<TransformStep>(steps));
		this.filters = filters == null ? Collections.<TransformFilter> emptyList()
		        : Collections.unmodifiableList(new ArrayList<TransformFilter>(filters));
		this.limit = limit;
		this.rowCap = rowCap <= 0 ? MAX_ROWS : rowCap;
	}
	
	/**
	 * Builds a request destined for a saved data export rather than for a preview.
	 * <p>
	 * The only difference is the row cap: an export is written to a file, so it may ask for more rows
	 * than a screen full of preview, up to {@link #MAX_EXPORT_ROWS}. Everything else - the allowlist,
	 * the column and step caps, the bound parameters - is identical, so an export cannot read anything
	 * a preview could not.
	 *
	 * @param table the allowlisted source table
	 * @param columns the columns to include
	 * @param steps the cleaning and mapping steps to apply
	 * @param filters the row conditions to apply, may be empty
	 * @param limit the rows to ask for
	 * @return the request, ready to build
	 */
	public static TransformRequest forExport(String table, List<String> columns, List<TransformStep> steps,
	        List<TransformFilter> filters, int limit) {
		return new TransformRequest(table, columns, steps, filters, limit, MAX_EXPORT_ROWS);
	}
	
	public String getTable() {
		return table;
	}
	
	public List<String> getColumns() {
		return columns;
	}
	
	public List<TransformStep> getSteps() {
		return steps;
	}
	
	public List<TransformFilter> getFilters() {
		return filters;
	}
	
	public int getLimit() {
		return limit;
	}
	
	/**
	 * @return the effective row cap, always within bounds
	 */
	public int effectiveLimit() {
		if (limit <= 0) {
			return 100;
		}
		return Math.min(limit, rowCap);
	}
	
	/**
	 * @return the steps that apply to one column, in the order they were added
	 */
	public List<TransformStep> stepsFor(String column) {
		List<TransformStep> matching = new ArrayList<TransformStep>();
		for (TransformStep step : steps) {
			if (column.equals(step.getColumn())) {
				matching.add(step);
			}
		}
		return matching;
	}
	
	/**
	 * @return the steps that would rename this column, and so the display label it should carry
	 */
	public String labelFor(String column, String fallback) {
		String label = fallback;
		for (TransformStep step : stepsFor(column)) {
			if (step.getType() == TransformStep.Type.RENAME && notBlank(step.getArgument())) {
				label = step.getArgument().trim();
			}
		}
		return label;
	}
	
	private static boolean notBlank(String value) {
		return value != null && !value.trim().isEmpty();
	}
	
	/**
	 * Builds the generated statement for this request.
	 * <p>
	 * The result carries the SQL text alongside the bound parameter values, because the sandbox shows
	 * an administrator exactly what it is about to run.
	 */
	public static final class Built {
		
		private final String sql;
		
		private final Map<String, Object> params;
		
		private final List<String> outputColumns;
		
		private final Map<String, String> labels;
		
		Built(String sql, Map<String, Object> params, List<String> outputColumns, Map<String, String> labels) {
			this.sql = sql;
			this.params = Collections.unmodifiableMap(params);
			this.outputColumns = Collections.unmodifiableList(outputColumns);
			this.labels = Collections.unmodifiableMap(labels);
		}
		
		public String getSql() {
			return sql;
		}
		
		public Map<String, Object> getParams() {
			return params;
		}
		
		/** @return the generated column names, in output order */
		public List<String> getOutputColumns() {
			return outputColumns;
		}
		
		/** @return display label per generated column name */
		public Map<String, String> getLabels() {
			return labels;
		}
	}
	
	/**
	 * Turns this request into a parameterised SELECT.
	 * <p>
	 * Everything that reaches the statement is either a constant from this class, an allowlisted
	 * identifier that has been checked against {@link ReportSourceRegistry#isPlainIdentifier}, or a
	 * bound parameter. Steps contribute fixed templates chosen by their own type, so no step
	 * configuration is ever concatenated into SQL as text.
	 *
	 * @return the statement to run
	 * @throws TransformValidationException when the request cannot be satisfied safely; the message is
	 *             written for the administrator who submitted it
	 */
	public Built build() {
		if (notBlank(table) == false) {
			throw new TransformValidationException("Choose a source table.");
		}
		ReportSourceRegistry.Source source = ReportSourceRegistry.find(table.trim());
		if (source == null) {
			// The table is not allowlisted. Do not confirm whether it exists.
			throw new TransformValidationException("That source is not available to the sandbox.");
		}
		if (columns.isEmpty()) {
			throw new TransformValidationException("Choose at least one column.");
		}
		if (columns.size() > MAX_COLUMNS) {
			throw new TransformValidationException("Choose at most " + MAX_COLUMNS + " columns.");
		}
		if (steps.size() > MAX_STEPS) {
			throw new TransformValidationException("Apply at most " + MAX_STEPS + " steps.");
		}
		
		Set<String> seen = new LinkedHashSet<String>();
		for (String column : columns) {
			if (!ReportSourceRegistry.isPlainIdentifier(column)) {
				throw new TransformValidationException("Column name \"" + abbreviate(column) + "\" is not valid.");
			}
			if (!source.allows(column)) {
				throw new TransformValidationException(
				        "Column \"" + abbreviate(column) + "\" is not available from " + source.getLabel() + ".");
			}
			if (!seen.add(column)) {
				throw new TransformValidationException("Column \"" + column + "\" was selected twice.");
			}
		}
		// A step may only touch a column this request actually selects, otherwise its expression
		// would be generated and then discarded.
		for (TransformStep step : steps) {
			if (step.getType() == null) {
				throw new TransformValidationException("A step has no recognised type.");
			}
			if (!notBlank(step.getColumn())) {
				throw new TransformValidationException("A step has no column chosen.");
			}
			if (!seen.contains(step.getColumn())) {
				throw new TransformValidationException("A step targets \"" + abbreviate(step.getColumn())
				        + "\", which is not one of the selected columns.");
			}
		}
		// A filter may narrow the rows without selecting its column: a date range on date_created is
		// worth having even when the export does not want the timestamp in every row. It still has to
		// be an allowlisted column of this source, so a filter can never reach for another table.
		if (filters.size() > MAX_FILTERS) {
			throw new TransformValidationException("Apply at most " + MAX_FILTERS + " filters.");
		}
		for (TransformFilter filter : filters) {
			validateFilter(source, filter);
		}
		
		Map<String, Object> params = new LinkedHashMap<String, Object>();
		List<String> expressions = new ArrayList<String>();
		List<String> outputColumns = new ArrayList<String>();
		Map<String, String> labels = new LinkedHashMap<String, String>();
		Set<String> usedAliases = new LinkedHashSet<String>();
		
		for (String column : columns) {
			String expression = column;
			for (TransformStep step : stepsFor(column)) {
				expression = apply(step, expression, params);
			}
			String alias = uniqueAlias(column, usedAliases);
			expressions.add(expression + " AS " + alias);
			outputColumns.add(alias);
			labels.put(alias, labelFor(column, column));
		}
		
		StringBuilder sql = new StringBuilder("SELECT ");
		sql.append(join(expressions, ", "));
		sql.append(" FROM ").append(source.getTable());
		sql.append(where(params));
		sql.append(" LIMIT ").append(effectiveLimit());
		return new Built(sql.toString(), params, outputColumns, labels);
	}
	
	/**
	 * Checks one row condition before it is allowed near the statement.
	 */
	private static void validateFilter(ReportSourceRegistry.Source source, TransformFilter filter) {
		if (filter.getOperator() == null) {
			throw new TransformValidationException("A filter has no recognised operator.");
		}
		if (!ReportSourceRegistry.isPlainIdentifier(filter.getColumn())) {
			throw new TransformValidationException(
			        "Column name \"" + abbreviate(filter.getColumn()) + "\" used in a filter is not valid.");
		}
		if (!source.allows(filter.getColumn())) {
			throw new TransformValidationException("Column \"" + abbreviate(filter.getColumn())
			        + "\" used in a filter is not available from " + source.getLabel() + ".");
		}
		if (filter.needsValue()) {
			String value = filter.getValue() == null ? "" : filter.getValue().trim();
			if (value.isEmpty()) {
				throw new TransformValidationException("A filter on \"" + filter.getColumn() + "\" needs a value.");
			}
			if (value.length() > TransformFilter.MAX_VALUE_LENGTH) {
				throw new TransformValidationException(
				        "A filter value may be at most " + TransformFilter.MAX_VALUE_LENGTH + " characters.");
			}
			if (filter.getOperator() == TransformFilter.Operator.IN && listValues(value).isEmpty()) {
				throw new TransformValidationException(
				        "An \"is one of\" filter on \"" + filter.getColumn() + "\" needs at least one value.");
			}
		}
	}
	
	/**
	 * @param params receives the bound values
	 * @return the {@code WHERE} clause, or an empty string when no filter was asked for
	 */
	private String where(Map<String, Object> params) {
		if (filters.isEmpty()) {
			return "";
		}
		StringBuilder clause = new StringBuilder(" WHERE ");
		for (int index = 0; index < filters.size(); index++) {
			if (index > 0) {
				clause.append(" AND ");
			}
			clause.append(condition(filters.get(index), params));
		}
		return clause.toString();
	}
	
	/**
	 * Renders one condition. The operator symbol comes from the enum rather than from the form, and
	 * every value is bound, so nothing a person typed reaches the statement as text.
	 */
	private static String condition(TransformFilter filter, Map<String, Object> params) {
		String column = filter.getColumn();
		if (filter.getOperator() == TransformFilter.Operator.IS_NULL) {
			return column + " IS NULL";
		}
		if (filter.getOperator() == TransformFilter.Operator.IS_NOT_NULL) {
			return column + " IS NOT NULL";
		}
		if (filter.getOperator() == TransformFilter.Operator.IN) {
			List<String> values = listValues(filter.getValue());
			StringBuilder clause = new StringBuilder(column).append(" IN (");
			for (int index = 0; index < values.size(); index++) {
				if (index > 0) {
					clause.append(", ");
				}
				clause.append(':').append(param(params, typed(values.get(index))));
			}
			return clause.append(')').toString();
		}
		if (filter.getOperator() == TransformFilter.Operator.CONTAINS
		        || filter.getOperator() == TransformFilter.Operator.NOT_CONTAINS) {
			String symbol = filter.getOperator() == TransformFilter.Operator.CONTAINS ? " LIKE " : " NOT LIKE ";
			// Lower both sides so "test" matches "Test": MySQL compares case-insensitively by collation
			// but PostgreSQL and H2 do not, and a cleaning step must behave the same on all three.
			return "LOWER(" + column + ")" + symbol + "LOWER(:" + param(params, containsPattern(filter.getValue())) + ")";
		}
		return column + " " + filter.getOperator().getSymbol() + " :" + param(params, typed(filter.getValue()));
	}
	
	/** Wraps a contains value in % so the bound parameter is a LIKE pattern rather than an equality. */
	private static String containsPattern(String raw) {
		return "%" + (raw == null ? "" : raw.trim()) + "%";
	}
	
	/**
	 * Splits an "is one of" value into its members, dropping blanks so a trailing comma is a typo
	 * rather than an empty member that matches nothing.
	 */
	private static List<String> listValues(String raw) {
		List<String> values = new ArrayList<String>();
		if (raw == null) {
			return values;
		}
		for (String candidate : raw.split(",")) {
			String trimmed = candidate.trim();
			if (!trimmed.isEmpty()) {
				values.add(trimmed);
			}
		}
		return values;
	}
	
	/**
	 * Gives a typed value the bound parameter, so that a number compares as a number and a date as a
	 * date. The form only ever supplies text; without this, {@code value_numeric > 50} would arrive as
	 * the string "50" and the database would either coerce it or refuse it depending on which one it
	 * is.
	 *
	 * @param raw the text a person typed
	 * @return the value to bind
	 */
	private static Object typed(String raw) {
		String value = raw == null ? "" : raw.trim();
		if ("true".equalsIgnoreCase(value)) {
			return Boolean.TRUE;
		}
		if ("false".equalsIgnoreCase(value)) {
			return Boolean.FALSE;
		}
		if (INTEGER.matcher(value).matches()) {
			try {
				return Long.valueOf(value);
			}
			catch (NumberFormatException e) {
				// too large for a long: fall through and bind it as text
			}
		}
		if (DECIMAL.matcher(value).matches()) {
			try {
				return new BigDecimal(value);
			}
			catch (NumberFormatException e) {
				// not a number after all: fall through and bind it as text
			}
		}
		if (DATE.matcher(value).matches()) {
			try {
				return java.sql.Date.valueOf(value);
			}
			catch (IllegalArgumentException e) {
				// 2024-13-45 matches the shape but is not a date: fall through and bind it as text
			}
		}
		return value;
	}
	
	/**
	 * Renders one step as SQL over the expression produced by the previous step.
	 */
	private static String apply(TransformStep step, String expression, Map<String, Object> params) {
		String argument = step.getArgument() == null ? "" : step.getArgument().trim();
		switch (step.getType()) {
			case TRIM:
				return "TRIM(" + expression + ")";
			case UPPER:
				return "UPPER(" + expression + ")";
			case LOWER:
				return "LOWER(" + expression + ")";
			case BLANK_TO:
				if (argument.isEmpty()) {
					throw new TransformValidationException("A blank-to step needs a replacement label.");
				}
				return "COALESCE(NULLIF(TRIM(" + expression + "), ''), :" + param(params, argument) + ")";
			case RECODE:
				return recode(step, expression, params);
			case BUCKET:
				return bucket(step, expression, params);
			case ROUND:
				return round(step, expression, params, argument);
			case YEAR:
				// EXTRACT rather than MySQL's YEAR(), and no cast: EXTRACT is the standard form that
				// MySQL, PostgreSQL and H2 all accept, and each already returns a number.
				return "EXTRACT(YEAR FROM " + expression + ")";
			case MONTH:
				return "EXTRACT(MONTH FROM " + expression + ")";
			case RENAME:
				// A rename changes only the output alias, which the caller applies separately.
				return expression;
			default:
				throw new TransformValidationException("Unsupported step " + step.getType() + ".");
		}
	}
	
	private static String recode(TransformStep step, String expression, Map<String, Object> params) {
		List<String[]> pairs = pairs(step);
		if (pairs.isEmpty()) {
			throw new TransformValidationException("A recode step needs at least one from -> to rule.");
		}
		StringBuilder sql = new StringBuilder("CASE ").append(expression).append(" ");
		for (String[] pair : pairs) {
			sql.append("WHEN :").append(param(params, pair[0])).append(" THEN :").append(param(params, pair[1])).append(' ');
		}
		// Anything not listed is left as it was rather than nulled, so a partial mapping does not
		// silently hide rows.
		return sql.append("ELSE ").append(expression).append(" END").toString();
	}
	
	private static String bucket(TransformStep step, String expression, Map<String, Object> params) {
		List<String[]> pairs = pairs(step);
		if (pairs.isEmpty()) {
			throw new TransformValidationException("A bucket step needs at least one upperBound|label band.");
		}
		StringBuilder sql = new StringBuilder("CASE ");
		boolean hasCatchAll = false;
		for (int i = 0; i < pairs.size(); i++) {
			String[] pair = pairs.get(i);
			String bound = pair[0].trim();
			if ("*".equals(bound)) {
				if (i != pairs.size() - 1) {
					throw new TransformValidationException("The \"*\" catch-all band must come last.");
				}
				hasCatchAll = true;
				sql.append("ELSE :").append(param(params, pair[1])).append(' ');
			} else {
				if (!isNumeric(bound)) {
					throw new TransformValidationException("Bucket bound \"" + abbreviate(bound) + "\" is not a number.");
				}
				sql.append("WHEN ").append(expression).append(" < :").append(param(params, bound)).append(" THEN :")
				        .append(param(params, pair[1])).append(' ');
			}
		}
		if (!hasCatchAll) {
			// Values above the highest band still need a label, otherwise they read as null and look
			// like missing data rather than "not in any band".
			sql.append("ELSE :").append(param(params, "Above all bands")).append(' ');
		}
		return sql.append("END").toString();
	}
	
	private static String round(TransformStep step, String expression, Map<String, Object> params, String argument) {
		int places = 0;
		try {
			places = Integer.parseInt(argument);
		}
		catch (NumberFormatException e) {
			throw new TransformValidationException("Round to needs a whole number of decimal places.");
		}
		if (places < 0 || places > 6) {
			throw new TransformValidationException("Round to accepts between 0 and 6 decimal places.");
		}
		// ROUND's second argument cannot be a bound parameter in MySQL, so the digit count is
		// validated above and inlined. It is an int from a fixed range, never caller text.
		return "ROUND(" + expression + ", " + places + ")";
	}
	
	/**
	 * Parses the pair form. RECODE uses {@code from -> to} and BUCKET uses {@code bound|label}.
	 */
	private static List<String[]> pairs(TransformStep step) {
		List<String[]> parsed = new ArrayList<String[]>();
		boolean bucket = step.getType() == TransformStep.Type.BUCKET;
		String separator = bucket ? "|" : "->";
		for (String raw : step.getPairs()) {
			if (raw == null) {
				continue;
			}
			String line = raw.trim();
			if (line.isEmpty()) {
				continue;
			}
			int at = line.indexOf(separator);
			if (at < 0) {
				throw new TransformValidationException("Expected \"" + separator + "\" in \"" + abbreviate(line) + "\".");
			}
			String left = line.substring(0, at).trim();
			String right = line.substring(at + separator.length()).trim();
			if (left.isEmpty() || right.isEmpty()) {
				throw new TransformValidationException(
				        "Both sides of \"" + separator + "\" are needed in \"" + abbreviate(line) + "\".");
			}
			parsed.add(new String[] { left, right });
		}
		return parsed;
	}
	
	private static boolean isNumeric(String value) {
		if (value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!Character.isDigit(c) && c != '-' && c != '.') {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Registers a value to bind and returns the generated parameter name. Names are positional so that
	 * no caller-supplied text ever appears in the statement.
	 */
	private static String param(Map<String, Object> params, Object value) {
		String name = "p" + params.size();
		params.put(name, value);
		return name;
	}
	
	/**
	 * Turns a column name into a safe output alias, keeping RENAME labels out of the SQL entirely.
	 */
	private static String uniqueAlias(String column, Set<String> used) {
		String base = ReportSourceRegistry.isPlainIdentifier(column) ? column : "col";
		String candidate = base;
		int suffix = 2;
		while (!used.add(candidate)) {
			candidate = base + "_" + suffix++;
		}
		return candidate;
	}
	
	private static String join(List<String> parts, String separator) {
		StringBuilder joined = new StringBuilder();
		for (String part : parts) {
			if (joined.length() > 0) {
				joined.append(separator);
			}
			joined.append(part);
		}
		return joined.toString();
	}
	
	private static String abbreviate(String value) {
		if (value == null) {
			return "";
		}
		return value.length() <= 40 ? value : value.substring(0, 37) + "...";
	}
}
