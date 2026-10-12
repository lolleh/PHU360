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
import org.openmrs.module.clinicreporting.api.dto.ClinicalReportFilter;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;

/**
 * Clinical and register aggregate reports, such as encounter volumes, observation trends and
 * program enrolment.
 * <p>
 * Obtain an instance with {@code Context.getService(ClinicalReportService.class)}.
 */
public interface ClinicalReportService {
	
	/**
	 * @param filter the report filter
	 * @return encounter counts per period, dense: every period in the range appears, and periods with
	 *         no activity report zero rather than being omitted
	 */
	List<TimeSeriesPoint> encountersOverTime(ClinicalReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @return observation counts per period, dense
	 */
	List<TimeSeriesPoint> observationsOverTime(ClinicalReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @return one row per encounter type, most frequent first
	 */
	List<BreakdownRow> byEncounterType(ClinicalReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @param limit maximum number of concepts, coerced to a sane positive value
	 * @return the most frequently recorded concepts
	 */
	List<BreakdownRow> topConcepts(ClinicalReportFilter filter, int limit);
	
	/**
	 * @param filter the report filter
	 * @return one row per program with the number of patients enrolled or active in range
	 */
	List<BreakdownRow> byProgram(ClinicalReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @return distinct patients with at least one encounter in the range
	 */
	long uniquePatientsWithEncounters(ClinicalReportFilter filter);
}
