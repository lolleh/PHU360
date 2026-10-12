/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.web.page;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.ClinicReportingConstants;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.model.DataExport;
import org.openmrs.module.clinicreporting.api.model.DataExportDefaults;
import org.openmrs.module.clinicreporting.api.model.ExportResult;
import org.openmrs.module.clinicreporting.api.service.DataExportService;
import org.openmrs.module.clinicreporting.api.service.TransformService;
import org.openmrs.module.clinicreporting.api.transform.RawQueryGuard;
import org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry;
import org.openmrs.module.clinicreporting.api.transform.TransformFilter;
import org.openmrs.module.clinicreporting.api.transform.TransformPreview;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;
import org.openmrs.module.clinicreporting.api.transform.TransformStep;
import org.openmrs.module.clinicreporting.api.transform.TransformValidationException;
import org.openmrs.module.clinicreporting.api.util.Csv;
import org.openmrs.module.clinicreporting.web.controller.BaseReportController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * The Data Exports workspace: the saved reports an installation builds, edits, runs and downloads.
 * <p>
 * One page carries the list and the builder. The list runs and downloads saved exports, and the
 * builder is where the declarative pipeline of the old indicator sandbox now lives: an allowlisted
 * source, a column picker, cleaning steps, row filters, and a raw statement for holders of the
 * second privilege. Nothing here reaches the database except through the same service, validation
 * and allowlist the sandbox used, so a definition typed today, a definition typed six versions ago,
 * and a definition read back out of the table all pass through the same gate.
 * <p>
 * Two privileges gate this page. {@code Manage Report Indicators} gets an administrator in at all.
 * {@code Run Raw Report SQL} is separate and is re-checked on every save, run or export that uses
 * the raw path, so holding raw SQL is always a deliberate second grant.
 * <p>
 * Like the other page controllers this one lives in {@code web.page} rather than {@code
 * web.controller}, because the REST DispatcherServlet has no view resolver for a JSP. The
 * {@code indicators.htm} URL is still served, pointing here, so a stale bookmark lands on a live
 * page.
 */
@Controller
public class DataExportsController extends BaseReportController {
	
	private static final Log log = LogFactory.getLog(DataExportsController.class);
	
	private static final String VIEW = "module/clinicreporting/exports";
	
	private static final String PIPELINE = "pipeline";
	
	private static final String RAW = "raw";
	
	/**
	 * Form field suffixes the step builder reads, e.g. stepType1, stepColumn1, stepArg1 and the from/to
	 * boxes stepFrom1_1, stepTo1_1.
	 */
	private static final int MAX_FORM_STEPS = 6;
	
	/**
	 * From/to boxes drawn for every step. A Recode fills them with value-to-label rules and a Bucket
	 * with upperBound-to-label bands, so the separator in the old "a -> b" text form never has to be
	 * typed. The boxes are read positionally and a blank box is simply unused.
	 */
	private static final int MAX_PAIRS_PER_STEP = 6;
	
	/** Names of the repeated from/to boxes, followed by the row and the slot, e.g. stepFrom2_3. */
	private static final String STEP_FROM_FIELD = "stepFrom";
	
	private static final String STEP_TO_FIELD = "stepTo";
	
	/** Filter slabs drawn before the administrator has added any of their own. */
	private static final int MAX_FORM_FILTERS = TransformRequest.MAX_FILTERS;
	
	/** Blank step rows drawn before the administrator has added any of their own. */
	private static final int MIN_FORM_STEPS = 3;
	
	/** Columns pre-selected on a first visit, so the page opens on something rather than on nothing. */
	private static final int DEFAULT_COLUMN_COUNT = 6;
	
	/**
	 * Rows shown on screen in one pass. Downloading is the point of an export, so the screen shows a
	 * sample bounded to keep a long result from becoming a long page; the CSV download is served with
	 * the export's own row limit.
	 */
	private static final int PREVIEW_ROWS = 200;
	
