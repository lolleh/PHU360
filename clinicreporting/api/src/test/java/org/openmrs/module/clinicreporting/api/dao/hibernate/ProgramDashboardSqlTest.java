/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.dao.hibernate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dao.NativeSqlTestBase;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.CrossTab;
import org.openmrs.module.clinicreporting.api.dto.CrossTabRow;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;

/**
 * Executes the programme dashboard SQL for real.
 * <p>
 * These queries are native SQL, so a wrong column name or alias would otherwise only surface in
 * production. The cases that matter are the ones where getting the query wrong produces a plausible
 * number: counting enrolment rows instead of patients, treating a voided enrolment as real, and
 * letting a patient's observations leak into a programme they never enrolled in.
 */
public class ProgramDashboardSqlTest extends NativeSqlTestBase {
	
	private static final String PMTCT = "prog-pmtct";
	
	private static final String HIV_PROGRAM = "prog-hiv";
	
	private HibernateProgramDashboardDao dao;
	
	private static Date date(int year, int month, int day) {
		Calendar calendar = Calendar.getInstance();
		calendar.clear();
		calendar.set(year, month - 1, day, 12, 0, 0);
		return calendar.getTime();
	}
	
	@Before
	public void seed() throws Exception {
		dao = dao(new HibernateProgramDashboardDao());
		clear();
		seedProgrammes();
		seedPatients();
	}
	
	private void clear() throws Exception {
		execute("DELETE FROM obs", "DELETE FROM encounter_diagnosis", "DELETE FROM drug_order", "DELETE FROM orders",
		    "DELETE FROM encounter", "DELETE FROM patient_program", "DELETE FROM patient", "DELETE FROM person",
		    "DELETE FROM program", "DELETE FROM concept_name", "DELETE FROM concept");
	}
	
	private void seedProgrammes() throws Exception {
		execute("INSERT INTO program (program_id, name, uuid, retired) VALUES (1, 'PMTCT', '" + PMTCT + "', FALSE)",
		    "INSERT INTO program (program_id, name, uuid, retired) VALUES (2, 'HIV', '" + HIV_PROGRAM + "', FALSE)",
		    // A retired programme must not attract anybody's figures.
		    "INSERT INTO program (program_id, name, uuid, retired) VALUES (3, 'Old', 'prog-retired', TRUE)");
	}
	
	/**
	 * Six patients, deliberately awkward:
	 * <ul>
	 * <li>1 and 2 are enrolled in PMTCT, 3 only in HIV;</li>
	 * <li>4 is enrolled twice in PMTCT, to catch a query that counts rows;</li>
	 * <li>5's PMTCT enrolment was voided, so they are not enrolled at all;</li>
	 * <li>6 enrolled in PMTCT but completed before the reporting period, so they are not active in it,
	 * while 7 completed inside it, so they are active for part of it and count as a completion;</li>
	 * <li>3 has no recorded gender or birthdate, to land in the "Not recorded" and "Unknown"
	 * bands.</li>
	 * </ul>
	 */
	private void seedPatients() throws Exception {
		execute("INSERT INTO person (person_id, uuid, gender, birthdate, dead, voided, date_created) VALUES "
		        + "(1, 'p-1', 'F', '1995-03-02 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00'), "
		        + "(2, 'p-2', 'F', '1996-07-11 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00'), "
		        + "(3, 'p-3', NULL, NULL, FALSE, FALSE, '2020-01-01 00:00:00'), "
		        + "(4, 'p-4', 'M', '1980-01-20 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00'), "
		        + "(5, 'p-5', 'M', '1990-06-06 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00'), "
		        + "(6, 'p-6', 'F', '1994-09-09 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00'), "
		        + "(7, 'p-7', 'F', '1994-09-09 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00')");
		execute("INSERT INTO patient (patient_id, voided) VALUES (1, FALSE), (2, FALSE), (3, FALSE), (4, FALSE), "
		        + "(5, FALSE), (6, FALSE), (7, FALSE)");
		
		execute(
		    "INSERT INTO patient_program (patient_program_id, patient_id, program_id, date_enrolled, date_completed, voided, date_created) VALUES "
		            + "(1, 1, 1, '2023-11-15 00:00:00', NULL, FALSE, '2023-11-15 00:00:00'), "
		            + "(2, 2, 1, '2024-02-10 00:00:00', NULL, FALSE, '2024-02-10 00:00:00'), "
		            + "(3, 3, 2, '2024-01-05 00:00:00', NULL, FALSE, '2024-01-05 00:00:00'), "
		            + "(4, 4, 1, '2023-06-01 00:00:00', NULL, FALSE, '2023-06-01 00:00:00'), "
					// Re-enrolment: two rows, one patient.
		            + "(5, 4, 1, '2024-03-20 00:00:00', NULL, FALSE, '2024-03-20 00:00:00'), "
					// Voided: never enrolled.
		            + "(6, 5, 1, '2024-01-05 00:00:00', NULL, TRUE, '2024-01-05 00:00:00'), "
					// Completed before the period, so not active inside it.
		            + "(7, 6, 1, '2023-01-05 00:00:00', '2023-12-31 00:00:00', FALSE, '2023-01-05 00:00:00'), "
					// Completed inside the period: active for part of it, and a completion.
		            + "(8, 7, 1, '2023-01-05 00:00:00', '2024-05-31 00:00:00', FALSE, '2023-01-05 00:00:00')");
	}
	
