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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.openmrs.module.clinicreporting.ClinicReportingConstants;
import org.openmrs.module.clinicreporting.api.model.DataExport;
import org.openmrs.module.clinicreporting.api.model.ExportResult;
import org.openmrs.module.clinicreporting.api.service.DataExportService;
import org.openmrs.module.clinicreporting.api.service.TransformService;
import org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry;
import org.openmrs.module.clinicreporting.api.transform.TransformPreview;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;
import org.springframework.web.servlet.ModelAndView;

/**
 * Tests the data exports page's privilege boundary, its form handling and its CSV download.
 * <p>
 * The running server cannot demonstrate this boundary by clicking through it: on this distribution
 * {@code Get Providers} is granted only to {@code Privilege Level: Full} and
 * {@code Privilege Level: High}, and the login flow calls it, so no account exists that can finish
 * a login and then be refused here. That leaves the check to be exercised against a stub, which is
 * what this does.
 */
public class DataExportsControllerTest {
	
	/** The row limit the page falls back to when the configured one is missing or unusable. */
	private static final Integer DEFAULT_ROWS = Integer.valueOf(DataExport.DEFAULT_ROW_LIMIT);
	
	@Test
	public void get_shouldRefuseAUserWithoutThePagePrivilege() {
		Stub controller = new Stub();
		controller.granted.clear();
		
		ModelAndView model = page(controller);
		
		assertEquals(Boolean.TRUE, model.getModel().get("denied"));
		// The refusal has to come before anything is loaded, or a denied caller still gets to see the
		// shape of the schema.
		assertNull(model.getModel().get("sources"));
		assertNull(model.getModel().get("exports"));
		assertFalse("seeding must not run for a denied caller", controller.seeded);
		assertFalse("the services must not be consulted at all", controller.anythingCalled());
	}
	
	@Test
	public void submission_shouldAlsoBeRefusedWithoutThePrivilege() {
		// A refused GET is not much of a boundary if the POST runs the query anyway.
		Stub controller = new Stub();
		controller.granted.clear();
		
		ModelAndView run = action(controller, "run", "1", null);
		ModelAndView csv = action(controller, "csv", "1", null);
		ModelAndView raw = raw(controller, "preview", null, null, "5", "5", "SELECT 1 AS a FROM obs");
		ModelAndView save = pipeline(controller, "save", "X", "patient", "patient_id", "100", null);
		
		assertEquals(Boolean.TRUE, run.getModel().get("denied"));
		assertEquals(Boolean.TRUE, csv.getModel().get("denied"));
		assertEquals(Boolean.TRUE, raw.getModel().get("denied"));
		assertEquals(Boolean.TRUE, save.getModel().get("denied"));
		assertFalse(controller.anythingCalled());
	}
	
	@Test
	public void get_shouldShowThePageToAUserWithThePagePrivilege() {
		Stub controller = new Stub();
		ModelAndView model = page(controller);
		
		assertNull(model.getModel().get("denied"));
		assertNotNull(model.getModel().get("sources"));
		assertEquals("exports", model.getModel().get("tab"));
		assertTrue("the navigation entry needs its flag", Boolean.TRUE.equals(model.getModel().get("showExportsTab")));
		// A first visit picks columns for the administrator rather than opening on an empty picker.
		assertNotNull(model.getModel().get("selectedFlags"));
		assertFalse(((Map<?, ?>) model.getModel().get("selectedFlags")).isEmpty());
		assertEquals(DEFAULT_ROWS, model.getModel().get("rowLimit"));
		assertTrue("the shipped list has to be offered", controller.seeded);
	}
	
	@Test
	public void page_shouldListTheShippedNamesWhoseSourceIsWithheld() {
		// The Reports page names three exports this workspace cannot seed. They still belong on the
		// list, marked unavailable, so the page carries the whole shipped list rather than three fewer.
		Stub controller = new Stub();
		
		ModelAndView model = page(controller);
		
		List<String> withheld = new ArrayList<String>();
		for (DataExport export : unavailableExports(model)) {
			withheld.add(export.getName());
		}
		assertTrue(withheld.contains("Users Data"));
		assertTrue(withheld.contains("User Roles"));
		assertTrue(withheld.contains("User Logins"));
		// The runnable list is what the page runs and downloads, so the unavailable names stay out of it.
		for (DataExport export : exports(model)) {
			assertFalse(withheld.contains(export.getName()));
		}
	}
	
