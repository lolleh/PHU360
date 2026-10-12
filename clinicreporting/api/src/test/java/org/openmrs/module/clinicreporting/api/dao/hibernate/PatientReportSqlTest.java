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

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dao.NativeSqlTestBase;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.PatientReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PatientSummaryRow;

/**
 * Executes the patient listing SQL for real. These queries are native SQL, so a mistake in an alias
 * or a column name would otherwise only surface in production.
 */
public class PatientReportSqlTest extends NativeSqlTestBase {
	
	private HibernatePatientReportDao dao;
	
	private static Date date(int year, int month, int day) {
		Calendar calendar = Calendar.getInstance();
		calendar.clear();
		calendar.set(year, month - 1, day, 12, 0, 0);
		return calendar.getTime();
	}
	
	private static PatientReportFilter filter() {
		PatientReportFilter filter = new PatientReportFilter();
		filter.setDateFrom(date(2024, 1, 1));
		filter.setDateTo(date(2024, 12, 31));
		filter.validate();
		return filter;
	}
	
	private static PatientSummaryRow byDisplay(PagedResult<PatientSummaryRow> result, String display) {
		for (PatientSummaryRow row : result.getResults()) {
			if (display.equals(row.getDisplay())) {
				return row;
			}
		}
		throw new AssertionError("no patient named '" + display + "' in " + displays(result));
	}
	
	private static int indexOfDisplay(PagedResult<PatientSummaryRow> result, String display) {
		for (int i = 0; i < result.getResults().size(); i++) {
			if (display.equals(result.getResults().get(i).getDisplay())) {
				return i;
			}
		}
		return -1;
	}
	
	private static String displays(PagedResult<PatientSummaryRow> result) {
		StringBuilder names = new StringBuilder("[");
		for (PatientSummaryRow row : result.getResults()) {
			names.append(row.getDisplay()).append(", ");
		}
		return names.append(']').toString();
	}
	
