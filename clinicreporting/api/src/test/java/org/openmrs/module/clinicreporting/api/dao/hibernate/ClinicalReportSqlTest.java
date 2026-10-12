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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dao.NativeSqlTestBase;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.ClinicalReportFilter;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;

/**
 * Executes the clinical and register queries for real.
 */
public class ClinicalReportSqlTest extends NativeSqlTestBase {
	
	private HibernateClinicalReportDao dao;
	
	private static Date date(int year, int month, int day, int hour) {
		Calendar calendar = Calendar.getInstance();
		calendar.clear();
		calendar.set(year, month - 1, day, hour, 0, 0);
		return calendar.getTime();
	}
	
	private static ClinicalReportFilter filter() {
		ClinicalReportFilter filter = new ClinicalReportFilter();
		filter.setDateFrom(date(2024, 1, 1, 0));
		filter.setDateTo(date(2024, 12, 31, 23));
		filter.validate();
		return filter;
	}
	
	private static long count(List<BreakdownRow> rows, String key) {
		for (BreakdownRow row : rows) {
			if (key.equals(row.getKey())) {
				return row.getCount();
			}
		}
		return 0L;
	}
	
	@Before
	public void seed() throws Exception {
		dao = dao(new HibernateClinicalReportDao());
		
		execute("DELETE FROM obs", "DELETE FROM encounter_diagnosis", "DELETE FROM drug_order", "DELETE FROM orders",
		    "DELETE FROM encounter", "DELETE FROM encounter_type", "DELETE FROM patient_program", "DELETE FROM program",
		    "DELETE FROM concept_name", "DELETE FROM concept", "DELETE FROM person_name", "DELETE FROM patient_identifier",
		    "DELETE FROM location", "DELETE FROM patient", "DELETE FROM person");
		
		execute("INSERT INTO location (location_id, name, parent_location, retired) VALUES (1, 'District', NULL, FALSE)",
		    "INSERT INTO location (location_id, name, parent_location, retired) VALUES (2, 'Clinic', 1, FALSE)");
		execute(
		    "INSERT INTO person (person_id, uuid, gender, birthdate, dead, voided, date_created) "
		            + "VALUES (1, 'p-1', 'F', '1985-02-02 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO person (person_id, uuid, gender, birthdate, dead, voided, date_created) "
		            + "VALUES (2, 'p-2', 'M', '1995-03-03 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00')");
		execute("INSERT INTO patient (patient_id, voided) VALUES (1, FALSE)",
		    "INSERT INTO patient (patient_id, voided) VALUES (2, FALSE)");
		execute(
		    "INSERT INTO encounter_type (encounter_type_id, name, uuid, retired) VALUES (1, 'Outpatient', 'et-opd', FALSE)",
		    "INSERT INTO encounter_type (encounter_type_id, name, uuid, retired) VALUES (2, 'Admission', 'et-adm', FALSE)");
		execute("INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (1, 'HIV', 'c-hiv', FALSE)",
		    "INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (2, 'TEMP', 'c-temp', FALSE)",
		    "INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (3, 'WEIGHT', 'c-weight', FALSE)");
		execute(
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (1, 1, 'HIV status', 'en', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (2, 2, 'Temperature', 'en', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (3, 3, 'Weight', 'en', FALSE, '2020-01-01 00:00:00')");
		execute("INSERT INTO program (program_id, name, uuid, retired) VALUES (1, 'TB', 'prog-tb', FALSE)",
		    "INSERT INTO program (program_id, name, uuid, retired) VALUES (2, 'HIV', 'prog-hiv', FALSE)");
		execute(
		    "INSERT INTO patient_program (patient_program_id, patient_id, program_id, date_enrolled, location_id, voided, date_created) "
		            + "VALUES (1, 1, 1, '2023-01-01 00:00:00', 2, FALSE, '2023-01-01 00:00:00')",
		    "INSERT INTO patient_program (patient_program_id, patient_id, program_id, date_enrolled, location_id, voided, date_created) "
		            + "VALUES (2, 2, 2, '2023-06-01 00:00:00', 2, FALSE, '2023-06-01 00:00:00')");
		execute(
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (1, 1, 1, '2024-03-01 09:00:00', 2, FALSE, '2024-03-01 09:00:00')",
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (2, 1, 2, '2024-03-01 14:00:00', 2, FALSE, '2024-03-01 14:00:00')",
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (3, 2, 1, '2024-03-02 09:00:00', 2, FALSE, '2024-03-02 09:00:00')",
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (4, 1, 1, '2024-03-04 09:00:00', 2, FALSE, '2024-03-04 09:00:00')",
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (5, 1, 1, '2024-03-05 09:00:00', 2, TRUE, '2024-03-05 09:00:00')",
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (6, 1, 1, '2023-03-05 09:00:00', 2, FALSE, '2023-03-05 09:00:00')");
		execute(
		    "INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, location_id, value_numeric, voided, date_created) "
		            + "VALUES (1, 1, 1, '2024-03-01 09:30:00', 2, NULL, FALSE, '2024-03-01 09:30:00')",
		    "INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, location_id, value_numeric, voided, date_created) "
		            + "VALUES (2, 1, 2, '2024-03-01 09:35:00', 2, 37.5, FALSE, '2024-03-01 09:35:00')",
		    "INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, location_id, value_numeric, voided, date_created) "
		            + "VALUES (3, 2, 2, '2024-03-02 09:35:00', 2, 38.1, FALSE, '2024-03-02 09:35:00')",
		    "INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, location_id, value_numeric, voided, date_created) "
		            + "VALUES (4, 1, 3, '2024-03-04 09:35:00', 2, 62, FALSE, '2024-03-04 09:35:00')",
		    // Voided, and must not be counted.
		    "INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, location_id, value_numeric, voided, date_created) "
		            + "VALUES (5, 1, 2, '2024-03-05 09:35:00', 2, 99, TRUE, '2024-03-05 09:35:00')");
	}
	
	@Test
	public void encountersPerDay_shouldCountEncountersByDay() {
		Map<String, TimeSeriesPoint> series = dao.encountersPerDay(filter());
		assertEquals(3, series.size());
		assertEquals(2L, series.get("2024-03-01").getCount());
		assertEquals(1L, series.get("2024-03-02").getCount());
		assertEquals(1L, series.get("2024-03-04").getCount());
	}
	
	@Test
	public void encountersPerDay_shouldFilterByEncounterType() {
		ClinicalReportFilter filter = filter();
		filter.setEncounterTypeUuid("et-adm");
		Map<String, TimeSeriesPoint> series = dao.encountersPerDay(filter);
		assertEquals(1, series.size());
		assertEquals(1L, series.get("2024-03-01").getCount());
	}
	
	@Test
	public void encountersPerDay_shouldFilterByProgram() {
		ClinicalReportFilter filter = filter();
		filter.setProgramUuid("prog-tb");
		Map<String, TimeSeriesPoint> series = dao.encountersPerDay(filter);
		// Patient 1 only.
		assertEquals(2, series.size());
		assertEquals(2L, series.get("2024-03-01").getCount());
		assertEquals(1L, series.get("2024-03-04").getCount());
	}
	
	@Test
	public void encountersPerDay_shouldIncludeDescendantLocations() {
		ClinicalReportFilter filter = filter();
		// District 1 contains Clinic 2 where every encounter sits.
		filter.setLocationId(1);
		assertEquals(3, dao.encountersPerDay(filter).size());
		
		filter.setLocationId(2);
		assertEquals(3, dao.encountersPerDay(filter).size());
	}
	
	@Test
	public void encountersPerDay_shouldExcludeUnrelatedLocations() {
		ClinicalReportFilter filter = filter();
		filter.setLocationId(99);
		assertEquals(0, dao.encountersPerDay(filter).size());
	}
	
	@Test
	public void observationsPerDay_shouldCountObservationsByDay() {
		Map<String, TimeSeriesPoint> series = dao.observationsPerDay(filter());
		assertEquals(3, series.size());
		assertEquals(2L, series.get("2024-03-01").getCount());
		assertEquals(1L, series.get("2024-03-02").getCount());
		assertEquals(1L, series.get("2024-03-04").getCount());
	}
	
	@Test
	public void observationsPerDay_shouldFilterByConcept() {
		ClinicalReportFilter filter = filter();
		filter.setConceptUuid("c-temp");
		Map<String, TimeSeriesPoint> series = dao.observationsPerDay(filter);
		assertEquals(2, series.size());
		assertEquals(1L, series.get("2024-03-01").getCount());
		assertEquals(1L, series.get("2024-03-02").getCount());
	}
	
	@Test
	public void observationsPerDay_shouldExpandAConceptSetIntoItsMembers() throws Exception {
		// Register: a set that groups both fever concepts, so a filter naming the set must
		// return every observation of any member.
		execute("INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (4, 'FEVERSET', 'c-fever', FALSE)",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (4, 4, 'Fever', 'en', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (5, 'COUGH', 'c-cough', FALSE)",
		    "INSERT INTO concept_set (concept_set_id, concept_id, concept_set, sort_weight, voided, date_created) "
		            + "VALUES (1, 2, 4, 1, FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept_set (concept_set_id, concept_id, concept_set, sort_weight, voided, date_created) "
		            + "VALUES (2, 5, 4, 2, FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO obs (obs_id, person_id, concept_id, obs_datetime, location_id, value_numeric, voided, date_created) "
		            + "VALUES (6, 2, 5, '2024-03-03 09:00:00', 2, NULL, FALSE, '2024-03-03 09:00:00')");
		
		ClinicalReportFilter filter = filter();
		filter.setConceptUuid("c-fever");
		Map<String, TimeSeriesPoint> series = dao.observationsPerDay(filter);
		assertEquals(3, series.size());
		assertEquals(1L, series.get("2024-03-03").getCount());
	}
	
	@Test
	public void observationsPerDay_shouldFilterByProgram() {
		ClinicalReportFilter filter = filter();
		filter.setProgramUuid("prog-hiv");
		Map<String, TimeSeriesPoint> series = dao.observationsPerDay(filter);
		assertEquals(1, series.size());
		assertEquals(1L, series.get("2024-03-02").getCount());
	}
	
	@Test
	public void countByEncounterType_shouldGroupByType() {
		List<BreakdownRow> rows = dao.countByEncounterType(filter());
		assertEquals(2, rows.size());
		assertEquals(3L, count(rows, "et-opd"));
		assertEquals(1L, count(rows, "et-adm"));
	}
	
	@Test
	public void countTopConcepts_shouldRankByFrequency() {
		List<BreakdownRow> rows = dao.countTopConcepts(filter(), 10);
		assertEquals(3, rows.size());
		// Temperature was recorded twice.
		assertEquals("Temperature", rows.get(0).getLabel());
		assertEquals(2L, rows.get(0).getCount());
	}
	
	@Test
	public void countTopConcepts_shouldHonourTheLimit() {
		List<BreakdownRow> rows = dao.countTopConcepts(filter(), 2);
		assertEquals(2, rows.size());
	}
	
	@Test
	public void countTopConcepts_shouldPreferTheEnglishNameOverOneThatSortsFirst() throws Exception {
		// A dictionary carries a name per locale, and a Sierra Leone deployment carries Haitian
		// Creole, French and Spanish alongside English. Resolving a name with MIN() picks the
		// language that happens to sort first, which labelled a pulse rate "Batman kè".
		execute(
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (91, 3, 'Peso', 'es', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (92, 3, 'Pwa (S)', 'ht', FALSE, '2020-01-01 00:00:00')");
		List<BreakdownRow> rows = dao.countTopConcepts(filter(), 10);
		BreakdownRow weight = rows.stream().filter(r -> "c-weight".equals(r.getKey())).findFirst().get();
		assertEquals("Weight", weight.getLabel());
	}
	
	@Test
	public void countTopConcepts_shouldFallBackToTheShortNameWhenNoNameMatchesTheLocale() throws Exception {
		execute(
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (93, 3, 'Poids', 'fr', FALSE, '2020-01-01 00:00:00')",
		    "DELETE FROM concept_name WHERE concept_id = 3 AND locale = 'en'");
		List<BreakdownRow> rows = dao.countTopConcepts(filter(), 10);
		BreakdownRow weight = rows.stream().filter(r -> "c-weight".equals(r.getKey())).findFirst().get();
		// French sorts after English, so this only proves the fallback chain did not invent a
		// label: with neither an English name nor a short name the row still resolves to a name.
		assertNotNull(weight.getLabel());
		assertEquals("Poids", weight.getLabel());
	}
	
	@Test
	public void countTopConcepts_shouldFilterByConcept() {
		ClinicalReportFilter filter = filter();
		filter.setConceptUuid("c-weight");
		List<BreakdownRow> rows = dao.countTopConcepts(filter, 10);
		assertEquals(1, rows.size());
		assertEquals("Weight", rows.get(0).getLabel());
	}
	
	@Test
	public void countByProgram_shouldCountPatientsInEachProgram() {
		List<BreakdownRow> rows = dao.countByProgram(filter());
		assertEquals(2, rows.size());
		assertEquals(1L, count(rows, "prog-tb"));
		assertEquals(1L, count(rows, "prog-hiv"));
	}
	
	@Test
	public void perDaySeries_shouldReturnAnEmptySeriesWhenNothingMatches() {
		ClinicalReportFilter filter = filter();
		filter.setProgramUuid("does-not-exist");
		assertEquals(0, dao.observationsPerDay(filter).size());
	}
	
	@Test
	public void countByEncounterType_shouldBeConsistentWithTheSeries() {
		long fromBreakdown = 0L;
		for (BreakdownRow row : dao.countByEncounterType(filter())) {
			fromBreakdown += row.getCount();
		}
		long fromSeries = 0L;
		for (TimeSeriesPoint point : dao.encountersPerDay(filter()).values()) {
			fromSeries += point.getCount();
		}
		assertEquals(fromSeries, fromBreakdown);
		assertTrue(fromSeries == 4L);
	}
}
