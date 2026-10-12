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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests the shipped definitions: their count, which of them are actually offerable, that every
 * offerable one passes the very validation the live form runs, and that the withheld ones are
 * declared rather than quietly missing.
 */
public class DataExportDefaultsTest {
	
	@Test
	public void defaults_shouldDeclareEveryNameIncludingTheWithheldOnes() {
		List<DataExport> defaults = DataExportDefaults.defaults();
		assertEquals(26, defaults.size());
		
		List<String> names = new ArrayList<String>();
		for (DataExport export : defaults) {
			names.add(export.getName());
		}
		assertTrue(names.contains("Registrations"));
		assertTrue(names.contains("Users Data"));
		assertTrue(names.contains("User Roles"));
		assertTrue(names.contains("User Logins"));
		assertTrue(names.contains("All Patients Export"));
	}
	
	@Test
	public void seedable_shouldSkipOnlyTheThreeNamesWithNoAllowlistedSource() {
		List<DataExport> seedable = DataExportDefaults.seedable();
		assertEquals(23, seedable.size());
		for (DataExport export : seedable) {
			assertFalse(export.getName().equals("Users Data"));
			assertFalse(export.getName().equals("User Roles"));
			assertFalse(export.getName().equals("User Logins"));
			assertNotNull("definition \"" + export.getName() + "\" has no source at all", export.source());
		}
	}
	
	@Test
	public void unavailable_shouldBeTheThreeWithheldNamesThatSeedingSkips() {
		List<DataExport> unavailable = DataExportDefaults.unavailable();
		assertEquals(3, unavailable.size());
		assertEquals("the whole shipped list is the seedable ones plus the withheld ones",
		    DataExportDefaults.defaults().size(), DataExportDefaults.seedable().size() + unavailable.size());
		for (DataExport export : unavailable) {
			assertNull("a withheld name must have no source to read from", export.source());
			assertTrue("the reason has to be written next to the name",
			    export.getDescription().startsWith("Not available:"));
		}
	}
	
	@Test
	public void seedable_shouldHaveUniqueNamesAndSources() {
		List<DataExport> seedable = DataExportDefaults.seedable();
		for (int i = 0; i < seedable.size(); i++) {
			for (int j = i + 1; j < seedable.size(); j++) {
				assertFalse("duplicate name: " + seedable.get(i).getName(),
				    seedable.get(i).getName().equalsIgnoreCase(seedable.get(j).getName()));
			}
		}
	}
	
	@Test
	public void everySeedableDefinition_shouldPassItsOwnValidation() {
		// The strongest guarantee this class can give: not one shipped definition is something the form
		// itself would refuse. The allowlist, the caps and the bound-values rules all apply to these
		// the way they apply to anything typed by hand.
		for (DataExport definition : DataExportDefaults.seedable()) {
			String message = definition.validate();
			assertNull("definition " + definition.getName() + " does not validate: " + message, message);
		}
	}
	
	@Test
	public void named_shouldFindAByCaseInsensitiveNameAndMissAnUnknownOne() {
		assertNotNull(DataExportDefaults.named("vitals data"));
		assertNotNull(DataExportDefaults.named("NCD Program Data (SL)"));
		assertNull(DataExportDefaults.named("Not really a report"));
		assertNull(DataExportDefaults.named(null));
	}
	
	@Test
	public void copyOf_shouldNotShareItsCollectionsWithTheShippedDefinition() {
		DataExport shipped = DataExportDefaults.named("Vitals Data");
		int shippedColumns = shipped.getColumns().size();
		DataExport copy = DataExportDefaults.copyOf(shipped);
		copy.getColumns().clear();
		copy.getFilters().clear();
		assertEquals(shippedColumns, shipped.getColumns().size());
		assertFalse(shipped.getFilters().isEmpty());
	}
}
