/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.reporting;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.openmrs.module.clinicreporting.api.model.DataExport;
import org.openmrs.module.clinicreporting.api.transform.DataExportReportingSql;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;
import org.openmrs.module.clinicreporting.api.transform.TransformValidationException;
import org.openmrs.module.reporting.dataset.definition.DataSetDefinition;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.ReportDesign;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.module.reporting.report.renderer.CsvReportRenderer;
import org.openmrs.module.reporting.report.service.ReportService;

/**
 * Keeps the openmrs-module-reporting side of a saved export in step with the export itself.
 * <p>
 * Every export gets one persistent {@link ReportDefinition} carrying a single
 * {@link SqlDataSetDefinition}. Its uuid is derived only from the export's own uuid, so saving the
 * same export twice always addresses the same report - an upsert, not an accumulation - and a
 * foreign module's reports are never touched, because their uuids cannot name a
 * {@link #reportUuid(String)}. The report declares the same required start-date/end-date range the
 * other exports on the reports page take, so its run form reads like theirs.
 * <p>
 * The reporting module is an optional layer for this module, not a hard requirement of it: callers
 * guard every entry point here behind an availability check, and each method is written so that a
 * reporting failure is a logged warning, never a failed export save.
 */
public final class ReportingDefinitionSync {
	
	/** Namespace prefix that makes {@link #reportUuid(String)} names unknowable to any other module. */
	private static final String NAMESPACE = "clinicreporting:data-export:";
	
	/** Dataset name inside the report, so inspecting a mirror is as easy as reading a map. */
	public static final String DATASET_NAME = "export";
	
	/**
	 * The name of the range-start parameter, matching what the other exports on the reports page take.
	 */
	public static final String START_DATE = "startDate";
	
	/**
	 * The name of the range-end parameter, matching what the other exports on the reports page take.
	 */
	public static final String END_DATE = "endDate";
	
	/** Message key the reportingui run page renders as "Start Date". */
	private static final String START_DATE_LABEL = "reporting.parameter.startDate";
	
	/** Message key the reportingui run page renders as "End Date". */
	private static final String END_DATE_LABEL = "reporting.parameter.endDate";
	
	/**
	 * Name of the CSV rendering design, matching what the other exports on the reports page render
	 * with: the run form offers one output format, labeled "CSV", because of this design.
	 */
	private static final String CSV_DESIGN_NAME = "reporting.csv";
	
	private ReportingDefinitionSync() {
	}
	
	/**
	 * @param exportUuid the saved export's uuid
	 * @return the report uuid that mirrors that export, deterministic for the same export
	 */
	public static String reportUuid(String exportUuid) {
		return UUID.nameUUIDFromBytes((NAMESPACE + exportUuid).getBytes(StandardCharsets.UTF_8)).toString();
	}
	
	/**
	 * Creates or refreshes the report that mirrors the export. A mirror that already carries the same
	 * name, description, statement, date range and range mapping is left alone, so a restart that
	 * reconciles every export does not rewrite reports that have not changed. A mirror retired
	 * alongside its export is revived by this, which is what puts a reactivated export back on the
	 * reports page. The mirror is also given a CSV rendering design, so its run form offers the same
	 * output format as the other exports on the reports page.
	 *
	 * @param export the export to mirror
	 * @param service the reporting module's definition service
	 * @throws IllegalArgumentException when the export cannot be mirrored - for example its definition
	 *             no longer builds - so the caller can skip exactly the definitions that broke
	 */
	public static void update(DataExport export, ReportDefinitionService service) {
		update(export, service, null);
	}
	