	@Test
	public void rawPanel_shouldBeHiddenWithoutTheRawPrivilege() {
		Stub sandboxOnly = new Stub();
		sandboxOnly.granted.remove(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE);
		assertEquals(Boolean.FALSE, page(sandboxOnly).getModel().get("rawAllowed"));
		
		Stub both = new Stub();
		assertEquals(Boolean.TRUE, page(both).getModel().get("rawAllowed"));
	}
	
	@Test
	public void rawPreview_shouldBeRefusedWhenOnlyThePagePrivilegeIsHeld() {
		// Holding the page privilege is not enough on its own: the raw path is checked again on every
		// submission, so removing the raw grant removes the ability to run SQL.
		Stub controller = new Stub();
		controller.granted.remove(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE);
		
		ModelAndView model = raw(controller, "preview", null, null, "5", "5", "SELECT 1 AS a FROM obs");
		
		assertNull("nothing may be run", model.getModel().get("preview"));
		assertTrue("the refusal has to be explained",
		    String.valueOf(model.getModel().get("formError")).toLowerCase().contains("raw"));
		assertNull(controller.lastSql);
	}
	
	@Test
	public void rawSave_shouldBeRefusedWhenOnlyThePagePrivilegeIsHeld() {
		// A raw definition must not be storable by someone who could never run it.
		Stub controller = new Stub();
		controller.granted.remove(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE);
		
		ModelAndView model = raw(controller, "save", "Timed out", "A raw export", "5", null, "SELECT 1 AS a FROM obs");
		
		assertNull(controller.lastSaved);
		assertTrue(String.valueOf(model.getModel().get("formError")).toLowerCase().contains("raw"));
	}
	
	@Test
	public void rawPreview_shouldRunWhenBothPrivilegesAreHeld() {
		Stub controller = new Stub();
		
		ModelAndView model = raw(controller, "preview", null, null, "5", "5", "SELECT 1 AS a FROM obs");
		
		assertEquals("SELECT 1 AS a FROM obs", controller.lastSql);
		assertNotNull(model.getModel().get("preview"));
	}
	
	@Test
	public void blankRawSubmission_shouldNotReachTheService() {
		Stub controller = new Stub();
		raw(controller, "preview", null, null, "5", "5", "   ");
		assertNull(controller.lastSql);
	}
	
	@Test
	public void pipelinePreview_shouldRunTheSelectedSourceAndColumns() {
		Stub controller = new Stub();
		
		ModelAndView model = pipeline(controller, "preview", null, "obs", "obs_id,concept_id", "5", "5");
		
		assertEquals("SELECT obs_id AS obs_id, concept_id AS concept_id FROM obs LIMIT 5", controller.lastSql);
		assertNotNull(model.getModel().get("preview"));
	}
	
	@Test
	public void withheldColumn_shouldBeNamedRatherThanSilentlyDropped() {
		// obs.comments exists in the database and is deliberately not offered. Saying so matters: a
		// column that vanishes between the picker and the result reads as a bug.
		Stub controller = new Stub();
		
		ModelAndView model = pipeline(controller, "preview", null, "obs", "comments", "5", "5");
		
		assertEquals(Arrays.asList("comments"), model.getModel().get("withheldColumns"));
		assertNull(model.getModel().get("preview"));
		assertNotNull("an all-withheld selection has to be refused", model.getModel().get("formError"));
		assertNull(controller.lastSql);
	}
	
	@Test
	public void withheldColumn_shouldNotBlockTheColumnsThatAreAllowed() {
		Stub controller = new Stub();
		
		ModelAndView model = pipeline(controller, "preview", null, "obs", "obs_id,comments", "5", "5");
		
		assertEquals(Arrays.asList("comments"), model.getModel().get("withheldColumns"));
		assertEquals("SELECT obs_id AS obs_id FROM obs LIMIT 5", controller.lastSql);
		assertNotNull(model.getModel().get("preview"));
	}
	
	@Test
	public void anUnknownTable_shouldNotSilentlyRedirectTheRequest() {
		// An explicit table that is not on the allowlist keeps the page on the list and never builds a
		// query against it, rather than quietly substituting a different table.
		Stub controller = new Stub();
		
		ModelAndView model = pipeline(controller, null, null, "not_a_table", null, null, null);
		
		assertNull(model.getModel().get("source"));
		assertNotNull(model.getModel().get("exports"));
		assertNull(controller.lastSql);
	}
	
