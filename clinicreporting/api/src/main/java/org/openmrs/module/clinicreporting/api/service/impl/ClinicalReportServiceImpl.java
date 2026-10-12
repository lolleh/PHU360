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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.openmrs.module.clinicreporting.api.dao.ClinicalReportDao;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.ClinicalReportFilter;
import org.openmrs.module.clinicreporting.api.dto.Period;
import org.openmrs.module.clinicreporting.api.dto.ReportFilter;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.service.ClinicalReportService;

/**
 * Turns per-day counts from the DAO into the coarse, gap-free series the API returns.
 */
public class ClinicalReportServiceImpl implements ClinicalReportService {
	
	private static final String ISO_DATE = "yyyy-MM-dd";
	
	private ClinicalReportDao dao;
	
	public void setDao(ClinicalReportDao dao) {
		this.dao = dao;
	}
	
	@Override
	public List<TimeSeriesPoint> encountersOverTime(ClinicalReportFilter filter) {
		ClinicalReportFilter validated = (ClinicalReportFilter) filter.validate();
		return toSeries(dao.encountersPerDay(validated), validated, validated.getGroupBy());
	}
	
	@Override
	public List<TimeSeriesPoint> observationsOverTime(ClinicalReportFilter filter) {
		ClinicalReportFilter validated = (ClinicalReportFilter) filter.validate();
		return toSeries(dao.observationsPerDay(validated), validated, validated.getGroupBy());
	}
	
	@Override
	public List<BreakdownRow> byEncounterType(ClinicalReportFilter filter) {
		return dao.countByEncounterType((ClinicalReportFilter) filter.validate());
	}
	
	@Override
	public List<BreakdownRow> topConcepts(ClinicalReportFilter filter, int limit) {
		int effectiveLimit = limit < 1 ? 20 : Math.min(limit, 500);
		return dao.countTopConcepts((ClinicalReportFilter) filter.validate(), effectiveLimit);
	}
	
	@Override
	public List<BreakdownRow> byProgram(ClinicalReportFilter filter) {
		return dao.countByProgram((ClinicalReportFilter) filter.validate());
	}
	
	@Override
	public long uniquePatientsWithEncounters(ClinicalReportFilter filter) {
		return dao.countUniquePatientsWithEncounters((ClinicalReportFilter) filter.validate());
	}
	
	/**
	 * Collapses the per-day buckets the DAO produced into the caller's period, filling gaps so the
	 * result is directly chartable.
	 *
	 * @param daily counts keyed by ISO date
	 * @param filter supplies the range, already validated
	 * @param period requested bucket size
	 * @return one point per period in the range
	 */
	static List<TimeSeriesPoint> toSeries(Map<String, TimeSeriesPoint> daily, ReportFilter filter, Period period) {
		// Aggregate the days into periods first, then overlay onto a dense skeleton. Building
		// the skeleton from the requested range (rather than from the rows returned) is what
		// guarantees a period with no activity still reports zero instead of vanishing.
		Map<String, Long> totals = new TreeMap<>();
		SimpleDateFormat parser = new SimpleDateFormat(ISO_DATE);
		parser.setLenient(false);
		for (Map.Entry<String, TimeSeriesPoint> entry : daily.entrySet()) {
			Date day = parseDay(parser, entry.getKey());
			if (day == null) {
				continue;
			}
			String label = period.label(period.truncate(day));
			Long current = totals.get(label);
			totals.put(label, current == null ? entry.getValue().getCount() : current + entry.getValue().getCount());
		}
		
		Map<String, TimeSeriesPoint> dense = period.expand(filter.getDateFrom(), filter.getDateTo());
		for (Map.Entry<String, Long> entry : totals.entrySet()) {
			TimeSeriesPoint point = dense.get(entry.getKey());
			if (point != null) {
				point.setCount(entry.getValue());
			}
		}
		return new ArrayList<>(dense.values());
	}
	
	private static Date parseDay(SimpleDateFormat parser, String value) {
		try {
			return parser.parse(value);
		}
		catch (ParseException e) {
			return null;
		}
	}
}
