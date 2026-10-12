/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.service;

import java.util.List;
import java.util.Map;

import org.openmrs.module.clinicreporting.api.model.DataExport;
import org.openmrs.module.clinicreporting.api.model.ExportResult;
import org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry;

/**
 * Owns the saved data exports: their definition, their validation, and the execution of a
 * definition against the database.
 * <p>
 * Two privileges matter and they behave differently. The page in front of this service checks
 * {@code Manage Report Indicators} for everything, and {@code Run Raw Report SQL} before an export
 * that runs an analyst-written statement is saved or executed. This interface checks neither - a
 * service cannot see the caller's privileges reliably - so it is documented here instead of
 * pretending to enforce them.
 * <p>
 * Every run is read-only, row-capped twice (once in the statement, once by the executor), and
 * bounded by {@code TransformRequest.MAX_EXPORT_ROWS}. A run cannot write, cannot be told to return
 * more than its cap, and cannot reach a table the allowlist or {@code RawQueryGuard} refused.
 */
public interface DataExportService {
	
	/**
	 * @return the active exports, ordered by name
	 */
	List<DataExport> getExports();
	
	/**
	 * @return the retired exports, ordered by name
	 */
	List<DataExport> getRetiredExports();
	
	/**
	 * @param id the export's id
	 * @return the export, or null when there is none
	 */
	DataExport getExport(Integer id);
	
	/**
	 * Checks a definition without changing anything.
	 *
	 * @param export the definition to check
	 * @return a message written for the person who submitted it, or null when the definition is
	 *         acceptable
	 */
	String validate(DataExport export);
	
	/**
	 * Validates and stores a definition. Callers must have checked the privileges the definition needs
	 * before getting here.
	 *
	 * @param export the definition to store
	 * @return the stored export, with its id
	 * @throws IllegalArgumentException when the definition fails {@link #validate(DataExport)} or its
	 *             name is already taken, in either case with a message for the caller
	 */
	DataExport save(DataExport export);
	
	/**
	 * Retires an export so it leaves the list but is not destroyed.
	 *
	 * @param id the export's id
	 * @param reason shown where the export is retired
	 * @return the retired export
	 * @throws IllegalArgumentException when there is no such export
	 */
	DataExport retire(Integer id, String reason);
	
	/**
	 * Brings a retired export back into the list. The retirement audit fields are cleared, so the
	 * export reads as never retired rather than as retired and then reversed.
	 *
	 * @param id the export's id
	 * @return the active export
	 * @throws IllegalArgumentException when there is no such export
	 */
	DataExport reactivate(Integer id);
	
	/**
	 * Runs a stored definition with its own row limit.
	 *
	 * @param export the definition to run
	 * @param limit the largest number of rows to return, capped at the module's absolute ceiling
	 * @return the bounded rows and the statement that produced them
	 * @throws org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException when the
	 *             definition is refused or the database rejects it
	 */
	ExportResult run(DataExport export, int limit);
	
	/**
	 * @return the allowlisted sources the page may offer, narrowed to the columns this database has
	 */
	Map<String, ReportSourceRegistry.Source> sources();
	
	/**
	 * Seeds the shipped definitions the first time the module serves the page, unless an earlier
	 * attempt already did so. Idempotent: safe to call on every page load.
	 */
	void ensureDefaults();
	
	/**
	 * Refreshes the openmrs-module-reporting mirror of every export, so an export created before the
	 * reporting integration existed still shows up on the Reports page, and retires the mirror of an
	 * export that was retired while reporting was not there to hear about it. Idempotent: a mirror that
	 * already matches its export is not rewritten. Safe to call whenever the module starts; a failure
	 * is logged, never thrown, because this module must keep serving its own page either way.
	 */
	void reconcileReportingMirrors();
}
