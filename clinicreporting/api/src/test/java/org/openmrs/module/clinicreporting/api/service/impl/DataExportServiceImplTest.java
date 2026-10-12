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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.clinicreporting.api.dao.DataExportDao;
import org.openmrs.module.clinicreporting.api.dao.TransformDao;
import org.openmrs.module.clinicreporting.api.exception.InvalidReportFilterException;
import org.openmrs.module.clinicreporting.api.model.DataExport;
import org.openmrs.module.clinicreporting.api.model.ExportResult;

/**
 * Tests the data-export service against a stubbed DAO pair.
 * <p>
 * The service is the hand-off between a stored definition and the two sandbox paths, so these tests
 * cover: that every shipped definition really is stored on first use, that a run re-validates the
 * definition it was handed (a definition loaded from a row today must obey the same rules as one
 * typed into the form), that neither path lets a refused or broken statement reach the database,
 * and that the retired exports are kept out of the runnable list until they are reactivated.
 */
public class DataExportServiceImplTest {
	
	private DataExportServiceImpl transformService;
	
	private StubExportDao exportDao;
	
	private StubTransformDao transformDao;
	
	@Before
	public void setUp() {
		exportDao = new StubExportDao();
		transformDao = new StubTransformDao();
		transformService = new DataExportServiceImpl();
		transformService.setDao(exportDao);
		transformService.setTransformDao(transformDao);
	}
	