	/**
	 * Both GET and POST reach the same handler: the page is a form, and the builder posts back here.
	 * The privilege check below is what actually protects it, not the method.
	 */
	@RequestMapping(value = { "/module/clinicreporting/exports.htm", "/module/clinicreporting/indicators.htm" }, method = {
	        RequestMethod.GET, RequestMethod.POST })
	public ModelAndView exports(HttpServletRequest request, HttpServletResponse response,
	        @RequestParam(value = "action", required = false) String action,
	        @RequestParam(value = "id", required = false) String id,
	        @RequestParam(value = "mode", required = false) String mode,
	        @RequestParam(value = "name", required = false) String name,
	        @RequestParam(value = "description", required = false) String description,
	        @RequestParam(value = "table", required = false) String table,
	        @RequestParam(value = "columns", required = false) String columns,
	        @RequestParam(value = "rowLimit", required = false) String rowLimit,
	        @RequestParam(value = "limit", required = false) String limit,
	        @RequestParam(value = "rawSql", required = false) String rawSql,
	        @RequestParam(value = "reason", required = false) String reason) {
		
		ModelAndView model = new ModelAndView(VIEW);
		model.addObject("tab", "exports");
		model.addObject("showExportsTab", Boolean.TRUE);
		
		// Checked before anything else is resolved: a caller without the privilege never sees the shape
		// of the schema or a single exported row.
		if (!hasPrivilege(ClinicReportingConstants.MANAGE_INDICATORS_PRIVILEGE)) {
			log.warn(
			    "Refused the data exports page to a user without " + ClinicReportingConstants.MANAGE_INDICATORS_PRIVILEGE);
			model.addObject("denied", Boolean.TRUE);
			return model;
		}
		
		// Seeding is idempotent and safe on every load; the first visit gets the shipped list, later
		// visits skip straight past it.
		dataExportService().ensureDefaults();
		
		String act = parseText(action);
		
		if ("csv".equalsIgnoreCase(act)) {
			return csv(model, response, id);
		}
		if ("retire".equalsIgnoreCase(act)) {
			retire(model, id, reason);
			return model;
		}
		if ("reactivate".equalsIgnoreCase(act)) {
			reactivate(model, id);
			return model;
		}
		if ("run".equalsIgnoreCase(act)) {
			run(model, id, limit);
			return model;
		}
		
		Map<String, ReportSourceRegistry.Source> sources;
		try {
			sources = dataExportService().sources();
		}
		catch (RuntimeException e) {
			log.error("Could not resolve the data export sources", e);
			sources = new LinkedHashMap<String, ReportSourceRegistry.Source>();
		}
		model.addObject("sources", sources);
		model.addObject("categories", categories(sources));
		model.addObject("stepTypes", Arrays.asList(TransformStep.Type.values()));
		model.addObject("operators", Arrays.asList(TransformFilter.Operator.values()));
		model.addObject("maxRows", Integer.valueOf(TransformRequest.MAX_EXPORT_ROWS));
		model.addObject("maxColumns", Integer.valueOf(TransformRequest.MAX_COLUMNS));
		model.addObject("maxSteps", Integer.valueOf(TransformRequest.MAX_STEPS));
		model.addObject("rawAllowed", Boolean.valueOf(hasPrivilege(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE)));
		model.addObject("rawPolicy", RawQueryGuard.forbiddenKeywords());
		
		DataExport editing = id == null ? null : dataExportService().getExport(parseId(id));
		
		// A submission carries its own values; a plain "edit" GET shows the stored ones.
		State state = buildState(request, sources, mode, name, description, table, columns, rowLimit, rawSql, editing);
		
		if ("save".equalsIgnoreCase(act)) {
			save(model, state);
		} else {
			listExports(model);
			if ("preview".equalsIgnoreCase(act)) {
				preview(model, state, limit);
			} else {
				// The default view doubles as the "create" entry point: the builder sits above the list
				// so the page opens on something to do.
				editor(model, state);
			}
		}
		
		if (sources.isEmpty()) {
			model.addObject("noSources", Boolean.TRUE);
		}
		return model;
	}
	
	// ---------------------------------------------------------------- list actions
	
	/**
	 * Runs a saved export on screen with the preview row bound. The download link (action csv) is the
	 * full result; this is the bounded glance.
	 */
	private void run(ModelAndView model, String id, String limit) {
		DataExport export = dataExportService().getExport(parseId(id));
		if (export == null) {
			model.addObject("flashError", "That export is no longer there.");
			return;
		}
		if (export.isRaw() && !hasPrivilege(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE)) {
			log.warn("Refused to run raw data export " + export.getName() + " for a user without "
			        + ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE);
			model.addObject("flashError", "Running that export needs the \"Run Raw Report SQL\" privilege.");
			return;
		}
		try {
			ExportResult result = dataExportService().run(export, previewLimit(limit));
			model.addObject("runResult", result);
			model.addObject("runExport", export);
		}
		catch (RuntimeException e) {
			log.warn("Could not run data export " + export.getName() + ": " + e.getMessage());
			model.addObject("flashError", e.getMessage());
		}
		listExports(model);
	}
	
