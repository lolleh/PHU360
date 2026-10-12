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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.clinicreporting.api.dao.ProgramDashboardDao;
import org.openmrs.module.clinicreporting.api.dashboard.BuiltinDashboard;
import org.openmrs.module.clinicreporting.api.dashboard.DashboardIndicator;
import org.openmrs.module.clinicreporting.api.dashboard.DashboardProgram;
import org.openmrs.module.clinicreporting.api.dashboard.IndicatorMetric;
import org.openmrs.module.clinicreporting.api.dashboard.IndicatorResult;
import org.openmrs.module.clinicreporting.api.dashboard.ProgramIndicatorCatalog;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.CrossTab;
import org.openmrs.module.clinicreporting.api.dto.CrossTabRow;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.service.ProgramDashboardService;

/**
 * Turns a programme's indicator definitions into figures.
 * <p>
 * The definition file decides what is measured; this class decides what happens when a measurement
 * cannot be made. That second job is most of the work, because the honest answers are rarely zero:
 * <ul>
 * <li>A concept the definition names may not exist on this server, which is reported as unavailable
 * with the concept named, rather than as a count of zero.</li>
 * <li>A rate whose denominator came out empty has no percentage, and is shown as the counts it
 * actually has.</li>
 * <li>A failure in one indicator is caught and reported against that indicator alone, so a broken
 * definition cannot empty the whole page.</li>
 * </ul>
 */
public class ProgramDashboardServiceImpl implements ProgramDashboardService {
	
	private static final Log log = LogFactory.getLog(ProgramDashboardServiceImpl.class);
	
	/**
	 * The cap on diagnosis/treatment pairs read before the tail is folded into "Other". Bounds the work
	 * a single dashboard can do; the tail it cuts off is the long tail of one-off pairs, which the
	 * "Other" bucket only ever summarises.
	 */
	private static final int PAIR_LIMIT = 1000;
	
	private ProgramDashboardDao dao;
	
	public void setDao(ProgramDashboardDao dao) {
		this.dao = dao;
	}
	
	@Override
	public List<DashboardProgram> getAvailablePrograms() {
		List<DashboardProgram> defined = ProgramIndicatorCatalog.load().getPrograms();
		Set<String> wanted = new LinkedHashSet<>();
		for (DashboardProgram program : defined) {
			wanted.add(program.getUuid());
		}
		Set<String> present = dao.existingProgramUuids(wanted);
		
		List<DashboardProgram> available = new ArrayList<>();
		for (DashboardProgram program : defined) {
			if (present.contains(program.getUuid().toLowerCase(Locale.ROOT))) {
				available.add(program);
			} else {
				log.info("Programme " + program.getUuid() + " has dashboard definitions but does not "
				        + "exist on this server, so it is not offered");
			}
		}
		// The built-in dashboards depend on no programme, so they are offered whatever this server has.
		// They come last, after the programmes an administrator configured.
		for (BuiltinDashboard builtin : BuiltinDashboard.values()) {
			available.add(builtin.toProgram());
		}
		return available;
	}
	
	@Override
	public DashboardProgram getProgram(String programUuid) {
		// Resolved before the definition file is even read, so a built-in dashboard still opens when the
		// file an administrator edited is broken, and so a reserved UUID can never collide with a
		// programme's own.
		BuiltinDashboard builtin = BuiltinDashboard.forUuid(programUuid);
		if (builtin != null) {
			return builtin.toProgram();
		}
		return ProgramIndicatorCatalog.load().findByUuid(programUuid);
	}
	
	@Override
	public List<IndicatorResult> compute(DashboardProgram program, Date from, Date to) {
		if (program == null) {
			throw new InvalidReportFilterException("No programme was given to compute");
		}
		if (from == null || to == null) {
			throw new InvalidReportFilterException("A dashboard needs both ends of the date range");
		}
		if (from.after(to)) {
			throw new InvalidReportFilterException("The start of the date range is after its end");
		}
		
		List<IndicatorResult> results = new ArrayList<>(program.getIndicators().size());
		for (DashboardIndicator indicator : program.getIndicators()) {
			results.add(computeOne(program, indicator, from, to));
		}
		return results;
	}
	
	@Override
	public CrossTab diagnosesByTreatment(String programUuid, Date from, Date to, int maxDiagnoses, int maxTreatments) {
		if (programUuid == null || programUuid.trim().isEmpty()) {
			throw new InvalidReportFilterException("No programme was given to compute");
		}
		if (from == null || to == null) {
			throw new InvalidReportFilterException("A dashboard needs both ends of the date range");
		}
		if (from.after(to)) {
			throw new InvalidReportFilterException("The start of the date range is after its end");
		}
		String uuid = programUuid.trim().toLowerCase(Locale.ROOT);
		List<CrossTabRow> pairs = dao.countDiagnosesByTreatment(uuid, from, to, PAIR_LIMIT);
		return CrossTab.build(pairs, maxDiagnoses, maxTreatments);
	}
	