	@Test
	public void noSources_shouldBeReportedAsAConfigurationProblem() {
		Stub controller = new Stub();
		controller.sources = Collections.emptyMap();
		
		ModelAndView model = page(controller);
		assertEquals(Boolean.TRUE, model.getModel().get("noSources"));
		assertNull(model.getModel().get("source"));
	}
	
	@Test
	public void anUnreachableService_shouldBeReportedRatherThanFakedWithTheAllowlist() {
		// Metadata being unreadable is the service's problem and it falls back to the allowlist there.
		// A service that cannot be reached at all is different: reimplementing the allowlist in the page
		// would offer columns the service never vetted, so the page says it has no sources instead.
		Stub controller = new Stub();
		controller.sourcesFailure = true;
		
		ModelAndView model = page(controller);
		assertEquals(Boolean.TRUE, model.getModel().get("noSources"));
		assertNull(model.getModel().get("source"));
		assertNull("nothing may be run against a service that is not there", controller.lastSql);
	}
	
	@Test
	public void rowLimit_shouldBeClampedToTheAllowedRange() {
		Stub controller = new Stub();
		
		assertEquals(Integer.valueOf(TransformRequest.MAX_EXPORT_ROWS),
		    pipeline(controller, "preview", null, "obs", "obs_id", "99999999", null).getModel().get("rowLimit"));
		assertEquals(Integer.valueOf(50),
		    pipeline(controller, "preview", null, "obs", "obs_id", "50", null).getModel().get("rowLimit"));
		// The clamping is a floor as well as a ceiling: nothing below one row can be asked for.
		assertEquals(Integer.valueOf(1),
		    pipeline(controller, "preview", null, "obs", "obs_id", "0", null).getModel().get("rowLimit"));
		assertEquals(Integer.valueOf(1),
		    pipeline(controller, "preview", null, "obs", "obs_id", "-3", null).getModel().get("rowLimit"));
		// A missing or unreadable limit falls back to the page default rather than to one row.
		assertEquals(DEFAULT_ROWS,
		    pipeline(controller, "preview", null, "obs", "obs_id", "lots", null).getModel().get("rowLimit"));
		assertEquals(DEFAULT_ROWS,
		    pipeline(controller, "preview", null, "obs", "obs_id", null, null).getModel().get("rowLimit"));
	}
	
	@Test
	public void save_shouldStoreAValidDefinition() {
		Stub controller = new Stub();
		
		ModelAndView model = pipeline(controller, "save", "Daily registrations", "patient", "patient_id", "100", null);
		
		assertEquals("Daily registrations", controller.lastSaved.getName());
		assertNotNull(model.getModel().get("flash"));
		assertNotNull(model.getModel().get("exports"));
	}
	
	@Test
	public void save_shouldRefuseADefinitionTheServiceRefuses() {
		Stub controller = new Stub();
		
		ModelAndView model = pipeline(controller, "save", "", "patient", "patient_id", "100", null);
		
		assertEquals("nothing may be persisted", 1, controller.byId.size());
		assertNotNull(model.getModel().get("formError"));
		assertEquals("the draft must stay on screen for correction", Boolean.TRUE, model.getModel().get("building"));
	}
	
	@Test
	public void anUnknownStepType_shouldBeReportedRatherThanSilentlyIgnored() {
		Stub controller = new Stub();
		Map<String, String> fields = new LinkedHashMap<String, String>();
		fields.put("stepType1", "__unknown__");
		fields.put("stepColumn1", "patient_id");
		
		ModelAndView model = pipeline(controller, "save", "Broken", "patient", "patient_id", "100", null, request(fields));
		
		assertEquals("nothing may be persisted", 1, controller.byId.size());
		assertTrue(String.valueOf(model.getModel().get("formError")).toLowerCase().contains("recognised"));
		assertNull(controller.lastSql);
	}
	
	@Test
	public void stepRows_shouldBeCappedAtWhatTheFormCanCarry() {
		Stub controller = new Stub();
		Map<String, String> fields = new LinkedHashMap<String, String>();
		for (int i = 1; i <= 30; i++) {
			fields.put("stepType" + i, "UPPER");
			fields.put("stepColumn" + i, "patient_id");
		}
		
		ModelAndView model = pipeline(controller, "preview", null, "patient", "patient_id", "100", "5", request(fields));
		
		List<?> rows = (List<?>) model.getModel().get("stepRows");
		assertNotNull(rows);
		assertTrue("a pasted form must not grow the pipeline past the form's cap", rows.size() <= 6);
		assertEquals(Integer.valueOf(6), model.getModel().get("stepRowsAvailable"));
	}
	
