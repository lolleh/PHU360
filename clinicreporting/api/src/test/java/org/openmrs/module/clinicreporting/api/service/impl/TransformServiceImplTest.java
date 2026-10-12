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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dao.TransformDao;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry;
import org.openmrs.module.clinicreporting.api.transform.TransformPreview;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;
import org.openmrs.module.clinicreporting.api.transform.TransformStep;

/**
 * Tests the sandbox service against a stubbed DAO.
 * <p>
 * The service is where the two paths meet, so these tests cover the hand-offs: a refused raw
 * statement must never reach the DAO at all, an accepted one must arrive wrapped and capped, and
 * the column names shown to the administrator must be the ones the database reported rather than
 * placeholders.
 */
public class TransformServiceImplTest {
	
	private TransformServiceImpl service;
	
	private StubDao dao;
	
	@Before
	public void setUp() {
		dao = new StubDao();
		service = new TransformServiceImpl();
		service.setDao(dao);
	}
	
	@Test
	public void preview_shouldHandTheGeneratedStatementAndParametersToTheDao() {
		dao.rows = rows("1", "2");
		
		TransformPreview preview = service.preview(new TransformRequest("obs", Arrays.asList("obs_id"),
		        Collections.singletonList(new TransformStep(TransformStep.Type.RENAME, "obs_id", "Fevers", null)), 10));
		
		assertNotNull(preview);
		assertEquals(TransformPreview.Mode.PIPELINE, preview.getMode());
		assertEquals("SELECT obs_id AS obs_id FROM obs LIMIT 10", dao.lastSql);
		assertTrue(preview.getSql().startsWith("SELECT obs_id AS obs_id FROM obs"));
		assertEquals(Arrays.asList("obs_id"), preview.getColumns());
		// The rename becomes the header the administrator reads, without touching the statement.
		assertEquals(Arrays.asList("Fevers"), preview.getLabels());
		assertEquals(2, preview.getRowCount());
	}
	
	@Test
	public void preview_shouldReportTruncationWhenTheRowCapIsReached() {
		dao.rows = rows("1", "2", "3");
		
		TransformPreview preview = service.preview(new TransformRequest("obs", Arrays.asList("obs_id"), null, 3));
		assertTrue(preview.isTruncated());
	}
	
	@Test
	public void preview_shouldRefuseBeforeItRunsAnything() {
		try {
			service.preview(new TransformRequest("users", Arrays.asList("user_id"), null, 10));
			fail("expected a refusal");
		}
		catch (InvalidReportFilterException e) {
			assertTrue(e.getMessage().contains("not available to the sandbox"));
		}
		assertNull("a refused request must not have reached the database", dao.lastSql);
	}
	
	@Test
	public void preview_shouldReportADatabaseFailureAsAReadableMessage() {
		// A driver message can echo the statement and name tables; the administrator gets the gist and
		// the detail stays in the server log.
		dao.failure = new IllegalStateException("Unknown column 'value_text' in 'field list'");
		
		try {
			service.preview(new TransformRequest("obs", Arrays.asList("value_text"), null, 10));
			fail("expected a refusal");
		}
		catch (InvalidReportFilterException e) {
			assertTrue(e.getMessage().contains("Unknown column"));
			assertTrue(e.getMessage().length() < 300);
		}
	}
	
	@Test
	public void previewRaw_shouldWrapTheStatementSoTheRowCapCannotBeRemoved() {
		dao.rawColumns = Arrays.asList("concept_id", "total");
		dao.rawRows = rows("1", "6");
		
		TransformPreview preview = service
		        .previewRaw("SELECT concept_id, COUNT(*) AS total FROM obs " + "GROUP BY concept_id LIMIT 5", 25);
		
		assertEquals(TransformPreview.Mode.RAW, preview.getMode());
		assertEquals("SELECT * FROM (SELECT concept_id, COUNT(*) AS total FROM obs GROUP BY concept_id LIMIT 5) "
		        + "cr_raw_preview LIMIT 25",
		    dao.lastSql);
		// Column names come from the database, so the header is honest rather than "Column 1".
		assertEquals(Arrays.asList("concept_id", "total"), preview.getColumns());
		assertEquals(Arrays.asList("concept_id", "total"), preview.getLabels());
		assertEquals(2, preview.getRowCount());
	}
	
	@Test
	public void previewRaw_shouldCapTheRowLimitItAsksFor() {
		dao.rawColumns = Arrays.asList("a");
		service.previewRaw("SELECT 1 AS a FROM obs", 99999);
		assertTrue(dao.lastSql.endsWith("cr_raw_preview LIMIT " + TransformRequest.MAX_ROWS));
		
		service.previewRaw("SELECT 1 AS a FROM obs", 0);
		assertTrue(dao.lastSql.endsWith("cr_raw_preview LIMIT 100"));
	}
	
