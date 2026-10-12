/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dashboard;

/**
 * The measurements a programme indicator can ask for.
 * <p>
 * This vocabulary is closed on purpose. An indicator definition is a JSON file an administrator can
 * edit, so the shape of a metric decides what SQL gets built. Every value here maps onto one
 * parameterised statement written by hand in {@code HibernateProgramDashboardDao}: the file can
 * choose a measurement, name the concepts to apply it to and set a target, but it cannot contribute
 * SQL, a table name or a fragment of one.
 */
public enum IndicatorMetric {
	
	/** Patients holding an unvoided, uncompleted enrolment in the programme during the range. */
	ACTIVE_ENROLLMENTS("activeEnrollments", true, false, false),
	
	/** Patients whose enrolment in the programme began inside the range. */
	NEW_ENROLLMENTS("newEnrollments", true, false, false),
	
	/** Patients whose programme enrolment was completed inside the range. */
	COMPLETED_ENROLLMENTS("completedEnrollments", true, false, false),
	
	/** Active enrolment split by recorded gender. */
	ENROLLED_BY_GENDER("enrolledByGender", true, false, true),
	
	/** Active enrolment split into age bands, with an "Unknown" band. */
	ENROLLED_BY_AGE_BAND("enrolledByAgeBand", true, false, true),
	
	/** New enrolments per calendar month across the range, for a trend line. */
	ENROLLMENTS_BY_MONTH("enrollmentsByMonth", true, false, true),
	
	/** Encounters belonging to programme patients inside the range. */
	ENCOUNTERS("encounters", true, false, false),
	
	/**
	 * Patients with at least one recorded observation of the named concept inside the range. Counts
	 * patients, not observations, so one patient seen eight times counts once.
	 */
	PATIENTS_WITH_OBSERVATION("patientsWithObservation", true, true, false),
	
	/** Number of observations of the named concept inside the range. */
	OBSERVATION_COUNT("observationCount", true, true, false),
	
	/**
	 * Number of observations of the named concept whose numeric value falls below the threshold the
	 * definition gives. This is what makes "low birth weight" mean low birth weight: counting every
	 * recorded weight under that label would report every infant weighed, which is a different and
	 * misleading figure. The threshold is required, because without one this measurement is just
	 * {@code observationCount} wearing a name it has not earned.
	 */
	LOW_VALUE_COUNT("lowValueCount", true, true, false),
	
	/**
	 * Share of the programme's active enrolment with a recorded observation of the named concept.
	 * <p>
	 * The denominator is the enrolment itself rather than another concept, which is the difference
	 * between "how many were tested" and "how many of the people enrolled were tested". Naming the same
	 * concepts on both sides of {@link #POSITIVE_RATE} would divide a number by itself and report 100%
	 * whatever the data said.
	 */
	ENROLLED_COVERAGE_RATE("enrolledCoverageRate", true, true, false),
	
	/** Mean numeric value of the named concept. Null when the concept has no numeric values. */
	AVERAGE_VALUE("averageValue", true, true, false),
	
	/** Observations of the named concept split by their coded answer. */
	VALUE_DISTRIBUTION("valueDistribution", true, true, true),
	
	/**
	 * Share of tested patients whose result was positive: patients with an observation of the numerator
	 * concept divided by patients with an observation of any denominator concept. Both sides are named,
	 * because a positivity rate with an unnamed numerator is just a number.
	 */
	POSITIVE_RATE("positiveRate", true, true, false);
	
	private final String definitionName;
	
	private final boolean programmeScoped;
	
	private final boolean requiresConcept;
	
	private final boolean producesBreakdown;
	
	IndicatorMetric(String definitionName, boolean programmeScoped, boolean requiresConcept, boolean producesBreakdown) {
		this.definitionName = definitionName;
		this.programmeScoped = programmeScoped;
		this.requiresConcept = requiresConcept;
		this.producesBreakdown = producesBreakdown;
	}
	
	/**
	 * @return the spelling used in the indicator definition file
	 */
	public String getDefinitionName() {
		return definitionName;
	}
	
	/**
	 * @return true when the measurement is about the programme's patients rather than about any patient
	 *         on the server
	 */
	public boolean isProgrammeScoped() {
		return programmeScoped;
	}
	
	/**
	 * @return true when the definition has to name at least one concept for the measurement to mean
	 *         anything
	 */
	public boolean requiresConcept() {
		return requiresConcept;
	}
	
	/**
	 * @return true when the measurement yields labelled rows to chart rather than a single figure
	 */
	public boolean producesBreakdown() {
		return producesBreakdown;
	}
	
	/**
	 * @return true when the measurement divides one measurement by another, and so also needs the
	 *         denominator concepts
	 */
	public boolean isRatio() {
		return this == POSITIVE_RATE;
	}
	
	/**
	 * @return true when the measurement counts only the numeric values under a threshold the definition
	 *         has to supply
	 */
	public boolean requiresThreshold() {
		return this == LOW_VALUE_COUNT;
	}
	
	/**
	 * Resolves the spelling used in a definition file.
	 *
	 * @param definitionName the name from the file, case-insensitive
	 * @return the matching metric, or null when there is none
	 */
	public static IndicatorMetric fromDefinitionName(String definitionName) {
		if (definitionName == null) {
			return null;
		}
		String wanted = definitionName.trim();
		for (IndicatorMetric metric : values()) {
			if (metric.definitionName.equalsIgnoreCase(wanted)) {
				return metric;
			}
		}
		return null;
	}
	
	/**
	 * @return the names accepted in a definition file, for error messages
	 */
	public static String allDefinitionNames() {
		StringBuilder names = new StringBuilder();
		for (IndicatorMetric metric : values()) {
			if (names.length() > 0) {
				names.append(", ");
			}
			names.append(metric.definitionName);
		}
		return names.toString();
	}
}
