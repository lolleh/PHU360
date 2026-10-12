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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.api.dashboard.DashboardProgram;
import org.openmrs.module.clinicreporting.api.dashboard.IndicatorResult;
import org.openmrs.module.clinicreporting.api.dashboard.ProgramIndicatorCatalog;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.service.ProgramDashboardService;
import org.openmrs.module.clinicreporting.web.controller.BaseReportController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * The programme dashboards: a picker, and one dashboard per programme.
 * <p>
 * These are separate pages from OpenMRS's own programme list on purpose. That list links to
 * enrolment forms, which is a different job - a clinician who opens a programme there is about to
 * enrol somebody, whereas a clinician who opens it here is about to see how the programme is doing.
 * Overloading the same page with both would have meant changing core behaviour this module has no
 * business changing.
 * <p>
 * The filter is an explicit date range. A tile comparing that range with the one immediately before
 * it is nearly always what a programme manager actually wants, so the previous range's figure is
 * computed alongside the chosen one and shown as a change. The previous range is the same span
 * shifted back so it ends where the chosen one starts. Both come from the same query per indicator,
 * so the two figures can never come from different definitions.
 */
@Controller
public class ProgramDashboardController extends BaseReportController {
	
	private static final Log log = LogFactory.getLog(ProgramDashboardController.class);
	
	/** The most diagnosis rows and treatment columns the cross-tab shows before folding the tail. */
	private static final int MATRIX_MAX_DIAGNOSES = 10;
	
	private static final int MATRIX_MAX_TREATMENTS = 8;
	
	private static final String PICKER_VIEW = "module/clinicreporting/programs";
	
	private static final String DASHBOARD_VIEW = "module/clinicreporting/dashboard";
	
	private static final String ISO_DATE = "yyyy-MM-dd";
	
	/**
	 * How far back the default range reaches when the page is opened without dates. A programme with no
	 * figures at all still gets its dashboard, so this is the floor, not the norm.
	 */
	private static final int DEFAULT_RANGE_MONTHS = 6;
	
	/**
	 * The programme picker.
	 * <p>
	 * Only programmes the definition file describes <em>and</em> the server actually has are listed. A
	 * definition for a programme that is not installed would otherwise offer a link to an empty page,
	 * which reads as "this programme has no patients" rather than "this programme is not here".
	 */
	@RequestMapping(value = "/module/clinicreporting/programs.htm", method = RequestMethod.GET)
	public ModelAndView programs() {
		requireViewReportsPrivilege();
		
		ModelAndView model = new ModelAndView(PICKER_VIEW);
		model.addObject("tab", "programs");
		model.addObject("showExportsTab", Boolean.valueOf(showExportsTab()));
		
		try {
			ProgramDashboardService service = Context.getService(ProgramDashboardService.class);
			model.addObject("programs", service.getAvailablePrograms());
			model.addObject("definitionSource", ProgramIndicatorCatalog.load().getSourceDescription());
		}
		catch (RuntimeException e) {
			// A broken definition file must not take the whole reporting section down with it: the rest
			// of the module still works, and the message says where the problem is.
			model.addObject("programs", new ArrayList<DashboardProgram>());
			model.addObject("error", "clinicreporting.programs.definitionsBroken");
			model.addObject("definitionDetail", e.getMessage());
		}
		return model;
	}
	
	/**
	 * One programme's dashboard.
	 * <p>
	 * The date range is bounded on both ends, like every other report here: an unbounded range over the
	 * obs and encounter tables is the usual way a reporting page takes a clinical server down.
	 */
	@RequestMapping(value = "/module/clinicreporting/dashboard.htm", method = RequestMethod.GET)
	public ModelAndView dashboard(@RequestParam(value = "program", required = false) String program,
	        @RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo) {
		requireViewReportsPrivilege();
		
		ModelAndView model = new ModelAndView(DASHBOARD_VIEW);
		model.addObject("tab", "programs");
		model.addObject("showExportsTab", Boolean.valueOf(showExportsTab()));
		
		DashboardProgram definition;
		ProgramDashboardService service;
		try {
			service = Context.getService(ProgramDashboardService.class);
			definition = service.getProgram(parseText(program));
		}
		catch (RuntimeException e) {
			model.addObject("error", "clinicreporting.programs.definitionsBroken");
			model.addObject("definitionDetail", e.getMessage());
			return model;
		}
		if (definition == null) {
			// No program parameter, or one this server has no definitions for. An unknown UUID is
			// reported the same way as an absent one rather than as an error: to a user following a
			// stale link they are the same situation.
			model.addObject("error", "clinicreporting.programs.unknownProgramme");
			model.addObject("requestedProgramme", parseText(program));
			return model;
		}
		
		model.addObject("program", definition);
		model.addObject("programUuid", definition.getUuid());
		if (definition.isBuiltin()) {
			// A built-in dashboard is not described by the definition file, so naming where its
			// indicators came from would be meaningless - and reading the file could fail for an edit
			// that has nothing to do with this page.
			model.addObject("builtin", Boolean.TRUE);
		} else {
			model.addObject("definitionSource", ProgramIndicatorCatalog.load().getSourceDescription());
		}
		
		Date from;
		Date to;
		try {
			Date[] range = resolveRange(dateFrom, dateTo);
			from = range[0];
			to = range[1];
		}
		catch (InvalidReportFilterException e) {
			// A hand-edited URL is the usual cause, so echo back the range the form would default to
			// rather than the text that failed to parse.
			model.addObject("error", "clinicreporting.programs.error.dates");
			model.addObject("dateFrom", format(monthsBefore(endOfToday(), DEFAULT_RANGE_MONTHS)));
			model.addObject("dateTo", format(endOfToday()));
			return model;
		}
		
		// The comparison range is the same span ending where this one starts, so the two are always the
		// same length whatever range was chosen.
		Date previousTo = from;
		Date previousFrom = new Date(from.getTime() - (to.getTime() - from.getTime()));
		model.addObject("dateFrom", format(from));
		model.addObject("dateTo", format(to));
		model.addObject("previousDateFrom", format(previousFrom));
		model.addObject("previousDateTo", format(previousTo));
		
		if (definition.isBuiltin()) {
			// The built-in dashboard has no indicator tiles: its whole page is the clinic-wide matrix.
			try {
				model.addObject("diagnosisTreatments",
				    service.diagnosesByTreatmentAcrossClinic(from, to, MATRIX_MAX_DIAGNOSES, MATRIX_MAX_TREATMENTS));
			}
			catch (RuntimeException e) {
				log.warn("Clinic-wide diagnoses-by-treatment matrix failed", e);
				model.addObject("matrixError", "clinicreporting.dashboard.matrixError");
			}
			return model;
		}
		
		try {
			List<IndicatorResult> current = service.compute(definition, from, to);
			List<IndicatorResult> previous = service.compute(definition, previousFrom, previousTo);
			model.addObject("results", compare(current, previous));
		}
		catch (RuntimeException e) {
			model.addObject("error", "clinicreporting.dashboard.error");
		}
		return model;
	}
	
