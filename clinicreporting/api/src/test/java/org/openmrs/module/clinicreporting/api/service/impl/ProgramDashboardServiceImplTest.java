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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dao.ProgramDashboardDao;
import org.openmrs.module.clinicreporting.api.dashboard.BuiltinDashboard;
import org.openmrs.module.clinicreporting.api.dashboard.DashboardIndicator;
import org.openmrs.module.clinicreporting.api.dashboard.DashboardProgram;
import org.openmrs.module.clinicreporting.api.dashboard.IndicatorResult;
import org.openmrs.module.clinicreporting.api.dashboard.ProgramIndicatorCatalog;
import org.openmrs.module.clinicreporting.api.dto.BreakdownRow;
import org.openmrs.module.clinicreporting.api.dto.CrossTab;
import org.openmrs.module.clinicreporting.api.dto.CrossTabRow;
import org.openmrs.module.clinicreporting.api.dto.TimeSeriesPoint;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tests how indicator definitions turn into figures, and what happens when a figure cannot be had.
 * <p>
 * The counts themselves are the DAO's job and are covered against a real database in
 * {@code ProgramDashboardSqlTest}. What matters here is the behaviour around them, because a
 * clinical dashboard is trusted: a concept this server has never heard of must read as "not
 * available here", not as a confident zero, and one broken indicator must not take the rest of the
 * page with it.
 */
public class ProgramDashboardServiceImplTest {
	
	private static final String PMTCT = "2e9a4c7d-8257-4e1d-ae8f-51b29e3e7cfa";
	
	private static final String TB = "bfc32f7d-6e2b-48c2-aa43-9d4f7c87a8e4";
	
	private static final int HIV_STATUS = 1;
	
	private static final int CD4 = 2;
	
	private StubDao dao;
	
	private ProgramDashboardServiceImpl service;
	
	@Before
	public void setUp() throws IOException {
		dao = new StubDao();
		service = new ProgramDashboardServiceImpl();
		service.setDao(dao);
	}
	
	@After
	public void forgetTheDefinitions() {
		ProgramIndicatorCatalog.clearCache();
	}
	
	// ---------------------------------------------------------------- the programme list
	
	@Test
	public void getAvailablePrograms_shouldOfferOnlyWhatThisServerHas() {
		writeDefinitions();
		dao.present = new LinkedHashSet<>(Arrays.asList(PMTCT));
		
		List<DashboardProgram> available = service.getAvailablePrograms();
		// The installed programme, then the built-in clinic-wide report that needs no programme.
		assertEquals(2, available.size());
		assertEquals(PMTCT, available.get(0).getUuid());
		assertTrue(available.get(1).isBuiltin());
	}
	
	@Test
	public void getAvailablePrograms_shouldMatchUuidsWhateverTheirCase() {
		writeDefinitions();
		// OpenMRS stores UUIDs as typed, so a server may well hold this one in capitals.
		dao.present = new LinkedHashSet<>(Collections.singletonList(PMTCT.toUpperCase()));
		List<DashboardProgram> available = service.getAvailablePrograms();
		assertEquals(2, available.size());
		assertEquals(PMTCT, available.get(0).getUuid());
	}
	
	@Test
	public void getAvailablePrograms_shouldOfferTheBuiltInReportEvenWhenTheServerHasNoProgrammes() {
		writeDefinitions();
		dao.present = new LinkedHashSet<>();
		// Better no programme links than links to programmes that are not installed, but the built-in
		// report is not a programme, so it is still offered.
		List<DashboardProgram> available = service.getAvailablePrograms();
		assertEquals(1, available.size());
		assertTrue(available.get(0).isBuiltin());
		assertEquals("diagnosesByTreatment", available.get(0).getBuiltinType());
	}
	
	@Test
	public void getProgram_shouldReturnTheDefinition() {
		writeDefinitions();
		assertEquals(PMTCT, service.getProgram(PMTCT).getUuid());
		assertEquals(PMTCT, service.getProgram(PMTCT.toUpperCase()).getUuid());
		assertNull(service.getProgram("00000000-0000-0000-0000-000000000000"));
	}
	
