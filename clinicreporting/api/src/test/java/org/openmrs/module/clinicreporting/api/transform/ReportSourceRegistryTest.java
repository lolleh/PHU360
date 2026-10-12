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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Tests the allowlist itself.
 * <p>
 * The allowlist is the sandbox's security boundary, and a boundary cannot be tested by exercising
 * it after the fact. These tests assert the shape of the list: that every identifier it contains is
 * plain, that the tables and columns it exists to withhold are genuinely absent, and that narrowing
 * to a real database can only ever remove.
 */
public class ReportSourceRegistryTest {
	
	/** Withheld outright: account data, direct identifiers and the tables that re-identify staff. */
	private static final String[] WITHHELD_TABLES = { "users", "user_role", "user_password", "user_property",
	        "login_failure_count", "authentication_event_log", "person_name", "person_address", "patient_identifier" };
	
	@Test
	public void registry_shouldOfferSomethingToWorkWith() {
		Map<String, ReportSourceRegistry.Source> sources = ReportSourceRegistry.all();
		assertTrue("expected a populated allowlist", sources.size() > 20);
		for (Map.Entry<String, ReportSourceRegistry.Source> entry : sources.entrySet()) {
			assertEquals("key and table must agree", entry.getKey(), entry.getValue().getTable());
			assertTrue(entry.getValue().getColumns().size() > 0);
			assertNotNull(entry.getValue().getLabel());
			assertNotNull(entry.getValue().getCategory());
		}
	}
	
	@Test
	public void everyIdentifierShouldBePlain() {
		// This is the property the SQL builder relies on: nothing in the list can carry SQL.
		for (ReportSourceRegistry.Source source : ReportSourceRegistry.all().values()) {
			assertTrue("not a plain table name: " + source.getTable(),
			    ReportSourceRegistry.isPlainIdentifier(source.getTable()));
			for (String column : source.getColumns()) {
				assertTrue("not a plain column name: " + source.getTable() + "." + column,
				    ReportSourceRegistry.isPlainIdentifier(column));
			}
		}
	}
	
	@Test
	public void withheldTables_shouldNotBePresent() {
		for (String table : WITHHELD_TABLES) {
			assertNull("must not be readable: " + table, ReportSourceRegistry.find(table));
		}
	}
	
	@Test
	public void withheldColumns_shouldNotBeOffered() {
		// person_name and person_address are withheld as tables; the temptation is to offer them as
		// columns of person, which would be the same leak with a different spelling.
		ReportSourceRegistry.Source person = ReportSourceRegistry.find("person");
		assertFalse("person must not expose a name", person.allows("name"));
		assertFalse("person must not expose an address", person.allows("address1"));
		
		ReportSourceRegistry.Source patient = ReportSourceRegistry.find("patient");
		assertFalse("patient must not expose an identifier", patient.allows("identifier"));
		
		// obs and note hold free text, which is where a clinician ends up typing a patient's name.
		ReportSourceRegistry.Source obs = ReportSourceRegistry.find("obs");
		assertFalse("obs must not expose its comment column", obs.allows("comments"));
		ReportSourceRegistry.Source note = ReportSourceRegistry.find("note");
		assertFalse("note must not expose its text", note.allows("text"));
		
		// provider.name is a clinician's name: an indicator built on it is a staff directory.
		ReportSourceRegistry.Source provider = ReportSourceRegistry.find("provider");
		assertFalse("provider must not expose a name", provider.allows("name"));
		assertFalse("provider must not expose an identifier", provider.allows("identifier"));
	}
	
	@Test
	public void provenanceColumns_shouldStayAvailable() {
		// Withholding user tables removes the easy way to answer "who recorded this". The recorded-by
		// column on the row itself is the substitute, so it has to remain readable.
		assertTrue(ReportSourceRegistry.find("obs").allows("creator"));
		assertTrue(ReportSourceRegistry.find("obs").allows("date_created"));
		assertTrue(ReportSourceRegistry.find("encounter").allows("creator"));
		assertTrue(ReportSourceRegistry.find("orders").allows("creator"));
	}
	