	/**
	 * Creates or refreshes the report that mirrors the export, and gives it a CSV rendering design when
	 * the reporting module is fully available to take one. Same shape as
	 * {@link #update(DataExport, ReportDefinitionService)}, with {@code designService} null meaning the
	 * design cannot be written, which is how callers that only guard the definition service stay free
	 * of the reporting module's report service.
	 *
	 * @throws IllegalArgumentException when the export cannot be mirrored - for example its definition
	 *             no longer builds - so the caller can skip exactly the definitions that broke
	 */
	public static void update(DataExport export, ReportDefinitionService definitionService, ReportService designService) {
		if (export == null || export.getUuid() == null) {
			throw new IllegalArgumentException("Nothing to mirror.");
		}
		String uuid = reportUuid(export.getUuid());
		ReportDefinition mirror = definitionService.getDefinitionByUuid(uuid);
		String description = description(export);
		
		ReportDefinition target = mirror;
		if (target == null || !sameAs(target, export, description)) {
			if (target == null) {
				target = new ReportDefinition();
				target.setUuid(uuid);
			}
			target.setName(export.getName());
			target.setDescription(description);
			// A mirror that was retired while its export was retired comes back with it, and the reason it
			// was retired with belongs to the export, not to the report it is no longer retired under.
			target.setRetired(false);
			target.setRetireReason(null);
			// The reports page run form is driven by the report's parameters: without the same range the
			// other exports take, a mirror opens to a run page with no start or end date and no way to ask
			// for the line of data the export is meant to deliver. Add the range at report level so the
			// form shows it, and at dataset level with the same mapping so it is never an optional extra
			// the page could present one way and evaluation resolve another.
			for (Parameter parameter : dateRangeParameters()) {
				if (target.getParameter(parameter.getName()) == null) {
					target.addParameter(parameter);
				}
			}
			SqlDataSetDefinition dataSet = new SqlDataSetDefinition(export.getName(), description, statement(export));
			for (Parameter parameter : dateRangeParameters()) {
				if (dataSet.getParameter(parameter.getName()) == null) {
					dataSet.addParameter(parameter);
				}
			}
			Map<String, Object> mappings = new LinkedHashMap<String, Object>();
			mappings.put(START_DATE, START_DATE);
			mappings.put(END_DATE, END_DATE);
			target.addDataSetDefinition(DATASET_NAME, dataSet, mappings);
			definitionService.saveDefinition(target);
		}
		ensureCsvDesign(target, designService);
	}
	
	/**
	 * Gives the mirror the one rendering design the reports-page run form needs: without a
	 * {@link CsvReportRenderer} design, the form opens with no output format at all, which is precisely
	 * what the other exports on the page do not have. A mirror that already has one is left as it is,
	 * so a reconcile that touches every export is cheap once the designs exist.
	 */
	private static void ensureCsvDesign(ReportDefinition mirror, ReportService designService) {
		if (designService == null || mirror == null) {
			return;
		}
		List<ReportDesign> designs = designService.getReportDesigns(mirror, CsvReportRenderer.class, false);
		if (designs != null && !designs.isEmpty()) {
			return;
		}
		ReportDesign design = new ReportDesign();
		design.setName(CSV_DESIGN_NAME);
		design.setReportDefinition(mirror);
		design.setRendererType(CsvReportRenderer.class);
		design.addPropertyValue("dateFormat", "dd-MMM-yyyy HH:mm:ss");
		design.addPropertyValue("blacklistRegex", "[^\\p{InBasicLatin}\\p{L}]");
		design.addPropertyValue("characterEncoding", "ISO-8859-1");
		designService.saveReportDesign(design);
	}
	
	/**
	 * Retires the report that mirrors the export, so it leaves the reports page but is not destroyed. A
	 * mirror that is already retired or was never created is left as it is.
	 */
	public static void retire(DataExport export, ReportDefinitionService service) {
		if (export == null || export.getUuid() == null) {
			return;
		}
		ReportDefinition mirror = service.getDefinitionByUuid(reportUuid(export.getUuid()));
		if (mirror == null || Boolean.TRUE.equals(mirror.getRetired())) {
			return;
		}
		mirror.setRetired(true);
		mirror.setRetireReason(export.getRetireReason());
		service.saveDefinition(mirror);
	}
	
