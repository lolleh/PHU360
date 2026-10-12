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

import org.openmrs.api.context.Context;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.PatientReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PatientSummaryRow;
import org.openmrs.module.clinicreporting.api.service.PatientReportService;
import org.openmrs.module.clinicreporting.web.controller.BaseReportController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * The patients tab: a filterable, paged register plus gender and age-band breakdowns of whatever
 * the filter matched.
 * <p>
 * The breakdowns deliberately reuse the same filter as the listing. A register whose total
 * disagrees with the chart beside it is worse than no chart, so both are computed in one place from
 * one filter.
 */
@Controller
public class PatientReportsController extends BaseReportController {
	
	private static final String VIEW = "module/clinicreporting/patients";
	
	private static final String ISO_DATE = "yyyy-MM-dd";
	
	/**
	 * The genders offered as filters on the register: male and female only, which is what this report
	 * is asked to break down by. OpenMRS itself also stores {@code U} when a patient's sex was never
	 * recorded, and the gender breakdown still reports those patients so its totals agree with the
	 * register count. They are deliberately absent here though: a filter the report cannot answer is
	 * worse than no filter, and {@link PatientReportFilter#validate()} rejects anything outside M, F
	 * and U anyway.
	 */
	private static final String[] GENDERS = { "M", "F" };
	
	@RequestMapping(value = "/module/clinicreporting/patients.htm", method = RequestMethod.GET)
	public ModelAndView patients(@RequestParam(value = "run", required = false) String run,
	        @RequestParam(value = "q", required = false) String q,
	        @RequestParam(value = "gender", required = false) String gender,
	        @RequestParam(value = "minAgeMonths", required = false) String minAgeMonths,
	        @RequestParam(value = "maxAgeMonths", required = false) String maxAgeMonths,
	        @RequestParam(value = "seenInRange", required = false) String seenInRange,
	        @RequestParam(value = "dateFrom", required = false) String dateFrom,
	        @RequestParam(value = "dateTo", required = false) String dateTo,
	        @RequestParam(value = "page", required = false) String page,
	        @RequestParam(value = "size", required = false) String size) {
		requireViewReportsPrivilege();
		
		ModelAndView model = new ModelAndView(VIEW);
		model.addObject("tab", "patients");
		model.addObject("showExportsTab", Boolean.valueOf(showExportsTab()));
		model.addObject("genders", GENDERS);
		
		String effectiveFrom = blank(dateFrom) ? monthsAgo(-6) : dateFrom.trim();
		String effectiveTo = blank(dateTo) ? today() : dateTo.trim();
		String search = parseText(q);
		String selectedGender = normaliseGender(gender);
		Integer minAge = parseInteger(minAgeMonths, "minAgeMonths");
		Integer maxAge = parseInteger(maxAgeMonths, "maxAgeMonths");
		Boolean seen = parseBoolean(seenInRange, "seenInRange");
		int requestedPage = parsePage(page);
		Integer requestedSize = parseInteger(size, "size");
		
		model.addObject("dateFrom", effectiveFrom);
		model.addObject("dateTo", effectiveTo);
		model.addObject("q", search);
		model.addObject("gender", selectedGender);
		model.addObject("minAgeMonths", minAge);
		model.addObject("maxAgeMonths", maxAge);
		model.addObject("seenInRange", seen);
		
		if (run == null) {
			return model;
		}
		
		Date from = parseDate(effectiveFrom, "dateFrom");
		Date to = parseDate(effectiveTo, "dateTo");
		if (from == null || to == null) {
			model.addObject("error", "clinicreporting.patients.error.dates");
			return model;
		}
		
		try {
			populate(model, from, to, search, selectedGender, minAge, maxAge, seen, requestedPage, requestedSize);
		}
		catch (RuntimeException e) {
			model.addObject("error", e.getMessage());
		}
		return model;
	}
	
