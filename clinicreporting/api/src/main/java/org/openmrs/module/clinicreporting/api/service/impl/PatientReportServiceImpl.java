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

import org.openmrs.module.clinicreporting.api.dao.PatientReportDao;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.PatientReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PatientSummaryRow;
import org.openmrs.module.clinicreporting.api.service.PatientReportService;

public class PatientReportServiceImpl implements PatientReportService {
	
	private PatientReportDao dao;
	
	public void setDao(PatientReportDao dao) {
		this.dao = dao;
	}
	
	@Override
	public PagedResult<PatientSummaryRow> findPatients(PatientReportFilter filter) {
		return dao.findPatients((PatientReportFilter) filter.validate());
	}
	
	@Override
	public List<BreakdownRow> byGender(PatientReportFilter filter) {
		return dao.countByGender((PatientReportFilter) filter.validate());
	}
	
	@Override
	public List<BreakdownRow> byAgeBand(PatientReportFilter filter) {
		return dao.countByAgeBand((PatientReportFilter) filter.validate());
	}
}
