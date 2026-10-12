/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;
import org.openmrs.module.clinicreporting.api.transform.TransformFilter;
import org.openmrs.module.clinicreporting.api.transform.TransformFilter.Operator;
import org.openmrs.module.clinicreporting.api.transform.TransformRequest;
import org.openmrs.module.clinicreporting.api.transform.TransformStep;

/**
 * Tests the saved-export model: that a definition survives a storage round-trip unchanged, that
 * validation runs the same builder the live form runs, and that the raw and table paths are
 * mutually exclusive descriptions of a query rather than two things a definition can blur.
 */
public class DataExportTest {
	
	@Test
	public void definitionJson_shouldRoundTripTheWholeQueryShape() {
		DataExport export = new DataExport();
		export.setColumns(Arrays.asList("obs_id", "value_numeric"));
		export.setSteps(
		    Arrays.asList(new TransformStep(TransformStep.Type.BLANK_TO, "value_numeric", "0", null), new TransformStep(
		            TransformStep.Type.RENAME, "value_numeric", "Reading; a hostile label", new String[] { "M -> Male" })));
		export.setFilters(Arrays.asList(new TransformFilter("voided", Operator.EQ, "false"),
		    new TransformFilter("value_numeric", Operator.GE, "1.5")));
		
		String json = export.definitionJson();
		
		DataExport read = new DataExport();
		read.applyDefinition(json);
		assertEquals(export.getColumns(), read.getColumns());
		assertEquals(2, read.getSteps().size());
		assertEquals(TransformStep.Type.BLANK_TO, read.getSteps().get(0).getType());
		assertEquals("0", read.getSteps().get(0).getArgument());
		assertEquals("Reading; a hostile label", read.getSteps().get(1).getArgument());
		assertEquals(Arrays.asList("M -> Male"), Arrays.asList(read.getSteps().get(1).getPairs()));
		assertEquals(2, read.getFilters().size());
		assertEquals("false", read.getFilters().get(0).getValue());
		assertEquals(Operator.EQ, read.getFilters().get(0).getOperator());
		assertEquals(Operator.GE, read.getFilters().get(1).getOperator());
		assertFalse(read.isRaw());
	}
	
	@Test
	public void definitionJson_shouldRoundTripARawStatement() {
		DataExport export = new DataExport();
		export.setRawSql("SELECT 1");
		
		DataExport read = new DataExport();
		read.applyDefinition(export.definitionJson());
		assertTrue(read.isRaw());
		assertEquals("SELECT 1", read.getRawSql());
	}
	
	@Test
	public void applyDefinition_shouldRefuseAGarbageDocument() {
		DataExport export = new DataExport();
		try {
			export.applyDefinition("{not json");
			fail("expected a refusal");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("could not be read"));
		}
		try {
			export.applyDefinition("[\"an\",\"array\"]");
			fail("expected a refusal");
		}
		catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("not an object"));
		}
	}
	
	@Test
	public void applyDefinition_shouldTolerateAnAbsentFiltersFieldFromAnOlderShape() {
		DataExport read = new DataExport();
		read.applyDefinition("{\"rawSql\":\"\",\"columns\":[\"obs_id\"],\"steps\":[]}");
		assertEquals(Arrays.asList("obs_id"), read.getColumns());
		assertTrue(read.getFilters().isEmpty());
	}
	
	@Test
	public void validate_shouldAcceptAValidTableDefinition() {
		DataExport export = new DataExport();
		export.setName("Vitals Data");
		export.setSourceTable("obs");
		export.setColumns(Arrays.asList("obs_id", "value_numeric"));
		export.setFilters(Arrays.asList(new TransformFilter("voided", Operator.EQ, "false")));
		
		assertNull(export.validate());
	}
	
	@Test
	public void validate_shouldRefuseANameALimitOrAnEmptyShape() {
		DataExport noName = new DataExport();
		noName.setName("   ");
		noName.setSourceTable("obs");
		noName.setColumns(Arrays.asList("obs_id"));
		assertTrue(noName.validate().contains("name"));
		
		DataExport nowhere = new DataExport();
		nowhere.setName("Nowhere");
		nowhere.setColumns(Arrays.asList("obs_id"));
		assertTrue(nowhere.validate().contains("source table"));
		
		DataExport overRows = new DataExport();
		overRows.setName("Too big");
		overRows.setSourceTable("obs");
		overRows.setColumns(Arrays.asList("obs_id"));
		overRows.setRowLimit(Integer.valueOf(TransformRequest.MAX_EXPORT_ROWS + 1));
		assertTrue(overRows.validate().contains("at most"));
		
		DataExport noColumns = new DataExport();
		noColumns.setName("Nothing");
		noColumns.setSourceTable("obs");
		assertTrue(noColumns.validate().contains("at least one column"));
	}
	
	@Test
	public void validate_shouldRunTheSameAllowlistTheLiveFormRuns() {
		DataExport export = new DataExport();
		export.setName("Users");
		export.setSourceTable("users");
		export.setColumns(Arrays.asList("user_id"));
		String message = export.validate();
		assertTrue("was: " + message, message != null && message.contains("not available"));
	}
	
	@Test
	public void validate_shouldRefuseARawStatementThatTheGuardRefuses() {
		DataExport export = new DataExport();
		export.setName("Danger");
		export.setRawSql("DELETE FROM obs");
		String message = export.validate();
		assertTrue("was: " + message, message != null && message.contains("A query must start with SELECT"));
		
		DataExport fine = new DataExport();
		fine.setName("Safe");
		fine.setRawSql("SELECT obs_id FROM obs LIMIT 5");
		assertNull(fine.validate());
	}
}
