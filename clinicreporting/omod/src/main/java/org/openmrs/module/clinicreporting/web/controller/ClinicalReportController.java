/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.web.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.ClinicReportingConstants;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.ClinicalReportFilter;
import org.openmrs.module.clinicreporting.api.dto.Period;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.service.ClinicalReportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Clinical and register aggregates.
 */
@Controller
@RequestMapping(ClinicReportingConstants.REST_NAMESPACE + "/clinical")
public class ClinicalReportController extends BaseReportController {
	
	private ClinicalReportService service() {
		requireViewReportsPrivilege();
		return Context.getService(ClinicalReportService.class);
	}
	
	/**
	 * Encounter counts per period.
	 *
	 * @param groupBy DAY, WEEK, MONTH, QUARTER or YEAR
	 * @return a dense time series
	 */
	@RequestMapping(value = "/encounters", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> encounters(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "groupBy", required = false) String groupBy,
	        @RequestParam(value = "encounterTypeUuid", required = false) String encounterTypeUuid,
	        @RequestParam(value = "locationId", required = false) String locationId) {
		return series(true, dateFrom, dateTo, groupBy, encounterTypeUuid, null, locationId);
	}
	
	/**
	 * Observation counts per period.
	 *
	 * @param conceptUuid restrict to one concept
	 * @return a dense time series
	 */
	@RequestMapping(value = "/obs-trend", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> observations(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "groupBy", required = false) String groupBy,
	        @RequestParam(value = "encounterTypeUuid", required = false) String encounterTypeUuid,
	        @RequestParam(value = "conceptUuid", required = false) String conceptUuid,
	        @RequestParam(value = "locationId", required = false) String locationId) {
		return series(false, dateFrom, dateTo, groupBy, encounterTypeUuid, conceptUuid, locationId);
	}
	
	/**
	 * Encounters grouped by encounter type.
	 */
	@RequestMapping(value = "/encounter-types", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> byEncounterType(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "locationId", required = false) String locationId) {
		ClinicalReportFilter filter = filter(dateFrom, dateTo, locationId, null, null);
		List<BreakdownRow> rows = service().byEncounterType(filter);
		Map<String, Object> body = base(filter);
		body.put("breakdown", rows);
		return body;
	}
	
	/**
	 * The most frequently recorded concepts.
	 *
	 * @param limit maximum number of rows
	 */
	@RequestMapping(value = "/concepts", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> topConcepts(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "conceptUuid", required = false) String conceptUuid,
	        @RequestParam(value = "encounterTypeUuid", required = false) String encounterTypeUuid,
	        @RequestParam(value = "limit", required = false) String limit) {
		ClinicalReportFilter filter = filter(dateFrom, dateTo, locationId, encounterTypeUuid, conceptUuid);
		List<BreakdownRow> rows = service().topConcepts(filter, parseLimit(limit, 20));
		Map<String, Object> body = base(filter);
		body.put("breakdown", rows);
		return body;
	}
	
	/**
	 * Patients enrolled in each program.
	 */
	@RequestMapping(value = "/program-enrollment", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> byProgram(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "programUuid", required = false) String programUuid) {
		ClinicalReportFilter filter = filter(dateFrom, dateTo, locationId, null, null);
		filter.setProgramUuid(parseText(programUuid));
		List<BreakdownRow> rows = service().byProgram(filter);
		Map<String, Object> body = base(filter);
		body.put("breakdown", rows);
		return body;
	}
	
	/**
	 * Headline counts for a register: distinct patients with encounters and total encounters.
	 */
	@RequestMapping(value = "/register-summary", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> registerSummary(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "encounterTypeUuid", required = false) String encounterTypeUuid) {
		ClinicalReportFilter filter = filter(dateFrom, dateTo, locationId, encounterTypeUuid, null);
		ClinicalReportService service = service();
		List<TimeSeriesPoint> series = service.encountersOverTime(filter);
		long encounterTotal = 0L;
		for (TimeSeriesPoint point : series) {
			encounterTotal += point.getCount();
		}
		Map<String, Object> body = base(filter);
		body.put("encounterCount", encounterTotal);
		body.put("distinctPatients", service.uniquePatientsWithEncounters(filter));
		body.put("series", series);
		return body;
	}
	
	// ---------------------------------------------------------------- helpers
	
	private Map<String, Object> series(boolean encounters, String dateFrom, String dateTo, String groupBy,
	        String encounterTypeUuid, String conceptUuid, String locationId) {
		ClinicalReportFilter filter = filter(dateFrom, dateTo, locationId, encounterTypeUuid, conceptUuid);
		filter.setGroupBy(Period.parse(groupBy));
		ClinicalReportService service = service();
		List<TimeSeriesPoint> points = encounters ? service.encountersOverTime(filter)
		        : service.observationsOverTime(filter);
		Map<String, Object> body = base(filter);
		body.put("groupBy", filter.getGroupBy());
		body.put("series", points);
		return body;
	}
	
	private ClinicalReportFilter filter(String dateFrom, String dateTo, String locationId, String encounterTypeUuid,
	        String conceptUuid) {
		ClinicalReportFilter filter = new ClinicalReportFilter();
		withRange(filter, dateFrom, dateTo);
		filter.setLocationId(parseInteger(locationId, "locationId"));
		filter.setEncounterTypeUuid(parseText(encounterTypeUuid));
		filter.setConceptUuid(parseText(conceptUuid));
		return filter;
	}
	
	private Map<String, Object> base(ClinicalReportFilter filter) {
		Map<String, Object> body = new LinkedHashMap<>();
		Map<String, Object> filters = new LinkedHashMap<>();
		filters.put("dateFrom", filter.getDateFrom());
		filters.put("dateTo", filter.getDateTo());
		filters.put("locationId", filter.getLocationId());
		filters.put("encounterTypeUuid", filter.getEncounterTypeUuid());
		filters.put("conceptUuid", filter.getConceptUuid());
		filters.put("programUuid", filter.getProgramUuid());
		filters.put("groupBy", filter.getGroupBy());
		body.put("filters", filters);
		return body;
	}
}
