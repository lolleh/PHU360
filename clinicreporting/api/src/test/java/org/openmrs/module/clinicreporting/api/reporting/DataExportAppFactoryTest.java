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
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.openmrs.module.appframework.domain.Extension;
import org.openmrs.module.clinicreporting.api.model.DataExport;

/**
 * Tests the links the data-export factory offers to the stock Reports home page: one per export, in
 * the order the exports page gives them, pointing at the mirror the reporting module runs, and
 * never asking the app framework for anything it cannot answer.
 */
public class DataExportAppFactoryTest {
	
	@Test
	public void linksFor_shouldDescribeOneLinkPerExportInTheOrderItIsGiven() {
		List<Extension> links = DataExportAppFactory.linksFor(Arrays.asList(export("a", "First"), export("b", "Second")));
		
		assertEquals(2, links.size());
		assertEquals("First", links.get(0).getLabel());
		assertEquals("Second", links.get(1).getLabel());
		assertTrue(links.get(0).getOrder() < links.get(1).getOrder());
	}
	
	@Test
	public void linksFor_shouldOfferTheExtensionPointTheStockReportsPageReads() {
		Extension link = DataExportAppFactory.linksFor(Collections.singletonList(export("a", "First"))).get(0);
		
		assertEquals(DataExportAppFactory.EXTENSION_POINT_ID, link.getExtensionPointId());
		assertEquals(DataExportAppFactory.EXTENSION_ID_PREFIX + "a", link.getId());
		assertEquals("link", link.getType());
	}
	
	@Test
	public void linksFor_shouldPointAtTheMirrorOfTheExport() {
		Extension link = DataExportAppFactory.linksFor(Collections.singletonList(export("a", "First"))).get(0);
		
		assertEquals("reportingui/runReport.page?reportDefinition=" + ReportingDefinitionSync.reportUuid("a"),
		    link.getUrl());
	}
	
	@Test
	public void linksFor_shouldAskForAPrivilegeTheRunPageActuallyNeeds() {
		Extension link = DataExportAppFactory.linksFor(Collections.singletonList(export("a", "First"))).get(0);
		
		assertEquals(DataExportAppFactory.REQUIRED_PRIVILEGE, link.getRequiredPrivilege());
		assertFalse("a blank privilege would offer the link to everyone", link.getRequiredPrivilege().trim().isEmpty());
	}
	
	@Test
	public void linksFor_shouldStayAboveTheLinkReportinguiShips() {
		List<Extension> links = DataExportAppFactory
		        .linksFor(Arrays.asList(export("a", "First"), export("b", "Second"), export("c", "Third")));
		
		int previous = Integer.MIN_VALUE;
		for (Extension link : links) {
			assertTrue("orders must climb, or the page will not keep the given order", link.getOrder() > previous);
			assertTrue("the shipped data-export link is ordered 9999 and has to stay last", link.getOrder() < 9999);
			previous = link.getOrder();
		}
	}
	
	@Test
	public void linksFor_shouldOfferNothingWhenThereAreNoExports() {
		assertTrue(DataExportAppFactory.linksFor(Collections.<DataExport> emptyList()).isEmpty());
	}
	
	@Test
	public void getExtensions_shouldOfferNothingRatherThanFailWhenThereIsNoContext() {
		assertTrue(new DataExportAppFactory().getExtensions().isEmpty());
	}
	
	@Test
	public void refreshReportsPage_shouldNotPropagateAFailureWhenThereIsNoContext() {
		DataExportAppFactory.refreshReportsPage();
	}
	
	// ---------------------------------------------------------------- helpers
	
	private static DataExport export(String uuid, String name) {
		DataExport export = new DataExport();
		export.setUuid(uuid);
		export.setName(name);
		return export;
	}
}
