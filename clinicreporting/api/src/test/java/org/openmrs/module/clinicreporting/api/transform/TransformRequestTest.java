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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests the statement the sandbox builds.
 * <p>
 * Two properties matter more than any individual rule. First, no step configuration may appear in
 * the statement as text: everything a person typed has to come back out as a bound parameter, which
 * is asserted directly rather than inferred from the presence of a quote character. Second, the
 * allowlist has to be enforced here, at the only place that decides what runs, because the
 * controller parses form fields and cannot be trusted to have filtered them.
 */
public class TransformRequestTest {
	
	@Test
	public void build_shouldSelectTheNamedColumnsWithAliasesAndARowCap() {
		TransformRequest.Built built = new TransformRequest("obs", Arrays.asList("obs_id", "concept_id", "obs_datetime"),
		        null, 50).build();
		
		assertEquals(
		    "SELECT obs_id AS obs_id, concept_id AS concept_id, obs_datetime AS obs_datetime " + "FROM obs LIMIT 50",
		    built.getSql());
		assertEquals(Arrays.asList("obs_id", "concept_id", "obs_datetime"), built.getOutputColumns());
		assertTrue(built.getParams().isEmpty());
	}
	
	@Test
	public void build_shouldDefaultAndCapTheRowLimit() {
		assertTrue(sqlFor(limit(0)).endsWith("LIMIT 100"));
		assertTrue(sqlFor(limit(-5)).endsWith("LIMIT 100"));
		assertTrue(sqlFor(limit(99999)).endsWith("LIMIT " + TransformRequest.MAX_ROWS));
	}
	
	@Test
	public void build_shouldRefuseTablesOutsideTheAllowlist() {
		// The message must not confirm whether the table exists, so it names the rule rather than the
		// table being asked for.
		refused(new TransformRequest("users", Arrays.asList("user_id"), null, 10), "not available to the sandbox");
		refused(new TransformRequest("person_name", Arrays.asList("person_name_id"), null, 10),
		    "not available to the sandbox");
		refused(new TransformRequest("obs; DROP TABLE obs", Arrays.asList("obs_id"), null, 10),
		    "not available to the sandbox");
		refused(new TransformRequest(null, Arrays.asList("obs_id"), null, 10), "Choose a source table");
		refused(new TransformRequest("   ", Arrays.asList("obs_id"), null, 10), "Choose a source table");
	}
	
	@Test
	public void build_shouldRefuseColumnsTheSourceDoesNotOffer() {
		// obs has a value_text column but no name; person has gender but no obs_id. Asking for a column
		// the table does not have is a mistake, and reaching for another table's column is a mistake too.
		refused(new TransformRequest("obs", Arrays.asList("name"), null, 10), "is not available from");
		refused(new TransformRequest("person", Arrays.asList("obs_id"), null, 10), "is not available from");
		refused(new TransformRequest("obs", Arrays.<String> asList(), null, 10), "Choose at least one column");
	}
	
	@Test
	public void build_shouldRefuseAnIdentifierThatIsNotPlain() {
		// Even if this reached the builder as a "column", it must not reach the statement.
		refused(new TransformRequest("obs", Arrays.asList("obs_id) ; DROP TABLE obs --"), null, 10), "is not valid");
		refused(new TransformRequest("obs", Arrays.asList("1obs"), null, 10), "is not valid");
		refused(new TransformRequest("obs", Arrays.asList("obs_id`"), null, 10), "is not valid");
	}
	
	@Test
	public void build_shouldRefuseDuplicateColumns() {
		refused(new TransformRequest("obs", Arrays.asList("obs_id", "obs_id"), null, 10), "selected twice");
	}
	
	@Test
	public void build_shouldRefuseMoreColumnsOrStepsThanTheCaps() {
		List<String> tooManyColumns = new ArrayList<String>();
		for (int i = 0; i <= TransformRequest.MAX_COLUMNS; i++) {
			tooManyColumns.add("c" + i);
		}
		refused(new TransformRequest("obs", tooManyColumns, null, 10), "Choose at most");
		
		List<TransformStep> tooManySteps = new ArrayList<TransformStep>();
		for (int i = 0; i <= TransformRequest.MAX_STEPS; i++) {
			tooManySteps.add(new TransformStep(TransformStep.Type.TRIM, "obs_id", null, null));
		}
		refused(new TransformRequest("obs", Arrays.asList("obs_id"), tooManySteps, 10), "Apply at most");
	}
	
