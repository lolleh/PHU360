/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dao;

import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.PatientReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PatientSummaryRow;

/**
 * Patient and cohort listing queries.
 */
public interface PatientReportDao {
	
	/**
	 * Finds one page of patients matching the filter.
	 *
	 * @param filter a validated filter
	 * @return the matching page, never null
	 */
	PagedResult<PatientSummaryRow> findPatients(PatientReportFilter filter);
	
	/**
	 * Counts matching patients without paging.
	 *
	 * @param filter a validated filter
	 * @return the total number of matching patients
	 */
	long countPatients(PatientReportFilter filter);
	
	/**
	 * Splits matching patients by gender.
	 *
	 * @param filter a validated filter
	 * @return one row per gender present
	 */
	java.util.List<BreakdownRow> countByGender(PatientReportFilter filter);
	
	/**
	 * Splits matching patients into standard age bands, with an "Unknown" band for patients whose
	 * birthdate is not recorded.
	 *
	 * @param filter a validated filter
	 * @return one row per age band
	 */
	java.util.List<BreakdownRow> countByAgeBand(PatientReportFilter filter);
}
