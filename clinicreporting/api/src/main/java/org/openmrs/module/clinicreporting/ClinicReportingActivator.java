/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.clinicreporting.api.service.DataExportService;
import org.openmrs.module.clinicreporting.api.service.TransformService;
import org.openmrs.module.clinicreporting.facility.FacilityReportingBootstrap;

/**
 * Contains the logic that is run every time this module is either started or shutdown.
 */
public class ClinicReportingActivator extends BaseModuleActivator {
	
	private static final Log log = LogFactory.getLog(ClinicReportingActivator.class);
	
	private final FacilityReportingBootstrap facilityReporting = new FacilityReportingBootstrap();
	
	/**
	 * @see org.openmrs.module.BaseModuleActivator#started()
	 */
	@Override
	public void started() {
		log.info("Clinic Reporting module started");
		warmReportMetadata();
		reconcileReportsPageMirrors();
		facilityReporting.start();
	}
	
	/**
	 * @see org.openmrs.module.BaseModuleActivator#stopped()
	 */
	@Override
	public void stopped() {
		facilityReporting.stop();
		log.info("Clinic Reporting module stopped");
	}
	
	/**
	 * Reads the database schema once at startup so the first report page served afterwards does not pay
	 * for it. Every report DAO shares one schema-metadata cache, and the JDBC call that fills it lists
	 * every table's columns in one pass; running it during startup moves that one-off cost off the
	 * first clinician's page load.
	 * <p>
	 * Best effort for the same reason as the mirror reconciliation: a page can always read the schema
	 * lazily if this fails, so a failed warm-up must not stop the module from starting.
	 */
	private void warmReportMetadata() {
		try {
			TransformService transformService = Context.getService(TransformService.class);
			if (transformService != null) {
				// sources() narrows the allowlist to the columns on this server, which reads and caches
				// the whole schema as a side effect.
				transformService.sources();
			}
		}
		catch (RuntimeException e) {
			log.warn("Could not pre-read the database schema at startup; the first report page will read it instead", e);
		}
	}
	
	/**
	 * Refreshes the reports-page mirror of every export that exists on this deployment. Best effort on
	 * purpose: keeping the module hashable is more important than a database cleanup, so nothing from
	 * here is allowed to make startup fail.
	 */
	private void reconcileReportsPageMirrors() {
		try {
			DataExportService service = Context.getService(DataExportService.class);
			if (service != null) {
				service.reconcileReportingMirrors();
			}
		}
		catch (RuntimeException e) {
			log.warn("Could not reconcile the reports-page mirrors at startup; exports will still save", e);
		}
	}
}