	@Test
	public void build_shouldRefuseAStepOnAColumnThatIsNotSelected() {
		// Otherwise the expression is generated and then thrown away, which reads as "the rule did
		// nothing" rather than "the rule cannot apply".
		refused(pipeline("obs", "obs_id", new TransformStep(TransformStep.Type.UPPER, "value_text", null, null)),
		    "not one of the selected");
	}
	
	@Test
	public void build_shouldRefuseAStepWithNoRecognisedType() {
		// The controller keeps such a row so the administrator can see it, which means the builder has to
		// be the thing that reports it.
		refused(pipeline("obs", "obs_id", new TransformStep(null, "obs_id", null, null)), "no recognised type");
	}
	
	@Test
	public void build_shouldApplyStepsInOrderOverTheSameColumn() {
		String sql = pipeline("obs", "value_text", new TransformStep(TransformStep.Type.TRIM, "value_text", null, null),
		    new TransformStep(TransformStep.Type.UPPER, "value_text", null, null),
		    new TransformStep(TransformStep.Type.LOWER, "value_text", null, null)).build().getSql();
		assertEquals("SELECT LOWER(UPPER(TRIM(value_text))) AS value_text FROM obs LIMIT 10", sql);
	}
	
	@Test
	public void build_shouldBindEveryTypedValueRatherThanPastingIt() {
		// The whole point: a value carrying quotes, a semicolon and a comment marker comes back as a
		// parameter and leaves no trace in the statement.
		String hostile = "'; DROP TABLE obs; --";
		TransformRequest.Built built = pipeline("obs", "value_text",
		    new TransformStep(TransformStep.Type.BLANK_TO, "value_text", hostile, null)).build();
		
		assertEquals("SELECT COALESCE(NULLIF(TRIM(value_text), ''), :p0) AS value_text FROM obs LIMIT 10", built.getSql());
		assertEquals(1, built.getParams().size());
		assertEquals(hostile, built.getParams().get("p0"));
		assertFalse(built.getSql().contains(hostile));
	}
	
	@Test
	public void build_shouldRefuseBlankToWithoutAReplacement() {
		refused(pipeline("obs", "value_text", new TransformStep(TransformStep.Type.BLANK_TO, "value_text", "  ", null)),
		    "needs a replacement label");
	}
	
	@Test
	public void build_shouldRecodeWithAnElseSoUnlistedValuesSurvive() {
		TransformRequest.Built built = pipeline("person", "gender",
		    new TransformStep(TransformStep.Type.RECODE, "gender", null, new String[] { "M -> Male", "F -> Female" }))
		        .build();
		
		assertEquals(
		    "SELECT CASE gender WHEN :p0 THEN :p1 WHEN :p2 THEN :p3 ELSE gender END AS gender " + "FROM person LIMIT 10",
		    built.getSql());
		assertEquals("M", built.getParams().get("p0"));
		assertEquals("Male", built.getParams().get("p1"));
		assertEquals("F", built.getParams().get("p2"));
		assertEquals("Female", built.getParams().get("p3"));
	}
	
	@Test
	public void build_shouldRefuseARecodeRuleWithoutBothSides() {
		refused(recode("M Male"), "Expected \"->\"");
		refused(recode("-> Male"), "Both sides");
		refused(recode("M ->"), "Both sides");
		refused(pipeline("person", "gender", new TransformStep(TransformStep.Type.RECODE, "gender", null, new String[0])),
		    "at least one");
	}
	
	@Test
	public void build_shouldBucketIntoBandsAndLabelWhateverIsAboveThem() {
		TransformRequest.Built built = pipeline("obs", "value_numeric", new TransformStep(TransformStep.Type.BUCKET,
		        "value_numeric", null, new String[] { "5|Under five", "15|Five to fourteen", "*|Fifteen and over" }))
		        .build();
		
		assertEquals("SELECT CASE WHEN value_numeric < :p0 THEN :p1 WHEN value_numeric < :p2 THEN :p3 "
		        + "ELSE :p4 END AS value_numeric FROM obs LIMIT 10",
		    built.getSql());
		assertEquals("5", built.getParams().get("p0"));
		assertEquals("Under five", built.getParams().get("p1"));
		assertEquals("Fifteen and over", built.getParams().get("p4"));
	}
	
