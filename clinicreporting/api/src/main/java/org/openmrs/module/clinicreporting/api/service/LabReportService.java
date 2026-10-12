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
import org.openmrs.module.clinicreporting.api.dto.LabOrderRow;
import org.openmrs.module.clinicreporting.api.dto.LabReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.dto.TurnaroundSummary;

/**
 * LIMS and lab result reports.
 * <p>
 * Obtain an instance with {@code Context.getService(LabReportService.class)}.
 */
public interface LabReportService {
	
	/**
	 * @param filter the report filter
	 * @return one page of orders with their results
	 */
	PagedResult<LabOrderRow> findOrders(LabReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @return order counts per period, dense
	 */
	List<TimeSeriesPoint> ordersOverTime(LabReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @return result counts per period, dense
	 */
	List<TimeSeriesPoint> resultsOverTime(LabReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @param limit maximum number of tests
	 * @return results grouped by test, most frequent first
	 */
	List<BreakdownRow> byTest(LabReportFilter filter, int limit);
	
	/**
	 * @param filter the report filter
	 * @return results grouped by observation status
	 */
	List<BreakdownRow> byResultStatus(LabReportFilter filter);
	
	/**
	 * @param filter the report filter
	 * @param maxSamples maximum number of completed orders to consider, coerced to a sane positive
	 *            value
	 * @return turnaround statistics between order and result
	 */
	TurnaroundSummary turnaround(LabReportFilter filter, int maxSamples);
}