	// ---------------------------------------------------------------- helpers
	
	/**
	 * Pairs each indicator with the same indicator over the preceding period of the same length.
	 * <p>
	 * The pairing is by indicator id rather than by position, so adding an indicator to the definition
	 * file cannot silently shift every comparison onto the wrong measure.
	 */
	static List<IndicatorResult> compare(List<IndicatorResult> current, List<IndicatorResult> previous) {
		java.util.Map<String, IndicatorResult> byId = new java.util.HashMap<>();
		for (IndicatorResult result : previous) {
			byId.put(result.getDefinition().getId(), result);
		}
		List<IndicatorResult> paired = new ArrayList<>(current.size());
		for (IndicatorResult result : current) {
			IndicatorResult before = byId.get(result.getDefinition().getId());
			Double change = change(result.getValue(), before == null ? null : before.getValue());
			// Reuse the current result instead of rebuilding it: rebuilding would throw away whether it
			// was available and why not, turning "this concept is missing on this server" into a bare
			// dash that looks like zero data.
			paired.add(result.withChange(change));
		}
		return paired;
	}
	
	/**
	 * @return the change between two figures, or null when either is missing. Null rather than zero,
	 *         because "no data last period" is not "no change".
	 */
	private static Double change(Double current, Double before) {
		if (current == null || before == null) {
			return null;
		}
		return Double.valueOf(current.doubleValue() - before.doubleValue());
	}
	
	/**
	 * Turns the two filter fields into a bounded range. Both ends are required by the time a query
	 * runs: an unbounded range over the obs and encounter tables is the usual way a reporting page
	 * takes a server down. A missing end is filled from the other, or from the default lookback when
	 * both are missing, so the page still draws something on first load.
	 *
	 * @return {@code {from, to}}, with {@code from} not after {@code to}
	 * @throws InvalidReportFilterException when a supplied date cannot be read, or the range runs
	 *             backwards
	 */
	static Date[] resolveRange(String dateFrom, String dateTo) {
		Date to = blank(dateTo) ? endOfToday() : parseDate(dateTo);
		Date from = blank(dateFrom) ? monthsBefore(to, DEFAULT_RANGE_MONTHS) : parseDate(dateFrom);
		if (from.after(to)) {
			throw new InvalidReportFilterException("The start of the date range is after its end");
		}
		return new Date[] { from, to };
	}
	
	/**
	 * @return the parsed date, never null
	 * @throws InvalidReportFilterException when the text is not a real {@code yyyy-MM-dd} date. Lenient
	 *             parsing is off on purpose, so {@code 2024-13-45} is refused rather than rolled into
	 *             2025.
	 */
	static Date parseDate(String text) {
		SimpleDateFormat parser = new SimpleDateFormat(ISO_DATE);
		parser.setLenient(false);
		try {
			return parser.parse(text.trim());
		}
		catch (java.text.ParseException e) {
			throw new InvalidReportFilterException("'" + text + "' is not a date in yyyy-MM-dd form");
		}
	}
	
	private static boolean blank(String text) {
		return text == null || text.trim().isEmpty();
	}
	
	/**
	 * @return the last moment of today, so a range ending now includes this morning's clinic
	 */
	private static Date endOfToday() {
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, 23);
		calendar.set(Calendar.MINUTE, 59);
		calendar.set(Calendar.SECOND, 59);
		return calendar.getTime();
	}
	
	private static Date monthsBefore(Date reference, int months) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(reference);
		calendar.add(Calendar.MONTH, -months);
		return calendar.getTime();
	}
	
	private static String format(Date date) {
		return new SimpleDateFormat(ISO_DATE).format(date);
	}
}
