/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.api.dao.DataExportDao;
import org.openmrs.module.clinicreporting.api.dao.TransformDao;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.model.DataExport;
import org.openmrs.module.clinicreporting.api.model.DataExportDefaults;
import org.openmrs.module.clinicreporting.api.model.ExportResult;
import org.openmrs.module.clinicreporting.api.reporting.DataExportAppFactory;
import org.openmrs.module.clinicreporting.api.reporting.ReportingDefinitionSync;
import org.openmrs.module.clinicreporting.api.service.DataExportService;
import org.openmrs.module.clinicreporting.api.transform.RawQueryGuard;
import org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;
import org.openmrs.module.clinicreporting.api.transform.TransformValidationException;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.module.reporting.report.service.ReportService;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link DataExportService}.
 * <p>
 * Running a definition is deliberately the same work as previewing a sandbox pipeline: build the
 * statement from validated pieces (or hand the raw statement to the guard), run it read-only under
 * a row cap, and report what came back. The two paths only differ in how much they trust the
 * caller, and both are bounded by the same absolute row ceiling.
 */
public class DataExportServiceImpl implements DataExportService {
	
	private static final Log log = LogFactory.getLog(DataExportServiceImpl.class);
	
	private DataExportDao dao;
	
	private TransformDao transformDao;
	
	public void setDao(DataExportDao dao) {
		this.dao = dao;
	}
	
	public void setTransformDao(TransformDao transformDao) {
		this.transformDao = transformDao;
	}
	
	@Override
	public List<DataExport> getExports() {
		return dao.getAll(false);
	}
	
	@Override
	public List<DataExport> getRetiredExports() {
		List<DataExport> retired = new ArrayList<DataExport>();
		for (DataExport export : dao.getAll(true)) {
			if (export.isRetired()) {
				retired.add(export);
			}
		}
		return retired;
	}
	
	@Override
	public DataExport getExport(Integer id) {
		return dao.getById(id);
	}
	
	@Override
	public String validate(DataExport export) {
		if (export == null) {
			return "Nothing to validate.";
		}
		return export.validate();
	}
	
	@Override
	@Transactional
	public DataExport save(DataExport export) {
		DataExport stored = store(export);
		DataExportAppFactory.refreshReportsPage();
		return stored;
	}
	
	/**
	 * Validates and writes one export without touching the Reports page. Every caller is already inside
	 * a transaction, so this deliberately carries no {@code @Transactional} of its own: a batch of
	 * seeded definitions refreshes the page once, at the end, instead of once each.
	 *
	 * @param export the definition to store
	 * @return the stored export, with its id
	 * @throws IllegalArgumentException when the definition is refused
	 */
	private DataExport store(DataExport export) {
		String message = validate(export);
		if (message != null) {
			throw new IllegalArgumentException(message);
		}
		String name = export.getName().trim();
		DataExport clash = dao.getByName(name);
		if (clash != null && (export.getId() == null || !clash.getId().equals(export.getId()))) {
			throw new IllegalArgumentException("An export named \"" + name + "\" already exists.");
		}
		
		if (export.getId() == null) {
			export.setUuid(UUID.randomUUID().toString());
			export.setCreator(userId());
			export.setDateCreated(new Date());
			export.setRetired(false);
			int id = dao.insert(export);
			DataExport stored = dao.getById(Integer.valueOf(id));
			syncMirror(stored);
			log.info("Data export \"" + name + "\" saved by " + currentUser());
			return stored;
		}
		
		export.setChangedBy(userId());
		export.setDateChanged(new Date());
		dao.update(export);
		syncMirror(export);
		log.info("Data export \"" + name + "\" updated by " + currentUser());
		return export;
	}
	
	@Override
	@Transactional
	public DataExport retire(Integer id, String reason) {
		DataExport export = dao.getById(id);
		if (export == null) {
			throw new IllegalArgumentException("There is no export with id " + id + ".");
		}
		export.setRetired(true);
		export.setDateRetired(new Date());
		export.setRetiredBy(userId());
		export.setRetireReason(reason);
		dao.update(export);
		retireMirror(export);
		DataExportAppFactory.refreshReportsPage();
		log.info("Data export \"" + export.getName() + "\" retired by " + currentUser()
		        + (reason == null || reason.trim().isEmpty() ? "" : (": " + reason)));
		return export;
	}
	
	@Override
	@Transactional
	public DataExport reactivate(Integer id) {
		DataExport export = dao.getById(id);
		if (export == null) {
			throw new IllegalArgumentException("There is no export with id " + id + ".");
		}
		if (export.isRetired()) {
			export.setRetired(false);
			export.setDateRetired(null);
			export.setRetiredBy(null);
			export.setRetireReason(null);
			export.setChangedBy(userId());
			export.setDateChanged(new Date());
			dao.update(export);
			syncMirror(export);
			DataExportAppFactory.refreshReportsPage();
			log.info("Data export \"" + export.getName() + "\" reactivated by " + currentUser());
		}
		return export;
	}
	