	@Test
	public void stepFromAndToBoxes_shouldBuildARecodeRule() {
		Stub controller = new Stub();
		Map<String, String> fields = new LinkedHashMap<String, String>();
		fields.put("stepType1", "RECODE");
		fields.put("stepColumn1", "gender");
		fields.put("stepFrom1_1", "M");
		fields.put("stepTo1_1", "Male");
		fields.put("stepFrom1_2", "F");
		fields.put("stepTo1_2", "Female");
		
		ModelAndView model = pipeline(controller, "preview", null, "person", "gender", "100", "5", request(fields));
		
		assertNotNull("a complete mapping must preview", model.getModel().get("preview"));
		assertNull("a complete mapping must not raise an error", model.getModel().get("formError"));
		assertTrue("both boxes must reach the statement as a recode", controller.lastSql.contains("CASE gender WHEN"));
	}
	
	@Test
	public void aHalfFilledStepBox_shouldBeReportedRatherThanIgnored() {
		Stub controller = new Stub();
		Map<String, String> fields = new LinkedHashMap<String, String>();
		fields.put("stepType1", "RECODE");
		fields.put("stepColumn1", "gender");
		fields.put("stepFrom1_1", "M");
		fields.put("stepTo1_1", "Male");
		fields.put("stepFrom1_2", "F");
		
		ModelAndView model = pipeline(controller, "preview", null, "person", "gender", "100", "5", request(fields));
		
		assertNull("a half-typed rule must not run", controller.lastSql);
		assertTrue("it has to be refused by name, not dropped",
		    String.valueOf(model.getModel().get("formError")).toLowerCase().contains("both sides"));
	}
	
	@Test
	public void aStepWithContentButNoColumn_shouldBeReported() {
		Stub controller = new Stub();
		Map<String, String> fields = new LinkedHashMap<String, String>();
		fields.put("stepType1", "RECODE");
		fields.put("stepFrom1_1", "M");
		fields.put("stepTo1_1", "Male");
		
		ModelAndView model = pipeline(controller, "preview", null, "person", "gender", "100", "5", request(fields));
		
		assertNull("nothing may run", controller.lastSql);
		assertTrue("the missing column has to be named",
		    String.valueOf(model.getModel().get("formError")).toLowerCase().contains("column"));
	}
	
	@Test
	public void aStepWithContentButNoType_shouldBeReported() {
		Stub controller = new Stub();
		Map<String, String> fields = new LinkedHashMap<String, String>();
		fields.put("stepColumn1", "gender");
		fields.put("stepFrom1_1", "M");
		fields.put("stepTo1_1", "Male");
		
		ModelAndView model = pipeline(controller, "preview", null, "person", "gender", "100", "5", request(fields));
		
		assertNull("nothing may run", controller.lastSql);
		assertTrue("the missing type has to be named",
		    String.valueOf(model.getModel().get("formError")).toLowerCase().contains("recognised"));
	}
	
	@Test
	public void aBlankBucketBound_shouldBecomeTheCatchAll() {
		Stub controller = new Stub();
		Map<String, String> fields = new LinkedHashMap<String, String>();
		fields.put("stepType1", "BUCKET");
		fields.put("stepColumn1", "value_numeric");
		fields.put("stepFrom1_1", "5");
		fields.put("stepTo1_1", "Under five");
		fields.put("stepTo1_2", "Five and over");
		
		ModelAndView model = pipeline(controller, "preview", null, "obs", "value_numeric", "100", "5", request(fields));
		
		assertNotNull("the bands must preview", model.getModel().get("preview"));
		assertTrue("a blank bound is the catch-all band",
		    controller.lastSql.contains("CASE WHEN value_numeric <") && controller.lastSql.contains("ELSE"));
	}
	
	@Test
	public void run_shouldReturnTheBoundedResultAndItsExport() {
		Stub controller = new Stub();
		
		ModelAndView model = action(controller, "run", "1", null);
		
		assertNotNull(model.getModel().get("runResult"));
		assertEquals("Seeded one", ((DataExport) model.getModel().get("runExport")).getName());
		assertNotNull(model.getModel().get("exports"));
	}
	
