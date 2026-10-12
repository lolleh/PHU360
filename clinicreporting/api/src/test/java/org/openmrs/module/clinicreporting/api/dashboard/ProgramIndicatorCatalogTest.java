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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openmrs.module.clinicreporting.api.exception.ReportException;

/**
 * Tests the indicator definition file: that the definitions this module ships are usable, that an
 * edited copy is preferred, and that a mistake in an edit is reported clearly.
 * <p>
 * The reporting tests matter more than they look. A definition file is edited by hand on a live
 * server, so the failure mode that has to be impossible is the one where a typo silently produces a
 * dashboard that shows something plausible and wrong.
 */
public class ProgramIndicatorCatalogTest {
	
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();
	
	@After
	public void forgetCache() {
		ProgramIndicatorCatalog.clearCache();
	}
	
	// ---------------------------------------------------------------- shipped definitions
	
	@Test
	public void bundledDefinitions_shouldLoad() {
		ProgramIndicatorCatalog catalog = ProgramIndicatorCatalog.load();
		assertFalse("the module ships indicator definitions", catalog.getPrograms().isEmpty());
		assertNotNull(catalog.getSourceDescription());
	}
	
	@Test
	public void bundledDefinitions_shouldCoverTheNineProgrammes() {
		// The nine programmes the dashboards were asked for. Named by UUID because that is what the
		// enrolments are matched on; a definition that drifts to a different UUID stops finding
		// patients, and that is exactly the kind of change that should not slip through unnoticed.
		String[] expected = { "3f257581-7e3a-4cf9-8a22-9a1dbd2a8c10", // AYFS
		        "bfc32f7d-6e2b-48c2-aa43-9d4f7c87a8e4", // Family Planning
		        "a932f5d2-9f2b-4eab-823d-8d6a561e9d8c", // Gynecology
		        "2e9a4c7d-8257-4e1d-ae8f-51b29e3e7cfa", // PMTCT
		        "8d7f6e9c-d2c9-4a7f-9ddc-23a8a5e9fc92", // Pregnancy Mental Health
		        "6a5713c2-3fd5-46e7-8f25-36a0f7871e12", // Pregnancy
		        "dc1b588a-9b94-45e4-8346-71b7c9a24845", // Infant
		        "0e69c3ab-1ccb-430b-b0db-b9760319230f", // Mental Health
		        "515796ec-bf3a-11e7-abc4-cec278b6b50a" // NCD
		};
		ProgramIndicatorCatalog catalog = ProgramIndicatorCatalog.load();
		for (String uuid : expected) {
			DashboardProgram program = catalog.findByUuid(uuid);
			assertNotNull("no dashboard is defined for programme " + uuid, program);
			assertFalse("programme " + program.getName() + " has no indicators", program.getIndicators().isEmpty());
		}
		assertEquals(expected.length, catalog.getPrograms().size());
	}
	
	@Test
	public void everyBundledIndicator_shouldBeOneTheDaoCanCompute() {
		for (DashboardProgram program : ProgramIndicatorCatalog.load().getPrograms()) {
			for (DashboardIndicator indicator : program.getIndicators()) {
				assertNotNull(indicator.getMetric());
				assertNotNull(indicator.getChartKind());
				assertTrue(DashboardIndicator.isSupportedChartKind(indicator.getChartKind()));
				assertFalse(indicator.getLabel().trim().isEmpty());
				assertFalse(indicator.getId().trim().isEmpty());
			}
		}
	}
	
	@Test
	public void findByUuid_shouldIgnoreCaseAndSurroundingSpace() {
		DashboardProgram program = ProgramIndicatorCatalog.load().findByUuid("  3F257581-7E3A-4CF9-8A22-9A1DBD2A8C10 ");
		assertNotNull(program);
		assertEquals("AYFS", program.getShortName());
	}
	
	@Test
	public void findByUuid_shouldReturnNullForAnUnknownProgramme() {
		assertNull(ProgramIndicatorCatalog.load().findByUuid("11111111-1111-1111-1111-111111111111"));
		assertNull(ProgramIndicatorCatalog.load().findByUuid(null));
		assertNull(ProgramIndicatorCatalog.load().findByUuid("   "));
	}
	