	@Test
	public void build_shouldLabelValuesAboveTheHighestBandWhenNoCatchAllIsGiven() {
		// Without this the highest values come back null, which reads as missing data rather than "not in
		// any band".
		TransformRequest.Built built = pipeline("obs", "value_numeric",
		    new TransformStep(TransformStep.Type.BUCKET, "value_numeric", null, new String[] { "5|Under five" })).build();
		
		assertTrue(built.getSql().contains("ELSE :p2"));
		assertEquals("Above all bands", built.getParams().get("p2"));
	}
	
	@Test
	public void build_shouldRefuseACatchAllBandThatIsNotLast() {
		refused(pipeline("obs", "value_numeric",
		    new TransformStep(TransformStep.Type.BUCKET, "value_numeric", null, new String[] { "*|Any", "5|Under five" })),
		    "catch-all band must come last");
	}
	
	@Test
	public void build_shouldRefuseANonNumericBucketBound() {
		refused(
		    pipeline("obs", "value_numeric",
		        new TransformStep(TransformStep.Type.BUCKET, "value_numeric", null, new String[] { "five|Under five" })),
		    "is not a number");
	}
	
	@Test
	public void build_shouldRoundWithinAValidatedRangeOfDecimalPlaces() {
		assertEquals("SELECT ROUND(value_numeric, 2) AS value_numeric FROM obs LIMIT 10",
		    pipeline("obs", "value_numeric", new TransformStep(TransformStep.Type.ROUND, "value_numeric", "2", null)).build()
		            .getSql());
		refused(pipeline("obs", "value_numeric", new TransformStep(TransformStep.Type.ROUND, "value_numeric", "7", null)),
		    "between 0 and 6");
		refused(pipeline("obs", "value_numeric", new TransformStep(TransformStep.Type.ROUND, "value_numeric", "-1", null)),
		    "between 0 and 6");
		refused(pipeline("obs", "value_numeric", new TransformStep(TransformStep.Type.ROUND, "value_numeric", "two", null)),
		    "whole number");
	}
	
	@Test
	public void build_shouldTakePartsOfDatesWithStandardExtract() {
		// EXTRACT rather than a vendor YEAR(): the same statement then works on MySQL, PostgreSQL and H2.
		assertEquals("SELECT EXTRACT(YEAR FROM obs_datetime) AS obs_datetime FROM obs LIMIT 10",
		    pipeline("obs", "obs_datetime", new TransformStep(TransformStep.Type.YEAR, "obs_datetime", null, null)).build()
		            .getSql());
		assertEquals("SELECT EXTRACT(MONTH FROM obs_datetime) AS obs_datetime FROM obs LIMIT 10",
		    pipeline("obs", "obs_datetime", new TransformStep(TransformStep.Type.MONTH, "obs_datetime", null, null)).build()
		            .getSql());
	}
	
	@Test
	public void build_shouldRenameTheOutputLabelWithoutPuttingTheLabelInTheStatement() {
		TransformRequest.Built built = pipeline("obs", "value_numeric",
		    new TransformStep(TransformStep.Type.RENAME, "value_numeric", "Fever count; DROP TABLE obs", null)).build();
		
		// The alias stays the column name; the label is applied by the caller. A rename is presentation,
		// and letting it reach the statement would hand the label the same power as the column name.
		assertEquals("SELECT value_numeric AS value_numeric FROM obs LIMIT 10", built.getSql());
		assertEquals(Arrays.asList("value_numeric"), built.getOutputColumns());
		assertEquals("Fever count; DROP TABLE obs", built.getLabels().get("value_numeric"));
	}
	
	@Test
	public void stepsFor_shouldReturnOnlyTheStepsForOneColumnInOrder() {
		TransformRequest request = new TransformRequest("obs", Arrays.asList("obs_id", "value_text"),
		        Arrays.asList(new TransformStep(TransformStep.Type.TRIM, "value_text", null, null),
		            new TransformStep(TransformStep.Type.UPPER, "obs_id", null, null),
		            new TransformStep(TransformStep.Type.UPPER, "value_text", null, null)),
		        10);
		
		List<TransformStep> forValueText = request.stepsFor("value_text");
		assertEquals(2, forValueText.size());
		assertEquals(TransformStep.Type.TRIM, forValueText.get(0).getType());
		assertEquals(TransformStep.Type.UPPER, forValueText.get(1).getType());
		assertEquals(1, request.stepsFor("obs_id").size());
		assertTrue(request.stepsFor("absent").isEmpty());
	}
	
