/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.facility.report;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The physical table each REPORT TYPE's indicators live in.
 * <p>
 * One table per report type, created by the module's liquibase.xml, and each holding only the
 * indicators of its own report: a report's numbers are the product of its definition, so an Under
 * Five row and a Mother and Neonate row for the same day and health center are different facts and
 * live in different tables.
 * <p>
 * The table is declared by the report's own mapping file and reached through this lookup rather
 * than derived from the report key, so that a key arriving from a request can never be interpolated
 * into SQL. Nothing is hardcoded here either: this is the same mapping {@link ReportCatalog} reads,
 * indexed by key.
 */
public final class ReportTables {
	
	private static Map<String, String> tables;
	
	private ReportTables() {
	}
	
	private static synchronized Map<String, String> tables() {
		if (tables == null) {
			Map<String, String> m = new LinkedHashMap<String, String>();
			for (ReportCatalog.Report report : ReportCatalog.all()) {
				m.put(report.getKey(), report.getTable());
			}
			tables = Collections.unmodifiableMap(m);
		}
		return tables;
	}
	
	/** The table for a report key, or null when the key has no table. */
	public static String forReport(String reportKey) {
		return tables().get(reportKey);
	}
	
	public static Map<String, String> all() {
		return tables();
	}
}
