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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dashboard.DashboardIndicator;
import org.openmrs.module.clinicreporting.api.dashboard.IndicatorResult;
import org.openmrs.module.clinicreporting.api.dashboard.ProgramIndicatorCatalog;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tests the date-range arithmetic and the current-versus-previous pairing of the programme
 * dashboards.
 * <p>
 * The pairing is the part worth testing hard. A tile that quietly shows the wrong previous figure
 * is worse than one showing nothing at all, because a programme manager will act on it.
 */
public class ProgramDashboardControllerTest {
	
	private static final String PROGRAMME_UUID = "3f257581-7e3a-4cf9-8a22-9a1dbd2a8c10";
	
	private static final List<String> INDICATOR_IDS = Arrays.asList("a", "b", "c", "new");
	
	@After
	public void forgetTheDefinitions() {
		ProgramIndicatorCatalog.clearCache();
	}
	
	private static DashboardIndicator indicator(String id) {
		for (DashboardIndicator candidate : catalogue().findByUuid(PROGRAMME_UUID).getIndicators()) {
			if (id.equals(candidate.getId())) {
				return candidate;
			}
		}
		throw new IllegalArgumentException("no such indicator in the fixture: " + id);
	}
	
	/**
	 * Builds indicators through the catalogue rather than by hand, which is also the path production
	 * takes. {@code DashboardIndicator} keeps its constructor package-private on purpose so that a
	 * half-populated indicator cannot exist; reading a definition file is the supported way to get one.
	 */
	private static ProgramIndicatorCatalog catalogue() {
		File directory = new File(System.getProperty("java.io.tmpdir"), "dashboard-controller-test");
		directory.mkdirs();
		ArrayNode indicators = JsonNodeFactory.instance.arrayNode();
		for (String id : INDICATOR_IDS) {
			indicators.add(JsonNodeFactory.instance.objectNode().put("id", id).put("label", id.toUpperCase()).put("metric",
			    "newEnrollments"));
		}
		ObjectNode program = JsonNodeFactory.instance.objectNode().put("uuid", PROGRAMME_UUID).put("name", "Test");
		program.set("indicators", indicators);
		ObjectNode root = JsonNodeFactory.instance.objectNode();
		root.putArray("programs").add(program);
		try {
			java.nio.file.Files.write(new File(directory, ProgramIndicatorCatalog.FILE_NAME).toPath(),
			    new ObjectMapper().writeValueAsBytes(root));
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		ProgramIndicatorCatalog.clearCache();
		ProgramIndicatorCatalog.setConfigurationDirectory(directory);
		return ProgramIndicatorCatalog.load();
	}
	
	private static IndicatorResult available(String id, Double value) {
		return IndicatorResult.of(indicator(id), value, null, null, null, null);
	}
	
	private static List<IndicatorResult> paired(String id) {
		for (IndicatorResult result : ProgramDashboardController.compare(
		    Collections.singletonList(available(id, Double.valueOf(10))),
		    Collections.singletonList(available(id, Double.valueOf(4))))) {
			return Collections.singletonList(result);
		}
		throw new IllegalStateException("expected one result");
	}
	
	// ---------------------------------------------------------------- change
	
	@Test
	public void compare_shouldSubtractTheEarlierFigure() {
		assertEquals(Double.valueOf(6), paired("a").get(0).getChange());
	}
	
	@Test
	public void compare_shouldKeepTheCurrentFigure() {
		assertEquals(Double.valueOf(10), paired("a").get(0).getValue());
	}
	
	@Test
	public void compare_shouldShowGrowthAgainstTheSameIndicatorWhateverTheOrder() {
		// Pairing by id rather than by position: an indicator added to the middle of the definition
		// file must not shift every later comparison onto its neighbour's figure.
		List<IndicatorResult> current = Arrays.asList(available("a", Double.valueOf(10)), available("b", Double.valueOf(50)),
		    available("c", Double.valueOf(7)));
		List<IndicatorResult> previous = Arrays.asList(available("c", Double.valueOf(3)), available("a", Double.valueOf(10)),
		    available("b", Double.valueOf(25)));
		List<IndicatorResult> paired = ProgramDashboardController.compare(current, previous);
		
		assertEquals(3, paired.size());
		assertEquals(Double.valueOf(0), paired.get(0).getChange());
		assertEquals(Double.valueOf(25), paired.get(1).getChange());
		assertEquals(Double.valueOf(4), paired.get(2).getChange());
	}
	
	@Test
	public void compare_shouldLeaveTheChangeUnsetWhenThereIsNoEarlierFigure() {
		// An indicator added yesterday has no previous period to compare against. Showing zero change
		// would claim the figure held steady, which is an assertion about data that does not exist.
		List<IndicatorResult> paired = ProgramDashboardController.compare(
		    Collections.singletonList(available("new", Double.valueOf(10))),
		    Collections.singletonList(available("c", Double.valueOf(4))));
		assertNull(paired.get(0).getChange());
		assertEquals(Double.valueOf(10), paired.get(0).getValue());
	}
	
	@Test
	public void compare_shouldLeaveTheChangeUnsetWhenTheEarlierFigureIsMissing() {
		List<IndicatorResult> paired = ProgramDashboardController.compare(
		    Collections.singletonList(available("a", Double.valueOf(10))),
		    Collections.singletonList(IndicatorResult.of(indicator("a"), null, null, null, null, null)));
		assertNull(paired.get(0).getChange());
	}
	
	@Test
	public void compare_shouldLeaveTheChangeUnsetWhenTheEarlierPeriodWasUnavailable() {
		IndicatorResult before = IndicatorResult.unavailable(indicator("a"), "clinicreporting.dashboard.conceptMissing");
		List<IndicatorResult> paired = ProgramDashboardController
		        .compare(Collections.singletonList(available("a", Double.valueOf(10))), Collections.singletonList(before));
		// "We could not measure it last period" is not "it was zero last period".
		assertNull(paired.get(0).getChange());
	}
	
	@Test
	public void compare_shouldKeepAnUnavailableCurrentResultUnavailable() {
		IndicatorResult now = IndicatorResult.unavailable(indicator("a"), "clinicreporting.dashboard.conceptMissing");
		List<IndicatorResult> paired = ProgramDashboardController.compare(Collections.singletonList(now),
		    Collections.singletonList(available("a", Double.valueOf(4))));
		IndicatorResult result = paired.get(0);
		// Rebuilding the result here would have produced an available result with a null value, which
		// the view renders as "no data" - losing the reason the server cannot answer.
		assertFalse(result.isAvailable());
		assertEquals("clinicreporting.dashboard.conceptMissing", result.getUnavailableReason());
		assertNull(result.getValue());
		assertNull(result.getChange());
	}
	
	@Test
	public void compare_shouldReturnTheSameResultInstanceSoNothingIsLost() {
		IndicatorResult now = available("a", Double.valueOf(10));
		List<IndicatorResult> paired = ProgramDashboardController.compare(Collections.singletonList(now),
		    Collections.singletonList(available("a", Double.valueOf(4))));
		// The definition object is carried through, so the label and unit on the tile come from the
		// current definition file and not from whatever the previous period happened to be.
		assertSame(now.getDefinition(), paired.get(0).getDefinition());
	}
	
	@Test
	public void compare_shouldCopeWithNothingToCompare() {
		assertTrue(ProgramDashboardController
		        .compare(Collections.<IndicatorResult> emptyList(), Collections.<IndicatorResult> emptyList()).isEmpty());
	}
	
	@Test
	public void compare_shouldKeepTheOrderOfTheCurrentPeriod() {
		List<IndicatorResult> current = Arrays.asList(available("b", Double.valueOf(2)), available("a", Double.valueOf(1)));
		List<IndicatorResult> paired = ProgramDashboardController.compare(current,
		    Collections.singletonList(available("a", Double.valueOf(1))));
		// The tile order comes from the definition file and must not follow the previous period's.
		assertEquals("b", paired.get(0).getDefinition().getId());
		assertEquals("a", paired.get(1).getDefinition().getId());
		assertNull(paired.get(0).getChange());
	}
	
	// ---------------------------------------------------------------- date range
	
	@Test
	public void resolveRange_shouldUseTheSuppliedDates() {
		Date[] range = ProgramDashboardController.resolveRange("2026-01-01", "2026-03-31");
		assertEquals("2026-01-01", iso().format(range[0]));
		assertEquals("2026-03-31", iso().format(range[1]));
	}
	
	@Test
	public void resolveRange_shouldDefaultToTheLastSixMonthsEndingToday() {
		Date[] range = ProgramDashboardController.resolveRange(null, null);
		assertFalse(range[0].after(range[1]));
		long days = (range[1].getTime() - range[0].getTime()) / (24L * 60 * 60 * 1000);
		// Six calendar months is 182 or 183 days depending where it falls; a day of slack absorbs a test
		// that runs across midnight.
		assertTrue("expected about six months, got " + days + " days", days >= 181 && days <= 184);
	}
	
	@Test
	public void resolveRange_shouldFillAMissingEndFromTheOther() {
		// A bookmark with one end is still answerable: the missing end falls back to the default lookback.
		Date[] range = ProgramDashboardController.resolveRange(null, "2026-03-31");
		assertEquals("2026-03-31", iso().format(range[1]));
		assertTrue(range[0].before(range[1]));
	}
	
	@Test
	public void resolveRange_shouldRefuseSomethingThatIsNotADate() {
		for (String bad : new String[] { "yesterday", "2024-13-45", "01/02/2024" }) {
			try {
				ProgramDashboardController.resolveRange(bad, "2026-03-31");
				throw new AssertionError("expected '" + bad + "' to be refused");
			}
			catch (InvalidReportFilterException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().contains(bad));
			}
		}
	}
	
	@Test
	public void resolveRange_shouldRefuseARangeThatRunsBackwards() {
		try {
			ProgramDashboardController.resolveRange("2026-05-01", "2026-01-01");
			throw new AssertionError("expected the backwards range to be refused");
		}
		catch (InvalidReportFilterException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("after its end"));
		}
	}
	
	private static java.text.SimpleDateFormat iso() {
		java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd");
		format.setLenient(false);
		return format;
	}
	
	@Test
	public void compare_shouldNotBeConfusedByAnEmptyPreviousPeriod() {
		List<IndicatorResult> current = new ArrayList<>(
		        Arrays.asList(available("a", Double.valueOf(10)), available("b", Double.valueOf(3))));
		List<IndicatorResult> paired = ProgramDashboardController.compare(current,
		    Collections.<IndicatorResult> emptyList());
		assertEquals(2, paired.size());
		assertNull(paired.get(0).getChange());
		assertNull(paired.get(1).getChange());
	}
}
