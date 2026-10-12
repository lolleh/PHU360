/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.clinicreporting.api.exception;

/**
 * Thrown when the caller supplied a report filter that cannot be executed, e.g. an inverted or
 * unbounded date range. Surfaces as HTTP 400.
 */
public class InvalidReportFilterException extends ReportException {
	
	private static final long serialVersionUID = 1L;
	
	public InvalidReportFilterException(String message) {
		super(message);
	}
}