	@Override
	public CrossTab diagnosesByTreatmentAcrossClinic(Date from, Date to, int maxDiagnoses, int maxTreatments) {
		if (from == null || to == null) {
			throw new InvalidReportFilterException("A dashboard needs both ends of the date range");
		}
		if (from.after(to)) {
			throw new InvalidReportFilterException("The start of the date range is after its end");
		}
		List<CrossTabRow> pairs = dao.countDiagnosesByTreatmentAcrossClinic(from, to, PAIR_LIMIT);
		return CrossTab.build(pairs, maxDiagnoses, maxTreatments);
	}
	
	/**
	 * Computes one indicator, containing any failure to that indicator alone.
	 */
	private IndicatorResult computeOne(DashboardProgram program, DashboardIndicator indicator, Date from, Date to) {
		try {
			IndicatorMetric metric = indicator.getMetric();
			
			if (metric == IndicatorMetric.ACTIVE_ENROLLMENTS) {
				return value(indicator, dao.countActiveEnrollments(program.getUuid(), from, to));
			}
			if (metric == IndicatorMetric.NEW_ENROLLMENTS) {
				return value(indicator, dao.countNewEnrollments(program.getUuid(), from, to));
			}
			if (metric == IndicatorMetric.COMPLETED_ENROLLMENTS) {
				return value(indicator, dao.countCompletedEnrollments(program.getUuid(), from, to));
			}
			if (metric == IndicatorMetric.ENCOUNTERS) {
				return value(indicator, dao.countEncounters(program.getUuid(), from, to));
			}
			if (metric == IndicatorMetric.ENROLLED_BY_GENDER) {
				return rows(indicator, dao.enrolledByGender(program.getUuid(), from, to));
			}
			if (metric == IndicatorMetric.ENROLLED_BY_AGE_BAND) {
				return rows(indicator, dao.enrolledByAgeBand(program.getUuid(), from, to));
			}
			if (metric == IndicatorMetric.ENROLLMENTS_BY_MONTH) {
				List<TimeSeriesPoint> series = dao.enrollmentsByMonth(program.getUuid(), from, to);
				// The tile counts people, not monthly buckets. A patient who re-enrols appears in two months
				// of the series, so adding the buckets up would report one person as two enrolments and would
				// disagree with the "new enrolments" figure on the same dashboard.
				return IndicatorResult.of(indicator, Double.valueOf(dao.countNewEnrollments(program.getUuid(), from, to)),
				    null, null, null, series);
			}
			if (metric == IndicatorMetric.PATIENTS_WITH_OBSERVATION) {
				Set<Integer> concepts = resolve(indicator);
				return value(indicator, dao.countPatientsWithObservations(program.getUuid(), from, to, concepts));
			}
			if (metric == IndicatorMetric.OBSERVATION_COUNT) {
				Set<Integer> concepts = resolve(indicator);
				return value(indicator, dao.countObservations(program.getUuid(), from, to, concepts));
			}
			if (metric == IndicatorMetric.LOW_VALUE_COUNT) {
				Set<Integer> concepts = resolve(indicator);
				return value(indicator, dao.countObservationsBelow(program.getUuid(), from, to, concepts,
				    indicator.getValueBelow().doubleValue()));
			}
			if (metric == IndicatorMetric.ENROLLED_COVERAGE_RATE) {
				return coverageRate(program, indicator, from, to);
			}
			if (metric == IndicatorMetric.AVERAGE_VALUE) {
				Set<Integer> concepts = resolve(indicator);
				Double average = dao.averageObservationValue(program.getUuid(), from, to, concepts);
				if (average == null) {
					return IndicatorResult.of(indicator, null, null, null, null, null);
				}
				return value(indicator, average);
			}
			if (metric == IndicatorMetric.VALUE_DISTRIBUTION) {
				Set<Integer> concepts = resolve(indicator);
				return rows(indicator, dao.observationValueDistribution(program.getUuid(), from, to, concepts));
			}
			if (metric == IndicatorMetric.POSITIVE_RATE) {
				return positiveRate(program, indicator, from, to);
			}
			return IndicatorResult.unavailable(indicator,
			    "This measurement (" + metric.getDefinitionName() + ") is not implemented by this module version.");
		}
		catch (UnavailableIndicatorException e) {
			// A concept this server does not have is a configuration gap rather than a failure, so its
			// explanation is worth showing as written.
			log.info("Indicator '" + indicator.getId() + "' on programme " + program.getUuid() + " is unavailable: "
			        + e.getMessage());
			return IndicatorResult.unavailable(indicator, e.getMessage());
		}
		catch (RuntimeException e) {
			// One bad indicator must not empty the page. The detail goes to the log; the page says which
			// indicator failed and why, without exposing a SQL fragment or an identifier.
			log.warn("Indicator '" + indicator.getId() + "' on programme " + program.getUuid() + " failed", e);
			return IndicatorResult.unavailable(indicator,
			    "This indicator could not be calculated. The server log has the detail; the other indicators on this dashboard are unaffected.");
		}
	}
	