	private void retire(ModelAndView model, String id, String reason) {
		Integer exportId = parseId(id);
		if (exportId == null) {
			model.addObject("flashError", "No export was chosen to retire.");
		} else {
			try {
				DataExport retired = dataExportService().retire(exportId, parseText(reason));
				model.addObject("flash", "Retired \"" + retired.getName() + "\".");
			}
			catch (IllegalArgumentException e) {
				model.addObject("flashError", e.getMessage());
			}
		}
		listExports(model);
	}
	
	/**
	 * Brings a retired export back into the list. The mirror it had on the Reports page is revived with
	 * it, so the link and the list agree again.
	 */
	private void reactivate(ModelAndView model, String id) {
		Integer exportId = parseId(id);
		if (exportId == null) {
			model.addObject("flashError", "No export was chosen to reactivate.");
		} else {
			try {
				DataExport restored = dataExportService().reactivate(exportId);
				model.addObject("flash", "Reactivated \"" + restored.getName() + "\".");
			}
			catch (IllegalArgumentException e) {
				model.addObject("flashError", e.getMessage());
			}
		}
		listExports(model);
	}
	
	/**
	 * Streams a saved export as a CSV document: a data download with the export's own row limit, not a
	 * screen scrape. Returns null because the response has been written and there is no view to render.
	 */
	private ModelAndView csv(ModelAndView model, HttpServletResponse response, String id) {
		DataExport export = dataExportService().getExport(parseId(id));
		if (export == null) {
			model.addObject("flashError", "That export is no longer there.");
			listExports(model);
			return model;
		}
		if (export.isRaw() && !hasPrivilege(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE)) {
			log.warn("Refused to export raw data export " + export.getName() + " for a user without "
			        + ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE);
			model.addObject("flashError", "Exporting that raw statement needs the \"Run Raw Report SQL\" privilege.");
			listExports(model);
			return model;
		}
		int rowLimit = export.getRowLimit() == null ? DataExport.DEFAULT_ROW_LIMIT : export.getRowLimit().intValue();
		rowLimit = Math.min(Math.max(rowLimit, 1), TransformRequest.MAX_EXPORT_ROWS);
		try {
			ExportResult result = dataExportService().run(export, rowLimit);
			writeCsv(response, export.getName(), result.getLabels(), result.getRows());
			return null;
		}
		catch (RuntimeException e) {
			log.warn("Could not export data export " + export.getName() + ": " + e.getMessage());
			model.addObject("flashError", e.getMessage());
			listExports(model);
			return model;
		}
	}
	