	// ---------------------------------------------------------------- exports and filters
	
	@Test
	public void forExport_shouldAllowManyMoreRowsThanAPreview() {
		// An export is written to a file rather than glanced at, so it may ask for a real result set,
		// still far short of dumping the table.
		TransformRequest export = TransformRequest.forExport("obs", Arrays.asList("obs_id"), null,
		    Collections.<TransformFilter> emptyList(), 999999);
		assertEquals(TransformRequest.MAX_EXPORT_ROWS, export.effectiveLimit());
		assertTrue(Math.abs(TransformRequest.MAX_EXPORT_ROWS - 500) > 4000);
	}
	
	@Test
	public void build_shouldPutABooleanFilterInTheWhereClauseAsABoundParameter() {
		TransformRequest.Built built = filtered("obs", "obs_id",
		    new TransformFilter("voided", TransformFilter.Operator.EQ, "false")).build();
		
		assertEquals("SELECT obs_id AS obs_id FROM obs WHERE voided = :p0 LIMIT 10", built.getSql());
		assertEquals(Boolean.FALSE, built.getParams().get("p0"));
	}
	
	@Test
	public void build_shouldBindFilterValuesAsNumbersDatesAndText() {
		TransformRequest.Built built = filtered("obs", "obs_id",
		    new TransformFilter("value_numeric", TransformFilter.Operator.GT, "1.5")).build();
		assertEquals(new java.math.BigDecimal("1.5"), built.getParams().get("p0"));
		
		built = filtered("encounter", "encounter_id",
		    new TransformFilter("encounter_datetime", TransformFilter.Operator.GE, "2024-01-01")).build();
		assertEquals(java.sql.Date.valueOf("2024-01-01"), built.getParams().get("p0"));
		
		built = filtered("encounter", "encounter_id",
		    new TransformFilter("encounter_type", TransformFilter.Operator.EQ, "2")).build();
		assertEquals(Long.valueOf(2), built.getParams().get("p0"));
		
		built = filtered("location", "name", new TransformFilter("name", TransformFilter.Operator.EQ, "KGH")).build();
		assertEquals("KGH", built.getParams().get("p0"));
	}
	
	@Test
	public void build_shouldExpandAnInFilterToOneBoundParameterPerMember() {
		TransformRequest.Built built = filtered("obs", "obs_id",
		    new TransformFilter("status", TransformFilter.Operator.IN, "FINAL, AMENDED,  PRELIMINARY")).build();
		
		assertEquals("SELECT obs_id AS obs_id FROM obs WHERE status IN (:p0, :p1, :p2) LIMIT 10", built.getSql());
		assertEquals("FINAL", built.getParams().get("p0"));
		assertEquals("AMENDED", built.getParams().get("p1"));
		assertEquals("PRELIMINARY", built.getParams().get("p2"));
	}
	
	@Test
	public void build_shouldMatchContainsIgnoringCaseWithABoundPattern() {
		// Case-insensitive on both sides so "test" and "Test" behave the same no matter which database
		// is running the export.
		TransformRequest.Built built = filtered("obs", "obs_id",
		    new TransformFilter("value_text", TransformFilter.Operator.CONTAINS, "test")).build();
		assertEquals("SELECT obs_id AS obs_id FROM obs WHERE LOWER(value_text) LIKE LOWER(:p0) LIMIT 10", built.getSql());
		assertEquals("%test%", built.getParams().get("p0"));
		
		built = filtered("obs", "obs_id", new TransformFilter("value_text", TransformFilter.Operator.NOT_CONTAINS, "Test"))
		        .build();
		assertEquals("SELECT obs_id AS obs_id FROM obs WHERE LOWER(value_text) NOT LIKE LOWER(:p0) LIMIT 10",
		    built.getSql());
		assertEquals("%Test%", built.getParams().get("p0"));
	}
	
	@Test
	public void build_shouldRenderIsNullAndIsNotNullWithoutAValue() {
		TransformRequest.Built built = filtered("visit", "visit_id",
		    new TransformFilter("date_stopped", TransformFilter.Operator.IS_NULL, null)).build();
		assertEquals("SELECT visit_id AS visit_id FROM visit WHERE date_stopped IS NULL LIMIT 10", built.getSql());
		assertTrue(built.getParams().isEmpty());
		
		built = filtered("visit", "visit_id",
		    new TransformFilter("date_stopped", TransformFilter.Operator.IS_NOT_NULL, null)).build();
		assertEquals("SELECT visit_id AS visit_id FROM visit WHERE date_stopped IS NOT NULL LIMIT 10", built.getSql());
	}
	
