/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.web.controller;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.ClinicReportingConstants;
import org.openmrs.module.clinicreporting.api.dto.ReportFilter;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.exception.ReportException;

/**
 * Query-parameter parsing, and the privilege check, shared by the report controllers.
 */
public abstract class BaseReportController {
	
	private static final Log log = LogFactory.getLog(BaseReportController.class);
	
	private static final String ISO_DATE = "yyyy-MM-dd";
	
	/**
	 * Enforces the module's read privilege.
	 * <p>
	 * Core's {@code @Authorized} AOP advice is installed in the service context, not in the
	 * DispatcherServlet context that serves these controllers, so the check is done explicitly here
	 * rather than by annotation. Controllers call this before touching any service, so a caller without
	 * the privilege learns nothing about the data.
	 *
	 * @throws InsufficientPrivilegeException when the current user lacks the privilege
	 */
	protected void requireViewReportsPrivilege() {
		if (!Context.hasPrivilege(ClinicReportingConstants.VIEW_REPORTS_PRIVILEGE)) {
			throw new InsufficientPrivilegeException(
			        "Viewing reports requires the '" + ClinicReportingConstants.VIEW_REPORTS_PRIVILEGE + "' privilege");
		}
	}
	
	/**
	 * Raised when the current user lacks the report privilege. Mapped to 403.
	 */
	public static class InsufficientPrivilegeException extends ReportException {
		
		private static final long serialVersionUID = 1L;
		
		public InsufficientPrivilegeException(String message) {
			super(message);
		}
	}
	
	/**
	 * @return true when the data exports tab should be offered in the page chrome.
	 *         <p>
	 *         Presentation only: the exports controller checks the same privilege again before it does
	 *         anything. Offering the tab to somebody who cannot use it would be a worse experience than
	 *         not offering it, but it must never be the thing that keeps them in.
	 */
	protected boolean showExportsTab() {
		try {
			return Context.hasPrivilege(ClinicReportingConstants.MANAGE_INDICATORS_PRIVILEGE);
		}
		catch (RuntimeException e) {
			// Refuse rather than fall open; worst case the tab is missing for an administrator who
			// could have used it, which they can still reach by URL.
			log.warn("Could not evaluate the exports privilege; hiding the tab", e);
			return false;
		}
	}
	
	/**
	 * Parses a {@code yyyy-MM-dd} query parameter.
	 *
	 * @param value the raw parameter
	 * @param name the parameter name, used in the error message
	 * @return the parsed date, or null when the parameter was absent or blank
	 * @throws org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException when the
	 *             value is present but not a valid date
	 */
	protected static Date parseDate(String value, String name) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		SimpleDateFormat parser = new SimpleDateFormat(ISO_DATE);
		// Without this, SimpleDateFormat silently accepts 2024-13-45 and rolls it over.
		parser.setLenient(false);
		try {
			return parser.parse(value.trim());
		}
		catch (ParseException e) {
			throw new InvalidReportFilterException(name + " must be a date in yyyy-MM-dd format, but was '" + value + "'");
		}
	}
	
	/**
	 * Applies the two mandatory date parameters to a filter.
	 *
	 * @return the same filter, for chaining
	 */
	protected static ReportFilter withRange(ReportFilter filter, String dateFrom, String dateTo) {
		filter.setDateFrom(parseDate(dateFrom, "dateFrom"));
		filter.setDateTo(parseDate(dateTo, "dateTo"));
		return filter;
	}
	
	/**
	 * @return the parsed value, or null when absent or blank
	 * @throws InvalidReportFilterException when the value is not an integer
	 */
	protected static Integer parseInteger(String value, String name) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return Integer.valueOf(value.trim());
		}
		catch (NumberFormatException e) {
			throw new InvalidReportFilterException(name + " must be a whole number, but was '" + value + "'");
		}
	}
	
	/**
	 * Accepts the spellings a browser or curl is likely to send for a boolean.
	 *
	 * @return the parsed value, or null when absent or blank
	 * @throws InvalidReportFilterException when the value is not a boolean
	 */
	protected static Boolean parseBoolean(String value, String name) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalised = value.trim().toLowerCase();
		if ("true".equals(normalised) || "1".equals(normalised)) {
			return Boolean.TRUE;
		}
		if ("false".equals(normalised) || "0".equals(normalised)) {
			return Boolean.FALSE;
		}
		throw new InvalidReportFilterException(name + " must be true or false, but was '" + value + "'");
	}
	
	/**
	 * @return the trimmed value, or null when absent or blank
	 */
	protected static String parseText(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return value.trim();
	}
	
	/**
	 * @param fallback used when the parameter is absent
	 * @return the parsed value
	 */
	protected static int parseLimit(String value, int fallback) {
		Integer parsed = parseInteger(value, "limit");
		return parsed == null ? fallback : parsed;
	}
	
	/**
	 * @param page the raw {@code page} parameter
	 * @return the page number, defaulting to the first page
	 */
	protected static int parsePage(String page) {
		Integer parsed = parseInteger(page, "page");
		return parsed == null ? 1 : parsed;
	}
}