	@Test
	public void save_shouldValidateBeforeItStoresAnything() {
		DataExport export = new DataExport();
		export.setName("Users");
		export.setSourceTable("users");
		export.setColumns(Arrays.asList("user_id"));
		
		try {
			transformService.save(export);
			fail("expected a refusal");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("not available"));
		}
		assertEquals(0, exportDao.inserts);
		assertNull(exportDao.getByName("Users"));
	}
	
	@Test
	public void save_shouldRefuseADuplicateName() {
		DataExport existing = validExport("Vitals Data");
		existing.setId(Integer.valueOf(7));
		exportDao.byName.put(existing.getName(), existing);
		
		try {
			transformService.save(validExport("Vitals Data"));
			fail("expected a refusal");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("already exists"));
		}
		assertEquals(0, exportDao.inserts);
	}
	
	@Test
	public void save_shouldStoreAValidDefinitionAndReturnTheStoredRow() {
		DataExport export = validExport("Allergies Data");
		exportDao.returned = export;
		
		DataExport stored = transformService.save(export);
		
		assertEquals(1, exportDao.inserts);
		assertNotNull(stored.getUuid());
		assertTrue(stored.getDateCreated() != null);
		assertFalse(stored.isRetired());
	}
	
	@Test
	public void retire_shouldMarkTheRowAndLeaveItInPlace() {
		DataExport export = validExport("Vitals Data");
		export.setId(Integer.valueOf(3));
		exportDao.byId.put(export.getId(), export);
		
		DataExport retired = transformService.retire(Integer.valueOf(3), "no longer used");
		
		assertTrue(retired.isRetired());
		assertEquals("no longer used", retired.getRetireReason());
		assertNotNull(retired.getDateRetired());
	}
	
	@Test
	public void retire_shouldRefuseAnUnknownId() {
		try {
			transformService.retire(Integer.valueOf(99), "why not");
			fail("expected a refusal");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("id 99"));
		}
	}
	
	@Test
	public void getExports_shouldLeaveTheRetiredOnesOut() {
		exportDao.stored.add(saved("Vitals Data", false));
		exportDao.stored.add(saved("Old Vitals", true));
		
		List<DataExport> exports = transformService.getExports();
		
		assertEquals(1, exports.size());
		assertEquals("Vitals Data", exports.get(0).getName());
	}
	
	@Test
	public void getRetiredExports_shouldReturnOnlyTheRetiredOnes() {
		exportDao.stored.add(saved("Vitals Data", false));
		exportDao.stored.add(saved("Old Vitals", true));
		
		List<DataExport> retired = transformService.getRetiredExports();
		
		assertEquals(1, retired.size());
		assertEquals("Old Vitals", retired.get(0).getName());
	}
	
	@Test
	public void reactivate_shouldClearTheRetirementAndLeaveTheRowInPlace() {
		DataExport export = saved("Vitals Data", true);
		export.setId(Integer.valueOf(3));
		exportDao.byId.put(export.getId(), export);
		
		DataExport restored = transformService.reactivate(Integer.valueOf(3));
		
		assertFalse(restored.isRetired());
		assertNull(restored.getDateRetired());
		assertNull(restored.getRetiredBy());
		assertNull(restored.getRetireReason());
		assertNotNull("a reactivation has to be recorded as a change", restored.getDateChanged());
		assertEquals("the row must not be a new one", Integer.valueOf(3), restored.getId());
	}
	
	@Test
	public void reactivate_shouldDoNothingWhenTheExportIsAlreadyActive() {
		DataExport export = saved("Vitals Data", false);
		export.setId(Integer.valueOf(3));
		exportDao.byId.put(export.getId(), export);
		
		DataExport unchanged = transformService.reactivate(Integer.valueOf(3));
		
		assertNull("an active export must not be touched", unchanged.getDateChanged());
		assertFalse(unchanged.isRetired());
	}
	
	@Test
	public void reactivate_shouldRefuseAnUnknownId() {
		try {
			transformService.reactivate(Integer.valueOf(99));
			fail("expected a refusal");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("id 99"));
		}
	}
	
	@Test
	public void run_shouldBuildTheTablePathAndPassTheBoundedStatementToTheDao() {
		DataExport export = validExport("Vitals Data");
		exportDao.byId.put(export.getId(), export);
		
		transformDao.rows = rows("1", "2");
		
		ExportResult result = transformService.run(export, 5000);
		
		assertEquals(ExportResult.Mode.TABLE, result.getMode());
		assertTrue(result.getSql().startsWith("SELECT obs_id AS obs_id FROM obs WHERE voided"));
		assertEquals(Arrays.asList("obs_id"), result.getColumns());
		assertEquals(2, result.getRowCount());
		assertNotNull(result.getLabels());
	}
	
	@Test
	public void run_shouldRevalidateTheDefinitionItIsGiven() {
		DataExport export = validExport("Vitals Data");
		export.setColumns(Arrays.asList("name"));
		
		try {
			transformService.run(export, 500);
			fail("expected a refusal");
		}
		catch (InvalidReportFilterException e) {
			assertTrue(e.getMessage().contains("not available"));
		}
		assertNull("a refused export must not have reached the database", transformDao.lastSql);
	}
	
	@Test
	public void runRaw_shouldWrapTheStatementSoTheRowCapHoldsAndAuditIt() {
		DataExport export = validExport("Manual");
		export.setRawSql("SELECT obs_id FROM obs LIMIT 5");
		
		transformDao.rawColumns = Arrays.asList("obs_id");
		transformDao.rawRows = rows("9");
		
		ExportResult result = transformService.run(export, 100);
		
		assertEquals(ExportResult.Mode.RAW, result.getMode());
		assertTrue(transformDao.lastSql.endsWith("cr_raw_preview LIMIT 100"));
		assertEquals(Arrays.asList("obs_id"), result.getLabels());
		assertEquals(1, result.getRowCount());
	}
	
	@Test
	public void runRaw_shouldRefuseAnythingTheGuardRefusesWithoutRunningIt() {
		DataExport export = validExport("Manual");
		export.setRawSql("SELECT * FROM mysql.user");
		
		try {
			transformService.run(export, 100);
			fail("expected a refusal");
		}
		catch (InvalidReportFilterException e) {
			assertNotNull(e.getMessage());
		}
		assertNull("a refused statement must not have reached the database", transformDao.lastSql);
	}
	
	@Test
	public void run_shouldCapTheRowsItAsksTheDatabaseFor() {
		DataExport export = validExport("Vitals Data");
		transformDao.rows = rows("1");
		
		ExportResult result = transformService.run(export, -1);
		// The statement keeps the export's own LIMIT and the executor is given the runtime ceiling as the
		// materialisation backstop, so neither the SQL nor the JDBC layer can be told to dump the table.
		assertTrue(result.getSql().contains("LIMIT 5000"));
		assertEquals(100, transformDao.lastBackstop);
	}
	
	@Test
	public void ensureDefaults_shouldSeedEveryOfferableShippedDefinitionOnce() {
		transformService.ensureDefaults();
		
		assertEquals(23, exportDao.inserts);
		// The three withheld names must never be stored just because they are shipped names.
		assertNull(exportDao.byName.get("Users Data"));
		assertNull(exportDao.byName.get("User Roles"));
		assertNull(exportDao.byName.get("User Logins"));
		// Every shipped name that IS stored was validated before it was stored.
		for (DataExport seeded : exportDao.inserted) {
			if (seeded.getName().equals("Users Data") || seeded.getName().equals("User Roles")
			        || seeded.getName().equals("User Logins")) {
				fail("a withheld name was seeded: " + seeded.getName());
			}
		}
	}
	
	@Test
	public void ensureDefaults_shouldBeIdempotent() {
		transformService.ensureDefaults();
		int afterFirstLoad = exportDao.inserts;
		transformService.ensureDefaults();
		assertEquals(afterFirstLoad, exportDao.inserts);
	}
	
	@Test
	public void sources_shouldNarrowTheAllowlistAndOfferTheFallbackOnMetadataFailure() {
		exportDao = new StubExportDao();
		transformDao.available = new LinkedHashMap<String, java.util.Set<String>>();
		transformDao.available.put("obs", new java.util.LinkedHashSet<String>(Arrays.asList("obs_id", "voided")));
		transformService.setDao(exportDao);
		
		Map<String, org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry.Source> sources = transformService
		        .sources();
		// obs survives narrowed; a table the database lacks disappears entirely.
		assertNotNull(sources.get("obs"));
		assertTrue(sources.get("obs").getColumns().contains("obs_id"));
		
		transformDao.available = null;
		assertEquals(org.openmrs.module.clinicreporting.api.transform.ReportSourceRegistry.all().keySet(),
		    transformService.sources().keySet());
	}
	
	// ---------------------------------------------------------------- helpers
	
	private static DataExport validExport(String name) {
		DataExport export = new DataExport();
		export.setName(name);
		export.setSourceTable("obs");
		export.setColumns(Arrays.asList("obs_id"));
		export.setFilters(Arrays.asList(new org.openmrs.module.clinicreporting.api.transform.TransformFilter("voided",
		        org.openmrs.module.clinicreporting.api.transform.TransformFilter.Operator.EQ, "false")));
		return export;
	}
	
	/** A row as it would be read back: an export that is retired carries a reason and a date. */
	private static DataExport saved(String name, boolean retired) {
		DataExport export = validExport(name);
		export.setUuid(java.util.UUID.randomUUID().toString());
		export.setRetired(retired);
		if (retired) {
			export.setDateRetired(new java.util.Date());
			export.setRetiredBy(Integer.valueOf(1));
			export.setRetireReason("expired");
		}
		return export;
	}
	
	private static List<List<String>> rows(String... values) {
		List<List<String>> result = new ArrayList<List<String>>();
		for (String value : values) {
			result.add(Arrays.asList(value));
		}
		return result;
	}
	
	// ---------------------------------------------------------------- stubs
	
	private static final class StubExportDao implements DataExportDao {
		
		private final List<DataExport> stored = new ArrayList<DataExport>();
		
		private final List<DataExport> inserted = new ArrayList<DataExport>();
		
		private final Map<String, DataExport> byName = new LinkedHashMap<String, DataExport>();
		
		private final Map<Integer, DataExport> byId = new LinkedHashMap<Integer, DataExport>();
		
		private int inserts;
		
		private DataExport returned;
		
		@Override
		public List<DataExport> getAll(boolean includeRetired) {
			List<DataExport> result = new ArrayList<DataExport>();
			for (DataExport export : stored) {
				if (includeRetired || !export.isRetired()) {
					result.add(export);
				}
			}
			return result;
		}
		
		@Override
		public DataExport getById(Integer id) {
			return byId.get(id);
		}
		
		@Override
		public DataExport getByUuid(String uuid) {
			for (DataExport export : stored) {
				if (export.getUuid().equals(uuid)) {
					return export;
				}
			}
			return null;
		}
		
		@Override
		public DataExport getByName(String name) {
			return byName.get(name);
		}
		
		@Override
		public long count(boolean includeRetired) {
			return stored.size();
		}
		
		@Override
		public int insert(DataExport export) {
			inserts++;
			DataExport copy = returned != null ? returned : export;
			stored.add(copy);
			inserted.add(copy);
			byName.put(copy.getName(), copy);
			byId.put(Integer.valueOf(inserts), copy);
			return inserts;
		}
		
		@Override
		public void update(DataExport export) {
			byId.put(export.getId(), export);
			byName.put(export.getName(), export);
		}
	}
	
	private static final class StubTransformDao implements TransformDao {
		
		private String lastSql;
		
		private int lastBackstop = -1;
		
		private List<List<String>> rows = new ArrayList<List<String>>();
		
		private List<String> rawColumns = new ArrayList<String>();
		
		private List<List<String>> rawRows = new ArrayList<List<String>>();
		
		private Map<String, java.util.Set<String>> available = new LinkedHashMap<String, java.util.Set<String>>();
		
		@Override
		public List<List<String>> runReadOnly(String sql, Map<String, Object> params) {
			lastSql = sql;
			return rows;
		}
		
		@Override
		public TransformDao.RawResult runRawReadOnly(String sql) {
			lastSql = sql;
			return new TransformDao.RawResult(rawColumns, rawRows, false);
		}
		
		@Override
		public List<List<String>> runExportReadOnly(String sql, Map<String, Object> params, int backstop) {
			lastSql = sql;
			lastBackstop = backstop;
			return rows;
		}
		
		@Override
		public TransformDao.RawResult runExportRawReadOnly(String sql, int backstop) {
			lastSql = sql;
			lastBackstop = backstop;
			return new TransformDao.RawResult(rawColumns, rawRows, false);
		}
		
		@Override
		public Map<String, java.util.Set<String>> availableColumns() {
			if (available == null) {
				throw new IllegalStateException("Unable to read database metadata");
			}
			return available;
		}
	}
}