	@Test
	public void getProgram_shouldResolveABuiltInDashboard() {
		// A built-in dashboard is not in the definition file, so it resolves to the code that builds it.
		DashboardProgram builtin = service.getProgram(BuiltinDashboard.DIAGNOSES_BY_TREATMENT.getUuid());
		assertNotNull(builtin);
		assertTrue(builtin.isBuiltin());
		assertEquals("diagnosesByTreatment", builtin.getBuiltinType());
	}
	
	// ---------------------------------------------------------------- argument checking
	
	@Test
	public void compute_shouldRefuseToRunWithoutAProgramme() {
		try {
			service.compute(null, date(), date());
			fail("expected a complaint about the missing programme");
		}
		catch (InvalidReportFilterException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("programme"));
		}
	}
	
	@Test
	public void compute_shouldRefuseToRunWithoutBothEndsOfTheRange() {
		DashboardProgram program = service.getProgram(program());
		try {
			service.compute(program, null, date());
			fail("expected a complaint about the missing start");
		}
		catch (InvalidReportFilterException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("date range"));
		}
		try {
			service.compute(program, date(), null);
			fail("expected a complaint about the missing end");
		}
		catch (InvalidReportFilterException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("date range"));
		}
	}
	
	@Test
	public void compute_shouldRefuseARangeThatRunsBackwards() {
		try {
			service.compute(service.getProgram(program()), date(2024, 12, 31), date(2024, 1, 1));
			fail("expected a complaint about the reversed range");
		}
		catch (InvalidReportFilterException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("after its end"));
		}
	}
	
	@Test
	public void compute_shouldAcceptASingleDayRange() {
		writeDefinitions();
		// One day is a real thing to ask for; only the reversed range is impossible.
		assertEquals(1, service.compute(service.getProgram(program()), date(2024, 1, 1), date(2024, 1, 1)).size());
	}
	
	@Test
	public void diagnosesByTreatment_shouldRefuseToRunWithoutAProgramme() {
		try {
			service.diagnosesByTreatment(" ", date(), date(), 10, 8);
			fail("expected a complaint about the missing programme");
		}
		catch (InvalidReportFilterException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("programme"));
		}
	}
	
	@Test
	public void diagnosesByTreatment_shouldRefuseARangeThatRunsBackwards() {
		try {
			service.diagnosesByTreatment(PMTCT, date(2024, 12, 31), date(2024, 1, 1), 10, 8);
			fail("expected a complaint about the reversed range");
		}
		catch (InvalidReportFilterException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("after its end"));
		}
	}
	
	@Test
	public void diagnosesByTreatment_shouldLayThePairsOutAndReadAGenerousCap() {
		dao.diagnosisPairs = Arrays.asList(new CrossTabRow("a", "A", "x", "X", 5), new CrossTabRow("a", "A", "y", "Y", 3),
		    new CrossTabRow("b", "B", "x", "X", 1));
		
		CrossTab matrix = service.diagnosesByTreatment(PMTCT, date(), date(), 10, 10);
		assertEquals("A", matrix.getRows().get(0).getLabel());
		assertEquals(9L, matrix.getGrandTotal());
		// A generous cap is read so the "Other" bucket has enough of the tail to summarise.
		assertEquals(1000, dao.lastPairLimit);
	}
	
	@Test
	public void diagnosesByTreatmentAcrossClinic_shouldRefuseARangeThatRunsBackwards() {
		try {
			service.diagnosesByTreatmentAcrossClinic(date(2024, 12, 31), date(2024, 1, 1), 10, 8);
			fail("expected a complaint about the reversed range");
		}
		catch (InvalidReportFilterException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("after its end"));
		}
	}
	
	@Test
	public void diagnosesByTreatmentAcrossClinic_shouldLayThePairsOutAndReadAGenerousCap() {
		dao.acrossClinicPairs = Arrays.asList(new CrossTabRow("a", "A", "x", "X", 5), new CrossTabRow("a", "A", "y", "Y", 3),
		    new CrossTabRow("b", "B", "x", "X", 1));
		
		CrossTab matrix = service.diagnosesByTreatmentAcrossClinic(date(), date(), 10, 10);
		assertEquals("A", matrix.getRows().get(0).getLabel());
		assertEquals(9L, matrix.getGrandTotal());
		assertEquals(1000, dao.lastAcrossClinicLimit);
	}
	
	// ---------------------------------------------------------------- the simple counts
	
	@Test
	public void compute_shouldCountActiveEnrollments() {
		writeDefinitions("activeEnrollments");
		dao.activeEnrollments = 42;
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		assertTrue(result.isAvailable());
		assertEquals(Double.valueOf(42), result.getValue());
		// The count is also exposed as the numerator so a proportion tile could be built from it.
		assertEquals(Long.valueOf(42), result.getNumerator());
	}
	
	@Test
	public void compute_shouldReportAProgrammeWithNobodyInItAsZero() {
		writeDefinitions("activeEnrollments");
		dao.activeEnrollments = 0;
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		// Zero here is a fact about the programme, not a missing measurement.
		assertTrue(result.isAvailable());
		assertEquals(Double.valueOf(0), result.getValue());
	}
	
	@Test
	public void compute_shouldCountNewAndCompletedEnrollmentsSeparately() {
		writeDefinitions("newEnrollments", "completedEnrollments");
		dao.newEnrollments = 7;
		dao.completedEnrollments = 3;
		List<IndicatorResult> results = service.compute(service.getProgram(program()), date(), date());
		assertEquals(Double.valueOf(7), results.get(0).getValue());
		assertEquals(Double.valueOf(3), results.get(1).getValue());
	}
	
	@Test
	public void compute_shouldCountEncounters() {
		writeDefinitions("encounters");
		dao.encounters = 128;
		assertEquals(Double.valueOf(128), only(service.compute(service.getProgram(program()), date(), date())).getValue());
	}
	
	// ---------------------------------------------------------------- breakdowns and series
	
	@Test
	public void compute_shouldGiveABreakdownNoTotal() {
		writeDefinitions("enrolledByGender");
		dao.byGender = breakdown("Female", 30, "Male", 12);
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		assertEquals(2, result.getBreakdown().size());
		// A total would double the counting on screen: the chart already shows the split.
		assertNull(result.getValue());
	}
	
	@Test
	public void compute_shouldGiveTheMonthlySeriesAndCountPeopleInThePeriod() {
		writeDefinitions("enrollmentsByMonth");
		dao.months = new ArrayList<>(Arrays.asList(point("2024-01", 4), point("2024-02", 5)));
		dao.newEnrollments = 8;
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		assertEquals(2, result.getSeries().size());
		// One patient re-enrolling in February is in both buckets, so 4 + 5 is nine enrolment events
		// but eight people. The tile says people.
		assertEquals(Double.valueOf(8), result.getValue());
	}
	
	// ---------------------------------------------------------------- clinical measures
	
	@Test
	public void compute_shouldResolveTheIndicatorConcepts() {
		writeDefinitions("patientsWithObservation");
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.patientsWithObservations = 55;
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		assertTrue(result.isAvailable());
		assertEquals(Double.valueOf(55), result.getValue());
		assertEquals(Collections.singleton(HIV_STATUS), dao.lastConcepts);
	}
	
	@Test
	public void compute_shouldSayWhichConceptThisServerHasNotGot() {
		writeDefinitions("patientsWithObservation");
		// Nothing resolves, and the name comes back from the DAO so the message can say which one.
		assertFalse(only(service.compute(service.getProgram(program()), date(), date())).isAvailable());
		String reason = only(service.compute(service.getProgram(program()), date(), date())).getUnavailableReason();
		assertTrue(reason, reason.contains("HIV Status"));
		// And it says what to do about it, because an administrator reading this is the person who can.
		assertTrue(reason, reason.contains("program-indicators.json"));
	}
	
	@Test
	public void compute_shouldCountObservations() {
		writeDefinitions("observationCount");
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.observations = 61;
		assertEquals(Double.valueOf(61), only(service.compute(service.getProgram(program()), date(), date())).getValue());
	}
	
	@Test
	public void compute_shouldAverageNumericValues() {
		writeDefinitions("averageValue");
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.average = Double.valueOf(512.5);
		assertEquals(Double.valueOf(512.5), only(service.compute(service.getProgram(program()), date(), date())).getValue());
	}
	
	@Test
	public void compute_shouldShowNothingRatherThanZeroWhenNoNumberWasRecorded() {
		writeDefinitions("averageValue");
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.average = null;
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		// The indicator is fine; there is simply nothing to average. Calling that zero would tell a
		// clinician the average value was none.
		assertTrue(result.isAvailable());
		assertNull(result.getValue());
	}
	
	@Test
	public void compute_shouldGroupTheValueDistribution() {
		writeDefinitions("valueDistribution");
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.distribution = breakdown("Positive", 9, "Negative", 31);
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		assertEquals(2, result.getBreakdown().size());
	}
	
	// ---------------------------------------------------------------- rates
	
	@Test
	public void compute_shouldReportARateAsAPercentageOfItsDenominator() {
		writeRateDefinition();
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.denominatorConcepts.put("ART", CD4);
		dao.patientsByConcept.put(HIV_STATUS, Integer.valueOf(9));
		dao.patientsByConcept.put(CD4, Integer.valueOf(36));
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		assertEquals(Double.valueOf(25), result.getValue());
		assertEquals(Long.valueOf(9), result.getNumerator());
		assertEquals(Long.valueOf(36), result.getDenominator());
	}
	
	@Test
	public void compute_shouldShowTheCountsButNoPercentageWhenNobodyIsInTheDenominator() {
		writeRateDefinition();
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.denominatorConcepts.put("ART", CD4);
		dao.patientsByConcept.put(HIV_STATUS, Integer.valueOf(0));
		dao.patientsByConcept.put(CD4, Integer.valueOf(0));
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		// "0 tested of 0" is a real answer; a percentage of it would be a division by zero.
		assertTrue(result.isAvailable());
		assertNull(result.getValue());
		assertEquals(Long.valueOf(0), result.getNumerator());
		assertEquals(Long.valueOf(0), result.getDenominator());
	}
	
	@Test
	public void compute_shouldNotReportARateWhoseDenominatorIsNotOnThisServer() {
		writeRateDefinition();
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.denominatorUnresolved = new LinkedHashSet<>(Collections.singletonList("ART"));
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		assertFalse(result.isAvailable());
		String reason = result.getUnavailableReason();
		assertTrue(reason, reason.contains("denominator"));
		assertTrue(reason, reason.contains("ART"));
	}
	
	@Test
	public void compute_shouldCountOnlyTheValuesUnderTheThreshold() {
		writeDefinitions("lowValueCount");
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.observations = 4;
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		assertTrue(result.isAvailable());
		assertEquals(Double.valueOf(4), result.getValue());
		// The limit comes from the definition file, not from a constant in this class.
		assertEquals(Double.valueOf(2.5), dao.lastValueBelow);
	}
	
	@Test
	public void compute_shouldMeasureCoverageAgainstTheEnrolment() {
		writeDefinitions("enrolledCoverageRate");
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.activeEnrollments = 40;
		dao.patientsWithObservations = 30;
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		// 30 of the 40 people enrolled were tested. Dividing by the patients who were tested instead
		// would have reported 100% and told the programme manager nobody had been missed.
		assertEquals(Double.valueOf(75), result.getValue());
		assertEquals(Long.valueOf(30), result.getNumerator());
		assertEquals(Long.valueOf(40), result.getDenominator());
	}
	
	@Test
	public void compute_shouldShowNoCoverageWhenNobodyIsEnrolled() {
		writeDefinitions("enrolledCoverageRate");
		dao.concepts.put("HIV Status", HIV_STATUS);
		dao.activeEnrollments = 0;
		dao.patientsWithObservations = 0;
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		// No percentage, but the counts stand: "0 of 0" is the answer, not a division by zero.
		assertTrue(result.isAvailable());
		assertNull(result.getValue());
		assertEquals(Long.valueOf(0), result.getDenominator());
	}
	
	// ---------------------------------------------------------------- failure containment
	
	@Test
	public void compute_shouldKeepGoingWhenOneIndicatorFails() {
		writeDefinitions("activeEnrollments", "newEnrollments", "completedEnrollments");
		dao.activeEnrollments = 5;
		dao.newEnrollments = 2;
		dao.failOnCompletedEnrollments = true;
		List<IndicatorResult> results = service.compute(service.getProgram(program()), date(), date());
		assertEquals(3, results.size());
		// The failure is contained to the indicator that caused it: the other two keep their figures,
		// so one broken definition cannot empty the page.
		assertTrue(results.get(0).isAvailable());
		assertEquals(Double.valueOf(5), results.get(0).getValue());
		assertTrue(results.get(1).isAvailable());
		assertEquals(Double.valueOf(2), results.get(1).getValue());
		assertFalse(results.get(2).isAvailable());
	}
	
	@Test
	public void compute_shouldNotLeakTheDetailOfAFailureOntoTheScreen() {
		writeDefinitions("activeEnrollments");
		dao.failure = new IllegalStateException("select * from obs where secret = 'hunter2'");
		IndicatorResult result = only(service.compute(service.getProgram(program()), date(), date()));
		// The log keeps the detail for whoever has to fix it; the page says only that it failed.
		assertNull(result.getValue());
		assertFalse(result.getUnavailableReason().contains("hunter2"));
		assertTrue(result.getUnavailableReason(), result.getUnavailableReason().contains("server log"));
	}
	
	// ---------------------------------------------------------------- helpers
	
	private static String program() {
		return PMTCT;
	}
	
	private static Date date() {
		return date(2024, 1, 1);
	}
	
	private static Date date(int year, int month, int day) {
		java.util.Calendar calendar = java.util.Calendar.getInstance();
		calendar.clear();
		calendar.set(year, month - 1, day, 12, 0, 0);
		return calendar.getTime();
	}
	
	private static IndicatorResult only(List<IndicatorResult> results) {
		assertEquals(1, results.size());
		return results.get(0);
	}
	
	private static BreakdownRow row(String label, long count) {
		BreakdownRow row = new BreakdownRow();
		row.setLabel(label);
		row.setCount(count);
		return row;
	}
	
	private static List<BreakdownRow> breakdown(String firstLabel, long firstCount, String secondLabel, long secondCount) {
		List<BreakdownRow> rows = new ArrayList<>();
		rows.add(row(firstLabel, firstCount));
		rows.add(row(secondLabel, secondCount));
		return rows;
	}
	
	private static TimeSeriesPoint point(String period, long count) {
		TimeSeriesPoint point = new TimeSeriesPoint();
		point.setPeriod(period);
		point.setCount(count);
		return point;
	}
	
	/**
	 * Writes a definition file with one programme holding the given metrics, and points the catalogue
	 * at it. Indicators are built from JSON rather than by hand because {@code DashboardIndicator}
	 * deliberately only lets the catalogue build them.
	 */
	private void writeDefinitions(String... metrics) {
		if (metrics.length == 0) {
			// An indicators array may not be empty, so these cases, which are not about any particular
			// measurement, still need one.
			metrics = new String[] { "activeEnrollments" };
		}
		writeDefinitions(metrics, "HIV Status", Collections.<String> emptyList());
	}
	
	/**
	 * Writes a single rate indicator whose denominator is a concept the test can choose to resolve or
	 * not.
	 */
	private void writeRateDefinition() {
		writeDefinitions(new String[] { "positiveRate" }, "HIV Status", Collections.singletonList("ART"));
	}
	
	private void writeDefinitions(String[] metrics, String numeratorConcept, List<String> denominatorConcepts) {
		ArrayNode indicators = JsonNodeFactory.instance.arrayNode();
		for (int i = 0; i < metrics.length; i++) {
			ObjectNode indicator = JsonNodeFactory.instance.objectNode().put("id", "indicator-" + i);
			indicator.put("label", metrics[i]).put("metric", metrics[i]);
			if ("lowValueCount".equals(metrics[i])) {
				indicator.put("valueBelow", 2.5);
			}
			if (!numeratorConcept.isEmpty()) {
				indicator.putArray("concepts").add(numeratorConcept);
			}
			if (!denominatorConcepts.isEmpty()) {
				indicator.putArray("denominatorConcepts").add(denominatorConcepts.get(0));
			}
			indicators.add(indicator);
		}
		ObjectNode pmtct = JsonNodeFactory.instance.objectNode().put("uuid", PMTCT).put("name", "PMTCT");
		pmtct.set("indicators", indicators);
		ObjectNode tb = JsonNodeFactory.instance.objectNode().put("uuid", TB).put("name", "TB");
		// One indicator, so the file is valid; the TB programme is only here to be absent from the
		// server and prove that an uninstalled programme is not offered.
		tb.set("indicators", JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("id", "tb-1")
		        .put("label", "Enrolled").put("metric", "activeEnrollments")));
		ObjectNode root = JsonNodeFactory.instance.objectNode();
		root.putArray("programs").add(pmtct).add(tb);
		
		File directory = new File(System.getProperty("java.io.tmpdir"), "dashboard-service-test");
		directory.mkdirs();
		try {
			Files.write(new File(directory, ProgramIndicatorCatalog.FILE_NAME).toPath(),
			    new ObjectMapper().writeValueAsBytes(root));
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		ProgramIndicatorCatalog.clearCache();
		ProgramIndicatorCatalog.setConfigurationDirectory(directory);
	}
	
	// ---------------------------------------------------------------- the stub
	
	/**
	 * Records what it was asked and answers from fields the test sets, so each case reads as a
	 * statement about the service rather than about the DAO.
	 */
	private static final class StubDao implements ProgramDashboardDao {
		
		private Set<String> present = new LinkedHashSet<>();
		
		private final Map<String, Integer> concepts = new LinkedHashMap<>();
		
		private final Map<String, Integer> denominatorConcepts = new LinkedHashMap<>();
		
		private final Map<Integer, Integer> patientsByConcept = new LinkedHashMap<>();
		
		private Set<String> denominatorUnresolved = new LinkedHashSet<>();
		
		private Set<Integer> lastConcepts;
		
		private Double lastValueBelow;
		
		private long activeEnrollments;
		
		private long newEnrollments;
		
		private long completedEnrollments;
		
		private long encounters;
		
		private long patientsWithObservations;
		
		private long observations;
		
		private Double average;
		
		private List<BreakdownRow> byGender = new ArrayList<>();
		
		private List<BreakdownRow> distribution = new ArrayList<>();
		
		private List<TimeSeriesPoint> months = new ArrayList<>();
		
		private List<CrossTabRow> diagnosisPairs = new ArrayList<>();
		
		private List<CrossTabRow> acrossClinicPairs = new ArrayList<>();
		
		private int lastPairLimit;
		
		private int lastAcrossClinicLimit;
		
		private boolean failOnCompletedEnrollments;
		
		private RuntimeException failure;
		
		@Override
		public Set<String> existingProgramUuids(java.util.Collection<String> uuids) {
			// The real DAO answers lower-cased, whatever case the server holds its UUIDs in.
			Set<String> lowerCased = new LinkedHashSet<>();
			for (String uuid : present) {
				lowerCased.add(uuid.toLowerCase());
			}
			return lowerCased;
		}
		
		@Override
		public boolean programExists(String programUuid) {
			return present.contains(programUuid == null ? null : programUuid.toLowerCase());
		}
		
		@Override
		public List<String> allProgramUuids() {
			return new ArrayList<>(present);
		}
		
		@Override
		public long countActiveEnrollments(String programUuid, Date from, Date to) {
			if (failure != null) {
				throw failure;
			}
			return activeEnrollments;
		}
		
		@Override
		public long countNewEnrollments(String programUuid, Date from, Date to) {
			return newEnrollments;
		}
		
		@Override
		public long countCompletedEnrollments(String programUuid, Date from, Date to) {
			if (failOnCompletedEnrollments) {
				throw failure();
			}
			return completedEnrollments;
		}
		
		@Override
		public long countEncounters(String programUuid, Date from, Date to) {
			return encounters;
		}
		
		@Override
		public List<BreakdownRow> enrolledByGender(String programUuid, Date from, Date to) {
			return byGender;
		}
		
		@Override
		public List<BreakdownRow> enrolledByAgeBand(String programUuid, Date from, Date to) {
			return byGender;
		}
		
		@Override
		public List<TimeSeriesPoint> enrollmentsByMonth(String programUuid, Date from, Date to) {
			return months;
		}
		
		@Override
		public long countPatientsWithObservations(String programUuid, Date from, Date to,
		        java.util.Collection<Integer> conceptIds) {
			lastConcepts = new LinkedHashSet<>(conceptIds);
			if (failure != null) {
				throw failure;
			}
			// A rate asks twice, once for its numerator and once for its denominator, and needs a
			// different answer each time, so the test can pin a count per concept. Everything else
			// wants the same count every time.
			boolean perConcept = false;
			for (Integer conceptId : conceptIds) {
				perConcept |= patientsByConcept.containsKey(conceptId);
			}
			if (!perConcept) {
				return patientsWithObservations;
			}
			long total = 0;
			for (Integer conceptId : conceptIds) {
				Integer count = patientsByConcept.get(conceptId);
				if (count != null) {
					total += count.intValue();
				}
			}
			return total;
		}
		
		@Override
		public long countObservations(String programUuid, Date from, Date to, java.util.Collection<Integer> conceptIds) {
			return observations;
		}
		
		@Override
		public long countObservationsBelow(String programUuid, Date from, Date to, java.util.Collection<Integer> conceptIds,
		        double valueBelow) {
			lastConcepts = new LinkedHashSet<>(conceptIds);
			lastValueBelow = Double.valueOf(valueBelow);
			return observations;
		}
		
		@Override
		public Double averageObservationValue(String programUuid, Date from, Date to,
		        java.util.Collection<Integer> conceptIds) {
			return average;
		}
		
		@Override
		public List<BreakdownRow> observationValueDistribution(String programUuid, Date from, Date to,
		        java.util.Collection<Integer> conceptIds) {
			return distribution;
		}
		
		@Override
		public List<CrossTabRow> countDiagnosesByTreatment(String programUuid, Date from, Date to, int limit) {
			if (failure != null) {
				throw failure;
			}
			lastPairLimit = limit;
			return diagnosisPairs;
		}
		
		@Override
		public List<CrossTabRow> countDiagnosesByTreatmentAcrossClinic(Date from, Date to, int limit) {
			if (failure != null) {
				throw failure;
			}
			lastAcrossClinicLimit = limit;
			return acrossClinicPairs;
		}
		
		@Override
		public Set<Integer> resolveConceptIds(java.util.Collection<String> uuids, java.util.Collection<String> names) {
			Set<Integer> resolved = new LinkedHashSet<>();
			if (names == null) {
				return resolved;
			}
			for (String name : names) {
				if (concepts.containsKey(name)) {
					resolved.add(Integer.valueOf(concepts.get(name)));
				}
				if (denominatorConcepts.containsKey(name)) {
					resolved.add(Integer.valueOf(denominatorConcepts.get(name)));
				}
			}
			return resolved;
		}
		
		@Override
		public Set<String> unresolvedConceptNames(java.util.Collection<String> names) {
			Set<String> missing = new LinkedHashSet<>();
			if (names == null) {
				return missing;
			}
			for (String name : names) {
				if (!concepts.containsKey(name) && !denominatorConcepts.containsKey(name)) {
					missing.add(name);
				}
			}
			return missing;
		}
		
		private RuntimeException failure() {
			return failure != null ? failure : new IllegalStateException("stubbed failure");
		}
	}
}