	@Test
	public void previewRaw_shouldRefuseAnythingTheGuardRefusesWithoutRunningIt() {
		String[] hostile = { "DELETE FROM obs", "SELECT 1; DROP TABLE obs", "SELECT * FROM mysql.user",
		        "SELECT * FROM obs FOR UPDATE", "SELECT 1 -- hidden" };
		for (String sql : hostile) {
			dao.lastSql = null;
			try {
				service.previewRaw(sql, 10);
				fail("expected a refusal for: " + sql);
			}
			catch (InvalidReportFilterException e) {
				assertNotNull(e.getMessage());
			}
			assertNull("a refused statement must not have reached the database: " + sql, dao.lastSql);
		}
	}
	
	@Test
	public void previewRaw_shouldTurnADatabaseRejectionIntoAReadableMessage() {
		dao.failure = new IllegalStateException("You have an error in your SQL syntax near 'obs'");
		try {
			service.previewRaw("SELECT nonexistent FROM obs", 10);
			fail("expected a refusal");
		}
		catch (InvalidReportFilterException e) {
			assertTrue(e.getMessage().startsWith("The database rejected that query"));
		}
	}
	
	@Test
	public void sources_shouldNarrowTheAllowlistToWhatThisDatabaseHas() {
		dao.available = new LinkedHashMap<String, Set<String>>();
		dao.available.put("obs", set("obs_id", "concept_id", "obs_datetime"));
		dao.available.put("person", set("person_id", "gender"));
		
		Map<String, ReportSourceRegistry.Source> sources = service.sources();
		
		// Tables the database does not have are dropped entirely rather than offered and then failing.
		assertNull(sources.get("medication_dispense"));
		assertNotNull(sources.get("obs"));
		assertEquals(set("obs_id", "concept_id", "obs_datetime"), sources.get("obs").getColumns());
		assertEquals(set("person_id", "gender"), sources.get("person").getColumns());
	}
	
	@Test
	public void sources_shouldOfferTheAllowlistAsWrittenWhenMetadataCannotBeRead() {
		// The allowlist is the security boundary and metadata is only a convenience, so an unreadable
		// schema must not empty the page: it falls back to the allowlist and lets a wrong column fail as
		// a database error.
		dao.failure = new IllegalStateException("Unable to read database metadata");
		assertEquals(ReportSourceRegistry.all().keySet(), service.sources().keySet());
	}
	
	@Test
	public void preview_shouldRejectANullRequest() {
		try {
			service.preview(null);
			fail("expected a refusal");
		}
		catch (InvalidReportFilterException e) {
			assertTrue(e.getMessage().contains("Nothing to preview"));
		}
	}
	
	// ---------------------------------------------------------------- stub
	
	/**
	 * Records what the service asked for and returns whatever the test wants back.
	 */
	private static final class StubDao implements TransformDao {
		
		private String lastSql;
		
		private Map<String, Object> lastParams;
		
		private List<List<String>> rows = new ArrayList<List<String>>();
		
		private List<String> rawColumns = new ArrayList<String>();
		
		private List<List<String>> rawRows = new ArrayList<List<String>>();
		
		private Map<String, Set<String>> available = new LinkedHashMap<String, Set<String>>();
		
		private RuntimeException failure;
		
		@Override
		public List<List<String>> runReadOnly(String sql, Map<String, Object> params) {
			lastSql = sql;
			lastParams = params;
			return run(failure, rows);
		}
		
		@Override
		public RawResult runRawReadOnly(String sql) {
			lastSql = sql;
			if (failure != null) {
				throw failure;
			}
			return new RawResult(rawColumns, rawRows, rawRows.size() > 0 && rawRows.size() >= limitOf(sql));
		}
		
		@Override
		public List<List<String>> runExportReadOnly(String sql, Map<String, Object> params, int backstop) {
			return runReadOnly(sql, params);
		}
		
		@Override
		public RawResult runExportRawReadOnly(String sql, int backstop) {
			return runRawReadOnly(sql);
		}
		
		@Override
		public Map<String, Set<String>> availableColumns() {
			if (failure != null) {
				throw failure;
			}
			return available;
		}
		
		private static <T> T run(RuntimeException failure, T value) {
			if (failure != null) {
				throw failure;
			}
			return value;
		}
		
		private static int limitOf(String sql) {
			int at = sql.lastIndexOf("LIMIT ");
			return at < 0 ? 100 : Integer.parseInt(sql.substring(at + 6).trim());
		}
	}
	
	private static List<List<String>> rows(String... values) {
		List<List<String>> rows = new ArrayList<List<String>>();
		for (String value : values) {
			rows.add(Arrays.asList(value));
		}
		return rows;
	}
	
	private static Set<String> set(String... values) {
		return new LinkedHashSet<String>(Arrays.asList(values));
	}
}
