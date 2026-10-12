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

import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.PatientReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PatientSummaryRow;

/**
 * Patient and cohort listings.
 * <p>
 * Obtain an instance with {@code Context.getService(PatientReportService.class)}.
 */
public interface PatientReportService {
	
	/**
	 * @param filter the report filter
	 * @return one page of patients matching the filter
	 */
	PagedResult<PatientSummaryRow> findPatients(PatientReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @return matching patients split by gender
	 */
	List<BreakdownRow> byGender(PatientReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @return matching patients split into age bands, with an "Unknown" band for patients whose
	 *         birthdate is not recorded
	 */
	List<BreakdownRow> byAgeBand(PatientReportFilter filter);
}