	@Before
	public void seed() throws Exception {
		dao = dao(new HibernatePatientReportDao());
		
		execute("DELETE FROM encounter", "DELETE FROM patient_program", "DELETE FROM cohort_member",
		    "DELETE FROM person_name", "DELETE FROM patient_identifier", "DELETE FROM location", "DELETE FROM patient",
		    "DELETE FROM person", "DELETE FROM program", "DELETE FROM cohort");
		
		execute("INSERT INTO location (location_id, name, parent_location, retired) VALUES (1, 'District', NULL, FALSE)",
		    "INSERT INTO location (location_id, name, parent_location, retired) VALUES (2, 'Clinic A', 1, FALSE)");
		execute(
		    "INSERT INTO person (person_id, uuid, gender, birthdate, dead, voided, date_created) "
		            + "VALUES (1, 'p-1', 'F', '1990-05-04 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO person (person_id, uuid, gender, birthdate, dead, voided, date_created) "
		            + "VALUES (2, 'p-2', 'M', '2015-01-01 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO person (person_id, uuid, gender, birthdate, dead, voided, date_created) "
		            + "VALUES (3, 'p-3', NULL, NULL, FALSE, FALSE, '2020-01-01 00:00:00')");
		execute("INSERT INTO patient (patient_id, voided) VALUES (1, FALSE)",
		    "INSERT INTO patient (patient_id, voided) VALUES (2, FALSE)",
		    "INSERT INTO patient (patient_id, voided) VALUES (3, FALSE)");
		execute(
		    "INSERT INTO person_name (person_name_id, person_id, preferred, given_name, family_name, voided, date_created) "
		            + "VALUES (1, 1, TRUE, 'Amara', 'Okafor', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO person_name (person_name_id, person_id, preferred, given_name, family_name, voided, date_created) "
		            + "VALUES (2, 2, TRUE, 'Ben', 'Kamau', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO person_name (person_name_id, person_id, preferred, given_name, family_name, voided, date_created) "
		            + "VALUES (3, 3, TRUE, 'Cleo', 'Njoroge', FALSE, '2020-01-01 00:00:00')");
		execute(
		    "INSERT INTO patient_identifier (patient_identifier_id, patient_id, identifier, preferred, location_id, voided, date_created) "
		            + "VALUES (1, 1, 'FAC-1', TRUE, 2, FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO patient_identifier (patient_identifier_id, patient_id, identifier, preferred, location_id, voided, date_created) "
		            + "VALUES (2, 2, 'FAC-2', TRUE, 2, FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO patient_identifier (patient_identifier_id, patient_id, identifier, preferred, location_id, voided, date_created) "
		            + "VALUES (3, 3, 'FAC-3', TRUE, 2, FALSE, '2020-01-01 00:00:00')");
		execute("INSERT INTO program (program_id, name, uuid, retired) VALUES (1, 'TB', 'prog-tb', FALSE)",
		    "INSERT INTO patient_program (patient_program_id, patient_id, program_id, date_enrolled, voided, date_created) "
		            + "VALUES (1, 1, 1, '2023-01-01 00:00:00', FALSE, '2023-01-01 00:00:00')");
		execute(
		    "INSERT INTO cohort (cohort_id, name, description, uuid, voided) VALUES (1, 'Diabetics', 'd', 'coh-1', FALSE)",
		    "INSERT INTO cohort_member (cohort_member_id, cohort_id, patient_id, start_date, end_date, voided, date_created) "
		            + "VALUES (1, 1, 2, '2023-01-01 00:00:00', NULL, FALSE, '2023-01-01 00:00:00')");
		execute(
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (1, 1, NULL, '2024-03-01 09:00:00', 2, FALSE, '2024-03-01 09:00:00')",
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (2, 2, NULL, '2024-04-01 09:00:00', 2, FALSE, '2024-04-01 09:00:00')");
	}
	
	@Test
	public void findPatients_shouldListEveryPatientWithTheirDetails() {
		PagedResult<PatientSummaryRow> result = dao.findPatients(filter());
		assertEquals(3L, result.getTotalCount());
		assertEquals(3, result.getResults().size());
		
		PatientSummaryRow amara = byDisplay(result, "Amara Okafor");
		assertEquals("FAC-1", amara.getIdentifier());
		assertEquals("F", amara.getGender());
		assertEquals("Clinic A", amara.getLocationName());
		assertEquals("TB", amara.getProgramName());
		assertEquals(Integer.valueOf(1), amara.getPatientId());
		assertNotNull(amara.getPatientUuid());
		assertNotNull(amara.getLastVisit());
		assertNotNull(amara.getAgeInMonths());
	}
	
	@Test
	public void findPatients_shouldNotDuplicatePatientsWithSeveralNames() throws Exception {
		// A second, non-preferred name must not produce a second row for the same patient.
		execute(
		    "INSERT INTO person_name (person_name_id, person_id, preferred, given_name, family_name, voided, date_created) "
		            + "VALUES (4, 1, FALSE, 'Amara', 'Married', FALSE, '2020-01-01 00:00:00')");
		PagedResult<PatientSummaryRow> result = dao.findPatients(filter());
		assertEquals(3L, result.getTotalCount());
		// The listing must still show the preferred name, not the newest one.
		assertEquals("Amara Okafor", byDisplay(result, "Amara Okafor").getDisplay());
		assertEquals(-1, indexOfDisplay(result, "Amara Married"));
	}
	
	@Test
	public void findPatients_shouldPreferThePreferredIdentifier() throws Exception {
		execute(
		    "INSERT INTO patient_identifier (patient_identifier_id, patient_id, identifier, preferred, location_id, voided, date_created) "
		            + "VALUES (4, 1, 'OLD-1', FALSE, 2, FALSE, '2020-01-01 00:00:00')");
		assertEquals("FAC-1", byDisplay(dao.findPatients(filter()), "Amara Okafor").getIdentifier());
	}
	
	@Test
	public void findPatients_shouldExcludedVoidedPatients() throws Exception {
		execute("UPDATE patient SET voided = TRUE WHERE patient_id = 3");
		assertEquals(2L, dao.findPatients(filter()).getTotalCount());
	}
	
	@Test
	public void findPatients_shouldSearchNameAndIdentifier() {
		PatientReportFilter byName = filter();
		byName.setQ("kamau");
		PagedResult<PatientSummaryRow> named = dao.findPatients(byName);
		assertEquals(1L, named.getTotalCount());
		assertEquals("Ben Kamau", byDisplay(named, "Ben Kamau").getDisplay());
		
		PatientReportFilter byIdentifier = filter();
		byIdentifier.setQ("fac-3");
		assertEquals(1L, dao.findPatients(byIdentifier).getTotalCount());
	}
	
	@Test
	public void findPatients_shouldTreatWildcardInputAsLiteralText() {
		PatientReportFilter filter = filter();
		// A bare '%' must not turn into "match every patient".
		filter.setQ("%");
		assertEquals(0L, dao.findPatients(filter).getTotalCount());
	}
	
	@Test
	public void findPatients_shouldFilterByGender() {
		PatientReportFilter filter = filter();
		filter.setGender("M");
		PagedResult<PatientSummaryRow> result = dao.findPatients(filter);
		assertEquals(1L, result.getTotalCount());
		assertEquals("Ben Kamau", byDisplay(result, "Ben Kamau").getDisplay());
	}
	
	@Test
	public void findPatients_shouldFilterByAgeRange() {
		PatientReportFilter minors = filter();
		minors.setMaxAgeMonths(200);
		PagedResult<PatientSummaryRow> result = dao.findPatients(minors);
		// Amara (born 1990) is far too old; Ben (2015) qualifies; Cleo has no birthdate and
		// is excluded because an age constraint cannot be evaluated for her.
		assertEquals(1L, result.getTotalCount());
		assertEquals("Ben Kamau", byDisplay(result, "Ben Kamau").getDisplay());
	}
	
	@Test
	public void findPatients_shouldFilterByProgram() {
		PatientReportFilter filter = filter();
		filter.setProgramUuid("prog-tb");
		PagedResult<PatientSummaryRow> result = dao.findPatients(filter);
		assertEquals(1L, result.getTotalCount());
		assertEquals("Amara Okafor", byDisplay(result, "Amara Okafor").getDisplay());
	}
	
	@Test
	public void findPatients_shouldFilterByCohort() {
		PatientReportFilter filter = filter();
		filter.setCohortUuid("coh-1");
		PagedResult<PatientSummaryRow> result = dao.findPatients(filter);
		assertEquals(1L, result.getTotalCount());
		assertEquals("Ben Kamau", byDisplay(result, "Ben Kamau").getDisplay());
	}
	
	@Test
	public void findPatients_shouldIncludeDescendantsOfTheSelectedLocation() {
		PatientReportFilter filter = filter();
		// Location 1 is the district; selecting it must include the clinic beneath it.
		filter.setLocationId(1);
		PagedResult<PatientSummaryRow> result = dao.findPatients(filter);
		assertEquals(2L, result.getTotalCount());
	}
	
	@Test
	public void findPatients_shouldFilterBySeenInRange() {
		PatientReportFilter filter = filter();
		filter.setSeenInRange(Boolean.TRUE);
		PagedResult<PatientSummaryRow> result = dao.findPatients(filter);
		assertEquals(2L, result.getTotalCount());
	}
	
	@Test
	public void findPatients_shouldExcludeEncountersOutsideTheRange() {
		PatientReportFilter filter = filter();
		filter.setSeenInRange(Boolean.TRUE);
		filter.setDateFrom(date(2023, 1, 1));
		filter.setDateTo(date(2023, 12, 31));
		filter.validate();
		assertEquals(0L, dao.findPatients(filter).getTotalCount());
	}
	
	@Test
	public void paging_shouldNotAffectTheTotalCount() {
		PatientReportFilter filter = filter();
		filter.setSize(2);
		filter.setPage(1);
		PagedResult<PatientSummaryRow> firstPage = dao.findPatients(filter);
		assertEquals(2, firstPage.getResults().size());
		assertEquals(3L, firstPage.getTotalCount());
		
		filter.setPage(2);
		PagedResult<PatientSummaryRow> secondPage = dao.findPatients(filter);
		assertEquals(1, secondPage.getResults().size());
		assertEquals(3L, secondPage.getTotalCount());
	}
	
	@Test
	public void countAndList_shouldAgree() {
		PatientReportFilter filter = filter();
		filter.setQ("a");
		assertEquals(dao.countPatients(filter), dao.findPatients(filter).getTotalCount());
	}
	
	@Test
	public void countByGender_shouldReportUnknownForMissingGender() {
		List<BreakdownRow> rows = dao.countByGender(filter());
		assertEquals(3, rows.size());
		for (BreakdownRow row : rows) {
			if ("U".equals(row.getKey())) {
				assertEquals(1L, row.getCount());
			}
		}
	}
	
	@Test
	public void countByAgeBand_shouldAlwaysReportEveryBand() {
		List<BreakdownRow> rows = dao.countByAgeBand(filter());
		// Bands are fixed so a chart has a stable axis even when a band is empty.
		assertEquals(6, rows.size());
		long total = 0L;
		for (BreakdownRow row : rows) {
			total += row.getCount();
		}
		assertEquals(3L, total);
	}
}
