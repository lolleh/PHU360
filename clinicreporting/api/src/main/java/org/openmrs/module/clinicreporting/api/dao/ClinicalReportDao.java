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

import java.util.List;
import java.util.Map;

import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.ClinicalReportFilter;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;

/**
 * Clinical and register aggregate queries.
 * <p>
 * Time series are returned bucketed by day, which is the finest granularity that can be expressed
 * identically on every supported database. Callers re-bucket into coarser periods.
 */
public interface ClinicalReportDao {
	
	/**
	 * @param filter a validated filter
	 * @return encounter counts per day, keyed by ISO date
	 */
	Map<String, TimeSeriesPoint> encountersPerDay(ClinicalReportFilter filter);
	
	/**
	 * @param filter a validated filter
	 * @return observation counts per day, keyed by ISO date
	 */
	Map<String, TimeSeriesPoint> observationsPerDay(ClinicalReportFilter filter);
	
	/**
	 * @param filter a validated filter
	 * @return one row per encounter type, most frequent first
	 */
	List<BreakdownRow> countByEncounterType(ClinicalReportFilter filter);
	
	/**
	 * @param filter a validated filter
	 * @param limit maximum number of concepts to return
	 * @return the most frequently recorded concepts, most frequent first
	 */
	List<BreakdownRow> countTopConcepts(ClinicalReportFilter filter, int limit);
	
	/**
	 * @param filter a validated filter
	 * @return one row per program, with the number of distinct patients enrolled or active during the
	 *         range
	 */
	List<BreakdownRow> countByProgram(ClinicalReportFilter filter);
	
	/**
	 * @param filter a validated filter
	 * @return the number of distinct patients with at least one encounter in the range
	 */
	long countUniquePatientsWithEncounters(ClinicalReportFilter filter);
}