	@Test
	public void build_shouldRefuseAFilterThatIsNotAllowlistedPlainOrValued() {
		refusedFilter(new TransformFilter("name", TransformFilter.Operator.EQ, "anything"), "not available from");
		refusedFilter(new TransformFilter("obs_id); DROP TABLE obs --", TransformFilter.Operator.EQ, "1"), "is not valid");
		refusedFilter(new TransformFilter("voided", null, "false"), "no recognised operator");
		refusedFilter(new TransformFilter("voided", TransformFilter.Operator.EQ, "   "), "needs a value");
		refusedFilter(new TransformFilter("voided", TransformFilter.Operator.IN, " , , "), "at least one value");
		
		StringBuilder tooLong = new StringBuilder();
		for (int i = 0; i <= TransformFilter.MAX_VALUE_LENGTH; i++) {
			tooLong.append('a');
		}
		refusedFilter(new TransformFilter("voided", TransformFilter.Operator.EQ, tooLong.toString()),
		    "at most " + TransformFilter.MAX_VALUE_LENGTH);
	}
	
	@Test
	public void build_shouldRefuseMoreFiltersThanTheCap() {
		List<TransformFilter> tooMany = new ArrayList<TransformFilter>();
		for (int i = 0; i <= TransformRequest.MAX_FILTERS; i++) {
			tooMany.add(new TransformFilter("voided", TransformFilter.Operator.EQ, "false"));
		}
		try {
			TransformRequest.forExport("obs", Arrays.asList("obs_id"), null, tooMany, 10).build();
			fail("expected a refusal for too many filters");
		}
		catch (TransformValidationException e) {
			assertTrue(e.getMessage().contains("Apply at most"));
		}
	}
	
	@Test
	public void build_shouldKeepACheckOnAnUnselectedColumnInTheWhereClause() {
		// A filter is not a column to show - it narrows which rows are shown. A date range on
		// date_created is worth having even when the export does not want the timestamp in every row.
		TransformRequest.Built built = filtered("obs", "obs_id",
		    new TransformFilter("date_created", TransformFilter.Operator.GE, "2024-01-01")).build();
		assertTrue(built.getSql().contains(" WHERE date_created >= :p0"));
		assertEquals(java.sql.Date.valueOf("2024-01-01"), built.getParams().get("p0"));
	}
	
	// ---------------------------------------------------------------- helpers
	
	private static TransformRequest filtered(String table, String column, TransformFilter filter) {
		return TransformRequest.forExport(table, Arrays.asList(column), null, Arrays.asList(filter), 10);
	}
	
	private static void refusedFilter(TransformFilter filter, String expectedFragment) {
		try {
			TransformRequest.Built built = filtered("obs", "obs_id", filter).build();
			fail("expected a refusal but built: " + built.getSql());
		}
		catch (TransformValidationException e) {
			if (expectedFragment != null) {
				assertTrue("message was: " + e.getMessage(), e.getMessage().contains(expectedFragment));
			}
		}
	}
	
	// ---------------------------------------------------------------- helpers
	
	private static TransformRequest limit(int value) {
		return new TransformRequest("obs", Arrays.asList("obs_id"), null, value);
	}
	
	private static String sqlFor(TransformRequest request) {
		return request.build().getSql();
	}
	
	private static TransformRequest pipeline(String table, String column, TransformStep... steps) {
		return new TransformRequest(table, Arrays.asList(column), Arrays.asList(steps), 10);
	}
	
	private static TransformRequest recode(String... rules) {
		return pipeline("person", "gender", new TransformStep(TransformStep.Type.RECODE, "gender", null, rules));
	}
	
	private static void refused(TransformRequest request, String expectedFragment) {
		try {
			TransformRequest.Built built = request.build();
			fail("expected a refusal but built: " + built.getSql());
		}
		catch (TransformValidationException e) {
			if (expectedFragment != null) {
				assertTrue("message was: " + e.getMessage(), e.getMessage().contains(expectedFragment));
			}
		}
	}
}
