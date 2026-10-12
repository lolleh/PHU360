/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests that the reporting mirror renders bound parameters as SQL literals and never lets a value a
 * person typed become syntax.
 */
public class DataExportReportingSqlTest {
	
	@Test
	public void literalize_shouldWriteNumbersBareAndTextQuoted() {
		Map<String, Object> params = new LinkedHashMap<String, Object>();
		params.put("p0", Long.valueOf(15));
		params.put("p1", "KGH");
		
		String sql = DataExportReportingSql.literalize(
		    "SELECT location_id AS location_id FROM location WHERE location_id = :p0 AND name <> :p1 LIMIT 10", params);
		
		assertEquals("SELECT location_id AS location_id FROM location WHERE location_id = 15 AND name <> 'KGH' LIMIT 10",
		    sql);
		assertFalse("no bind must survive once literals are inlined", sql.contains(":p"));
	}
	
	@Test
	public void literalize_shouldKeepBooleansAndDatesReadable() {
		Map<String, Object> params = new LinkedHashMap<String, Object>();
		params.put("p0", Boolean.FALSE);
		params.put("p1", Date.valueOf("2024-01-15"));
		params.put("p2", true);
		
		String sql = DataExportReportingSql.literalize(
		    "SELECT obs_id AS obs_id FROM obs WHERE voided = :p0 AND date_created >= :p1 AND :p2 = TRUE LIMIT 1", params);
		
		assertEquals(
		    "SELECT obs_id AS obs_id FROM obs WHERE voided = FALSE AND date_created >= '2024-01-15' AND TRUE = TRUE LIMIT 1",
		    sql);
	}
	
	@Test
	public void literalize_shouldKeepAQuoteInsideTextAsData() {
		Map<String, Object> params = new LinkedHashMap<String, Object>();
		params.put("p0", "O'Brien's");
		
		String sql = DataExportReportingSql.literalize("SELECT person_id AS person_id FROM person WHERE family_name = :p0",
		    params);
		
		assertEquals("SELECT person_id AS person_id FROM person WHERE family_name = 'O''Brien''s'", sql);
	}
	
	@Test
	public void literalize_shouldPrintABigDecimalAsItWasCompared() {
		Map<String, Object> params = new LinkedHashMap<String, Object>();
		params.put("p0", new BigDecimal("72.50"));
		
		String sql = DataExportReportingSql
		        .literalize("SELECT value_numeric AS value_numeric FROM obs WHERE value_numeric > :p0 LIMIT 5", params);
		
		assertEquals("SELECT value_numeric AS value_numeric FROM obs WHERE value_numeric > 72.50 LIMIT 5", sql);
	}
	
	@Test
	public void literalize_shouldRefuseABindNobodyProvided() {
		Map<String, Object> params = new LinkedHashMap<String, Object>();
		params.put("p0", "only this one");
		
		try {
			DataExportReportingSql.literalize("SELECT person_id AS person_id FROM person WHERE family_name = :p1", params);
			fail("a statement that names an unbound parameter must be refused");
		}
		catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("p1"));
		}
	}
	
	@Test
	public void literalize_shouldLeaveAStatementWithoutBindsAlone() {
		String sql = DataExportReportingSql.literalize("SELECT 1 AS one LIMIT 1", new LinkedHashMap<String, Object>());
		assertEquals("SELECT 1 AS one LIMIT 1", sql);
		
		assertEquals("", DataExportReportingSql.literalize(null, new LinkedHashMap<String, Object>()));
		assertEquals("", DataExportReportingSql.literalize("   ", null));
	}
}
