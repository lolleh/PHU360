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
import java.util.Map;

import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.ClinicReportingConstants;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.PatientReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PatientSummaryRow;
import org.openmrs.module.clinicreporting.api.service.PatientReportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Patient and cohort listings.
 */
@Controller
@RequestMapping(ClinicReportingConstants.REST_NAMESPACE + "/patients")
public class PatientReportController extends BaseReportController {
	
	private PatientReportService service() {
		requireViewReportsPrivilege();
		return Context.getService(PatientReportService.class);
	}
	
	/**
	 * Lists patients matching the filter.
	 *
	 * @param q free-text search over name and patient identifier
	 * @param gender one of M, F or U
	 * @param minAgeMonths minimum age in months
	 * @param maxAgeMonths maximum age in months
	 * @param locationId a location; descendants are included
	 * @param programUuid restrict to patients enrolled in a program
	 * @param cohortUuid restrict to patients who were members of the cohort during the range
	 * @param seenInRange restrict to patients with an encounter inside the range
	 * @param dateFrom range start, yyyy-MM-dd
	 * @param dateTo range end, yyyy-MM-dd
	 * @param page 1-based page number
	 * @param size page size
	 * @return the page of patients
	 */
	@RequestMapping(value = "", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> list(@RequestParam(value = "q", required = false) String q,
	        @RequestParam(value = "gender", required = false) String gender,
	        @RequestParam(value = "minAgeMonths", required = false) String minAgeMonths,
	        @RequestParam(value = "maxAgeMonths", required = false) String maxAgeMonths,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "programUuid", required = false) String programUuid,
	        @RequestParam(value = "cohortUuid", required = false) String cohortUuid,
	        @RequestParam(value = "seenInRange", required = false) String seenInRange,
	        @RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "page", defaultValue = "1") String page,
	        @RequestParam(value = "size", required = false) String size) {
		
		PatientReportFilter filter = new PatientReportFilter();
		withRange(filter, dateFrom, dateTo);
		filter.setQ(parseText(q));
		filter.setGender(parseText(gender));
		filter.setMinAgeMonths(parseInteger(minAgeMonths, "minAgeMonths"));
		filter.setMaxAgeMonths(parseInteger(maxAgeMonths, "maxAgeMonths"));
		filter.setLocationId(parseInteger(locationId, "locationId"));
		filter.setProgramUuid(parseText(programUuid));
		filter.setCohortUuid(parseText(cohortUuid));
		filter.setSeenInRange(parseBoolean(seenInRange, "seenInRange"));
		filter.setPage(intParam(page, "page"));
		if (size != null) {
			filter.setSize(intParam(size, "size"));
		}
		
		PagedResult<PatientSummaryRow> result = service().findPatients(filter);
		return envelope(filter, "patients", result.getResults(), result.getTotalCount(), result.getPage(), result.getSize());
	}
	
	/**
	 * Summarises the matching patients by gender and by age band.
	 *
	 * @return both breakdowns under {@code byGender} and {@code byAgeBand}
	 */
	@RequestMapping(value = "/summary", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> summary(@RequestParam(value = "q", required = false) String q,
	        @RequestParam(value = "gender", required = false) String gender,
	        @RequestParam(value = "minAgeMonths", required = false) String minAgeMonths,
	        @RequestParam(value = "maxAgeMonths", required = false) String maxAgeMonths,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "programUuid", required = false) String programUuid,
	        @RequestParam(value = "cohortUuid", required = false) String cohortUuid,
	        @RequestParam(value = "seenInRange", required = false) String seenInRange,
	        @RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo) {
		
		PatientReportFilter filter = new PatientReportFilter();
		withRange(filter, dateFrom, dateTo);
		filter.setQ(parseText(q));
		filter.setGender(parseText(gender));
		filter.setMinAgeMonths(parseInteger(minAgeMonths, "minAgeMonths"));
		filter.setMaxAgeMonths(parseInteger(maxAgeMonths, "maxAgeMonths"));
		filter.setLocationId(parseInteger(locationId, "locationId"));
		filter.setProgramUuid(parseText(programUuid));
		filter.setCohortUuid(parseText(cohortUuid));
		filter.setSeenInRange(parseBoolean(seenInRange, "seenInRange"));
		
		PatientReportService service = service();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("filters", describe(filter));
		body.put("byGender", service.byGender(filter));
		body.put("byAgeBand", service.byAgeBand(filter));
		return body;
	}
	
	// ---------------------------------------------------------------- helpers
	
	private static int intParam(String value, String name) {
		Integer parsed = parseInteger(value, name);
		return parsed == null ? 1 : parsed;
	}
	
	/**
	 * Wraps a page of rows in the standard response envelope, so clients always find the effective
	 * filters alongside the data and can tell what was actually applied.
	 */
	static Map<String, Object> envelope(PatientReportFilter filter, String key, java.util.List<?> rows, long total, int page,
	        int size) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("filters", describe(filter));
		body.put(key, rows);
		body.put("totalCount", total);
		body.put("page", page);
		body.put("size", size);
		return body;
	}
	
	/**
	 * @return the filter as it was actually applied, after normalisation
	 */
	static Map<String, Object> describe(PatientReportFilter filter) {
		Map<String, Object> described = new LinkedHashMap<>();
		described.put("dateFrom", filter.getDateFrom());
		described.put("dateTo", filter.getDateTo());
		described.put("locationId", filter.getLocationId());
		described.put("q", filter.getQ());
		described.put("gender", filter.getGender());
		described.put("minAgeMonths", filter.getMinAgeMonths());
		described.put("maxAgeMonths", filter.getMaxAgeMonths());
		described.put("programUuid", filter.getProgramUuid());
		described.put("cohortUuid", filter.getCohortUuid());
		described.put("seenInRange", filter.getSeenInRange());
		return described;
	}
}