	/**
	 * @return true when the mirror already shows this export, so it needs no rewrite. A retired mirror
	 *         never matches, because an export that is back in the list needs the report that mirrors
	 *         it back too, and the fields compared below would all still agree.
	 */
	private static boolean sameAs(ReportDefinition mirror, DataExport export, String description) {
		if (Boolean.TRUE.equals(mirror.getRetired())) {
			return false;
		}
		if (!export.getName().equals(mirror.getName())) {
			return false;
		}
		if (!Objects.equals(description, mirror.getDescription())) {
			return false;
		}
		Map<String, Mapped<? extends DataSetDefinition>> datasets = mirror.getDataSetDefinitions();
		Mapped<? extends DataSetDefinition> mapped = datasets == null ? null : datasets.get(DATASET_NAME);
		DataSetDefinition dataset = mapped == null ? null : mapped.getParameterizable();
		if (!(dataset instanceof SqlDataSetDefinition)) {
			return false;
		}
		SqlDataSetDefinition dataSet = (SqlDataSetDefinition) dataset;
		if (!statement(export).equals(dataSet.getSqlQuery())) {
			return false;
		}
		if (!dateRangeMappings().equals(mapped.getParameterMappings())) {
			return false;
		}
		return hasOnlyDateRangeParameters(mirror.getParameters()) && hasOnlyDateRangeParameters(dataSet.getParameters());
	}
	
	/** @return the range-open and range-close parameters, in the order the run page should show them */
	private static List<Parameter> dateRangeParameters() {
		List<Parameter> parameters = new ArrayList<Parameter>(2);
		parameters.add(dateRangeParameter(START_DATE, START_DATE_LABEL));
		parameters.add(dateRangeParameter(END_DATE, END_DATE_LABEL));
		return parameters;
	}
	
	/** @return one required date parameter that the reportingui run page turns into a date picker */
	private static Parameter dateRangeParameter(String name, String label) {
		Parameter parameter = new Parameter(name, label, Date.class);
		parameter.setRequired(true);
		return parameter;
	}
	
	/** @return the report-start-to-dataset-start mapping used by the mirrors */
	private static Map<String, Object> dateRangeMappings() {
		Map<String, Object> mappings = new LinkedHashMap<String, Object>();
		mappings.put(START_DATE, START_DATE);
		mappings.put(END_DATE, END_DATE);
		return mappings;
	}
	
	/** @return true when the list is exactly the required start-date/end-date pair, in any order */
	private static boolean hasOnlyDateRangeParameters(List<Parameter> parameters) {
		if (parameters == null || parameters.size() != 2) {
			return false;
		}
		int ranges = 0;
		for (Parameter parameter : parameters) {
			if ((START_DATE.equals(parameter.getName()) || END_DATE.equals(parameter.getName()))
			        && Date.class.equals(parameter.getType()) && parameter.isRequired()) {
				ranges++;
			}
		}
		return ranges == 2;
	}
	
	/**
	 * @return the description the report should carry, so the reports page reads like the exports page
	 */
	private static String description(DataExport export) {
		String described = export.getDescription();
		return described == null ? "" : described;
	}
	
	/**
	 * Builds the statement the report will run. Table exports are re-built through the same
	 * {@link TransformRequest} that validates them and then their binds are written back as literals;
	 * raw exports deliver their guarded statement as-is.
	 *
	 * @throws IllegalArgumentException when the export no longer builds, so a report is never created
	 *             from a definition that cannot run
	 */
	private static String statement(DataExport export) {
		String message = export.validate();
		if (message != null) {
			throw new IllegalArgumentException(message);
		}
		if (export.isRaw()) {
			return export.getRawSql();
		}
		TransformRequest.Built built;
		try {
			built = export.request().build();
		}
		catch (TransformValidationException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
		return DataExportReportingSql.literalize(built.getSql(), built.getParams());
	}
}