	private static void writeCsv(HttpServletResponse response, String exportName, List<String> labels,
	        List<List<String>> rows) {
		try {
			response.setContentType("text/csv;charset=UTF-8");
			response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName(exportName) + "\"");
			response.getOutputStream().write(("\uFEFF" + Csv.format(labels, rows)).getBytes("UTF-8"));
			response.getOutputStream().flush();
		}
		catch (Exception e) {
			log.warn("Could not write the CSV response: " + e.getMessage());
		}
	}
	
	/** @return a name safe to put in a Content-Disposition filename */
	private static String fileName(String exportName) {
		String base = exportName == null ? "export" : exportName.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
		if (base.isEmpty()) {
			base = "export";
		}
		return (base.length() > 60 ? base.substring(0, 60) : base) + ".csv";
	}
	
	// ---------------------------------------------------------------- editor
	
	private void save(ModelAndView model, State state) {
		if (state.export.isRaw() && !hasPrivilege(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE)) {
			log.warn("Refused to save raw data export " + state.export.getName() + " for a user without "
			        + ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE);
			model.addObject("formError", "Saving a raw statement needs the \"Run Raw Report SQL\" privilege.");
			listExports(model);
			editor(model, state);
			return;
		}
		try {
			DataExport stored = dataExportService().save(state.export);
			model.addObject("flash", "Saved \"" + stored.getName() + "\".");
			listExports(model);
		}
		catch (IllegalArgumentException e) {
			model.addObject("formError", e.getMessage());
			listExports(model);
			editor(model, state);
		}
	}
	
	/**
	 * Previews what the builder describes without saving it, by the same route the sandbox used. The
	 * raw path is checked again here, before the statement is run.
	 */
	private void preview(ModelAndView model, State state, String limit) {
		int rowLimit = previewLimit(limit);
		try {
			if (RAW.equals(state.mode)) {
				if (!hasPrivilege(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE)) {
					log.warn("Refused a raw data export query from a user without "
					        + ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE);
					model.addObject("formError", "You do not have the privilege to run raw SQL here.");
					editor(model, state);
					return;
				}
				if (state.rawSql == null || state.rawSql.trim().isEmpty()) {
					model.addObject("formError", "Write a statement to run.");
					editor(model, state);
					return;
				}
				model.addObject("preview", transformService().previewRaw(state.rawSql, rowLimit));
			} else {
				ReportSourceRegistry.Source source = ReportSourceRegistry.find(state.sourceTable);
				if (source == null || state.selectedColumns.isEmpty()) {
					model.addObject("formError", "Choose a source table and at least one column.");
					editor(model, state);
					return;
				}
				TransformRequest request = TransformRequest.forExport(source.getTable(), state.selectedColumns, state.steps,
				    state.filters, rowLimit);
				model.addObject("preview", transformService().preview(request));
			}
		}
		catch (InvalidReportFilterException e) {
			model.addObject("formError", e.getMessage());
		}
		catch (TransformValidationException e) {
			model.addObject("formError", e.getMessage());
		}
		editor(model, state);
	}
	
	/**
	 * Fills the model with everything the builder JSP renders. The editor is drawn whenever the page is
	 * building, so a failed save or preview returns the administrator to their drafts.
	 */
	private void editor(ModelAndView model, State state) {
		boolean building = RAW.equals(state.mode) || ReportSourceRegistry.find(state.sourceTable) != null;
		if (!building) {
			return;
		}
		model.addObject("building", Boolean.TRUE);
		model.addObject("activeMode", state.mode);
		model.addObject("editingId", state.export.getId());
		model.addObject("exportName", nullToEmpty(state.export.getName()));
		model.addObject("exportDescription", nullToEmpty(state.export.getDescription()));
		model.addObject("rowLimit", Integer.valueOf(state.rowLimit));
		model.addObject("rawSql", nullToEmpty(state.rawSql));
		
		ReportSourceRegistry.Source source = ReportSourceRegistry.find(state.sourceTable);
		model.addObject("table", state.sourceTable);
		if (source != null) {
			model.addObject("source", source);
			model.addObject("selectedFlags", flags(state.selectedColumns));
		}
		if (!state.withheld.isEmpty()) {
			model.addObject("withheldColumns", state.withheld);
		}
		
		model.addObject("stepRows", stepRows(state.steps));
		model.addObject("stepRowsAvailable", Integer.valueOf(MAX_FORM_STEPS));
		model.addObject("filterRows", filterRows(state.filters));
	}
	
	/**
	 * Builds the working state of the builder from the request. A submission carries every field; a
	 * plain edit populates from the stored export; a first visit falls back to a useful default. The
	 * {@code State} carries the submitted values, so a failed save or preview returns the page to what
	 * was typed rather than to the stored row. The fallback source comes from the resolved, narrowed
	 * {@code available} map, never from the static allowlist, so a page whose database answers nothing
	 * cannot offer a table the database may not have.
	 */
	private static State buildState(HttpServletRequest request, Map<String, ReportSourceRegistry.Source> available,
	        String mode, String name, String description, String table, String columns, String rowLimit, String rawSql,
	        DataExport editing) {
		State state = new State();
		state.mode = RAW.equalsIgnoreCase(parseText(mode)) ? RAW : PIPELINE;
		
		boolean submitted = hasText(mode) || hasText(name) || hasText(description) || hasText(table) || hasText(columns)
		        || hasText(rowLimit) || hasText(rawSql);
		
		DataExport draft;
		if (editing != null) {
			draft = editing;
		} else {
			draft = new DataExport();
			draft.setRowLimit(Integer.valueOf(defaultRowLimit()));
		}
		state.export = draft;
		
		if (submitted) {
			String trimmedName = parseText(name);
			if (trimmedName != null) {
				draft.setName(trimmedName);
			}
			if (description != null) {
				draft.setDescription(description.trim());
			}
			if (hasText(rowLimit)) {
				try {
					int parsed = Integer.parseInt(rowLimit.trim());
					draft.setRowLimit(Integer.valueOf(Math.min(Math.max(parsed, 1), TransformRequest.MAX_EXPORT_ROWS)));
				}
				catch (NumberFormatException e) {
					// keep the draft's current row limit
				}
			}
		}
		state.rowLimit = draft.getRowLimit() == null ? defaultRowLimit() : draft.getRowLimit().intValue();
		
		String sourceTable = hasText(table) ? table.trim() : (editing != null ? editing.getSourceTable() : null);
		state.sourceTable = sourceTable;
		
		if (RAW.equals(state.mode)) {
			state.rawSql = rawSql != null ? rawSql : (editing != null && editing.isRaw() ? editing.getRawSql() : null);
			draft.setRawSql(state.rawSql);
			draft.setSourceTable(null);
			return state;
		}
		
		draft.setRawSql(null);
		draft.setSourceTable(sourceTable);
		if (submitted) {
			state.steps = parseSteps(request);
			state.filters = parseFilters(request);
			List<String> selected = new ArrayList<String>();
			List<String> withheld = new ArrayList<String>();
			parseColumns(columns, ReportSourceRegistry.find(sourceTable), selected, withheld);
			state.selectedColumns = selected;
			state.withheld = withheld;
			draft.setColumns(new ArrayList<String>(selected));
			draft.setSteps(new ArrayList<TransformStep>(state.steps));
			draft.setFilters(new ArrayList<TransformFilter>(state.filters));
		} else if (editing != null) {
			state.selectedColumns = new ArrayList<String>(editing.getColumns());
			state.steps = new ArrayList<TransformStep>(editing.getSteps());
			state.filters = new ArrayList<TransformFilter>(editing.getFilters());
		} else {
			ReportSourceRegistry.Source fallback = ReportSourceRegistry.find(sourceTable);
			if (fallback == null && !available.isEmpty()) {
				fallback = available.values().iterator().next();
			}
			if (fallback != null) {
				state.sourceTable = fallback.getTable();
				state.selectedColumns = defaultColumns(fallback);
			}
		}
		return state;
	}
	
	// ---------------------------------------------------------------- form parsing
	
	/**
	 * Reads the repeated step fields. The from/to boxes are read positionally; a box with both sides
	 * blank is unused, but a box with only one side filled is kept with the empty side in place so the
	 * builder refuses it by name rather than dropping it. A row that carries anything at all is also
	 * kept when its Step or Column is missing, so the builder can say which one is absent instead of
	 * the row vanishing without a word.
	 */
	private static List<TransformStep> parseSteps(HttpServletRequest request) {
		List<TransformStep> steps = new ArrayList<TransformStep>();
		if (request == null) {
			return steps;
		}
		for (int index = 1; index <= MAX_FORM_STEPS; index++) {
			String rawType = parseText(request.getParameter("stepType" + index));
			String rawColumn = parseText(request.getParameter("stepColumn" + index));
			String rawArgument = request.getParameter("stepArg" + index);
			String[] rules = readRules(request, index, rawType);
			if (rawType == null && rawColumn == null && parseText(rawArgument) == null && rules.length == 0) {
				continue;
			}
			steps.add(new TransformStep(TransformStep.Type.parse(rawType), rawColumn, rawArgument, rules));
		}
		return steps;
	}
	
	/**
	 * Reads the from/to boxes for one step row into rule lines the builder already understands: {@code
	 * from -> to} for every type except a Bucket, which uses {@code bound|label}. A Bucket band whose
	 * bound is left blank and whose label is filled becomes the {@code *} catch-all, which is how the
	 * final "everything above the bands" row is entered now that there is no line to type it on.
	 */
	private static String[] readRules(HttpServletRequest request, int index, String rawType) {
		boolean bucket = "BUCKET".equalsIgnoreCase(rawType);
		String separator = bucket ? "|" : "->";
		List<String> rules = new ArrayList<String>();
		for (int slot = 1; slot <= MAX_PAIRS_PER_STEP; slot++) {
			String from = parseText(request.getParameter(STEP_FROM_FIELD + index + "_" + slot));
			String to = parseText(request.getParameter(STEP_TO_FIELD + index + "_" + slot));
			if (from == null && to == null) {
				continue;
			}
			String left = from == null ? "" : from;
			String right = to == null ? "" : to;
			if (bucket && left.isEmpty()) {
				left = "*";
			}
			rules.add(left + separator + right);
		}
		return rules.toArray(new String[rules.size()]);
	}
	
	/**
	 * Reads the repeated filter slabs. An unrecognised operator is kept with a null operator so the
	 * builder (the layer that can say why) refuses it rather than the form silently dropping the row.
	 */
	private static List<TransformFilter> parseFilters(HttpServletRequest request) {
		List<TransformFilter> filters = new ArrayList<TransformFilter>();
		if (request == null) {
			return filters;
		}
		for (int index = 1; index <= MAX_FORM_FILTERS; index++) {
			String column = parseText(request.getParameter("filterColumn" + index));
			if (column == null) {
				continue;
			}
			TransformFilter.Operator operator = TransformFilter.Operator.parse(request.getParameter("filterOp" + index));
			filters.add(new TransformFilter(column, operator, request.getParameter("filterValue" + index)));
		}
		return filters;
	}
	
	private static void parseColumns(String joined, ReportSourceRegistry.Source source, List<String> columns,
	        List<String> withheld) {
		if (joined == null || source == null) {
			return;
		}
		for (String candidate : joined.split(",")) {
			String trimmed = candidate.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (!source.allows(trimmed)) {
				if (!withheld.contains(trimmed)) {
					withheld.add(trimmed);
				}
			} else if (!columns.contains(trimmed)) {
				columns.add(trimmed);
			}
		}
	}
	
	// ---------------------------------------------------------------- rows
	
	/**
	 * One slab of the filter builder, carrying the field names the form will submit for it.
	 */
	public static final class FilterRow {
		
		private final int index;
		
		private final String column;
		
		private final String operatorName;
		
		private final String value;
		
		FilterRow(int index, TransformFilter filter) {
			this.index = index;
			this.column = filter == null ? "" : nullToEmpty(filter.getColumn());
			this.operatorName = filter == null || filter.getOperator() == null ? "" : filter.getOperator().name();
			this.value = filter == null ? "" : nullToEmpty(filter.getValue());
		}
		
		public int getIndex() {
			return index;
		}
		
		public String getColumn() {
			return column;
		}
		
		public String getOperatorName() {
			return operatorName;
		}
		
		public String getValue() {
			return value;
		}
		
		public String getColumnField() {
			return "filterColumn" + index;
		}
		
		public String getOperatorField() {
			return "filterOp" + index;
		}
		
		public String getValueField() {
			return "filterValue" + index;
		}
	}
	
	private static List<FilterRow> filterRows(List<TransformFilter> filters) {
		int count = Math.min(MAX_FORM_FILTERS, Math.max(2, filters.size() + 1));
		List<FilterRow> rows = new ArrayList<FilterRow>(count);
		for (int index = 1; index <= count; index++) {
			rows.add(new FilterRow(index, index <= filters.size() ? filters.get(index - 1) : null));
		}
		return rows;
	}
	
	/** One row of the step builder, carrying the field names the form will submit for it. */
	public static final class StepRow {
		
		private final int index;
		
		private final String typeName;
		
		private final String column;
		
		private final String argument;
		
		private final List<Pair> pairSlots;
		
		StepRow(int index, TransformStep step) {
			this.index = index;
			this.typeName = step == null || step.getType() == null ? "" : step.getType().name();
			this.column = step == null ? "" : nullToEmpty(step.getColumn());
			this.argument = step == null ? "" : nullToEmpty(step.getArgument());
			this.pairSlots = pairSlots(index, step);
		}
		
		public int getIndex() {
			return index;
		}
		
		public String getTypeName() {
			return typeName;
		}
		
		public String getColumn() {
			return column;
		}
		
		public String getArgument() {
			return argument;
		}
		
		/** @return the from/to boxes to draw for this step, pre-filled from any stored rules */
		public List<Pair> getPairSlots() {
			return pairSlots;
		}
		
		public String getTypeField() {
			return "stepType" + index;
		}
		
		public String getColumnField() {
			return "stepColumn" + index;
		}
		
		public String getArgumentField() {
			return "stepArg" + index;
		}
	}
	
	/** One from/to box in a step row, plus the field names the form will submit for it. */
	public static final class Pair {
		
		private final String fromValue;
		
		private final String toValue;
		
		private final String fromField;
		
		private final String toField;
		
		Pair(int index, int slot, String fromValue, String toValue) {
			this.fromField = STEP_FROM_FIELD + index + "_" + slot;
			this.toField = STEP_TO_FIELD + index + "_" + slot;
			this.fromValue = fromValue;
			this.toValue = toValue;
		}
		
		public String getFromValue() {
			return fromValue;
		}
		
		public String getToValue() {
			return toValue;
		}
		
		public String getFromField() {
			return fromField;
		}
		
		public String getToField() {
			return toField;
		}
	}
	
	/**
	 * Builds the boxes for one step, splitting each stored rule back into its two sides. A Recode rule
	 * is stored as {@code from -> to} and a Bucket band as {@code bound|label}, so the stored string is
	 * read with the same separator the builder writes.
	 */
	private static List<Pair> pairSlots(int index, TransformStep step) {
		List<String[]> parsed = new ArrayList<String[]>();
		if (step != null) {
			String separator = step.getType() == TransformStep.Type.BUCKET ? "|" : "->";
			for (String rule : step.getPairs()) {
				parsed.add(splitRule(rule, separator));
			}
		}
		List<Pair> slots = new ArrayList<Pair>(MAX_PAIRS_PER_STEP);
		for (int slot = 1; slot <= MAX_PAIRS_PER_STEP; slot++) {
			String from = "";
			String to = "";
			if (slot <= parsed.size()) {
				from = parsed.get(slot - 1)[0];
				to = parsed.get(slot - 1)[1];
			}
			slots.add(new Pair(index, slot, from, to));
		}
		return slots;
	}
	
	private static String[] splitRule(String rule, String separator) {
		if (rule == null) {
			return new String[] { "", "" };
		}
		int at = rule.indexOf(separator);
		if (at < 0) {
			return new String[] { rule.trim(), "" };
		}
		return new String[] { rule.substring(0, at).trim(), rule.substring(at + separator.length()).trim() };
	}
	
	private static List<StepRow> stepRows(List<TransformStep> steps) {
		int count = Math.min(MAX_FORM_STEPS, Math.max(MIN_FORM_STEPS, steps.size() + 1));
		List<StepRow> rows = new ArrayList<StepRow>(count);
		for (int index = 1; index <= count; index++) {
			rows.add(new StepRow(index, index <= steps.size() ? steps.get(index - 1) : null));
		}
		return rows;
	}
	
	// ---------------------------------------------------------------- helpers
	
	/**
	 * The builder's working state, carrying both the draft export and the derived form values so the
	 * page redraws exactly what the administrator is typing.
	 */
	private static final class State {
		
		private DataExport export;
		
		private String mode = PIPELINE;
		
		private String sourceTable;
		
		private int rowLimit = DataExport.DEFAULT_ROW_LIMIT;
		
		private String rawSql;
		
		private List<String> selectedColumns = new ArrayList<String>();
		
		private List<String> withheld = new ArrayList<String>();
		
		private List<TransformStep> steps = new ArrayList<TransformStep>();
		
		private List<TransformFilter> filters = new ArrayList<TransformFilter>();
		
		private State() {
		}
	}
	
	private static Map<String, Boolean> flags(List<String> values) {
		Map<String, Boolean> flags = new LinkedHashMap<String, Boolean>();
		for (String value : values) {
			flags.put(value, Boolean.TRUE);
		}
		return flags;
	}
	
	private static List<Group> categories(Map<String, ReportSourceRegistry.Source> sources) {
		Map<String, List<String>> grouped = new LinkedHashMap<String, List<String>>();
		for (ReportSourceRegistry.Source source : sources.values()) {
			List<String> names = grouped.get(source.getCategory());
			if (names == null) {
				names = new ArrayList<String>();
				grouped.put(source.getCategory(), names);
			}
			names.add(source.getTable());
		}
		List<Group> groups = new ArrayList<Group>(grouped.size());
		for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
			groups.add(new Group(entry.getKey(), entry.getValue()));
		}
		return groups;
	}
	
	/** A picker's category and the source names under it. */
	public static final class Group {
		
		private final String label;
		
		private final List<String> tables;
		
		Group(String label, List<String> tables) {
			this.label = label;
			this.tables = tables;
		}
		
		public String getLabel() {
			return label;
		}
		
		public List<String> getTables() {
			return tables;
		}
	}
	
	private static List<String> defaultColumns(ReportSourceRegistry.Source source) {
		List<String> preferred = Arrays.asList("patient_id", "person_id", "encounter_id", "concept_id", "obs_datetime",
		    "encounter_datetime", "date_created", "gender", "birthdate", "name", "value_numeric", "value_text", "drug_id");
		List<String> columns = new ArrayList<String>();
		for (String candidate : preferred) {
			if (source.allows(candidate)) {
				columns.add(candidate);
			}
			if (columns.size() >= DEFAULT_COLUMN_COUNT) {
				return columns;
			}
		}
		for (String candidate : source.getColumns()) {
			if (columns.size() >= DEFAULT_COLUMN_COUNT) {
				break;
			}
			columns.add(candidate);
		}
		return columns;
	}
	
	private static int previewLimit(String raw) {
		Integer parsed = null;
		if (raw != null && !raw.trim().isEmpty()) {
			try {
				parsed = Integer.valueOf(raw.trim());
			}
			catch (NumberFormatException e) {
				log.warn("Ignoring an unreadable preview row limit: '" + raw + "'");
			}
		}
		if (parsed == null || parsed.intValue() <= 0) {
			return PREVIEW_ROWS;
		}
		return Math.min(parsed.intValue(), PREVIEW_ROWS);
	}
	
	private static Integer parseId(String id) {
		if (id == null || id.trim().isEmpty()) {
			return null;
		}
		try {
			return Integer.valueOf(id.trim());
		}
		catch (NumberFormatException e) {
			log.warn("Ignoring an unreadable export id: '" + id + "'");
			return null;
		}
	}
	
	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}
	
	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
	
	/**
	 * The configured default row limit for new exports, clamped to the module ceiling. A missing or
	 * unreadable value falls back to the model default.
	 */
	private static int defaultRowLimit() {
		String configured;
		try {
			configured = Context.getAdministrationService()
			        .getGlobalProperty(ClinicReportingConstants.GP_DATA_EXPORT_ROW_LIMIT);
		}
		catch (RuntimeException e) {
			configured = null;
		}
		if (configured == null || configured.trim().isEmpty()) {
			return DataExport.DEFAULT_ROW_LIMIT;
		}
		try {
			return Math.min(Math.max(Integer.parseInt(configured.trim()), 1), TransformRequest.MAX_EXPORT_ROWS);
		}
		catch (NumberFormatException e) {
			return DataExport.DEFAULT_ROW_LIMIT;
		}
	}
	
	// ---------------------------------------------------------------- plumbing
	
	protected TransformService transformService() {
		return Context.getService(TransformService.class);
	}
	
	/**
	 * Fills the model with every part of the list: the exports the page offers to run, the shipped
	 * names it can only list because their source is withheld, and the ones it offers to bring back.
	 * They are always read together, so a page can never show one list against a stale other.
	 * <p>
	 * The withheld names are listed so the workspace advertises the whole shipped list rather than
	 * three names fewer than the Reports page does. They are kept out of {@code exports} because that
	 * list is the one the page runs and downloads.
	 */
	private void listExports(ModelAndView model) {
		List<DataExport> exports = dataExportService().getExports();
		model.addObject("exports", exports);
		model.addObject("unavailableExports", unavailableNotIn(exports));
		model.addObject("retiredExports", dataExportService().getRetiredExports());
	}
	
	/**
	 * @param exports the runnable exports already in the list
	 * @return the shipped definitions with no source to read from, minus any whose name is already
	 *         listed, so a name is never shown twice
	 */
	private static List<DataExport> unavailableNotIn(List<DataExport> exports) {
		List<DataExport> unavailable = new ArrayList<DataExport>();
		for (DataExport shipped : DataExportDefaults.unavailable()) {
			if (!namedIn(exports, shipped.getName())) {
				unavailable.add(shipped);
			}
		}
		return unavailable;
	}
	
	private static boolean namedIn(List<DataExport> exports, String name) {
		if (name == null) {
			return false;
		}
		for (DataExport export : exports) {
			if (name.equalsIgnoreCase(export.getName())) {
				return true;
			}
		}
		return false;
	}
	
	protected DataExportService dataExportService() {
		return Context.getService(DataExportService.class);
	}
	
	/**
	 * @return true when the current user holds the privilege. Checked defensively: the page must refuse
	 *         rather than fall open if the authorisation context cannot answer.
	 */
	protected boolean hasPrivilege(String privilege) {
		try {
			return Context.hasPrivilege(privilege);
		}
		catch (RuntimeException e) {
			log.warn("Could not evaluate privilege " + privilege + "; treating as not granted", e);
			return false;
		}
	}
}
