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
import org.openmrs.module.clinicreporting.api.dto.LabOrderRow;
import org.openmrs.module.clinicreporting.api.dto.LabReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.Period;
import org.openmrs.module.clinicreporting.api.dto.TurnaroundSummary;
import org.openmrs.module.clinicreporting.api.service.LabReportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * LIMS and lab result reports.
 */
@Controller
@RequestMapping(ClinicReportingConstants.REST_NAMESPACE + "/lab")
public class LabReportController extends BaseReportController {
	
	private LabReportService service() {
		requireViewReportsPrivilege();
		return Context.getService(LabReportService.class);
	}
	
	/**
	 * Lists lab orders with their results.
	 *
	 * @param resultValue match a specific result value
	 * @param obsStatus match a result status, such as FINAL or AMENDED
	 */
	@RequestMapping(value = "/orders", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> orders(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "orderTypeUuid", required = false) String orderTypeUuid,
	        @RequestParam(value = "conceptUuid", required = false) String conceptUuid,
	        @RequestParam(value = "resultValue", required = false) String resultValue,
	        @RequestParam(value = "obsStatus", required = false) String obsStatus,
	        @RequestParam(value = "gender", required = false) String gender,
	        @RequestParam(value = "minAgeMonths", required = false) String minAgeMonths,
	        @RequestParam(value = "maxAgeMonths", required = false) String maxAgeMonths,
	        @RequestParam(value = "page", defaultValue = "1") String page,
	        @RequestParam(value = "size", required = false) String size) {
		
		LabReportFilter filter = filter(dateFrom, dateTo, locationId);
		filter.setOrderTypeUuid(parseText(orderTypeUuid));
		filter.setConceptUuid(parseText(conceptUuid));
		filter.setResultValue(parseText(resultValue));
		filter.setObsStatus(parseText(obsStatus));
		filter.setGender(parseText(gender));
		filter.setMinAgeMonths(parseInteger(minAgeMonths, "minAgeMonths"));
		filter.setMaxAgeMonths(parseInteger(maxAgeMonths, "maxAgeMonths"));
		Integer pageNumber = parseInteger(page, "page");
		filter.setPage(pageNumber == null ? 1 : pageNumber);
		if (size != null) {
			filter.setSize(parseInteger(size, "size"));
		}
		
		PagedResult<LabOrderRow> result = service().findOrders(filter);
		Map<String, Object> body = base(filter);
		body.put("orders", result.getResults());
		body.put("totalCount", result.getTotalCount());
		body.put("page", result.getPage());
		body.put("size", result.getSize());
		return body;
	}
	
	/**
	 * Order and result counts per period.
	 */
	@RequestMapping(value = "/trends", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> trends(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "groupBy", required = false) String groupBy,
	        @RequestParam(value = "orderTypeUuid", required = false) String orderTypeUuid,
	        @RequestParam(value = "conceptUuid", required = false) String conceptUuid,
	        @RequestParam(value = "obsStatus", required = false) String obsStatus) {
		LabReportFilter filter = filter(dateFrom, dateTo, locationId);
		filter.setOrderTypeUuid(parseText(orderTypeUuid));
		filter.setConceptUuid(parseText(conceptUuid));
		filter.setObsStatus(parseText(obsStatus));
		filter.setGroupBy(Period.parse(groupBy));
		LabReportService service = service();
		Map<String, Object> body = base(filter);
		body.put("groupBy", filter.getGroupBy());
		body.put("orders", service.ordersOverTime(filter));
		body.put("results", service.resultsOverTime(filter));
		return body;
	}
	
	/**
	 * Results grouped by test.
	 *
	 * @param limit maximum number of tests
	 */
	@RequestMapping(value = "/by-test", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> byTest(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "limit", required = false) String limit) {
		LabReportFilter filter = filter(dateFrom, dateTo, locationId);
		List<BreakdownRow> rows = service().byTest(filter, parseLimit(limit, 20));
		Map<String, Object> body = base(filter);
		body.put("breakdown", rows);
		return body;
	}
	
	/**
	 * Results grouped by observation status, which is how a register distinguishes results still
	 * pending from those finalised.
	 */
	@RequestMapping(value = "/by-result-status", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> byResultStatus(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "orderTypeUuid", required = false) String orderTypeUuid) {
		LabReportFilter filter = filter(dateFrom, dateTo, locationId);
		filter.setOrderTypeUuid(parseText(orderTypeUuid));
		List<BreakdownRow> rows = service().byResultStatus(filter);
		Map<String, Object> body = base(filter);
		body.put("breakdown", rows);
		return body;
	}
	
	/**
	 * Turnaround statistics between an order and its result.
	 *
	 * @param maxSamples how many completed orders to measure, default 5000
	 */
	@RequestMapping(value = "/turnaround", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> turnaround(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "locationId", required = false) String locationId,
	        @RequestParam(value = "orderTypeUuid", required = false) String orderTypeUuid,
	        @RequestParam(value = "maxSamples", required = false) String maxSamples) {
		LabReportFilter filter = filter(dateFrom, dateTo, locationId);
		filter.setOrderTypeUuid(parseText(orderTypeUuid));
		TurnaroundSummary summary = service().turnaround(filter, parseLimit(maxSamples, 5000));
		Map<String, Object> body = base(filter);
		body.put("turnaround", summary);
		return body;
	}
	
	// ---------------------------------------------------------------- helpers
	
	private LabReportFilter filter(String dateFrom, String dateTo, String locationId) {
		LabReportFilter filter = new LabReportFilter();
		withRange(filter, dateFrom, dateTo);
		filter.setLocationId(parseInteger(locationId, "locationId"));
		return filter;
	}
	
	private Map<String, Object> base(LabReportFilter filter) {
		Map<String, Object> body = new LinkedHashMap<>();
		Map<String, Object> filters = new LinkedHashMap<>();
		filters.put("dateFrom", filter.getDateFrom());
		filters.put("dateTo", filter.getDateTo());
		filters.put("locationId", filter.getLocationId());
		filters.put("orderTypeUuid", filter.getOrderTypeUuid());
		filters.put("conceptUuid", filter.getConceptUuid());
		filters.put("resultValue", filter.getResultValue());
		filters.put("obsStatus", filter.getObsStatus());
		filters.put("gender", filter.getGender());
		filters.put("groupBy", filter.getGroupBy());
		body.put("filters", filters);
		return body;
	}
}
