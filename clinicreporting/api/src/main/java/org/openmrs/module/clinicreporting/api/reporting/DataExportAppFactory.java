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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.appframework.AppFrameworkActivator;
import org.openmrs.module.appframework.domain.AppDescriptor;
import org.openmrs.module.appframework.domain.AppTemplate;
import org.openmrs.module.appframework.domain.Extension;
import org.openmrs.module.appframework.factory.AppFrameworkFactory;
import org.openmrs.module.clinicreporting.api.model.DataExport;
import org.openmrs.module.clinicreporting.api.service.DataExportService;
import org.springframework.stereotype.Component;

/**
 * Publishes every saved, active export as a link on the reportingui Reports home page.
 * <p>
 * The Reports home page is a stock page: it renders whatever extensions it finds under
 * {@code org.openmrs.module.reportingui.reports.dataexport} and nothing else, so the only way to
 * put an export on it is to be an app framework factory that offers one {@link Extension} per
 * export. Each link points at the reporting module's run page for the mirror of the export, so a
 * link opens the same definition the exports page saves, and a retired export has no link because
 * it is not in {@link DataExportService#getExports()}.
 * <p>
 * The privilege is {@code View Reports}, not the Reports app's own privilege: opening
 * {@code runReport.page} needs the definition service to accept the read, and an installation that
 * grants the app privilege without granting the read privilege would render links that fail. A
 * blank privilege would be worse still, because the app framework treats a blank privilege as
 * visible to everyone.
 * <p>
 * Registered as a Spring bean by core's component scan, which is the same mechanism the app
 * framework's own factories are found by - they are annotation-scanned classes, not XML beans. The
 * app framework collects factories through {@code Context.getRegisteredComponents}, so this class
 * has to be a bean in that same context to be asked for its extensions.
 */
@Component
public class DataExportAppFactory implements AppFrameworkFactory {
	
	/** The extension point the stock Reports home page reads its data-export list from. */
	public static final String EXTENSION_POINT_ID = "org.openmrs.module.reportingui.reports.dataexport";
	
	/** Prefix keeping these extension ids out of the way of every other module's ids. */
	static final String EXTENSION_ID_PREFIX = "clinicreporting.dataExport.";
	
	/** The privilege a reader needs to follow one of these links and get an answer. */
	static final String REQUIRED_PRIVILEGE = "View Reports";
	
	/**
	 * reportingui's own data-export link is ordered 9999, so starting the exports here lists them above
	 * it without touching another module's ordering.
	 */
	static final int FIRST_ORDER = 100;
	
	private static final Log log = LogFactory.getLog(DataExportAppFactory.class);
	
	@Override
	public List<AppDescriptor> getAppDescriptors() {
		return Collections.emptyList();
	}
	
	@Override
	public List<AppTemplate> getAppTemplates() {
		return Collections.emptyList();
	}
	
	/**
	 * Builds one link per active export, in the order the exports page lists them.
	 * <p>
	 * Never throws: the app framework clears every extension it knows about before asking each factory
	 * to repopulate them, so a factory that fails takes the whole Reports page down with it rather than
	 * being skipped.
	 *
	 * @return the links, or an empty list when the exports cannot be read
	 */
	@Override
	public List<Extension> getExtensions() {
		List<DataExport> exports;
		try {
			DataExportService service = Context.getService(DataExportService.class);
			exports = service == null ? Collections.<DataExport> emptyList() : service.getExports();
		}
		catch (RuntimeException e) {
			log.warn("Could not read the saved data exports for the Reports page", e);
			return Collections.emptyList();
		}
		return linksFor(exports);
	}
	
	/**
	 * Builds one link per export, in the order the exports page lists them. Package-private because a
	 * test can only reach it with an OpenMRS context behind {@link #getExtensions()}.
	 *
	 * @param exports the active exports, in page order
	 * @return one link per export
	 */
	static List<Extension> linksFor(List<DataExport> exports) {
		List<Extension> extensions = new ArrayList<Extension>(exports.size());
		for (int index = 0; index < exports.size(); index++) {
			extensions.add(linkFor(exports.get(index), FIRST_ORDER + index));
		}
		return extensions;
	}
	
	/**
	 * Builds the Reports page link for one export. The url is relative because the app framework
	 * prepends the context path when it renders it.
	 *
	 * @param export the export to link to
	 * @param order the position the link should take on the page
	 * @return the extension the Reports home page renders
	 */
	private static Extension linkFor(DataExport export, int order) {
		Extension extension = new Extension();
		extension.setId(EXTENSION_ID_PREFIX + export.getUuid());
		extension.setExtensionPointId(EXTENSION_POINT_ID);
		extension.setType("link");
		extension.setLabel(export.getName());
		extension.setUrl(
		    "reportingui/runReport.page?reportDefinition=" + ReportingDefinitionSync.reportUuid(export.getUuid()));
		extension.setRequiredPrivilege(REQUIRED_PRIVILEGE);
		extension.setOrder(order);
		return extension;
	}
	
	/**
	 * Rebuilds the app framework's extension registry, which is what makes a save, a retire or a
	 * reactivation show up on the Reports page without restarting the server. The app framework only
	 * asks its factories for extensions while it is registering them, so a new export has no way to
	 * appear until this runs.
	 * <p>
	 * Never throws: an export that was saved must not fail because the Reports page could not be
	 * rebuilt, and the app framework logs a failure of its own if a factory misbehaves.
	 */
	public static void refreshReportsPage() {
		try {
			new AppFrameworkActivator().contextRefreshed();
		}
		catch (RuntimeException e) {
			log.warn("Could not refresh the Reports page links after a data export change; they will "
			        + "be rebuilt when the server next starts",
			    e);
		}
	}
}
