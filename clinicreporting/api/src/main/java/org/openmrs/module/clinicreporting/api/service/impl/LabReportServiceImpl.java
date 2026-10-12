/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.service.impl;

import java.util.List;

import org.openmrs.module.clinicreporting.api.dao.LabReportDao;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.LabOrderRow;
import org.openmrs.module.clinicreporting.api.dto.LabReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.dto.TurnaroundSummary;
import org.openmrs.module.clinicreporting.api.service.LabReportService;

public class LabReportServiceImpl implements LabReportService {
	
	/** Upper bound on turnaround samples, so one request cannot pull an unbounded result set. */
	private static final int MAX_TURNAROUND_SAMPLES = 50000;
	
	private LabReportDao dao;
	
	public void setDao(LabReportDao dao) {
		this.dao = dao;
	}
	
	@Override
	public PagedResult<LabOrderRow> findOrders(LabReportFilter filter) {
		return dao.findOrders((LabReportFilter) filter.validate());
	}
	
	@Override
	public List<TimeSeriesPoint> ordersOverTime(LabReportFilter filter) {
		LabReportFilter validated = (LabReportFilter) filter.validate();
		return ClinicalReportServiceImpl.toSeries(dao.ordersPerDay(validated), validated, validated.getGroupBy());
	}
	
	@Override
	public List<TimeSeriesPoint> resultsOverTime(LabReportFilter filter) {
		LabReportFilter validated = (LabReportFilter) filter.validate();
		return ClinicalReportServiceImpl.toSeries(dao.resultsPerDay(validated), validated, validated.getGroupBy());
	}
	
	@Override
	public List<BreakdownRow> byTest(LabReportFilter filter, int limit) {
		int effectiveLimit = limit < 1 ? 20 : Math.min(limit, 500);
		return dao.countByTest((LabReportFilter) filter.validate(), effectiveLimit);
	}
	
	@Override
	public List<BreakdownRow> byResultStatus(LabReportFilter filter) {
		return dao.countByResultStatus((LabReportFilter) filter.validate());
	}
	
	@Override
	public TurnaroundSummary turnaround(LabReportFilter filter, int maxSamples) {
		int effectiveLimit = maxSamples < 1 ? 5000 : Math.min(maxSamples, MAX_TURNAROUND_SAMPLES);
		return dao.turnaround((LabReportFilter) filter.validate(), effectiveLimit);
	}
}
