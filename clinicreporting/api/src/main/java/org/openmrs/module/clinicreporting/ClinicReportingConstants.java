/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting;

/**
 * Constants shared across the Clinic Reporting module.
 */
public class ClinicReportingConstants {
	
	public static final String MODULE_ID = "clinicreporting";
	
	/**
	 * Root of every REST endpoint exposed by this module, relative to the DispatcherServlet. Core
	 * mounts that servlet at {@code /ws/*}, so this value is what has to be repeated in the public URL
	 * - {@code /ws}{@code /clinicreporting/v1} - not this value on its own.
	 */
	public static final String REST_NAMESPACE = "/" + MODULE_ID + "/v1";
	
	/** The namespace as clients see it, including the DispatcherServlet's own {@code /ws} mount. */
	public static final String REST_NAMESPACE_URL = "/ws" + REST_NAMESPACE;
	
	// Privileges
	
	public static final String VIEW_REPORTS_PRIVILEGE = "View Reports";
	
	/**
	 * Grants the indicator sandbox, where an administrator previews cleaning and mapping
	 * transformations over allowlisted clinical tables.
	 * <p>
	 * Deliberately not implied by {@link #VIEW_REPORTS_PRIVILEGE}: that one is granted to clinicians
	 * and must never reach raw rows.
	 */
	public static final String MANAGE_INDICATORS_PRIVILEGE = "Manage Report Indicators";
	
	/**
	 * Grants the audited raw SQL escape hatch inside the sandbox. Held separately from
	 * {@link #MANAGE_INDICATORS_PRIVILEGE} so that running analyst-written statements is always a
	 * deliberate second grant.
	 */
	public static final String RAW_REPORT_SQL_PRIVILEGE = "Run Raw Report SQL";
	
	// Global properties
	
	public static final String GP_DEFAULT_PAGE_SIZE = MODULE_ID + ".defaultPageSize";
	
	public static final String GP_MAX_PAGE_SIZE = MODULE_ID + ".maxPageSize";
	
	public static final String GP_DEFAULT_GROUP_BY = MODULE_ID + ".defaultGroupBy";
	
	/**
	 * Absolute path to an edited copy of the programme dashboard definitions, for deployments that do
	 * not keep module configuration inside the webapp. Empty by default, which means "use the copy in
	 * the module configuration directory, or the one this module ships".
	 */
	public static final String GP_PROGRAM_INDICATOR_DEFINITIONS = MODULE_ID + ".programIndicatorDefinitions";
	
	/**
	 * Default row limit offered when a new data export is created, clamped by the model to the module's
	 * absolute export ceiling. A missing or unreadable value falls back to
	 * {@link org.openmrs.module.clinicreporting.api.model.DataExport#DEFAULT_ROW_LIMIT}.
	 */
	public static final String GP_DATA_EXPORT_ROW_LIMIT = MODULE_ID + ".maxExportRows";
	
	// Defaults
	
	public static final int DEFAULT_PAGE_SIZE = 50;
	
	public static final int MAX_PAGE_SIZE = 1000;
	
	/**
	 * Reports are read-only. Long-running date ranges against encounter/obs tables are the usual cause
	 * of an overloaded database, so an unbounded range is rejected outright and the ceiling is
	 * deliberately generous but finite.
	 */
	public static final long MAX_RANGE_DAYS = 2000L;
	
	private ClinicReportingConstants() {
		// static utility class
	}
}
