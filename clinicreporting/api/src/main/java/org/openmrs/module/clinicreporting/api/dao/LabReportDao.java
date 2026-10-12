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
import org.openmrs.module.clinicreporting.api.dto.LabOrderRow;
import org.openmrs.module.clinicreporting.api.dto.LabReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.dto.TurnaroundSummary;

/**
 * LIMS and lab result queries.
 * <p>
 * Built on the core {@code orders} and {@code obs} tables. A result is an observation whose
 * {@code order_id} points back at the order, which is how OpenMRS models lab results without
 * needing a dedicated LIMS schema.
 */
public interface LabReportDao {
	
	/**
	 * @param filter a validated filter
	 * @return one page of orders with their results, where recorded
	 */
	PagedResult<LabOrderRow> findOrders(LabReportFilter filter);
	
	/**
	 * @param filter a validated filter
	 * @return order counts per day, keyed by ISO date
	 */
	Map<String, TimeSeriesPoint> ordersPerDay(LabReportFilter filter);
	
	/**
	 * @param filter a validated filter
	 * @return result counts per day, keyed by ISO date, for orders that have a result
	 */
	Map<String, TimeSeriesPoint> resultsPerDay(LabReportFilter filter);
	
	/**
	 * @param filter a validated filter
	 * @param limit maximum number of tests to return
	 * @return results grouped by test (concept), most frequent first
	 */
	List<BreakdownRow> countByTest(LabReportFilter filter, int limit);
	
	/**
	 * @param filter a validated filter
	 * @return results grouped by observation status, e.g. FINAL or PRELIMINARY
	 */
	List<BreakdownRow> countByResultStatus(LabReportFilter filter);
	
	/**
	 * @param filter a validated filter
	 * @param maxSamples maximum number of completed orders to consider
	 * @return turnaround statistics between order and result
	 */
	TurnaroundSummary turnaround(LabReportFilter filter, int maxSamples);
}