	@Test
	public void find_shouldBeCaseSensitiveSoTheNameMatchesTheStatement() {
		// The builder emits the table name straight into SQL, so the lookup has to be exact rather than
		// "helpful".
		assertNull(ReportSourceRegistry.find("OBS"));
		assertNull(ReportSourceRegistry.find("obs "));
		assertNull(ReportSourceRegistry.find(null));
	}
	
	@Test
	public void allowsColumn_shouldRequireBothTheTableAndTheColumn() {
		assertTrue(ReportSourceRegistry.allowsColumn("obs", "obs_id"));
		assertFalse(ReportSourceRegistry.allowsColumn("obs", "user_id"));
		assertFalse(ReportSourceRegistry.allowsColumn("users", "user_id"));
		assertFalse(ReportSourceRegistry.allowsColumn(null, "obs_id"));
	}
	
	@Test
	public void isPlainIdentifier_shouldRejectAnythingThatIsNotAnIdentifier() {
		assertTrue(ReportSourceRegistry.isPlainIdentifier("obs_id"));
		assertTrue(ReportSourceRegistry.isPlainIdentifier("Obs2"));
		assertFalse(ReportSourceRegistry.isPlainIdentifier(null));
		assertFalse(ReportSourceRegistry.isPlainIdentifier(""));
		assertFalse(ReportSourceRegistry.isPlainIdentifier("2obs"));
		assertFalse(ReportSourceRegistry.isPlainIdentifier("obs id"));
		assertFalse(ReportSourceRegistry.isPlainIdentifier("obs.id"));
		assertFalse(ReportSourceRegistry.isPlainIdentifier("obs`"));
		assertFalse(ReportSourceRegistry.isPlainIdentifier("obs;"));
		assertFalse(ReportSourceRegistry.isPlainIdentifier("*"));
	}
	
	@Test
	public void narrowed_shouldKeepOnlyColumnsTheDatabaseHas() {
		ReportSourceRegistry.Source obs = ReportSourceRegistry.find("obs");
		Set<String> present = new LinkedHashSet<String>(Arrays.asList("obs_id", "concept_id"));
		
		ReportSourceRegistry.Source narrowed = obs.narrowed(present);
		assertNotNull(narrowed);
		assertEquals(2, narrowed.getColumns().size());
		assertTrue(narrowed.allows("obs_id"));
		assertTrue(narrowed.allows("concept_id"));
		assertFalse(narrowed.allows("value_text"));
		// The original is untouched: narrowing is a view for the picker, not a mutation.
		assertTrue(obs.allows("value_text"));
	}
	
	@Test
	public void narrowed_shouldMatchColumnsRegardlessOfCase() {
		// PostgreSQL reports lower-case names and MySQL preserves the declaration, so the intersection
		// has to be case-insensitive while the emitted SQL keeps the registry's spelling.
		ReportSourceRegistry.Source narrowed = ReportSourceRegistry.find("obs")
		        .narrowed(new LinkedHashSet<String>(Arrays.asList("OBS_ID", "Value_Text")));
		assertEquals(2, narrowed.getColumns().size());
	}
	
	@Test
	public void narrowed_shouldReturnNullForATableThisServerDoesNotHave() {
		// Absence is a refusal, not an empty picker: the page then says the table is unavailable rather
		// than offering a source that will fail.
		assertNull(ReportSourceRegistry.find("obs").narrowed(null));
		assertNull(ReportSourceRegistry.find("obs").narrowed(new LinkedHashSet<String>()));
	}
	
	@Test
	public void narrowed_shouldNeverAddAColumn() {
		Set<String> everything = new LinkedHashSet<String>();
		ReportSourceRegistry.Source obs = ReportSourceRegistry.find("obs");
		for (ReportSourceRegistry.Source source : ReportSourceRegistry.all().values()) {
			everything.addAll(source.getColumns());
		}
		ReportSourceRegistry.Source narrowed = obs.narrowed(everything);
		Iterator<String> columns = narrowed.getColumns().iterator();
		while (columns.hasNext()) {
			String column = columns.next();
			assertTrue("narrowing added " + column, obs.allows(column));
		}
	}
}
