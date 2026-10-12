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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dao.NativeSqlTestBase;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.LabOrderRow;
import org.openmrs.module.clinicreporting.api.dto.LabReportFilter;
import org.openmrs.module.clinicreporting.api.dto.PagedResult;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.dto.TurnaroundSummary;

/**
 * Executes the lab/LIMS queries for real, covering the order/result relationship and the turnaround
 * arithmetic.
 */
public class LabReportSqlTest extends NativeSqlTestBase {
	
	private HibernateLabReportDao dao;
	
	private static Date date(int year, int month, int day, int hour) {
		Calendar calendar = Calendar.getInstance();
		calendar.clear();
		calendar.set(year, month - 1, day, hour, 0, 0);
		return calendar.getTime();
	}
	
	private static LabReportFilter filter() {
		LabReportFilter filter = new LabReportFilter();
		filter.setDateFrom(date(2024, 1, 1, 0));
		filter.setDateTo(date(2024, 12, 31, 23));
		filter.validate();
		return filter;
	}
	
	private static LabOrderRow byOrder(PagedResult<LabOrderRow> result, String orderNumber) {
		for (LabOrderRow row : result.getResults()) {
			if (orderNumber.equals(row.getOrderNumber())) {
				return row;
			}
		}
		throw new AssertionError("no order " + orderNumber);
	}
	
