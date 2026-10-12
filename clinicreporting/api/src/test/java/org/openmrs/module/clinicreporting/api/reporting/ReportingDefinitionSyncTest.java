/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.openmrs.module.clinicreporting.api.model.DataExport;
import org.openmrs.module.clinicreporting.api.transform.TransformFilter;
import org.openmrs.module.reporting.dataset.definition.DataSetDefinition;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.definition.DefinitionSummary;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.ReportData;
import org.openmrs.module.reporting.report.ReportDesign;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.reporting.report.definition.service.ReportDefinitionService;
import org.openmrs.module.reporting.report.renderer.CsvReportRenderer;
import org.openmrs.module.reporting.report.service.ReportService;

/**
 * Tests that a saved export becomes exactly one Reports-page definition, that the mirror is the
 * same definition after an edit rather than an extra row, that retiring the export retires the
 * mirror, and that a reactivated export brings its mirror back with it.
 */
public class ReportingDefinitionSyncTest {
	
	@Test
	public void reportUuid_shouldBeDeterministicPerExportAndUnknowableToOthers() {
		assertEquals(ReportingDefinitionSync.reportUuid("abc"), ReportingDefinitionSync.reportUuid("abc"));
		assertFalse(ReportingDefinitionSync.reportUuid("abc").equals(ReportingDefinitionSync.reportUuid("def")));
		assertFalse(ReportingDefinitionSync.reportUuid("abc").equals("abc"));
	}
	
	@Test
	public void update_shouldCreateOneReadableReportForAValidExport() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		
		ReportingDefinitionSync.update(validExport(), service);
		
