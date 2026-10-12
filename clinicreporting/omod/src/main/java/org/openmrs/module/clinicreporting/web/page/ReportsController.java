/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.web.page;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.ClinicalReportFilter;
import org.openmrs.module.clinicreporting.api.dto.LabReportFilter;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.service.ClinicalReportService;
import org.openmrs.module.clinicreporting.api.service.LabReportService;
import org.openmrs.module.clinicreporting.web.controller.BaseReportController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * The page behind the "Clinic Reports" link on the OpenMRS home page.
 * <p>
 * The reports are computed here, through the service layer, rather than by the browser calling the
 * module's REST endpoints. The REST controllers sit behind the {@code /ws} DispatcherServlet, which
 * expects HTTP Basic credentials; a page already authenticated with a session cookie would have to
 * hand those credentials to script. Rendering server-side keeps the whole module on one privilege
 * check and one code path.
 * <p>
 * This controller lives outside {@code web.controller} on purpose. That package is
 * component-scanned into the REST DispatcherServlet context, which has no handler mapping for
 * {@code @OpenmrsForm}; a legacy page controller has to be picked up by the openmrs-servlet context
 * instead.
 */
@Controller
public class ReportsController extends BaseReportController {
	
	/**
	 * Resolved against the module's webapp directory, which the omod build copies to
	 * {@code web/module/clinicreporting/}.
	 */
	private static final String VIEW = "module/clinicreporting/reports";
	
	private static final String ISO_DATE = "yyyy-MM-dd";
	
	@RequestMapping(value = "/module/clinicreporting/reports.htm", method = RequestMethod.GET)
	public ModelAndView reports(@RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo) {
		requireViewReportsPrivilege();
		
		ModelAndView model = new ModelAndView(VIEW);
		model.addObject("tab", "clinical");
		model.addObject("showExportsTab", Boolean.valueOf(showExportsTab()));
		model.addObject("dateFrom", defaultDateFrom());
		model.addObject("dateTo", defaultDateTo());
		
		if (dateFrom == null || dateTo == null) {
			// First render: show the form without querying, so the page stays cheap.
			return model;
		}
		
		Date from = parse(dateFrom);
		Date to = parse(dateTo);
		if (from == null || to == null) {
			model.addObject("error", "clinicreporting.reports.error.dates");
			return model;
		}
		
		model.addObject("dateFrom", dateFrom);
		model.addObject("dateTo", dateTo);
		
		try {
			populate(model, from, to);
		}
		catch (RuntimeException e) {
			// A bad filter should show a message, not a stack trace to the clinician.
			model.addObject("error", e.getMessage());
		}
		return model;
	}
	
	private void populate(ModelAndView model, Date from, Date to) {
		ClinicalReportFilter clinical = new ClinicalReportFilter();
		clinical.setDateFrom(from);
		clinical.setDateTo(to);
		clinical.validate();
		ClinicalReportService clinicalService = Context.getRegisteredComponent("clinicalReportService",
		    ClinicalReportService.class);
		
		List<TimeSeriesPoint> encounters = clinicalService.encountersOverTime(clinical);
		model.addObject("encounters", encounters);
		model.addObject("encounterTotal", sum(encounters));
		model.addObject("distinctPatients", clinicalService.uniquePatientsWithEncounters(clinical));
		model.addObject("byEncounterType", clinicalService.byEncounterType(clinical));
		model.addObject("topConcepts", clinicalService.topConcepts(clinical, 10));
		
		LabReportFilter lab = new LabReportFilter();
		lab.setDateFrom(from);
		lab.setDateTo(to);
		lab.validate();
		LabReportService labService = Context.getRegisteredComponent("labReportService", LabReportService.class);
		
		model.addObject("byResultStatus", labService.byResultStatus(lab));
		model.addObject("byTest", labService.byTest(lab, 10));
		model.addObject("turnaround", labService.turnaround(lab, 1000));
	}
	
	private static long sum(List<TimeSeriesPoint> points) {
		long total = 0;
		for (TimeSeriesPoint point : points) {
			total += point.getCount();
		}
		return total;
	}
	
	/**
	 * @return the parsed date, or null when the text is not a valid date
	 */
	private static Date parse(String text) {
		SimpleDateFormat parser = new SimpleDateFormat(ISO_DATE);
		parser.setLenient(false);
		try {
			return parser.parse(text);
		}
		catch (ParseException e) {
			return null;
		}
	}
	
	/**
	 * @return six months ago, so the default range is wide enough to show something
	 */
	private static String defaultDateFrom() {
		return format(shifted(-6));
	}
	
	private static String defaultDateTo() {
		return format(new Date());
	}
	
	private static Date shifted(int months) {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.MONTH, months);
		return calendar.getTime();
	}
	
	private static String format(Date date) {
		return new SimpleDateFormat(ISO_DATE).format(date);
	}
}
