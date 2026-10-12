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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.clinicreporting.api.dao.TransformDao;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.service.TransformService;
import org.openmrs.module.clinicreporting.api.transform.RawQueryGuard;
import org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry;
import org.openmrs.module.clinicreporting.api.transform.TransformPreview;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;
import org.openmrs.module.clinicreporting.api.transform.TransformValidationException;

/**
 * Default {@link TransformService}.
 * <p>
 * The two paths differ in how much they trust the caller. The pipeline path builds its own
 * statement from validated pieces. The raw path is handed a statement that has already been through
 * {@link RawQueryGuard}, is executed as a read-only bounded query, and is written to the server log
 * with the user who ran it.
 */
public class TransformServiceImpl implements TransformService {
	
	private static final Log log = LogFactory.getLog(TransformServiceImpl.class);
	
	private TransformDao dao;
	
	public void setDao(TransformDao dao) {
		this.dao = dao;
	}
	
	@Override
	public Map<String, ReportSourceRegistry.Source> sources() {
		Map<String, Set<String>> available;
		try {
			available = dao.availableColumns();
		}
		catch (RuntimeException e) {
			// Metadata is a convenience here, not the security boundary: the allowlist is. If it cannot
			// be read, offer the allowlist as written and let an absent column fail as a database error
			// rather than leaving the page empty and unexplained.
			log.warn("Could not read database metadata for the indicator sandbox; offering the " + "allowlist unfiltered",
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
	public TransformPreview preview(TransformRequest request) {
		if (request == null) {
			throw new InvalidReportFilterException("Nothing to preview.");
		}
		TransformRequest.Built built;
		try {
			built = request.build();
		}
		catch (TransformValidationException e) {
			// The builder's messages are written for the administrator, so they pass through as-is.
			throw new InvalidReportFilterException(e.getMessage());
		}
		
		long started = System.currentTimeMillis();
		List<List<String>> rows;
		try {
			rows = dao.runReadOnly(built.getSql(), built.getParams());
		}
		catch (RuntimeException e) {
			throw new InvalidReportFilterException("The database rejected that preview: " + rootMessage(e));
		}
		long elapsed = System.currentTimeMillis() - started;
		
		log.info("Indicator sandbox pipeline preview by " + currentUser() + ": table=" + request.getTable() + " columns="
		        + request.getColumns() + " steps=" + request.getSteps().size() + " rows=" + rows.size() + " in " + elapsed
		        + "ms");
		
		return new TransformPreview(TransformPreview.Mode.PIPELINE, built.getSql(), built.getOutputColumns(),
		        labelsFor(built), rows, rows.size() >= request.effectiveLimit(), elapsed);
	}
	
	@Override
	public TransformPreview previewRaw(String sql, int limit) {
		String refusal = RawQueryGuard.reject(sql);
		if (refusal == null) {
			// Accepted by the guard. Logged before it runs, so a statement that takes the server down is
			// still attributable. WARN rather than INFO because this is the audit trail for the one thing
			// here an administrator can point at the database with, and a default deployment logs at WARN:
			// an execution record that disappears under normal configuration is not a record.
			log.warn("Indicator sandbox RAW query accepted from " + currentUser() + ": " + oneLine(sql));
		} else {
			log.warn("Indicator sandbox refused a raw query from " + currentUser() + ": " + oneLine(sql) + " -> " + refusal);
			throw new InvalidReportFilterException(refusal);
		}
		
		int capped = limit <= 0 ? 100 : Math.min(limit, TransformRequest.MAX_ROWS);
		String statement = RawQueryGuard.wrap(sql, capped);
		
		long started = System.currentTimeMillis();
		TransformDao.RawResult result;
		try {
			result = dao.runRawReadOnly(statement);
		}
		catch (RuntimeException e) {
			log.warn("Indicator sandbox raw query failed after " + (System.currentTimeMillis() - started) + "ms for user "
			        + currentUser() + ": " + oneLine(sql) + " -> " + e.getMessage());
			throw new InvalidReportFilterException("The database rejected that query: " + rootMessage(e));
		}
		long elapsed = System.currentTimeMillis() - started;
		
		// The audit line. Raw SQL is the one thing here an administrator can point at the database with,
		// so it is logged with the user, the timing and the row count even on success, at the same level
		// as the refusal above so that it survives a default logging configuration.
		log.warn("Indicator sandbox RAW query by " + currentUser() + " returned " + result.getRows().size() + " row(s) in "
		        + elapsed + "ms: " + oneLine(sql));
		
		return new TransformPreview(TransformPreview.Mode.RAW, statement, result.getColumns(), labelsOf(result),
		        result.getRows(), result.isTruncated(), elapsed);
	}
	
	private static List<String> labelsFor(TransformRequest.Built built) {
		List<String> labels = new ArrayList<String>(built.getOutputColumns().size());
		for (String column : built.getOutputColumns()) {
			String label = built.getLabels().get(column);
			labels.add(label == null ? column : label);
		}
		return labels;
	}
	
	/**
	 * @return the same strings as column headers. A raw query is its own labelling; a human-friendly
	 *         header would have to be configured, and guessing one from a column name would be a lie.
	 */
	private static List<String> labelsOf(TransformDao.RawResult result) {
		return new ArrayList<String>(result.getColumns());
	}
	
	/**
	 * @return the current user for the audit line, or "unknown" rather than failing the query because
	 *         the context is unavailable
	 */
	private String currentUser() {
		try {
			String username = org.openmrs.api.context.Context.getAuthenticatedUser().getUsername();
			return username == null ? "unknown" : username;
		}
		catch (Exception e) {
			return "unknown";
		}
	}
	
	/**
	 * Collapses a statement onto one line so one query is one log line and cannot be used to flood the
	 * log with a hundred lines of its own text.
	 */
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
		// A driver message can be long and can echo the statement back; keep it short.
		return message.length() > 200 ? message.substring(0, 197) + "..." : message;
	}
}