	/**
	 * A rate is only reported as a percentage when there is a denominator to divide by. With an empty
	 * denominator the counts are still shown, because "0 tested, 0 positive" is a real answer and a
	 * blank tile is not.
	 */
	private IndicatorResult positiveRate(DashboardProgram program, DashboardIndicator indicator, Date from, Date to) {
		Set<Integer> numeratorConcepts = resolve(indicator);
		Set<Integer> denominatorConcepts = dao.resolveConceptIds(indicator.getDenominatorConceptUuids(),
		    indicator.getDenominatorConceptNames());
		if (denominatorConcepts.isEmpty()) {
			String missing = describeMissing(dao.unresolvedConceptNames(indicator.getDenominatorConceptNames()));
			return IndicatorResult.unavailable(indicator,
			    "The denominator for this rate is not on this server" + missing + ", so the rate cannot be calculated.");
		}
		long numerator = dao.countPatientsWithObservations(program.getUuid(), from, to, numeratorConcepts);
		long denominator = dao.countPatientsWithObservations(program.getUuid(), from, to, denominatorConcepts);
		
		Double percent = denominator > 0 ? Double.valueOf(numerator * 100.0 / denominator) : null;
		return IndicatorResult.of(indicator, percent, Long.valueOf(numerator), Long.valueOf(denominator), null, null);
	}
	
	/**
	 * A coverage rate divides by the programme's own enrolment rather than by another concept, so the
	 * figure can be less than 100%: that gap is the untested people the indicator exists to expose.
	 */
	private IndicatorResult coverageRate(DashboardProgram program, DashboardIndicator indicator, Date from, Date to) {
		Set<Integer> concepts = resolve(indicator);
		long enrolled = dao.countActiveEnrollments(program.getUuid(), from, to);
		long covered = dao.countPatientsWithObservations(program.getUuid(), from, to, concepts);
		// Both sides are drawn from the programme's patients, so this cannot exceed 100%. It would only
		// do so if a patient were counted in the numerator without holding an enrolment in the range,
		// which the queries do not allow.
		Double percent = enrolled > 0 ? Double.valueOf(covered * 100.0 / enrolled) : null;
		return IndicatorResult.of(indicator, percent, Long.valueOf(covered), Long.valueOf(enrolled), null, null);
	}
	
	/**
	 * Resolves an indicator's concepts, or explains which ones are missing.
	 *
	 * @return the resolved concept ids
	 * @throws UnavailableIndicatorException when this server has none of them
	 */
	private Set<Integer> resolve(DashboardIndicator indicator) {
		Set<Integer> concepts = dao.resolveConceptIds(indicator.getConceptUuids(), indicator.getConceptNames());
		if (concepts.isEmpty()) {
			String missing = describeMissing(dao.unresolvedConceptNames(indicator.getConceptNames()));
			throw new UnavailableIndicatorException("This dashboard looks for " + missing
			        + ", which this server has no concept for. Add the concept to this server, or point "
			        + "the indicator at one it has in program-indicators.json.");
		}
		return concepts;
	}
	
	/**
	 * @return a phrase naming what could not be found, for appending to a sentence
	 */
	private String describeMissing(Set<String> missingNames) {
		if (missingNames == null || missingNames.isEmpty()) {
			return "the configured concepts";
		}
		StringBuilder phrase = new StringBuilder();
		for (String name : missingNames) {
			if (phrase.length() > 0) {
				phrase.append(", ");
			}
			phrase.append('"').append(name).append('"');
		}
		return phrase.toString();
	}
	
	private IndicatorResult value(DashboardIndicator indicator, long count) {
		return IndicatorResult.of(indicator, Double.valueOf(count), Long.valueOf(count), null, null, null);
	}
	
	private IndicatorResult value(DashboardIndicator indicator, Double value) {
		return IndicatorResult.of(indicator, value, null, null, null, null);
	}
	
	private IndicatorResult rows(DashboardIndicator indicator, List<BreakdownRow> breakdown) {
		return IndicatorResult.of(indicator, null, null, null, breakdown, null);
	}
	
	/**
	 * Internal signal that an indicator cannot be computed here, as opposed to having failed. Caught by
	 * the same handler as any other failure but with a message worth showing.
	 */
	private static class UnavailableIndicatorException extends RuntimeException {
		
		private static final long serialVersionUID = 1L;
		
		UnavailableIndicatorException(String message) {
			super(message);
		}
	}
}