	private void seedConceptsAndObs() throws Exception {
		execute("INSERT INTO concept (concept_id, uuid, short_name, retired) VALUES "
		        + "(1, 'c-hiv', 'HIV', NULL), (2, 'c-positive', 'Positive', NULL)");
		execute(
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, locale_preferred, voided, date_created) VALUES "
		            + "(1, 1, 'HIV Status', 'en', TRUE, FALSE, '2020-01-01 00:00:00'), "
		            + "(2, 2, 'Positive', 'en', TRUE, FALSE, '2020-01-01 00:00:00'), "
		            + "(3, 2, 'Negatif', 'fr', TRUE, FALSE, '2020-01-01 00:00:00')");
		// Patient 1 tested positive, patient 2 tested negative, patient 4 has two observations so a
		// patient-level count and an observation count differ.
		execute("INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, status, voided, date_created) VALUES "
		        + "(1, 1, 1, '2024-03-01 09:00:00', 'FINAL', FALSE, '2024-03-01 09:00:00'), "
		        + "(2, 2, 1, '2024-03-02 09:00:00', 'FINAL', FALSE, '2024-03-02 09:00:00'), "
		        + "(3, 4, 1, '2024-03-03 09:00:00', 'FINAL', FALSE, '2024-03-03 09:00:00'), "
		        + "(4, 4, 1, '2024-04-03 09:00:00', 'FINAL', FALSE, '2024-04-03 09:00:00'), "
				// Voided, so it must not count.
		        + "(5, 4, 1, '2024-04-04 09:00:00', 'FINAL', TRUE, '2024-04-04 09:00:00'), "
				// Patient 3 is in the HIV programme only: this must not appear on the PMTCT dashboard.
		        + "(6, 3, 1, '2024-03-05 09:00:00', 'FINAL', FALSE, '2024-03-05 09:00:00')");
	}
	
	private static Date from() {
		return date(2024, 1, 1);
	}
	
	private static Date to() {
		return date(2024, 12, 31);
	}
	
	// ---------------------------------------------------------------- programme lookups
	
	@Test
	public void programExists_shouldBeFalseForAnAbsentOrRetiredProgramme() {
		assertTrue(dao.programExists(PMTCT));
		assertTrue(dao.programExists(PMTCT.toUpperCase()));
		assertFalse(dao.programExists("prog-missing"));
		assertFalse(dao.programExists("prog-retired"));
		assertFalse(dao.programExists(null));
	}
	
	@Test
	public void existingProgramUuids_shouldIgnoreProgrammesThisServerDoesNotHave() {
		assertEquals(1, dao.existingProgramUuids(java.util.Collections.singletonList(PMTCT)).size());
		assertEquals(2, dao.existingProgramUuids(java.util.Arrays.asList(PMTCT, HIV_PROGRAM, "nope")).size());
		assertTrue(dao.existingProgramUuids(null).isEmpty());
		assertTrue(dao.existingProgramUuids(java.util.Collections.<String> emptyList()).isEmpty());
	}
	
	// ---------------------------------------------------------------- enrolment figures
	
	@Test
	public void activeEnrollments_shouldCountPatientsNotRows() {
		// Patients 1, 2, 4 and 7. Patient 4 has two enrolments and is still one patient; patient 5's
		// row is voided and patient 6's finished before the period began.
		assertEquals(4L, dao.countActiveEnrollments(PMTCT, from(), to()));
	}
	
	@Test
	public void activeEnrollments_shouldIgnoreAnotherProgrammesPatients() {
		assertEquals(0L, dao.countActiveEnrollments("prog-missing", from(), to()));
		// Patient 3's only enrolment is in the HIV programme.
		assertEquals(1L, dao.countActiveEnrollments(HIV_PROGRAM, from(), to()));
	}
	
	@Test
	public void newEnrollments_shouldOnlyCountEnrolmentsThatBeganInTheRange() {
		// Patient 2 (February) and patient 4 (March, re-enrolment). Patient 1 enrolled the year before.
		assertEquals(2L, dao.countNewEnrollments(PMTCT, from(), to()));
		// A range that excludes February and March sees nobody new.
		assertEquals(0L, dao.countNewEnrollments(PMTCT, date(2024, 5, 1), date(2024, 12, 31)));
	}
	
	@Test
	public void completedEnrollments_shouldOnlyCountCompletionsInTheRange() {
		assertEquals(1L, dao.countCompletedEnrollments(PMTCT, from(), to()));
		assertEquals(0L, dao.countCompletedEnrollments(PMTCT, date(2024, 1, 1), date(2024, 3, 31)));
	}
	
	@Test
	public void encounters_shouldBeLimitedToTheProgrammesPatients() throws Exception {
		execute("INSERT INTO encounter (encounter_id, patient_id, encounter_datetime, voided, date_created) VALUES "
		        + "(1, 1, '2024-03-01 09:00:00', FALSE, '2024-03-01 09:00:00'), "
		        + "(2, 1, '2024-04-01 09:00:00', FALSE, '2024-04-01 09:00:00'), "
		        + "(3, 3, '2024-04-01 09:00:00', FALSE, '2024-04-01 09:00:00'), "
		        + "(4, 1, '2024-05-01 09:00:00', TRUE, '2024-05-01 09:00:00')");
		// Two unvoided encounters for patient 1; patient 3 belongs to another programme, and encounter
		// 4 is voided.
		assertEquals(2L, dao.countEncounters(PMTCT, from(), to()));
	}
	
	// ---------------------------------------------------------------- breakdowns
	
	@Test
	public void enrolledByGender_shouldLabelEachGender() throws Exception {
		Map<String, Long> byLabel = counts(dao.enrolledByGender(PMTCT, from(), to()));
		assertEquals(Long.valueOf(3L), byLabel.get("Female"));
		assertEquals(Long.valueOf(1L), byLabel.get("Male"));
	}
	
	@Test
	public void enrolledByAgeBand_shouldKeepTheBandsOfThePatientsRegister() throws Exception {
		// The same band names and the same Unknown handling as the patients tab, so a dashboard and a
		// register cannot mean different things by "15-49y".
		execute("INSERT INTO person (person_id, uuid, gender, birthdate, voided, date_created) VALUES "
		        + "(9, 'p-9', 'F', NULL, FALSE, '2020-01-01 00:00:00')");
		execute("INSERT INTO patient (patient_id, voided) VALUES (9, FALSE)");
		execute(
		    "INSERT INTO patient_program (patient_program_id, patient_id, program_id, date_enrolled, voided, date_created) "
		            + "VALUES (9, 9, 1, '2024-01-05 00:00:00', FALSE, '2024-01-05 00:00:00')");
		
		Map<String, Long> byLabel = counts(dao.enrolledByAgeBand(PMTCT, from(), to()));
		assertTrue(byLabel.keySet().toString(), byLabel.containsKey("15-49y"));
		assertTrue(byLabel.containsKey("50+y"));
		assertTrue(byLabel.containsKey("Unknown"));
		assertEquals(Long.valueOf(1L), byLabel.get("Unknown"));
		// Every band is present even at zero, so a chart has a stable set of categories.
		assertEquals(6, byLabel.size());
	}
	
	@Test
	public void enrollmentsByMonth_shouldFillEmptyMonthsAndCountPatients() throws Exception {
		List<TimeSeriesPoint> series = dao.enrollmentsByMonth(PMTCT, date(2024, 2, 1), date(2024, 4, 30));
		assertEquals("2024-02", series.get(0).getPeriod());
		assertEquals(1L, series.get(0).getCount());
		assertEquals("2024-03", series.get(1).getPeriod());
		assertEquals(1L, series.get(1).getCount());
		// April has nobody in it and must still appear: a gap in an enrolment trend is a finding.
		assertEquals("2024-04", series.get(2).getPeriod());
		assertEquals(0L, series.get(2).getCount());
		assertEquals(3, series.size());
	}
	
	// ---------------------------------------------------------------- clinical measures
	
	@Test
	public void patientsWithObservations_shouldCountPatientsAndStayInsideTheProgramme() throws Exception {
		seedConceptsAndObs();
		// Patients 1, 2 and 4 have an HIV result. Patient 4's two unvoided observations are one patient,
		// so this is three patients against four observations.
		assertEquals(3L, dao.countPatientsWithObservations(PMTCT, from(), to(), java.util.Arrays.asList(1)));
		// Patient 3's observation belongs to the HIV programme and must not be counted here.
		assertEquals(0L, dao.countPatientsWithObservations("prog-missing", from(), to(), java.util.Arrays.asList(1)));
	}
	
	@Test
	public void observations_shouldCountEveryRow() throws Exception {
		seedConceptsAndObs();
		assertEquals(4L, dao.countObservations(PMTCT, from(), to(), java.util.Arrays.asList(1)));
	}
	
	@Test
	public void observationsBelow_shouldCountOnlyTheValuesUnderTheLimit() throws Exception {
		seedConceptsAndObs();
		execute(
		    "INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, value_numeric, status, voided, date_created) VALUES "
		            + "(7, 1, 1, '2024-03-06 09:00:00', 2.4, 'FINAL', FALSE, '2024-03-06 09:00:00'), "
		            + "(8, 2, 1, '2024-03-07 09:00:00', 2.5, 'FINAL', FALSE, '2024-03-07 09:00:00'), "
		            + "(9, 4, 1, '2024-03-08 09:00:00', 3.1, 'FINAL', FALSE, '2024-03-08 09:00:00'), "
					// A row with no number is not under the limit; it is unmeasured.
		            + "(10, 1, 1, '2024-03-09 09:00:00', NULL, 'FINAL', FALSE, '2024-03-09 09:00:00')");
		// 2.4 counts and 2.5 does not: the limit is exclusive, so a baby weighing exactly 2.5 kg is not
		// recorded as low birth weight, which is how the WHO definition reads.
		assertEquals(1L, dao.countObservationsBelow(PMTCT, from(), to(), java.util.Arrays.asList(1), 2.5));
		assertEquals(2L, dao.countObservationsBelow(PMTCT, from(), to(), java.util.Arrays.asList(1), 2.6));
		assertEquals(0L, dao.countObservationsBelow(PMTCT, from(), to(), java.util.Arrays.asList(1), 1.0));
	}
	
	@Test
	public void averageValue_shouldIgnoreRowsWithNoNumber() throws Exception {
		seedConceptsAndObs();
		execute(
		    "INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, value_numeric, status, voided, date_created) VALUES "
		            + "(7, 1, 1, '2024-03-06 09:00:00', 140.0, 'FINAL', FALSE, '2024-03-06 09:00:00'), "
		            + "(8, 2, 1, '2024-03-07 09:00:00', 130.0, 'FINAL', FALSE, '2024-03-07 09:00:00')");
		Double average = dao.averageObservationValue(PMTCT, from(), to(), java.util.Arrays.asList(1));
		assertEquals(135.0, average, 0.001);
	}
	
	@Test
	public void averageValue_shouldBeNullWhenNothingNumericWasRecorded() throws Exception {
		seedConceptsAndObs();
		assertNull(dao.averageObservationValue(PMTCT, from(), to(), java.util.Arrays.asList(1)));
	}
	
	@Test
	public void valueDistribution_shouldNameTheCodedAnswerAndGroupTheRest() throws Exception {
		seedConceptsAndObs();
		execute(
		    "INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, value_numeric, value_text, status, voided, date_created) "
		            + "VALUES (7, 1, 1, '2024-03-06 09:00:00', NULL, NULL, 'FINAL', FALSE, '2024-03-06 09:00:00'), "
					// A coded answer pointing at the concept_name row for "Positive".
		            + "(8, 2, 1, '2024-03-07 09:00:00', NULL, NULL, 'FINAL', FALSE, '2024-03-07 09:00:00'), "
		            + "(9, 4, 1, '2024-03-08 09:00:00', 37.5, NULL, 'FINAL', FALSE, '2024-03-08 09:00:00')");
		execute("UPDATE obs SET value_coded_name_id = 2 WHERE obs_id = 8");
		
		Map<String, Long> byLabel = counts(
		    dao.observationValueDistribution(PMTCT, from(), to(), java.util.Arrays.asList(1)));
		assertEquals(Long.valueOf(1L), byLabel.get("Positive"));
		assertEquals(Long.valueOf(1L), byLabel.get("37.5"));
		// Observations with no answer at all still get a row, so the total of the distribution adds up
		// to the total of the observations it describes.
		assertEquals(7L, dao.countObservations(PMTCT, from(), to(), java.util.Arrays.asList(1)));
		assertEquals(Long.valueOf(5L), byLabel.get("No answer recorded"));
	}
	
	// ---------------------------------------------------------------- diagnoses by treatment
	
	/**
	 * Programmes 1 and 2 already exist; patients 1, 2, 4 and 7 are PMTCT-active and patient 3 belongs
	 * to the HIV programme only. Seeding concepts 3 and 4 as the drug treatments keeps them distinct
	 * from the diagnosis concept 1.
	 */
	private void seedDiagnosesAndOrders() throws Exception {
		execute("INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (1, 'HIV', 'c-hiv', NULL)",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (1, 1, 'HIV status', 'en', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (3, 'ART', 'c-art', FALSE)",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (3, 3, 'ART', 'en', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (4, 'COTRIM', 'c-cotrim', FALSE)",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (4, 4, 'Cotrimoxazole', 'en', FALSE, '2020-01-01 00:00:00')");
		execute(
		    // Patient 1 visit: diagnosed HIV, treated with ART.
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_datetime, voided, date_created) "
		            + "VALUES (1, 1, '2024-03-01 09:00:00', FALSE, '2024-03-01 09:00:00')",
		    "INSERT INTO encounter_diagnosis (diagnosis_id, encounter_id, patient_id, diagnosis_coded, voided, date_created) "
		            + "VALUES (1, 1, 1, 1, FALSE, '2024-03-01 09:00:00')",
		    "INSERT INTO orders (order_id, patient_id, encounter_id, concept_id, voided, date_created) "
		            + "VALUES (1, 1, 1, 3, FALSE, '2024-03-01 09:10:00')",
		    "INSERT INTO drug_order (order_id, drug_inventory_id) VALUES (1, 100)",
		    // The same patient, a later visit: same diagnosis, a different treatment.
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_datetime, voided, date_created) "
		            + "VALUES (2, 1, '2024-04-01 09:00:00', FALSE, '2024-04-01 09:00:00')",
		    "INSERT INTO encounter_diagnosis (diagnosis_id, encounter_id, patient_id, diagnosis_coded, voided, date_created) "
		            + "VALUES (2, 2, 1, 1, FALSE, '2024-04-01 09:00:00')",
		    "INSERT INTO orders (order_id, patient_id, encounter_id, concept_id, voided, date_created) "
		            + "VALUES (2, 1, 2, 4, FALSE, '2024-04-01 09:10:00')",
		    "INSERT INTO drug_order (order_id, drug_inventory_id) VALUES (2, 101)",
		    // Patient 4 visit: diagnosed HIV, treated with ART.
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_datetime, voided, date_created) "
		            + "VALUES (3, 4, '2024-05-01 09:00:00', FALSE, '2024-05-01 09:00:00')",
		    "INSERT INTO encounter_diagnosis (diagnosis_id, encounter_id, patient_id, diagnosis_coded, voided, date_created) "
		            + "VALUES (3, 3, 4, 1, FALSE, '2024-05-01 09:00:00')",
		    "INSERT INTO orders (order_id, patient_id, encounter_id, concept_id, voided, date_created) "
		            + "VALUES (3, 4, 3, 3, FALSE, '2024-05-01 09:10:00')",
		    "INSERT INTO drug_order (order_id, drug_inventory_id) VALUES (3, 102)",
		    // Patient 3 is in the HIV programme only, so this must not appear on the PMTCT dashboard.
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_datetime, voided, date_created) "
		            + "VALUES (4, 3, '2024-06-01 09:00:00', FALSE, '2024-06-01 09:00:00')",
		    "INSERT INTO encounter_diagnosis (diagnosis_id, encounter_id, patient_id, diagnosis_coded, voided, date_created) "
		            + "VALUES (4, 4, 3, 1, FALSE, '2024-06-01 09:00:00')",
		    "INSERT INTO orders (order_id, patient_id, encounter_id, concept_id, voided, date_created) "
		            + "VALUES (4, 3, 4, 3, FALSE, '2024-06-01 09:10:00')",
		    "INSERT INTO drug_order (order_id, drug_inventory_id) VALUES (4, 103)",
		    // Patient 2 visit fell before the reporting period and so must not count.
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_datetime, voided, date_created) "
		            + "VALUES (5, 2, '2023-12-31 09:00:00', FALSE, '2023-12-31 09:00:00')",
		    "INSERT INTO encounter_diagnosis (diagnosis_id, encounter_id, patient_id, diagnosis_coded, voided, date_created) "
		            + "VALUES (5, 5, 2, 1, FALSE, '2023-12-31 09:00:00')",
		    "INSERT INTO orders (order_id, patient_id, encounter_id, concept_id, voided, date_created) "
		            + "VALUES (5, 2, 5, 3, FALSE, '2023-12-31 09:10:00')",
		    "INSERT INTO drug_order (order_id, drug_inventory_id) VALUES (5, 104)");
		execute(
		    // A voided diagnosis must not appear, and neither must an order with no drug_order row.
		    "INSERT INTO encounter_diagnosis (diagnosis_id, encounter_id, patient_id, diagnosis_coded, voided, date_created) "
		            + "VALUES (6, 2, 1, 1, TRUE, '2024-04-01 09:00:00')",
		    "INSERT INTO orders (order_id, patient_id, encounter_id, concept_id, voided, date_created) "
		            + "VALUES (6, 1, 2, 2, FALSE, '2024-04-01 09:20:00')");
	}
	
	@Test
	public void countDiagnosesByTreatment_shouldCrossTabWithinTheProgrammesPatients() throws Exception {
		seedDiagnosesAndOrders();
		
		List<CrossTabRow> pairs = dao.countDiagnosesByTreatment(PMTCT, from(), to(), 100);
		assertEquals(2, pairs.size());
		// HIV status outranks its own taller pair, and every pair belongs to a PMTCT patient.
		CrossTab matrix = CrossTab.build(pairs, 10, 10);
		assertEquals(1, matrix.getRows().size());
		assertEquals(2, matrix.getColumns().size());
		assertEquals("HIV status", matrix.getRows().get(0).getLabel());
		assertEquals(3L, matrix.getRows().get(0).getCount());
		assertEquals("ART", matrix.getColumns().get(0).getLabel());
		assertEquals("Cotrimoxazole", matrix.getColumns().get(1).getLabel());
		assertEquals(Long.valueOf(2), matrix.getCells().get(0).get(0));
		assertEquals(Long.valueOf(1), matrix.getCells().get(0).get(1));
		assertEquals(3L, matrix.getGrandTotal());
	}
	
	@Test
	public void countDiagnosesByTreatment_shouldStayInsideTheProgrammeAndTheRange() throws Exception {
		seedDiagnosesAndOrders();
		
		// Patient 3's ART order belongs to the HIV programme and patient 2's visit is before the range,
		// so the PMTCT matrix sees only patients 1 and 4: two pairs adding up to three orders.
		List<CrossTabRow> pairs = dao.countDiagnosesByTreatment(PMTCT, from(), to(), 100);
		assertEquals(2, pairs.size());
		assertEquals(3L, CrossTab.build(pairs, 10, 10).getGrandTotal());
		
		List<CrossTabRow> hiv = dao.countDiagnosesByTreatment(HIV_PROGRAM, from(), to(), 100);
		assertEquals(1, hiv.size());
		assertEquals(1L, hiv.get(0).getCount());
		
		assertEquals(0, dao.countDiagnosesByTreatment("prog-missing", from(), to(), 100).size());
	}
	
	@Test
	public void countDiagnosesByTreatment_shouldHonourThePairLimit() throws Exception {
		seedDiagnosesAndOrders();
		assertEquals(2, dao.countDiagnosesByTreatment(PMTCT, from(), to(), 2).size());
		assertEquals(1, dao.countDiagnosesByTreatment(PMTCT, from(), to(), 1).size());
	}
	
	@Test
	public void countDiagnosesByTreatmentAcrossClinic_shouldIgnoreProgrammeMembership() throws Exception {
		seedDiagnosesAndOrders();
		
		// The clinic-wide matrix counts patients 1 and 4 (PMTCT) as well as patient 3, who belongs to
		// the HIV programme only. The programme-scoped query deliberately excludes patient 3.
		CrossTab matrix = CrossTab.build(dao.countDiagnosesByTreatmentAcrossClinic(from(), to(), 100), 10, 10);
		assertEquals(1, matrix.getRows().size());
		assertEquals("HIV status", matrix.getRows().get(0).getLabel());
		assertEquals(2, matrix.getColumns().size());
		assertEquals("ART", matrix.getColumns().get(0).getLabel());
		assertEquals("Cotrimoxazole", matrix.getColumns().get(1).getLabel());
		assertEquals(Long.valueOf(3), matrix.getCells().get(0).get(0));
		assertEquals(Long.valueOf(1), matrix.getCells().get(0).get(1));
		assertEquals(4L, matrix.getGrandTotal());
		
		// The scoped queries see less: PMTCT two pairs worth three orders, HIV one pair.
		assertEquals(3L, CrossTab.build(dao.countDiagnosesByTreatment(PMTCT, from(), to(), 100), 10, 10).getGrandTotal());
		assertEquals(1L,
		    CrossTab.build(dao.countDiagnosesByTreatment(HIV_PROGRAM, from(), to(), 100), 10, 10).getGrandTotal());
	}
	
	@Test
	public void countDiagnosesByTreatmentAcrossClinic_shouldStayInsideTheRangeAndHonourTheLimit() throws Exception {
		seedDiagnosesAndOrders();
		
		// Patient 2's visit is before the range, so it counts nowhere. Only the two in-range pairs remain.
		assertEquals(2, dao.countDiagnosesByTreatmentAcrossClinic(from(), to(), 100).size());
		assertEquals(2, dao.countDiagnosesByTreatmentAcrossClinic(from(), to(), 2).size());
		assertEquals(1, dao.countDiagnosesByTreatmentAcrossClinic(from(), to(), 1).size());
	}
	
	// ---------------------------------------------------------------- concepts
	
	@Test
	public void resolveConceptIds_shouldFindUuidsAndNames() throws Exception {
		seedConceptsAndObs();
		assertEquals(1, dao.resolveConceptIds(java.util.Collections.singletonList("c-hiv"), null).size());
		assertEquals(1, dao.resolveConceptIds(null, java.util.Arrays.asList("HIV Status")).size());
		// A name is matched whatever case it is written in.
		assertEquals(1, dao.resolveConceptIds(null, java.util.Arrays.asList("hiv status")).size());
		// An unknown concept resolves to nothing rather than throwing.
		assertTrue(dao.resolveConceptIds(java.util.Collections.singletonList("nope"), null).isEmpty());
		assertTrue(dao.resolveConceptIds(null, java.util.Collections.singletonList("Nope")).isEmpty());
		assertTrue(dao.resolveConceptIds(null, null).isEmpty());
	}
	
	@Test
	public void conceptLookup_shouldIgnoreRetiredConceptsAndNames() throws Exception {
		seedConceptsAndObs();
		// Concept carries no voided column on OpenMRS: retirement is the only lifecycle flag, so a
		// retired concept and a voided name must both be skipped.
		execute("INSERT INTO concept (concept_id, uuid, short_name, retired) VALUES (3, 'c-old', 'Old', TRUE)");
		execute(
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, locale_preferred, voided, date_created) VALUES "
		            + "(4, 3, 'Retired Concept', 'en', TRUE, FALSE, '2020-01-01 00:00:00'), "
		            + "(5, 1, 'Superseded Name', 'en', TRUE, FALSE, '2020-01-01 00:00:00')");
		execute("UPDATE concept_name SET voided = TRUE WHERE concept_name_id = 5");
		assertTrue(dao.resolveConceptIds(null, java.util.Collections.singletonList("Retired Concept")).isEmpty());
		assertTrue(dao.resolveConceptIds(null, java.util.Collections.singletonList("Superseded Name")).isEmpty());
		// A retired concept counts as missing, so the dashboard can say the dictionary lacks it.
		assertTrue(
		    dao.unresolvedConceptNames(java.util.Collections.singletonList("Retired Concept")).contains("Retired Concept"));
	}
	
	@Test
	public void unresolvedConceptNames_shouldReportWhatThisServerDoesNotHave() throws Exception {
		seedConceptsAndObs();
		assertFalse(dao.unresolvedConceptNames(java.util.Arrays.asList("HIV Status")).contains("HIV Status"));
		assertTrue(dao.unresolvedConceptNames(java.util.Arrays.asList("Condom distributed")).contains("Condom distributed"));
		assertTrue(dao.unresolvedConceptNames(null).isEmpty());
	}
	
	// ---------------------------------------------------------------- helpers
	
	private static Map<String, Long> counts(List<BreakdownRow> rows) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (BreakdownRow row : rows) {
			counts.put(row.getLabel(), Long.valueOf(row.getCount()));
		}
		return counts;
	}
}
