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

import java.util.Collections;
import java.util.Map;

import org.openmrs.module.clinicreporting.ClinicReportingConstants;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Liveness probe and module metadata.
 * <p>
 * This endpoint deliberately touches no clinical data, so it can be used to confirm the module
 * loaded without needing the report privilege.
 */
@Controller
@RequestMapping(ClinicReportingConstants.REST_NAMESPACE)
public class SystemController extends BaseReportController {
	
	@RequestMapping(value = "/ping", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> ping() {
		return Collections.<String, Object> singletonMap("status", "ok");
	}
	
	@RequestMapping(value = "/metadata", method = RequestMethod.GET)
	@ResponseBody
	public Map<String, Object> metadata() {
		Map<String, Object> metadata = new java.util.LinkedHashMap<>();
		metadata.put("moduleId", ClinicReportingConstants.MODULE_ID);
		metadata.put("namespace", ClinicReportingConstants.REST_NAMESPACE_URL);
		metadata.put("privilege", ClinicReportingConstants.VIEW_REPORTS_PRIVILEGE);
		metadata.put("defaultPageSize", ClinicReportingConstants.DEFAULT_PAGE_SIZE);
		metadata.put("maxPageSize", ClinicReportingConstants.MAX_PAGE_SIZE);
		metadata.put("maxRangeDays", ClinicReportingConstants.MAX_RANGE_DAYS);
		metadata.put("groupByOptions", org.openmrs.module.clinicreporting.api.dto.Period.values());
		return metadata;
	}
}
