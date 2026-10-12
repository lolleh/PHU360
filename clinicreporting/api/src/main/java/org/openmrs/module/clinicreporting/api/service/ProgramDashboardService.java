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

import java.util.Date;
import java.util.List;

import org.openmrs.module.clinicreporting.api.dashboard.DashboardProgram;
import org.openmrs.module.clinicreporting.api.dashboard.IndicatorResult;
import org.openmrs.module.clinicreporting.api.dto.CrossTab;

/**
 * Programme dashboards: which programmes have one, and what their indicators currently say.
 * <p>
 * Obtain an instance with {@code Context.getService(ProgramDashboardService.class)}.
 */
public interface ProgramDashboardService {
	
	/**
	 * @return the programmes that have a dashboard in the definition file <em>and</em> exist on this
	 *         server, in definition order, followed by the built-in dashboards, which depend on no
	 *         programme. A programme the server does not have is left out rather than offered as a link
	 *         that opens on an empty page.
	 */
	List<DashboardProgram> getAvailablePrograms();
	
	/**
	 * @param programUuid a programme UUID, or the reserved UUID of a built-in dashboard
	 * @return the dashboard definition for it, or null when the file does not describe that programme
	 *         and it is not a built-in dashboard
	 */
	DashboardProgram getProgram(String programUuid);
	
	/**
	 * Computes every indicator on a dashboard over a date range.
	 * <p>
	 * An indicator whose concepts this server does not have comes back marked unavailable rather than
	 * failing the call, so one absent concept cannot take a whole dashboard down.
	 *
	 * @param program the dashboard to compute
	 * @param from range start, inclusive
	 * @param to range end, inclusive
	 * @return one result per indicator, in definition order
	 */
	List<IndicatorResult> compute(DashboardProgram program, Date from, Date to);
	
	/**
	 * Lays out the coded diagnoses the programme's patients received against the drug treatments
	 * ordered on the same visit, as a grid.
	 *
	 * @param programUuid the programme UUID
	 * @param from range start, inclusive
	 * @param to range end, inclusive
	 * @param maxDiagnoses the most diagnosis rows to show; the tail is folded into "Other"
	 * @param maxTreatments the most treatment columns to show; the tail is folded into "Other"
	 * @return the grid, never null, empty when this server has no diagnosis or drug-order table
	 */
	CrossTab diagnosesByTreatment(String programUuid, Date from, Date to, int maxDiagnoses, int maxTreatments);
	
	/**
	 * Lays out the coded diagnoses the whole clinic's patients received against the drug treatments
	 * ordered on the same visit, as a grid. Unlike {@link #diagnosesByTreatment} this is not scoped to
	 * a programme: it backs the built-in clinic-wide dashboard.
	 *
	 * @param from range start, inclusive
	 * @param to range end, inclusive
	 * @param maxDiagnoses the most diagnosis rows to show; the tail is folded into "Other"
	 * @param maxTreatments the most treatment columns to show; the tail is folded into "Other"
	 * @return the grid, never null, empty when this server has no diagnosis or drug-order table
	 */
	CrossTab diagnosesByTreatmentAcrossClinic(Date from, Date to, int maxDiagnoses, int maxTreatments);
}
