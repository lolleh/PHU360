/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dao;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.CrossTabRow;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;

/**
 * The queries behind the programme dashboards.
 * <p>
 * Every method takes the programme UUID rather than an id, because that is what the dashboard
 * definition file carries, and a mismatched UUID then yields no figures rather than another
 * programme's figures. Every clinical measurement is additionally restricted to the programme's own
 * patients, so no programme dashboard can report on the whole clinic. The one deliberate exception
 * is {@link #countDiagnosesByTreatmentAcrossClinic}, which backs the built-in clinic-wide matrix
 * and is named so that reading the call site makes that plain.
 */
public interface ProgramDashboardDao {
	
	/**
	 * @param programUuid the programme UUID
	 * @return true when this server has a non-retired programme with that UUID
	 */
	boolean programExists(String programUuid);
	
	/**
	 * @param programUuids programme UUIDs to look up
	 * @return the subset that exists on this server, lower-cased
	 */
	Set<String> existingProgramUuids(Collection<String> programUuids);
	
	/**
	 * @param programUuid the programme UUID
	 * @return the UUIDs of non-retired programmes that exist, in name order, for naming dashboards
	 */
	List<String> allProgramUuids();
	
	/**
	 * Counts patients holding an unvoided enrolment in the programme that overlaps the range.
	 *
	 * @param programUuid the programme UUID
	 * @param from range start, inclusive
	 * @param to range end, inclusive
	 * @return the number of distinct patients
	 */
	long countActiveEnrollments(String programUuid, Date from, Date to);
	
	/**
	 * Counts patients whose enrolment in the programme began inside the range.
	 *
	 * @return the number of distinct patients
	 */
	long countNewEnrollments(String programUuid, Date from, Date to);
	
	/**
	 * Counts patients whose programme enrolment was completed inside the range.
	 *
	 * @return the number of distinct patients
	 */
	long countCompletedEnrollments(String programUuid, Date from, Date to);
	
	/**
	 * Counts encounters belonging to the programme's patients inside the range.
	 *
	 * @return the number of encounters
	 */
	long countEncounters(String programUuid, Date from, Date to);
	
	/**
	 * Splits the active enrolment by recorded gender.
	 *
	 * @return one row per gender present, "U" for unrecorded
	 */
	List<BreakdownRow> enrolledByGender(String programUuid, Date from, Date to);
	
	/**
	 * Splits the active enrolment into age bands.
	 *
	 * @return one row per band, including "Unknown" for patients with no recorded birthdate
	 */
	List<BreakdownRow> enrolledByAgeBand(String programUuid, Date from, Date to);
	
	/**
	 * Buckets new enrolments by calendar month across the range.
	 *
	 * @return one point per month that has at least one enrolment, in order
	 */
	List<TimeSeriesPoint> enrollmentsByMonth(String programUuid, Date from, Date to);
	
	/**
	 * Counts patients with at least one observation of the given concepts inside the range.
	 *
	 * @param conceptIds the concept ids resolved from the indicator definition
	 * @return the number of distinct patients of the programme
	 */
	long countPatientsWithObservations(String programUuid, Date from, Date to, Collection<Integer> conceptIds);
	
	/**
	 * Counts observations of the given concepts inside the range.
	 *
	 * @return the number of observation rows
	 */
	long countObservations(String programUuid, Date from, Date to, Collection<Integer> conceptIds);
	
	/**
	 * Counts observations of the given concepts whose numeric value falls below the threshold.
	 *
	 * @param valueBelow the exclusive upper limit, from the indicator definition: an observation
	 *            exactly at the threshold does not count, which is what makes "birth weight below
	 *            2.5kg" stop at 2.5
	 * @return the number of observation rows
	 */
	long countObservationsBelow(String programUuid, Date from, Date to, Collection<Integer> conceptIds, double valueBelow);
	
	/**
	 * @return the mean numeric value of the given concepts, or null when nothing numeric was recorded
	 */
	Double averageObservationValue(String programUuid, Date from, Date to, Collection<Integer> conceptIds);
	
	/**
	 * Splits observations of the given concepts by their coded answer.
	 *
	 * @return one row per answer present, most frequent first
	 */
	List<BreakdownRow> observationValueDistribution(String programUuid, Date from, Date to, Collection<Integer> conceptIds);
	
	/**
	 * Cross-tabulates the coded diagnoses the programme's patients received against the drug treatments
	 * ordered on the same visit.
	 * <p>
	 * Scoped to the programme's own patients and to the range, so the matrix describes the same
	 * population as the tiles above it.
	 *
	 * @param limit the most pairs to return, most frequent first
	 * @return one row per diagnosis/treatment pair present, or empty when this server has no diagnosis
	 *         or drug-order table
	 */
	List<CrossTabRow> countDiagnosesByTreatment(String programUuid, Date from, Date to, int limit);
	
	/**
	 * Cross-tabulates coded diagnoses against drug treatments for the whole clinic, ignoring programme
	 * membership. This is the one query here that is not scoped to a programme, and it exists only for
	 * the built-in clinic-wide dashboard.
	 *
	 * @param limit the most pairs to return, most frequent first
	 * @return one row per diagnosis/treatment pair present, or empty when this server has no diagnosis
	 *         or drug-order table
	 */
	List<CrossTabRow> countDiagnosesByTreatmentAcrossClinic(Date from, Date to, int limit);
	
	/**
	 * Resolves the concepts an indicator named.
	 *
	 * @param uuids concept UUIDs from the definition
	 * @param names concept names from the definition, matched case-insensitively
	 * @return the matching concept ids, never null
	 */
	Set<Integer> resolveConceptIds(Collection<String> uuids, Collection<String> names);
	
	/**
	 * @param names concept names from the definition
	 * @return the names this server does not have a concept for, so the dashboard can say which
	 */
	Set<String> unresolvedConceptNames(Collection<String> names);
}