	@Override
	public ExportResult run(DataExport export, int limit) {
		if (export == null) {
			throw new InvalidReportFilterException("No export was chosen to run.");
		}
		String message = validate(export);
		if (message != null) {
			throw new InvalidReportFilterException(message);
		}
		int capped = limit <= 0 ? 100 : Math.min(limit, TransformRequest.MAX_EXPORT_ROWS);
		
		if (export.isRaw()) {
			return runRaw(export, capped);
		}
		return runTable(export, capped);
	}
	
	private ExportResult runTable(DataExport export, int capped) {
		TransformRequest.Built built;
		try {
			built = export.request().build();
		}
		catch (TransformValidationException e) {
			throw new InvalidReportFilterException(e.getMessage());
		}
		
		long started = System.currentTimeMillis();
		List<List<String>> rows;
		try {
			rows = transformDao.runExportReadOnly(built.getSql(), built.getParams(), capped);
		}
		catch (RuntimeException e) {
			throw new InvalidReportFilterException("The database rejected that export: " + rootMessage(e));
		}
		long elapsed = System.currentTimeMillis() - started;
		
		log.info("Data export \"" + export.getName() + "\" run by " + currentUser() + ": table=" + export.getSourceTable()
		        + " rows=" + rows.size() + " in " + elapsed + "ms");
		
		List<String> labels = new ArrayList<String>(built.getOutputColumns().size());
		for (String column : built.getOutputColumns()) {
			String label = built.getLabels().get(column);
			labels.add(label == null ? column : label);
		}
		return new ExportResult(ExportResult.Mode.TABLE, built.getSql(), built.getOutputColumns(), labels, rows,
		        rows.size() >= capped, elapsed);
	}
	
	private ExportResult runRaw(DataExport export, int capped) {
		String sql = export.getRawSql();
		String refusal = RawQueryGuard.reject(sql);
		if (refusal != null) {
			log.warn("Data export \"" + export.getName() + "\" refused a raw statement from " + currentUser() + ": "
			        + oneLine(sql) + " -> " + refusal);
			throw new InvalidReportFilterException(refusal);
		}
		String statement = RawQueryGuard.wrap(sql, capped);
		// WARN because this is the audit trail for the one kind of export an administrator can point at
		// the database with, and a default deployment logs at WARN.
		log.warn(
		    "Data export \"" + export.getName() + "\" RAW statement accepted from " + currentUser() + ": " + oneLine(sql));
		
		long started = System.currentTimeMillis();
		TransformDao.RawResult result;
		try {
			result = transformDao.runExportRawReadOnly(statement, capped);
		}
		catch (RuntimeException e) {
			log.warn("Data export \"" + export.getName() + "\" raw statement failed after "
			        + (System.currentTimeMillis() - started) + "ms for user " + currentUser() + ": " + oneLine(sql) + " -> "
			        + e.getMessage());
			throw new InvalidReportFilterException("The database rejected that export: " + rootMessage(e));
		}
		long elapsed = System.currentTimeMillis() - started;
		
		log.warn("Data export \"" + export.getName() + "\" RAW statement by " + currentUser() + " returned "
		        + result.getRows().size() + " row(s) in " + elapsed + "ms: " + oneLine(sql));
		
		return new ExportResult(ExportResult.Mode.RAW, statement, result.getColumns(), result.getColumns(), result.getRows(),
		        result.isTruncated(), elapsed);
	}
	
	@Override
	public Map<String, ReportSourceRegistry.Source> sources() {
		Map<String, Set<String>> available;
		try {
			available = transformDao.availableColumns();
		}
		catch (RuntimeException e) {
			// Metadata is a convenience here, not the security boundary: the allowlist is. If it cannot
			// be read, offer the allowlist as written and let an absent column fail as a database error.
			log.warn("Could not read database metadata for the data exports page; offering the allowlist " + "unfiltered",
			    e);
			return ReportSourceRegistry.all();
		}
		Map<String, ReportSourceRegistry.Source> offered = new LinkedHashMap<String, ReportSourceRegistry.Source>();
		for (Map.Entry<String, ReportSourceRegistry.Source> entry : ReportSourceRegistry.all().entrySet()) {
			ReportSourceRegistry.Source narrowed = entry.getValue().narrowed(available.get(entry.getKey()));
			if (narrowed != null) {
				offered.put(entry.getKey(), narrowed);
			}
		}
		return offered;
	}
	
