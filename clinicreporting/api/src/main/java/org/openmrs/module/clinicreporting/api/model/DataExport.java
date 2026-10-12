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
import java.util.Date;
import java.util.List;

import org.openmrs.module.clinicreporting.api.transform.RawQueryGuard;
import org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry;
import org.openmrs.module.clinicreporting.api.transform.TransformFilter;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;
import org.openmrs.module.clinicreporting.api.transform.TransformStep;
import org.openmrs.module.clinicreporting.api.transform.TransformValidationException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A saved data export: a name, an allowlisted source table, the columns to take from it, and the
 * cleaning steps and row conditions that make it a report rather than a dump.
 * <p>
 * Everything that decides what runs lives in the definition and is validated by
 * {@link TransformRequest#build()}, so a definition read back out of the database goes through
 * exactly the same allowlist, the same column and step caps and the same bound parameters as one
 * typed into the form. Persisting an export stores a description of a query, never a statement.
 * <p>
 * The query shape is held as JSON in one column rather than as three columns of text, because a
 * rename label or a recode rule is free text and any hand-picked delimiter would eventually appear
 * inside one.
 */
public class DataExport {
	
	/** Longest name the form accepts, so one export cannot take over the list. */
	public static final int MAX_NAME_LENGTH = 120;
	
	/** Longest description the form accepts. */
	public static final int MAX_DESCRIPTION_LENGTH = 500;
	
	/** Rows an export asks for when its owner did not say otherwise. */
	public static final int DEFAULT_ROW_LIMIT = 5000;
	
	private static final ObjectMapper JSON = new ObjectMapper();
	
	private Integer id;
	
	private String uuid;
	
	private String name;
	
	private String description;
	
	private String sourceTable;
	
	private Integer rowLimit = Integer.valueOf(DEFAULT_ROW_LIMIT);
	
	private List<String> columns = new ArrayList<String>();
	
	private List<TransformStep> steps = new ArrayList<TransformStep>();
	
	private List<TransformFilter> filters = new ArrayList<TransformFilter>();
	
	/**
	 * The analyst-written statement, for exports built on the raw path. Only ever set together with an
	 * empty {@link #sourceTable}: a definition is one or the other, because the two builders have
	 * different security machinery and a definition must not blur them.
	 */
	private String rawSql;
	
	private Integer creator;
	
	private Date dateCreated;
	
	private Integer changedBy;
	
	private Date dateChanged;
	
	private boolean retired;
	
	private Date dateRetired;
	
	private Integer retiredBy;
	
	private String retireReason;
	
	public Integer getId() {
		return id;
	}
	
	public void setId(Integer id) {
		this.id = id;
	}
	
	public String getUuid() {
		return uuid;
	}
	
	public void setUuid(String uuid) {
		this.uuid = uuid;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public String getDescription() {
		return description;
	}
	
	public void setDescription(String description) {
		this.description = description;
	}
	
	public String getSourceTable() {
		return sourceTable;
	}
	
	public void setSourceTable(String sourceTable) {
		this.sourceTable = sourceTable;
	}
	
	public Integer getRowLimit() {
		return rowLimit;
	}
	
	public void setRowLimit(Integer rowLimit) {
		this.rowLimit = rowLimit;
	}
	
	public List<String> getColumns() {
		return columns;
	}
	
	public void setColumns(List<String> columns) {
		this.columns = columns == null ? new ArrayList<String>() : new ArrayList<String>(columns);
	}
	
	public List<TransformStep> getSteps() {
		return steps;
	}
	
	public void setSteps(List<TransformStep> steps) {
		this.steps = steps == null ? new ArrayList<TransformStep>() : new ArrayList<TransformStep>(steps);
	}
	
	public List<TransformFilter> getFilters() {
		return filters;
	}
	
	public void setFilters(List<TransformFilter> filters) {
		this.filters = filters == null ? new ArrayList<TransformFilter>() : new ArrayList<TransformFilter>(filters);
	}
	
	public String getRawSql() {
		return rawSql;
	}
	
	public void setRawSql(String rawSql) {
		this.rawSql = rawSql;
	}
	
	/**
	 * @return true when this export runs an analyst-written statement rather than an allowlisted table
	 */
	public boolean isRaw() {
		return rawSql != null && !rawSql.trim().isEmpty();
	}
	
	public Integer getCreator() {
		return creator;
	}
	
	public void setCreator(Integer creator) {
		this.creator = creator;
	}
	
	public Date getDateCreated() {
		return dateCreated;
	}
	
	public void setDateCreated(Date dateCreated) {
		this.dateCreated = dateCreated;
	}
	
	public Integer getChangedBy() {
		return changedBy;
	}
	
	public void setChangedBy(Integer changedBy) {
		this.changedBy = changedBy;
	}
	
	public Date getDateChanged() {
		return dateChanged;
	}
	
	public void setDateChanged(Date dateChanged) {
		this.dateChanged = dateChanged;
	}
	
	public boolean isRetired() {
		return retired;
	}
	
	public void setRetired(boolean retired) {
		this.retired = retired;
	}
	
	public Date getDateRetired() {
		return dateRetired;
	}
	
	public void setDateRetired(Date dateRetired) {
		this.dateRetired = dateRetired;
	}
	
	public Integer getRetiredBy() {
		return retiredBy;
	}
	
	public void setRetiredBy(Integer retiredBy) {
		this.retiredBy = retiredBy;
	}
	
	public String getRetireReason() {
		return retireReason;
	}
	
	public void setRetireReason(String retireReason) {
		this.retireReason = retireReason;
	}
	
	/** @return the later of the two timestamps, for the "updated" column of the list */
	public Date getUpdated() {
		return dateChanged != null ? dateChanged : dateCreated;
	}
	
	/**
	 * @return the row condition summaries for the list, in the order they apply
	 */
	public List<String> describeFilters() {
		List<String> described = new ArrayList<String>();
		for (TransformFilter filter : filters) {
			described.add(filter.describe());
		}
		return described;
	}
	
	// ---------------------------------------------------------------- definition codec
	
	/**
	 * Serialises the query shape - columns, steps and filters - for the one column that holds it.
	 *
	 * @return the JSON document to persist
	 */
	public String definitionJson() {
		ObjectNode root = JSON.createObjectNode();
		root.put("rawSql", rawSql == null ? "" : rawSql);
		ArrayNode columnNodes = root.putArray("columns");
		for (String column : columns) {
			columnNodes.add(column);
		}
		ArrayNode stepNodes = root.putArray("steps");
		for (TransformStep step : steps) {
			ObjectNode node = stepNodes.addObject();
			node.put("type", step.getType() == null ? "" : step.getType().name());
			node.put("column", step.getColumn() == null ? "" : step.getColumn());
			node.put("argument", step.getArgument() == null ? "" : step.getArgument());
			ArrayNode pairs = node.putArray("pairs");
			for (String pair : step.getPairs()) {
				pairs.add(pair);
			}
		}
		ArrayNode filterNodes = root.putArray("filters");
		for (TransformFilter filter : filters) {
			ObjectNode node = filterNodes.addObject();
			node.put("column", filter.getColumn() == null ? "" : filter.getColumn());
			node.put("operator", filter.getOperator() == null ? "" : filter.getOperator().name());
			node.put("value", filter.getValue() == null ? "" : filter.getValue());
		}
		return root.toString();
	}
	
	/**
	 * Reads a definition written by {@link #definitionJson()} back into this export.
	 * <p>
	 * A malformed document throws rather than being half-applied: an export whose columns silently
	 * disappeared would run and produce the wrong report. An unrecognised step or operator is kept with
	 * a null type, which is the same refusal the form gives, so validation reports it by name.
	 *
	 * @param json the stored document
	 * @throws IllegalArgumentException when the document cannot be parsed
	 */
	public void applyDefinition(String json) {
		setColumns(new ArrayList<String>());
		setSteps(new ArrayList<TransformStep>());
		setFilters(new ArrayList<TransformFilter>());
		if (json == null || json.trim().isEmpty()) {
			return;
		}
		JsonNode root;
		try {
			root = JSON.readTree(json);
		}
		catch (Exception e) {
			throw new IllegalArgumentException("The stored export definition could not be read.", e);
		}
		if (root == null || !root.isObject()) {
			throw new IllegalArgumentException("The stored export definition is not an object.");
		}
		setRawSql(text(root, "rawSql"));
		List<String> readColumns = new ArrayList<String>();
		for (JsonNode column : array(root, "columns")) {
			readColumns.add(column.asText(""));
		}
		setColumns(readColumns);
		
		List<TransformStep> readSteps = new ArrayList<TransformStep>();
		for (JsonNode node : array(root, "steps")) {
			TransformStep.Type type = TransformStep.Type.parse(text(node, "type"));
			List<String> pairs = new ArrayList<String>();
			for (JsonNode pair : array(node, "pairs")) {
				pairs.add(pair.asText(""));
			}
			readSteps.add(new TransformStep(type, text(node, "column"), text(node, "argument"),
			        pairs.toArray(new String[pairs.size()])));
		}
		setSteps(readSteps);
		
		List<TransformFilter> readFilters = new ArrayList<TransformFilter>();
		for (JsonNode node : array(root, "filters")) {
			TransformFilter.Operator operator = TransformFilter.Operator.parse(text(node, "operator"));
			readFilters.add(new TransformFilter(text(node, "column"), operator, text(node, "value")));
		}
		setFilters(readFilters);
	}
	
	/**
	 * @param node the parent
	 * @param field the array field to read
	 * @return the array, or an empty one when the field is absent. Absent is not an error: an export
	 *         saved before filters existed still has to load.
	 */
	private static JsonNode array(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isArray() ? value : JSON.createArrayNode();
	}
	
	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asText(null);
	}
	
	// ---------------------------------------------------------------- validation
	
	/**
	 * Checks the export against the rules that decide what may run.
	 * <p>
	 * The query-level checks are delegated to {@link TransformRequest#build()}, which is the one place
	 * that decides whether a request is acceptable, so there is no second implementation of the
	 * allowlist to drift out of step with the first.
	 *
	 * @return a message written for the person who submitted it, or null when the export is acceptable
	 */
	public String validate() {
		String trimmedName = name == null ? "" : name.trim();
		if (trimmedName.isEmpty()) {
			return "Give the export a name.";
		}
		if (trimmedName.length() > MAX_NAME_LENGTH) {
			return "An export name may be at most " + MAX_NAME_LENGTH + " characters.";
		}
		if (description != null && description.trim().length() > MAX_DESCRIPTION_LENGTH) {
			return "A description may be at most " + MAX_DESCRIPTION_LENGTH + " characters.";
		}
		if (rowLimit != null && rowLimit.intValue() > TransformRequest.MAX_EXPORT_ROWS) {
			return "An export may ask for at most " + TransformRequest.MAX_EXPORT_ROWS + " rows.";
		}
		if (rowLimit != null && rowLimit.intValue() < 1) {
			return "An export must ask for at least one row.";
		}
		if (isRaw()) {
			// The raw exporter re-checks this when it runs too; refusing at save time keeps a statement
			// that could never run from being stored as though it were a report.
			return RawQueryGuard.reject(rawSql);
		}
		if (sourceTable == null || sourceTable.trim().isEmpty()) {
			return "Choose a source table.";
		}
		try {
			request().build();
		}
		catch (TransformValidationException e) {
			return e.getMessage();
		}
		return null;
	}
	
	/**
	 * @return the request this export describes, ready to build or to run
	 * @throws IllegalStateException when the definition cannot produce a request at all, which validate
	 *             has already reported as a message
	 */
	public TransformRequest request() {
		if (sourceTable == null || sourceTable.trim().isEmpty()) {
			// build() reports this one, and more clearly than a null table would
			return new TransformRequest(sourceTable, columns, steps, filters, 1, TransformRequest.MAX_ROWS);
		}
		int limit = rowLimit == null ? DEFAULT_ROW_LIMIT : rowLimit.intValue();
		return TransformRequest.forExport(sourceTable.trim(), columns, steps, filters, limit);
	}
	
	/**
	 * @return the allowlisted source this export reads, or null when it names something outside it
	 */
	public ReportSourceRegistry.Source source() {
		return ReportSourceRegistry.find(sourceTable);
	}
	
	/**
	 * @return an unmodifiable view of the definition's shape, for tests and for log lines
	 */
	public List<String> columnList() {
		return Collections.unmodifiableList(columns);
	}
}