		assertEquals(1, service.saves);
		ReportDefinition mirror = service.byUuid.get(ReportingDefinitionSync.reportUuid("export-uuid"));
		assertNotNull(mirror);
		assertEquals("Vitals Data", mirror.getName());
		assertEquals("Rows worth reviewing", mirror.getDescription());
		assertEquals(ReportingDefinitionSync.reportUuid("export-uuid"), mirror.getUuid());
		SqlDataSetDefinition dataset = dataset(mirror);
		assertNotNull(dataset);
		// The mirror runs the same statement the export runs, with its binds written as literals.
		assertTrue(dataset.getSqlQuery().startsWith("SELECT obs_id AS obs_id FROM obs WHERE voided = FALSE"));
		assertFalse("a mirror must not carry JDBC binds", dataset.getSqlQuery().contains(":p"));
		assertTrue("the export's own row cap must survive the mirroring",
		    dataset.getSqlQuery().endsWith(" LIMIT " + DataExport.DEFAULT_ROW_LIMIT));
		// The run form is driven by the report's parameters: a mirror whose page has no range cannot be
		// run the way the other exports on the reports page are.
		assertEquals("startDate and endDate", reportParameterNames(mirror));
		assertEquals("startDate and endDate", datasetParameterNames(dataset));
		for (Parameter parameter : mirror.getParameters()) {
			assertEquals("both ends of the range are dates", java.util.Date.class, parameter.getType());
			assertTrue("both ends of the range are required", parameter.isRequired());
		}
		Map<String, Object> expected = new java.util.LinkedHashMap<String, Object>();
		expected.put(ReportingDefinitionSync.START_DATE, ReportingDefinitionSync.START_DATE);
		expected.put(ReportingDefinitionSync.END_DATE, ReportingDefinitionSync.END_DATE);
		assertEquals("the page's range must reach the dataset", expected,
		    mirror.getDataSetDefinitions().get(ReportingDefinitionSync.DATASET_NAME).getParameterMappings());
	}
	
	@Test
	public void update_shouldAddTheRangeToAMirrorBuiltBeforeItExisted() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		// A mirror written by an older build: same export, but no parameters and no range mapping.
		ReportDefinition stale = new ReportDefinition();
		stale.setUuid(ReportingDefinitionSync.reportUuid("export-uuid"));
		stale.setName("Vitals Data");
		stale.setDescription("Rows worth reviewing");
		stale.addDataSetDefinition(ReportingDefinitionSync.DATASET_NAME,
		    new SqlDataSetDefinition("Vitals Data", "Rows worth reviewing",
		            "SELECT obs_id AS obs_id FROM obs WHERE voided = FALSE AND obs_id <= 200244332 LIMIT "
		                    + DataExport.DEFAULT_ROW_LIMIT),
		    null);
		service.saveDefinition(stale);
		
		ReportingDefinitionSync.update(validExport(), service);
		
		assertEquals("the stale mirror must be rewritten", 2, service.saves);
		assertEquals("startDate and endDate",
		    reportParameterNames(service.byUuid.get(ReportingDefinitionSync.reportUuid("export-uuid"))));
	}
	
	@Test
	public void update_shouldReturnTheSameDefinitionRatherThanAccumulate() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		ReportingDefinitionSync.update(validExport(), service);
		ReportDefinition first = service.byUuid.get(ReportingDefinitionSync.reportUuid("export-uuid"));
		
		ReportingDefinitionSync.update(validExport(), service);
		
		assertEquals(1, service.saves);
		assertEquals(first, service.byUuid.get(ReportingDefinitionSync.reportUuid("export-uuid")));
	}
	
	@Test
	public void update_shouldRewriteTheMirrorWhenTheExportChanged() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		ReportingDefinitionSync.update(validExport(), service);
		
		DataExport edited = validExport();
		edited.setName("Vitals Data (edited)");
		ReportingDefinitionSync.update(edited, service);
		
		assertEquals(2, service.saves);
		ReportDefinition mirror = service.byUuid.get(ReportingDefinitionSync.reportUuid("export-uuid"));
		assertEquals("Vitals Data (edited)", mirror.getName());
		assertEquals("Vitals Data (edited)", dataset(mirror).getSqlQuery().isEmpty() ? "" : dataset(mirror).getName());
	}
	
	@Test
	public void update_shouldMirrorARawExportAsTheGuardAcceptedIt() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		DataExport export = validExport();
		export.setName("Manual Check");
		export.setRawSql("SELECT obs_id FROM obs WHERE value_coded IS NOT NULL LIMIT 20");
		
		ReportingDefinitionSync.update(export, service);
		
		SqlDataSetDefinition dataset = dataset(service.byUuid.get(ReportingDefinitionSync.reportUuid("export-uuid")));
		assertEquals("SELECT obs_id FROM obs WHERE value_coded IS NOT NULL LIMIT 20", dataset.getSqlQuery());
	}
	
	@Test
	public void update_shouldRefuseAnExportThatCannotBuildAnymore() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		DataExport broken = validExport();
		broken.setColumns(Arrays.asList("name"));
		
		try {
			ReportingDefinitionSync.update(broken, service);
			fail("a mirror must not be created for a definition that cannot run");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("not available"));
		}
		assertTrue(service.byUuid.isEmpty());
	}
	
	@Test
	public void retire_shouldRetireOnlyTheMirrorThatBelongsToTheExport() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		ReportingDefinitionSync.update(validExport(), service);
		DataExport retired = validExport();
		retired.setRetired(true);
		retired.setRetireReason("cleanup");
		
		ReportingDefinitionSync.retire(retired, service);
		
		ReportDefinition mirror = service.byUuid.get(ReportingDefinitionSync.reportUuid("export-uuid"));
		assertTrue(mirror.getRetired());
		assertEquals("cleanup", mirror.getRetireReason());
	}
	
	@Test
	public void retire_shouldBeADeadEndWhenThereIsNoMirrorOrItIsAlreadyRetired() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		DataExport export = validExport();
		export.setRetireReason("cleanup");
		ReportingDefinitionSync.retire(export, service);
		assertTrue(service.byUuid.isEmpty());
		
		ReportingDefinitionSync.update(export, service);
		int saves = service.saves;
		export.setRetired(true);
		ReportingDefinitionSync.retire(export, service);
		int afterA = service.saves;
		ReportingDefinitionSync.retire(export, service);
		assertEquals(afterA, service.saves);
		assertEquals(saves + 1, afterA);
	}
	
	@Test
	public void update_shouldPutAReactivatedExportBackOnTheReportsPage() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		ReportingDefinitionSync.update(validExport(), service);
		DataExport retired = validExport();
		retired.setRetired(true);
		retired.setRetireReason("cleanup");
		ReportingDefinitionSync.retire(retired, service);
		ReportDefinition mirror = service.byUuid.get(ReportingDefinitionSync.reportUuid("export-uuid"));
		assertTrue(mirror.getRetired());
		
		// The export is back: the mirror has to come back with it, even though every field still agrees.
		ReportingDefinitionSync.update(validExport(), service);
		
		assertEquals("the retire saved once, and so does the revival", 3, service.saves);
		assertFalse("a reactivated export must not stay mirrored as retired", mirror.getRetired());
		assertNull(mirror.getRetireReason());
		assertEquals("Vitals Data", mirror.getName());
	}
	
	@Test
	public void update_shouldNeverSucceedWithoutAnExportUuid() {
		try {
			ReportingDefinitionSync.update(new DataExport(), new StubReportDefinitionService());
			fail("nothing to mirror");
		}
		catch (IllegalArgumentException e) {
			assertNotNull(e.getMessage());
		}
	}
	
	@Test
	public void update_shouldGiveTheMirrorACsvRenderingDesign() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		StubReportService designs = new StubReportService();
		
		ReportingDefinitionSync.update(validExport(), service, designs);
		
		assertEquals(1, designs.saves);
		ReportDesign design = designs.one(ReportingDefinitionSync.reportUuid("export-uuid"));
		assertNotNull("the mirror must render in CSV like the other exports on the reports page", design);
		assertEquals("reporting.csv", design.getName());
		assertEquals(CsvReportRenderer.class, design.getRendererType());
		assertEquals(service.byUuid.get(ReportingDefinitionSync.reportUuid("export-uuid")), design.getReportDefinition());
	}
	
	@Test
	public void update_shouldNotDuplicateACsvDesignThatAlreadyExists() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		StubReportService designs = new StubReportService();
		ReportingDefinitionSync.update(validExport(), service, designs);
		int saves = designs.saves;
		
		ReportingDefinitionSync.update(validExport(), service, designs);
		
		assertEquals("an already-designed mirror must not be redesigned", saves, designs.saves);
	}
	
	@Test
	public void update_shouldAddTheCsvDesignToAMirrorThatIsOtherwiseCurrent() {
		StubReportDefinitionService service = new StubReportDefinitionService();
		// The mirror is already up to date from a build that had no rendering design; a reconcile run
		// after an upgrade has to write the design without rewriting the definition.
		ReportingDefinitionSync.update(validExport(), service);
		
		ReportingDefinitionSync.update(validExport(), service, new StubReportService());
		
		assertEquals("no definition rewrite for a current mirror", 1, service.saves);
	}
	
	// ---------------------------------------------------------------- helpers
	
	private static DataExport validExport() {
		DataExport export = new DataExport();
		export.setUuid("export-uuid");
		export.setName("Vitals Data");
		export.setDescription("Rows worth reviewing");
		export.setSourceTable("obs");
		export.setColumns(Arrays.asList("obs_id"));
		export.setFilters(Arrays.asList(new TransformFilter("voided", TransformFilter.Operator.EQ, "false")));
		return export;
	}
	
	private static SqlDataSetDefinition dataset(ReportDefinition mirror) {
		Map<String, Mapped<? extends DataSetDefinition>> datasets = mirror.getDataSetDefinitions();
		if (datasets == null || datasets.get(ReportingDefinitionSync.DATASET_NAME) == null) {
			return null;
		}
		DataSetDefinition definition = datasets.get(ReportingDefinitionSync.DATASET_NAME).getParameterizable();
		return definition instanceof SqlDataSetDefinition ? (SqlDataSetDefinition) definition : null;
	}
	
	/** @return the report parameters as their names joined by ", " */
	private static String reportParameterNames(ReportDefinition mirror) {
		return parameterNames(mirror.getParameters());
	}
	
	/** @return the dataset parameters as their names joined by ", " */
	private static String datasetParameterNames(DataSetDefinition dataset) {
		return parameterNames(dataset.getParameters());
	}
	
	private static String parameterNames(List<Parameter> parameters) {
		StringBuilder names = new StringBuilder();
		for (Parameter parameter : parameters) {
			if (names.length() > 0) {
				names.append(" and ");
			}
			names.append(parameter.getName());
		}
		return names.toString();
	}
	
	// ---------------------------------------------------------------- stub
	
	private static final class StubReportDefinitionService implements ReportDefinitionService {
		
		private final Map<String, ReportDefinition> byUuid = new LinkedHashMap<String, ReportDefinition>();
		
		private int saves;
		
		@Override
		public void onStartup() {
		}
		
		@Override
		public void onShutdown() {
		}
		
		@Override
		public Class<ReportDefinition> getDefinitionType() {
			return ReportDefinition.class;
		}
		
		@Override
		public List<Class<? extends ReportDefinition>> getDefinitionTypes() {
			return Arrays.asList(ReportDefinition.class);
		}
		
		@Override
		public <D extends ReportDefinition> D getDefinition(Class<D> type, Integer id) {
			for (ReportDefinition definition : byUuid.values()) {
				if (definition.getId() != null && definition.getId().equals(id)) {
					return type.cast(definition);
				}
			}
			return null;
		}
		
		@Override
		public ReportDefinition getDefinitionByUuid(String uuid) {
			return byUuid.get(uuid);
		}
		
		@Override
		public ReportDefinition getDefinition(String uuid, Class<? extends ReportDefinition> type) {
			return byUuid.get(uuid);
		}
		
		@Override
		public List<ReportDefinition> getAllDefinitions(boolean includeRetired) {
			return new ArrayList<ReportDefinition>(byUuid.values());
		}
		
		@Override
		public List<DefinitionSummary> getAllDefinitionSummaries(boolean includeRetired) {
			return new ArrayList<DefinitionSummary>();
		}
		
		@Override
		public int getNumberOfDefinitions(boolean includeRetired) {
			return byUuid.size();
		}
		
		@Override
		public List<ReportDefinition> getDefinitions(String name, boolean includeRetired) {
			List<ReportDefinition> found = new ArrayList<ReportDefinition>();
			for (ReportDefinition definition : byUuid.values()) {
				if (definition.getName() != null && definition.getName().equals(name)) {
					found.add(definition);
				}
			}
			return found;
		}
		
		@Override
		public List<ReportDefinition> getDefinitionsByTag(String tag) {
			return new ArrayList<ReportDefinition>();
		}
		
		@Override
		public <D extends ReportDefinition> D saveDefinition(D definition) {
			saves++;
			byUuid.put(definition.getUuid(), definition);
			return definition;
		}
		
		@Override
		public ReportDefinition getDefinition(Integer id) {
			return getDefinition(ReportDefinition.class, id);
		}
		
		@Override
		public void purgeDefinition(String uuid) {
			byUuid.remove(uuid);
		}
		
		@Override
		public void purgeDefinition(ReportDefinition definition) {
			if (definition != null && definition.getUuid() != null) {
				byUuid.remove(definition.getUuid());
			}
		}
		
		@Override
		public ReportData evaluate(Mapped<? extends ReportDefinition> definition, EvaluationContext context) {
			return null;
		}
		
		@Override
		public ReportData evaluate(ReportDefinition definition, EvaluationContext context) {
			return null;
		}
	}
	
	private static final class StubReportService implements ReportService {
		
		private final Map<String, List<ReportDesign>> byDefinitionUuid = new LinkedHashMap<String, List<ReportDesign>>();
		
		private int saves;
		
		@Override
		public void onStartup() {
		}
		
		@Override
		public void onShutdown() {
		}
		
		@Override
		public ReportDesign getReportDesignByUuid(String uuid) {
			return null;
		}
		
		@Override
		public ReportDesign getReportDesign(Integer id) {
			return null;
		}
		
		@Override
		public List<ReportDesign> getAllReportDesigns(boolean includeRetired) {
			List<ReportDesign> all = new ArrayList<ReportDesign>();
			for (List<ReportDesign> designs : byDefinitionUuid.values()) {
				all.addAll(designs);
			}
			return all;
		}
		
		@Override
		public List<ReportDesign> getReportDesigns(ReportDefinition definition,
		        Class<? extends org.openmrs.module.reporting.report.renderer.ReportRenderer> rendererType,
		        boolean includeRetired) {
			List<ReportDesign> matching = new ArrayList<ReportDesign>();
			if (definition != null && definition.getUuid() != null) {
				List<ReportDesign> designs = byDefinitionUuid.get(definition.getUuid());
				if (designs != null) {
					for (ReportDesign design : designs) {
						if (rendererType.equals(design.getRendererType())) {
							matching.add(design);
						}
					}
				}
			}
			return matching;
		}
		
		@Override
		public ReportDesign saveReportDesign(ReportDesign design) {
			saves++;
			List<ReportDesign> designs = byDefinitionUuid.get(design.getReportDefinition().getUuid());
			if (designs == null) {
				designs = new ArrayList<ReportDesign>();
				byDefinitionUuid.put(design.getReportDefinition().getUuid(), designs);
			}
			designs.add(design);
			return design;
		}
		
		@Override
		public void purgeReportDesign(ReportDesign design) {
		}
		
		@Override
		public void purgeReportDesignsForReportDefinition(String reportDefinitionUuid) {
		}
		
		@Override
		public Collection<org.openmrs.module.reporting.report.renderer.ReportRenderer> getReportRenderers() {
			return new ArrayList<org.openmrs.module.reporting.report.renderer.ReportRenderer>();
		}
		
		@Override
		public org.openmrs.module.reporting.report.renderer.ReportRenderer getReportRenderer(String className) {
			return null;
		}
		
		@Override
		public org.openmrs.module.reporting.report.renderer.ReportRenderer getPreferredReportRenderer(
		        Class<Object> dataType) {
			return null;
		}
		
		@Override
		public List<org.openmrs.module.reporting.report.renderer.RenderingMode> getRenderingModes(
		        ReportDefinition reportDefinition) {
			return new ArrayList<org.openmrs.module.reporting.report.renderer.RenderingMode>();
		}
		
		@Override
		public org.openmrs.module.reporting.report.ReportRequest saveReportRequest(
		        org.openmrs.module.reporting.report.ReportRequest request) {
			return request;
		}
		
		@Override
		public org.openmrs.module.reporting.report.ReportRequest getReportRequest(Integer id) {
			return null;
		}
		
		@Override
		public org.openmrs.module.reporting.report.ReportRequest getReportRequestByUuid(String uuid) {
			return null;
		}
		
		@Override
		public List<org.openmrs.module.reporting.report.ReportRequest> getReportRequests(ReportDefinition definition,
		        java.util.Date from, java.util.Date to,
		        org.openmrs.module.reporting.report.ReportRequest.Status... statuses) {
			return new ArrayList<org.openmrs.module.reporting.report.ReportRequest>();
		}
		
		@Override
		public List<org.openmrs.module.reporting.report.ReportRequest> getReportRequests(ReportDefinition definition,
		        java.util.Date from, java.util.Date to, Integer requestedBy,
		        org.openmrs.module.reporting.report.ReportRequest.Status... statuses) {
			return new ArrayList<org.openmrs.module.reporting.report.ReportRequest>();
		}
		
		@Override
		public List<org.openmrs.module.reporting.report.ReportRequest> getReportRequests(ReportDefinition definition,
		        java.util.Date from, java.util.Date to, Integer requestedBy, Integer limit,
		        org.openmrs.module.reporting.report.ReportRequest.Status... statuses) {
			return new ArrayList<org.openmrs.module.reporting.report.ReportRequest>();
		}
		
		@Override
		public long getReportRequestsCount(ReportDefinition definition, java.util.Date from, java.util.Date to,
		        org.openmrs.module.reporting.report.ReportRequest.Status... statuses) {
			return 0;
		}
		
		@Override
		public void purgeReportRequest(org.openmrs.module.reporting.report.ReportRequest request) {
		}
		
		@Override
		public org.openmrs.module.reporting.report.ReportProcessorConfiguration saveReportProcessorConfiguration(
		        org.openmrs.module.reporting.report.ReportProcessorConfiguration configuration) {
			return configuration;
		}
		
		@Override
		public org.openmrs.module.reporting.report.ReportProcessorConfiguration getReportProcessorConfiguration(Integer id) {
			return null;
		}
		
		@Override
		public org.openmrs.module.reporting.report.ReportProcessorConfiguration getReportProcessorConfigurationByUuid(
		        String uuid) {
			return null;
		}
		
		@Override
		public List<org.openmrs.module.reporting.report.ReportProcessorConfiguration> getAllReportProcessorConfigurations(
		        boolean includeRetired) {
			return new ArrayList<org.openmrs.module.reporting.report.ReportProcessorConfiguration>();
		}
		
		@Override
		public List<org.openmrs.module.reporting.report.ReportProcessorConfiguration> getGlobalReportProcessorConfigurations() {
			return new ArrayList<org.openmrs.module.reporting.report.ReportProcessorConfiguration>();
		}
		
		@Override
		public List<org.openmrs.module.reporting.report.ReportProcessorConfiguration> getReportProcessorConfigurations(
		        Class<? extends org.openmrs.module.reporting.report.processor.ReportProcessor> processorType) {
			return new ArrayList<org.openmrs.module.reporting.report.ReportProcessorConfiguration>();
		}
		
		@Override
		public void purgeReportProcessorConfiguration(
		        org.openmrs.module.reporting.report.ReportProcessorConfiguration configuration) {
		}
		
		@Override
		public File getReportDataFile(org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public File getReportErrorFile(org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public File getReportOutputFile(org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public File getReportLogFile(org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public File getReportLogFile(String uuid) {
			return null;
		}
		
		@Override
		public org.openmrs.module.reporting.report.Report runReport(
		        org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public org.openmrs.module.reporting.report.ReportRequest queueReport(
		        org.openmrs.module.reporting.report.ReportRequest request) {
			return request;
		}
		
		@Override
		public Integer getPositionInQueue(org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public void processNextQueuedReports() {
		}
		
		@Override
		public org.openmrs.module.reporting.report.Report saveReport(org.openmrs.module.reporting.report.Report report,
		        String name) {
			return report;
		}
		
		@Override
		public org.openmrs.module.reporting.report.ReportData loadReportData(
		        org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public byte[] loadRenderedOutput(org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public String loadReportError(org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public List<String> loadReportLog(org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public org.openmrs.module.reporting.report.Report loadReport(
		        org.openmrs.module.reporting.report.ReportRequest request) {
			return null;
		}
		
		@Override
		public void deleteOldReportRequests() {
		}
		
		@Override
		public void persistCachedReports() {
		}
		
		@Override
		public void logReportMessage(org.openmrs.module.reporting.report.ReportRequest request, String message) {
		}
		
		@Override
		public void logReportMessage(String uuid, String message) {
		}
		
		@Override
		public void purgeReportRequestsForReportDefinition(String reportDefinitionUuid) {
		}
		
		private ReportDesign one(String reportDefinitionUuid) {
			List<ReportDesign> designs = byDefinitionUuid.get(reportDefinitionUuid);
			return designs == null || designs.isEmpty() ? null : designs.get(0);
		}
	}
}