	@Test
	public void run_shouldRefuseAnUnknownId() {
		Stub controller = new Stub();
		
		ModelAndView model = action(controller, "run", "999", null);
		assertNotNull(model.getModel().get("flashError"));
		assertNull(model.getModel().get("runResult"));
	}
	
	@Test
	public void run_shouldBeRefusedForARawExportWithoutTheRawPrivilege() {
		Stub controller = new Stub();
		controller.rawById.put(2, rawExport(2));
		controller.granted.remove(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE);
		
		ModelAndView model = action(controller, "run", "2", null);
		
		assertNull(model.getModel().get("runResult"));
		assertNull(controller.lastRun);
		assertTrue(String.valueOf(model.getModel().get("flashError")).toLowerCase().contains("raw"));
	}
	
	@Test
	public void csv_shouldServeTheExportAsAFile() {
		Stub controller = new Stub();
		controller.byId.get(1).setRowLimit(Integer.valueOf(5));
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		java.util.concurrent.atomic.AtomicReference<String> contentType = new java.util.concurrent.atomic.AtomicReference<String>();
		java.util.concurrent.atomic.AtomicReference<String> disposition = new java.util.concurrent.atomic.AtomicReference<String>();
		HttpServletResponse response = response(body, contentType, disposition);
		
		ModelAndView model = action(controller, response, "csv", "1", null);
		
		assertNull("a download must not render a page", model);
		assertEquals("the download runs the export's own row limit", Integer.valueOf(5), controller.lastRun);
		assertTrue(contentType.get().startsWith("text/csv"));
		assertTrue(disposition.get().contains(".csv"));
		String csv = new String(body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
		assertTrue("Excel needs the byte-order mark", csv.startsWith("\uFEFF"));
		assertTrue(csv.contains("patient_id"));
	}
	
	@Test
	public void csv_shouldBeRefusedForARawExportWithoutTheRawPrivilege() {
		Stub controller = new Stub();
		controller.rawById.put(2, rawExport(2));
		controller.granted.remove(ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE);
		
		ModelAndView model = action(controller, "csv", "2", null);
		
		assertNotNull("a refusal has to come back as a page", model);
		assertNull(controller.lastRun);
		assertTrue(String.valueOf(model.getModel().get("flashError")).toLowerCase().contains("raw"));
	}
	
	@Test
	public void csv_shouldRefuseAnUnknownId() {
		Stub controller = new Stub();
		
		ModelAndView model = action(controller, "csv", "999", null);
		assertNotNull(model.getModel().get("flashError"));
	}
	
	@Test
	public void csv_shouldNeverRunMoreThanTheExportAsksFor() {
		Stub controller = new Stub();
		controller.byId.get(1).setRowLimit(Integer.valueOf(TransformRequest.MAX_EXPORT_ROWS + 50000));
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		
		action(controller, response(body, new java.util.concurrent.atomic.AtomicReference<String>(),
		    new java.util.concurrent.atomic.AtomicReference<String>()), "csv", "1", null);
		
		assertEquals("the ceiling has to win", Integer.valueOf(TransformRequest.MAX_EXPORT_ROWS), controller.lastRun);
	}
	
	@Test
	public void retiredExport_shouldLeaveTheListWithAReason() {
		Stub controller = new Stub();
		
		ModelAndView model = action(controller, "retire", "1", "expired");
		
		assertEquals(Integer.valueOf(1), controller.lastRetiredId);
		assertEquals("expired", controller.lastRetiredReason);
		assertTrue(String.valueOf(model.getModel().get("flash")).contains("Seeded one"));
	}
	
	@Test
	public void retire_shouldRefuseAnUnknownId() {
		Stub controller = new Stub();
		
		ModelAndView model = action(controller, "retire", "999", null);
		assertNotNull(model.getModel().get("flashError"));
	}
	
	@Test
	public void reactivate_shouldPutARetiredExportBackInTheList() {
		Stub controller = new Stub();
		action(controller, "retire", "1", "expired");
		
		ModelAndView model = action(controller, "reactivate", "1", null);
		
		assertEquals(Integer.valueOf(1), controller.lastReactivatedId);
		assertTrue(String.valueOf(model.getModel().get("flash")).contains("Seeded one"));
		assertEquals("the export has to be back in the list that can be run", 1, exports(model).size());
		assertEquals("and out of the list of retired ones", 0, retiredExports(model).size());
	}
	
	@Test
	public void reactivate_shouldRefuseAnUnknownId() {
		Stub controller = new Stub();
		
		ModelAndView model = action(controller, "reactivate", "999", null);
		assertNotNull(model.getModel().get("flashError"));
	}
	
	@Test
	public void retiredExports_shouldBeOfferedForReactivation() {
		Stub controller = new Stub();
		action(controller, "retire", "1", "expired");
		
		ModelAndView model = page(controller);
		
		assertEquals(1, retiredExports(model).size());
		assertEquals("expired", retiredExports(model).get(0).getRetireReason());
		assertEquals("the retired export must not also appear in the runnable list", 0, exports(model).size());
	}
	
	@Test
	public void editing_shouldRestoreTheStoredValues() {
		Stub controller = new Stub();
		
		ModelAndView model = edit(controller, "1");
		
		assertEquals(Integer.valueOf(1), model.getModel().get("editingId"));
		assertEquals("Seeded one", model.getModel().get("exportName"));
		assertEquals(DEFAULT_ROWS, model.getModel().get("rowLimit"));
		assertEquals("patient", model.getModel().get("table"));
	}
	
	// ---------------------------------------------------------------- call sites
	//
	// The controller handler takes thirteen positional parameters, which invites a transposed limit or
	// rawSql argument. Every test goes through one of these wrappers so the intent stays on the line
	// that reads like a sentence. Signature of the handler: (request, response, action, id, mode,
	// name, description, table, columns, rowLimit, limit, rawSql, reason).
	
	/** A plain first visit: no parameters at all. */
	private static ModelAndView page(Stub controller) {
		return controller.exports(null, null, null, null, null, null, null, null, null, null, null, null, null);
	}
	
	/**
	 * An action against the saved list: run, csv, retire or reactivate (reason is the retire reason).
	 */
	private static ModelAndView action(Stub controller, String action, String id, String reason) {
		return controller.exports(null, null, action, id, null, null, null, null, null, null, null, null, reason);
	}
	
	/** An action against the saved list that writes into the supplied response. */
	private static ModelAndView action(Stub controller, HttpServletResponse response, String action, String id,
	        String reason) {
		return controller.exports(null, response, action, id, null, null, null, null, null, null, null, null, reason);
	}
	
	/** Editing: an id and nothing else, so the stored definition is loaded. */
	private static ModelAndView edit(Stub controller, String id) {
		return controller.exports(null, null, null, id, null, null, null, null, null, null, null, null, null);
	}
	
	/** The runnable half of the list the controller put in the model. */
	@SuppressWarnings("unchecked")
	private static List<DataExport> exports(ModelAndView model) {
		return (List<DataExport>) model.getModel().get("exports");
	}
	
	/** The retired half of the list the controller put in the model. */
	@SuppressWarnings("unchecked")
	private static List<DataExport> retiredExports(ModelAndView model) {
		return (List<DataExport>) model.getModel().get("retiredExports");
	}
	
	/** The shipped names the page lists because their source is withheld rather than absent. */
	@SuppressWarnings("unchecked")
	private static List<DataExport> unavailableExports(ModelAndView model) {
		return (List<DataExport>) model.getModel().get("unavailableExports");
	}
	
	/** A pipeline submission: action, name, table, columns, export row limit, preview limit. */
	private static ModelAndView pipeline(Stub controller, String action, String name, String table, String columns,
	        String rowLimit, String limit) {
		return controller.exports(null, null, action, null, "pipeline", name, null, table, columns, rowLimit, limit, null,
		    null);
	}
	
	/** A pipeline submission carrying repeated form fields for the steps. */
	private static ModelAndView pipeline(Stub controller, String action, String name, String table, String columns,
	        String rowLimit, String limit, HttpServletRequest request) {
		return controller.exports(request, null, action, null, "pipeline", name, null, table, columns, rowLimit, limit, null,
		    null);
	}
	
	/** A raw submission: action, name, description, export row limit, preview limit, statement. */
	private static ModelAndView raw(Stub controller, String action, String name, String description, String rowLimit,
	        String limit, String sql) {
		return controller.exports(null, null, action, null, "raw", name, description, null, null, rowLimit, limit, sql,
		    null);
	}
	
	// ---------------------------------------------------------------- proxies
	//
	// Spring's MockHttpServletRequest and MockHttpServletResponse are the obvious choice and cannot be
	// used here: the test classpath resolves servlet-api 2.4, which predates the AsyncContext that the
	// mocks are compiled against. Proxies returning null for everything else are enough, because the
	// controller reads nothing more. {@code HttpServletResponse.getOutputStream} must answer with a
	// ServletOutputStream, since the call site checks the declared type; it feeds the supplied buffer.
	
	private static HttpServletRequest request(final Map<String, String> fields) {
		return (HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
		    DataExportsControllerTest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class },
		    new java.lang.reflect.InvocationHandler() {
			    
			    @Override
			    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
				    if ("getParameter".equals(method.getName()) && args != null && args.length == 1) {
					    return fields.get(args[0]);
				    }
				    if ("getParameterValues".equals(method.getName()) && args != null && args.length == 1) {
					    String value = fields.get(args[0]);
					    return value == null ? null : new String[] { value };
				    }
				    if ("toString".equals(method.getName())) {
					    return "stub request";
				    }
				    if ("hashCode".equals(method.getName())) {
					    return Integer.valueOf(System.identityHashCode(proxy));
				    }
				    if ("equals".equals(method.getName())) {
					    return Boolean.valueOf(proxy == args[0]);
				    }
				    return null;
			    }
		    });
	}
	
	private static HttpServletResponse response(final ByteArrayOutputStream body,
	        final java.util.concurrent.atomic.AtomicReference<String> contentType,
	        final java.util.concurrent.atomic.AtomicReference<String> disposition) {
		return (HttpServletResponse) java.lang.reflect.Proxy.newProxyInstance(
		    DataExportsControllerTest.class.getClassLoader(), new Class<?>[] { HttpServletResponse.class },
		    new java.lang.reflect.InvocationHandler() {
			    
			    @Override
			    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
				    if ("getOutputStream".equals(method.getName())) {
					    return new ServletOutputStream() {
						    
						    @Override
						    public void write(int b) {
							    body.write(b);
						    }
					    };
				    }
				    if ("setContentType".equals(method.getName())) {
					    contentType.set(String.valueOf(args[0]));
					    return null;
				    }
				    if ("setHeader".equals(method.getName()) && "Content-Disposition".equals(args[0])) {
					    disposition.set(String.valueOf(args[1]));
					    return null;
				    }
				    if ("flushBuffer".equals(method.getName())) {
					    return null;
				    }
				    if ("toString".equals(method.getName())) {
					    return "stub response";
				    }
				    if ("hashCode".equals(method.getName())) {
					    return Integer.valueOf(System.identityHashCode(proxy));
				    }
				    if ("equals".equals(method.getName())) {
					    return Boolean.valueOf(proxy == args[0]);
				    }
				    return null;
			    }
		    });
	}
	
	// ---------------------------------------------------------------- fixtures
	
	private static DataExport seededExport() {
		DataExport export = new DataExport();
		export.setId(Integer.valueOf(1));
		export.setName("Seeded one");
		export.setSourceTable("patient");
		export.setColumns(Arrays.asList("patient_id", "gender"));
		export.setRowLimit(DataExport.DEFAULT_ROW_LIMIT);
		return export;
	}
	
	private static DataExport rawExport(int id) {
		DataExport export = new DataExport();
		export.setId(Integer.valueOf(id));
		export.setName("Raw one");
		export.setRawSql("SELECT 1 AS a FROM patient");
		export.setRowLimit(Integer.valueOf(100));
		return export;
	}
	
	// ---------------------------------------------------------------- stub
	
	/**
	 * Answers the only things the controller asks the outside world: whether a privilege is held, and
	 * what the services do. Overriding them is the point of these tests, since neither can be arranged
	 * against the running server.
	 */
	private static final class Stub extends DataExportsController {
		
		private final Set<String> granted = new LinkedHashSet<String>(Arrays.asList(
		    ClinicReportingConstants.MANAGE_INDICATORS_PRIVILEGE, ClinicReportingConstants.RAW_REPORT_SQL_PRIVILEGE));
		
		private Map<String, ReportSourceRegistry.Source> sources = ReportSourceRegistry.all();
		
		private boolean sourcesFailure;
		
		private boolean seeded;
		
		private String lastSql;
		
		private DataExport lastSaved;
		
		private Integer lastRun;
		
		private Integer lastRetiredId;
		
		private String lastRetiredReason;
		
		private Integer lastReactivatedId;
		
		private final Map<Integer, DataExport> byId = new LinkedHashMap<Integer, DataExport>();
		
		private final Map<Integer, DataExport> retiredById = new LinkedHashMap<Integer, DataExport>();
		
		private final Map<Integer, DataExport> rawById = new LinkedHashMap<Integer, DataExport>();
		
		Stub() {
			DataExport seeded = seededExport();
			byId.put(seeded.getId(), seeded);
		}
		
		boolean anythingCalled() {
			return lastSql != null || lastSaved != null || lastRun != null || seeded;
		}
		
		@Override
		protected boolean hasPrivilege(String privilege) {
			return granted.contains(privilege);
		}
		
		@Override
		protected DataExportService dataExportService() {
			return new DataExportService() {
				
				@Override
				public void ensureDefaults() {
					seeded = true;
				}
				
				@Override
				public List<DataExport> getExports() {
					return new ArrayList<DataExport>(byId.values());
				}
				
				@Override
				public List<DataExport> getRetiredExports() {
					return new ArrayList<DataExport>(retiredById.values());
				}
				
				@Override
				public DataExport getExport(Integer id) {
					if (byId.containsKey(id)) {
						return byId.get(id);
					}
					return rawById.get(id);
				}
				
				@Override
				public String validate(DataExport export) {
					return export.validate();
				}
				
				@Override
				public DataExport save(DataExport export) {
					lastSaved = export;
					String problem = validate(export);
					if (problem != null) {
						throw new IllegalArgumentException(problem);
					}
					export.setId(Integer.valueOf(byId.size() + 1));
					byId.put(export.getId(), export);
					return export;
				}
				
				@Override
				public DataExport retire(Integer id, String reason) {
					lastRetiredId = id;
					lastRetiredReason = reason;
					DataExport export = byId.remove(id);
					if (export == null) {
						throw new IllegalArgumentException("That export is no longer there.");
					}
					export.setRetired(true);
					export.setRetireReason(reason);
					retiredById.put(export.getId(), export);
					return export;
				}
				
				@Override
				public DataExport reactivate(Integer id) {
					lastReactivatedId = id;
					DataExport export = retiredById.remove(id);
					if (export == null) {
						throw new IllegalArgumentException("There is no retired export with id " + id + ".");
					}
					export.setRetired(false);
					export.setRetireReason(null);
					byId.put(export.getId(), export);
					return export;
				}
				
				@Override
				public ExportResult run(DataExport export, int limit) {
					lastRun = Integer.valueOf(limit);
					List<List<String>> rows = new ArrayList<List<String>>();
					rows.add(Arrays.asList("1", "M"));
					return new ExportResult(ExportResult.Mode.TABLE, "SELECT patient_id AS patient_id FROM patient",
					        Arrays.asList("patient_id"), Arrays.asList("patient_id"), rows, false, 3L);
				}
				
				@Override
				public Map<String, ReportSourceRegistry.Source> sources() {
					if (sourcesFailure) {
						throw new IllegalStateException("Unable to read database metadata");
					}
					return sources;
				}
				
				@Override
				public void reconcileReportingMirrors() {
					// no-op in test stub
				}
			};
		}
		
		@Override
		protected TransformService transformService() {
			return new TransformService() {
				
				@Override
				public Map<String, ReportSourceRegistry.Source> sources() {
					return sources;
				}
				
				@Override
				public TransformPreview preview(TransformRequest request) {
					TransformRequest.Built built = request.build();
					lastSql = built.getSql();
					List<List<String>> rows = new ArrayList<List<String>>();
					rows.add(Arrays.asList("1"));
					return new TransformPreview(TransformPreview.Mode.PIPELINE, built.getSql(), built.getOutputColumns(),
					        new ArrayList<String>(built.getLabels().values()), rows, false, 3L);
				}
				
				@Override
				public TransformPreview previewRaw(String sql, int limit) {
					lastSql = sql;
					List<List<String>> rows = new ArrayList<List<String>>();
					rows.add(Arrays.asList("1"));
					return new TransformPreview(TransformPreview.Mode.RAW, sql, Arrays.asList("a"), Arrays.asList("a"), rows,
					        false, 4L);
				}
			};
		}
	}
}