	private void populate(ModelAndView model, Date from, Date to, String q, String gender, Integer minAgeMonths,
	        Integer maxAgeMonths, Boolean seenInRange, int requestedPage, Integer requestedSize) {
		
		PatientReportService service = Context.getService(PatientReportService.class);
		
		PatientReportFilter filter = new PatientReportFilter();
		filter.setDateFrom(from);
		filter.setDateTo(to);
		filter.setQ(q);
		filter.setGender(gender);
		filter.setMinAgeMonths(minAgeMonths);
		filter.setMaxAgeMonths(maxAgeMonths);
		filter.setSeenInRange(seenInRange);
		filter.setPage(requestedPage);
		if (requestedSize != null) {
			filter.setSize(requestedSize);
		}
		filter.validate();
		
		PagedResult<PatientSummaryRow> page = service.findPatients(filter);
		
		// The breakdowns describe the whole filtered set, not the visible page, so they must not carry
		// the page window. Filtering again without it keeps the tile totals equal to the register total.
		PatientReportFilter all = new PatientReportFilter();
		all.setDateFrom(from);
		all.setDateTo(to);
		all.setQ(q);
		all.setGender(gender);
		all.setMinAgeMonths(minAgeMonths);
		all.setMaxAgeMonths(maxAgeMonths);
		all.setSeenInRange(seenInRange);
		all.validate();
		
		List<PatientRow> rows = new ArrayList<>();
		for (PatientSummaryRow row : page.getResults()) {
			rows.add(PatientRow.of(row));
		}
		model.addObject("patients", rows);
		model.addObject("totalCount", page.getTotalCount());
		model.addObject("page", page.getPage());
		model.addObject("size", page.getSize());
		model.addObject("totalPages", page.getTotalPages());
		model.addObject("hasPrevious", page.getPage() > 1);
		model.addObject("hasNext", page.isHasMore());
		model.addObject("previousPage", page.getPage() - 1);
		model.addObject("nextPage", page.getPage() + 1);
		model.addObject("byGender", service.byGender(all));
		model.addObject("byAgeBand", service.byAgeBand(all));
	}
	
	// ---------------------------------------------------------------- helpers
	
	/**
	 * @return the upper-cased gender when it is one this module can filter on, otherwise null, so a
	 *         hand-edited URL cannot push an unexpected value into the query. Anything unrecognised,
	 *         including OpenMRS's {@code U}, degrades to "no gender filter" rather than an error page.
	 */
	static String normaliseGender(String gender) {
		String value = parseText(gender);
		if (value == null) {
			return null;
		}
		String upper = value.toUpperCase();
		for (String candidate : GENDERS) {
			if (candidate.equals(upper)) {
				return upper;
			}
		}
		return null;
	}
	
	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}
	
	private static String monthsAgo(int months) {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.MONTH, months);
		return format(calendar.getTime());
	}
	
	private static String today() {
		return format(new Date());
	}
	
	private static String format(Date date) {
		return new SimpleDateFormat(ISO_DATE).format(date);
	}
	
	/**
	 * A patient row as the view needs it: the report row, plus age and date rendering the JSP should
	 * not be doing itself.
	 */
	public static final class PatientRow {
		
		private final PatientSummaryRow row;
		
		private final String ageDisplay;
		
		private final String birthdateDisplay;
		
		private final String lastVisitDisplay;
		
		private PatientRow(PatientSummaryRow row, String ageDisplay, String birthdateDisplay, String lastVisitDisplay) {
			this.row = row;
			this.ageDisplay = ageDisplay;
			this.birthdateDisplay = birthdateDisplay;
			this.lastVisitDisplay = lastVisitDisplay;
		}
		
		static PatientRow of(PatientSummaryRow row) {
			return new PatientRow(row, formatAge(row.getAgeInMonths()), formatDate(row.getBirthdate()),
			        formatDate(row.getLastVisit()));
		}
		
		public PatientSummaryRow getRow() {
			return row;
		}
		
		public String getDisplay() {
			return row.getDisplay();
		}
		
		public String getIdentifier() {
			return row.getIdentifier();
		}
		
		public String getGender() {
			return row.getGender();
		}
		
		public String getLocationName() {
			return row.getLocationName();
		}
		
		public String getProgramName() {
			return row.getProgramName();
		}
		
		public String getAgeDisplay() {
			return ageDisplay;
		}
		
		public String getBirthdateDisplay() {
			return birthdateDisplay;
		}
		
		public String getLastVisitDisplay() {
			return lastVisitDisplay;
		}
		
		/**
		 * @return the age as years and months, e.g. {@code 3y 4m}, or a dash when the age is unknown
		 */
		private static String formatAge(Integer ageInMonths) {
			if (ageInMonths == null || ageInMonths < 0) {
				return "-";
			}
			if (ageInMonths < 24) {
				return ageInMonths + "m";
			}
			return (ageInMonths / 12) + "y " + (ageInMonths % 12) + "m";
		}
		
		private static String formatDate(Date date) {
			return date == null ? "-" : new SimpleDateFormat(ISO_DATE).format(date);
		}
	}
	
	/**
	 * Kept package-private so a test can assert the age rendering rules without a Spring context.
	 *
	 * @param ageInMonths an age in months, or null
	 * @return the display string for that age
	 */
	static String ageDisplay(Integer ageInMonths) {
		return PatientRow.formatAge(ageInMonths);
	}
}