	@Before
	public void seed() throws Exception {
		dao = dao(new HibernateLabReportDao());
		
		execute("DELETE FROM obs", "DELETE FROM orders", "DELETE FROM order_type", "DELETE FROM concept_name",
		    "DELETE FROM concept", "DELETE FROM encounter", "DELETE FROM person_name", "DELETE FROM patient_identifier",
		    "DELETE FROM location", "DELETE FROM patient", "DELETE FROM person");
		
		execute("INSERT INTO location (location_id, name, parent_location, retired) VALUES (1, 'Clinic', NULL, FALSE)");
		execute(
		    "INSERT INTO person (person_id, uuid, gender, birthdate, dead, voided, date_created) "
		            + "VALUES (1, 'p-1', 'F', '1985-02-02 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO person (person_id, uuid, gender, birthdate, dead, voided, date_created) "
		            + "VALUES (2, 'p-2', 'M', '1995-03-03 00:00:00', FALSE, FALSE, '2020-01-01 00:00:00')");
		execute("INSERT INTO patient (patient_id, voided) VALUES (1, FALSE)",
		    "INSERT INTO patient (patient_id, voided) VALUES (2, FALSE)");
		execute(
		    "INSERT INTO person_name (person_name_id, person_id, preferred, given_name, family_name, voided, date_created) "
		            + "VALUES (1, 1, TRUE, 'Ada', 'Mwangi', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO person_name (person_name_id, person_id, preferred, given_name, family_name, voided, date_created) "
		            + "VALUES (2, 2, TRUE, 'Bo', 'Ndlovu', FALSE, '2020-01-01 00:00:00')");
		execute(
		    "INSERT INTO patient_identifier (patient_identifier_id, patient_id, identifier, preferred, location_id, voided, date_created) "
		            + "VALUES (1, 1, 'LAB-1', TRUE, 1, FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO patient_identifier (patient_identifier_id, patient_id, identifier, preferred, location_id, voided, date_created) "
		            + "VALUES (2, 2, 'LAB-2', TRUE, 1, FALSE, '2020-01-01 00:00:00')");
		execute("INSERT INTO order_type (order_type_id, name, uuid, retired) VALUES (1, 'Lab', 'ot-lab', FALSE)");
		execute("INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (1, 'HIV', 'c-hiv', FALSE)",
		    "INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (2, 'MALARIA', 'c-mal', FALSE)",
		    "INSERT INTO concept (concept_id, short_name, uuid, retired) VALUES (3, 'POSITIVE', 'c-pos', FALSE)");
		execute(
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (1, 1, 'HIV test', 'en', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (2, 2, 'Malaria test', 'en', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (3, 3, 'Positive', 'en', FALSE, '2020-01-01 00:00:00')");
		execute(
		    "INSERT INTO orders (order_id, patient_id, encounter_id, order_type_id, concept_id, order_number, "
		            + "accession_number, uuid, date_activated, date_created, voided) VALUES "
		            + "(1, 1, 1, 1, 1, 'ORD-1', 'ACC-1', 'ord-uuid-1', '2024-03-01 08:00:00', '2024-03-01 08:00:00', FALSE)",
		    "INSERT INTO orders (order_id, patient_id, encounter_id, order_type_id, concept_id, order_number, "
		            + "accession_number, uuid, date_activated, date_created, voided) VALUES "
		            + "(2, 2, 2, 1, 2, 'ORD-2', 'ACC-2', 'ord-uuid-2', '2024-03-02 08:00:00', '2024-03-02 08:00:00', FALSE)",
		    // No date_activated: the row date must be used instead.
		    "INSERT INTO orders (order_id, patient_id, encounter_id, order_type_id, concept_id, order_number, "
		            + "accession_number, uuid, date_activated, date_created, voided) VALUES "
		            + "(3, 1, 1, 1, 1, 'ORD-3', 'ACC-3', 'ord-uuid-3', NULL, '2024-03-05 08:00:00', FALSE)",
		    // Voided, and must never appear.
		    "INSERT INTO orders (order_id, patient_id, encounter_id, order_type_id, concept_id, order_number, "
		            + "accession_number, uuid, date_activated, date_created, voided) VALUES "
		            + "(4, 1, 1, 1, 1, 'ORD-4', 'ACC-4', 'ord-uuid-4', '2024-03-06 08:00:00', '2024-03-06 08:00:00', TRUE)");
		execute(
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (1, 1, NULL, '2024-03-01 07:00:00', 1, FALSE, '2024-03-01 07:00:00')",
		    "INSERT INTO encounter (encounter_id, patient_id, encounter_type, encounter_datetime, location_id, voided, date_created) "
		            + "VALUES (2, 2, NULL, '2024-03-02 07:00:00', 1, FALSE, '2024-03-02 07:00:00')");
		// Order 1 resulted after 4 hours; order 2 after 30 hours; order 3 has no result yet.
		execute(
		    "INSERT INTO obs (obs_id, person_id, concept_id, order_id, obs_datetime, location_id, "
		            + "value_coded_name_id, value_numeric, status, voided, date_created) VALUES "
		            + "(1, 1, 1, 1, '2024-03-01 12:00:00', 1, 3, NULL, 'FINAL', FALSE, '2024-03-01 12:00:00')",
		    "INSERT INTO obs (obs_id, person_id, concept_id, order_id, obs_datetime, location_id, "
		            + "value_coded_name_id, value_numeric, status, voided, date_created) VALUES "
		            + "(2, 2, 2, 2, '2024-03-03 14:00:00', 1, NULL, 2.5, 'FINAL', FALSE, '2024-03-03 14:00:00')");
	}
	
	@Test
	public void findOrders_shouldJoinOrdersToPatientsAndResults() {
		PagedResult<LabOrderRow> result = dao.findOrders(filter());
		assertEquals(3L, result.getTotalCount());
		
		LabOrderRow order = byOrder(result, "ORD-1");
		assertEquals(Integer.valueOf(1), order.getPatientId());
		assertEquals("Ada Mwangi", order.getDisplay());
		assertEquals("LAB-1", order.getIdentifier());
		assertEquals("Lab", order.getOrderTypeName());
		assertNotNull(order.getOrderUuid());
	}
	
	@Test
	public void findOrders_shouldPreferTheEnglishTestNameOverOneThatSortsFirst() throws Exception {
		// 'An' and 'Bicha' sort before 'HIV test'. Taking MIN(name) across every locale would
		// report the order as one of those, so the name has to be resolved by locale.
		execute(
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (91, 1, 'An es', 'es', FALSE, '2020-01-01 00:00:00')",
		    "INSERT INTO concept_name (concept_name_id, concept_id, name, locale, voided, date_created) "
		            + "VALUES (92, 1, 'Bicha', 'ht', FALSE, '2020-01-01 00:00:00')");
		PagedResult<LabOrderRow> result = dao.findOrders(filter());
		assertEquals("HIV test", byOrder(result, "ORD-1").getConceptName());
	}
	
	@Test
	public void findOrders_shouldKeepAnOrderThatNamesNoTest() throws Exception {
		// orders.concept_id is nullable. Requiring a concept join made such orders disappear from
		// the report entirely, so eight orders could render as zero.
		execute("INSERT INTO orders (order_id, patient_id, encounter_id, order_type_id, concept_id, order_number, "
		        + "accession_number, uuid, date_activated, date_created, voided) VALUES "
		        + "(9, 1, 1, 1, NULL, 'ORD-9', 'ACC-9', 'ord-uuid-9', '2024-03-01 08:00:00', '2024-03-01 08:00:00', FALSE)");
		PagedResult<LabOrderRow> result = dao.findOrders(filter());
		assertEquals(4L, result.getTotalCount());
		assertNotNull(byOrder(result, "ORD-9"));
	}
	
	@Test
	public void findOrders_shouldFallBackToTheRowDateWhenNotActivated() {
		LabReportFilter filter = filter();
		// Only the order whose date_activated is NULL is in range.
		filter.setDateFrom(date(2024, 3, 5, 0));
		filter.setDateTo(date(2024, 3, 5, 23));
		filter.validate();
		PagedResult<LabOrderRow> result = dao.findOrders(filter);
		assertEquals(1L, result.getTotalCount());
		assertEquals("ORD-3", result.getResults().get(0).getOrderNumber());
	}
	
	@Test
	public void findOrders_shouldExcludeVoidedOrders() {
		PagedResult<LabOrderRow> result = dao.findOrders(filter());
		for (LabOrderRow order : result.getResults()) {
			assertTrue(!"ORD-4".equals(order.getOrderNumber()));
		}
	}
	
	@Test
	public void findOrders_shouldLeaveAnUnresultedOrderBlank() {
		LabReportFilter filter = filter();
		filter.setDateFrom(date(2024, 3, 5, 0));
		filter.setDateTo(date(2024, 3, 5, 23));
		filter.validate();
		LabOrderRow pending = dao.findOrders(filter).getResults().get(0);
		assertNull(pending.getResultDate());
		assertNull(pending.getResultStatus());
		assertNull(pending.getResultValue());
	}
	
	@Test
	public void findOrders_shouldRenderACodedResultAsItsConceptName() {
		LabOrderRow order = byOrder(dao.findOrders(filter()), "ORD-1");
		assertEquals("FINAL", order.getResultStatus());
		assertEquals("Positive", order.getResultValue());
	}
	
	@Test
	public void findOrders_shouldRenderANumericResultAsText() {
		LabOrderRow order = byOrder(dao.findOrders(filter()), "ORD-2");
		assertEquals("2.5", order.getResultValue());
	}
	
	@Test
	public void findOrders_shouldComputeTurnaroundHours() {
		assertEquals(Double.valueOf(4d), byOrder(dao.findOrders(filter()), "ORD-1").getTurnaroundHours());
		assertEquals(Double.valueOf(30d), byOrder(dao.findOrders(filter()), "ORD-2").getTurnaroundHours());
	}
	
	@Test
	public void countOrders_shouldNotBeInflatedBySeveralResultsPerOrder() throws Exception {
		execute("INSERT INTO obs (obs_id, person_id, concept_id, order_id, obs_datetime, location_id, "
		        + "value_numeric, status, voided, date_created) VALUES "
		        + "(3, 1, 1, 1, '2024-03-01 13:00:00', 1, NULL, 'FINAL', FALSE, '2024-03-01 13:00:00')");
		// Three orders, even though order 1 now has two result rows.
		assertEquals(3L, dao.findOrders(filter()).getTotalCount());
	}
	
	@Test
	public void ordersPerDay_shouldCountOrdersByDay() {
		Map<String, TimeSeriesPoint> series = dao.ordersPerDay(filter());
		// Order 3 is dated from date_created, and the voided order is excluded.
		assertEquals(3, series.size());
		assertEquals(1L, series.get("2024-03-01").getCount());
		assertEquals(1L, series.get("2024-03-02").getCount());
		assertEquals(1L, series.get("2024-03-05").getCount());
	}
	
	@Test
	public void resultsPerDay_shouldCountResultsByDay() {
		Map<String, TimeSeriesPoint> series = dao.resultsPerDay(filter());
		assertEquals(2, series.size());
		assertEquals(1L, series.get("2024-03-01").getCount());
		assertEquals(1L, series.get("2024-03-03").getCount());
	}
	
	@Test
	public void countByTest_shouldGroupByTheOrderedTest() {
		List<BreakdownRow> rows = dao.countByTest(filter(), 10);
		assertEquals(2, rows.size());
		assertEquals("HIV test", rows.get(0).getLabel());
	}
	
	@Test
	public void countByTest_shouldHonourTheLimit() {
		assertEquals(1, dao.countByTest(filter(), 1).size());
	}
	
	@Test
	public void countByResultStatus_shouldReportFinalResults() {
		List<BreakdownRow> rows = dao.countByResultStatus(filter());
		assertEquals(1, rows.size());
		assertEquals("FINAL", rows.get(0).getKey());
		assertEquals(2L, rows.get(0).getCount());
	}
	
	@Test
	public void findOrders_shouldFilterByResultStatus() {
		LabReportFilter filter = filter();
		filter.setObsStatus("FINAL");
		assertEquals(2L, dao.findOrders(filter).getTotalCount());
		
		filter.setObsStatus("PENDING");
		assertEquals(0L, dao.findOrders(filter).getTotalCount());
	}
	
	@Test
	public void findOrders_shouldFilterByResultValue() {
		LabReportFilter filter = filter();
		filter.setResultValue("2.5");
		PagedResult<LabOrderRow> numeric = dao.findOrders(filter);
		assertEquals(1L, numeric.getTotalCount());
		assertEquals("Malaria test", numeric.getResults().get(0).getConceptName());
		
		// A coded result has no text column, so it has to be matched through its concept name.
		filter.setResultValue("Positive");
		PagedResult<LabOrderRow> coded = dao.findOrders(filter);
		assertEquals(1L, coded.getTotalCount());
		assertEquals("ORD-1", coded.getResults().get(0).getOrderNumber());
	}
	
	@Test
	public void findOrders_shouldFilterByOrderType() {
		LabReportFilter filter = filter();
		filter.setOrderTypeUuid("ot-lab");
		assertEquals(3L, dao.findOrders(filter).getTotalCount());
		
		filter.setOrderTypeUuid("does-not-exist");
		assertEquals(0L, dao.findOrders(filter).getTotalCount());
	}
	
	@Test
	public void findOrders_shouldFilterByGender() {
		LabReportFilter filter = filter();
		filter.setGender("M");
		PagedResult<LabOrderRow> result = dao.findOrders(filter);
		assertEquals(1L, result.getTotalCount());
		assertEquals("Bo Ndlovu", result.getResults().get(0).getDisplay());
	}
	
	@Test
	public void turnaround_shouldSummariseCompletedOrdersOnly() {
		TurnaroundSummary summary = dao.turnaround(filter(), 50);
		// Two completed orders: 4h and 30h.
		assertEquals(2L, summary.getCompletedOrders());
		assertEquals(4d, summary.getMinHours(), 0.001);
		assertEquals(30d, summary.getMaxHours(), 0.001);
		assertEquals(17d, summary.getAverageHours(), 0.001);
		assertEquals(17d, summary.getMedianHours(), 0.001);
		assertEquals(27.4d, summary.getP90Hours(), 0.001);
	}
}