	// ---------------------------------------------------------------- the edited copy
	
	@Test
	public void configurationDirectory_shouldBePreferredOverTheBundledFile() throws Exception {
		File edited = write("program-indicators.json",
		    "{\"version\":1,\"programs\":[{\"uuid\":\"11111111-1111-1111-1111-111111111111\","
		            + "\"name\":\"Edited\",\"shortName\":\"ED\",\"indicators\":"
		            + "[{\"id\":\"one\",\"label\":\"One\",\"metric\":\"activeEnrollments\"}]}]}");
		
		ProgramIndicatorCatalog.setConfigurationDirectory(folder.getRoot());
		ProgramIndicatorCatalog catalog = ProgramIndicatorCatalog.load();
		assertEquals(1, catalog.getPrograms().size());
		assertEquals("Edited", catalog.getPrograms().get(0).getName());
		assertTrue(catalog.getSourceDescription().contains("program-indicators.json"));
		assertNotNull(catalog.findByUuid("11111111-1111-1111-1111-111111111111"));
		assertEquals(edited.getAbsolutePath(),
		    folder.getRoot().getAbsolutePath() + File.separator + "program-indicators.json");
	}
	
	@Test
	public void aMalformedConfigurationFile_shouldBeReportedRatherThanIgnored() throws Exception {
		// Silently falling back to the shipped definitions would show an administrator a dashboard that
		// quietly ignores what they just wrote, which is the failure this module has to avoid.
		write("program-indicators.json", "{ this is not json");
		ProgramIndicatorCatalog.setConfigurationDirectory(folder.getRoot());
		try {
			ProgramIndicatorCatalog.load();
			fail("Expected the malformed definition file to be reported");
		}
		catch (ReportException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("not valid JSON"));
		}
	}
	
	@Test
	public void anIncompleteConfigurationFile_shouldNameTheOffendingProgramme() throws Exception {
		write("program-indicators.json",
		    "{\"version\":1,\"programs\":[{\"uuid\":\"not-a-uuid\",\"name\":\"Broken\",\"indicators\":"
		            + "[{\"id\":\"one\",\"label\":\"One\",\"metric\":\"activeEnrollments\"}]}]}");
		ProgramIndicatorCatalog.setConfigurationDirectory(folder.getRoot());
		try {
			ProgramIndicatorCatalog.load();
			fail("Expected the invalid uuid to be reported");
		}
		catch (ReportException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("not-a-uuid"));
			assertTrue(e.getMessage(), e.getMessage().contains("programs[0]"));
		}
	}
	
	@Test
	public void aMissingConfigurationDirectory_shouldFallBackToTheBundledFile() {
		ProgramIndicatorCatalog.setConfigurationDirectory(new File(folder.getRoot(), "does-not-exist"));
		assertFalse(ProgramIndicatorCatalog.load().getPrograms().isEmpty());
	}
	
	// ---------------------------------------------------------------- parsing and validation
	
	@Test
	public void parse_shouldReadBothConceptForms() {
		ProgramIndicatorCatalog catalog = ProgramIndicatorCatalog
		        .parse("{\"programs\":[{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"P\","
		                + "\"indicators\":[{\"id\":\"i\",\"label\":\"I\",\"metric\":\"patientsWithObservation\","
		                + "\"concepts\":[\"HIV Status\",{\"uuid\":\"22222222-2222-2222-2222-222222222222\"},"
		                + "{\"name\":\"Condom\"}]}]}]}",
		            "test");
		DashboardIndicator indicator = catalog.getPrograms().get(0).getIndicators().get(0);
		assertEquals(1, indicator.getConceptUuids().size());
		assertEquals("22222222-2222-2222-2222-222222222222", indicator.getConceptUuids().get(0));
		assertEquals(2, indicator.getConceptNames().size());
		assertTrue(indicator.getConceptNames().contains("HIV Status"));
		assertTrue(indicator.getConceptNames().contains("Condom"));
	}
	
	@Test
	public void parse_shouldDefaultTheChartKindFromTheMetric() {
		// A file that says nothing about charts still has to produce a drawable dashboard.
		DashboardIndicator breakdown = firstIndicator("{\"id\":\"i\",\"label\":\"I\",\"metric\":\"enrolledByGender\"}");
		assertEquals("bar", breakdown.getChartKind());
		DashboardIndicator scalar = firstIndicator("{\"id\":\"i\",\"label\":\"I\",\"metric\":\"activeEnrollments\"}");
		assertEquals("none", scalar.getChartKind());
	}
	
	@Test
	public void parse_shouldRejectAnUnknownMetric() {
		assertRejected("{\"id\":\"i\",\"label\":\"I\",\"metric\":\"somethingElse\"}", "somethingElse");
	}
	
	@Test
	public void load_shouldPickUpAnEditedDefinitionFile() throws IOException {
		ProgramIndicatorCatalog.setConfigurationDirectory(folder.getRoot());
		File file = definitionNamed("Before");
		assertEquals("Before", loadNameOf());
		
		// The whole point of the file living outside the jar: save it, reload the page, see the change.
		// The timestamp has to move, so this cannot pass on the strength of a same-second write.
		rewrite(file, "After");
		assertEquals("After", loadNameOf());
	}
	
	@Test
	public void load_shouldComplainAboutAnEditedFileThatNoLongerParses() throws IOException {
		ProgramIndicatorCatalog.setConfigurationDirectory(folder.getRoot());
		File file = definitionNamed("Before");
		assertEquals("Before", loadNameOf());
		
		rewrite(file, "{ this is not json");
		try {
			ProgramIndicatorCatalog.load();
			fail("expected the broken edit to be reported");
		}
		catch (ReportException expected) {
			// Quietly carrying on with the old definitions would show a clinician numbers from a file they
			// have already replaced.
			assertNotNull(expected.getMessage());
		}
	}
	
	@Test
	public void load_shouldFallBackToTheBundledDefinitionsWhenTheEditedFileIsRemoved() throws IOException {
		ProgramIndicatorCatalog.setConfigurationDirectory(folder.getRoot());
		File file = definitionNamed("OnlyMine");
		assertEquals("OnlyMine", loadNameOf());
		assertEquals(1, ProgramIndicatorCatalog.load().getPrograms().size());
		
		// Deleting the file is an edit like any other. Leaving the deleted definitions on screen would
		// be the same lie as ignoring a broken edit.
		assertTrue(file.delete());
		assertNotEquals("OnlyMine", loadNameOf());
		assertTrue(ProgramIndicatorCatalog.load().getSourceDescription().contains("bundled"));
	}
	
	@Test
	public void load_shouldRetryAnEditedFileThatNoLongerParses() throws IOException {
		ProgramIndicatorCatalog.setConfigurationDirectory(folder.getRoot());
		File file = definitionNamed("Before");
		assertEquals("Before", loadNameOf());
		
		rewrite(file, "{ this is not json");
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				ProgramIndicatorCatalog.load();
				fail("expected the broken edit to be reported on every load");
			}
			catch (ReportException expected) {
				assertNotNull(expected.getMessage());
			}
			// The timestamp of a file that did not parse must not be remembered as read, or the second
			// load would quietly succeed with the definitions the administrator has already replaced.
		}
	}
	
	@Test
	public void load_shouldKeepServingTheSameDefinitionsWhenTheFileIsUntouched() throws IOException {
		ProgramIndicatorCatalog.setConfigurationDirectory(folder.getRoot());
		definitionNamed("Once");
		assertEquals("Once", loadNameOf());
		assertEquals("Once", loadNameOf());
	}
	
	private String loadNameOf() {
		return ProgramIndicatorCatalog.load().getPrograms().get(0).getName();
	}
	
	/**
	 * Writes a definition file holding one programme under the given name.
	 */
	private File definitionNamed(String programmeName) throws IOException {
		return write(ProgramIndicatorCatalog.FILE_NAME, oneProgramme("3f257581-7e3a-4cf9-8a22-9a1dbd2a8c10", programmeName));
	}
	
	private String oneProgramme(String uuid, String programmeName) {
		return "{\"programs\":[{\"uuid\":\"" + uuid + "\",\"name\":\"" + programmeName + "\","
		        + "\"indicators\":[{\"id\":\"i\",\"label\":\"I\",\"metric\":\"activeEnrollments\"}]}]}";
	}
	
	/**
	 * Replaces the file's contents the way an editor saving it would: same path, new bytes, and a
	 * timestamp that has moved.
	 */
	private void rewrite(File file, String programmeNameOrRawJson) throws IOException {
		String content = programmeNameOrRawJson.startsWith("{ this") ? programmeNameOrRawJson
		        : oneProgramme("3f257581-7e3a-4cf9-8a22-9a1dbd2a8c10", programmeNameOrRawJson);
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
		file.setLastModified(file.lastModified() + 2000);
	}
	
	@Test
	public void parse_shouldRejectAnUnknownChartKind() {
		assertRejected("{\"id\":\"i\",\"label\":\"I\",\"metric\":\"activeEnrollments\",\"chart\":\"pie3d\"}", "pie3d");
	}
	
	@Test
	public void parse_shouldRejectAMeasurementWithNoConcept() {
		// Counting "observations of nothing" would report zero forever and look like a real finding.
		assertRejected("{\"id\":\"i\",\"label\":\"I\",\"metric\":\"observationCount\"}", "concepts");
	}
	
	@Test
	public void parse_shouldRejectARateWithNoDenominator() {
		assertRejected("{\"id\":\"i\",\"label\":\"I\",\"metric\":\"positiveRate\",\"concepts\":[\"A\"]}",
		    "denominatorConcepts");
	}
	
	@Test
	public void parse_shouldRejectADenominatorOnANonRate() {
		assertRejected(
		    "{\"id\":\"i\",\"label\":\"I\",\"metric\":\"activeEnrollments\"," + "\"denominatorConcepts\":[\"A\"]}",
		    "denominatorConcepts");
	}
	
	@Test
	public void parse_shouldRejectALowValueCountWithNoLimit() {
		// Without "valueBelow" this would count every recorded value, which is exactly the mistake that
		// makes a "low birth weight" indicator report every infant who was weighed.
		assertRejected("{\"id\":\"i\",\"label\":\"I\",\"metric\":\"lowValueCount\",\"concepts\":[\"A\"]}", "valueBelow");
	}
	
	@Test
	public void parse_shouldKeepTheLowValueLimit() {
		DashboardIndicator indicator = firstIndicator(
		    "{\"id\":\"i\",\"label\":\"I\",\"metric\":\"lowValueCount\",\"valueBelow\":2.5,\"concepts\":[\"A\"]}");
		assertEquals(Double.valueOf(2.5), indicator.getValueBelow());
	}
	
	@Test
	public void parse_shouldReadACoverageRateAsMeasuringAgainstTheEnrolment() {
		// The denominator is the programme itself, so the file must not name one: naming the same
		// concepts on both sides would divide a number by itself.
		DashboardIndicator indicator = firstIndicator(
		    "{\"id\":\"i\",\"label\":\"I\",\"metric\":\"enrolledCoverageRate\",\"concepts\":[\"A\"]}");
		assertEquals(IndicatorMetric.ENROLLED_COVERAGE_RATE, indicator.getMetric());
		assertRejected("{\"id\":\"i\",\"label\":\"I\",\"metric\":\"enrolledCoverageRate\",\"concepts\":[\"A\"],"
		        + "\"denominatorConcepts\":[\"B\"]}",
		    "denominatorConcepts");
	}
	
	@Test
	public void parse_shouldRejectDuplicateIndicatorIds() {
		// The id becomes a table id in the page, and a duplicate would make two indicators fight over
		// the same element.
		try {
			ProgramIndicatorCatalog.parse("{\"programs\":[{\"uuid\":\"11111111-1111-1111-1111-111111111111\","
			        + "\"name\":\"P\",\"indicators\":[{\"id\":\"same\",\"label\":\"A\",\"metric\":\"activeEnrollments\"},"
			        + "{\"id\":\"same\",\"label\":\"B\",\"metric\":\"newEnrollments\"}]}]}",
			    "test");
			fail("Expected the duplicate indicator id to be reported");
		}
		catch (ReportException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("twice"));
		}
	}
	
	@Test
	public void parse_shouldRejectADuplicateProgrammeUuid() {
		String programme = "{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"P\","
		        + "\"indicators\":[{\"id\":\"i\",\"label\":\"I\",\"metric\":\"activeEnrollments\"}]}";
		try {
			ProgramIndicatorCatalog.parse("{\"programs\":[" + programme + "," + programme + "]}", "test");
			fail("Expected the duplicate programme to be reported");
		}
		catch (ReportException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("defined twice"));
		}
	}
	
	@Test
	public void parse_shouldRejectANegativeTarget() {
		assertRejected("{\"id\":\"i\",\"label\":\"I\",\"metric\":\"activeEnrollments\",\"target\":-1}", "target");
	}
	
	@Test
	public void parse_shouldRejectAFutureFormatVersion() {
		try {
			ProgramIndicatorCatalog.parse("{\"version\":99,\"programs\":[]}", "test");
			fail("Expected the newer format version to be reported");
		}
		catch (ReportException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("version 99"));
		}
	}
	
	@Test
	public void parse_shouldRejectAProgrammeWithNoIndicators() {
		try {
			ProgramIndicatorCatalog.parse(
			    "{\"programs\":[{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"P\",\"indicators\":[]}]}",
			    "test");
			fail("Expected the empty indicator list to be reported");
		}
		catch (ReportException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("non-empty"));
		}
	}
	
	// ---------------------------------------------------------------- presentation helpers
	
	@Test
	public void targetState_shouldRespectWhichDirectionIsBetter() {
		assertEquals("missed", state(80.0, 90.0, true));
		assertEquals("met", state(95.0, 90.0, true));
		// A positivity rate or a mean blood pressure is better when it is lower, and treating a
		// falling figure as a failure would colour every good month amber.
		assertEquals("missed", state(150.0, 140.0, false));
		assertEquals("met", state(130.0, 140.0, false));
	}
	
	@Test
	public void targetState_shouldBeNoneWithoutATargetOrAValue() {
		assertEquals("none", state(80.0, null, true));
		assertEquals("none", state(null, 90.0, true));
	}
	
	private static String state(Double value, Double target, boolean higherIsBetter) {
		DashboardIndicator indicator = new DashboardIndicator("i", "I", null, IndicatorMetric.ACTIVE_ENROLLMENTS, "none",
		        target, higherIsBetter, null, null, null, null, null);
		return indicator.getTargetState(value);
	}
	
	@Test
	public void domId_shouldStripAnythingThatIsNotSafeInAnElementId() {
		DashboardIndicator indicator = indicatorWithId("ANC Visits (first)");
		assertEquals("anc-visits--first-", indicator.getDomId());
	}
	
	// ---------------------------------------------------------------- helpers
	
	private static DashboardIndicator indicatorWithId(String id) {
		return new DashboardIndicator(id, "I", null, IndicatorMetric.ACTIVE_ENROLLMENTS, "none", null, true, null, null,
		        null, null, null);
	}
	
	private DashboardIndicator firstIndicator(String indicatorJson) {
		return ProgramIndicatorCatalog
		        .parse("{\"programs\":[{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"P\",\"indicators\":["
		                + indicatorJson + "]}]}",
		            "test")
		        .getPrograms().get(0).getIndicators().get(0);
	}
	
	private void assertRejected(String indicatorJson, String expectedFragment) {
		try {
			firstIndicator(indicatorJson);
			fail("Expected " + indicatorJson + " to be rejected");
		}
		catch (ReportException e) {
			assertTrue("message was: " + e.getMessage(), e.getMessage().contains(expectedFragment));
		}
	}
	
	private File write(String name, String content) throws IOException {
		File file = folder.newFile(name);
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
		return file;
	}
}
