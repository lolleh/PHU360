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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the policy applied to analyst-written SQL.
 * <p>
 * A keyword scanner is only as good as its false negatives and its false positives, and both are
 * tested here: every way of smuggling a second statement or a system schema has to be refused, and
 * an ordinary query whose column happens to be called {@code updates} or {@code create_date} has to
 * survive.
 */
public class RawQueryGuardTest {
	
	@Test
	public void reject_shouldAcceptAPlainSelect() {
		assertNull(RawQueryGuard.reject("SELECT concept_id, COUNT(*) FROM obs GROUP BY concept_id"));
		assertNull(RawQueryGuard.reject("  select 1  "));
	}
	
	@Test
	public void reject_shouldAcceptACommonTableExpression() {
		assertNull(RawQueryGuard.reject("WITH counts AS (SELECT concept_id, COUNT(*) c FROM obs GROUP BY concept_id) "
		        + "SELECT * FROM counts ORDER BY c DESC"));
	}
	
	@Test
	public void reject_shouldRefuseNothingAtAll() {
		assertEquals("Enter a SELECT statement.", RawQueryGuard.reject(null));
		assertEquals("Enter a SELECT statement.", RawQueryGuard.reject(""));
		assertEquals("Enter a SELECT statement.", RawQueryGuard.reject("   \n  "));
	}
	
	@Test
	public void reject_shouldRefuseAStatementThatDoesNotStartAsAQuery() {
		// Starting as a query is what stops a WITH clause from carrying a leading data-modifying clause.
		assertRefused("UPDATE obs SET value_text = 'x'");
		assertRefused("DELETE FROM obs");
		assertRefused("INSERT INTO obs (obs_id) VALUES (1)");
		assertRefused("WITH x AS (DELETE FROM obs RETURNING 1) SELECT * FROM x");
		assertRefused("(SELECT 1) UNION (SELECT 2)");
	}
	
	@Test
	public void reject_shouldRefuseEveryDataChangingKeyword() {
		assertRefused("DROP TABLE obs");
		assertRefused("ALTER TABLE obs ADD COLUMN x INT");
		assertRefused("TRUNCATE TABLE obs");
		assertRefused("REPLACE INTO obs VALUES (1)");
		assertRefused("MERGE INTO obs USING obs ON 1=1 WHEN MATCHED THEN UPDATE SET x=1");
	}
	
	@Test
	public void reject_shouldRefuseSessionAndTransactionControl() {
		// These are how a read-only query becomes a way to change how the connection behaves.
		assertRefused("SELECT 1; SET GLOBAL max_execution_time = 0");
		assertRefused("SELECT LOAD_FILE('/etc/passwd')");
		assertRefused("SELECT 1 FROM obs WHERE 1 = SLEEP(10)");
		assertRefused("SELECT BENCHMARK(100000000, MD5('a'))");
		assertRefused("SELECT * FROM obs INTO OUTFILE '/tmp/x'");
	}
	
	@Test
	public void reject_shouldRefuseASecondStatement() {
		assertRefused("SELECT 1; DROP TABLE obs");
		assertRefused("SELECT 1;;");
	}
	
	@Test
	public void reject_shouldTolerateASingleTrailingSemicolon() {
		// Writers add one out of habit, and refusing it teaches them to paste something stranger.
		assertNull(RawQueryGuard.reject("SELECT concept_id FROM obs;"));
		assertNull(RawQueryGuard.reject("SELECT concept_id FROM obs ; "));
	}
	
	@Test
	public void reject_shouldRefuseCommentsBecauseTheyHideTheSecondStatement() {
		assertRefused("SELECT 1 -- and then drop table obs");
		assertRefused("SELECT 1 /* hidden */");
		assertRefused("SELECT 1 # hidden");
	}
	
	@Test
	public void reject_shouldRefuseLockingReads() {
		// A locking read holds a row lock for the life of the request, which is a way to stop a clinic
		// writing rather than to read.
		assertRefused("SELECT * FROM obs FOR UPDATE");
		assertRefused("SELECT * FROM obs LOCK IN SHARE MODE");
	}
	
	@Test
	public void reject_shouldRefuseSystemSchemas() {
		// Reading privilege tables or a stored password is the reason this gate exists at all.
		assertRefused("SELECT * FROM information_schema.tables");
		assertRefused("SELECT user, password FROM mysql.user");
		assertRefused("SELECT * FROM performance_schema.accounts");
		assertRefused("SELECT * FROM sys.host_summary");
	}
	
	@Test
	public void reject_shouldNotBeFooledByWordsThatMerelyStartWithAKeyword() {
		// A blunt substring scan would refuse these, and a sandbox that refuses ordinary queries gets
		// abandoned or worked around.
		assertNull(RawQueryGuard.reject("SELECT updates, created, setting FROM visits"));
		assertNull(RawQueryGuard.reject("SELECT date_created, retired_by, drop_date FROM encounters"));
		assertNull(RawQueryGuard.reject("SELECT COUNT(*) AS committed_orders FROM orders"));
		assertNull(RawQueryGuard.reject("SELECT o.order_id FROM orders o WHERE o.voided = 0"));
	}
	
	@Test
	public void reject_shouldRefuseAnOverlongStatement() {
		StringBuilder padding = new StringBuilder("SELECT concept_id FROM obs WHERE concept_id IN (");
		for (int i = 0; i < RawQueryGuard.MAX_LENGTH; i++) {
			padding.append('1');
		}
		padding.append(')');
		assertRefused(padding.toString());
	}
	
	@Test
	public void wrap_shouldForceTheRowCapSoAnAnalystCannotRemoveIt() {
		assertEquals("SELECT * FROM (SELECT concept_id FROM obs LIMIT 5) cr_raw_preview LIMIT 100",
		    RawQueryGuard.wrap("SELECT concept_id FROM obs LIMIT 5", 100));
	}
	
	@Test
	public void wrap_shouldStripATrailingSemicolonBeforeQuoting() {
		assertEquals("SELECT * FROM (SELECT 1 FROM obs) cr_raw_preview LIMIT 25",
		    RawQueryGuard.wrap("SELECT 1 FROM obs;", 25));
	}
	
	@Test
	public void wrap_shouldKeepAtLeastOneRowEvenIfAskedForNone() {
		assertTrue(RawQueryGuard.wrap("SELECT 1 FROM obs", 0).endsWith("LIMIT 1"));
		assertTrue(RawQueryGuard.wrap("SELECT 1 FROM obs", -4).endsWith("LIMIT 1"));
	}
	
	@Test
	public void policy_shouldBeInspectableSoThePageCanStateIt() {
		assertNotNull(RawQueryGuard.forbiddenKeywords());
		assertTrue(RawQueryGuard.forbiddenKeywords().contains("delete"));
		assertTrue(RawQueryGuard.forbiddenKeywords().contains("information_schema"));
		assertEquals(2, RawQueryGuard.allowedStarts().size());
	}
	
	private static void assertRefused(String sql) {
		String reason = RawQueryGuard.reject(sql);
		assertNotNull("should have been refused: " + sql, reason);
		assertTrue("refusal should say what is wrong: " + reason,
		    reason.contains("not allowed") || reason.contains("Comments") || reason.contains("one statement")
		            || reason.contains("SELECT or WITH") || reason.contains("too long")
		            || reason.contains("Enter a SELECT"));
	}
}