	@Override
	@Transactional
	public void ensureDefaults() {
		String marker;
		try {
			marker = Context.getAdministrationService().getGlobalProperty(DataExportDefaults.SEEDED_PROPERTY);
		}
		catch (RuntimeException e) {
			log.warn("Could not read the data-export seeding marker; seeding anyway", e);
			marker = null;
		}
		if ("true".equalsIgnoreCase(marker)) {
			return;
		}
		int seeded = 0;
		int refused = 0;
		for (DataExport definition : DataExportDefaults.seedable()) {
			DataExport copy;
			try {
				copy = DataExportDefaults.copyOf(definition);
			}
			catch (RuntimeException e) {
				log.warn("A shipped data export definition could not be copied: " + e.getMessage());
				continue;
			}
			String message = validate(copy);
			if (message != null) {
				refused++;
				log.warn("Not seeding data export \"" + copy.getName() + "\": " + message);
				continue;
			}
			try {
				store(copy);
				seeded++;
			}
			catch (RuntimeException e) {
				refused++;
				log.warn("Could not seed data export \"" + copy.getName() + "\": " + e.getMessage());
			}
		}
		// One refresh for the whole batch: the page is rebuilt from the definitions as they now are,
		// so refreshing per definition would repeat the work for no different a result.
		if (seeded > 0) {
			DataExportAppFactory.refreshReportsPage();
		}
		log.info("Seeded " + seeded + " data export default" + (seeded == 1 ? "" : "s") + ", " + refused + " refused");
		try {
			Context.getAdministrationService()
			        .saveGlobalProperty(new org.openmrs.GlobalProperty(DataExportDefaults.SEEDED_PROPERTY, "true"));
		}
		catch (RuntimeException e) {
			// The marker is a courtesy; the definitions were still offered. Next load will retry.
			log.warn("Could not write the data-export seeding marker", e);
		}
	}
	
	// ---------------------------------------------------------------- reporting mirror
	
	/**
	 * @see org.openmrs.module.clinicreporting.api.service.DataExportService#reconcileReportingMirrors()
	 */
	@Override
	public void reconcileReportingMirrors() {
		ReportDefinitionService reporting = reportingService();
		if (reporting == null) {
			return;
		}
		int reconciled = 0;
		// Active and retired alike: an export retired while the reporting module was unavailable
		// leaves a mirror that would keep offering a definition the exports page no longer does.
		for (DataExport export : dao.getAll(true)) {
			try {
				if (export.isRetired()) {
					ReportingDefinitionSync.retire(export, reporting);
				} else {
					ReportingDefinitionSync.update(export, reporting, reportingDesignService());
				}
				reconciled++;
			}
			catch (RuntimeException e) {
				log.warn(
				    "Could not mirror data export \"" + export.getName() + "\" onto the reports page: " + rootMessage(e));
			}
		}
		log.info("Reconciled " + reconciled + " data export mirror" + (reconciled == 1 ? "" : "s") + " on the reports page");
	}
	
	/**
	 * Mirrors one saved export onto the reports page, or logs that the reporting module was not there
	 * to receive it. Never throws: an export is saved even when reporting is missing or refuses.
	 */
	private void syncMirror(DataExport export) {
		try {
			ReportDefinitionService reporting = reportingService();
			if (reporting != null) {
				ReportingDefinitionSync.update(export, reporting, reportingDesignService());
			}
		}
		catch (RuntimeException e) {
			log.warn("Could not mirror data export \"" + export.getName() + "\" onto the reports page: " + rootMessage(e));
		}
	}
	
	/**
	 * Retires the reports-page mirror of one retired export. Never throws; the failure to retire a
	 * mirror is worth a warning, not a failed retire.
	 */
	private void retireMirror(DataExport export) {
		try {
			ReportDefinitionService reporting = reportingService();
			if (reporting != null) {
				ReportingDefinitionSync.retire(export, reporting);
			}
		}
		catch (RuntimeException e) {
			log.warn("Could not retire the reports-page mirror of \"" + export.getName() + "\": " + rootMessage(e));
		}
	}
	
	/**
	 * @return the reporting module's definition service, or null when the reporting module is not
	 *         loaded on this deployment
	 */
	private ReportDefinitionService reportingService() {
		try {
			return Context.getService(ReportDefinitionService.class);
		}
		catch (RuntimeException e) {
			log.debug("The reporting module is not available; no reports-page mirror will be kept", e);
			return null;
		}
	}
	
	/**
	 * @return the reporting module's report service, or null when the reporting module is not loaded on
	 *         this deployment, so the caller can skip writing rendering designs it cannot write
	 */
	private ReportService reportingDesignService() {
		try {
			return Context.getService(ReportService.class);
		}
		catch (RuntimeException e) {
			log.debug("The reporting module is not available; no rendering design will be kept", e);
			return null;
		}
	}
	
	// ---------------------------------------------------------------- plumbing
	
	/**
	 * @return the current user's id, or null when the context is a system one with no user
	 */
	private Integer userId() {
		try {
			return Context.getAuthenticatedUser() == null ? null : Context.getAuthenticatedUser().getUserId();
		}
		catch (RuntimeException e) {
			return null;
		}
	}
	
	private String currentUser() {
		try {
			String username = Context.getAuthenticatedUser().getUsername();
			return username == null ? "unknown" : username;
		}
		catch (RuntimeException e) {
			return "unknown";
		}
	}
	
	private static String oneLine(String sql) {
		if (sql == null) {
			return "";
		}
		String flattened = sql.replaceAll("\\s+", " ").trim();
		return flattened.length() > 500 ? flattened.substring(0, 497) + "..." : flattened;
	}
	
	private static String rootMessage(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		if (message == null) {
			return cause.getClass().getSimpleName();
		}
		return message.length() > 200 ? message.substring(0, 197) + "..." : message;
	}
}
